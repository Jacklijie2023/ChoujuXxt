package `fun`.kirari.hanako.overlay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlin.math.sqrt

/**
 * 摇一摇检测器
 * 检测设备摇晃手势，触发截屏处理
 */
internal class ShakeDetector(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ShakeDetector"
        private const val SHAKE_THRESHOLD = 15f
        private const val SHAKE_INTERVAL_MS = 1000L
    }
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listener: (() -> Unit)? = null
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var initialized = false

    fun start(listener: () -> Unit) {
        this.listener = listener
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            AppDebugLogStore.d(TAG, "Shake detector started")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        listener = null
        initialized = false
        AppDebugLogStore.d(TAG, "Shake detector stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!initialized) {
            lastX = x
            lastY = y
            lastZ = z
            initialized = true
            return
        }

        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ
        val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

        if (acceleration > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > SHAKE_INTERVAL_MS) {
                lastShakeTime = now
                AppDebugLogStore.i(TAG, "Shake detected! acceleration=$acceleration")
                listener?.invoke()
            }
        }

        lastX = x
        lastY = y
        lastZ = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
