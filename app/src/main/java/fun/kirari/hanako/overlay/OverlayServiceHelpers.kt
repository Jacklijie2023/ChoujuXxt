package `fun`.kirari.hanako.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.app.Service
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import `fun`.kirari.hanako.MainActivity
import `fun`.kirari.hanako.R

internal fun OverlayService.buildNotification(): Notification {
    val openIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    // 截屏处理 Action
    val captureIntent = Intent(this, OverlayService::class.java).apply {
        action = OverlayService.ACTION_CAPTURE
    }
    val capturePendingIntent = PendingIntent.getService(
        this, 1, captureIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    // 无障碍提取 Action
    val a11yIntent = Intent(this, OverlayService::class.java).apply {
        action = OverlayService.ACTION_A11Y_EXTRACT
    }
    val a11yPendingIntent = PendingIntent.getService(
        this, 2, a11yIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    // 停止 Action
    val stopIntent = Intent(this, OverlayService::class.java).apply {
        action = OverlayService.ACTION_STOP
    }
    val stopPendingIntent = PendingIntent.getService(
        this, 3, stopIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, OverlayService.CHANNEL_ID)
        .setContentTitle(getString(R.string.overlay_notification_title))
        .setContentText(getString(R.string.overlay_notification_text))
        .setSmallIcon(R.mipmap.ic_launcher_round)
        .setContentIntent(openIntent)
        .addAction(0, "截屏处理", capturePendingIntent)
        .addAction(0, "无障碍提取", a11yPendingIntent)
        .addAction(0, "停止", stopPendingIntent)
        .setOngoing(true)
        .build()
}

internal fun OverlayService.notifyAutomationCompleted(label: String?) {
    val content = label?.take(32)?.let { "已复制：$it" } ?: "自动模式已完成并复制到剪贴板"
    val notification = NotificationCompat.Builder(this, OverlayService.AUTOMATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.overlay_automation_done_title))
        .setContentText(content)
        .setSmallIcon(R.mipmap.ic_launcher_round)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        .build()
    NotificationManagerCompat.from(this).notify(
        OverlayService.AUTOMATION_COMPLETE_NOTIFICATION_ID,
        notification
    )
}

internal fun OverlayService.openMainActivity() {
    openMainActivity(this)
}

internal fun openMainActivity(context: android.content.Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    )
}

/**
 * 跳转主 App 并请求打开「聊天」页。
 *
 * 通过 [EXTRA_OPEN_CHAT] 通知 Activity 将初始 tab 切到 Chat；由于使用
 * `FLAG_ACTIVITY_SINGLE_TOP`，Activity 已在前台时走 `onNewIntent`，同样生效。
 */
internal fun openMainActivityForChat(context: android.content.Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_CHAT, true)
        }
    )
}

/** 通知主 Activity 打开聊天页的 Intent extra。 */
const val EXTRA_OPEN_CHAT = "fun.kirari.hanako.overlay.EXTRA_OPEN_CHAT"

internal fun OverlayService.vibrateShort() {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Service.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30L)
            }
        }
    }
}

internal fun OverlayService.createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val overlayChannel = NotificationChannel(
        OverlayService.CHANNEL_ID,
        "Hanako Overlay",
        NotificationManager.IMPORTANCE_LOW
    )
    val automationChannel = NotificationChannel(
        OverlayService.AUTOMATION_CHANNEL_ID,
        "Hanako Automation",
        NotificationManager.IMPORTANCE_DEFAULT
    )
    getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(overlayChannel)
        createNotificationChannel(automationChannel)
    }
}
