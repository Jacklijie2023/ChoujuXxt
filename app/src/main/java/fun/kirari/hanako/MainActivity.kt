package `fun`.kirari.hanako

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import `fun`.kirari.hanako.overlay.EXTRA_OPEN_CHAT
import `fun`.kirari.hanako.ui.HanakoApp
import `fun`.kirari.hanako.ui.MainViewModel
import `fun`.kirari.hanako.ui.theme.HanakoTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenChatIntent(intent)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("HanakoAI", "Uncaught exception in thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        setContent {
            HanakoTheme {
                HanakoApp(viewModel)
            }
        }
    }

    /**
     * Activity 已在前台（SINGLE_TOP）时，悬浮球迷你窗「打开」会走此回调。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenChatIntent(intent)
    }

    /** 解析 [EXTRA_OPEN_CHAT]，请求主界面切到聊天页。 */
    private fun handleOpenChatIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) {
            viewModel.requestOpenChat()
        }
    }
}
