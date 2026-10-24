package `fun`.kirari.hanako.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import `fun`.kirari.hanako.MainActivity
import `fun`.kirari.hanako.R
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

class MediaProjectionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProjectionSessionManager.stop()
                stopSelf()
            }

            ACTION_START_SESSION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    ProjectionSessionManager.startSession(this, resultCode, resultData)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ProjectionSessionManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hanako Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "fun.kirari.hanako.capture.STOP"
        const val ACTION_START_SESSION = "fun.kirari.hanako.capture.START_SESSION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 2001
    }
}

object ProjectionSessionManager {
    /** 沿用实时屏幕感知标签，便于与 ScreenCtx / ChatTrace 同一请求窗口对齐。 */
    private const val logTag = "ScreenCtx"

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive
    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var imageReader: ImageReader? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var callback: MediaProjection.Callback? = null
    @Volatile
    private var width: Int = 0
    @Volatile
    private var height: Int = 0

    /**
     * 本进程内是否**曾经**成功建立过截图会话。
     *
     * 用于区分「从未建立会话（尚未授权）」与「建立过但运行期丢失」：
     * `stop()` 只关会话、不清零该标记，使它表达「曾经可用过」这一事实。
     */
    @Volatile
    private var everEstablished = false

    /** 本进程内截图会话是否曾成功建立（见 [everEstablished]）。 */
    fun hasEverEstablished(): Boolean = everEstablished

    fun startSession(context: Context, resultCode: Int, data: Intent) {
        stop()

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
            ?: error("无法创建屏幕捕捉会话")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        width = metrics.widthPixels
        height = metrics.heightPixels
        val density = metrics.densityDpi
        val localReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val localCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }
        projection.registerCallback(localCallback, Handler(Looper.getMainLooper()))
        val localDisplay = projection.createVirtualDisplay(
            "hanako-live-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            localReader.surface,
            null,
            null
        )

        mediaProjection = projection
        imageReader = localReader
        virtualDisplay = localDisplay
        callback = localCallback
        everEstablished = true
        _sessionActive.value = true
    }

    fun hasActiveSession(): Boolean {
        return mediaProjection != null && imageReader != null && virtualDisplay != null
    }

    /**
     * 取得当前屏幕的一帧。
     *
     * [minFrameTimestampNanos] 非空时表示「Hanako 窗口已在该时刻完成隐藏」，
     * 只接受时间戳不早于该时刻的帧：`ImageReader` 中可能仍缓存着隐藏前产出的旧帧，
     * 直接 `acquireLatestImage()` 会把它当成当前屏幕交给 OCR。
     * 等待有上限，超时即报明确失败，不把不可验证的旧帧标记为成功。
     */
    suspend fun captureLatestBitmap(minFrameTimestampNanos: Long? = null): Bitmap {
        val reader = imageReader ?: error("屏幕捕捉会话未初始化")
        val startedAtNanos = System.nanoTime()
        var discardedCount = 0
        val image = acquireFreshFrame(
            minFrameTimestampNanos = minFrameTimestampNanos,
            acquire = { reader.acquireLatestImage() },
            frameTimestampOf = { it.timestamp },
            release = { it.close() },
            onDiscardedFrame = { staleTimestamp ->
                discardedCount += 1
                AppDebugLogStore.i(
                    logTag,
                    "frame-stale-discarded minTs=$minFrameTimestampNanos ts=$staleTimestamp " +
                        "staleMillis=${(minFrameTimestampNanos!! - staleTimestamp) / 1_000_000}"
                )
            }
        ) ?: run {
            val waitedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000
            if (minFrameTimestampNanos != null && discardedCount > 0) {
                AppDebugLogStore.e(
                    logTag,
                    "frame-not-fresh minTs=$minFrameTimestampNanos discarded=$discardedCount " +
                        "waitedMillis=$waitedMillis",
                    null
                )
                throw StaleFrameException(
                    "截图未取得窗口隐藏后的新帧（已丢弃 $discardedCount 个旧帧，等待 ${waitedMillis}ms）"
                )
            }
            AppDebugLogStore.e(
                logTag,
                "frame-unavailable minTs=${minFrameTimestampNanos ?: "-"} waitedMillis=$waitedMillis",
                null
            )
            error("未获取到屏幕内容")
        }
        AppDebugLogStore.i(
            logTag,
            "frame-accepted ts=${image.timestamp} minTs=${minFrameTimestampNanos ?: "-"} " +
                "ageMillis=${(System.nanoTime() - image.timestamp) / 1_000_000} " +
                "discarded=$discardedCount waitedMillis=${(System.nanoTime() - startedAtNanos) / 1_000_000}"
        )
        image.use {
            val plane = it.planes.first()
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        mediaProjection?.let { projection ->
            callback?.let { runCatching { projection.unregisterCallback(it) } }
            runCatching { projection.stop() }
        }
        callback = null
        mediaProjection = null
        width = 0
        height = 0
        _sessionActive.value = false
    }

}

private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}
