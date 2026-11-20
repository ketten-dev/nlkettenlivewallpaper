package nl.ketten.livewallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 *  - Tapering, breathing organic roots blended with PCB traces & USB-C power nexus
 *  - Radial-gradient bloom lights that locally illuminate nearby trunks & leaves
 *  - Smooth 24-hour organic growth cycle (morning baseline -> day growth -> evening peak -> night wind-down)
 *  - Prominent sub-pixel 3D swaying KETTEN logo with gyro parallax & AMOLED burn-in shift
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

    // Time & animation state
    private var wallElapsed: Float = 0f
    private var animElapsed: Float = 0f
    private var lastInteractionTime: Float = -10f
    private var paletteRefreshAccum: Float = 0f
    private var chargePulseTimer: Float = 0f
    private var passivePulseTimer: Float = 1.5f
    private var lastDragTouchTime: Float = 0f
    private var logoRippleTime: Float = -10f
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
        val variant: Int = 0,  // Bark nerve / leaf tone variation
    )

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

    private val floraNodes = ArrayList<FloraNode>(4500)
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

    private val leafAnimChars = charArrayOf('~', '/', '\\', '\'', '"', ',', '*')

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
        val rawStep = dt.coerceIn(0.001f, 0.06f)
        val simStep = (rawStep * config.animationSpeed).coerceIn(0.0005f, 0.14f)

        wallElapsed += rawStep
        animElapsed += simStep

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
            r.radius += simStep * 24f
            r.strength *= (1f - simStep * 1.45f).coerceAtLeast(0f)
            if (r.radius >= r.maxRadius || r.strength <= 0.03f) {
                r.active = false
            }
        }

        // Update energy pulses
        for (p in pulses) {
            if (!p.active) continue
            p.progress += simStep * p.speed
            if (p.progress > 1.18f) {
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
            val windPush = if (config.windEnabled) sin(animElapsed * 1.3f + s.phase) * 1.6f else 0f
            val sway = windPush + smoothGyroX * 1.4f
            s.x += (s.vx + sway) * simStep
            s.y += s.vy * simStep
            s.vx *= (1f - simStep * 1.6f).coerceAtLeast(0f)
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

        val growthChanged = builtGrowthFactor < 0f || abs(targetGrowth - builtGrowthFactor) >= 0.012f
        val lightsChanged = builtLightCount != config.lightCount

        if (forceRebuild || growthChanged || lightsChanged) {
            builtGrowthFactor = targetGrowth
            builtLightCount = config.lightCount
            generateWorld(targetGrowth, config.lightCount)
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
        ambientLights.clear()
        groupCount = 1

        // Normalized daily growth progression: 0.0 at 06:00 morning baseline -> 1.0 at evening peak
        val g = ((growth - 0.15f) / 0.85f).coerceIn(0f, 1f)

        // Deterministic seed per viewport dimensions so growth smoothly expands existing flora
        val gen = Random(1337 + cols * 31 + rows * 7)

        val cx = (cols - 1) * 0.5f
        val bottomY = (rows - 1).toFloat()
        // PCB mycelium occupies approximately the bottom quarter of the screen (~25%)
        val rootTopY = rows * (0.75f - g * 0.02f)
        val leftJoinX = cols * 0.10f
        val rightJoinX = cols * 0.90f

        // --- A. LAYER 0 (3rd depth plane): Far Atmospheric Background (emerges midday -> lush evening) ---
        if (g > 0.24f) {
            val farG = ((g - 0.24f) / 0.76f).coerceIn(0f, 1f)
            generateFarBackgroundLayer(cx, bottomY, farG, gen)
        }

        // --- B. LAYER 1 (2nd depth plane): Midground Stems, Corners & Deep Canopy (emerges late morning -> evening) ---
        if (g > 0.10f) {
            val midG = ((g - 0.10f) / 0.82f).coerceIn(0f, 1f)
            generateMidgroundLayer(cx, bottomY, rootTopY, leftJoinX, rightJoinX, midG, gen)
        }

        // --- C. LAYER 2 (Foreground): Mycelium Network + PCB Traces ---
        // Morning: simple mycelium following the current base curve.
        // Day -> Evening: expands & branches outward into bottom-left and bottom-right screen corners.
        generateMyceliumNetwork(cx, bottomY, rootTopY, leftJoinX, rightJoinX, g, gen)

        // --- D. LAYER 2 (Foreground): Two Slender Morning Vines -> 3D Trunks + Leaf-Node Branches ---
        val anchors = ArrayList<SproutAnchor>(24)
        generateMainVineAndBranches(
            p0x = leftJoinX, p0y = rootTopY,
            p1x = 2.6f, p1y = rows * 0.60f,
            p2x = 3.0f, p2y = rows * 0.14f,
            p3x = cx - 2.0f,
            side = -1, g = g, anchors = anchors, gen = gen,
        )
        generateMainVineAndBranches(
            p0x = rightJoinX, p0y = rootTopY,
            p1x = cols - 3.6f, p1y = rows * 0.60f,
            p2x = cols - 4.0f, p2y = rows * 0.14f,
            p3x = cx + 2.0f,
            side = 1, g = g, anchors = anchors, gen = gen,
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
        generateAmbientLights(lightCount, g, anchors, gen)
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
        bottomY: Float,
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

            val botStems = if (farG > 0.40f) 4 else 2
            for (k in 0 until botStems) {
                val frac = (k + 0.5f) / botStems
                val startX = if (side < 0) cols * (0.10f + frac * 0.20f) else cols * (0.90f - frac * 0.20f)
                val startY = rows * (0.76f + frac * 0.12f)
                val endX = edgeX + (-side) * (k * 2.0f)
                val endY = bottomY - k * 2.0f
                generateBackgroundCornerSprig(
                    startX = startX, startY = startY,
                    targetX = endX, targetY = endY,
                    fillFactor = farG,
                    side = side, layer = 0, progressBase = 0.08f,
                    gen = Random(7500 + side * 317 + k * 79),
                )
            }

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
            p0x = leftJoinX + 2.5f, p0y = rootTopY + 2f,
            p1x = cols * 0.09f, p1y = rows * 0.56f,
            p2x = cols * 0.11f, p2y = rows * 0.18f,
            p3x = cx - 5f,
            side = -1, growth = midG, gen = gen,
        )
        generateMidVine(
            p0x = rightJoinX - 2.5f, p0y = rootTopY + 2f,
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

            // Bottom-corner fan + rising lower-field midground undergrowth below the logo box
            val botOriginX = if (side < 0) leftJoinX + 3f else rightJoinX - 3f
            val botOriginY = rootTopY + 1f
            generateBackgroundCornerSprig(
                startX = botOriginX, startY = botOriginY,
                targetX = edgeX, targetY = bottomY - 0.5f,
                fillFactor = midG, side = side, layer = 1, progressBase = 0.12f,
                gen = Random(5400 + side * 199),
            )
            if (midG > 0.25f) {
                val subG = ((midG - 0.18f) / 0.82f).coerceIn(0f, 1f)
                generateBackgroundCornerSprig(
                    startX = botOriginX + inward * 6f, startY = bottomY - 4f,
                    targetX = edgeX + inward * 5f, targetY = bottomY - 0.5f,
                    fillFactor = subG, side = side, layer = 1, progressBase = 0.08f,
                    gen = Random(5500 + side * 211),
                )
                // Rising midground tendrils below the logo exclusion border
                generateBackgroundCanopyVine(
                    startX = if (side < 0) cols * 0.14f else cols * 0.86f,
                    startY = bottomY - 2f,
                    targetX = cx + side * 6f,
                    targetY = (logoBotY + 2f).coerceAtMost(rows * 0.78f),
                    fill = subG, side = side, layer = 1, progressBase = 0.18f,
                    gen = Random(5540 + side * 229),
                )
                generateBackgroundCanopyVine(
                    startX = if (side < 0) cols * 0.28f else cols * 0.72f,
                    startY = bottomY - 1f,
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
     * Generates the bottom mycelium & PCB-trace network:
     * - Morning (g ~ 0): simple slender mycelium following the base curve + minimal PCB traces.
     * - Day -> Evening (g -> 1): expands and branches outward to fill bottom-left & bottom-right corners.
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
        // Two main mycelium trunks connecting USB-C nexus to the left & right vines
        generateOrganicRoot(
            p0x = cx - 1f, p0y = bottomY,
            p1x = cx - cols * 0.17f, p1y = bottomY - rows * 0.03f,
            p2x = cols * 0.17f, p2y = bottomY - rows * 0.09f,
            p3x = leftJoinX, p3y = rootTopY,
            side = -1, g = g, gen = gen,
        )
        generateOrganicRoot(
            p0x = cx + 1f, p0y = bottomY,
            p1x = cx + cols * 0.17f, p1y = bottomY - rows * 0.03f,
            p2x = cols * 0.83f, p2y = bottomY - rows * 0.09f,
            p3x = rightJoinX, p3y = rootTopY,
            side = 1, g = g, gen = gen,
        )

        // Subtle morning mycelium tendrils extending gently along the base; grow into full bottom corners by evening
        val baseSteps = (5 + g * 14f).roundToInt()
        addCreepingRootBranch(cx - 5f, bottomY - 1f, dirX = -1, steps = baseSteps, side = -1, g = g, gen = gen)
        addCreepingRootBranch(cx + 5f, bottomY - 1f, dirX = 1, steps = baseSteps, side = 1, g = g, gen = gen)

        // Corner-filling mycelium fan sprouting outward into (0, bottomY) and (cols-1, bottomY)
        if (g > 0.12f) {
            val cornerG = ((g - 0.12f) / 0.88f).coerceIn(0f, 1f)
            for (side in intArrayOf(-1, 1)) {
                val edgeX = if (side < 0) 0.5f else cols - 1.5f
                val joinX = if (side < 0) leftJoinX else rightJoinX
                // Branch 1: straight from vine base diagonally down into bottom corner
                addCornerMyceliumThread(
                    startX = joinX, startY = rootTopY + 1f,
                    targetX = edgeX, targetY = bottomY - 0.5f,
                    fill = cornerG, side = side, progressBase = 0.14f,
                    gen = Random(8100 + side * 137),
                )
                // Branch 2: lower horizontal-sweeping hyphae into bottom corner margin
                addCornerMyceliumThread(
                    startX = cx + side * cols * 0.24f, startY = bottomY - rows * 0.045f,
                    targetX = edgeX, targetY = bottomY - 1.2f,
                    fill = cornerG, side = side, progressBase = 0.08f,
                    gen = Random(8200 + side * 149),
                )
                // Branch 3: reaches mid-lower corner wall as growth deepens
                if (cornerG > 0.28f) {
                    addCornerMyceliumThread(
                        startX = joinX, startY = rootTopY + 2.5f,
                        targetX = edgeX, targetY = bottomY - rows * 0.08f,
                        fill = ((cornerG - 0.20f) / 0.80f).coerceIn(0f, 1f),
                        side = side, progressBase = 0.16f,
                        gen = Random(8300 + side * 151),
                    )
                    // Inward lower root flourish
                    addCreepingRootBranch(
                        startX = joinX - side * 1f, startY = rootTopY + 3f,
                        dirX = -side, steps = (4 + cornerG * 8f).roundToInt(),
                        side = side, g = g, gen = gen,
                    )
                }
            }
        }

        // Geometric 45-degree PCB-trace roots (Ketten Organic Hardware signature)
        // PCB traces grow with daily cycle:
        // - Morning: modest single-track bus lines
        // - Afternoon & Evening: main traces widen to 2-3 parallel tracks and reach further into the bottom quarter
        val traceWidth = if (g < 0.20f) 1 else if (g < 0.65f) 2 else 3

        // Main PCB power rails emerging from USB-C nexus
        val pcbLen1 = (5 + g * 6f).roundToInt()
        val pcbDiag1 = (2 + g * 4f).roundToInt()
        val pcbLen2 = (4 + g * 7f).roundToInt()
        addPcbTraceRoot(cx - 2f, bottomY - 1f, dirX = -1, len1 = pcbLen1, diag = pcbDiag1, len2 = pcbLen2, side = -1, pBase = 0.04f, width = traceWidth, gen = gen)
        addPcbTraceRoot(cx + 2f, bottomY - 1f, dirX = 1, len1 = pcbLen1, diag = pcbDiag1, len2 = pcbLen2, side = 1, pBase = 0.04f, width = traceWidth, gen = gen)

        // Mid-height PCB bus traces traversing higher into the bottom quarter (up to rootTopY ~ 0.75f)
        if (g > 0.15f) {
            val diag2 = (3 + g * 5f).roundToInt()
            val busWidth = if (g > 0.70f) 2 else 1
            addPcbTraceRoot(cx - 8f, bottomY - 3f, dirX = -1, len1 = 4 + (g * 3f).roundToInt(), diag = diag2, len2 = 5 + (g * 5f).roundToInt(), side = -1, pBase = 0.09f, width = busWidth, gen = gen)
            addPcbTraceRoot(cx + 8f, bottomY - 3f, dirX = 1, len1 = 4 + (g * 3f).roundToInt(), diag = diag2, len2 = 5 + (g * 5f).roundToInt(), side = 1, pBase = 0.09f, width = busWidth, gen = gen)
        }

        // Upper PCB traces climbing up toward the bottom quarter boundary (~ rows * 0.75)
        if (g > 0.35f) {
            val upperG = ((g - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val diag3 = (3 + upperG * 4f).roundToInt()
            addPcbTraceRoot(cx - 3f, bottomY - 2f, dirX = -1, len1 = 3, diag = diag3, len2 = (4 + upperG * 5f).roundToInt(), side = -1, pBase = 0.07f, width = 1, gen = gen)
            addPcbTraceRoot(cx + 3f, bottomY - 2f, dirX = 1, len1 = 3, diag = diag3, len2 = (4 + upperG * 5f).roundToInt(), side = 1, pBase = 0.07f, width = 1, gen = gen)

            // High lateral PCB data lines spanning horizontally across bottom quarter
            val latStartX = cx - cols * 0.18f
            addPcbTraceRoot(latStartX, rootTopY + 3f, dirX = -1, len1 = (6 + upperG * 6f).roundToInt(), diag = 2, len2 = (4 + upperG * 4f).roundToInt(), side = -1, pBase = 0.12f, width = if (g > 0.75f) 2 else 1, gen = gen)
            addPcbTraceRoot(cx + cols * 0.18f, rootTopY + 3f, dirX = 1, len1 = (6 + upperG * 6f).roundToInt(), diag = 2, len2 = (4 + upperG * 4f).roundToInt(), side = 1, pBase = 0.12f, width = if (g > 0.75f) 2 else 1, gen = gen)
        }

        // Dense evening IC breakout / ground plane traces
        if (g > 0.60f) {
            val evG = ((g - 0.60f) / 0.40f).coerceIn(0f, 1f)
            addPcbTraceRoot(cx - 12f, bottomY - 5f, dirX = -1, len1 = 2, diag = (2 + evG * 3f).roundToInt(), len2 = (3 + evG * 4f).roundToInt(), side = -1, pBase = 0.14f, width = 1, gen = gen)
            addPcbTraceRoot(cx + 12f, bottomY - 5f, dirX = 1, len1 = 2, diag = (2 + evG * 3f).roundToInt(), len2 = (3 + evG * 4f).roundToInt(), side = 1, pBase = 0.14f, width = 1, gen = gen)
        }
    }

    private fun addCornerMyceliumThread(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        fill: Float,
        side: Int,
        progressBase: Float,
        gen: Random,
    ) {
        if (fill <= 0.04f) return
        val dist = hypot(targetX - startX, targetY - startY).coerceAtLeast(1f)
        val steps = (dist * (0.25f + fill * 0.85f)).roundToInt().coerceAtLeast(2)
        val gid = allocGroup(gen)
        val dxU = (targetX - startX) / dist
        val dyU = (targetY - startY) / dist
        val perpX = -dyU
        val perpY = dxU
        val waveAmp = 0.9f + gen.nextFloat() * 1.4f

        var prevX = startX.roundToInt()
        var prevY = startY.roundToInt()
        for (s in 1..steps) {
            val u = s.toFloat() / steps
            val reach = (s.toFloat() / dist).coerceAtMost(1.05f)
            val wobble = sin(u * PI.toFloat() * 1.5f) * waveAmp
            val px = (startX + (targetX - startX) * reach + perpX * wobble).roundToInt()
            val py = (startY + (targetY - startY) * reach + perpY * wobble).roundToInt()
            if (px !in 0 until cols || py !in (rows * 0.68f).toInt() until rows) break

            if (px != prevX || py != prevY) {
                val isTip = s == steps
                val ch = when {
                    isTip -> '°'
                    s % 4 == 0 -> '~'
                    else -> dirChar(px - prevX, py - prevY)
                }
                floraNodes.add(
                    FloraNode(
                        x = px.toFloat(), y = py.toFloat(),
                        ch = ch,
                        kind = if (isTip && fill > 0.45f) NodeKind.ROOT_PAD else NodeKind.ROOT_ORGANIC,
                        side = side,
                        progress = (progressBase + u * 0.12f).coerceIn(0f, 1f),
                        groupId = gid, phase = gen.nextFloat() * 6.28f,
                        flex = 0.14f + u * 0.26f,
                        shade = 0.35f - u * 0.85f,
                    )
                )
                // Fine lateral mycelium hyphae branching off along the thread
                if (fill > 0.35f && s % 3 == 0) {
                    val subLen = (1 + fill * 3.2f).roundToInt()
                    val subDirY = if (s % 6 == 0) 1 else -1
                    for (k in 1..subLen) {
                        val hx = (px + side * (k / 2)).coerceIn(0, cols - 1)
                        val hy = (py + subDirY * k).coerceIn((rows * 0.72f).toInt(), rows - 1)
                        floraNodes.add(
                            FloraNode(
                                x = hx.toFloat(), y = hy.toFloat(),
                                ch = if (k == subLen) '.' else if (side < 0) '/' else '\\',
                                kind = NodeKind.ROOT_ORGANIC, side = side,
                                progress = (progressBase + u * 0.12f).coerceIn(0f, 1f),
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = 0.20f + k * 0.10f, shade = -0.35f,
                            )
                        )
                    }
                }
                prevX = px
                prevY = py
            }
        }
    }

    private fun generateOrganicRoot(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float, p3y: Float,
        side: Int,
        g: Float,
        gen: Random,
    ) {
        var lastX = -999
        var lastY = -999
        var step = 0
        var t = 0f
        while (t <= 1f) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            if (ix != lastX || iy != lastY) {
                val ch = if (lastX == -999) '_' else dirChar(ix - lastX, iy - lastY)
                val prog = 0.22f * t
                // Morning (g=0): slender single-cell mycelium strand; evening: thick 2-3 cell tapering root
                val thickness = if (g < 0.18f) {
                    1
                } else {
                    (1 + ((t * 0.7f + 0.3f) * g * 1.9f).roundToInt()).coerceIn(1, 3)
                }
                for (w in 0 until thickness) {
                    val shade = if (thickness == 1) 0.45f else ((w.toFloat() / (thickness - 1)) * 2f - 1f)
                    floraNodes.add(
                        FloraNode(
                            x = ix.toFloat(),
                            y = (iy + w).coerceAtMost(rows - 1).toFloat(),
                            ch = if (w == 0) ch else if (w == thickness - 1) '.' else '~',
                            kind = NodeKind.ROOT_ORGANIC,
                            side = side,
                            progress = prog,
                            groupId = 0,
                            phase = gen.nextFloat() * 6.28f,
                            flex = 0.12f + w * 0.08f,
                            shade = shade,
                        )
                    )
                }

                // Subtle mycelium nodes in morning -> longer root hairs over the day
                if (step > 1 && step % 5 == 0 && iy < rows - 1) {
                    if (g < 0.15f) {
                        // Simple tiny mycelium node dot below strand
                        floraNodes.add(
                            FloraNode(
                                x = ix.toFloat(),
                                y = (iy + 1).coerceAtMost(rows - 1).toFloat(),
                                ch = '·',
                                kind = NodeKind.ROOT_ORGANIC,
                                side = side,
                                progress = prog,
                                groupId = 0,
                                phase = gen.nextFloat() * 6.28f,
                                flex = 0.18f,
                                shade = 0.2f,
                            )
                        )
                    } else {
                        val hairLen = 1 + gen.nextInt(1 + (g * 3.5f).roundToInt())
                        val gid = allocGroup(gen)
                        for (h in 1..hairLen) {
                            floraNodes.add(
                                FloraNode(
                                    x = (ix + (if (h % 2 == 0) side else 0)).coerceIn(0, cols - 1).toFloat(),
                                    y = (iy + h).coerceAtMost(rows - 1).toFloat(),
                                    ch = if (h == hairLen) '.' else if (side < 0) '/' else '\\',
                                    kind = NodeKind.ROOT_ORGANIC,
                                    side = side,
                                    progress = prog + 0.01f * h,
                                    groupId = gid,
                                    phase = gen.nextFloat() * 6.28f,
                                    flex = 0.20f + h * 0.12f,
                                    shade = -0.3f,
                                )
                            )
                        }
                    }
                }
                lastX = ix
                lastY = iy
                step++
            }
            t += 0.008f
        }
    }

    private fun addCreepingRootBranch(
        startX: Float,
        startY: Float,
        dirX: Int,
        steps: Int,
        side: Int,
        g: Float,
        gen: Random,
    ) {
        var rx = startX
        var ry = startY
        val gid = allocGroup(gen)
        val total = steps.coerceAtLeast(1)
        for (i in 0 until steps) {
            val frac = i.toFloat() / total
            rx += dirX * (0.85f + gen.nextFloat() * 0.35f)
            ry -= (0.22f + sin(frac * PI.toFloat()) * 0.30f)
            val ix = rx.roundToInt()
            val iy = ry.roundToInt()
            if (ix !in 0 until cols || iy !in (rows * 0.72f).toInt() until rows) break

            val ch = when {
                i == steps - 1 -> if (g > 0.3f) '°' else '.'
                i % 3 == 0 -> if (dirX > 0) '/' else '\\'
                else -> '~'
            }
            floraNodes.add(
                FloraNode(
                    x = ix.toFloat(), y = iy.toFloat(), ch = ch,
                    kind = NodeKind.ROOT_ORGANIC, side = side,
                    progress = 0.05f + frac * 0.16f,
                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                    flex = 0.14f + frac * 0.32f,
                    shade = 0.4f - frac * 0.9f,
                )
            )
            if (frac < 0.4f && g > 0.45f && iy + 1 < rows) {
                floraNodes.add(
                    FloraNode(
                        x = ix.toFloat(), y = (iy + 1).toFloat(), ch = '_',
                        kind = NodeKind.ROOT_ORGANIC, side = side,
                        progress = 0.05f + frac * 0.16f,
                        groupId = gid, phase = gen.nextFloat() * 6.28f,
                        flex = 0.12f, shade = -0.5f,
                    )
                )
            }
        }
    }

    private fun addPcbTraceRoot(
        startX: Float,
        startY: Float,
        dirX: Int,
        len1: Int,
        diag: Int,
        len2: Int,
        side: Int,
        pBase: Float,
        width: Int = 1,
        gen: Random,
    ) {
        val total = (len1 + diag + len2).coerceAtLeast(1)
        val clampedWidth = width.coerceIn(1, 3)

        for (w in 0 until clampedWidth) {
            val yOffset = w
            var cx = startX.roundToInt()
            var cy = (startY.roundToInt() + yOffset).coerceAtMost(rows - 1)
            var stepIdx = 0

            repeat(len1) {
                cx += dirX
                stepIdx++
                if (cx in 1 until cols - 1 && cy in (rows * 0.70f).toInt() until rows) {
                    val traceChar = if (w == 0) '_' else if (w == clampedWidth - 1) '-' else '='
                    floraNodes.add(
                        FloraNode(
                            x = cx.toFloat(), y = cy.toFloat(),
                            ch = traceChar, kind = NodeKind.ROOT_PCB, side = side,
                            progress = pBase + 0.10f * (stepIdx.toFloat() / total),
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.0f,
                        )
                    )
                }
            }
            repeat(diag) {
                cx += dirX
                cy -= 1
                stepIdx++
                if (cx in 1 until cols - 1 && cy in (rows * 0.70f).toInt() until rows) {
                    val diagChar = if (dirX > 0) '/' else '\\'
                    floraNodes.add(
                        FloraNode(
                            x = cx.toFloat(), y = cy.toFloat(),
                            ch = diagChar, kind = NodeKind.ROOT_PCB, side = side,
                            progress = pBase + 0.10f * (stepIdx.toFloat() / total),
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.0f,
                        )
                    )
                }
            }
            for (i in 0 until len2) {
                cx += dirX
                stepIdx++
                if (cx in 1 until cols - 1 && cy in (rows * 0.70f).toInt() until rows) {
                    val isPad = (i == len2 - 1 && w == 0)
                    val segmentChar = when {
                        isPad -> 'o'
                        w == 0 -> '-'
                        w == clampedWidth - 1 -> '_'
                        else -> '='
                    }
                    floraNodes.add(
                        FloraNode(
                            x = cx.toFloat(), y = cy.toFloat(),
                            ch = segmentChar,
                            kind = if (isPad) NodeKind.ROOT_PAD else NodeKind.ROOT_PCB,
                            side = side,
                            progress = pBase + 0.12f * (stepIdx.toFloat() / total),
                            groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.0f,
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
                val colWidth = if (g < 0.12f) {
                    1
                } else {
                    (baseThickness * taper).roundToInt().coerceIn(1, 5)
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

                    // 1. INITIAL MORNING LEAF (always present on the vine at this node!)
                    val leafSideDir = if (anchorIdx % 2 == 0) inwardDir else outwardDir
                    val leafX = (ix + leafSideDir * (colWidth / 2 + 1)).coerceIn(0, cols - 1)
                    val leafY = (iy - (if (t > 0.80f) 0 else 1)).coerceIn(0, rows - 1)
                    floraNodes.add(
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
                    // Zero branches in morning (g <= 0.08); branches sprout & fill the 4 rectangular corners.
                    val startG = 0.08f + (anchorIdx % 3) * 0.055f
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

                                // Extra corner-filling twin bough from nodes 7 & 8 so the outer top corners fill completely
                                if ((anchorIdx == 7 || anchorIdx == 8) && branchG > 0.35f) {
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

                                // Secondary inward canopy bough on high arch once growth matures
                                if (cornerProgress > 0.35f && branchG > 0.32f) {
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

                            // LOWER TRUNK NODES -> sweep outward & down to fill BOTTOM-LEFT & BOTTOM-RIGHT corners!
                            t < 0.24f -> {
                                val targetY = if (anchorIdx == 0) rows - 1.2f else rows * 0.86f
                                val tip = growOrganicBranch(
                                    startX = ix + outwardDir * (colWidth / 2f),
                                    startY = iy.toFloat(),
                                    targetX = edgeX,
                                    targetY = targetY,
                                    branchG = branchG,
                                    side = side,
                                    progBase = prog,
                                    gid = gid,
                                    allowInCenter = false,
                                    gen = nodeRng,
                                )
                                anchor.tipX = tip.first
                                anchor.tipY = tip.second

                                // Secondary lower corner frond sweeping along bottom edge
                                if (anchorIdx == 0 && branchG > 0.38f) {
                                    val cG = ((branchG - 0.38f) / 0.62f).coerceIn(0f, 1f)
                                    growOrganicBranch(
                                        startX = ix + outwardDir * (colWidth / 2f),
                                        startY = iy.toFloat(),
                                        targetX = edgeX + inwardDir * (cols * 0.08f),
                                        targetY = rows - 1.2f,
                                        branchG = cG,
                                        side = side,
                                        progBase = prog,
                                        gid = allocGroup(nodeRng),
                                        allowInCenter = true,
                                        gen = nodeRng,
                                    )
                                }

                                // Short inward lower sprig (stays below the logo zone)
                                if (branchG > 0.35f) {
                                    growOrganicBranch(
                                        startX = ix + inwardDir * (colWidth / 2f),
                                        startY = iy.toFloat(),
                                        targetX = ix + inwardDir * (4f + branchG * 6f),
                                        targetY = (iy - 2.5f).coerceAtLeast(rows * 0.72f),
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

        for (s in 1..steps) {
            val u = s.toFloat() / steps
            val travel = s.toFloat() / dist
            val arch = sin(u * PI.toFloat()) * archAmp
            val bx = (startX + (targetX - startX) * travel + perpX * arch).roundToInt()
            val by = (startY + (targetY - startY) * travel + perpY * arch).roundToInt()

            if (bx !in 0 until cols || by !in 0 until rows) break
            // Hard exclusion border around KETTEN logo + readout (nothing may grow inside!)
            if (isInsideLogoExclusion(bx.toFloat(), by.toFloat(), pad = 0.5f)) break
            if (!allowInCenter && by in (rows * 0.24f).toInt()..(rows * 0.72f).toInt() &&
                bx > cols * 0.22f && bx < cols * 0.78f
            ) {
                break
            }

            if (bx != prevX || by != prevY) {
                val bChar = dirChar(bx - prevX, by - prevY)
                val bFlex = 0.30f + u * 0.95f
                val bProg = (progBase + u * 0.08f).coerceAtMost(1f)

                floraNodes.add(
                    FloraNode(
                        x = bx.toFloat(), y = by.toFloat(), ch = bChar,
                        kind = NodeKind.BRANCH, side = side, progress = bProg,
                        groupId = gid, phase = gen.nextFloat() * 6.28f, flex = bFlex,
                    )
                )

                // Layered foliage leaves along the growing branch
                val leafChance = 0.45f - branchG * 0.35f
                if (by > 0 && gen.nextFloat() > leafChance &&
                    !isInsideLogoExclusion(bx.toFloat(), (by - 1).toFloat(), pad = 0.5f)
                ) {
                    floraNodes.add(
                        FloraNode(
                            x = bx.toFloat(), y = (by - 1).toFloat(),
                            ch = if (dxU >= 0f) '/' else '\\',
                            kind = NodeKind.LEAF, side = side, progress = bProg,
                            groupId = gid, phase = gen.nextFloat() * 6.28f,
                            flex = bFlex * 1.15f, variant = gen.nextInt(3),
                        )
                    )
                }
                if (s >= 2 && by < rows - 1 && gen.nextFloat() > (leafChance + 0.22f) &&
                    !isInsideLogoExclusion(bx.toFloat(), (by + 1).toFloat(), pad = 0.5f)
                ) {
                    floraNodes.add(
                        FloraNode(
                            x = bx.toFloat(), y = (by + 1).toFloat(),
                            ch = '~',
                            kind = NodeKind.LEAF, side = side, progress = bProg,
                            groupId = gid, phase = gen.nextFloat() * 6.28f,
                            flex = bFlex * 1.10f, variant = gen.nextInt(3),
                        )
                    )
                }

                // Secondary sub-twig forks off once the branch is well developed (fills corners densely!)
                val forkStride = if (branchG > 0.65f) 3 else 4
                if (branchG > 0.40f && s >= 3 && s % forkStride == 0 && s < steps - 1) {
                    val forkSign = if ((s / forkStride) % 2 == 0) 1f else -1f
                    val subLen = (2 + (branchG - 0.35f) * 5.2f).roundToInt().coerceIn(2, 5)
                    var sx = bx.toFloat()
                    var sy = by.toFloat()
                    val subDx = (dxU * 0.62f + perpX * forkSign * 0.78f)
                    val subDy = (dyU * 0.62f + perpY * forkSign * 0.78f)
                    for (k in 1..subLen) {
                        sx += subDx
                        sy += subDy
                        val isx = sx.roundToInt()
                        val isy = sy.roundToInt()
                        if (isx !in 0 until cols || isy !in 0 until rows) break
                        if (isInsideLogoExclusion(isx.toFloat(), isy.toFloat(), pad = 0.5f)) break
                        if (!allowInCenter && isy in (rows * 0.24f).toInt()..(rows * 0.72f).toInt() &&
                            isx > cols * 0.22f && isx < cols * 0.78f
                        ) break

                        val subKind = if (k == subLen && branchG > 0.65f) NodeKind.BUD else NodeKind.LEAF
                        val subCh = when {
                            subKind == NodeKind.BUD -> '*'
                            k == 1 -> if (subDx >= 0f) '/' else '\\'
                            else -> if (k % 2 == 0) '~' else '\''
                        }
                        floraNodes.add(
                            FloraNode(
                                x = isx.toFloat(), y = isy.toFloat(), ch = subCh,
                                kind = subKind, side = side, progress = bProg,
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = bFlex * (1.05f + k * 0.08f), variant = gen.nextInt(3),
                            )
                        )
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
            if (!isInsideLogoExclusion(tipX.toFloat(), tipY.toFloat(), pad = 0.5f)) {
                if (branchG > 0.50f) {
                    val capX = (tipX + perpX.roundToInt()).coerceIn(0, cols - 1)
                    val capY = (tipY + perpY.roundToInt()).coerceIn(0, rows - 1)
                    if (!isInsideLogoExclusion(capX.toFloat(), capY.toFloat(), pad = 0.5f)) {
                        floraNodes.add(
                            FloraNode(
                                x = capX.toFloat(), y = capY.toFloat(),
                                ch = if (dxU >= 0f) '/' else '\\',
                                kind = NodeKind.LEAF, side = side,
                                progress = (progBase + 0.085f).coerceAtMost(1f),
                                groupId = gid, phase = gen.nextFloat() * 6.28f,
                                flex = 1.18f, variant = 2,
                            )
                        )
                    }
                }
                floraNodes.add(
                    FloraNode(
                        x = tipX.toFloat(), y = tipY.toFloat(),
                        ch = if (branchG > 0.45f) '¤' else '*',
                        kind = NodeKind.BUD, side = side,
                        progress = (progBase + 0.09f).coerceAtMost(1f),
                        groupId = gid, phase = gen.nextFloat() * 6.28f,
                        flex = 1.25f,
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
                    baseY = ly.coerceIn(1f, rows - 2f),
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
                val driftX = sin(animElapsed * 0.65f + l.phase) * 1.15f
                val driftY = cos(animElapsed * 0.52f + l.phase * 1.3f) * 0.85f
                l.curX = l.baseX + parallaxX + driftX
                l.curY = l.baseY + parallaxY + driftY
                l.intensity = 0.52f + 0.48f * (sin(animElapsed * 1.35f + l.phase) * 0.5f + 0.5f)
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

            // Clear atmospheric multi-layer parallax (Far > Mid > Foreground > PCB Roots)
            val parallaxX = when {
                passLayer == 0 -> smoothSwipeNorm * 2.5f + smoothGyroX * 2.4f
                passLayer == 1 -> smoothSwipeNorm * 1.45f + smoothGyroX * 1.35f
                n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD -> smoothSwipeNorm * 0.40f + smoothGyroX * 0.25f
                else -> smoothSwipeNorm * 0.90f + smoothGyroX * 0.80f
            }
            val parallaxY = when {
                passLayer == 0 -> smoothGyroY * 0.95f
                passLayer == 1 -> smoothGyroY * 0.55f
                n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD -> 0f
                else -> smoothGyroY * 0.30f
            }

            var cx = n.x + parallaxX
            var cy = n.y + parallaxY

            // Wind & organic breathing motion (respects Wind toggle!)
            // Note: Roots and PCB traces are anchored in the substrate and are unaffected by wind!
            val isRootOrPcb = n.kind == NodeKind.ROOT_ORGANIC || n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD
            if (windActive && !isRootOrPcb && n.flex > 0.08f) {
                val windPhase = if (n.groupId > 0) groupPhases[n.groupId] else n.phase
                val breeze = sin(animElapsed * 1.15f + windPhase + n.y * 0.08f)
                cx += breeze * n.flex * 0.85f
                if (breeze > 0.55f && n.kind != NodeKind.CANOPY_HANG) {
                    cy -= (breeze - 0.55f) * n.flex * 0.65f
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
                val delta = abs(n.progress - p.progress)
                if (delta < 0.085f) {
                    val h = (1f - delta / 0.085f) * p.intensity
                    if (h > pulseHit) {
                        pulseHit = h
                        chargeHit = p.fromCharge
                    }
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
                NodeKind.FAR_VINE -> {
                    col = colors.farVine
                }
                NodeKind.FAR_LEAF -> {
                    col = if (n.variant == 1) {
                        SpectralRecipe.lerpColor(colors.farVine, colors.midStalk, 0.35f)
                    } else {
                        colors.farVine
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
                    col = if (n.variant == 1) {
                        SpectralRecipe.lerpColor(colors.midStalk, colors.leafDark, 0.85f)
                    } else {
                        SpectralRecipe.lerpColor(colors.midStalk, colors.leafDark, 0.55f)
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
                    val padPulse = (sin(animElapsed * 2.1f + n.phase) * 0.5f + 0.5f) * vitality
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
                    val boost = max(max(pulseHit, touchBoost), lightIllum * 0.7f)
                    col = if (boost > 0.18f) {
                        SpectralRecipe.lerpColor(colors.branch, colors.trunkRimWarm, boost * 0.8f)
                    } else {
                        colors.branch
                    }
                }
                NodeKind.LEAF -> {
                    if (windActive) {
                        val shimmerIdx = ((animElapsed * 1.8f + n.phase * 3f).toInt() and 0x7FFFFFFF) % leafAnimChars.size
                        ch = leafAnimChars[shimmerIdx]
                    }
                    val baseLeaf = when (n.variant) {
                        0 -> colors.leafDark
                        2 -> colors.leafBright
                        else -> colors.leaf
                    }
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
                        (sin(animElapsed * 1.4f + n.phase) * 0.5f + 0.5f) * vitality + pulseHit * 0.9f + touchBoost * 0.7f
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

            putCell(cx.roundToInt(), cy.roundToInt(), ch, col, glow)
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
        val beat = sin(animElapsed * if (isCharging) 5.0f else 1.8f) * 0.5f + 0.5f
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
        val burnShiftPxX = sin(wallElapsed * 0.011f) * 2.5f
        val burnShiftPxY = cos(wallElapsed * 0.008f) * 2.5f

        val anchorCol = (cols - logoW) * 0.5f - smoothSwipeNorm * 0.55f - smoothGyroX * 0.55f + logoTiltX * 0.22f
        val anchorRow = (rows * 0.42f).roundToInt().toFloat() - smoothGyroY * 0.22f

        val idleSwayX = sin(animElapsed * 0.48f) * 0.35f
        val swayCellsX = (smoothGyroX * 2.75f + smoothSwipeNorm * 1.6f + logoTiltX * 1.35f + idleSwayX)
            .coerceIn(-3.1f, 3.1f)
        val swayCellsY = (0.72f + smoothGyroY * 0.95f)
            .coerceIn(-0.45f, 1.55f)

        val extrudePxX = swayCellsX * cellW
        val extrudePxY = swayCellsY * cellH

        val sheenCycle = animElapsed % 6.5f
        val sheenPos = if (sheenCycle < 2.1f) (sheenCycle / 2.1f) * (logoW + 14f) - 7f else -100f

        val logoWaveAge = animElapsed - logoRippleTime
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
