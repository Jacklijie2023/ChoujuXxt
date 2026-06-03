package `fun`.kirari.hanako.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触觉反馈助手
 */
object HapticHelper {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * 轻触反馈 - 按钮点击
     */
    fun lightTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(20)
        }
    }

    /**
     * 中等反馈 - 截屏、复制
     */
    fun mediumTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }
    }

    /**
     * 强反馈 - 任务完成
     */
    fun heavyTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(80, 200))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(80)
        }
    }

    /**
     * 成功反馈 - 双击模式
     */
    fun successFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 30, 50, 30)
            val amplitudes = intArrayOf(0, 100, 0, 150)
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 30, 50, 30), -1)
        }
    }

    /**
     * 错误反馈 - 三次短促震动
     */
    fun errorFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 20, 80, 20, 80, 20)
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 20, 80, 20, 80, 20), -1)
        }
    }

    /**
     * 使用View的hapticFeedback
     */
    fun performViewHaptic(view: View, type: HapticFeedbackType = HapticFeedbackType.CONFIRM) {
        when (type) {
            HapticFeedbackType.CONFIRM -> {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            HapticFeedbackType.REJECT -> {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            HapticFeedbackType.TAP -> {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
}

enum class HapticFeedbackType {
    CONFIRM, REJECT, TAP
}
