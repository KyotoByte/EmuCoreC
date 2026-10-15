package net.rpcsx

import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class NativeProgress(
    val id: Long,
    val value: Long = 0,
    val maximum: Long = 0,
    val message: String? = null,
    val failed: Boolean = false,
    val completed: Boolean = false,
)

/** Receives long-running install/compile progress callbacks from RPCSX. */
class ProgressRepository {
    companion object {
        private val nextId = AtomicLong(1)
        private val mutableProgress = MutableStateFlow<Map<Long, NativeProgress>>(emptyMap())
        private val listeners = ConcurrentHashMap<Long, (NativeProgress) -> Unit>()
        private val lock = Any()

        val progress: StateFlow<Map<Long, NativeProgress>> = mutableProgress

        fun create(listener: ((NativeProgress) -> Unit)? = null): Long {
            val id = nextId.getAndIncrement()
            synchronized(lock) {
                mutableProgress.value = mutableProgress.value + (id to NativeProgress(id))
                if (listener != null) listeners[id] = listener
            }
            return id
        }

        fun cancel(id: Long) {
            synchronized(lock) {
                listeners.remove(id)
                mutableProgress.value = mutableProgress.value - id
            }
            GameRepository.clearProgress(id)
        }

        fun remove(id: Long) = cancel(id)

        @Keep
        @JvmStatic
        fun onProgressEvent(id: Long, value: Long, maximum: Long, message: String?): Boolean =
            update(id, value, maximum, message, completed = false)

        @Keep
        @JvmStatic
        fun onProgressCompleted(id: Long, value: Long, maximum: Long, message: String?): Boolean =
            update(id, value, maximum, message, completed = true)

        private fun update(id: Long, value: Long, maximum: Long, message: String?, completed: Boolean): Boolean {
            val failed = value < 0
            val succeeded = completed && !failed
            val update = NativeProgress(
                id = id,
                value = value.coerceAtLeast(0),
                maximum = maximum.coerceAtLeast(0),
                message = message,
                failed = failed,
                completed = succeeded,
            )
            val listener = synchronized(lock) {
                // Check and update while holding the same lock. A cancellation
                // between two separate critical sections used to let a late
                // native callback add the removed operation back to the map.
                if (id !in mutableProgress.value) return false
                mutableProgress.value = mutableProgress.value + (id to update)
                listeners[id]
            }
            listener?.invoke(update)
            if (failed || succeeded) cancel(id)
            return true
        }
    }
}
