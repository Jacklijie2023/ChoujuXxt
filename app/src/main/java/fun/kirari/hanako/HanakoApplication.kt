package `fun`.kirari.hanako

import android.app.Application
import `fun`.kirari.hanako.debug.AppDebugLogStore
import ru.noties.jlatexmath.JLatexMathAndroid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HanakoApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        JLatexMathAndroid.init(this)
        container = AppContainer(applicationContext)
        logDiagnosticSelfTest()
    }

    /**
     * 诊断日志自检：应用启动时输出一条固定的 INFO 级日志。
     *
     * 用途：让「日志看不到」这件事变成可验证的——用于区分「日志系统没输出」与「Logcat 过滤条件不对」。
     * - 该行经 [AppDebugLogStore.i] 输出，而 [AppDebugLogStore.i] 内部**直接调用** `android.util.Log.i`，
     *   因此它同时进入 Logcat（tag = [DIAG_TAG]）与应用内内存缓存（可导出）。
     * - 若 Logcat 看不到本行，说明是过滤条件 / 进程选择 / 日志级别的问题，而非日志实现未工作。
     * - `installedAt` 取系统记录的「本包最后安装时间」，用于确认手机上装的是哪一版 APK。
     * - 只输出版本号 / 构建变体 / 安装时间，不含任何敏感信息。
     */
    private fun logDiagnosticSelfTest() {
        val installedAt = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrNull()
        val installedText = installedAt?.let {
            synchronized(installedTimeFormatter) { installedTimeFormatter.format(Date(it)) }
        } ?: "unknown"
        AppDebugLogStore.i(
            DIAG_TAG,
            "$SELF_TEST_MARKER app_started version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "flavor=${BuildConfig.FLAVOR} type=${BuildConfig.BUILD_TYPE} " +
                "appId=${BuildConfig.APPLICATION_ID} debug=${BuildConfig.DEBUG} installedAt=$installedText"
        )
    }

    companion object {
        /** 自检日志统一 tag：Logcat 中直接过滤该 tag 即可看到全部自检行。 */
        const val DIAG_TAG = "HANAKO_DIAG"

        /** 自检日志固定前缀，便于在应用内导出文本中检索。 */
        const val SELF_TEST_MARKER = "HANAKO_DIAG_SELF_TEST"

        private val installedTimeFormatter =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
