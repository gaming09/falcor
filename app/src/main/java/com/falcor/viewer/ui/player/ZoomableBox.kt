package com.falcor.viewer.ui.player

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Pinch-zoom + pan for live/clip surfaces (WebView, VLC, OkHttp preview, fullscreen).
 *
 * Hosts [content] in a [ComposeView] inside a [FrameLayout] that intercepts multi-touch for
 * scale/pan while dispatching single-finger events to children so WebView/VLC still get taps.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    content: @Composable () -> Unit
) {
    val contentState = rememberUpdatedState(content)
    val lifecycleOwner = LocalLifecycleOwner.current
    val vmStoreOwner = LocalViewModelStoreOwner.current
    val savedOwner = lifecycleOwner as? SavedStateRegistryOwner

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val composeView = ComposeView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
            }
            object : FrameLayout(ctx) {
                private var scaleFactor = 1f
                private var posX = 0f
                private var posY = 0f
                private var lastFocusX = 0f
                private var lastFocusY = 0f
                private var multiTouchActive = false

                private val scaleListener =
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                            lastFocusX = detector.focusX
                            lastFocusY = detector.focusY
                            multiTouchActive = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }

                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            scaleFactor =
                                (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                            if (scaleFactor > 1.01f) {
                                posX += detector.focusX - lastFocusX
                                posY += detector.focusY - lastFocusY
                            } else {
                                posX = 0f
                                posY = 0f
                            }
                            lastFocusX = detector.focusX
                            lastFocusY = detector.focusY
                            applyTransform()
                            return true
                        }

                        override fun onScaleEnd(detector: ScaleGestureDetector) {
                            if (scaleFactor <= 1.01f) {
                                scaleFactor = 1f
                                posX = 0f
                                posY = 0f
                                applyTransform()
                            }
                        }
                    }

                private val scaleDetector = ScaleGestureDetector(ctx, scaleListener)

                init {
                    setViewTreeLifecycleOwner(lifecycleOwner)
                    if (vmStoreOwner != null) {
                        setViewTreeViewModelStoreOwner(vmStoreOwner)
                    }
                    if (savedOwner != null) {
                        setViewTreeSavedStateRegistryOwner(savedOwner)
                    }
                    addView(composeView)
                    composeView.setContent { contentState.value.invoke() }
                }

                private fun applyTransform() {
                    val w = width.toFloat().coerceAtLeast(1f)
                    val h = height.toFloat().coerceAtLeast(1f)
                    composeView.pivotX = w / 2f
                    composeView.pivotY = h / 2f
                    composeView.scaleX = scaleFactor
                    composeView.scaleY = scaleFactor
                    composeView.translationX = posX
                    composeView.translationY = posY
                }

                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    val before = scaleDetector.isInProgress
                    scaleDetector.onTouchEvent(ev)
                    val multi = ev.pointerCount >= 2 || scaleDetector.isInProgress || before ||
                        multiTouchActive

                    when (ev.actionMasked) {
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            multiTouchActive = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                            if (scaleFactor <= 1.01f) {
                                scaleFactor = 1f
                                posX = 0f
                                posY = 0f
                                applyTransform()
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            if (ev.pointerCount <= 2) {
                                multiTouchActive = scaleDetector.isInProgress
                            }
                        }
                    }

                    if (multi) {
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }
        },
        update = { frame ->
            val cv = frame.getChildAt(0) as? ComposeView ?: return@AndroidView
            cv.setContent { contentState.value.invoke() }
        }
    )
}
