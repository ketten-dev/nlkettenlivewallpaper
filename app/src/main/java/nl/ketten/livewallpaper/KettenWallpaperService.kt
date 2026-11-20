package nl.ketten.livewallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

class KettenWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = KettenEngine()

    inner class KettenEngine : Engine() {
        private val scene = KettenAsciiScene()
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var interactive = true
        private var running = false
        private var lastFrameMs = 0L
        private var receiversRegistered = false
        private lateinit var tiltTracker: TiltTracker
        private var powerManager: PowerManager? = null

        private val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> applyBatteryIntent(intent)
                    Intent.ACTION_SCREEN_OFF -> {
                        interactive = false
                        updateRunningState()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        interactive = true
                        updateRunningState()
                    }
                }
            }
        }

        private val frameRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                val now = SystemClock.uptimeMillis()
                val dt = if (lastFrameMs == 0L) 1f / 20f else (now - lastFrameMs) / 1000f
                lastFrameMs = now

                scene.update(dt)
                drawFrame()

                val targetInterval = scene.recommendedFrameIntervalMs(
                    powerManager?.isPowerSaveMode == true,
                )
                val renderCost = SystemClock.uptimeMillis() - now
                val nextDelay = (targetInterval - renderCost).coerceAtLeast(10L)
                handler.postDelayed(this, nextDelay)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)

            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            interactive = powerManager?.isInteractive != false

            tiltTracker = TiltTracker(this@KettenWallpaperService) { tx, ty ->
                scene.setGyroTilt(tx, ty)
            }

            scene.spectrumMode = WallpaperPrefs.getSpectrumMode(this@KettenWallpaperService)
            val metrics = resources.displayMetrics
            scene.resize(metrics.widthPixels, metrics.heightPixels)

            registerReceivers()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width > 0 && height > 0) {
                scene.resize(width, height)
                drawFrame()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            interactive = powerManager?.isInteractive != false
            updateRunningState()
        }

        private fun updateRunningState() {
            val shouldRun = visible && interactive
            if (shouldRun == running) return
            running = shouldRun
            if (shouldRun) {
                scene.spectrumMode = WallpaperPrefs.getSpectrumMode(this@KettenWallpaperService)
                refreshStickyBattery()
                tiltTracker.start()
                lastFrameMs = SystemClock.uptimeMillis()
                handler.removeCallbacks(frameRunnable)
                handler.post(frameRunnable)
            } else {
                tiltTracker.stop()
                handler.removeCallbacks(frameRunnable)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset,
            )
            scene.setSwipeOffset(xOffset)
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> scene.onTouch(event.x, event.y, isMove = false)
                MotionEvent.ACTION_MOVE -> scene.onTouch(event.x, event.y, isMove = true)
            }
        }

        override fun onDestroy() {
            visible = false
            updateRunningState()
            unregisterReceivers()
            scene.release()
            super.onDestroy()
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            if (!holder.surface.isValid) return
            var canvas: Canvas? = null
            try {
                canvas = try {
                    holder.lockHardwareCanvas()
                } catch (_: Exception) {
                    holder.lockCanvas()
                }
                canvas?.let { scene.draw(it) }
            } catch (_: Exception) {
                // Surface can momentarily become invalid during wallpaper transitions
            } finally {
                canvas?.let {
                    try {
                        holder.unlockCanvasAndPost(it)
                    } catch (_: Exception) {
                        // Ignore unlock race if surface was destroyed mid-frame
                    }
                }
            }
        }

        private fun registerReceivers() {
            if (receiversRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                val sticky = registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
                receiversRegistered = true
                if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) {
                    applyBatteryIntent(sticky)
                } else {
                    refreshStickyBattery()
                }
            } catch (_: Exception) {
                // Fallback safely if receiver registration fails
            }
        }

        private fun refreshStickyBattery() {
            try {
                val sticky = registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    RECEIVER_NOT_EXPORTED,
                )
                applyBatteryIntent(sticky)
            } catch (_: Exception) {
                // Keep previous battery state
            }
        }

        private fun unregisterReceivers() {
            if (!receiversRegistered) return
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
                // Already unregistered
            }
            receiversRegistered = false
        }

        private fun applyBatteryIntent(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val frac = if (level >= 0) level.toFloat() / scale.toFloat() else 0.85f
            scene.setBatteryState(frac, charging)
        }
    }
}
