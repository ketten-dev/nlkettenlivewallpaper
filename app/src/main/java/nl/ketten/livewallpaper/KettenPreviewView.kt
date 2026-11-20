package nl.ketten.livewallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Interactive fullscreen preview of [KettenAsciiScene] inside [MainActivity].
 */
class KettenPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val scene = KettenAsciiScene()
    private val tiltTracker = TiltTracker(context) { tx, ty ->
        scene.setGyroTilt(tx, ty)
    }
    private var running = false
    private var lastFrameMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) 1f / 30f else (now - lastFrameMs) / 1000f
            lastFrameMs = now
            scene.update(dt)
            invalidate()
            postDelayed(this, 33L)
        }
    }

    init {
        scene.spectrumMode = WallpaperPrefs.getSpectrumMode(context)
        refreshBatteryState()
    }

    fun setSpectrumMode(mode: SpectrumMode) {
        scene.spectrumMode = mode
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
        running = true
        refreshBatteryState()
        tiltTracker.start()
        lastFrameMs = SystemClock.uptimeMillis()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        running = false
        tiltTracker.stop()
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
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
