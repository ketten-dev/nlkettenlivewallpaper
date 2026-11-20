package nl.ketten.livewallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Native Android Canvas ASCII + Volumetric Lighting engine for Ketten "Organic Hardware".
 * Features:
 *  - 3-plane atmospheric perspective (far background, midground, foreground)
 *  - Cylindrical 3D shaded foreground trunks with curved organic bark nerves & warm rim-light
 *  - Bottom quarter is a vegetation-free PCB substrate; copper lanes turn into tapering roots
 *    at the vine foot and graft onto vias (copper -> root transition)
 *  - Branches: bare, thickening old wood near the trunk, foliage on the young outer part,
 *    sag under their own weight and drop hanging side twigs (leaf cluster + bud) forward
 *  - Radial-gradient bloom lights that locally illuminate nearby trunks & leaves
 *  - Smooth 24-hour organic growth cycle (morning baseline -> day growth -> evening peak -> night wind-down);
 *    new cells sprout root -> tip after every growth step instead of popping in
 *  - One shared travelling wind field (trunk, branches, leaves, spores) with gust envelope,
 *    cantilever-like branch bending and wind-driven leaf glyphs
 *  - Energy pulses: spark along the PCB copper, then a sharp-headed glow with fading tail rising
 *    through trunk and branches at constant cells/s (arc-length based progress)
 *  - Prominent sub-pixel 3D swaying KETTEN logo with optional gyro parallax & AMOLED burn-in shift
 *  - Live configuration updates (speed, light count, wind/lights/growth toggles, time simulation)
 */
class KettenAsciiScene {

    // Grid dimensions & typography layout
    private var viewWidth = 1080
    private var viewHeight = 2400
    private var cols = 56
    private var rows = 76
    private var cellW = 19f
    private var cellH = 31f
    private var offsetY = 0f
    private var baselineOffset = 24f

    private var gridChars = CharArray(cols * rows) { ' ' }
    private var gridColors = IntArray(cols * rows)
    private var gridGlow = ByteArray(cols * rows)
    private var rowHasContent = BooleanArray(rows)
    private var glowIndices = IntArray(640)
    private var glowCount = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bloomDstRect = RectF()

    // Dedicated vector PCB substrate paints & reusable geometry (zero per-frame allocations)
    private val pcbTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pcbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pcbSilkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val pcbPath = Path()
    private val pcbPadRect = RectF()

    // Cached offscreen bitmaps: 5 volumetric 3D logo depth slices + 1 radial-gradient bloom sprite
    private val sliceBitmaps = arrayOfNulls<Bitmap>(5)
    private var sliceCacheDirty = true
    private var bloomSprite: Bitmap? = null

    private val singleCharBuf = CharArray(1)
    private val colors = SpectralColors()

