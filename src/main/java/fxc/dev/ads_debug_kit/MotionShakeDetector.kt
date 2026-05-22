package fxc.dev.ads_debug_kit

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

internal object MotionShakeDetector : SensorEventListener {
    private const val SHAKE_G_FORCE_THRESHOLD = 2.7f
    private const val SHAKE_THROTTLE_MS = 900L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var onShake: (() -> Unit)? = null
    private var isStarted = false
    private var lastShakeAtMs = 0L

    fun start(context: Context, onShake: () -> Unit) {
        mainHandler.post {
            this.onShake = onShake
            if (isStarted) return@post

            val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (manager == null || accelerometer == null) return@post

            sensorManager = manager
            isStarted = manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        mainHandler.post {
            sensorManager?.unregisterListener(this)
            sensorManager = null
            onShake = null
            isStarted = false
            lastShakeAtMs = 0L
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        if (now - lastShakeAtMs < SHAKE_THROTTLE_MS) return

        val gX = event.values[0] / SensorManager.GRAVITY_EARTH
        val gY = event.values[1] / SensorManager.GRAVITY_EARTH
        val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        if (gForce > SHAKE_G_FORCE_THRESHOLD) {
            lastShakeAtMs = now
            onShake?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
