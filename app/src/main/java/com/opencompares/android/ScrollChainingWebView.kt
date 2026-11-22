package com.opencompares.android

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/** Where a pane sends the drags its page cannot use, usually the grid's scroll state. */
interface ScrollChainTarget {
    /** A finger touched the pane. Stop any fling the grid is still running. */
    fun onTouchDown()

    /** Finger movement in px the page did not use. Positive means the finger moved down. */
    fun onUnconsumedDrag(fingerDy: Float)

    /** Release velocity in px/s of a chained drag. Positive means the finger moved down. */
    fun onUnconsumedFling(fingerVelocityY: Float)
}

/**
 * A WebView that passes vertical drags it cannot use on to its container.
 *
 * Panes sit inside a scrolling grid but always keep the touch for themselves,
 * so that page scrolling, taps and carousels work. Without help the grid could
 * then only be scrolled from the gaps between panes. This view watches each
 * one-finger vertical drag: once the page is already at its top or bottom in
 * the drag direction, the remaining movement and the release velocity go to
 * [chainTarget].
 *
 * Pages that never scroll the document itself, such as apps that scroll an
 * inner element, report no scroll range in either direction. Chaining is
 * skipped for those so the page and the grid never move together. The grid's
 * scrollbar still works for them.
 */
open class ScrollChainingWebView(context: Context) : WebView(context) {

    /** Receives leftover drags. Null means the page keeps every drag, as in fullscreen. */
    var chainTarget: ScrollChainTarget? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawY = 0f
    private var isVerticalDrag = false
    private var isHorizontalDrag = false
    private var isMultiTouch = false
    private var chainedThisGesture = false
    private var velocityTracker: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        chainTarget?.let { trackGesture(event, it) }
        return super.onTouchEvent(event)
    }

    private fun trackGesture(event: MotionEvent, target: ScrollChainTarget) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target.onTouchDown()
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawY = event.rawY
                isVerticalDrag = false
                isHorizontalDrag = false
                isMultiTouch = false
                chainedThisGesture = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { addMovement(it, event) }
            }

            MotionEvent.ACTION_POINTER_DOWN -> isMultiTouch = true

            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch) return
                velocityTracker?.let { addMovement(it, event) }

                // Lock the gesture direction once it passes the touch slop.
                if (!isVerticalDrag && !isHorizontalDrag) {
                    val totalDx = abs(event.rawX - downRawX)
                    val totalDy = abs(event.rawY - downRawY)
                    if (totalDx > touchSlop || totalDy > touchSlop) {
                        if (totalDy > totalDx) isVerticalDrag = true else isHorizontalDrag = true
                    }
                }

                // Raw screen coordinates stay correct while the grid moves this view.
                val dy = event.rawY - lastRawY
                lastRawY = event.rawY
                if (!isVerticalDrag || dy == 0f) return

                val canScrollUp = canScrollVertically(-1)
                val canScrollDown = canScrollVertically(1)
                if (!canScrollUp && !canScrollDown) return

                // Finger moving down reveals content above, which needs scroll room upward.
                val pageCanTakeIt = if (dy > 0) canScrollUp else canScrollDown
                if (!pageCanTakeIt) {
                    chainedThisGesture = true
                    target.onUnconsumedDrag(dy)
                }
            }

            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                if (chainedThisGesture && !isMultiTouch && tracker != null) {
                    addMovement(tracker, event)
                    tracker.computeCurrentVelocity(1000)
                    target.onUnconsumedFling(tracker.yVelocity)
                }
                recycleTracker()
            }

            MotionEvent.ACTION_CANCEL -> recycleTracker()
        }
    }

    /** Feeds the tracker screen coordinates so velocity ignores this view moving. */
    private fun addMovement(tracker: VelocityTracker, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
