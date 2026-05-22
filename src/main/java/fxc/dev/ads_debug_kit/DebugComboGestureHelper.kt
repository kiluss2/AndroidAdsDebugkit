package fxc.dev.ads_debug_kit

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.abs

class DebugComboGestureHelper(
    private val comboTimeoutMs: Long = 3_000L,
    private val velocityThreshold: Float = 500f,
    private val translationThreshold: Float = 50f
) {
    private enum class ComboState {
        IDLE,
        SWIPE_DOWN,
        DOUBLE_TAP
    }

    private val handler = Handler(Looper.getMainLooper())
    private var comboState = ComboState.IDLE
    private var downY = 0f
    private var downTime = 0L

    fun setup(view: View, onCompleted: (() -> Unit)? = null) {
        view.isClickable = true
        view.isFocusable = true

        val gestureDetector = GestureDetector(
            view.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleDoubleTap()
                    return true
                }
            }
        )

        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    downTime = event.eventTime
                }

                MotionEvent.ACTION_UP -> {
                    val translationY = event.y - downY
                    val duration = (event.eventTime - downTime).coerceAtLeast(1L)
                    val velocityY = translationY / duration * 1_000f
                    if (abs(velocityY) > velocityThreshold && abs(translationY) > translationThreshold) {
                        if (velocityY > 0 && translationY > 0) {
                            handleSwipeDown()
                        } else if (velocityY < 0 && translationY < 0) {
                            handleSwipeUp(view, onCompleted)
                        }
                    }
                }
            }
            true
        }
    }

    private fun handleSwipeDown() {
        if (comboState == ComboState.IDLE) {
            comboState = ComboState.SWIPE_DOWN
            startComboTimer()
        } else {
            resetCombo()
        }
    }

    private fun handleDoubleTap() {
        if (comboState == ComboState.SWIPE_DOWN) {
            comboState = ComboState.DOUBLE_TAP
        } else {
            resetCombo()
        }
    }

    private fun handleSwipeUp(view: View, onCompleted: (() -> Unit)?) {
        if (comboState == ComboState.DOUBLE_TAP) {
            resetCombo()
            AdsDebugKit.setDebugEnabled(true)
            Toast.makeText(view.context, "Debug mode enabled", Toast.LENGTH_SHORT).show()
            onCompleted?.invoke()
        } else {
            resetCombo()
        }
    }

    private fun startComboTimer() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ resetCombo() }, comboTimeoutMs)
    }

    private fun resetCombo() {
        comboState = ComboState.IDLE
        handler.removeCallbacksAndMessages(null)
    }
}
