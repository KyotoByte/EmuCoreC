package com.sbro.emucorec.ui.library

import android.content.Context
import com.sbro.emucorec.ui.common.loadUrlImageBitmap
import com.sbro.emucorec.ui.common.preloadLocalImage
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class LibraryArtworkRequest(val coverUrl: String?, val localPath: String?)

private val artworkPrefetchLoads = Semaphore(1)

internal suspend fun prefetchLibraryArtwork(
    context: Context,
    request: LibraryArtworkRequest,
    decodeEdgePx: Int
) {
    artworkPrefetchLoads.withPermit {
        if (!request.coverUrl.isNullOrBlank()) {
            loadUrlImageBitmap(context, request.coverUrl, request.localPath, decodeEdgePx)
        } else {
            request.localPath?.takeIf { it.isNotBlank() }?.let { preloadLocalImage(it) }
        }
    }
}
