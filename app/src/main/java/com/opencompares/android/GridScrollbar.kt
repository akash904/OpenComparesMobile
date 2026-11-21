package com.opencompares.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A draggable scrollbar for the marketplace grid.
 *
 * WebView panes keep the touches that land on them, so this strip gives the
 * grid a scroll handle that always works, whatever the pages inside do.
 * Drag the thumb to scroll, or tap the track to jump there.
 * Place it beside the scrolling content with the same height as the viewport.
 */
@Composable
fun GridScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    var trackHeightPx by remember { mutableIntStateOf(0) }
    val minThumbPx = with(LocalDensity.current) { 32.dp.toPx() }
    val scope = rememberCoroutineScope()

    // Thumb size and travel, computed from the current scroll extent.
    fun thumbHeightPx(): Float {
        val viewport = trackHeightPx.toFloat()
        val content = viewport + scrollState.maxValue
        return (viewport * viewport / content).coerceIn(minThumbPx, viewport)
    }
    fun thumbTravelPx(): Float = (trackHeightPx - thumbHeightPx()).coerceAtLeast(1f)

    val isScrollable = trackHeightPx > 0 &&
        scrollState.maxValue in 1 until Int.MAX_VALUE

    val dragState = rememberDraggableState { delta ->
        // Moving the thumb by its full travel scrolls the whole content.
        scrollState.dispatchRawDelta(delta * scrollState.maxValue / thumbTravelPx())
    }

    Box(
        modifier = modifier
            .width(20.dp)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = isScrollable,
                onDragStarted = { scrollState.stopScroll() }
            )
            .pointerInput(scrollState) {
                detectTapGestures { tap ->
                    if (scrollState.maxValue <= 0 || scrollState.maxValue == Int.MAX_VALUE) return@detectTapGestures
                    // Center the thumb on the tapped point.
                    val fraction = ((tap.y - thumbHeightPx() / 2f) / thumbTravelPx()).coerceIn(0f, 1f)
                    scope.launch { scrollState.animateScrollTo((fraction * scrollState.maxValue).roundToInt()) }
                }
            }
    ) {
        if (isScrollable) {
            val density = LocalDensity.current
            val thumbHeight = thumbHeightPx()

            // Faint track so the handle is discoverable even before scrolling.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    // Read the scroll position at layout time so scrolling skips recomposition.
                    .offset {
                        val thumbTop = scrollState.value.toFloat() / scrollState.maxValue * thumbTravelPx()
                        IntOffset(0, thumbTop.roundToInt())
                    }
                    .width(8.dp)
                    .height(with(density) { thumbHeight.toDp() })
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}
