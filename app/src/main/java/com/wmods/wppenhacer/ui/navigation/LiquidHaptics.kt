package com.wmods.wppenhacer.ui.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Tactile Haptic Feedback engine specifically tuned for the Liquid Navigation Bar.
 * Provides micro-ticks when dragging across tabs, snap-clicks when landing,
 * and boundary bumps when pulling against spring limits.
 */
class LiquidHaptics(
    private val context: Context,
    private val view: View
) {
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Subtle micro-tick when finger touches down or glides over a tab boundary.
     * Feels like passing over a notch/detent in a mechanical wheel.
     */
    fun tick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator?.hasVibrator() == true) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } catch (_: Exception) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } catch (_: Exception) {}
        }
    }

    /**
     * Crisp, firm click when the liquid bubble snaps into a tab on release,
     * or when tapping a tab directly.
     */
    fun click() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator?.hasVibrator() == true) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {}
        }
    }

    /**
     * Boundary thump when pulling the liquid bubble beyond the edge limits.
     */
    fun boundary() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator?.hasVibrator() == true) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } catch (_: Exception) {
            tick()
        }
    }
}

@Composable
fun rememberLiquidHaptics(): LiquidHaptics {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) {
        LiquidHaptics(context, view)
    }
}
