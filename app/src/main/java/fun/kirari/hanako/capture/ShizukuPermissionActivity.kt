package `fun`.kirari.hanako.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import `fun`.kirari.hanako.overlay.OverlayLaunchMode
import `fun`.kirari.hanako.overlay.OverlayService
import rikka.shizuku.Shizuku

class ShizukuPermissionActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        ShizukuAuthorizationManager.setAuthorized(granted)
        if (granted) {
            // OverlayService 在 onCreate 中已调用 startForeground，且本调用来自前台 Activity，
            // 使用 startService 即可（startForegroundService 会导致回调时序变化，非必要）。
            startService(
                Intent(this, OverlayService::class.java).apply {
                    putExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE, launchMode.name)
                }
            )
            // 自动模式：授权完成后自动退到后台，避免停留本 App 导致后续截图截到本 App 界面。
            if (launchMode == OverlayLaunchMode.AUTO) {
                android.widget.Toast.makeText(this, "已进入自动模式：请切到题目界面后点击悬浮球，将自动截图并分析", android.widget.Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            }
            setResult(Activity.RESULT_OK)
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    private lateinit var launchMode: OverlayLaunchMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchMode = intent.getStringExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE)
            ?.let { runCatching { OverlayLaunchMode.valueOf(it) }.getOrNull() }
            ?: OverlayLaunchMode.NORMAL

        if (!Shizuku.pingBinder()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        when {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                ShizukuAuthorizationManager.setAuthorized(true)
                // OverlayService 在 onCreate 中已调用 startForeground，且本调用来自前台 Activity，
                // 使用 startService 即可（startForegroundService 会导致回调时序变化，非必要）。
                startService(
                    Intent(this, OverlayService::class.java).apply {
                        putExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE, launchMode.name)
                    }
                )
                // 自动模式：授权完成后自动退到后台，避免停留本 App 导致后续截图截到本 App 界面。
                if (launchMode == OverlayLaunchMode.AUTO) {
                    android.widget.Toast.makeText(this, "已进入自动模式：请切到题目界面后点击悬浮球，将自动截图并分析", android.widget.Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                }
                setResult(Activity.RESULT_OK)
                finish()
            }

            Shizuku.shouldShowRequestPermissionRationale() -> {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }

            else -> {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
