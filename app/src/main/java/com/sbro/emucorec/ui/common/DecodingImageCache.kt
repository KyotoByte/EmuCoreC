package com.sbro.emucorec.ui.common

internal class DecodingImageCache<T : Any>(
    private val read: (String) -> T?,
    private val write: (String, T) -> Unit,
    private val evict: () -> Unit,
    private val decode: (String) -> T?
) {
    private var generation = 0L

    @Synchronized
    fun get(path: String?): T? = path?.let(read)

    fun load(path: String?): T? {
        if (path.isNullOrBlank()) return null
        val loadGeneration = synchronized(this) {
            read(path)?.let { return it }
            generation
        }
        // Composition also reads this cache. Never hold its monitor during disk IO.
        val decoded = runCatching { decode(path) }.getOrNull() ?: return null
        return synchronized(this) {
            if (generation != loadGeneration) decoded
            else read(path) ?: decoded.also { write(path, it) }
        }
    }

    @Synchronized
    fun clear() {
        generation++
        evict()
    }
}
