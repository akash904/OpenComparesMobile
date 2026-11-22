package com.opencompares.android

import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.opencompares.android.ui.CompareScreen
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var scaleDetector: ScaleGestureDetector
    private val globalScaleState = mutableStateOf(1f)
    private val globalTranslateXState = mutableStateOf(0f)
    private val globalTranslateYState = mutableStateOf(0f)
    private var lastPanCentroid: PointF? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (globalScaleState.value * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                if (next != globalScaleState.value) globalScaleState.value = next
                return true
            }
        })
        setContent { App(globalScaleState, globalTranslateXState, globalTranslateYState) }
    }

    // Two-finger pinch and pan zoom the whole app; one-finger touches pass through untouched.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    lastPanCentroid = computeCentroid(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    val current = computeCentroid(ev)
                    val last = lastPanCentroid ?: current
                    val dx = current.x - last.x
                    val dy = current.y - last.y
                    if (dx != 0f || dy != 0f) {
                        globalTranslateXState.value += dx
                        globalTranslateYState.value += dy
                    }
                    lastPanCentroid = current
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    lastPanCentroid = null
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun computeCentroid(ev: MotionEvent): PointF {
        var sumX = 0f
        var sumY = 0f
        val count = ev.pointerCount
        for (i in 0 until count) {
            sumX += ev.getX(i)
            sumY += ev.getY(i)
        }
        return PointF(sumX / count, sumY / count)
    }
}

@Composable
fun App(
    globalScaleState: MutableState<Float>,
    globalTranslateXState: MutableState<Float>,
    globalTranslateYState: MutableState<Float>
) {
    MaterialTheme {
        val globalScale by globalScaleState
        val translateX by globalTranslateXState
        val translateY by globalTranslateYState

        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = globalScale
                        scaleY = globalScale
                        translationX = translateX
                        translationY = translateY
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
            ) {
                CompareScreen()
            }

            // Floating reset button: visible only when scale/pan deviates from defaults
            val hasTransform = (abs(globalScale - 1f) > 0.01f) || (abs(translateX) > 1f) || (abs(translateY) > 1f)
            if (hasTransform) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            globalScaleState.value = 1f
                            globalTranslateXState.value = 0f
                            globalTranslateYState.value = 0f
                        }
                    ) {
                        Text("Reset view")
                    }
                }
            }
        }
    }
}