    // Cached clock/date formatting (zero per-frame allocations)
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.forLanguageTag("nl-NL"))
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("nl-NL"))
    private val reusableDate = Date()
    private var lastClockCheckMs: Long = 0L
    private var cachedTimeText: String = "00:00"
    private var cachedDateText: String = ""

    // Configuration & growth state
    private var config: WallpaperConfig = WallpaperConfig()
    private var paletteDirty: Boolean = true
    private var growthDirty: Boolean = true
    private var currentGrowthFactor: Float = 0.62f
    private var builtGrowthFactor: Float = -1f
    private var builtLightCount: Int = -1
    private var growthCheckAccum: Float = 0f

    // External dynamic state
    private var batteryLevel: Float = 0.85f
    private var isCharging: Boolean = false
    private var targetSwipeNorm: Float = 0f
    private var smoothSwipeNorm: Float = 0f
    private var targetGyroX: Float = 0f
    private var targetGyroY: Float = 0f
    private var smoothGyroX: Float = 0f
    private var smoothGyroY: Float = 0f

    // Time & animation state.
    // Clocks are Double on purpose: a Float accumulator loses resolution after ~18 h of visible
    // runtime (ulp 7.8 ms at 2^16 s) and freezes completely after ~291 h (ulp 125 ms > dt).
    private var wallElapsed: Double = 0.0
    private var animElapsed: Double = 0.0
    private var lastInteractionTime: Double = -10.0
    private var paletteRefreshAccum: Float = 0f
    private var chargePulseTimer: Float = 0f
    private var passivePulseTimer: Float = 1.5f
    private var lastDragTouchTime: Double = 0.0
    private var logoRippleTime: Double = -10.0
    private var lastPulseLaunch: Double = -10.0
    private var logoRippleCx: Float = 0.5f
    private var logoRippleCy: Float = 0.5f
    private var logoTiltVelX: Float = 0f
    private var logoTiltX: Float = 0f

    private enum class NodeKind {
        FAR_VINE,
        FAR_LEAF,
        MID_STALK,
        MID_LEAF,
        ROOT_ORGANIC,
        ROOT_PCB,
        ROOT_PAD,
        TRUNK_3D,
        TENDRIL,
        BRANCH,
        LEAF,
        CANOPY_HANG,
        BUD,
    }

    private class FloraNode(
        val x: Float,
        val y: Float,
        val ch: Char,
        val kind: NodeKind,
        val side: Int,         // -1 = left, +1 = right, 0 = center
        val progress: Float,   // 0.0 (bottom root nexus) .. 1.0 (top canopy)
        val groupId: Int,
        val phase: Float,
        val flex: Float,
        val shade: Float = 0f, // For TRUNK_3D: -1.0 (outer shadow) .. +1.0 (inner warm rim-light)
        val variant: Int = 0,  // Bark nerve / leaf tone variation (BRANCH: 1 = thickened old wood)
        val depth: Float = 0f, // 0 = on the vine plane .. 1 = hanging forward towards the viewer
    ) {
        // Growth reveal: wall-clock time at which this cell starts sprouting (NaN = always visible)
        var bornAt: Double = Double.NaN

        // Last drawn grid cell, used for a small hysteresis so sub-cell motion does not flicker
        var drawX: Int = Int.MIN_VALUE
        var drawY: Int = Int.MIN_VALUE
    }

    private class AmbientLight(
        val baseX: Float,
        val baseY: Float,
        val phase: Float,
        val radiusCells: Float,
        val warmWeight: Float, // 0 = spectral magenta/rose, 1 = warm amber/gold
        val depthLayer: Int,   // 1 = mid, 2 = foreground
    ) {
        var curX: Float = baseX
        var curY: Float = baseY
        var intensity: Float = 0.7f
    }

    private class Spore {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var maxLife = 1f
        var phase = 0f
        var bright = false
        var ch = '·'
    }

    private class Ripple {
        var active = false
        var x = 0f
        var y = 0f
        var radius = 0f
        var maxRadius = 16f
        var strength = 1f
    }

    private class EnergyPulse {
        var active = false
        var progress = 0f
        var speed = 0.45f
        var side = 0
        var intensity = 1f
        var fromCharge = false
    }

    private class SproutAnchor(
        val x: Float,
        val y: Float,
        val side: Int,
    ) {
        var tipX: Float = x
        var tipY: Float = y
    }

    private class PcbTrace(
        val ptsX: FloatArray,
        val ptsY: FloatArray,
        val widthFactor: Float, // Relative to cellW (scales wider over the day)
        val tier: Int,          // 0 = secondary/ground, 1 = signal bus, 2 = main power rail
        val side: Int,          // -1 left, +1 right, 0 center
        val progressStart: Float,
        val progressEnd: Float,
    )

    private class PcbVia(
        val x: Float,
        val y: Float,
        val outerRadiusCells: Float,
        val drillRadiusCells: Float,
        val tier: Int,          // 0 = stitching via, 1 = signal via, 2 = power/junction via
        val side: Int,
        val progress: Float,
        val phase: Float,
    )

    private class PcbSmdPad(
        val x: Float,
        val y: Float,
        val wCells: Float,
        val hCells: Float,
        val side: Int,
        val progress: Float,
    )

    private class PcbSilkRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val pin1X: Float,
        val pin1Y: Float,
    )

    private val floraNodes = ArrayList<FloraNode>(4500)
    private val pcbTraces = ArrayList<PcbTrace>(256)
    private val pcbVias = ArrayList<PcbVia>(256)
    private val pcbPads = ArrayList<PcbSmdPad>(96)
    private val pcbSilks = ArrayList<PcbSilkRect>(16)
    private val ambientLights = ArrayList<AmbientLight>(WallpaperPrefs.MAX_LIGHTS)
    private val groupPhases = FloatArray(768)
    private var groupCount = 1

    private val spores = Array(56) { Spore() }
    private val ripples = Array(6) { Ripple() }
    private val pulses = Array(10) { EnergyPulse() }

    private val animRng = Random(System.currentTimeMillis())

    // Refined 3D KETTEN Logo mask: 32 columns x 5 rows
    private val logoMask = arrayOf(
        "#  # #### ##### ##### #### #   #",
        "# #  #      #     #   #    ##  #",
        "##   ###    #     #   ###  # # #",
        "# #  #      #     #   #    #  ##",
        "#  # ####   #     #   #### #   #",
    )
    private val logoW = logoMask[0].length
    private val logoH = logoMask.size

    // -------------------------------------------------------------------------
    // Motion timing model (all rates in rad/s on the animation clock, scaled by "speed").
    // One shared wind field drives trunks, branches, leaves and spores, so everything that
    // is close together moves together. The rates are deliberately spread out (no near-equal
    // pairs) so the separate rhythms stay readable instead of beating against each other.
    //   Trunk / main sway   1.15 rad/s ≈ 0.18 Hz  (real trees: fundamental ≈ 0.1–0.5 Hz)
    //   Branch overtone     2.05 rad/s ≈ 0.33 Hz
    //   Leaf flutter        4.30 rad/s ≈ 0.68 Hz
    //   Gust envelope       0.21 rad/s ≈ 30 s period
    //   Light breathing     0.70 rad/s ≈ 9 s, drift 0.41 / 0.33 rad/s
    //   Bud glow            0.90 rad/s ≈ 7 s
    // -------------------------------------------------------------------------
    private companion object {
        const val WIND_RATE = 1.15
        const val BRANCH_RATE = 2.05
        const val FLUTTER_RATE = 4.30
        const val GUST_RATE = 0.21
        const val LIGHT_BREATH_RATE = 0.70
        const val LIGHT_DRIFT_RATE_X = 0.41
        const val LIGHT_DRIFT_RATE_Y = 0.33
        const val BUD_RATE = 0.90

        // Wave number of the travelling wind field (rad per grid cell): gusts visibly roll across
        // the canopy from left to right, and neighbouring cells stay almost in phase.
        const val WIND_K_X = 0.11
        const val WIND_K_Y = 0.045

        // Energy pulse profile in progress units: sharp leading edge, long fading tail.
        const val PULSE_HEAD = 0.022f
        const val PULSE_TAIL = 0.15f

        // Growth reveal: new cells sprout from root to tip after each daily-growth rebuild.
        const val GROW_SPREAD_S = 3.2f
        const val GROW_FADE_S = 1.4f
        const val PENDING_MARK = -1.0e9
    }

    // Per-frame cached wind values (computed once in update(), reused by every node)
    private var gustNow: Float = 1f
    private var windPhaseNow: Double = 0.0
    private var branchPhaseNow: Double = 0.0
    private var flutterPhaseNow: Double = 0.0

    // Highest progress value present in the current world; pulses run until just past it
    private var maxNodeProgress: Float = 1f

    // Progress units per grid cell along the main vines (set while generating the trunks)
    private var progPerCell: Float = 0.012f

    // Top edge of the PCB substrate band (bottom ~quarter): no vegetation below this line,
    // only copper, vias and the copper -> root transition.
    private var substrateTopY: Float = 57f

    init {
        refreshTimeAndGrowth(forceRebuild = false)
        SpectralRecipe.evaluate(config.spectrumMode, activeHourOverride(), colors)
        paletteDirty = false
        refreshClockText(force = true)
        resize(viewWidth, viewHeight)
    }

    fun applyConfig(newConfig: WallpaperConfig) {
        val old = config
        config = newConfig

        if (old.spectrumMode != newConfig.spectrumMode ||
            old.useSystemTime != newConfig.useSystemTime ||
            old.simulatedMinutes != newConfig.simulatedMinutes
        ) {
            paletteDirty = true
            growthDirty = true
            refreshClockText(force = true)
        }
        if (old.growthEnabled != newConfig.growthEnabled ||
            old.lightCount != newConfig.lightCount
        ) {
            growthDirty = true
        }
        lastInteractionTime = wallElapsed
        refreshTimeAndGrowth(forceRebuild = false)
    }

    fun onVisibilityRestored() {
        paletteDirty = true
        growthDirty = true
        refreshClockText(force = true)
        refreshTimeAndGrowth(forceRebuild = false)
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height

        cols = if (height >= width) {
            56
        } else {
            (56f * (width.toFloat() / height.toFloat())).roundToInt().coerceIn(56, 92)
        }

        textPaint.textSize = 100f
        val charAspect = (textPaint.measureText("M") / 100f).coerceIn(0.45f, 0.75f)

        cellW = width.toFloat() / cols.toFloat()
        val computedTextSize = (cellW / charAspect).coerceAtLeast(10f)
        textPaint.textSize = computedTextSize

        val fm = textPaint.fontMetrics
        cellH = ((fm.descent - fm.ascent) * 0.96f).coerceAtLeast(computedTextSize * 0.98f)
        rows = (height / cellH).toInt().coerceIn(32, 120)
        offsetY = (height - rows * cellH) * 0.5f
        baselineOffset = -fm.ascent * 0.94f

        val size = cols * rows
        if (gridChars.size != size) {
            gridChars = CharArray(size) { ' ' }
            gridColors = IntArray(size)
            gridGlow = ByteArray(size)
        }
        if (rowHasContent.size != rows) {
            rowHasContent = BooleanArray(rows)
        }

        sliceCacheDirty = true
        ensureBloomSprite()
        refreshTimeAndGrowth(forceRebuild = true)
        seedAmbientSpores()
    }

    fun release() {
        for (i in sliceBitmaps.indices) {
            sliceBitmaps[i]?.recycle()
            sliceBitmaps[i] = null
        }
        sliceCacheDirty = true
        bloomSprite?.recycle()
        bloomSprite = null
    }

    fun recommendedFrameIntervalMs(isPowerSave: Boolean): Long {
        if (isPowerSave) return 66L
        if (isCharging ||
            (wallElapsed - lastInteractionTime) < 1.8f ||
            (animElapsed - logoRippleTime) < 1.5f
        ) {
            return 33L
        }
        for (r in ripples) {
            if (r.active) return 33L
        }
        for (p in pulses) {
            if (p.active) return 33L
        }
        return if (config.animationSpeed > 1.4f) 36L else 50L
    }

    fun setBatteryState(level: Float, charging: Boolean) {
        val wasCharging = isCharging
        batteryLevel = level.coerceIn(0f, 1f)
        isCharging = charging
        if (charging && !wasCharging) {
            spawnEnergyPulse(side = 0, speed = 0.55f, intensity = 1.25f, fromCharge = true)
        }
    }

    fun setSwipeOffset(xOffset: Float) {
        val next = ((xOffset.coerceIn(0f, 1f) - 0.5f) * 2f)
        if (abs(next - targetSwipeNorm) > 0.01f) {
            lastInteractionTime = wallElapsed
        }
        targetSwipeNorm = next
    }

    fun setGyroTilt(tiltX: Float, tiltY: Float) {
        val nx = tiltX.coerceIn(-1f, 1f)
        val ny = tiltY.coerceIn(-1f, 1f)
        if (abs(nx - targetGyroX) > 0.03f || abs(ny - targetGyroY) > 0.03f) {
            lastInteractionTime = wallElapsed
        }
        targetGyroX = nx
        targetGyroY = ny
    }

    fun onTouch(px: Float, py: Float, isMove: Boolean) {
        lastInteractionTime = wallElapsed
        val gx = (px / cellW).coerceIn(0f, (cols - 1).toFloat())
        val gy = ((py - offsetY) / cellH).coerceIn(0f, (rows - 1).toFloat())

        if (isMove) {
            if (wallElapsed - lastDragTouchTime < 0.085f) return
            lastDragTouchTime = wallElapsed
            spawnRipple(gx, gy, maxRadius = 11f, strength = 0.65f)
            spawnSporeBurst(gx, gy, count = 2)
            val centerDx = (gx / cols - 0.5f)
            logoTiltVelX += centerDx * 1.4f
        } else {
            spawnRipple(gx, gy, maxRadius = 18f, strength = 1.25f)
            spawnSporeBurst(gx, gy, count = 9)

            val logoStartX = (cols - logoW) * 0.5f
            val logoStartY = rows * 0.42f
            val hitLogo = gx >= logoStartX - 3f && gx <= logoStartX + logoW + 3f &&
                gy >= logoStartY - 3f && gy <= logoStartY + logoH + 5f

            if (hitLogo) {
                logoRippleTime = animElapsed
                logoRippleCx = gx
                logoRippleCy = gy
                logoTiltVelX += if (gx < cols * 0.5f) 4.0f else -4.0f
                spawnEnergyPulse(side = 0, speed = 0.52f, intensity = 1.15f, fromCharge = false)
            } else if (gy > rows * 0.74f) {
                spawnEnergyPulse(side = 0, speed = 0.60f, intensity = 1.35f, fromCharge = true)
            } else {
                val side = if (gx < cols * 0.5f) -1 else 1
                spawnEnergyPulse(side = side, speed = 0.48f, intensity = 1.1f, fromCharge = false)
            }
        }
    }

    fun triggerDemoPulse() {
        lastInteractionTime = wallElapsed
        spawnEnergyPulse(side = 0, speed = 0.55f, intensity = 1.35f, fromCharge = true)
        spawnSporeBurst(cols * 0.5f, rows - 2f, count = 8)
    }

    fun update(dt: Float) {
        // Upper clamp sits above the 66 ms power-save frame interval so time never runs slow there,
        // while still protecting against huge jumps after a stall.
        val rawStep = dt.coerceIn(0.001f, 0.10f)
        val simStep = (rawStep * config.animationSpeed).coerceIn(0.0005f, 0.20f)

        wallElapsed += rawStep
        animElapsed += simStep

        // Shared wind field state for this frame (see windAt())
        val t = animElapsed
        gustNow = (0.70 + 0.30 * sin(GUST_RATE * t + 0.6 * sin(0.083 * t))).toFloat()
        windPhaseNow = WIND_RATE * t
        branchPhaseNow = BRANCH_RATE * t
        flutterPhaseNow = FLUTTER_RATE * t

        // Refresh 24h spectral recipe on change or every 15s
        paletteRefreshAccum += rawStep
        if (paletteDirty || paletteRefreshAccum >= 15f) {
            paletteRefreshAccum = 0f
            paletteDirty = false
            SpectralRecipe.evaluate(config.spectrumMode, activeHourOverride(), colors)
            sliceCacheDirty = true
        }

        // Check daily growth cycle only once per minute (or immediately if settings changed)
        growthCheckAccum += rawStep
        if (growthDirty || growthCheckAccum >= 60f) {
            growthCheckAccum = 0f
            growthDirty = false
            refreshTimeAndGrowth(forceRebuild = false)
        }

        refreshClockText(force = false)

        // Smooth swipe & gyro parallax (responsive in real wall-clock time)
        smoothSwipeNorm += (targetSwipeNorm - smoothSwipeNorm) * min(1f, rawStep * 7.5f)
        smoothGyroX += (targetGyroX - smoothGyroX) * min(1f, rawStep * 9.0f)
        smoothGyroY += (targetGyroY - smoothGyroY) * min(1f, rawStep * 9.0f)

        // Spring physics for interactive 3D logo tilt
        val springForce = -logoTiltX * 16f - logoTiltVelX * 4.2f
        logoTiltVelX += springForce * rawStep
        logoTiltX = (logoTiltX + logoTiltVelX * rawStep).coerceIn(-1.5f, 1.5f)

        // Update floating/pulsing ambient lights
        updateAmbientLights()

        // Charging energy pulses from USB-C root nexus
        if (isCharging) {
            chargePulseTimer += simStep
            if (chargePulseTimer >= 1.15f) {
                chargePulseTimer = 0f
                val side = when (animRng.nextInt(3)) {
                    0 -> -1
                    1 -> 1
                    else -> 0
                }
                spawnEnergyPulse(side = side, speed = 0.46f, intensity = 1.2f, fromCharge = true)
                spawnSporeBurst(cols * 0.5f, rows - 2.2f, count = 2)
            }
        }

        // Calm passive bio-heartbeat pulses
        passivePulseTimer += simStep
        val passiveInterval = 6.5f - batteryLevel * 2.2f
        if (passivePulseTimer >= passiveInterval) {
            passivePulseTimer = 0f
            val side = if (animRng.nextBoolean()) -1 else 1
            spawnEnergyPulse(side = side, speed = 0.30f, intensity = 0.85f, fromCharge = false)
        }

        // Update ripples
        for (r in ripples) {
            if (!r.active) continue
            // Touch feedback runs on real time: it must feel equally responsive at every speed setting.
            // Exponential decay is frame-rate independent (unlike a (1 - k*dt) Euler factor).
            r.radius += rawStep * 24f
            r.strength *= exp(-1.45f * rawStep)
            if (r.radius >= r.maxRadius || r.strength <= 0.03f) {
                r.active = false
            }
        }

        // Update energy pulses
        for (p in pulses) {
            if (!p.active) continue
            // Sap-like rise: full speed through roots and trunk, easing off ~30 % towards the canopy tips
            val ease = 1f - 0.30f * p.progress.coerceIn(0f, 1f)
            p.progress += simStep * p.speed * ease
            if (p.progress > maxNodeProgress + PULSE_TAIL + 0.02f) {
                p.active = false
            }
        }

        // Update spores
        var activeAmbient = 0
        for (s in spores) {
            if (!s.active) continue
            s.life += simStep
            if (s.life >= s.maxLife || s.y < -2f || s.x < -2f || s.x > cols + 2f) {
                s.active = false
                continue
            }
            if (!s.bright) activeAmbient++
            // Same wind field as the foliage, so spores drift with the gust that bends the leaves
            val windPush = if (config.windEnabled) windAt(s.x, s.y) * 1.6f else 0f
            val sway = windPush + smoothGyroX * 1.4f
            s.x += (s.vx + sway) * simStep
            s.y += s.vy * simStep
            s.vx *= exp(-1.6f * simStep)
            if (s.vy > -1.2f) {
                s.vy -= simStep * 1.8f
            }
            for (r in ripples) {
                if (!r.active) continue
                val dx = s.x - r.x
                val dy = s.y - r.y
                val d = hypot(dx, dy)
                if (abs(d - r.radius) < 3.2f && d > 0.1f) {
                    val push = r.strength * 8f * simStep
                    s.vx += (dx / d) * push
                    s.vy += (dy / d) * push
                }
            }
        }
        val targetAmbientSpores = (8 + (currentGrowthFactor * 10f).roundToInt())
        if (activeAmbient < targetAmbientSpores && animRng.nextFloat() < simStep * 4.5f) {
            spawnAmbientSpore()
        }
    }

    fun draw(canvas: Canvas) {
        if (paletteDirty) {
            paletteDirty = false
            SpectralRecipe.evaluate(config.spectrumMode, activeHourOverride(), colors)
            sliceCacheDirty = true
        }
        if (growthDirty) {
            growthDirty = false
            refreshTimeAndGrowth(forceRebuild = false)
        }

        // 1. Clear ASCII buffer & fast-path trackers
        gridChars.fill(' ')
        gridColors.fill(0)
        gridGlow.fill(0)
        rowHasContent.fill(false)
        glowCount = 0

        // 2. Render depth layers into the ASCII grid (Far -> Mid -> Foreground)
        drawFloraPass(passLayer = 0)
        drawFloraPass(passLayer = 1)
        drawFloraPass(passLayer = 2)
        drawUsbNexusCore()
        drawLogoReadout()
        drawSpores()

        // 3. Draw AMOLED pure black background
        canvas.drawColor(colors.bg)

        // 3b. Draw vector PCB substrate (45-degree routed copper traces, SMD pads & plated vias)
        drawPcbSubstrate(canvas)

        // 4. Soft radial-gradient bloom halos behind the glowing ambient lights
        drawAmbientLightBlooms(canvas)

        // 5. Lightweight glow halos for glowing ASCII buds/pads/pulses
        val glowRadius = cellH * 0.70f
        for (i in 0 until glowCount) {
            val idx = glowIndices[i]
            val g = gridGlow[idx].toInt()
            if (g > 0) {
                val row = idx / cols
                val col = idx - row * cols
                val cx = col * cellW + cellW * 0.5f
                val cy = offsetY + row * cellH + cellH * 0.5f
                val alpha = if (g >= 2) 56 else 30
                glowPaint.color = SpectralRecipe.withAlpha(gridColors[idx], alpha)
                canvas.drawCircle(cx, cy, if (g >= 2) glowRadius * 1.12f else glowRadius * 0.82f, glowPaint)
            }
        }

        // 6. Crisp per-cell monospace characters (skipping completely empty rows)
        for (row in 0 until rows) {
            if (!rowHasContent[row]) continue
            val rowOffset = row * cols
            val baselineY = offsetY + row * cellH + baselineOffset
            for (col in 0 until cols) {
                val idx = rowOffset + col
                val ch = gridChars[idx]
                if (ch != ' ') {
                    textPaint.color = gridColors[idx]
                    singleCharBuf[0] = ch
                    val cx = col * cellW + cellW * 0.5f
                    canvas.drawText(singleCharBuf, 0, 1, cx, baselineY, textPaint)
                }
            }
        }

        // 7. Crisp bright light cores on top of foliage for warm/spectral floating orbs
        drawAmbientLightCores(canvas)

        // 8. Sub-pixel smooth 3D swaying KETTEN logo
        drawLogo3DDirect(canvas)
    }

    // -------------------------------------------------------------------------
    // Time & Daily Growth Calculation
    // -------------------------------------------------------------------------

    private fun activeHourOverride(): Float? =
        if (config.useSystemTime) null else config.simulatedMinutes / 60f

    private fun activeHour(): Float =
        activeHourOverride() ?: SpectralRecipe.currentHourOfDay()

    private fun refreshTimeAndGrowth(forceRebuild: Boolean) {
        val targetGrowth = if (config.growthEnabled) {
            SpectralRecipe.computeGrowthFactor(activeHour())
        } else {
            0.62f
        }
        currentGrowthFactor = targetGrowth

        // Finer rebuild step than before (0.006 instead of 0.012); the reveal animation below hides
        // each step, so growth reads as continuous sprouting instead of periodic jumps.
        val growthChanged = builtGrowthFactor < 0f || abs(targetGrowth - builtGrowthFactor) >= 0.006f
        val lightsChanged = builtLightCount != config.lightCount

        if (forceRebuild || growthChanged || lightsChanged) {
            // Animate only real growth steps of an already visible world (not first build / resize)
            val reveal = !forceRebuild && builtGrowthFactor >= 0f && growthChanged
            if (reveal) snapshotOccupancy()
            builtGrowthFactor = targetGrowth
            builtLightCount = config.lightCount
            generateWorld(targetGrowth, config.lightCount)
            if (reveal) scheduleGrowthReveal()
        }
    }

    // Per grid cell state before a rebuild (reused buffer): NaN = empty, -Inf = visible,
    // any other value = the time at which a still-pending cell was scheduled to sprout.
    private var cellStateScratch = DoubleArray(0)

    private fun snapshotOccupancy() {
        val size = cols * rows
        if (cellStateScratch.size != size) cellStateScratch = DoubleArray(size)
        cellStateScratch.fill(Double.NaN)
        for (i in 0 until floraNodes.size) {
            val n = floraNodes[i]
            val x = n.x.roundToInt()
            val y = n.y.roundToInt()
            if (x !in 0 until cols || y !in 0 until rows) continue
            val idx = y * cols + x
            val state = if (n.bornAt.isNaN() || n.bornAt <= wallElapsed) Double.NEGATIVE_INFINITY else n.bornAt
            val prev = cellStateScratch[idx]
            if (prev.isNaN() || state < prev) cellStateScratch[idx] = state
        }
    }

    /**
     * Marks every cell that did not exist before the rebuild as "sprouting". Start times are spread
     * by [FloraNode.progress] (root -> tip), so new wood visibly grows outward along the plant.
     * Cells that were already waiting keep their original start time, so quick successive rebuilds
     * (e.g. dragging the time-simulation slider) never postpone growth indefinitely.
     */
    private fun scheduleGrowthReveal() {
        if (cellStateScratch.size != cols * rows) return
        var minP = Float.MAX_VALUE
        var maxP = -Float.MAX_VALUE
        for (i in 0 until floraNodes.size) {
            val n = floraNodes[i]
            val x = n.x.roundToInt()
            val y = n.y.roundToInt()
            val state = if (x in 0 until cols && y in 0 until rows) cellStateScratch[y * cols + x] else Double.NaN
            when {
                state == Double.NEGATIVE_INFINITY -> Unit // already visible: stays visible
                !state.isNaN() -> n.bornAt = state         // still pending: keep its schedule
                else -> {
                    n.bornAt = PENDING_MARK
                    if (n.progress < minP) minP = n.progress
                    if (n.progress > maxP) maxP = n.progress
                }
            }
        }
        if (minP > maxP) return
        val span = (maxP - minP).coerceAtLeast(0.05f)
        for (i in 0 until floraNodes.size) {
            val n = floraNodes[i]
            if (n.bornAt != PENDING_MARK) continue
            n.bornAt = wallElapsed + ((n.progress - minP) / span) * GROW_SPREAD_S
        }
    }

    // -------------------------------------------------------------------------
    // Procedural World Generation:
    //   Morning (g ~ 0): Two slender unbranched vines with initial leaves & lights + simple mycelium
    //   Day -> Evening (g -> 1): Branches sprout from exact leaf nodes, filling all 4 rectangular
    //     screen corners while 2nd (midground) and 3rd (far background) layers gradually fill in.
    // -------------------------------------------------------------------------

    private fun generateWorld(growth: Float, lightCount: Int) {
        floraNodes.clear()
        pcbTraces.clear()
        pcbVias.clear()
        pcbPads.clear()
        pcbSilks.clear()
        ambientLights.clear()
        groupCount = 1

        // Normalized daily growth progression: 0.0 at 06:00 morning baseline -> 1.0 at evening peak
        val g = ((growth - 0.15f) / 0.85f).coerceIn(0f, 1f)

        // Deterministic seed per viewport dimensions. Every section gets its own generator, so a
        // section that grows (and therefore draws more random numbers) cannot reshuffle the others.
        val seed = 1337 + cols * 31 + rows * 7
        fun sectionRng(salt: Int) = Random(seed * 7919 + salt)

        val cx = (cols - 1) * 0.5f
        val bottomY = (rows - 1).toFloat()
        // PCB mycelium occupies approximately the bottom quarter of the screen (~25%)
        val rootTopY = rows * (0.75f - g * 0.02f)
        substrateTopY = rootTopY
        val leftJoinX = cols * 0.10f
        val rightJoinX = cols * 0.90f

        // --- A. LAYER 0 (3rd depth plane): Far Atmospheric Background (emerges midday -> lush evening) ---
        if (g > 0.24f) {
            val farG = ((g - 0.24f) / 0.76f).coerceIn(0f, 1f)
            generateFarBackgroundLayer(cx, rootTopY, farG, sectionRng(101))
        }

        // --- B. LAYER 1 (2nd depth plane): Midground Stems, Corners & Deep Canopy (emerges late morning -> evening) ---
        if (g > 0.10f) {
            val midG = ((g - 0.10f) / 0.82f).coerceIn(0f, 1f)
            generateMidgroundLayer(cx, bottomY, rootTopY, leftJoinX, rightJoinX, midG, sectionRng(202))
        }

        // --- C. LAYER 2 (Foreground): Mycelium Network + PCB Traces ---
        // Morning: simple mycelium following the current base curve.
        // Day -> Evening: expands & branches outward into bottom-left and bottom-right screen corners.
        generateMyceliumNetwork(cx, bottomY, rootTopY, leftJoinX, rightJoinX, g, sectionRng(303))

        // --- D. LAYER 2 (Foreground): Two Slender Morning Vines -> 3D Trunks + Leaf-Node Branches ---
        val anchors = ArrayList<SproutAnchor>(24)
        generateMainVineAndBranches(
            p0x = leftJoinX, p0y = rootTopY,
            p1x = 2.6f, p1y = rows * 0.60f,
            p2x = 3.0f, p2y = rows * 0.14f,
            p3x = cx - 2.0f,
            side = -1, g = g, anchors = anchors, gen = sectionRng(404),
        )
        generateMainVineAndBranches(
            p0x = rightJoinX, p0y = rootTopY,
            p1x = cols - 3.6f, p1y = rows * 0.60f,
            p2x = cols - 4.0f, p2y = rows * 0.14f,
            p3x = cx + 2.0f,
            side = 1, g = g, anchors = anchors, gen = sectionRng(505),
        )

        // --- E. Hanging Canopy Tendrils at Top Arch (emerge gradually after morning, stopping above logo border) ---
        if (g > 0.22f) {
            val hangG = ((g - 0.22f) / 0.78f).coerceIn(0f, 1f)
            val hangPositions = when {
                hangG > 0.60f -> floatArrayOf(0.08f, 0.17f, 0.28f, 0.39f, 0.50f, 0.61f, 0.72f, 0.83f, 0.92f)
                hangG > 0.28f -> floatArrayOf(0.14f, 0.28f, 0.39f, 0.61f, 0.72f, 0.86f)
                else -> floatArrayOf(0.28f, 0.72f)
            }
            for (hp in hangPositions) {
                val gen = sectionRng(606 + (hp * 1000f).roundToInt())
                val hx = cols * hp
                val inCorner = hp < 0.20f || hp > 0.80f
                val topStartY = if (inCorner) 1.5f else if (hp in 0.32f..0.68f) 3.2f else 4.2f
                val maxLen = if (inCorner) 9.5f else 7.5f
                val length = (2 + (hangG * maxLen).roundToInt() + gen.nextInt(2)).coerceIn(2, 12)
                val gid = allocGroup(gen)
                val side = if (hp < 0.5f) -1 else 1
                for (step in 0 until length) {
                    val hy = topStartY + step
                    if (isInsideLogoExclusion(hx, hy, pad = 0.5f)) break
                    val ch = when {
                        step == length - 1 -> '¤'
                        step % 2 == 0 -> '|'
                        else -> ':'
                    }
                    val kind = if (step == length - 1) NodeKind.BUD else NodeKind.CANOPY_HANG
                    floraNodes.add(
                        FloraNode(
                            x = hx, y = hy, ch = ch, kind = kind, side = side,
                            progress = 0.94f + (step.toFloat() / length) * 0.06f,
                            groupId = gid,
                            phase = gen.nextFloat() * (2f * PI.toFloat()),
                            flex = 0.35f + (step.toFloat() / length) * 1.15f,
                        )
                    )
                }
            }
        }

        // --- F. Ambient Warm & Spectral Bloom Lights ---
        // Morning: distributed directly along the two slender vines.
        // Day -> Evening: remain along trunks for warm rim-light AND spread outward with corner branches.
        generateAmbientLights(lightCount, g, anchors, sectionRng(707))

        // Keep the substrate band clean: no leaves/stems/buds below its top edge
        floraNodes.removeAll { it.y > rootTopY + 0.3f && isVegetation(it.kind) }

        var maxP = 0f
        for (i in 0 until floraNodes.size) {
            if (floraNodes[i].progress > maxP) maxP = floraNodes[i].progress
        }
        maxNodeProgress = maxP.coerceAtLeast(1f)
    }

    // -------------------------------------------------------------------------
    // Hard Logo Exclusion Border (nothing may grow or drift inside this box)
    // -------------------------------------------------------------------------

    private fun logoExclMinX(parallaxX: Float = 0f, pad: Float = 0f): Float =
        (cols - logoW) * 0.5f - 3.6f - pad + parallaxX

    private fun logoExclMaxX(parallaxX: Float = 0f, pad: Float = 0f): Float =
        (cols + logoW) * 0.5f + 3.6f + pad + parallaxX

    private fun logoExclMinY(parallaxY: Float = 0f, pad: Float = 0f): Float =
        (rows * 0.42f).roundToInt().toFloat() - 3.2f - pad + parallaxY

    private fun logoExclMaxY(parallaxY: Float = 0f, pad: Float = 0f): Float =
        (rows * 0.42f).roundToInt().toFloat() + logoH + 6.4f + pad + parallaxY

    private fun isInsideLogoExclusion(
        x: Float,
        y: Float,
        parallaxX: Float = 0f,
        parallaxY: Float = 0f,
        pad: Float = 0f,
    ): Boolean {
        return x >= logoExclMinX(parallaxX, pad) &&
            x <= logoExclMaxX(parallaxX, pad) &&
            y >= logoExclMinY(parallaxY, pad) &&
            y <= logoExclMaxY(parallaxY, pad)
    }

    /**
     * 3rd depth plane (Far Atmospheric Background): grows richly across the entire upper canopy,
     * lower mycelium field, side margins, and all 4 rectangular corners — stopping cleanly at the
     * hard border around the KETTEN logo.
     */
    private fun generateFarBackgroundLayer(
        cx: Float,
        bottomY: Float, // top edge of the PCB substrate: the far layer grows up from here
        farG: Float,
        gen: Random,
    ) {
        val logoTopY = logoExclMinY(pad = 1.0f)
        val logoBotY = logoExclMaxY(pad = 1.0f)

        // 1. Deep framing vines along both flanks
        generateFarVine(
            p0x = cx - 4f, p0y = bottomY,
            p1x = cols * 0.14f, p1y = rows * 0.72f,
            p2x = cols * 0.13f, p2y = rows * 0.24f,
            p3x = cx - 3f,
            side = -1, growth = farG, gen = gen,
        )
        generateFarVine(
            p0x = cx + 4f, p0y = bottomY,
            p1x = cols * 0.86f, p1y = rows * 0.72f,
            p2x = cols * 0.87f, p2y = rows * 0.24f,
            p3x = cx + 3f,
            side = 1, growth = farG, gen = gen,
        )

        // 2. Corner-filling deep silhouettes (all 4 corners) + side margins flanking the logo border
        for (side in intArrayOf(-1, 1)) {
            val edgeX = if (side < 0) 0.5f else cols - 1.5f
            val innerX = if (side < 0) cols * 0.22f else cols * 0.78f

            val topStems = if (farG > 0.45f) 5 else 3
            for (k in 0 until topStems) {
                val frac = (k + 0.5f) / topStems
                val startX = if (side < 0) cols * (0.03f + frac * 0.22f) else cols * (0.97f - frac * 0.22f)
                val startY = rows * (0.24f - frac * 0.16f)
                val endX = edgeX + (-side) * (k * 2.0f)
                val endY = (k * 1.2f).coerceAtMost(rows * 0.14f)
                generateBackgroundCornerSprig(
                    startX = startX, startY = startY,
                    targetX = endX, targetY = endY,
                    fillFactor = farG,
                    side = side, layer = 0, progressBase = 0.78f,
                    gen = Random(7100 + side * 311 + k * 73),
                )
            }

            // (No bottom-corner stems: the bottom quarter is reserved for the PCB substrate)

            // Secondary deeper flank vines (staying outside the hard logo box)
            if (farG > 0.30f) {
                generateFarVine(
                    p0x = innerX, p0y = bottomY,
                    p1x = if (side < 0) cols * 0.11f else cols * 0.89f, p1y = rows * 0.64f,
                    p2x = if (side < 0) cols * 0.12f else cols * 0.88f, p2y = rows * 0.20f,
                    p3x = cx + side * 6f,
                    side = side, growth = ((farG - 0.15f) / 0.85f).coerceIn(0f, 1f), gen = gen,
                )
            }
        }

        // 3. DEEP UPPER CANOPY CURTAIN (grows downward from top edge across full width toward logoTopY!)
        val topCurtainCount = 11
        for (i in 0 until topCurtainCount) {
            val frac = (i + 0.5f) / topCurtainCount // 0.045 .. 0.955
            // Grow from outer edges inward as farG increases!
            val distFromEdge = min(frac, 1f - frac) * 2f // 0 at edge, 1 at center
            val localStart = distFromEdge * 0.45f
            if (farG <= localStart) continue
            val localG = ((farG - localStart) / (1f - localStart)).coerceIn(0f, 1f)

            val startX = cols * frac
            val swayTargetX = startX + (if (i % 2 == 0) 3.5f else -3.5f)
            val maxDepth = (logoTopY - 0.5f).coerceAtLeast(4f)
            val targetY = (2f + maxDepth * (0.28f + localG * 0.76f)).coerceAtMost(logoTopY)
            generateBackgroundCanopyVine(
                startX = startX, startY = 0.5f,
                targetX = swayTargetX.coerceIn(1f, cols - 2f), targetY = targetY,
                fill = localG,
                side = if (frac < 0.5f) -1 else 1,
                layer = 0,
                progressBase = 0.82f,
                gen = Random(7800 + i * 97 + cols * 13),
            )
        }

        // 4. DEEP LOWER UNDERGROWTH & MYCELIUM FIELD (grows upward from bottom edge toward logoBotY!)
        val botFieldCount = 11
        for (i in 0 until botFieldCount) {
            val frac = (i + 0.5f) / botFieldCount
            val distFromEdge = min(frac, 1f - frac) * 2f
            val localStart = distFromEdge * 0.42f
            if (farG <= localStart) continue
            val localG = ((farG - localStart) / (1f - localStart)).coerceIn(0f, 1f)

            val startX = cols * frac
            val swayTargetX = startX + (if (i % 2 == 0) -3.5f else 3.5f)
            val span = (bottomY - logoBotY).coerceAtLeast(4f)
            val targetY = (bottomY - span * (0.26f + localG * 0.78f)).coerceAtLeast(logoBotY)
            generateBackgroundCanopyVine(
                startX = startX, startY = bottomY,
                targetX = swayTargetX.coerceIn(1f, cols - 2f), targetY = targetY,
                fill = localG,
                side = if (frac < 0.5f) -1 else 1,
                layer = 0,
                progressBase = 0.08f,
                gen = Random(7950 + i * 101 + cols * 19),
            )
        }
    }

    /**
     * 2nd depth plane (Midground): grows progressively from late morning through evening across
     * all 4 corners, upper canopy, side flanks, and lower mycelium undergrowth — stopping cleanly
     * at the hard exclusion border around the KETTEN logo.
     */
    private fun generateMidgroundLayer(
        cx: Float,
        bottomY: Float,
        rootTopY: Float,
        leftJoinX: Float,
        rightJoinX: Float,
        midG: Float,
        gen: Random,
    ) {
        val logoTopY = logoExclMinY(pad = 1.0f)
        val logoBotY = logoExclMaxY(pad = 1.0f)

        // 1. Primary midground framing stems
        generateMidVine(
            p0x = leftJoinX + 2.5f, p0y = rootTopY,
            p1x = cols * 0.09f, p1y = rows * 0.56f,
            p2x = cols * 0.11f, p2y = rows * 0.18f,
            p3x = cx - 5f,
            side = -1, growth = midG, gen = gen,
        )
        generateMidVine(
            p0x = rightJoinX - 2.5f, p0y = rootTopY,
            p1x = cols * 0.91f, p1y = rows * 0.56f,
            p2x = cols * 0.89f, p2y = rows * 0.18f,
            p3x = cx + 5f,
            side = 1, growth = midG, gen = gen,
        )

        // 2. Corner-filling midground branches reaching into all 4 screen corners + sweeping inward
        for (side in intArrayOf(-1, 1)) {
            val edgeX = if (side < 0) 0.5f else cols - 1.5f
            val inward = -side

            val topOriginX = if (side < 0) cols * 0.14f else cols * 0.86f
            val topOriginY = rows * 0.16f
            generateBackgroundCornerSprig(
                startX = topOriginX, startY = topOriginY,
                targetX = edgeX, targetY = 0.5f,
                fillFactor = midG, side = side, layer = 1, progressBase = 0.84f,
                gen = Random(5100 + side * 191),
            )
            if (midG > 0.20f) {
                val subG = ((midG - 0.12f) / 0.88f).coerceIn(0f, 1f)
                generateBackgroundCornerSprig(
                    startX = topOriginX + inward * 4f, startY = rows * 0.11f,
                    targetX = edgeX + inward * 4f, targetY = 0.5f,
                    fillFactor = subG, side = side, layer = 1, progressBase = 0.88f,
                    gen = Random(5200 + side * 193),
                )
                generateBackgroundCornerSprig(
                    startX = if (side < 0) cols * 0.08f else cols * 0.92f, startY = rows * 0.24f,
                    targetX = edgeX, targetY = rows * 0.07f,
                    fillFactor = subG, side = side, layer = 1, progressBase = 0.78f,
                    gen = Random(5300 + side * 197),
                )
                // Arching inward canopy boughs above the logo box (fill top half richly!)
                generateBackgroundCanopyVine(
                    startX = if (side < 0) cols * 0.10f else cols * 0.90f,
                    startY = rows * 0.22f,
                    targetX = cx + side * 2f,
                    targetY = (logoTopY - 2f).coerceAtLeast(rows * 0.15f),
                    fill = subG, side = side, layer = 1, progressBase = 0.76f,
                    gen = Random(5340 + side * 223),
                )
                generateBackgroundCanopyVine(
                    startX = if (side < 0) cols * 0.16f else cols * 0.84f,
                    startY = rows * 0.06f,
                    targetX = cx + side * 4f,
                    targetY = (logoTopY - 5f).coerceAtLeast(rows * 0.10f),
                    fill = subG, side = side, layer = 1, progressBase = 0.86f,
                    gen = Random(5360 + side * 227),
                )
            }

            // Rising lower-field midground undergrowth between the substrate edge and the logo box
            // (no bottom-corner fans: the bottom quarter is reserved for the PCB substrate)
            if (midG > 0.25f) {
                val subG = ((midG - 0.18f) / 0.82f).coerceIn(0f, 1f)
                // Rising midground tendrils below the logo exclusion border
                generateBackgroundCanopyVine(
                    startX = if (side < 0) cols * 0.14f else cols * 0.86f,
                    startY = rootTopY - 0.5f,
                    targetX = cx + side * 6f,
                    targetY = (logoBotY + 2f).coerceAtMost(rows * 0.78f),
                    fill = subG, side = side, layer = 1, progressBase = 0.18f,
                    gen = Random(5540 + side * 229),
                )
                generateBackgroundCanopyVine(
                    startX = if (side < 0) cols * 0.28f else cols * 0.72f,
                    startY = rootTopY - 0.5f,
                    targetX = if (side < 0) cols * 0.36f else cols * 0.64f,
                    targetY = (logoBotY + 1.5f).coerceAtMost(rows * 0.76f),
                    fill = ((midG - 0.32f) / 0.68f).coerceIn(0f, 1f),
                    side = side, layer = 1, progressBase = 0.15f,
                    gen = Random(5580 + side * 233),
                )
            }
        }

        // 3. Midground upper-arch hanging boughs descending toward logoTopY
        if (midG > 0.28f) {
            val archG = ((midG - 0.28f) / 0.72f).coerceIn(0f, 1f)
            val xs = floatArrayOf(0.22f, 0.35f, 0.50f, 0.65f, 0.78f)
            for (i in xs.indices) {
                val sx = cols * xs[i]
                val depth = (logoTopY - 2.0f).coerceAtLeast(5f)
                val ty = (2.5f + depth * (0.25f + archG * 0.72f)).coerceAtMost(logoTopY)
                generateBackgroundCanopyVine(
                    startX = sx, startY = 1.5f,
                    targetX = sx + (if (i % 2 == 0) 3f else -3f), targetY = ty,
                    fill = archG,
                    side = if (xs[i] < 0.5f) -1 else 1,
                    layer = 1, progressBase = 0.85f,
                    gen = Random(5700 + i * 83),
                )
            }
        }
    }

    /**
     * Generates a rich, meandering background vine + side foliage tufts in layer 0 (far) or
     * layer 1 (mid) that grows smoothly with [fill] and stops sharply at the hard logo border.
     */
    private fun generateBackgroundCanopyVine(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        fill: Float,
        side: Int,
        layer: Int,
        progressBase: Float,
        gen: Random,
    ) {
        if (fill <= 0.05f) return
        val dist = hypot(targetX - startX, targetY - startY).coerceAtLeast(1f)
        val totalSteps = (dist * (0.22f + fill * 0.86f)).roundToInt().coerceIn(2, 34)
        val stemKind = if (layer == 0) NodeKind.FAR_VINE else NodeKind.MID_STALK
        val leafKind = if (layer == 0) NodeKind.FAR_LEAF else NodeKind.MID_LEAF
        val gid = if (layer == 1) allocGroup(gen) else 0
        val dxU = (targetX - startX) / dist
        val dyU = (targetY - startY) / dist
        val perpX = -dyU
        val perpY = dxU
        val waveFreq = 1.4f + gen.nextFloat() * 1.4f
        val waveAmp = 1.1f + gen.nextFloat() * 1.6f
        val wavePhase = gen.nextFloat() * 6.28f

        var prevX = startX.roundToInt()
        var prevY = startY.roundToInt()
        for (step in 1..totalSteps) {
            val u = step.toFloat() / totalSteps
            val reach = step.toFloat() / dist
            val wave = sin(u * PI.toFloat() * waveFreq + wavePhase) * waveAmp
            val px = (startX + (targetX - startX) * reach + perpX * wave).roundToInt()
            val py = (startY + (targetY - startY) * reach + perpY * wave).roundToInt()
            if (px !in 0 until cols || py !in 0 until rows) break
            // Hard stop at the logo exclusion border!
            if (isInsideLogoExclusion(px.toFloat(), py.toFloat(), pad = 0.5f)) break

            if (px != prevX || py != prevY) {
                val ch = dirChar(px - prevX, py - prevY)
                floraNodes.add(
                    FloraNode(
                        x = px.toFloat(), y = py.toFloat(), ch = ch,
                        kind = stemKind, side = side,
                        progress = (progressBase + u * 0.08f).coerceIn(0f, 1f),
                        groupId = gid, phase = gen.nextFloat() * 6.28f,
                        flex = if (layer == 0) 0.18f else 0.30f + u * 0.25f,
                    )
                )

                // Branching leaf tufts & mini-twigs along the background vine (richer as fill -> 1)
                val tuftStride = if (fill > 0.65f) 2 else 3
                if (step % tuftStride == 0) {
                    val twigDir = if ((step / tuftStride) % 2 == 0) 1 else -1
                    val tuftLen = if (fill > 0.70f) 3 else if (fill > 0.35f) 2 else 1
                    for (k in 1..tuftLen) {
                        val lx = (px + (perpX * twigDir * k).roundToInt()).coerceIn(0, cols - 1)
                        val ly = (py + (perpY * twigDir * k).roundToInt() + (if (k == 2) (if (dyU >= 0) 1 else -1) else 0)).coerceIn(0, rows - 1)
                        if (isInsideLogoExclusion(lx.toFloat(), ly.toFloat(), pad = 0.5f)) break
                        val leafCh = if (layer == 0) {
                            when {
                                k == 1 -> if (twigDir > 0) '/' else '\\'
                                k == 2 -> '~'
                                else -> ':'
                            }
                        } else {
                            when {
                                k == 1 -> if (twigDir > 0) '/' else '\\'
                                k == 2 -> '~'
                                else -> '\''
                            }
                        }
                        floraNodes.add(
                            FloraNode(
                                x = lx.toFloat(), y = ly.toFloat(), ch = leafCh,
                                kind = leafKind, side = side,
                                progress = (progressBase + u * 0.09f).coerceIn(0f, 1f),
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = if (layer == 0) 0.22f else 0.45f + k * 0.08f,
                                variant = k % 2,
                            )
                        )
                    }
                }
                prevX = px
                prevY = py
            }
        }
    }

    /**
     * Generates a single curved organic sprig in layer 0 (far) or layer 1 (mid) that grows
     * proportionally to [fillFactor] from (startX, startY) toward a rectangular corner target.
     */
    private fun generateBackgroundCornerSprig(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        fillFactor: Float,
        side: Int,
        layer: Int,
        progressBase: Float,
        gen: Random,
    ) {
        if (fillFactor <= 0.05f) return
        val dist = hypot(targetX - startX, targetY - startY).coerceAtLeast(1f)
        val totalSteps = (dist * (0.28f + fillFactor * 0.82f)).roundToInt().coerceAtLeast(2)
        val stemKind = if (layer == 0) NodeKind.FAR_VINE else NodeKind.MID_STALK
        val leafKind = if (layer == 0) NodeKind.FAR_LEAF else NodeKind.MID_LEAF
        val gid = if (layer == 1) allocGroup(gen) else 0
        val curveAmp = (gen.nextFloat() * 2.6f - 1.3f)

        var prevX = startX.roundToInt()
        var prevY = startY.roundToInt()
        val dxUnit = (targetX - startX) / dist
        val dyUnit = (targetY - startY) / dist
        val perpX = -dyUnit
        val perpY = dxUnit

        for (step in 1..totalSteps) {
            val u = step.toFloat() / totalSteps
            val reach = step.toFloat() / dist.coerceAtLeast(1f)
            val arch = sin(u * PI.toFloat()) * curveAmp
            val px = (startX + (targetX - startX) * reach + perpX * arch).roundToInt()
            val py = (startY + (targetY - startY) * reach + perpY * arch).roundToInt()
            if (px !in 0 until cols || py !in 0 until rows) break
            if (isInsideLogoExclusion(px.toFloat(), py.toFloat(), pad = 0.5f)) break

            if (px != prevX || py != prevY) {
                val ch = dirChar(px - prevX, py - prevY)
                floraNodes.add(
                    FloraNode(
                        x = px.toFloat(), y = py.toFloat(), ch = ch,
                        kind = stemKind, side = side,
                        progress = (progressBase + u * 0.08f).coerceIn(0f, 1f),
                        groupId = gid, phase = gen.nextFloat() * 6.28f,
                        flex = if (layer == 0) 0.16f else 0.28f + u * 0.25f,
                    )
                )

                // Soft atmospheric foliage clusters filling the corner around the sprig
                val leafInterval = if (fillFactor > 0.65f) 2 else 3
                if (step % leafInterval == 0) {
                    val ox = px + (if (gen.nextBoolean()) 1 else -1)
                    val oy = py + (if (gen.nextBoolean()) -1 else 1)
                    if (ox in 0 until cols && oy in 0 until rows &&
                        !isInsideLogoExclusion(ox.toFloat(), oy.toFloat(), pad = 0.5f)
                    ) {
                        val leafCh = if (layer == 0) {
                            if (gen.nextBoolean()) ':' else '~'
                        } else {
                            if (side < 0) '/' else '\\'
                        }
                        floraNodes.add(
                            FloraNode(
                                x = ox.toFloat(), y = oy.toFloat(), ch = leafCh,
                                kind = leafKind, side = side,
                                progress = (progressBase + u * 0.09f).coerceIn(0f, 1f),
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = if (layer == 0) 0.22f else 0.45f,
                                variant = 1,
                            )
                        )
                    }
                    // Extra density cluster when nearing evening peak
                    if (fillFactor > 0.50f) {
                        val ox2 = (px - side * (1 + gen.nextInt(2))).coerceIn(0, cols - 1)
                        val oy2 = (py + gen.nextInt(3) - 1).coerceIn(0, rows - 1)
                        if (!isInsideLogoExclusion(ox2.toFloat(), oy2.toFloat(), pad = 0.5f)) {
                            floraNodes.add(
                                FloraNode(
                                    x = ox2.toFloat(), y = oy2.toFloat(),
                                    ch = if (layer == 0) '.' else '~',
                                    kind = leafKind, side = side,
                                    progress = (progressBase + u * 0.09f).coerceIn(0f, 1f),
                                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                                    flex = if (layer == 0) 0.20f else 0.48f,
                                )
                            )
                        }
                    }
                }
                prevX = px
                prevY = py
            }
        }
    }

    private fun generateFarVine(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float,
        side: Int,
        growth: Float,
        gen: Random,
    ) {
        val p3y = 3.2f
        var lastX = -999
        var lastY = -999
        var step = 0
        var t = 0f
        val maxT = (0.28f + growth * 0.72f).coerceIn(0.25f, 1f)
        while (t <= maxT) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            if (ix != lastX || iy != lastY) {
                val ch = if (lastX == -999) '|' else dirChar(ix - lastX, iy - lastY)
                floraNodes.add(
                    FloraNode(
                        x = ix.toFloat(), y = iy.toFloat(), ch = ch,
                        kind = NodeKind.FAR_VINE, side = side, progress = t,
                        groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.16f,
                    )
                )
                if (growth > 0.30f && step % 4 == 0 && (iy < rows * 0.30f || iy > rows * 0.70f)) {
                    floraNodes.add(
                        FloraNode(
                            x = (ix - side * (1 + gen.nextInt(3))).coerceIn(0, cols - 1).toFloat(),
                            y = iy.toFloat(),
                            ch = if (gen.nextBoolean()) ':' else '.',
                            kind = NodeKind.FAR_LEAF, side = side, progress = t,
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.25f,
                        )
                    )
                }
                lastX = ix
                lastY = iy
                step++
            }
            t += 0.007f
        }
    }

    private fun generateMidVine(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float,
        side: Int,
        growth: Float,
        gen: Random,
    ) {
        val p3y = 2.8f
        var lastX = -999
        var lastY = -999
        var step = 0
        var t = 0f
        val maxT = (0.25f + growth * 0.75f).coerceIn(0.25f, 1f)
        while (t <= maxT) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            if (ix != lastX || iy != lastY) {
                val ch = if (lastX == -999) '|' else dirChar(ix - lastX, iy - lastY)
                floraNodes.add(
                    FloraNode(
                        x = ix.toFloat(), y = iy.toFloat(), ch = ch,
                        kind = NodeKind.MID_STALK, side = side, progress = 0.20f + 0.80f * t,
                        groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.22f,
                    )
                )
                val leafSpacing = (7 - (growth * 4f).roundToInt()).coerceIn(2, 7)
                if (step > 2 && step % leafSpacing == 0) {
                    val inTopOrBottom = iy < rows * 0.26f || iy > rows * 0.72f
                    val reach = if (inTopOrBottom) 2 + (growth * 3f).roundToInt() else 1 + (growth * 1.5f).roundToInt()
                    val lx = ix - side * reach
                    if (lx in 1 until cols - 1 && (inTopOrBottom || lx < cols * 0.22f || lx > cols * 0.78f)) {
                        floraNodes.add(
                            FloraNode(
                                x = lx.toFloat(), y = iy.toFloat(),
                                ch = if (side < 0) '/' else '\\',
                                kind = NodeKind.MID_LEAF, side = side,
                                progress = 0.20f + 0.80f * t,
                                groupId = allocGroup(gen),
                                phase = gen.nextFloat() * 6.28f, flex = 0.55f,
                            )
                        )
                    }
                }
                lastX = ix
                lastY = iy
                step++
            }
            t += 0.006f
        }
    }

    /**
     * Generates the bottom-quarter authentic DRC-clean PCB substrate (copper traces with pixel-exact
     * 45-degree mitered routing, parallel multi-lane buses, SMD IC pad footprints with clear
     * courtyards, plated through-hole vias, and ground stitching vias) with a subtle organic
     * mycelium bridge where top power vias feed the living vines:
     * - Morning (g ~ 0): clean single-lane main rails, USB-C nexus pads & primary vias.
     * - Day -> Evening (g -> 1): main rails thicken in copper width & expand into 2-4 parallel bus
     *   lanes, dual SMD IC footprints fan out cleanly with 45-degree routing, and corner buses +
     *   stitching vias populate the full bottom quarter with zero overlapping shorts.
     */
    private fun generateMyceliumNetwork(
        cx: Float,
        bottomY: Float,
        rootTopY: Float,
        leftJoinX: Float,
        rightJoinX: Float,
        g: Float,
        gen: Random,
    ) {
        // Aspect ratio converts horizontal cell units (u) to vertical cell units (v = u * aspect)
        // so every 45-degree PCB bend is an exact 45.0 degrees in screen pixels.
        val aspect = (cellW / cellH).coerceIn(0.45f, 0.85f)
        val bandH = (bottomY - rootTopY).coerceAtLeast(8f)
        val miterTan = 0.41421356f // tan(22.5 deg) = sqrt(2) - 1 for constant parallel bus spacing

        // 1. Bottom-center USB-C / Power Connector Footprint (SMD pads + silkscreen frame)
        val connTop = bottomY - 1.55f
        pcbSilks.add(
            PcbSilkRect(
                left = cx - 4.4f,
                top = connTop,
                right = cx + 4.4f,
                bottom = bottomY + 0.15f,
                pin1X = cx - 3.9f,
                pin1Y = connTop + 0.35f,
            )
        )
        for (p in -3..3) {
            if (p == 0) continue // Center space reserved for [=o=] ASCII nexus
            val px = cx + p * 1.15f
            pcbPads.add(
                PcbSmdPad(
                    x = px,
                    y = bottomY - 0.85f,
                    wCells = 0.52f,
                    hCells = 0.72f,
                    side = if (p < 0) -1 else 1,
                    progress = 0.02f,
                )
            )
        }

        // 2. Main Power Bus (DRC-clean: runs low beneath IC courtyard, 45-deg miter outside IC, climbs to vine base)
        // Grows smoothly in BOTH copper trace width AND parallel bus lane count over the day!
        val mainRailWidth = 0.17f + g * 0.17f // 0.17 (morning) -> 0.34 (evening thick copper!)
        val mainBusLanes = when {
            g < 0.18f -> 1
            g < 0.48f -> 2
            g < 0.78f -> 3
            else -> 4
        }
        val busPitch = 0.78f + g * 0.06f

        for (side in intArrayOf(-1, 1)) {
            val joinX = if (side < 0) leftJoinX else rightJoinX
            val startX = cx + side * 4.4f
            val baseLowY = bottomY - 1.25f - (mainBusLanes - 1) * busPitch * aspect * 0.78f
            val vLow = baseLowY / aspect
            val vTop = (rootTopY + 0.35f) / aspect

            // Outer vertical climb sits aligned with joinX; 45-deg elbow starts clear outside the IC bay
            val elbowDiagU = (4.2f + g * 1.4f).coerceAtMost(abs(joinX - startX) * 0.36f)
            val kneeU = joinX - side * elbowDiagU // horizontal run stops here, then 45-deg up-outward to joinX

            for (k in 0 until mainBusLanes) {
                // k = 0 is the outermost/thickest rail (lowest on horizontal, outermost on vertical climb)
                val s = k * busPitch
                val vH = vLow + s // downward shift for companion tracks along bottom
                val uKnee = kneeU + side * (s * miterTan)
                val uVert = (joinX + side * (-s)).coerceIn(1.8f, cols - 2.8f)
                val actualDiagU = abs(uVert - uKnee).coerceAtLeast(1.2f)
                val vAfterDiag = vH - actualDiagU
                val vEnd = (vTop + s * 0.85f).coerceAtMost(vAfterDiag - 1.0f)

                val xs = floatArrayOf(startX, uKnee, uVert, uVert)
                val ys = floatArrayOf(
                    (vH * aspect).coerceIn(rootTopY, bottomY - 0.35f),
                    (vH * aspect).coerceIn(rootTopY, bottomY - 0.35f),
                    (vAfterDiag * aspect).coerceIn(rootTopY + 0.5f, bottomY - 0.5f),
                    (vEnd * aspect).coerceIn(rootTopY - 0.3f, bottomY - 0.8f),
                )

                val laneWidth = when (k) {
                    0 -> mainRailWidth
                    1 -> mainRailWidth * 0.72f
                    else -> mainRailWidth * 0.54f
                }
                pcbTraces.add(
                    PcbTrace(
                        ptsX = xs,
                        ptsY = ys,
                        widthFactor = laneWidth,
                        tier = if (k == 0) 2 else 1,
                        side = side,
                        progressStart = 0.02f,
                        progressEnd = 0.22f,
                    )
                )

                val isPrimary = k == 0
                // Plated power via at the upper vine junction
                pcbVias.add(
                    PcbVia(
                        x = xs[3],
                        y = ys[3],
                        outerRadiusCells = if (isPrimary) 0.50f else 0.34f,
                        drillRadiusCells = if (isPrimary) 0.24f else 0.16f,
                        tier = if (isPrimary) 2 else 1,
                        side = side,
                        progress = 0.21f,
                        phase = gen.nextFloat() * 6.28f,
                    )
                )
                // Mid-climb transition via on the vertical trunk
                if (k <= 1 && (isPrimary || g > 0.35f)) {
                    val midY = (ys[2] + ys[3]) * 0.5f
                    pcbVias.add(
                        PcbVia(
                            x = xs[2],
                            y = midY,
                            outerRadiusCells = if (isPrimary) 0.38f else 0.28f,
                            drillRadiusCells = if (isPrimary) 0.18f else 0.13f,
                            tier = if (isPrimary) 2 else 1,
                            side = side,
                            progress = 0.14f,
                            phase = gen.nextFloat() * 6.28f,
                        )
                    )
                }
            }
        }

        // 3. Central Vertical Data Spine + Upper-Quarter Parallel Horizontal Buses
        // Stays strictly in the upper band (rootTopY + 0.8 .. rootTopY + 4.8), completely clear above ICs!
        val spineLanes = if (g < 0.22f) 1 else if (g < 0.62f) 2 else 3
        val upperShelfBaseY = rootTopY + bandH * (0.24f - g * 0.08f)
        for (side in intArrayOf(-1, 1)) {
            val maxShelfReach = abs((if (side < 0) leftJoinX else rightJoinX) - cx) - 3.6f
            for (k in 0 until spineLanes) {
                val lanePitch = 0.78f
                val laneX = cx + side * (1.25f + k * lanePitch)
                val shelfY = (upperShelfBaseY + k * lanePitch * aspect).coerceIn(rootTopY + 0.8f, rootTopY + bandH * 0.34f)
                val diagU = 2.0f + g * 1.6f
                val bendY = shelfY + diagU * aspect
                val targetLen = (diagU + 4.5f + g * 8.5f - k * 1.1f).coerceAtMost(maxShelfReach - k * 0.9f)
                val shelfEndX = (laneX + side * targetLen).coerceIn(leftJoinX + 2.5f, rightJoinX - 2.5f)

                pcbTraces.add(
                    PcbTrace(
                        ptsX = floatArrayOf(laneX, laneX, laneX + side * diagU, shelfEndX),
                        ptsY = floatArrayOf(bottomY - 1.65f, bendY, shelfY, shelfY),
                        widthFactor = if (k == 0) (0.14f + g * 0.08f) else 0.11f,
                        tier = if (k == 0) 2 else 1,
                        side = side,
                        progressStart = 0.03f,
                        progressEnd = 0.19f,
                    )
                )

                // Staggered via fan-out at bus end
                pcbVias.add(
                    PcbVia(
                        x = shelfEndX,
                        y = shelfY,
                        outerRadiusCells = if (k == 0) 0.40f else 0.31f,
                        drillRadiusCells = if (k == 0) 0.19f else 0.14f,
                        tier = if (k == 0) 2 else 1,
                        side = side,
                        progress = 0.18f,
                        phase = gen.nextFloat() * 6.28f,
                    )
                )

                // Short upward 45-deg tap stub near top border (day -> evening)
                if (g > 0.30f && k == 0) {
                    val tapX = laneX + side * (diagU + 2.6f)
                    val tapDiag = 1.4f + g * 1.0f
                    val tapEndX = (tapX + side * tapDiag).coerceIn(leftJoinX + 3f, rightJoinX - 3f)
                    val tapEndY = (shelfY - tapDiag * aspect).coerceAtLeast(rootTopY + 0.35f)
                    val tipX = (tapEndX + side * (2.2f + g * 2.4f)).coerceIn(leftJoinX + 3f, rightJoinX - 3f)
                    pcbTraces.add(
                        PcbTrace(
                            ptsX = floatArrayOf(tapX, tapEndX, tipX),
                            ptsY = floatArrayOf(shelfY, tapEndY, tapEndY),
                            widthFactor = 0.105f,
                            tier = 1,
                            side = side,
                            progressStart = 0.14f,
                            progressEnd = 0.22f,
                        )
                    )
                    pcbVias.add(
                        PcbVia(
                            x = tipX,
                            y = tapEndY,
                            outerRadiusCells = 0.33f,
                            drillRadiusCells = 0.15f,
                            tier = 1,
                            side = side,
                            progress = 0.22f,
                            phase = gen.nextFloat() * 6.28f,
                        )
                    )
                }
            }
        }

        // 4. Dual SMD IC Footprints (SOIC / QFN Pad Clusters + 45-degree Fan-Out Vias)
        // Positioned cleanly in the open courtyard bay between upper data bus & lower power bus!
        if (g > 0.14f) {
            val icG = ((g - 0.14f) / 0.86f).coerceIn(0f, 1f)
            val pinsPerRow = if (icG < 0.45f) 4 else if (icG < 0.78f) 5 else 6
            val pinPitch = 1.02f
            val icCenterY = rootTopY + bandH * 0.56f

            for (side in intArrayOf(-1, 1)) {
                val icCenterX = cx + side * (cols * 0.23f)
                val halfW = (pinsPerRow - 1) * pinPitch * 0.5f + 0.62f
                val halfH = 1.15f

                // Silkscreen body outline + Pin-1 dot
                pcbSilks.add(
                    PcbSilkRect(
                        left = icCenterX - halfW,
                        top = icCenterY - halfH,
                        right = icCenterX + halfW,
                        bottom = icCenterY + halfH,
                        pin1X = icCenterX - halfW + 0.42f,
                        pin1Y = icCenterY - halfH + 0.42f,
                    )
                )

                for (pin in 0 until pinsPerRow) {
                    val px = icCenterX - (pinsPerRow - 1) * pinPitch * 0.5f + pin * pinPitch
                    val topPadY = icCenterY - halfH - 0.22f
                    val botPadY = icCenterY + halfH + 0.22f

                    pcbPads.add(
                        PcbSmdPad(
                            x = px, y = topPadY, wCells = 0.46f, hCells = 0.52f,
                            side = side, progress = 0.11f,
                        )
                    )
                    pcbPads.add(
                        PcbSmdPad(
                            x = px, y = botPadY, wCells = 0.46f, hCells = 0.52f,
                            side = side, progress = 0.09f,
                        )
                    )

                    // Top pin 45-degree fan-out to staggered vias (stays strictly below upper horizontal bus)
                    val fanDir = if (pin < pinsPerRow / 2) -1 else 1
                    val stubUp = (0.55f + (pin % 2) * 0.42f) * aspect
                    val diagU = 0.85f + icG * 0.75f
                    val viaX = (px + fanDir * diagU).coerceIn(leftJoinX + 1.8f, rightJoinX - 1.8f)
                    val viaY = (topPadY - stubUp - diagU * aspect).coerceAtLeast(upperShelfBaseY + 2.1f)
                    pcbTraces.add(
                        PcbTrace(
                            ptsX = floatArrayOf(px, px, viaX),
                            ptsY = floatArrayOf(topPadY, topPadY - stubUp, viaY),
                            widthFactor = 0.10f,
                            tier = 1,
                            side = side,
                            progressStart = 0.11f,
                            progressEnd = 0.17f,
                        )
                    )
                    pcbVias.add(
                        PcbVia(
                            x = viaX,
                            y = viaY,
                            outerRadiusCells = 0.30f,
                            drillRadiusCells = 0.14f,
                            tier = 1,
                            side = side,
                            progress = 0.17f,
                            phase = gen.nextFloat() * 6.28f,
                        )
                    )

                    // Bottom pin 45-degree fan-out downward (stays strictly above lower power bus)
                    if (icG > 0.22f && (pin == 0 || pin == pinsPerRow - 1 || pin % 2 == 0)) {
                        val stubDown = (0.48f + (pin % 2) * 0.38f) * aspect
                        val diagDown = 0.85f + icG * 0.85f
                        val bViaX = (px + fanDir * diagDown).coerceIn(leftJoinX + 1.8f, rightJoinX - 1.8f)
                        val maxLowerViaY = (bottomY - 3.6f - mainBusLanes * 0.45f).coerceAtLeast(botPadY + 0.8f)
                        val bViaY = (botPadY + stubDown + diagDown * aspect).coerceAtMost(maxLowerViaY)
                        pcbTraces.add(
                            PcbTrace(
                                ptsX = floatArrayOf(px, px, bViaX),
                                ptsY = floatArrayOf(botPadY, botPadY + stubDown, bViaY),
                                widthFactor = 0.095f,
                                tier = 1,
                                side = side,
                                progressStart = 0.07f,
                                progressEnd = 0.12f,
                            )
                        )
                        pcbVias.add(
                            PcbVia(
                                x = bViaX,
                                y = bViaY,
                                outerRadiusCells = 0.28f,
                                drillRadiusCells = 0.13f,
                                tier = 1,
                                side = side,
                                progress = 0.08f,
                                phase = gen.nextFloat() * 6.28f,
                            )
                        )
                    }
                }
            }
        }

        // 5. Outer Corner Breakout Buses & Outer Edge Vertical Rails (filling bottom-left & bottom-right corners!)
        if (g > 0.18f) {
            val cornerG = ((g - 0.18f) / 0.82f).coerceIn(0f, 1f)
            val edgeLanes = if (cornerG < 0.55f) 1 else 2

            for (side in intArrayOf(-1, 1)) {
                val joinX = if (side < 0) leftJoinX else rightJoinX
                for (k in 0 until edgeLanes) {
                    val ex = if (side < 0) (1.5f + k * 0.82f) else (cols - 2.5f - k * 0.82f)
                    val cornerBotY = bottomY - 0.85f - k * 0.72f
                    val tapStartX = joinX - side * (1.2f + k * 0.9f)
                    val diagOutU = abs(ex - tapStartX).coerceAtLeast(1.4f)
                    val tapStartY = (cornerBotY - diagOutU * aspect).coerceAtLeast(rootTopY + bandH * 0.55f)

                    // Lower corner diagonal branch from main bus elbow down into screen corner
                    pcbTraces.add(
                        PcbTrace(
                            ptsX = floatArrayOf(tapStartX, ex, ex),
                            ptsY = floatArrayOf(tapStartY, cornerBotY, (rootTopY + bandH * (0.42f - cornerG * 0.35f) + k * 0.9f)),
                            widthFactor = if (k == 0) (0.14f + cornerG * 0.06f) else 0.10f,
                            tier = if (k == 0) 2 else 1,
                            side = side,
                            progressStart = 0.08f,
                            progressEnd = 0.21f,
                        )
                    )
                    // Corner via at bottom-outer elbow
                    pcbVias.add(
                        PcbVia(
                            x = ex,
                            y = cornerBotY,
                            outerRadiusCells = if (k == 0) 0.38f else 0.28f,
                            drillRadiusCells = if (k == 0) 0.18f else 0.13f,
                            tier = if (k == 0) 2 else 1,
                            side = side,
                            progress = 0.09f,
                            phase = gen.nextFloat() * 6.28f,
                        )
                    )
                    // Upper outer-edge via feeding outer foliage
                    val topEdgeY = rootTopY + bandH * (0.42f - cornerG * 0.35f) + k * 0.9f
                    pcbVias.add(
                        PcbVia(
                            x = ex,
                            y = topEdgeY,
                            outerRadiusCells = if (k == 0) 0.42f else 0.30f,
                            drillRadiusCells = if (k == 0) 0.20f else 0.14f,
                            tier = if (k == 0) 2 else 1,
                            side = side,
                            progress = 0.21f,
                            phase = gen.nextFloat() * 6.28f,
                        )
                    )
                }
            }
        }

        // 6. Ground-Plane Stitching Vias (neat geometric via columns in open copper bays at higher growth)
        if (g > 0.38f) {
            val stitchG = ((g - 0.38f) / 0.62f).coerceIn(0f, 1f)
            val rowsCount = (2 + stitchG * 3f).roundToInt()
            for (side in intArrayOf(-1, 1)) {
                // Neat vertical stitching via column tucked cleanly between center spine and IC bay
                val colX = cx + side * 6.2f
                for (r in 0 until rowsCount) {
                    val vy = rootTopY + bandH * 0.38f + r * 1.45f
                    if (vy < bottomY - 3.8f) {
                        pcbVias.add(
                            PcbVia(
                                x = colX,
                                y = vy,
                                outerRadiusCells = 0.25f,
                                drillRadiusCells = 0.115f,
                                tier = 0,
                                side = side,
                                progress = 0.08f + r * 0.02f,
                                phase = gen.nextFloat() * 6.28f,
                            )
                        )
                    }
                }
            }
        }

        // 7. Copper -> root transition at the foot of each vine.
        // Every power lane that climbs to the vine base is met by an organic root that grows down
        // from the trunk foot, tapers from thick bark to fine root hair and grafts onto the copper
        // in a plated via ("the trace becomes a root"). Below the graft point there is only copper.
        // Roots deepen and multiply with the bus lanes over the day; pulses climb them into the trunk.
        for (side in intArrayOf(-1, 1)) {
            val joinX = if (side < 0) leftJoinX else rightJoinX
            for (k in 0 until mainBusLanes) {
                val laneS = k * busPitch
                val laneX = (joinX + side * (-laneS)).coerceIn(1.8f, cols - 2.8f)
                val laneTopY = rootTopY + 0.35f + laneS * 0.85f * aspect
                val depth = (1.6f + g * bandH * 0.34f) * (1f - 0.12f * k)
                val graftY = (laneTopY + depth).coerceAtMost(bottomY - 3.2f)
                val startX = joinX - side * k * 0.35f
                val startY = rootTopY + 0.6f
                val steps = ((graftY - startY) * 1.25f).roundToInt().coerceAtLeast(2)
                val wobblePhase = gen.nextFloat() * 6.28f
                var prevX = startX.roundToInt()
                var prevY = startY.roundToInt() - 1
                for (j in 0..steps) {
                    val f = j.toFloat() / steps
                    val ease = f * f * (3f - 2f * f)
                    val wobble = sin(f * PI.toFloat() * 2.4f + wobblePhase) * 1.15f * (1f - f * 0.8f)
                    val rx = (startX + (laneX - startX) * ease + wobble).roundToInt().coerceIn(1, cols - 2)
                    val ry = (startY + (graftY - startY) * f).roundToInt().coerceIn(0, rows - 1)
                    if (rx == prevX && ry == prevY) continue
                    val ch = when {
                        f > 0.88f -> '.'
                        f > 0.70f -> ':'
                        else -> dirChar(rx - prevX, ry - prevY)
                    }
                    // Upper part of the main root is a 2-cell wide bark cylinder continuing the trunk foot
                    if (k == 0 && f < 0.38f) {
                        val bx2 = (rx - side).coerceIn(1, cols - 2)
                        floraNodes.add(
                            FloraNode(
                                x = bx2.toFloat(), y = ry.toFloat(), ch = if (side < 0) ')' else '(',
                                kind = NodeKind.ROOT_ORGANIC, side = side,
                                progress = 0.22f - 0.05f * f,
                                groupId = 0, phase = 0f, flex = 0f, shade = 0.55f - f,
                            )
                        )
                    }
                    floraNodes.add(
                        FloraNode(
                            x = rx.toFloat(), y = ry.toFloat(), ch = ch,
                            kind = NodeKind.ROOT_ORGANIC, side = side,
                            progress = 0.22f - 0.05f * f, // pulse climbs from the graft up into the trunk
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0f,
                            shade = 0.45f - 1.35f * f,
                        )
                    )
                    prevX = rx
                    prevY = ry
                }
                // Graft point: root nodule sitting on a plated via of the lane
                floraNodes.add(
                    FloraNode(
                        x = laneX.roundToInt().toFloat(), y = graftY.roundToInt().toFloat(), ch = 'o',
                        kind = NodeKind.ROOT_PAD, side = side, progress = 0.17f,
                        groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0f,
                    )
                )
                pcbVias.add(
                    PcbVia(
                        x = laneX, y = graftY.roundToInt().toFloat(),
                        outerRadiusCells = if (k == 0) 0.42f else 0.32f,
                        drillRadiusCells = if (k == 0) 0.20f else 0.15f,
                        tier = 1, side = side, progress = 0.17f,
                        phase = gen.nextFloat() * 6.28f,
                    )
                )
            }

            // Fine lateral root hairs just under the substrate edge, spreading as the day advances
            val hairLen = (1 + g * 4f).roundToInt()
            for (dir in intArrayOf(-1, 1)) {
                for (h in 1..hairLen) {
                    val hx = (joinX + dir * (h + 0.5f)).roundToInt().coerceIn(1, cols - 2)
                    val hy = (rootTopY + 0.8f + h * 0.28f).roundToInt().coerceIn(0, rows - 1)
                    floraNodes.add(
                        FloraNode(
                            x = hx.toFloat(), y = hy.toFloat(),
                            ch = if (h == hairLen) '.' else if (h % 2 == 0) ',' else '~',
                            kind = NodeKind.ROOT_ORGANIC, side = side, progress = 0.21f,
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0f,
                            shade = -0.55f - h * 0.08f,
                        )
                    )
                }
            }
        }
    }

    /**
     * Generates the main framing vine following the signature Ketten arch:
     * - Morning (g ~ 0): slender 1-cell vine with NO branches, only initial leaves & buds at fixed nodes.
     * - Day -> Evening (g -> 1): thickens into a cylindrical 3D shaded trunk with bark nerves & warm rim-light,
     *   while branches sprout directly from the exact initial leaf nodes to fill all rectangular corners.
     */
    private fun generateMainVineAndBranches(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float,
        side: Int,
        g: Float,
        anchors: MutableList<SproutAnchor>,
        gen: Random,
    ) {
        val p3y = 2.6f
        var lastX = -999
        var lastY = -999
        var stepCounter = 0
        var t = 0f

        // Morning (g=0): 1 cell slender vine -> Evening peak (g=1): 4-5 cell volumetric 3D trunk
        val baseThickness = 1.05f + g * 3.65f
        val inwardDir = -side // +1 for left vine (toward center), -1 for right vine
        val outwardDir = side // -1 for left vine (toward left edge/corners), +1 for right vine

        // Pre-pass: count the grid cells along the vine so branch progress can be expressed in the
        // same "progress per cell" unit as the trunk (0.78 progress spread over the trunk length).
        run {
            var cells = 0
            var px = Int.MIN_VALUE
            var py = Int.MIN_VALUE
            var tt = 0f
            while (tt <= 1f) {
                val qx = bezier(tt, p0x, p1x, p2x, p3x).roundToInt()
                val qy = bezier(tt, p0y, p1y, p2y, p3y).roundToInt()
                if (qx != px || qy != py) {
                    cells++
                    px = qx
                    py = qy
                }
                tt += 0.0045f
            }
            progPerCell = 0.78f / cells.coerceAtLeast(8)
        }

        // Fixed sprout node positions along the vine: initial leaves sit here in the morning,
        // and branches sprout directly out of these exact nodes as the day progresses.
        val anchorTs = floatArrayOf(0.08f, 0.16f, 0.25f, 0.34f, 0.43f, 0.52f, 0.61f, 0.70f, 0.78f, 0.86f, 0.94f)
        var nextAnchorIdx = 0

        while (t <= 1f) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()

            if (ix != lastX || iy != lastY) {
                val dx = if (lastX == -999) 0 else ix - lastX
                val dy = if (lastY == -999) -1 else iy - lastY
                val prog = 0.22f + 0.78f * t

                val taper = (1f - t * 0.68f).coerceIn(0.32f, 1f)
                // Secondary growth climbs: the foot thickens first, the upper trunk follows later in
                // the day, so the widening visibly travels up the stem instead of scaling all at once.
                val climbG = ((g - 0.12f - 0.30f * t) / (0.88f - 0.30f * t)).coerceIn(0f, 1f)
                val localThickness = 1.05f + (baseThickness - 1.05f) * climbG
                val colWidth = if (g < 0.12f) {
                    1
                } else {
                    (localThickness * taper).roundToInt().coerceIn(1, 5)
                }

                for (w in 0 until colWidth) {
                    val u = if (colWidth == 1) 0.72f else (w.toFloat() / (colWidth - 1).toFloat())
                    val cylShade = u * 2f - 1f // -1.0 (outer shadow) .. +1.0 (inner warm rim)
                    val tx = (ix + inwardDir * (w - colWidth / 2)).coerceIn(0, cols - 1)

                    val barkWave = sin(t * 28f + w * 1.45f + side)
                    val isBarkNerve = colWidth >= 3 && w in 1 until (colWidth - 1) && abs(barkWave) > 0.68f

                    val ch = when {
                        colWidth == 1 -> dirChar(dx, dy)
                        w == 0 -> if (side < 0) '(' else ')'
                        w == colWidth - 1 -> if (side < 0) ')' else '('
                        isBarkNerve -> when {
                            dx != 0 && dy != 0 -> dirChar(dx, dy)
                            barkWave > 0f -> if (side < 0) ')' else '('
                            else -> ':'
                        }
                        w == 1 -> if (colWidth >= 4) '▒' else '│'
                        else -> if (stepCounter % 3 == 0) '┃' else '|'
                    }

                    floraNodes.add(
                        FloraNode(
                            x = tx.toFloat(),
                            y = iy.toFloat(),
                            ch = ch,
                            kind = NodeKind.TRUNK_3D,
                            side = side,
                            progress = prog,
                            groupId = 0,
                            phase = gen.nextFloat() * 6.28f,
                            flex = 0.14f + t * 0.08f,
                            shade = cylShade,
                            variant = if (isBarkNerve) 1 else 0,
                        )
                    )
                }

                // Intertwining organic tendril hugs the trunk once it thickens past morning
                if (stepCounter % 3 == 0 && g > 0.25f) {
                    val wrapOffset = sin(t * 20f + side).roundToInt()
                    val tx = (ix + inwardDir * (colWidth / 2 + 1) + wrapOffset).coerceIn(1, cols - 2)
                    val tChar = when {
                        wrapOffset > 0 -> ')'
                        wrapOffset < 0 -> '('
                        else -> '~'
                    }
                    floraNodes.add(
                        FloraNode(
                            x = tx.toFloat(), y = iy.toFloat(), ch = tChar,
                            kind = NodeKind.TENDRIL, side = side, progress = prog,
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.30f,
                        )
                    )
                }

                // Check if we reached one of our fixed leaf/branch sprout nodes
                if (nextAnchorIdx < anchorTs.size && t >= anchorTs[nextAnchorIdx]) {
                    val anchorIdx = nextAnchorIdx++
                    val anchor = SproutAnchor(
                        x = ix.toFloat(),
                        y = iy.toFloat(),
                        side = side,
                    )
                    anchors.add(anchor)

                    val nodeRng = Random(4201 + side * 997 + anchorIdx * 131 + cols * 17)
                    val gid = allocGroup(nodeRng)

                    // 1. INITIAL MORNING LEAF on the vine at this node. Once the branch from this node
                    // has matured the leaf drops: the thickening wood stays bare, foliage moves outward.
                    val startG = 0.08f + (anchorIdx % 3) * 0.055f
                    val leafSideDir = if (anchorIdx % 2 == 0) inwardDir else outwardDir
                    val leafX = (ix + leafSideDir * (colWidth / 2 + 1)).coerceIn(0, cols - 1)
                    val leafY = (iy - (if (t > 0.80f) 0 else 1)).coerceIn(0, rows - 1)
                    if (g < startG + 0.22f) floraNodes.add(
                        FloraNode(
                            x = leafX.toFloat(), y = leafY.toFloat(),
                            ch = if (leafSideDir > 0) '/' else '\\',
                            kind = NodeKind.LEAF, side = side, progress = prog,
                            groupId = gid, phase = nodeRng.nextFloat() * 6.28f,
                            flex = 0.65f, variant = if (anchorIdx % 3 == 0) 2 else 1,
                        )
                    )
                    // Small morning leaf bud/speckle on alternating nodes when unbranched
                    if (g < 0.16f && anchorIdx % 2 == 0) {
                        val budX = (leafX + leafSideDir).coerceIn(0, cols - 1)
                        floraNodes.add(
                            FloraNode(
                                x = budX.toFloat(), y = iy.toFloat(),
                                ch = '*',
                                kind = NodeKind.BUD, side = side, progress = prog,
                                groupId = gid, phase = nodeRng.nextFloat() * 6.28f,
                                flex = 0.72f,
                            )
                        )
                    }

                    // 2. DAY -> EVENING BRANCHING DIRECTLY FROM THIS LEAF NODE!
                    // Zero branches in morning (g <= 0.08); branches sprout & fill the corners.
                    if (g > startG) {
                        val branchG = ((g - startG) / (0.88f - startG)).coerceIn(0f, 1f)
                        val edgeX = if (side < 0) 0.5f else cols - 1.5f

                        when {
                            // UPPER ARCH NODES -> fan outward & upward to completely fill TOP-LEFT & TOP-RIGHT corners!
                            t >= 0.52f -> {
                                val cornerProgress = ((t - 0.52f) / 0.44f).coerceIn(0f, 1f)
                                // Spread targets across the whole top corner triangle: outer edge -> (0,0) -> top edge
                                val targetX = when (anchorIdx) {
                                    5 -> edgeX                                        // Mid-upper outer wall
                                    6 -> edgeX                                        // Upper wall approaching corner
                                    7 -> edgeX                                        // Exact corner apex (0,0) / (cols-1,0)
                                    8 -> edgeX + inwardDir * (cols * 0.08f)           // Top edge right beside corner
                                    9 -> edgeX + inwardDir * (cols * 0.19f)           // Top edge mid-corner
                                    else -> edgeX + inwardDir * (cols * 0.31f)        // Upper arch fill
                                }
                                val targetY = when (anchorIdx) {
                                    5 -> rows * 0.20f
                                    6 -> rows * 0.06f
                                    7 -> 0.4f
                                    8 -> 0.4f
                                    9 -> 0.4f
                                    else -> 0.8f
                                }
                                val tip = growOrganicBranch(
                                    startX = ix + outwardDir * (colWidth / 2f),
                                    startY = iy.toFloat(),
                                    targetX = targetX,
                                    targetY = targetY,
                                    branchG = branchG,
                                    side = side,
                                    progBase = prog,
                                    gid = gid,
                                    allowInCenter = true,
                                    gen = nodeRng,
                                )
                                anchor.tipX = tip.first
                                anchor.tipY = tip.second

                                // Extra corner-filling twin bough only from node 7 (fewer, clearer limbs: the
                                // hanging side twigs now provide the volume in the corners)
                                if (anchorIdx == 7 && branchG > 0.35f) {
                                    val cG = ((branchG - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                    val cTargetX = if (anchorIdx == 7) edgeX else edgeX + inwardDir * 3f
                                    val cTargetY = if (anchorIdx == 7) rows * 0.04f else 0.4f
                                    growOrganicBranch(
                                        startX = ix + outwardDir * (colWidth / 2f),
                                        startY = iy.toFloat(),
                                        targetX = cTargetX,
                                        targetY = cTargetY,
                                        branchG = cG,
                                        side = side,
                                        progBase = prog,
                                        gid = allocGroup(nodeRng),
                                        allowInCenter = true,
                                        gen = nodeRng,
                                    )
                                }

                                // Secondary inward canopy bough on every other high-arch node once growth matures
                                if (anchorIdx % 2 == 1 && cornerProgress > 0.35f && branchG > 0.32f) {
                                    val inG = ((branchG - 0.32f) / 0.68f).coerceIn(0f, 1f)
                                    val inTargetX = (ix + inwardDir * (5f + inG * 7f)).coerceIn(2f, cols - 3f)
                                    val inTargetY = (iy - 1.5f + cornerProgress * 3.5f).coerceIn(1f, rows * 0.24f)
                                    growOrganicBranch(
                                        startX = ix + inwardDir * (colWidth / 2f),
                                        startY = iy.toFloat(),
                                        targetX = inTargetX,
                                        targetY = inTargetY,
                                        branchG = inG * 0.85f,
                                        side = side,
                                        progBase = prog,
                                        gid = allocGroup(nodeRng),
                                        allowInCenter = true,
                                        gen = nodeRng,
                                    )
                                }
                            }

                            // LOWER TRUNK NODES -> reach outward to the screen edge just above the PCB substrate
                            // (the bottom quarter stays free of vegetation); their twigs hang down towards it.
                            t < 0.24f -> {
                                val ceilY = substrateTopY - 2.0f
                                val tip = growOrganicBranch(
                                    startX = ix + outwardDir * (colWidth / 2f),
                                    startY = iy.toFloat(),
                                    targetX = edgeX,
                                    targetY = (iy - 3.5f).coerceAtMost(ceilY),
                                    branchG = branchG,
                                    side = side,
                                    progBase = prog,
                                    gid = gid,
                                    allowInCenter = false,
                                    gen = nodeRng,
                                )
                                anchor.tipX = tip.first
                                anchor.tipY = tip.second

                                // Short inward lower sprig (stays between the logo zone and the substrate)
                                if (branchG > 0.35f) {
                                    growOrganicBranch(
                                        startX = ix + inwardDir * (colWidth / 2f),
                                        startY = iy.toFloat(),
                                        targetX = ix + inwardDir * (4f + branchG * 6f),
                                        targetY = (iy - 2.5f).coerceAtMost(ceilY),
                                        branchG = branchG * 0.8f,
                                        side = side,
                                        progBase = prog,
                                        gid = allocGroup(nodeRng),
                                        allowInCenter = true,
                                        gen = nodeRng,
                                    )
                                }
                            }

                            // MID-TRUNK NODES -> fill outer side margin + modest inward bough respecting logo sanctuary
                            else -> {
                                // Outward sprig hugging the left/right screen edge
                                val outTip = growOrganicBranch(
                                    startX = ix + outwardDir * (colWidth / 2f),
                                    startY = iy.toFloat(),
                                    targetX = edgeX,
                                    targetY = (iy - 5.5f).coerceAtLeast(2f),
                                    branchG = branchG,
                                    side = side,
                                    progBase = prog,
                                    gid = gid,
                                    allowInCenter = false,
                                    gen = nodeRng,
                                )
                                // Inward framing bough (stops cleanly before central KETTEN logo sanctuary)
                                val inTip = growOrganicBranch(
                                    startX = ix + inwardDir * (colWidth / 2f),
                                    startY = iy.toFloat(),
                                    targetX = ix + inwardDir * (4f + branchG * 6f),
                                    targetY = (iy - 4.0f).coerceAtLeast(2f),
                                    branchG = branchG * 0.9f,
                                    side = side,
                                    progBase = prog,
                                    gid = allocGroup(nodeRng),
                                    allowInCenter = false,
                                    gen = nodeRng,
                                )
                                anchor.tipX = if (anchorIdx % 2 == 0) inTip.first else outTip.first
                                anchor.tipY = if (anchorIdx % 2 == 0) inTip.second else outTip.second
                            }
                        }
                    }
                }

                lastX = ix
                lastY = iy
                stepCounter++
            }
            t += 0.0045f
        }
    }

    /**
     * Grows a curved, organic foreground branch with sub-twigs, layered leaves, and glowing tip bud
     * from (startX, startY) toward (targetX, targetY) proportional to [branchG] (0..1).
     * Returns the (tipX, tipY) of the branch tip.
     */
    private fun growOrganicBranch(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        branchG: Float,
        side: Int,
        progBase: Float,
        gid: Int,
        allowInCenter: Boolean,
        gen: Random,
    ): Pair<Float, Float> {
        val dist = hypot(targetX - startX, targetY - startY).coerceAtLeast(1f)
        val desiredLen = (dist * (0.18f + branchG * 0.90f)).coerceAtMost(dist + 1.5f)
        val steps = desiredLen.roundToInt().coerceIn(1, 24)

        val dxU = (targetX - startX) / dist
        val dyU = (targetY - startY) / dist
        val perpX = -dyU
        val perpY = dxU
        val archAmp = (gen.nextFloat() * 2.2f - 1.1f)

        var prevX = startX.roundToInt()
        var prevY = startY.roundToInt()
        var lastValidX = startX
        var lastValidY = startY

        // Old wood near the trunk thickens as the branch matures (double stroke, bark colour, bare),
        // foliage only grows on the younger outer part, and the outer half sags under its own weight.
        val thickU = if (branchG > 0.25f) 0.12f + 0.40f * ((branchG - 0.25f) / 0.75f) else 0f
        val leafStartU = (thickU + 0.12f).coerceAtLeast(0.25f)
        val sagMax = desiredLen * (0.08f + 0.26f * branchG)
        // Offset towards the underside of the branch for the second (thick) wood stroke
        val underX = if (perpY >= 0f) perpX else -perpX
        val underY = if (perpY >= 0f) perpY else -perpY
        val maxY = substrateTopY - 1.2f

        for (s in 1..steps) {
            val u = s.toFloat() / steps
            val travel = s.toFloat() / dist
            val arch = sin(u * PI.toFloat()) * archAmp
            val sag = sagMax * u * u
            val bx = (startX + (targetX - startX) * travel + perpX * arch).roundToInt()
            val by = (startY + (targetY - startY) * travel + perpY * arch + sag).roundToInt()

            if (bx !in 0 until cols || by !in 0 until rows) break
            if (by > maxY) break
            // Hard exclusion border around KETTEN logo + readout (nothing may grow inside!)
            if (isInsideLogoExclusion(bx.toFloat(), by.toFloat(), pad = 0.5f)) break
            if (!allowInCenter && by in (rows * 0.24f).toInt()..(rows * 0.72f).toInt() &&
                bx > cols * 0.22f && bx < cols * 0.78f
            ) {
                break
            }

            if (bx != prevX || by != prevY) {
                val bChar = dirChar(bx - prevX, by - prevY)
                // Cantilever-like bend profile: almost rigid where the branch leaves the trunk
                // (so it stays attached), increasingly flexible towards the tip.
                val bFlex = 0.16f + 1.12f * (u * u * (3f - u) * 0.5f)
                // Arc-length progress: an energy pulse continues from the trunk into the branch
                // at the same speed in cells/s instead of lighting the whole branch at once.
                val bProg = progBase + s * progPerCell
                val oldWood = u <= thickU
                val tipDepth = (u * u * 0.45f * branchG).coerceIn(0f, 0.45f)

                floraNodes.add(
                    FloraNode(
                        x = bx.toFloat(), y = by.toFloat(), ch = bChar,
                        kind = NodeKind.BRANCH, side = side, progress = bProg,
                        groupId = gid, phase = gen.nextFloat() * 6.28f, flex = bFlex,
                        variant = if (oldWood) 1 else 0, depth = tipDepth,
                    )
                )
                if (oldWood) {
                    val ox = (bx + underX).roundToInt()
                    val oy = (by + underY).roundToInt()
                    if (ox in 0 until cols && oy in 0 until rows && oy <= maxY &&
                        !isInsideLogoExclusion(ox.toFloat(), oy.toFloat(), pad = 0.5f)
                    ) {
                        floraNodes.add(
                            FloraNode(
                                x = ox.toFloat(), y = oy.toFloat(), ch = bChar,
                                kind = NodeKind.BRANCH, side = side, progress = bProg,
                                groupId = gid, phase = 0f, flex = bFlex, variant = 1,
                            )
                        )
                    }
                }

                // Foliage only on the younger outer part, denser and fresher towards the tip
                val leafRoll = gen.nextFloat()
                if (u >= leafStartU) {
                    val outer = (u - leafStartU) / (1f - leafStartU + 0.001f)
                    val pLeaf = 0.25f + 0.55f * outer
                    val young = u > 0.82f
                    if (by > 0 && leafRoll < pLeaf &&
                        !isInsideLogoExclusion(bx.toFloat(), (by - 1).toFloat(), pad = 0.5f)
                    ) {
                        floraNodes.add(
                            FloraNode(
                                x = bx.toFloat(), y = (by - 1).toFloat(),
                                ch = if (dxU >= 0f) '/' else '\\',
                                kind = NodeKind.LEAF, side = side, progress = bProg,
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = bFlex * 1.15f, variant = if (young) 2 else gen.nextInt(2),
                                depth = tipDepth,
                            )
                        )
                    }
                    if (s >= 2 && by < maxY && leafRoll < pLeaf * 0.55f &&
                        !isInsideLogoExclusion(bx.toFloat(), (by + 1).toFloat(), pad = 0.5f)
                    ) {
                        floraNodes.add(
                            FloraNode(
                                x = bx.toFloat(), y = (by + 1).toFloat(),
                                ch = '~',
                                kind = NodeKind.LEAF, side = side, progress = bProg,
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = bFlex * 1.10f, variant = if (young) 2 else gen.nextInt(2),
                                depth = tipDepth,
                            )
                        )
                    }
                }

                // Hanging side twigs: drop forward/down from the outer part of a mature branch,
                // each ending in its own little leaf cluster with a bud (new growth at the tips).
                val hangStride = if (branchG > 0.65f) 3 else 4
                if (branchG > 0.35f && u > thickU && s >= 3 && s % hangStride == 0 && s < steps - 1) {
                    val hangLen = ((2f + (branchG - 0.30f) * 7f) * (0.7f + 0.3f * gen.nextFloat()))
                        .roundToInt().coerceIn(2, 7)
                    val twigGid = allocGroup(gen)
                    val drift = dxU * 0.45f
                    var hx = bx.toFloat()
                    var hy = by.toFloat()
                    var lastHx = bx
                    var lastHy = by
                    var placed = 0
                    for (k in 1..hangLen) {
                        hx += drift * (1f - k.toFloat() / hangLen)
                        hy += 1f
                        val ihx = hx.roundToInt()
                        val ihy = hy.roundToInt()
                        if (ihx !in 1 until cols - 1 || ihy !in 0 until rows || ihy > maxY) break
                        if (isInsideLogoExclusion(ihx.toFloat(), ihy.toFloat(), pad = 0.5f)) break
                        if (!allowInCenter && ihy in (rows * 0.24f).toInt()..(rows * 0.72f).toInt() &&
                            ihx > cols * 0.22f && ihx < cols * 0.78f
                        ) break
                        val kf = k.toFloat() / hangLen
                        floraNodes.add(
                            FloraNode(
                                x = ihx.toFloat(), y = ihy.toFloat(),
                                ch = if (ihx != lastHx) dirChar(ihx - lastHx, ihy - lastHy) else if (k % 2 == 0) ':' else '|',
                                kind = NodeKind.BRANCH, side = side, progress = bProg + k * progPerCell,
                                groupId = twigGid, phase = gen.nextFloat() * 6.28f,
                                flex = bFlex + 0.22f * k, depth = 0.35f + 0.65f * kf,
                            )
                        )
                        lastHx = ihx
                        lastHy = ihy
                        placed = k
                    }
                    if (placed >= 2) {
                        // Leaf cluster around the hanging tip:  / * \
                        val tipProg = bProg + (placed + 1) * progPerCell
                        val tipFlex = bFlex + 0.22f * placed
                        for (dx in intArrayOf(-1, 1)) {
                            val lx = lastHx + dx
                            if (lx !in 0 until cols || isInsideLogoExclusion(lx.toFloat(), lastHy.toFloat(), pad = 0.5f)) continue
                            floraNodes.add(
                                FloraNode(
                                    x = lx.toFloat(), y = lastHy.toFloat(),
                                    ch = if (dx < 0) '/' else '\\',
                                    kind = NodeKind.LEAF, side = side, progress = tipProg,
                                    groupId = twigGid, phase = gen.nextFloat() * 6.28f,
                                    flex = tipFlex, variant = 2, depth = 1f,
                                )
                            )
                        }
                        if (lastHy + 1 <= maxY && !isInsideLogoExclusion(lastHx.toFloat(), (lastHy + 1).toFloat(), pad = 0.5f)) {
                            floraNodes.add(
                                FloraNode(
                                    x = lastHx.toFloat(), y = (lastHy + 1).toFloat(),
                                    ch = if (branchG > 0.6f) '*' else '.',
                                    kind = NodeKind.BUD, side = side, progress = tipProg,
                                    groupId = twigGid, phase = gen.nextFloat() * 6.28f,
                                    flex = tipFlex + 0.15f, depth = 1f,
                                )
                            )
                        }
                    }
                }

                prevX = bx
                prevY = by
                lastValidX = bx.toFloat()
                lastValidY = by.toFloat()
            }
        }

        // Tip bud + lush foliage cap at the end of the branch
        if (steps >= 2) {
            val tipX = (lastValidX + dxU).roundToInt().coerceIn(0, cols - 1)
            val tipY = (lastValidY + dyU).roundToInt().coerceIn(0, rows - 1)
            if (tipY <= maxY && !isInsideLogoExclusion(tipX.toFloat(), tipY.toFloat(), pad = 0.5f)) {
                if (branchG > 0.50f) {
                    val capX = (tipX + perpX.roundToInt()).coerceIn(0, cols - 1)
                    val capY = (tipY + perpY.roundToInt()).coerceIn(0, rows - 1)
                    if (!isInsideLogoExclusion(capX.toFloat(), capY.toFloat(), pad = 0.5f)) {
                        floraNodes.add(
                            FloraNode(
                                x = capX.toFloat(), y = capY.toFloat(),
                                ch = if (dxU >= 0f) '/' else '\\',
                                kind = NodeKind.LEAF, side = side,
                                progress = progBase + (steps + 1) * progPerCell,
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = 1.30f, variant = 2, depth = 0.45f * branchG,
                            )
                        )
                    }
                }
                floraNodes.add(
                    FloraNode(
                        x = tipX.toFloat(), y = tipY.toFloat(),
                        ch = if (branchG > 0.45f) '¤' else '*',
                        kind = NodeKind.BUD, side = side,
                        progress = progBase + (steps + 1) * progPerCell,
                        groupId = gid, phase = gen.nextFloat() * 6.28f,
                        flex = 1.28f, depth = 0.45f * branchG,
                    )
                )
                return Pair(tipX.toFloat(), tipY.toFloat())
            }
        }

        return Pair(lastValidX, lastValidY)
    }

    /**
     * Distributes up to [count] warm & spectral blooming lights:
     * - Morning (g ~ 0): placed directly along the two slender vines (at the sprout nodes).
     * - Day -> Evening (g -> 1): half stay near the 3D trunks for warm bark rim-light, while the rest
     *   organically travel outward along the sprouted branches into the 4 rectangular corners & 2nd layer.
     */
    private fun generateAmbientLights(
        count: Int,
        g: Float,
        anchors: List<SproutAnchor>,
        gen: Random,
    ) {
        if (count <= 0 || anchors.isEmpty()) return
        val clamped = count.coerceIn(0, WallpaperPrefs.MAX_LIGHTS)

        for (i in 0 until clamped) {
            val anchor = anchors[(i * 7 + (i / anchors.size) * 3) % anchors.size]
            val inwardDir = -anchor.side

            val travelFactor = if (i % 2 == 0) {
                g * 0.22f
            } else {
                g * 0.88f
            }

            var lx = anchor.x + (anchor.tipX - anchor.x) * travelFactor + inwardDir * (if (i % 2 == 0) 1.5f else 0f)
            var ly = anchor.y + (anchor.tipY - anchor.y) * travelFactor
            if (isInsideLogoExclusion(lx, ly, pad = 1.5f)) {
                lx = anchor.x
                ly = anchor.y
            }

            val radius = (1.9f + gen.nextFloat() * 1.5f) * (0.78f + g * 0.52f)
            val depthLayer = if (g > 0.35f && i % 4 == 3) 1 else 2

            ambientLights.add(
                AmbientLight(
                    baseX = lx.coerceIn(1f, cols - 2f),
                    baseY = ly.coerceIn(1f, substrateTopY - 1.5f), // never inside the PCB substrate band
                    phase = gen.nextFloat() * (2f * PI.toFloat()),
                    radiusCells = radius,
                    warmWeight = if (i % 3 == 0) 0.25f else 0.85f,
                    depthLayer = depthLayer,
                )
            )
        }
    }

    private fun updateAmbientLights() {
        for (i in 0 until ambientLights.size) {
            val l = ambientLights[i]
            val parallaxX = if (l.depthLayer == 1) {
                smoothSwipeNorm * 1.4f + smoothGyroX * 1.25f
            } else {
                smoothSwipeNorm * 0.9f + smoothGyroX * 0.75f
            }
            val parallaxY = if (l.depthLayer == 1) smoothGyroY * 0.55f else smoothGyroY * 0.30f

            if (config.lightsEnabled) {
                val driftX = sin(animElapsed * LIGHT_DRIFT_RATE_X + l.phase).toFloat() * 1.15f
                val driftY = cos(animElapsed * LIGHT_DRIFT_RATE_Y + l.phase * 1.3f).toFloat() * 0.85f
                l.curX = l.baseX + parallaxX + driftX
                l.curY = l.baseY + parallaxY + driftY
                l.intensity = 0.52f + 0.48f * (sin(animElapsed * LIGHT_BREATH_RATE + l.phase).toFloat() * 0.5f + 0.5f)
            } else {
                l.curX = l.baseX + parallaxX
                l.curY = l.baseY + parallaxY
                l.intensity = 0.62f
            }
        }
    }

    private fun allocGroup(gen: Random): Int {
        val id = groupCount
        if (id < groupPhases.size) {
            groupPhases[id] = gen.nextFloat() * (2f * PI.toFloat())
            groupCount++
            return id
        }
        return id % groupPhases.size
    }

    // -------------------------------------------------------------------------
    // Per-Frame Drawing Passes
    // -------------------------------------------------------------------------

    private fun drawFloraPass(passLayer: Int) {
        val vitality = 0.65f + batteryLevel * 0.35f
        val windActive = config.windEnabled
        val hasLights = ambientLights.isNotEmpty()

        for (i in 0 until floraNodes.size) {
            val n = floraNodes[i]
            val nodeLayer = when (n.kind) {
                NodeKind.FAR_VINE, NodeKind.FAR_LEAF -> 0
                NodeKind.MID_STALK, NodeKind.MID_LEAF -> 1
                else -> 2
            }
            if (nodeLayer != passLayer) continue

            // Growth reveal: freshly grown cells wait for their turn, then sprout in a short fade
            var sprout = 1f
            if (!n.bornAt.isNaN()) {
                val age = (wallElapsed - n.bornAt).toFloat()
                if (age < 0f) continue
                if (age >= GROW_FADE_S) n.bornAt = Double.NaN else sprout = age / GROW_FADE_S
            }

            // Clear atmospheric multi-layer parallax (Far > Mid > Foreground > PCB Roots)
            val parallaxX = when {
                passLayer == 0 -> smoothSwipeNorm * 2.5f + smoothGyroX * 2.4f
                passLayer == 1 -> smoothSwipeNorm * 1.45f + smoothGyroX * 1.35f
                n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD -> smoothSwipeNorm * 0.40f + smoothGyroX * 0.25f
                // Foreground: parts hanging forward (depth > 0) sit closer to the viewer and shift more
                else -> (smoothSwipeNorm * 0.90f + smoothGyroX * 0.80f) * (1f + 0.8f * n.depth)
            }
            val parallaxY = when {
                passLayer == 0 -> smoothGyroY * 0.95f
                passLayer == 1 -> smoothGyroY * 0.55f
                n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD -> 0f
                else -> smoothGyroY * 0.30f * (1f + 0.8f * n.depth)
            }

            var cx = n.x + parallaxX
            var cy = n.y + parallaxY

            // Wind & organic breathing motion (respects Wind toggle!)
            // Note: Roots and PCB traces are anchored in the substrate and are unaffected by wind!
            val isRootOrPcb = n.kind == NodeKind.ROOT_ORGANIC || n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD
            // One shared, spatially coherent wind field: trunk cells move as one body, a branch base
            // follows the trunk (low flex) and the tip swings further (high flex). Branch groups add a
            // small overtone of their own on top, so neighbouring branches are related but not clones.
            var localWind = 0f
            if (windActive && !isRootOrPcb && n.flex > 0.08f) {
                localWind = windAt(n.x, n.y)
                var bend = localWind
                if (n.groupId > 0) {
                    val overtone = sin(branchPhaseNow + groupPhases[n.groupId]).toFloat()
                    bend += overtone * 0.22f * gustNow * ((n.flex - 0.16f) / 1.1f).coerceIn(0f, 1f)
                }
                cx += bend * n.flex * 0.85f
                // A bending cantilever tip drops slightly (arc shortening) instead of floating upward
                if (n.flex > 0.6f && n.kind != NodeKind.CANOPY_HANG) {
                    cy += abs(bend) * (n.flex - 0.6f) * 0.35f
                }
            }

            // Touch wind ripples (roots and PCB traces are solid/static in substrate)
            var touchBoost = 0f
            if (!isRootOrPcb) {
                for (r in ripples) {
                    if (!r.active) continue
                    val dx = cx - r.x
                    val dy = cy - r.y
                    val dist = hypot(dx, dy)
                    val band = abs(dist - r.radius)
                    if (band < 3.6f && dist > 0.1f) {
                        val influence = (1f - band / 3.6f) * r.strength
                        cx += (dx / dist) * influence * (0.5f + n.flex * 1.45f)
                        cy += (dy / dist) * influence * (0.35f + n.flex * 0.95f)
                        touchBoost = max(touchBoost, influence)
                    }
                }
            }

            // Hard exclusion border around KETTEN logo + readout (even under wind/gyro/swipe!)
            val logoShiftX = -smoothSwipeNorm * 0.50f - smoothGyroX * 0.45f
            val logoShiftY = -smoothGyroY * 0.22f
            if (isInsideLogoExclusion(cx, cy, parallaxX = logoShiftX, parallaxY = logoShiftY)) {
                continue
            }

            // Energy / charge pulses
            var pulseHit = 0f
            var chargeHit = false
            for (p in pulses) {
                if (!p.active) continue
                if (p.side != 0 && n.side != 0 && p.side != n.side) continue
                val h = pulseProfile(p, n.progress)
                if (h > pulseHit) {
                    pulseHit = h
                    chargeHit = p.fromCharge
                }
            }

            // Local warm rim-lighting from nearby pulsing bloom lights (only computed for foreground bark/leaves/roots)
            var lightIllum = 0f
            if (hasLights && passLayer == 2 && (n.kind == NodeKind.TRUNK_3D || n.kind == NodeKind.LEAF || n.kind == NodeKind.BRANCH || n.kind == NodeKind.ROOT_ORGANIC)) {
                for (li in 0 until ambientLights.size) {
                    val l = ambientLights[li]
                    val dx = cx - l.curX
                    val dy = cy - l.curY
                    val distSq = dx * dx + dy * dy
                    val maxD = l.radiusCells * 2.1f
                    if (distSq < maxD * maxD) {
                        val falloff = 1f - (hypot(dx, dy) / maxD)
                        val contrib = falloff * falloff * l.intensity
                        if (contrib > lightIllum) lightIllum = contrib
                    }
                }
            }

            var ch = n.ch
            var col: Int
            var glow = 0

            when (n.kind) {
                // Depth hierarchy: the far plane is pushed further back so the foreground limbs,
                // their bark and their new growth stay the readable structure on screen.
                NodeKind.FAR_VINE -> {
                    // Far plane is drawn as atmospheric stipple only; solid line glyphs are reserved
                    // for midground and foreground wood so limbs stand out as structure.
                    ch = when (ch) {
                        '|', '│', '┃', '(', ')' -> ':'
                        else -> '.'
                    }
                    col = SpectralRecipe.lerpColor(colors.bg, colors.farVine, 0.62f)
                }
                NodeKind.FAR_LEAF -> {
                    ch = if (n.variant == 1) '·' else '.'
                    col = if (n.variant == 1) {
                        SpectralRecipe.lerpColor(colors.bg, SpectralRecipe.lerpColor(colors.farVine, colors.midStalk, 0.35f), 0.62f)
                    } else {
                        SpectralRecipe.lerpColor(colors.bg, colors.farVine, 0.62f)
                    }
                }
                NodeKind.MID_STALK -> {
                    col = if (pulseHit > 0.2f) {
                        SpectralRecipe.lerpColor(colors.midStalk, colors.branch, pulseHit * 0.6f)
                    } else {
                        colors.midStalk
                    }
                }
                NodeKind.MID_LEAF -> {
                    // Kept clearly darker than foreground foliage so the foreground branches,
                    // their old wood and their new growth stay readable against the midground.
                    col = if (n.variant == 1) {
                        SpectralRecipe.lerpColor(colors.midStalk, colors.leafDark, 0.45f)
                    } else {
                        SpectralRecipe.lerpColor(colors.midStalk, colors.leafDark, 0.25f)
                    }
                }
                NodeKind.ROOT_ORGANIC -> {
                    val baseCol = if (n.shade > 0f) {
                        SpectralRecipe.lerpColor(colors.rootMid, colors.trunkBark, n.shade * 0.6f)
                    } else {
                        SpectralRecipe.lerpColor(colors.rootMid, colors.rootDim, -n.shade * 0.7f)
                    }
                    col = when {
                        pulseHit > 0.15f -> {
                            if (pulseHit > 0.6f) glow = 1
                            SpectralRecipe.lerpColor(baseCol, colors.pulseCore, pulseHit)
                        }
                        lightIllum > 0.15f -> {
                            SpectralRecipe.lerpColor(baseCol, colors.trunkRimWarm, lightIllum * 0.55f)
                        }
                        else -> baseCol
                    }
                }
                NodeKind.ROOT_PCB -> {
                    col = if (pulseHit > 0.15f) {
                        if (pulseHit > 0.55f) glow = 1
                        SpectralRecipe.lerpColor(colors.rootDim, colors.pulseTrail, pulseHit)
                    } else {
                        colors.rootDim
                    }
                }
                NodeKind.ROOT_PAD -> {
                    val padPulse = (sin(animElapsed * 2.1 + n.phase).toFloat() * 0.5f + 0.5f) * vitality
                    if (pulseHit > 0.35f || (isCharging && padPulse > 0.72f)) {
                        ch = '¤'
                        col = colors.pulseCore
                        glow = 2
                    } else if (padPulse > 0.45f) {
                        ch = 'o'
                        col = colors.rootNode
                        glow = 1
                    } else {
                        ch = '°'
                        col = colors.rootMid
                    }
                }
                NodeKind.TRUNK_3D -> {
                    // Cylindrical shading across trunk width:
                    // shade = -1 (dark outer shadow) -> 0 (core) -> +1 (warm light-facing rim)
                    val s = n.shade
                    var baseCol = when {
                        s < 0f -> SpectralRecipe.lerpColor(colors.trunkCore, colors.trunkShadow, -s)
                        n.variant == 1 -> SpectralRecipe.lerpColor(colors.trunkCore, colors.trunkBark, 0.55f + s * 0.45f)
                        else -> SpectralRecipe.lerpColor(colors.trunkCore, colors.trunkBark, s * 0.85f)
                    }
                    // Warm rim-light on the inner edge of the cylinder (enhanced when lights are near!)
                    val rimFactor = ((s - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    val warmRim = (rimFactor * 0.42f + lightIllum * (0.45f + rimFactor * 0.55f)).coerceIn(0f, 1f)
                    if (warmRim > 0.08f) {
                        baseCol = SpectralRecipe.lerpColor(baseCol, colors.trunkRimWarm, warmRim)
                        if (warmRim > 0.72f && rimFactor > 0.5f) glow = 1
                    }
                    col = if (pulseHit > 0.12f) {
                        if (pulseHit > 0.55f) glow = 1
                        SpectralRecipe.lerpColor(
                            baseCol,
                            if (chargeHit) colors.pulseCore else colors.pulseTrail,
                            pulseHit * 0.85f,
                        )
                    } else {
                        baseCol
                    }
                }
                NodeKind.TENDRIL -> {
                    col = if (pulseHit > 0.25f) {
                        SpectralRecipe.lerpColor(colors.branch, colors.pulseTrail, pulseHit * 0.8f)
                    } else {
                        colors.branch
                    }
                }
                NodeKind.BRANCH -> {
                    // Old thickened wood takes the bark colour; forward-hanging twigs catch more light
                    val wood = when {
                        // Same warm bark as the trunk, so a thickening limb reads as wood growing out of it
                        n.variant == 1 -> SpectralRecipe.lerpColor(colors.trunkBark, colors.trunkRimWarm, 0.38f)
                        n.depth > 0.05f -> SpectralRecipe.lerpColor(colors.branch, colors.trunkRimWarm, n.depth * 0.22f)
                        else -> colors.branch
                    }
                    val boost = max(max(pulseHit, touchBoost), lightIllum * 0.7f)
                    col = if (boost > 0.18f) {
                        SpectralRecipe.lerpColor(wood, colors.trunkRimWarm, boost * 0.8f)
                    } else {
                        wood
                    }
                }
                NodeKind.LEAF -> {
                    if (windActive) {
                        // Leaf glyph follows the local wind: it leans with the gust and only flutters
                        // ('~') on strong gust peaks, so the wind wave is readable across the canopy.
                        val flutter = sin(flutterPhaseNow + n.phase).toFloat() * 0.35f * gustNow
                        val lean = localWind + flutter
                        ch = when {
                            lean > 0.42f -> '/'
                            lean < -0.42f -> '\\'
                            gustNow > 0.88f && flutter > 0.30f -> '~'
                            else -> n.ch
                        }
                    }
                    var baseLeaf = when (n.variant) {
                        0 -> colors.leafDark
                        2 -> colors.leafBright
                        else -> colors.leaf
                    }
                    if (n.depth > 0.05f) baseLeaf = SpectralRecipe.lerpColor(baseLeaf, colors.leafBright, n.depth * 0.2f)
                    val boost = max(pulseHit, touchBoost)
                    col = when {
                        boost > 0.15f -> {
                            if (boost > 0.65f) glow = 1
                            SpectralRecipe.lerpColor(baseLeaf, colors.leafBright, boost)
                        }
                        lightIllum > 0.15f -> {
                            SpectralRecipe.lerpColor(baseLeaf, colors.trunkRimWarm, lightIllum * 0.65f)
                        }
                        else -> baseLeaf
                    }
                }
                NodeKind.CANOPY_HANG -> {
                    col = if (pulseHit > 0.2f) {
                        SpectralRecipe.lerpColor(colors.branch, colors.pulseTrail, pulseHit)
                    } else {
                        colors.branch
                    }
                }
                NodeKind.BUD -> {
                    val bloom = if (config.lightsEnabled) {
                        (sin(animElapsed * BUD_RATE + n.phase).toFloat() * 0.5f + 0.5f) * vitality + pulseHit * 0.9f + touchBoost * 0.7f
                    } else {
                        0.60f * vitality + pulseHit * 0.9f + touchBoost * 0.7f
                    }
                    when {
                        bloom > 0.82f -> {
                            ch = '¤'
                            col = if (pulseHit > 0.5f) colors.pulseCore else colors.lightHigh
                            glow = 2
                        }
                        bloom > 0.45f -> {
                            ch = '*'
                            col = colors.lightMid
                            glow = 1
                        }
                        bloom > 0.22f -> {
                            ch = 'o'
                            col = colors.lightDim
                        }
                        else -> {
                            ch = '.'
                            col = colors.lightDim
                        }
                    }
                }
            }

            // Sprouting cells grow in as '.' -> ':' -> final glyph while their colour fades in,
            // with a faint bright tip at the very start of the growth front.
            if (sprout < 1f) {
                ch = when {
                    sprout < 0.33f -> '.'
                    sprout < 0.66f -> ':'
                    else -> ch
                }
                col = if (sprout < 0.33f) {
                    SpectralRecipe.lerpColor(col, colors.leafBright, 0.55f)
                } else {
                    SpectralRecipe.lerpColor(colors.bg, col, 0.35f + 0.65f * sprout)
                }
                glow = 0
            }

            // Hysteresis: keep the previous cell until the node has clearly moved past the cell
            // boundary. Plain rounding makes sub-cell motion near x.5 flicker between two columns.
            val gx = snapCell(cx, n.drawX)
            val gy = snapCell(cy, n.drawY)
            n.drawX = gx
            n.drawY = gy
            putCell(gx, gy, ch, col, glow)
        }
    }

    private fun drawTraceSpark(
        canvas: Canvas,
        tr: PcbTrace,
        fraction: Float,
        stroke: Float,
        pxShift: Float,
        p: EnergyPulse,
    ) {
        val count = tr.ptsX.size
        var total = 0f
        for (i in 1 until count) {
            total += hypot((tr.ptsX[i] - tr.ptsX[i - 1]) * cellW, (tr.ptsY[i] - tr.ptsY[i - 1]) * cellH)
        }
        if (total <= 0f) return
        var remaining = fraction.coerceIn(0f, 1f) * total
        var sx = tr.ptsX[count - 1]
        var sy = tr.ptsY[count - 1]
        for (i in 1 until count) {
            val seg = hypot((tr.ptsX[i] - tr.ptsX[i - 1]) * cellW, (tr.ptsY[i] - tr.ptsY[i - 1]) * cellH)
            if (remaining <= seg && seg > 0f) {
                val k = remaining / seg
                sx = tr.ptsX[i - 1] + (tr.ptsX[i] - tr.ptsX[i - 1]) * k
                sy = tr.ptsY[i - 1] + (tr.ptsY[i] - tr.ptsY[i - 1]) * k
                break
            }
            remaining -= seg
        }
        val px = sx * cellW + cellW * 0.5f + pxShift
        val py = offsetY + sy * cellH + cellH * 0.5f
        val tint = if (p.fromCharge) colors.pulseCore else colors.pulseTrail
        val a = p.intensity.coerceIn(0f, 1.2f)
        pcbFillPaint.color = SpectralRecipe.withAlpha(tint, (70f * a).roundToInt().coerceIn(0, 110))
        canvas.drawCircle(px, py, stroke * 2.6f + cellW * 0.18f, pcbFillPaint)
        pcbFillPaint.color = SpectralRecipe.withAlpha(colors.pulseCore, (220f * a).roundToInt().coerceIn(0, 255))
        canvas.drawCircle(px, py, stroke * 0.85f + 1.2f, pcbFillPaint)
    }

    private fun isVegetation(kind: NodeKind): Boolean = when (kind) {
        NodeKind.ROOT_ORGANIC, NodeKind.ROOT_PCB, NodeKind.ROOT_PAD, NodeKind.TRUNK_3D -> false
        else -> true
    }

    private fun snapCell(v: Float, prev: Int): Int {
        if (prev != Int.MIN_VALUE && abs(v - prev) < 0.68f) return prev
        return v.roundToInt()
    }

    /**
     * Shared wind field in the range ~[-1, 1] at grid position (x, y).
     * A primary wave travels left -> right across the screen (gusts visibly roll through the
     * canopy), a weaker secondary wave breaks the regularity, and a slow gust envelope varies
     * the overall strength. Cells close together get almost the same value, so they move together.
     */
    private fun windAt(x: Float, y: Float): Float {
        val primary = sin(windPhaseNow - WIND_K_X * x + WIND_K_Y * y)
        val secondary = sin(windPhaseNow * 1.37 + 1.9 - 0.07 * x - 0.03 * y)
        return ((primary * 0.78 + secondary * 0.22) * gustNow).toFloat()
    }

    /**
     * Brightness of an energy pulse at a given network progress: a sharp leading edge
     * ([PULSE_HEAD]) and a longer fading tail ([PULSE_TAIL]) so it reads as something that
     * travels upward, plus a gradual fade towards the canopy so pulses die out softly.
     */
    private fun pulseProfile(p: EnergyPulse, nodeProgress: Float): Float {
        val d = p.progress - nodeProgress // > 0: node is behind the head (tail side)
        val shape = when {
            d < -PULSE_HEAD -> return 0f
            d < 0f -> 1f + d / PULSE_HEAD
            d < PULSE_TAIL -> {
                val k = 1f - d / PULSE_TAIL
                k * k
            }
            else -> return 0f
        }
        val reach = (1f - 0.45f * (nodeProgress / maxNodeProgress)).coerceIn(0.35f, 1f)
        return shape * reach * p.intensity
    }

    /**
     * Renders the bottom-quarter vector PCB substrate directly onto the Android Canvas:
     * - Silkscreen component outlines & Pin-1 markers
     * - Pixel-exact 45-degree routed copper traces & growing multi-lane power buses
     * - ENIG copper/gold SMD pads
     * - Plated through-hole vias (copper annular ring + crisp #000000 drill center)
     * - Real-time energy/charging signal pulses illuminating traces & vias
     * Completely solid & anchored in substrate (zero wind sway or touch ripple distortion).
     */
    private fun drawPcbSubstrate(canvas: Canvas) {
        if (pcbTraces.isEmpty() && pcbVias.isEmpty()) return

        val pxShift = (smoothSwipeNorm * 0.36f + smoothGyroX * 0.22f) * cellW
        val vitality = 0.68f + batteryLevel * 0.32f

        val traceDimCol = SpectralRecipe.lerpColor(colors.bg, colors.rootDim, 0.92f)
        val traceSignalCol = colors.rootMid
        val tracePowerCol = SpectralRecipe.lerpColor(colors.rootMid, colors.rootNode, 0.58f)
        val padCopperCol = SpectralRecipe.lerpColor(colors.rootNode, colors.trunkRimWarm, 0.34f)
        val silkCol = SpectralRecipe.withAlpha(colors.rootMid, 92)

        // 1. Subtle silkscreen package outlines + Pin-1 indicator dots
        pcbSilkPaint.color = silkCol
        pcbSilkPaint.strokeWidth = (cellW * 0.055f).coerceAtLeast(1.1f)
        pcbFillPaint.color = SpectralRecipe.withAlpha(colors.rootNode, 140)
        for (i in 0 until pcbSilks.size) {
            val s = pcbSilks[i]
            val l = s.left * cellW + cellW * 0.5f + pxShift
            val t = offsetY + s.top * cellH + cellH * 0.5f
            val r = s.right * cellW + cellW * 0.5f + pxShift
            val b = offsetY + s.bottom * cellH + cellH * 0.5f
            canvas.drawRect(l, t, r, b, pcbSilkPaint)
            val p1x = s.pin1X * cellW + cellW * 0.5f + pxShift
            val p1y = offsetY + s.pin1Y * cellH + cellH * 0.5f
            canvas.drawCircle(p1x, p1y, (cellW * 0.11f).coerceAtLeast(1.6f), pcbFillPaint)
        }

        // 2. Routed copper PCB traces (drawn tier 0 -> 1 -> 2 so main power rails sit cleanly on top)
        for (tierPass in 0..2) {
            for (i in 0 until pcbTraces.size) {
                val tr = pcbTraces[i]
                if (tr.tier != tierPass) continue
                val count = tr.ptsX.size
                if (count < 2) continue

                // Check if an energy/charging pulse is sweeping through this trace
                var pulseHit = 0f
                var chargeHit = false
                for (p in pulses) {
                    if (!p.active) continue
                    if (p.side != 0 && tr.side != 0 && p.side != tr.side) continue
                    // While the head travels along this trace the copper only warms up (the bright
                    // spark below marks the head); after the head has left, the trace fades with the tail.
                    val h = when {
                        p.progress < tr.progressStart -> 0f
                        p.progress <= tr.progressEnd -> 0.42f * p.intensity
                        else -> pulseProfile(p, tr.progressEnd) * 0.8f
                    }
                    if (h > pulseHit) {
                        pulseHit = h
                        chargeHit = p.fromCharge
                    }
                }

                pcbPath.rewind()
                for (pt in 0 until count) {
                    val x = tr.ptsX[pt] * cellW + cellW * 0.5f + pxShift
                    val y = offsetY + tr.ptsY[pt] * cellH + cellH * 0.5f
                    if (pt == 0) pcbPath.moveTo(x, y) else pcbPath.lineTo(x, y)
                }

                val baseStroke = (tr.widthFactor * cellW).coerceAtLeast(1.5f)
                val baseColor = when (tierPass) {
                    0 -> traceDimCol
                    1 -> traceSignalCol
                    else -> tracePowerCol
                }

                // Soft copper glow halo when energized by a signal/charge pulse
                if (pulseHit > 0.18f) {
                    val pulseTint = if (chargeHit) colors.pulseCore else colors.pulseTrail
                    pcbTracePaint.color = SpectralRecipe.withAlpha(pulseTint, (pulseHit * 72f).roundToInt().coerceIn(0, 95))
                    pcbTracePaint.strokeWidth = baseStroke * 2.5f
                    canvas.drawPath(pcbPath, pcbTracePaint)
                }

                // Main copper trace body
                val traceColor = if (pulseHit > 0.10f) {
                    SpectralRecipe.lerpColor(baseColor, if (chargeHit) colors.pulseCore else colors.pulseTrail, pulseHit * 0.85f)
                } else {
                    baseColor
                }
                pcbTracePaint.color = traceColor
                pcbTracePaint.strokeWidth = baseStroke
                canvas.drawPath(pcbPath, pcbTracePaint)

                // Travelling spark: the head of each pulse is drawn as a bright dot moving along the
                // copper from the USB-C connector towards the vine base, at the same progress rate
                // that later carries the glow up the trunk.
                if (tierPass >= 1) {
                    for (p in pulses) {
                        if (!p.active) continue
                        if (p.side != 0 && tr.side != 0 && p.side != tr.side) continue
                        val span = tr.progressEnd - tr.progressStart
                        if (span <= 0.001f || p.progress < tr.progressStart || p.progress > tr.progressEnd) continue
                        drawTraceSpark(canvas, tr, (p.progress - tr.progressStart) / span, baseStroke, pxShift, p)
                    }
                }

                // Subtle bright center specular highlight on wide power rails
                if (tierPass == 2 && baseStroke >= 3.2f) {
                    pcbTracePaint.color = SpectralRecipe.withAlpha(
                        if (pulseHit > 0.2f) colors.pulseCore else padCopperCol,
                        if (pulseHit > 0.2f) 170 else 90,
                    )
                    pcbTracePaint.strokeWidth = baseStroke * 0.34f
                    canvas.drawPath(pcbPath, pcbTracePaint)
                }
            }
        }

        // 3. ENIG Gold/Copper SMD Pads
        val padCornerR = (cellW * 0.08f).coerceAtLeast(1.2f)
        for (i in 0 until pcbPads.size) {
            val pad = pcbPads[i]
            val px = pad.x * cellW + cellW * 0.5f + pxShift
            val py = offsetY + pad.y * cellH + cellH * 0.5f
            val hw = pad.wCells * cellW * 0.5f
            val hh = pad.hCells * cellH * 0.5f

            var pulseHit = 0f
            for (p in pulses) {
                if (!p.active) continue
                if (p.side != 0 && pad.side != 0 && p.side != pad.side) continue
                val h = pulseProfile(p, pad.progress)
                if (h > pulseHit) pulseHit = h
            }

            pcbFillPaint.color = if (pulseHit > 0.15f) {
                SpectralRecipe.lerpColor(padCopperCol, colors.pulseCore, pulseHit * 0.85f)
            } else {
                padCopperCol
            }
            pcbPadRect.set(px - hw, py - hh, px + hw, py + hh)
            canvas.drawRoundRect(pcbPadRect, padCornerR, padCornerR, pcbFillPaint)
        }

        // 4. Plated Through-Hole Vias (copper annular ring + dark #000000 drill hole)
        for (i in 0 until pcbVias.size) {
            val v = pcbVias[i]
            val vx = v.x * cellW + cellW * 0.5f + pxShift
            val vy = offsetY + v.y * cellH + cellH * 0.5f
            val outerR = (v.outerRadiusCells * cellW).coerceAtLeast(2.6f)
            val drillR = (v.drillRadiusCells * cellW).coerceAtLeast(1.2f)

            var pulseHit = 0f
            var chargeHit = false
            for (p in pulses) {
                if (!p.active) continue
                if (p.side != 0 && v.side != 0 && p.side != v.side) continue
                val h = pulseProfile(p, v.progress)
                if (h > pulseHit) {
                    pulseHit = h
                    chargeHit = p.fromCharge
                }
            }

            // Gentle alive copper glint on signal/power vias
            val glint = if (v.tier >= 1) {
                ((sin(animElapsed * 1.9 + v.phase).toFloat() * 0.5f + 0.5f) * vitality)
            } else {
                0f
            }

            val baseRingCol = when (v.tier) {
                0 -> colors.rootDim
                1 -> SpectralRecipe.lerpColor(colors.rootMid, colors.rootNode, 0.55f + glint * 0.35f)
                else -> SpectralRecipe.lerpColor(colors.rootNode, padCopperCol, 0.35f + glint * 0.45f)
            }
            val ringCol = if (pulseHit > 0.12f) {
                SpectralRecipe.lerpColor(baseRingCol, if (chargeHit) colors.pulseCore else colors.pulseTrail, pulseHit * 0.9f)
            } else {
                baseRingCol
            }

            // Soft glow behind active / charging power vias
            if (pulseHit > 0.25f || (isCharging && v.tier >= 2 && glint > 0.65f)) {
                val glowAmt = max(pulseHit, if (isCharging) glint * 0.65f else 0f)
                pcbFillPaint.color = SpectralRecipe.withAlpha(colors.pulseCore, (glowAmt * 78f).roundToInt().coerceIn(0, 96))
                canvas.drawCircle(vx, vy, outerR * 2.1f, pcbFillPaint)
            }

            // Copper annular ring
            pcbFillPaint.color = ringCol
            canvas.drawCircle(vx, vy, outerR, pcbFillPaint)

            // Crisp dark center drill hole (#000000 AMOLED pure black)
            pcbFillPaint.color = colors.bg
            canvas.drawCircle(vx, vy, drillR, pcbFillPaint)
        }
    }

    private fun drawAmbientLightBlooms(canvas: Canvas) {
        if (ambientLights.isEmpty()) return
        val sprite = bloomSprite ?: return

        for (i in 0 until ambientLights.size) {
            val l = ambientLights[i]
            val cx = l.curX * cellW + cellW * 0.5f
            val cy = offsetY + l.curY * cellH + cellH * 0.5f
            val haloColor = SpectralRecipe.lerpColor(colors.lightHigh, colors.warmOrbHalo, l.warmWeight)
            val radiusPx = l.radiusCells * cellW * (0.85f + l.intensity * 0.35f)

            bloomPaint.colorFilter = PorterDuffColorFilter(haloColor, PorterDuff.Mode.SRC_IN)
            bloomPaint.alpha = (52 + l.intensity * 82f).roundToInt().coerceIn(0, 155)
            bloomDstRect.set(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx)
            canvas.drawBitmap(sprite, null, bloomDstRect, bloomPaint)
        }
        bloomPaint.colorFilter = null
    }

    private fun drawAmbientLightCores(canvas: Canvas) {
        if (ambientLights.isEmpty()) return

        for (i in 0 until ambientLights.size) {
            val l = ambientLights[i]
            val cx = l.curX * cellW + cellW * 0.5f
            val baselineY = offsetY + l.curY * cellH + baselineOffset
            val coreColor = SpectralRecipe.lerpColor(colors.lightHigh, colors.warmOrbCore, l.warmWeight)
            val glyph = when {
                l.intensity > 0.78f -> '¤'
                l.intensity > 0.45f -> '*'
                else -> '°'
            }
            textPaint.color = coreColor
            singleCharBuf[0] = glyph
            canvas.drawText(singleCharBuf, 0, 1, cx, baselineY, textPaint)
        }
    }

    private fun drawUsbNexusCore() {
        val cx = (cols * 0.5f + smoothSwipeNorm * 0.4f).roundToInt()
        val cy = rows - 1
        // The nexus "beats" exactly when it launches an energy pulse, so the glow that climbs the
        // vines visibly starts at the USB-C root (plus a faint idle shimmer in between).
        val sinceLaunch = (animElapsed - lastPulseLaunch).toFloat().coerceAtLeast(0f)
        val flash = exp(-3.2f * sinceLaunch)
        val idle = (sin(animElapsed * if (isCharging) 2.6 else BUD_RATE).toFloat() * 0.5f + 0.5f) * 0.35f
        val beat = max(flash, idle)
        val coreColor = if (isCharging) {
            SpectralRecipe.lerpColor(colors.pulseTrail, colors.pulseCore, beat)
        } else {
            SpectralRecipe.lerpColor(colors.rootMid, colors.rootNode, beat)
        }
        val coreGlow = if (isCharging && beat > 0.45f) 2 else if (beat > 0.7f) 1 else 0
        val coreChar = if (isCharging && beat > 0.5f) '¤' else 'o'
        putCell(cx - 2, cy, '[', colors.rootDim, 0)
        putCell(cx - 1, cy, '=', colors.rootMid, 0)
        putCell(cx, cy, coreChar, coreColor, coreGlow)
        putCell(cx + 1, cy, '=', colors.rootMid, 0)
        putCell(cx + 2, cy, ']', colors.rootDim, 0)
    }

    private fun drawLogoReadout() {
        val anchorRow = (rows * 0.42f).roundToInt()
        val parallax = -smoothSwipeNorm * 0.45f - smoothGyroX * 0.35f

        val tagline = "── ORGANIC // HARDWARE ──"
        val tagY = anchorRow + logoH + 2
        val tagX = ((cols - tagline.length) * 0.5f + parallax).roundToInt()
        val tagCol = SpectralRecipe.lerpColor(colors.trunkCore, colors.branch, 0.85f)
        for (i in tagline.indices) {
            putCell(tagX + i, tagY, tagline[i], tagCol, 0)
        }

        // Single clean info line: Time · Date (or SIM) · Battery%
        val pct = (batteryLevel * 100f).roundToInt()
        val chgMark = if (isCharging) "+" else ""
        val dateOrSim = if (config.useSystemTime) cachedDateText else "sim"
        val infoLine = "$cachedTimeText · $dateOrSim · $pct%$chgMark"
        val infoY = tagY + 2
        val infoX = ((cols - infoLine.length) * 0.5f + parallax).roundToInt()
        val timeEnd = cachedTimeText.length
        val battStart = infoLine.lastIndexOf('·') + 2
        for (i in infoLine.indices) {
            val isTimePart = i < timeEnd
            val isBattPart = i >= battStart
            val col = when {
                isTimePart -> colors.logoHigh
                isBattPart && isCharging -> colors.pulseCore
                else -> colors.logoBase
            }
            val glow = if (isTimePart || (isBattPart && isCharging)) 1 else 0
            putCell(infoX + i, infoY, infoLine[i], col, glow)
        }
    }

    private fun drawLogo3DDirect(canvas: Canvas) {
        val burnShiftPxX = sin(wallElapsed * 0.011).toFloat() * 2.5f
        val burnShiftPxY = cos(wallElapsed * 0.008).toFloat() * 2.5f

        val anchorCol = (cols - logoW) * 0.5f - smoothSwipeNorm * 0.55f - smoothGyroX * 0.55f + logoTiltX * 0.22f
        val anchorRow = (rows * 0.42f).roundToInt().toFloat() - smoothGyroY * 0.22f

        val idleSwayX = sin(animElapsed * 0.48).toFloat() * 0.35f
        val swayCellsX = (smoothGyroX * 2.75f + smoothSwipeNorm * 1.6f + logoTiltX * 1.35f + idleSwayX)
            .coerceIn(-3.1f, 3.1f)
        val swayCellsY = (0.72f + smoothGyroY * 0.95f)
            .coerceIn(-0.45f, 1.55f)

        val extrudePxX = swayCellsX * cellW
        val extrudePxY = swayCellsY * cellH

        val sheenCycle = (animElapsed % 6.5).toFloat()
        val sheenPos = if (sheenCycle < 2.1f) (sheenCycle / 2.1f) * (logoW + 14f) - 7f else -100f

        val logoWaveAge = (animElapsed - logoRippleTime).toFloat()
        val logoWaveActive = logoWaveAge in 0f..1.4f
        val logoWaveRadius = logoWaveAge * 26f

        if (sliceCacheDirty) {
            rebuildSliceBitmaps()
        }
        val baseLeft = anchorCol * cellW + burnShiftPxX
        val baseTop = offsetY + anchorRow * cellH + burnShiftPxY
        val depthSlices = 5
        for (slice in depthSlices downTo 1) {
            val bmp = sliceBitmaps[slice - 1] ?: continue
            val frac = slice.toFloat() / depthSlices.toFloat()
            val sliceOx = extrudePxX * frac
            val sliceOy = extrudePxY * frac
            canvas.drawBitmap(bmp, baseLeft + sliceOx, baseTop + sliceOy, bitmapPaint)
        }

        val glowRadius = cellH * 0.68f
        for (ly in 0 until logoH) {
            val rowStr = logoMask[ly]
            val gy = anchorRow + ly
            val cy = offsetY + gy * cellH + cellH * 0.5f + burnShiftPxY
            val baselineY = offsetY + gy * cellH + baselineOffset + burnShiftPxY

            for (lx in 0 until logoW) {
                if (rowStr[lx] == ' ') continue

                val gx = anchorCol + lx
                val cx = gx * cellW + cellW * 0.5f + burnShiftPxX

                val topEmpty = (ly == 0) || (logoMask[ly - 1][lx] == ' ')
                val leftEmpty = (lx == 0) || (rowStr[lx - 1] == ' ')

                val diagCoord = lx + ly * 1.6f
                val sheenDist = abs(diagCoord - sheenPos)
                val sheenFactor = (1f - sheenDist / 3.8f).coerceIn(0f, 1f)

                var waveFactor = 0f
                if (logoWaveActive) {
                    val d = hypot(gx - logoRippleCx, gy - logoRippleCy)
                    val band = abs(d - logoWaveRadius)
                    if (band < 4.0f) {
                        waveFactor = (1f - band / 4.0f) * (1f - logoWaveAge / 1.4f)
                    }
                }

                val vGrad = 1f - (ly.toFloat() / (logoH - 1).toFloat())
                var faceColor = SpectralRecipe.lerpColor(colors.logoBase, colors.logoHigh, vGrad * 0.78f)
                var ch = when {
                    topEmpty -> '▓'
                    leftEmpty -> '#'
                    ly % 2 == 0 -> '#'
                    else -> '%'
                }
                var glow = if (topEmpty && ly == 0) 1 else 0

                if (sheenFactor > 0.15f) {
                    faceColor = SpectralRecipe.lerpColor(faceColor, colors.logoSheen, sheenFactor)
                    if (sheenFactor > 0.62f) {
                        ch = '█'
                        glow = 2
                    } else if (sheenFactor > 0.32f) {
                        ch = '@'
                        glow = 1
                    }
                }

                if (waveFactor > 0.15f) {
                    faceColor = SpectralRecipe.lerpColor(faceColor, colors.lightHigh, waveFactor)
                    if (waveFactor > 0.48f) {
                        ch = '¤'
                        glow = 2
                    } else {
                        glow = max(glow, 1)
                    }
                }

                if (glow > 0) {
                    val alpha = if (glow >= 2) 66 else 30
                    glowPaint.color = SpectralRecipe.withAlpha(faceColor, alpha)
                    canvas.drawCircle(cx, cy, if (glow >= 2) glowRadius * 1.12f else glowRadius * 0.8f, glowPaint)
                }

                textPaint.color = faceColor
                singleCharBuf[0] = ch
                canvas.drawText(singleCharBuf, 0, 1, cx, baselineY, textPaint)
            }
        }
    }

    private fun rebuildSliceBitmaps() {
        sliceCacheDirty = false
        val bmpW = (logoW * cellW).roundToInt().coerceAtLeast(1)
        val bmpH = (logoH * cellH).roundToInt().coerceAtLeast(1)

        for (slice in 1..5) {
            val idx = slice - 1
            var bmp = sliceBitmaps[idx]
            if (bmp == null || bmp.width != bmpW || bmp.height != bmpH) {
                bmp?.recycle()
                bmp = createBitmap(bmpW, bmpH)
                sliceBitmaps[idx] = bmp
            } else {
                bmp.eraseColor(0)
            }

            val sliceCanvas = Canvas(bmp)
            val sliceColor = when {
                slice >= 4 -> SpectralRecipe.lerpColor(colors.logoSide2, colors.logoShadow, (slice - 3) * 0.5f)
                slice >= 2 -> SpectralRecipe.lerpColor(colors.logoSide1, colors.logoSide2, (slice - 1) * 0.5f)
                else -> colors.logoSide1
            }
            val sliceChar = when {
                slice >= 4 -> '░'
                slice >= 2 -> '▒'
                else -> '▓'
            }
            textPaint.color = sliceColor
            singleCharBuf[0] = sliceChar

            for (ly in 0 until logoH) {
                val rowStr = logoMask[ly]
                val baseY = ly * cellH + baselineOffset
                for (lx in 0 until logoW) {
                    if (rowStr[lx] != ' ') {
                        val cx = lx * cellW + cellW * 0.5f
                        sliceCanvas.drawText(singleCharBuf, 0, 1, cx, baseY, textPaint)
                    }
                }
            }
        }
    }

    private fun ensureBloomSprite() {
        if (bloomSprite != null) return
        val size = 96
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val r = size * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                r, r, r,
                intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(145, 255, 255, 255),
                    Color.argb(42, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.28f, 0.64f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(r, r, r, p)
        bloomSprite = bmp
    }

    private fun drawSpores() {
        for (s in spores) {
            if (!s.active) continue
            val lifeFrac = (s.life / s.maxLife).coerceIn(0f, 1f)
            val fade = when {
                lifeFrac < 0.15f -> lifeFrac / 0.15f
                lifeFrac > 0.75f -> (1f - lifeFrac) / 0.25f
                else -> 1f
            }
            val baseCol = if (s.bright) colors.sporeBright else colors.sporeDim
            val col = SpectralRecipe.lerpColor(colors.bg, baseCol, fade)
            val glow = if (s.bright && fade > 0.45f) 1 else 0
            putCell(s.x.roundToInt(), s.y.roundToInt(), s.ch, col, glow)
        }
    }

    // -------------------------------------------------------------------------
    // Spawners & Helpers
    // -------------------------------------------------------------------------

    private fun refreshClockText(force: Boolean) {
        if (!config.useSystemTime) {
            val h = (config.simulatedMinutes / 60).coerceIn(0, 23)
            val m = (config.simulatedMinutes % 60).coerceIn(0, 59)
            cachedTimeText = String.format(Locale.US, "%02d:%02d", h, m)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastClockCheckMs < 1000L) return
        lastClockCheckMs = now
        reusableDate.time = now
        cachedTimeText = clockFormat.format(reusableDate)
        cachedDateText = dateFormat.format(reusableDate)
            .replace(".", "")
            .lowercase(Locale.forLanguageTag("nl-NL"))
    }

    private fun seedAmbientSpores() {
        for (s in spores) s.active = false
        repeat(14) {
            spawnAmbientSpore(randomHeight = true)
        }
    }

    private fun spawnAmbientSpore(randomHeight: Boolean = false) {
        val slot = spores.firstOrNull { !it.active } ?: return
        slot.active = true
        slot.x = if (animRng.nextFloat() < 0.72f) {
            if (animRng.nextBoolean()) {
                animRng.nextFloat() * (cols * 0.24f) + 1f
            } else {
                cols - 2f - animRng.nextFloat() * (cols * 0.24f)
            }
        } else {
            animRng.nextFloat() * (cols - 4f) + 2f
        }
        slot.y = if (randomHeight) animRng.nextFloat() * rows else (rows - 2f - animRng.nextFloat() * 8f)
        slot.vx = (animRng.nextFloat() - 0.5f) * 0.6f
        slot.vy = -(1.2f + animRng.nextFloat() * 2.0f)
        slot.life = 0f
        slot.maxLife = 5.5f + animRng.nextFloat() * 6.0f
        slot.phase = animRng.nextFloat() * 6.28f
        slot.bright = animRng.nextFloat() > 0.72f
        slot.ch = if (slot.bright) '°' else '·'
    }

    private fun spawnSporeBurst(gx: Float, gy: Float, count: Int) {
        var spawned = 0
        for (s in spores) {
            if (s.active) continue
            val angle = animRng.nextFloat() * (2f * PI.toFloat())
            val spd = 3.5f + animRng.nextFloat() * 7.5f
            s.active = true
            s.x = gx
            s.y = gy
            s.vx = cos(angle) * spd
            s.vy = sin(angle) * spd - 2.2f
            s.life = 0f
            s.maxLife = 1.6f + animRng.nextFloat() * 1.8f
            s.phase = animRng.nextFloat() * 6.28f
            s.bright = true
            s.ch = when (animRng.nextInt(4)) {
                0 -> '¤'
                1 -> '*'
                2 -> '°'
                else -> '·'
            }
            spawned++
            if (spawned >= count) break
        }
    }

    private fun spawnRipple(gx: Float, gy: Float, maxRadius: Float, strength: Float) {
        val slot = ripples.firstOrNull { !it.active } ?: ripples.minByOrNull { it.strength } ?: return
        slot.active = true
        slot.x = gx
        slot.y = gy
        slot.radius = 0.5f
        slot.maxRadius = maxRadius
        slot.strength = strength
    }

    private fun spawnEnergyPulse(side: Int, speed: Float, intensity: Float, fromCharge: Boolean) {
        val slot = pulses.firstOrNull { !it.active } ?: return
        lastPulseLaunch = animElapsed
        slot.active = true
        slot.progress = 0f
        slot.speed = speed
        slot.side = side
        slot.intensity = intensity
        slot.fromCharge = fromCharge
    }

    private fun putCell(x: Int, y: Int, ch: Char, color: Int, glow: Int) {
        if (x in 0 until cols && y in 0 until rows) {
            val idx = y * cols + x
            gridChars[idx] = ch
            gridColors[idx] = color
            rowHasContent[y] = true
            if (glow > gridGlow[idx].toInt()) {
                if (gridGlow[idx].toInt() == 0 && glowCount < glowIndices.size) {
                    glowIndices[glowCount++] = idx
                }
                gridGlow[idx] = glow.toByte()
            }
        }
    }

    private fun bezier(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
        val u = 1f - t
        return (u * u * u * p0) +
            (3f * u * u * t * p1) +
            (3f * u * t * t * p2) +
            (t * t * t * p3)
    }

    private fun dirChar(dx: Int, dy: Int): Char = when {
        dx == 0 -> '|'
        dy == 0 -> '_'
        (dx > 0 && dy > 0) || (dx < 0 && dy < 0) -> '\\'
        else -> '/'
    }
}
