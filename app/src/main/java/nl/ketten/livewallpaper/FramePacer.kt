package nl.ketten.livewallpaper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer

/**
 * Battery-friendly, vsync-aligned frame loop.
 *
 * A plain Handler.postDelayed(50 ms) loop is not locked to the display: on a 90 Hz panel 50 ms is
 * 4.5 vsync periods, so frames alternate between 44 and 56 ms while dt is measured at callback time.
 * Here the Handler only sleeps until just before the target frame, then a single Choreographer
 * callback renders on the next vsync and uses its frameTimeNanos as the time base (all callbacks of
 * one frame share that timestamp, which is what animations should be synchronised to).
 * There is no per-vsync wake-up between frames, so the adaptive 15–30 fps budget is kept.
 */
class FramePacer(
    private val targetIntervalMs: () -> Long,
    private val onFrame: (dtSeconds: Float) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L

    private val vsyncCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) {
                1f / 20f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
            }
            lastFrameNanos = frameTimeNanos

            val startMs = SystemClock.uptimeMillis()
            onFrame(dt)
            if (!running) return
            val costMs = SystemClock.uptimeMillis() - startMs

            // Wake up slightly before the target so the next vsync after arming is the target frame.
            val sleepMs = targetIntervalMs() - costMs - VSYNC_LEAD_MS
            if (sleepMs <= 0L) {
                choreographer.postFrameCallback(this)
            } else {
                handler.postDelayed(armRunnable, sleepMs)
            }
        }
    }

    private val armRunnable: Runnable = Runnable {
        if (running) choreographer.postFrameCallback(vsyncCallback)
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(vsyncCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(armRunnable)
        choreographer.removeFrameCallback(vsyncCallback)
    }

    private companion object {
        const val VSYNC_LEAD_MS = 6L
    }
}
