package de.metaviewsoft.chordprogressionhelper.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * Click listener with press-and-hold auto-repeat: one tap = one [onStep]; keeping the button
 * pressed fires [onStep] repeatedly after [initialDelayMs] (default 1s), every [repeatIntervalMs].
 * Used by all tempo/BPM arrow buttons.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.setAutoRepeatOnClickListener(
    initialDelayMs: Long = 1000L,
    repeatIntervalMs: Long = 60L,
    onStep: () -> Unit,
) {
    val repeater = object : Runnable {
        override fun run() {
            onStep()
            postDelayed(this, repeatIntervalMs)
        }
    }
    // Accessibility services trigger performClick() directly (no touch events).
    setOnClickListener { onStep() }
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                onStep()
                v.postDelayed(repeater, initialDelayMs)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(repeater)
                v.isPressed = false
                true
            }
            else -> true
        }
    }
}
