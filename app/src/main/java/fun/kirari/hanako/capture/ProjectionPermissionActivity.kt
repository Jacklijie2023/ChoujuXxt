package `fun`.kirari.hanako.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import `fun`.kirari.hanako.overlay.OverlayLaunchMode
import `fun`.kirari.hanako.overlay.OverlayService

class ProjectionPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val launchMode = remember {
                intent.getStringExtra(EXTRA_LAUNCH_MODE)
                    ?.let { runCatching { OverlayLaunchMode.valueOf(it) }.getOrNull() }
                    ?: OverlayLaunchMode.NORMAL
            }
            val projectionManager = remember {
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            }
            var launched by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, MediaProjectionForegroundService::class.java).apply {
                            action = MediaProjectionForegroundService.ACTION_START_SESSION
                            putExtra(MediaProjectionForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(MediaProjectionForegroundService.EXTRA_RESULT_DATA, result.data)
                        }
                    )
                    // OverlayService 在 onCreate 中已调用 startForeground，且本调用来自前台 Activity，
                    // 使用 startService 即可（startForegroundService 会导致回调时序变化，非必要）。
                    startService(
                        Intent(this, OverlayService::class.java).apply {
                            putExtra(EXTRA_LAUNCH_MODE, launchMode.name)
                        }
                    )
                    // 自动模式：授权完成后自动退到后台，避免停留本 App 导致后续截图截到本 App 界面。
                    if (launchMode == OverlayLaunchMode.AUTO) {
                        android.widget.Toast.makeText(this, "已进入自动模式：请切到题目界面后点击悬浮球，将自动截图并分析", android.widget.Toast.LENGTH_SHORT).show()
                        moveTaskToBack(true)
                    }
                    setResult(Activity.RESULT_OK)
                } else {
                    stopService(Intent(this, MediaProjectionForegroundService::class.java))
                    setResult(Activity.RESULT_CANCELED)
                }
                finish()
            }

            LaunchedEffect(Unit) {
                if (!launched) {
                    launched = true
                    ContextCompat.startForegroundService(
                        this@ProjectionPermissionActivity,
                        Intent(this@ProjectionPermissionActivity, MediaProjectionForegroundService::class.java)
                    )
                    launcher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
    }

    companion object {
        const val EXTRA_LAUNCH_MODE = "extra_launch_mode"
    }
}
