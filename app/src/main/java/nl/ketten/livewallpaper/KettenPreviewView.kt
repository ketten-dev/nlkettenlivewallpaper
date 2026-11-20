package nl.ketten.livewallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.PowerManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Interactive fullscreen preview of [KettenAsciiScene] inside [MainActivity].
 * Automatically pauses rendering and sensors whenever the window is hidden or the screen turns off,
 * reacts live to [WallpaperPrefs] changes, and renders safely in Android Studio's Layout Editor.
 */
class KettenPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val scene = KettenAsciiScene()
    private val powerManager: PowerManager? = if (isInEditMode) {
        null
    } else {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    private val tiltTracker: TiltTracker? = if (isInEditMode) {
        null
    } else {
        TiltTracker(context) { tx, ty ->
            scene.setGyroTilt(tx, ty)
        }
    }
    private var running = false
    private var prefsRegistered = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        post {
            scene.applyConfig(WallpaperPrefs.loadConfig(context))
            invalidate()
        }
    }

    // Vsync-aligned adaptive frame loop (see FramePacer); created lazily so the Layout Editor never touches it
    private val framePacer by lazy {
        FramePacer(
            targetIntervalMs = { scene.recommendedFrameIntervalMs(powerManager?.isPowerSaveMode == true) },
            onFrame = { dt ->
                scene.update(dt)
                invalidate()
            },
        )
    }

    init {
        if (isInEditMode) {
            scene.applyConfig(
                WallpaperConfig(
                    spectrumMode = SpectrumMode.DUSK,
                    lightCount = 16,
                    useSystemTime = false,
                    simulatedMinutes = 20 * 60 + 30,
                )
            )
            scene.setBatteryState(0.88f, charging = true)
            scene.update(1.6f)
        } else {
            scene.applyConfig(WallpaperPrefs.loadConfig(context))
            refreshBatteryState()
        }
    }

    fun applyConfig(config: WallpaperConfig) {
        scene.applyConfig(config)
        invalidate()
    }

    fun triggerPulse() {
        scene.triggerDemoPulse()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            scene.resize(w, h)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) {
            if (!prefsRegistered) {
                WallpaperPrefs.registerListener(context, prefsListener)
                prefsRegistered = true
            }
            updateActiveState(windowVisibility == VISIBLE && isShown)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isInEditMode) {
            updateActiveState(visibility == VISIBLE && isShown)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isInEditMode) {
            updateActiveState(windowVisibility == VISIBLE && isShown)
        }
    }

    override fun onDetachedFromWindow() {
        if (!isInEditMode) {
            updateActiveState(false)
            if (prefsRegistered) {
                WallpaperPrefs.unregisterListener(context, prefsListener)
                prefsRegistered = false
            }
        }
        scene.release()
        super.onDetachedFromWindow()
    }

    private fun updateActiveState(shouldRun: Boolean) {
        if (shouldRun == running) return
        running = shouldRun
        if (shouldRun) {
            scene.applyConfig(WallpaperPrefs.loadConfig(context))
            scene.onVisibilityRestored()
            refreshBatteryState()
            tiltTracker?.start()
            framePacer.start()
        } else {
            tiltTracker?.stop()
            framePacer.stop()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scene.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scene.onTouch(event.x, event.y, isMove = false)
                if (width > 0) {
                    scene.setSwipeOffset(event.x / width.toFloat())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                scene.onTouch(event.x, event.y, isMove = true)
                if (width > 0) {
                    scene.setSwipeOffset(event.x / width.toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scene.setSwipeOffset(0.5f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun refreshBatteryState() {
        try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            ) ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val frac = if (level >= 0) level.toFloat() / scale.toFloat() else 0.85f
            scene.setBatteryState(frac, charging)
        } catch (_: Exception) {
            // Keep default state
        }
    }
}
