package nl.ketten.livewallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Low-power, drift-free phone tilt tracker using hardware-fused gravity (gyro + accel)
 * with automatic fallback to accelerometer. Uses SENSOR_DELAY_UI (~16-20 Hz) and is only
 * active while the wallpaper or preview window is visible.
 */
class TiltTracker(
    context: Context,
    private val onTiltChanged: (tiltX: Float, tiltY: Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var registered = false
    private var hasBaselineY = false
    private var baselineY = 6.0f
    private var smoothX = 0f
    private var smoothY = 0f
    private var lastSentX = Float.NaN
    private var lastSentY = Float.NaN

    fun start() {
        if (registered || sensor == null) return
        hasBaselineY = false
        registered = sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_UI,
        ) == true
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]

        if (!hasBaselineY) {
            baselineY = gy
            hasBaselineY = true
        } else {
            // Gently adapt vertical holding angle so pitch tilt works whether sitting, standing, or on a desk
            baselineY += (gy - baselineY) * 0.025f
        }

        // ~25 degrees wrist tilt reaches full -1..+1 range for crisp, noticeable 3D depth
        val rawX = (-gx / 4.2f).coerceIn(-1f, 1f)
        val rawY = ((gy - baselineY) / 3.0f).coerceIn(-1f, 1f)

        smoothX += (rawX - smoothX) * 0.24f
        smoothY += (rawY - smoothY) * 0.24f

        if (lastSentX.isNaN() ||
            abs(smoothX - lastSentX) > 0.004f ||
            abs(smoothY - lastSentY) > 0.004f
        ) {
            lastSentX = smoothX
            lastSentY = smoothY
            onTiltChanged(smoothX, smoothY)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
