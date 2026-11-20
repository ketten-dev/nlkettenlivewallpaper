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

    private val floraNodes = ArrayList<FloraNode>(1600)
    private val ambientLights = ArrayList<AmbientLight>(WallpaperPrefs.MAX_LIGHTS)
    private val groupPhases = FloatArray(384)
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

        val growthChanged = builtGrowthFactor < 0f || abs(targetGrowth - builtGrowthFactor) >= 0.02f
        val lightsChanged = builtLightCount != config.lightCount

        if (forceRebuild || growthChanged || lightsChanged) {
            builtGrowthFactor = targetGrowth
            builtLightCount = config.lightCount
            generateWorld(targetGrowth, config.lightCount)
        }
    }

    // -------------------------------------------------------------------------
    // Procedural World Generation (3 Depth Layers + 3D Trunks + Roots + Lights)
    // -------------------------------------------------------------------------

    private fun generateWorld(growth: Float, lightCount: Int) {
        floraNodes.clear()
        ambientLights.clear()
        groupCount = 1
        // Deterministic seed per viewport dimensions so growth smoothly expands existing flora
        val gen = Random(1337 + cols * 31 + rows * 7)

        val cx = (cols - 1) * 0.5f
        val bottomY = (rows - 1).toFloat()

        // --- A. LAYER 0: Far Atmospheric Background Vines (softer, cooler, denser at evening peak) ---
        generateFarVine(
            p0x = cx - 4f, p0y = bottomY,
            p1x = cols * 0.16f, p1y = rows * 0.72f,
            p2x = cols * 0.15f, p2y = rows * 0.24f,
            p3x = cx - 3f,
            side = -1, growth = growth, gen = gen,
        )
        generateFarVine(
            p0x = cx + 4f, p0y = bottomY,
            p1x = cols * 0.84f, p1y = rows * 0.72f,
            p2x = cols * 0.85f, p2y = rows * 0.24f,
            p3x = cx + 3f,
            side = 1, growth = growth, gen = gen,
        )
        // Secondary deep background silhouettes emerge as growth progresses past morning
        if (growth > 0.35f) {
            generateFarVine(
                p0x = cols * 0.24f, p0y = bottomY,
                p1x = cols * 0.21f, p1y = rows * 0.64f,
                p2x = cols * 0.23f, p2y = rows * 0.20f,
                p3x = cx - 8f,
                side = -1, growth = (growth - 0.2f).coerceIn(0f, 1f), gen = gen,
            )
            generateFarVine(
                p0x = cols * 0.76f, p0y = bottomY,
                p1x = cols * 0.79f, p1y = rows * 0.64f,
                p2x = cols * 0.77f, p2y = rows * 0.20f,
                p3x = cx + 8f,
                side = 1, growth = (growth - 0.2f).coerceIn(0f, 1f), gen = gen,
            )
        }

        // --- B. LAYER 1: Midground Structural Stems & Canopy Branches ---
        val rootTopY = rows * (0.85f - growth * 0.04f)
        val leftJoinX = cols * 0.10f
        val rightJoinX = cols * 0.90f

        generateMidVine(
            p0x = leftJoinX + 2.5f, p0y = rootTopY + 2f,
            p1x = cols * 0.09f, p1y = rows * 0.56f,
            p2x = cols * 0.11f, p2y = rows * 0.18f,
            p3x = cx - 5f,
            side = -1, growth = growth, gen = gen,
        )
        generateMidVine(
            p0x = rightJoinX - 2.5f, p0y = rootTopY + 2f,
            p1x = cols * 0.91f, p1y = rows * 0.56f,
            p2x = cols * 0.89f, p2y = rows * 0.18f,
            p3x = cx + 5f,
            side = 1, growth = growth, gen = gen,
        )

        // --- C. LAYER 2: Organic Tapering Roots + PCB Traces (Bottom ~18%..28% by growth) ---
        generateOrganicRoot(
            p0x = cx - 1f, p0y = bottomY,
            p1x = cx - cols * 0.17f, p1y = bottomY - rows * 0.03f,
            p2x = cols * 0.17f, p2y = bottomY - rows * 0.09f,
            p3x = leftJoinX, p3y = rootTopY,
            side = -1, growth = growth, gen = gen,
        )
        generateOrganicRoot(
            p0x = cx + 1f, p0y = bottomY,
            p1x = cx + cols * 0.17f, p1y = bottomY - rows * 0.03f,
            p2x = cols * 0.83f, p2y = bottomY - rows * 0.09f,
            p3x = rightJoinX, p3y = rootTopY,
            side = 1, growth = growth, gen = gen,
        )

        // Secondary organic creeping sub-roots that reach further along bottom/edges as growth increases
        val rootReachScale = 0.45f + growth * 0.75f
        addCreepingRootBranch(cx - 5f, bottomY - 1f, dirX = -1, steps = (14 * rootReachScale).roundToInt(), side = -1, growth = growth, gen = gen)
        addCreepingRootBranch(cx + 5f, bottomY - 1f, dirX = 1, steps = (14 * rootReachScale).roundToInt(), side = 1, growth = growth, gen = gen)
        if (growth > 0.35f) {
            addCreepingRootBranch(leftJoinX + 1f, rootTopY + 3f, dirX = 1, steps = (10 * growth).roundToInt(), side = -1, growth = growth, gen = gen)
            addCreepingRootBranch(rightJoinX - 1f, rootTopY + 3f, dirX = -1, steps = (10 * growth).roundToInt(), side = 1, growth = growth, gen = gen)
        }

        // Geometric 45-degree PCB-trace roots (Ketten Organic Hardware signature)
        val pcbScale = 0.65f + growth * 0.55f
        addPcbTraceRoot(cx - 2f, bottomY - 1f, dirX = -1, len1 = (6 * pcbScale).roundToInt(), diag = 3, len2 = (6 * pcbScale).roundToInt(), side = -1, pBase = 0.04f, gen = gen)
        addPcbTraceRoot(cx + 2f, bottomY - 1f, dirX = 1, len1 = (6 * pcbScale).roundToInt(), diag = 3, len2 = (6 * pcbScale).roundToInt(), side = 1, pBase = 0.04f, gen = gen)
        addPcbTraceRoot(cx - 8f, bottomY - 3f, dirX = -1, len1 = 3, diag = (3 + growth * 3).roundToInt(), len2 = 5, side = -1, pBase = 0.10f, gen = gen)
        addPcbTraceRoot(cx + 8f, bottomY - 3f, dirX = 1, len1 = 3, diag = (3 + growth * 3).roundToInt(), len2 = 5, side = 1, pBase = 0.10f, gen = gen)
        if (growth > 0.45f) {
            addPcbTraceRoot(cx - 3f, bottomY - 2f, dirX = -1, len1 = 2, diag = 4, len2 = 3, side = -1, pBase = 0.07f, gen = gen)
            addPcbTraceRoot(cx + 3f, bottomY - 2f, dirX = 1, len1 = 2, diag = 4, len2 = 3, side = 1, pBase = 0.07f, gen = gen)
        }

        // --- D. LAYER 2: 3D Cylindrical Foreground Framing Trunks + Layered Canopy ---
        generateForegroundTrunk3D(
            p0x = leftJoinX, p0y = rootTopY,
            p1x = 2.6f, p1y = rows * 0.60f,
            p2x = 3.0f, p2y = rows * 0.14f,
            p3x = cx - 2.0f,
            side = -1, growth = growth, gen = gen,
        )
        generateForegroundTrunk3D(
            p0x = rightJoinX, p0y = rootTopY,
            p1x = cols - 3.6f, p1y = rows * 0.60f,
            p2x = cols - 4.0f, p2y = rows * 0.14f,
            p3x = cx + 2.0f,
            side = 1, growth = growth, gen = gen,
        )

        // --- E. Hanging Canopy Tendrils at Top Arch (count & length scale with daily growth) ---
        val hangPositions = if (growth > 0.60f) {
            floatArrayOf(0.18f, 0.28f, 0.39f, 0.61f, 0.72f, 0.82f)
        } else if (growth > 0.32f) {
            floatArrayOf(0.24f, 0.37f, 0.63f, 0.76f)
        } else {
            floatArrayOf(0.30f, 0.70f)
        }
        for (hp in hangPositions) {
            val hx = cols * hp
            val topStartY = if (hp in 0.32f..0.68f) 3.4f else 4.8f
            val length = (3 + (growth * 5.5f).roundToInt() + gen.nextInt(2)).coerceIn(3, 10)
            val gid = allocGroup(gen)
            val side = if (hp < 0.5f) -1 else 1
            for (step in 0 until length) {
                val hy = topStartY + step
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

        // --- F. Ambient Warm & Spectral Bloom Lights (0 .. 24 controlled by settings slider) ---
        generateAmbientLights(lightCount, growth, gen)
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
        val p3y = 3.5f
        var lastX = -999
        var lastY = -999
        var step = 0
        var t = 0f
        val maxT = 0.65f + growth * 0.35f
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
                // Soft background canopy flecks appear as evening lushness grows
                if (growth > 0.45f && step % 5 == 0 && (iy < rows * 0.28f || iy > rows * 0.72f)) {
                    floraNodes.add(
                        FloraNode(
                            x = (ix - side * (1 + gen.nextInt(3))).toFloat(),
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
        val p3y = 3.0f
        var lastX = -999
        var lastY = -999
        var step = 0
        var t = 0f
        val maxT = 0.55f + growth * 0.45f
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
                    if (lx in 2 until cols - 2 && (inTopOrBottom || lx < cols * 0.22f || lx > cols * 0.78f)) {
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

    private fun generateOrganicRoot(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float, p3y: Float,
        side: Int,
        growth: Float,
        gen: Random,
    ) {
        var lastX = -999
        var lastY = -999
        var t = 0f
        while (t <= 1f) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            if (ix != lastX || iy != lastY) {
                val ch = if (lastX == -999) '_' else dirChar(ix - lastX, iy - lastY)
                val prog = 0.22f * t
                // Tapering thickness: thicker where root joins trunk (t -> 1) and thicker with evening growth
                val thickness = (1 + ((t * 0.7f + 0.3f) * growth * 1.8f).roundToInt()).coerceIn(1, 3)
                for (w in 0 until thickness) {
                    val shade = if (thickness == 1) 0.4f else ((w.toFloat() / (thickness - 1)) * 2f - 1f)
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
                            flex = 0.14f + w * 0.08f,
                            shade = shade,
                        )
                    )
                }

                // Organic hanging root tendrils scaling with growth
                val hairChance = 0.80f - growth * 0.30f
                if (gen.nextFloat() > hairChance && iy < rows - 2) {
                    val hairLen = 1 + gen.nextInt(1 + (growth * 3f).roundToInt())
                    val gid = allocGroup(gen)
                    for (h in 1..hairLen) {
                        floraNodes.add(
                            FloraNode(
                                x = (ix + (if (h % 2 == 0) side else 0)).toFloat(),
                                y = (iy + h).coerceAtMost(rows - 1).toFloat(),
                                ch = if (h == hairLen) '.' else if (side < 0) '/' else '\\',
                                kind = NodeKind.ROOT_ORGANIC,
                                side = side,
                                progress = prog + 0.01f * h,
                                groupId = gid,
                                phase = gen.nextFloat() * 6.28f,
                                flex = 0.22f + h * 0.12f,
                                shade = -0.3f,
                            )
                        )
                    }
                }
                lastX = ix
                lastY = iy
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
        growth: Float,
        gen: Random,
    ) {
        var rx = startX
        var ry = startY
        val gid = allocGroup(gen)
        val total = steps.coerceAtLeast(1)
        for (i in 0 until steps) {
            val frac = i.toFloat() / total
            rx += dirX * (0.85f + gen.nextFloat() * 0.35f)
            ry -= (0.28f + sin(frac * PI.toFloat()) * 0.35f)
            val ix = rx.roundToInt()
            val iy = ry.roundToInt()
            if (ix !in 1 until cols - 1 || iy !in (rows * 0.72f).toInt() until rows) break

            val ch = when {
                i == steps - 1 -> '.'
                i % 3 == 0 -> if (dirX > 0) '/' else '\\'
                else -> '~'
            }
            floraNodes.add(
                FloraNode(
                    x = ix.toFloat(), y = iy.toFloat(), ch = ch,
                    kind = NodeKind.ROOT_ORGANIC, side = side,
                    progress = 0.05f + frac * 0.16f,
                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                    flex = 0.15f + frac * 0.35f,
                    shade = 0.4f - frac * 0.9f,
                )
            )
            // Tapering thickness at base of creeping root
            if (frac < 0.4f && growth > 0.45f && iy + 1 < rows) {
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
        gen: Random,
    ) {
        var cx = startX.roundToInt()
        var cy = startY.roundToInt()
        var stepIdx = 0
        val total = (len1 + diag + len2).coerceAtLeast(1)

        repeat(len1) {
            cx += dirX
            stepIdx++
            if (cx in 1 until cols - 1 && cy in 1 until rows) {
                floraNodes.add(
                    FloraNode(
                        x = cx.toFloat(), y = cy.toFloat(),
                        ch = '_', kind = NodeKind.ROOT_PCB, side = side,
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
            if (cx in 1 until cols - 1 && cy in (rows * 0.72f).toInt() until rows) {
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
            if (cx in 1 until cols - 1 && cy in (rows * 0.72f).toInt() until rows) {
                val isPad = (i == len2 - 1)
                floraNodes.add(
                    FloraNode(
                        x = cx.toFloat(), y = cy.toFloat(),
                        ch = if (isPad) 'o' else '-',
                        kind = if (isPad) NodeKind.ROOT_PAD else NodeKind.ROOT_PCB,
                        side = side,
                        progress = pBase + 0.12f * (stepIdx.toFloat() / total),
                        groupId = 0, phase = gen.nextFloat() * 6.28f, flex = 0.0f,
                    )
                )
            }
        }
    }

    /**
     * Generates a volumetric 3D foreground trunk with cylindrical cross-section shading,
     * organic curved bark nerves following trunk curvature, and growth-responsive canopy branches.
     */
    private fun generateForegroundTrunk3D(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float,
        side: Int,
        growth: Float,
        gen: Random,
    ) {
        val p3y = 2.6f
        var lastX = -999
        var lastY = -999
        var stepCounter = 0
        var t = 0f

        // Base trunk width scales clearly from morning (2 cells) to evening peak (4-5 cells)!
        val baseThickness = 1.9f + growth * 2.7f

        while (t <= 1f) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()

            if (ix != lastX || iy != lastY) {
                val dx = if (lastX == -999) 0 else ix - lastX
                val dy = if (lastY == -999) -1 else iy - lastY
                val prog = 0.22f + 0.78f * t

                // Smooth natural taper from strong thick lower trunk to slender crown arch
                val taper = (1f - t * 0.68f).coerceIn(0.32f, 1f)
                val colWidth = (baseThickness * taper).roundToInt().coerceIn(1, 5)
                val inwardDir = -side // +1 for left trunk (toward center), -1 for right trunk

                // Populate cylindrical cross-section cells from outer shadow edge (-1) to inner warm rim (+1)
                for (w in 0 until colWidth) {
                    val u = if (colWidth == 1) 0.5f else (w.toFloat() / (colWidth - 1).toFloat()) // 0=outer shadow, 1=inner warm rim
                    val cylShade = u * 2f - 1f // -1.0 .. +1.0
                    val tx = (ix + inwardDir * (w - colWidth / 2)).coerceIn(0, cols - 1)

                    // Organic bark grain/nerves curving gently along the trunk's cylindrical contour
                    val barkWave = sin(t * 28f + w * 1.45f + side)
                    val isBarkNerve = colWidth >= 3 && w in 1 until (colWidth - 1) && abs(barkWave) > 0.68f

                    val ch = when {
                        colWidth == 1 -> dirChar(dx, dy)
                        w == 0 -> if (side < 0) '(' else ')'                       // Outer shaded cylindrical edge
                        w == colWidth - 1 -> if (side < 0) ')' else '('            // Inner light-catching rim edge
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

                // Intertwining organic tendril hugging the inner side of the trunk
                if (stepCounter % 3 == 0 && growth > 0.25f) {
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

                // Growth-driven branch & layered canopy generation
                val branchSpacing = (7 - (growth * 4f).roundToInt()).coerceIn(3, 7)
                if (stepCounter > 2 && stepCounter % branchSpacing == 0 && iy > 2 && iy < rows - 4) {
                    val inTopOrBottom = iy < rows * 0.25f || iy > rows * 0.72f
                    val baseLen = if (inTopOrBottom) {
                        (2 + growth * 5.2f).roundToInt()
                    } else {
                        (1 + growth * 3.5f).roundToInt()
                    }
                    val branchLen = (baseLen + gen.nextInt(2)).coerceIn(1, 8)
                    val gid = allocGroup(gen)
                    var bx = ix + inwardDir * (colWidth / 2)
                    var by = iy

                    for (b in 1..branchLen) {
                        bx += inwardDir
                        if (b % 2 == 0) by -= 1
                        // Keep central logo & app-icon sanctuary calm and legible
                        if (!inTopOrBottom && bx > cols * 0.24f && bx < cols * 0.76f) break
                        if (bx !in 1 until cols - 1 || by !in 1 until rows - 2) break

                        val bChar = if (inwardDir > 0) {
                            if (b % 2 == 0) '/' else '_'
                        } else {
                            if (b % 2 == 0) '\\' else '_'
                        }
                        val bFlex = 0.35f + (b.toFloat() / branchLen) * 0.95f
                        val bProg = (prog + b * 0.012f).coerceAtMost(1f)

                        floraNodes.add(
                            FloraNode(
                                x = bx.toFloat(), y = by.toFloat(), ch = bChar,
                                kind = NodeKind.BRANCH, side = side, progress = bProg,
                                groupId = gid, phase = gen.nextFloat() * 6.28f, flex = bFlex,
                            )
                        )

                        // Layered foliage cluster (density increases smoothly toward evening peak)
                        val leafThreshold = 0.62f - growth * 0.48f
                        if (by > 1 && gen.nextFloat() > leafThreshold) {
                            floraNodes.add(
                                FloraNode(
                                    x = bx.toFloat(), y = (by - 1).toFloat(),
                                    ch = if (inwardDir > 0) '/' else '\\',
                                    kind = NodeKind.LEAF, side = side, progress = bProg,
                                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                                    flex = bFlex * 1.15f, variant = gen.nextInt(3),
                                )
                            )
                        }
                        if (b >= 2 && by < rows - 2 && gen.nextFloat() > (leafThreshold + 0.18f)) {
                            floraNodes.add(
                                FloraNode(
                                    x = (bx - inwardDir).toFloat(), y = (by + 1).toFloat(),
                                    ch = '~',
                                    kind = NodeKind.LEAF, side = side, progress = bProg,
                                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                                    flex = bFlex * 1.10f, variant = gen.nextInt(3),
                                )
                            )
                        }
                        if (inTopOrBottom && growth > 0.55f && by > 2 && gen.nextFloat() > 0.55f) {
                            floraNodes.add(
                                FloraNode(
                                    x = (bx + inwardDir).coerceIn(1, cols - 2).toFloat(),
                                    y = (by - 1).toFloat(),
                                    ch = '\'',
                                    kind = NodeKind.LEAF, side = side, progress = bProg,
                                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                                    flex = bFlex * 1.25f, variant = 2,
                                )
                            )
                        }

                        if (b == branchLen) {
                            val tipX = (bx + inwardDir).coerceIn(1, cols - 2)
                            floraNodes.add(
                                FloraNode(
                                    x = tipX.toFloat(), y = by.toFloat(), ch = '¤',
                                    kind = NodeKind.BUD, side = side,
                                    progress = (bProg + 0.015f).coerceAtMost(1f),
                                    groupId = gid, phase = gen.nextFloat() * 6.28f,
                                    flex = bFlex * 1.25f,
                                )
                            )
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
     * Generates up to [count] warm & spectral blooming lights organically distributed
     * along the framing trunks, canopy arch, and roots so they cast warm rim-light onto bark & leaves.
     */
    private fun generateAmbientLights(count: Int, growth: Float, gen: Random) {
        if (count <= 0) return
        val clamped = count.coerceIn(0, WallpaperPrefs.MAX_LIGHTS)

        for (i in 0 until clamped) {
            val side = if (i % 2 == 0) -1 else 1
            val zone = i % 5
            val bx: Float
            val by: Float
            when (zone) {
                0 -> { // Upper canopy arch
                    bx = if (side < 0) cols * (0.14f + gen.nextFloat() * 0.24f) else cols * (0.62f + gen.nextFloat() * 0.24f)
                    by = rows * (0.06f + gen.nextFloat() * 0.15f)
                }
                1, 2 -> { // Along inner side of left/right 3D trunks (illuminates trunk rim!)
                    bx = if (side < 0) cols * (0.10f + gen.nextFloat() * 0.14f) else cols * (0.76f + gen.nextFloat() * 0.14f)
                    by = rows * (0.22f + gen.nextFloat() * 0.50f)
                }
                3 -> { // Lower root / PCB nexus zone
                    bx = if (side < 0) cols * (0.12f + gen.nextFloat() * 0.26f) else cols * (0.62f + gen.nextFloat() * 0.26f)
                    by = rows * (0.76f + gen.nextFloat() * 0.18f)
                }
                else -> { // Mid-high crown accents
                    bx = if (side < 0) cols * (0.16f + gen.nextFloat() * 0.16f) else cols * (0.68f + gen.nextFloat() * 0.16f)
                    by = rows * (0.16f + gen.nextFloat() * 0.20f)
                }
            }
            val radius = (2.6f + gen.nextFloat() * 2.1f) * (0.85f + growth * 0.25f)
            ambientLights.add(
                AmbientLight(
                    baseX = bx.coerceIn(2f, cols - 3f),
                    baseY = by.coerceIn(2f, rows - 3f),
                    phase = gen.nextFloat() * (2f * PI.toFloat()),
                    radiusCells = radius,
                    warmWeight = if (i % 3 == 0) 0.25f else 0.85f, // Mix of warm golden lamps & Ketten spectral lights
                    depthLayer = if (i % 4 == 0) 1 else 2,
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
            if (windActive && n.flex > 0.08f) {
                val windPhase = if (n.groupId > 0) groupPhases[n.groupId] else n.phase
                if (n.kind == NodeKind.ROOT_ORGANIC) {
                    // Slow subtle organic root breathing
                    val breath = sin(animElapsed * 0.75f + n.phase + n.x * 0.12f)
                    cy += breath * n.flex * 0.45f
                } else {
                    val breeze = sin(animElapsed * 1.15f + windPhase + n.y * 0.08f)
                    cx += breeze * n.flex * 0.85f
                    if (breeze > 0.55f && n.kind != NodeKind.CANOPY_HANG) {
                        cy -= (breeze - 0.55f) * n.flex * 0.65f
                    }
                }
            }

            // Touch wind ripples
            var touchBoost = 0f
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
                NodeKind.FAR_VINE, NodeKind.FAR_LEAF -> {
                    col = colors.farVine
                }
                NodeKind.MID_STALK -> {
                    col = if (pulseHit > 0.2f) {
                        SpectralRecipe.lerpColor(colors.midStalk, colors.branch, pulseHit * 0.6f)
                    } else {
                        colors.midStalk
                    }
                }
                NodeKind.MID_LEAF -> {
                    col = SpectralRecipe.lerpColor(colors.midStalk, colors.leafDark, 0.7f)
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
