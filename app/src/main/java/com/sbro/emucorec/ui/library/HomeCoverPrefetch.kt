package com.sbro.emucorec.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Image-only lookahead for the library's list and row-grid LazyColumn. */
@Composable
internal fun HomeCoverPrefetch(
    listState: LazyListState,
    window: HomeCoverPrefetchWindow,
    enabled: Boolean,
    load: suspend (String) -> Unit
) {
    LaunchedEffect(listState, window, enabled, load) {
        if (!enabled) return@LaunchedEffect
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        var forward = true
        snapshotFlow {
            Triple(
                listState.layoutInfo.visibleItemsInfo.map { it.key.toString() },
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }.map { (keys, index, offset) ->
            if (index != previousIndex || offset != previousOffset) {
                forward = index > previousIndex || (index == previousIndex && offset > previousOffset)
                previousIndex = index
                previousOffset = offset
            }
            window.paths(keys, forward)
        }.distinctUntilChanged().collectLatest { paths ->
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                load(path)
            }
        }
    }
}
