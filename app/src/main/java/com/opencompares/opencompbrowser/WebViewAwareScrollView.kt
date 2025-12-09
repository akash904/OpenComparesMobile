package com.opencompares.opencompbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ScrollView

class WebViewAwareScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    init {
        // Ensure scrollbar is always visible
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Check if the touch event is over a WebView
        val child = findChildViewUnder(ev.x, ev.y)
        
        if (child is WebView || isDescendantOfWebView(child)) {
            // Don't intercept - let WebView handle the touch
            return false
        }
        
        // Intercept touch events for grid scrolling
        return super.onInterceptTouchEvent(ev)
    }
    
    private fun findChildViewUnder(x: Float, y: Float): android.view.View? {
        // Account for scroll offset
        val scrollY = this.scrollY
        val adjustedY = y + scrollY
        
        val childCount = childCount
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            val childLeft = child.left.toFloat()
            val childTop = child.top.toFloat()
            val childRight = child.right.toFloat()
            val childBottom = child.bottom.toFloat()
            
            if (x >= childLeft && x < childRight && adjustedY >= childTop && adjustedY < childBottom) {
                val found = findViewInChild(child, x - childLeft, adjustedY - childTop)
                return found ?: child
            }
        }
        return null
    }
    
    private fun findViewInChild(parent: android.view.View, x: Float, y: Float): android.view.View? {
        if (parent is WebView) {
            return parent
        }
        
        if (parent is ViewGroup) {
            val childCount = parent.childCount
            for (i in childCount - 1 downTo 0) {
                val child = parent.getChildAt(i)
                val childLeft = child.left.toFloat()
                val childTop = child.top.toFloat()
                val childRight = child.right.toFloat()
                val childBottom = child.bottom.toFloat()
                
                if (x >= childLeft && x < childRight && y >= childTop && y < childBottom) {
                    val found = findViewInChild(child, x - childLeft, y - childTop)
                    return found ?: child
                }
            }
        }
        
        return parent
    }
    
    private fun isDescendantOfWebView(view: android.view.View?): Boolean {
        if (view == null) return false
        if (view is WebView) return true
        
        var parent = view.parent
        while (parent is android.view.View) {
            if (parent is WebView) return true
            parent = parent.parent
        }
        return false
    }
}

