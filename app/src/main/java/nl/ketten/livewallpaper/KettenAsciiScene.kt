package nl.ketten.livewallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
 * Native Android Canvas ASCII engine for Ketten "Organic Hardware" Live Wallpaper.
 * Optimized for tall smartphone screens with:
 *  - Quiet framed center for app icons & a refined 3D sub-pixel swaying KETTEN logo
 *  - Live clock & date readout beneath the ORGANIC // HARDWARE signature
 *  - Bottom PCB-mycelium root system anchored above the USB-C port
 *  - Layered framing vines, botanical leaf clusters, hanging canopy tendrils & blooming spectral buds
 *  - Interactive touch wind ripples, spore bursts, bio-energy pulses, swipe parallax,
 *    24-hour spectral recipe, and battery/charging visualization
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
    private var glowIndices = IntArray(512)
    private var glowCount = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val singleCharBuf = CharArray(1)
    private val colors = SpectralColors()

    // Cached clock/date formatting (updated only once per second, zero per-frame allocations)
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.forLanguageTag("nl-NL"))
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("nl-NL"))
    private val reusableDate = Date()
    private var lastClockCheckMs: Long = 0L
    private var cachedTimeText: String = "00:00"
    private var cachedDateText: String = ""

    // External dynamic state
    var spectrumMode: SpectrumMode = SpectrumMode.AUTO
        set(value) {
            if (field != value) {
                field = value
                paletteDirty = true
            }
        }
    private var paletteDirty: Boolean = true
    private var batteryLevel: Float = 0.85f
    private var isCharging: Boolean = false
    private var targetSwipeNorm: Float = 0f // -1f..+1f
    private var smoothSwipeNorm: Float = 0f
    private var targetGyroX: Float = 0f     // -1f..+1f
    private var targetGyroY: Float = 0f     // -1f..+1f
    private var smoothGyroX: Float = 0f
    private var smoothGyroY: Float = 0f

    // Time & animation state
    private var elapsed: Float = 0f
    private var paletteRefreshAccum: Float = 0f
    private var chargePulseTimer: Float = 0f
    private var passivePulseTimer: Float = 1.5f
    private var lastDragTouchTime: Float = 0f
    private var logoRippleTime: Float = -10f
    private var logoRippleCx: Float = 0.5f
    private var logoRippleCy: Float = 0.5f
    private var logoTiltVelX: Float = 0f
    private var logoTiltX: Float = 0f

    // Data structures for procedural organic-hardware world
    private enum class NodeKind {
        FAR_VINE,
        ROOT_ORGANIC,
        ROOT_PCB,
        ROOT_PAD,
        STALK,
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
        val side: Int,         // -1 = left, +1 = right, 0 = center/both
        val progress: Float,   // 0.0 (bottom USB-C root nexus) .. 1.0 (top canopy crown)
        val groupId: Int,
        val phase: Float,
        val flex: Float,       // wind & touch flexibility
    )

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
        var side = 0           // -1 left, +1 right, 0 both
        var intensity = 1f
        var fromCharge = false
    }

    private val floraNodes = ArrayList<FloraNode>(900)
    private val groupPhases = FloatArray(256)
    private var groupCount = 1

    private val spores = Array(56) { Spore() }
    private val ripples = Array(6) { Ripple() }
    private val pulses = Array(10) { EnergyPulse() }

    private val animRng = Random(System.currentTimeMillis())

    // Refined 3D KETTEN Logo mask: 32 columns x 5 rows (~57% screen width)
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
        SpectralRecipe.evaluate(spectrumMode, colors)
        paletteDirty = false
        refreshClockText(force = true)
        resize(viewWidth, viewHeight)
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height

        // Portrait phone gets crisp 56-column resolution; landscape scales wider cleanly.
        cols = if (height >= width) {
            56
        } else {
            (56f * (width.toFloat() / height.toFloat())).roundToInt().coerceIn(56, 92)
        }

        // Measure monospace character aspect ratio accurately
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

        generateWorld()
        seedAmbientSpores()
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
        // xOffset is 0..1; map to -1..+1 around center
        targetSwipeNorm = ((xOffset.coerceIn(0f, 1f) - 0.5f) * 2f)
    }

    fun setGyroTilt(tiltX: Float, tiltY: Float) {
        targetGyroX = tiltX.coerceIn(-1f, 1f)
        targetGyroY = tiltY.coerceIn(-1f, 1f)
    }

    fun onTouch(px: Float, py: Float, isMove: Boolean) {
        val gx = (px / cellW).coerceIn(0f, (cols - 1).toFloat())
        val gy = ((py - offsetY) / cellH).coerceIn(0f, (rows - 1).toFloat())

        if (isMove) {
            if (elapsed - lastDragTouchTime < 0.085f) return
            lastDragTouchTime = elapsed
            spawnRipple(gx, gy, maxRadius = 11f, strength = 0.65f)
            spawnSporeBurst(gx, gy, count = 2)
            // Subtle tilt impulse on horizontal drag
            val centerDx = (gx / cols - 0.5f)
            logoTiltVelX += centerDx * 1.4f
        } else {
            spawnRipple(gx, gy, maxRadius = 18f, strength = 1.25f)
            spawnSporeBurst(gx, gy, count = 9)

            // Check if tapped near the 3D KETTEN logo
            val logoStartX = (cols - logoW) * 0.5f
            val logoStartY = rows * 0.42f
            val hitLogo = gx >= logoStartX - 3f && gx <= logoStartX + logoW + 3f &&
                gy >= logoStartY - 3f && gy <= logoStartY + logoH + 5f

            if (hitLogo) {
                logoRippleTime = elapsed
                logoRippleCx = gx
                logoRippleCy = gy
                logoTiltVelX += if (gx < cols * 0.5f) 4.0f else -4.0f
                spawnEnergyPulse(side = 0, speed = 0.52f, intensity = 1.15f, fromCharge = false)
            } else if (gy > rows * 0.74f) {
                // Tapped in the PCB root nexus zone -> fire dual surge up both stems!
                spawnEnergyPulse(side = 0, speed = 0.60f, intensity = 1.35f, fromCharge = true)
            } else {
                // Fire a bio-pulse up the nearer side
                val side = if (gx < cols * 0.5f) -1 else 1
                spawnEnergyPulse(side = side, speed = 0.48f, intensity = 1.1f, fromCharge = false)
            }
        }
    }

    fun triggerDemoPulse() {
        spawnEnergyPulse(side = 0, speed = 0.55f, intensity = 1.35f, fromCharge = true)
        spawnSporeBurst(cols * 0.5f, rows - 2f, count = 8)
    }

    fun update(dt: Float) {
        val step = dt.coerceIn(0.001f, 0.05f)
        elapsed += step

        // Refresh 24h spectral recipe only on mode change or every 10s (saves Calendar allocs)
        paletteRefreshAccum += step
        if (paletteDirty || paletteRefreshAccum >= 10f) {
            paletteRefreshAccum = 0f
            paletteDirty = false
            SpectralRecipe.evaluate(spectrumMode, colors)
        }

        // Refresh clock & date once per second
        refreshClockText(force = false)

        // Smooth swipe & gyro parallax
        smoothSwipeNorm += (targetSwipeNorm - smoothSwipeNorm) * min(1f, step * 7.5f)
        smoothGyroX += (targetGyroX - smoothGyroX) * min(1f, step * 9.0f)
        smoothGyroY += (targetGyroY - smoothGyroY) * min(1f, step * 9.0f)

        // Spring physics for interactive 3D logo tilt
        val springForce = -logoTiltX * 16f - logoTiltVelX * 4.2f
        logoTiltVelX += springForce * step
        logoTiltX = (logoTiltX + logoTiltVelX * step).coerceIn(-1.5f, 1.5f)

        // Charging energy pulses from USB-C root nexus
        if (isCharging) {
            chargePulseTimer += step
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

        // Calm passive bio-heartbeat pulses even on battery
        passivePulseTimer += step
        val passiveInterval = 6.5f - batteryLevel * 2.2f
        if (passivePulseTimer >= passiveInterval) {
            passivePulseTimer = 0f
            val side = if (animRng.nextBoolean()) -1 else 1
            spawnEnergyPulse(side = side, speed = 0.30f, intensity = 0.85f, fromCharge = false)
        }

        // Update ripples
        for (r in ripples) {
            if (!r.active) continue
            r.radius += step * 24f
            r.strength *= (1f - step * 1.45f).coerceAtLeast(0f)
            if (r.radius >= r.maxRadius || r.strength <= 0.03f) {
                r.active = false
            }
        }

        // Update energy pulses
        for (p in pulses) {
            if (!p.active) continue
            p.progress += step * p.speed
            if (p.progress > 1.18f) {
                p.active = false
            }
        }

        // Update spores
        var activeAmbient = 0
        for (s in spores) {
            if (!s.active) continue
            s.life += step
            if (s.life >= s.maxLife || s.y < -2f || s.x < -2f || s.x > cols + 2f) {
                s.active = false
                continue
            }
            if (!s.bright) activeAmbient++
            val sway = sin(elapsed * 1.3f + s.phase) * 1.6f + smoothGyroX * 1.4f
            s.x += (s.vx + sway) * step
            s.y += s.vy * step
            s.vx *= (1f - step * 1.6f).coerceAtLeast(0f)
            if (s.vy > -1.2f) {
                s.vy -= step * 1.8f
            }
            for (r in ripples) {
                if (!r.active) continue
                val dx = s.x - r.x
                val dy = s.y - r.y
                val d = hypot(dx, dy)
                if (abs(d - r.radius) < 3.2f && d > 0.1f) {
                    val push = r.strength * 8f * step
                    s.vx += (dx / d) * push
                    s.vy += (dy / d) * push
                }
            }
        }
        if (activeAmbient < 16 && animRng.nextFloat() < step * 4.5f) {
            spawnAmbientSpore()
        }
    }

    fun draw(canvas: Canvas) {
        if (paletteDirty) {
            paletteDirty = false
            SpectralRecipe.evaluate(spectrumMode, colors)
        }

        // 1. Clear ASCII buffer & fast-path trackers
        gridChars.fill(' ')
        gridColors.fill(0)
        gridGlow.fill(0)
        rowHasContent.fill(false)
        glowCount = 0

        // 2. Render grid layers from back to front (clean, calm center — no clutter logs)
        drawFloraLayer(farOnly = true)
        drawFloraLayer(farOnly = false)
        drawUsbNexusCore()
        drawLogoReadout()
        drawSpores()

        // 3. Draw AMOLED pure black background
        canvas.drawColor(colors.bg)

        // 4. First pass: glow halos only for active glowing cells (O(glowCount) instead of O(rows*cols))
        val glowRadius = cellH * 0.72f
        for (i in 0 until glowCount) {
            val idx = glowIndices[i]
            val g = gridGlow[idx].toInt()
            if (g > 0) {
                val row = idx / cols
                val col = idx - row * cols
                val cx = col * cellW + cellW * 0.5f
                val cy = offsetY + row * cellH + cellH * 0.5f
                val alpha = if (g >= 2) 64 else 34
                glowPaint.color = SpectralRecipe.withAlpha(gridColors[idx], alpha)
                canvas.drawCircle(cx, cy, if (g >= 2) glowRadius * 1.15f else glowRadius * 0.85f, glowPaint)
            }
        }

        // 5. Second pass: crisp per-cell monospace characters (skipping completely empty rows)
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

        // 6. Third pass: sub-pixel smooth 3D swaying KETTEN logo (no jumpy integer snapping!)
        drawLogo3DDirect(canvas)
    }

    // -------------------------------------------------------------------------
    // Procedural World Generation (Roots + PCB Traces + Framing Vines + Canopy)
    // -------------------------------------------------------------------------

    private fun generateWorld() {
        floraNodes.clear()
        groupCount = 1
        val gen = Random(1337 + cols * 31 + rows * 7)

        val cx = (cols - 1) * 0.5f
        val bottomY = (rows - 1).toFloat()

        // --- A. Far Background Depth Vines (Subtle Parallax Layer) ---
        generateFarVine(
            p0x = cx - 4f, p0y = bottomY,
            p1x = cols * 0.14f, p1y = rows * 0.72f,
            p2x = cols * 0.13f, p2y = rows * 0.24f,
            p3x = cx - 3f,
            side = -1, gen = gen,
        )
        generateFarVine(
            p0x = cx + 4f, p0y = bottomY,
            p1x = cols * 0.86f, p1y = rows * 0.72f,
            p2x = cols * 0.87f, p2y = rows * 0.24f,
            p3x = cx + 3f,
            side = 1, gen = gen,
        )

        // --- B. Bottom PCB-Mycelium Root System (Bottom ~22% of screen) ---
        val rootTopY = rows * 0.83f
        val leftJoinX = cols * 0.10f
        val rightJoinX = cols * 0.90f

        generateRootCurve(
            p0x = cx - 1f, p0y = bottomY,
            p1x = cx - cols * 0.16f, p1y = bottomY - rows * 0.04f,
            p2x = cols * 0.18f, p2y = bottomY - rows * 0.10f,
            p3x = leftJoinX, p3y = rootTopY,
            side = -1, gen = gen,
        )
        generateRootCurve(
            p0x = cx + 1f, p0y = bottomY,
            p1x = cx + cols * 0.16f, p1y = bottomY - rows * 0.04f,
            p2x = cols * 0.82f, p2y = bottomY - rows * 0.10f,
            p3x = rightJoinX, p3y = rootTopY,
            side = 1, gen = gen,
        )

        // Geometric 45-degree PCB-trace sub-roots branching across the bottom dock area
        addPcbTraceRoot(cx - 2f, bottomY - 1f, dirX = -1, len1 = 7, diag = 4, len2 = 8, side = -1, pBase = 0.04f, gen = gen)
        addPcbTraceRoot(cx + 2f, bottomY - 1f, dirX = 1, len1 = 7, diag = 4, len2 = 8, side = 1, pBase = 0.04f, gen = gen)
        addPcbTraceRoot(cx - 8f, bottomY - 3f, dirX = -1, len1 = 4, diag = 5, len2 = 6, side = -1, pBase = 0.10f, gen = gen)
        addPcbTraceRoot(cx + 8f, bottomY - 3f, dirX = 1, len1 = 4, diag = 5, len2 = 6, side = 1, pBase = 0.10f, gen = gen)
        addPcbTraceRoot(cx - 3f, bottomY - 2f, dirX = -1, len1 = 2, diag = 4, len2 = 3, side = -1, pBase = 0.07f, gen = gen)
        addPcbTraceRoot(cx + 3f, bottomY - 2f, dirX = 1, len1 = 2, diag = 4, len2 = 3, side = 1, pBase = 0.07f, gen = gen)
        addPcbTraceRoot(cx - 12f, bottomY - 1f, dirX = -1, len1 = 6, diag = 2, len2 = 5, side = -1, pBase = 0.13f, gen = gen)
        addPcbTraceRoot(cx + 12f, bottomY - 1f, dirX = 1, len1 = 6, diag = 2, len2 = 5, side = 1, pBase = 0.13f, gen = gen)

        // --- C. Primary Framing Left & Right Vines + Cathedral Canopy Arch ---
        generatePrimaryVine(
            p0x = leftJoinX, p0y = rootTopY,
            p1x = 1.8f, p1y = rows * 0.60f,
            p2x = 2.2f, p2y = rows * 0.14f,
            p3x = cx - 2.0f,
            side = -1, gen = gen,
        )
        generatePrimaryVine(
            p0x = rightJoinX, p0y = rootTopY,
            p1x = cols - 2.8f, p1y = rows * 0.60f,
            p2x = cols - 3.2f, p2y = rows * 0.14f,
            p3x = cx + 2.0f,
            side = 1, gen = gen,
        )

        // --- D. Delicate Hanging Canopy Tendrils at Top Arch ---
        val hangPositions = floatArrayOf(0.24f, 0.36f, 0.64f, 0.76f)
        for (hp in hangPositions) {
            val hx = cols * hp
            val topStartY = if (hp in 0.32f..0.68f) 3.5f else 5.0f
            val length = 4 + gen.nextInt(5)
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
                        x = hx,
                        y = hy,
                        ch = ch,
                        kind = kind,
                        side = side,
                        progress = 0.94f + (step.toFloat() / length) * 0.06f,
                        groupId = gid,
                        phase = gen.nextFloat() * (2f * PI.toFloat()),
                        flex = 0.35f + (step.toFloat() / length) * 1.15f,
                    )
                )
            }
        }
    }

    private fun generateFarVine(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float,
        side: Int,
        gen: Random,
    ) {
        val p3y = 3.5f
        var lastX = -999
        var lastY = -999
        var t = 0f
        while (t <= 1f) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            if (ix != lastX || iy != lastY) {
                val ch = if (lastX == -999) '|' else dirChar(ix - lastX, iy - lastY)
                floraNodes.add(
                    FloraNode(
                        x = ix.toFloat(),
                        y = iy.toFloat(),
                        ch = ch,
                        kind = NodeKind.FAR_VINE,
                        side = side,
                        progress = t,
                        groupId = 0,
                        phase = gen.nextFloat() * 6.28f,
                        flex = 0.2f,
                    )
                )
                lastX = ix
                lastY = iy
            }
            t += 0.006f
        }
    }

    private fun generateRootCurve(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float, p3y: Float,
        side: Int,
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
                floraNodes.add(
                    FloraNode(
                        x = ix.toFloat(),
                        y = iy.toFloat(),
                        ch = ch,
                        kind = NodeKind.ROOT_ORGANIC,
                        side = side,
                        progress = prog,
                        groupId = 0,
                        phase = gen.nextFloat() * 6.28f,
                        flex = 0.08f,
                    )
                )
                if (gen.nextFloat() > 0.72f && iy < rows - 2) {
                    val hairLen = 1 + gen.nextInt(3)
                    for (h in 1..hairLen) {
                        floraNodes.add(
                            FloraNode(
                                x = (ix + (if (h % 2 == 0) side else 0)).toFloat(),
                                y = (iy + h).toFloat(),
                                ch = if (h == hairLen) '.' else if (side < 0) '/' else '\\',
                                kind = NodeKind.ROOT_ORGANIC,
                                side = side,
                                progress = prog + 0.01f * h,
                                groupId = 0,
                                phase = gen.nextFloat() * 6.28f,
                                flex = 0.15f,
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
            if (cx in 1 until cols - 1 && cy in (rows * 0.74f).toInt() until rows) {
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
            if (cx in 1 until cols - 1 && cy in (rows * 0.74f).toInt() until rows) {
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

    private fun generatePrimaryVine(
        p0x: Float, p0y: Float,
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float,
        side: Int,
        gen: Random,
    ) {
        val p3y = 2.6f
        var lastX = -999
        var lastY = -999
        var stepCounter = 0
        var t = 0f

        while (t <= 1f) {
            val x = bezier(t, p0x, p1x, p2x, p3x)
            val y = bezier(t, p0y, p1y, p2y, p3y)
            val ix = x.roundToInt()
            val iy = y.roundToInt()

            if (ix != lastX || iy != lastY) {
                val ch = if (lastX == -999) '|' else dirChar(ix - lastX, iy - lastY)
                val prog = 0.22f + 0.78f * t

                floraNodes.add(
                    FloraNode(
                        x = ix.toFloat(),
                        y = iy.toFloat(),
                        ch = ch,
                        kind = NodeKind.STALK,
                        side = side,
                        progress = prog,
                        groupId = 0,
                        phase = gen.nextFloat() * 6.28f,
                        flex = 0.18f,
                    )
                )

                if (stepCounter % 2 == 0) {
                    val wrapOffset = sin(t * 22f + side).roundToInt()
                    val tx = (ix + wrapOffset).coerceIn(1, cols - 2)
                    val tChar = when {
                        wrapOffset > 0 -> ')'
                        wrapOffset < 0 -> '('
                        else -> '~'
                    }
                    floraNodes.add(
                        FloraNode(
                            x = tx.toFloat(),
                            y = iy.toFloat(),
                            ch = tChar,
                            kind = NodeKind.TENDRIL,
                            side = side,
                            progress = prog,
                            groupId = 0,
                            phase = gen.nextFloat() * 6.28f,
                            flex = 0.28f,
                        )
                    )
                }

                if (stepCounter > 3 && stepCounter % 4 == 0 && iy > 2 && iy < rows - 4) {
                    val inTopOrBottom = iy < rows * 0.22f || iy > rows * 0.72f
                    val maxLen = if (inTopOrBottom) 6 else 4
                    val branchLen = 2 + gen.nextInt(maxLen - 1)
                    val inwardDir = -side
                    val bDir = if (inTopOrBottom) {
                        inwardDir
                    } else {
                        if (gen.nextFloat() > 0.25f) inwardDir else side
                    }
                    val gid = allocGroup(gen)
                    var bx = ix
                    var by = iy

                    for (b in 1..branchLen) {
                        bx += bDir
                        if (b % 2 == 0) by -= 1
                        if (!inTopOrBottom && bx > cols * 0.21f && bx < cols * 0.79f) break
                        if (bx !in 1 until cols - 1 || by !in 1 until rows - 2) break

                        val bChar = if (bDir > 0) {
                            if (b % 2 == 0) '/' else '_'
                        } else {
                            if (b % 2 == 0) '\\' else '_'
                        }
                        val bFlex = 0.35f + (b.toFloat() / branchLen) * 0.95f
                        val bProg = (prog + b * 0.012f).coerceAtMost(1f)

                        floraNodes.add(
                            FloraNode(
                                x = bx.toFloat(), y = by.toFloat(),
                                ch = bChar, kind = NodeKind.BRANCH, side = side,
                                progress = bProg, groupId = gid,
                                phase = gen.nextFloat() * 6.28f, flex = bFlex,
                            )
                        )

                        if (by > 1 && gen.nextFloat() > 0.22f) {
                            floraNodes.add(
                                FloraNode(
                                    x = bx.toFloat(), y = (by - 1).toFloat(),
                                    ch = if (bDir > 0) '/' else '\\',
                                    kind = NodeKind.LEAF, side = side,
                                    progress = bProg, groupId = gid,
                                    phase = gen.nextFloat() * 6.28f, flex = bFlex * 1.15f,
                                )
                            )
                        }
                        if (b >= 2 && by < rows - 2 && gen.nextFloat() > 0.55f) {
                            floraNodes.add(
                                FloraNode(
                                    x = (bx - bDir).toFloat(), y = (by + 1).toFloat(),
                                    ch = '~',
                                    kind = NodeKind.LEAF, side = side,
                                    progress = bProg, groupId = gid,
                                    phase = gen.nextFloat() * 6.28f, flex = bFlex * 1.1f,
                                )
                            )
                        }

                        if (b == branchLen) {
                            val tipX = (bx + bDir).coerceIn(1, cols - 2)
                            floraNodes.add(
                                FloraNode(
                                    x = tipX.toFloat(), y = by.toFloat(),
                                    ch = '¤', kind = NodeKind.BUD, side = side,
                                    progress = (bProg + 0.015f).coerceAtMost(1f),
                                    groupId = gid,
                                    phase = gen.nextFloat() * 6.28f, flex = bFlex * 1.25f,
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
    // Per-Frame Drawing Passes into the ASCII Grid
    // -------------------------------------------------------------------------

    private fun drawFloraLayer(farOnly: Boolean) {
        val vitality = 0.65f + batteryLevel * 0.35f

        for (i in 0 until floraNodes.size) {
            val n = floraNodes[i]
            val isFar = (n.kind == NodeKind.FAR_VINE)
            if (isFar != farOnly) continue

            val parallaxX = when {
                isFar -> smoothSwipeNorm * 2.4f + smoothGyroX * 2.2f
                n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD -> smoothSwipeNorm * 0.45f + smoothGyroX * 0.3f
                else -> smoothSwipeNorm * 1.0f + smoothGyroX * 0.85f
            }
            val parallaxY = when {
                isFar -> smoothGyroY * 0.85f
                n.kind == NodeKind.ROOT_PCB || n.kind == NodeKind.ROOT_PAD -> 0f
                else -> smoothGyroY * 0.35f
            }

            var cx = n.x + parallaxX
            var cy = n.y + parallaxY

            if (n.flex > 0.1f) {
                val windPhase = if (n.groupId > 0) groupPhases[n.groupId] else n.phase
                val breeze = sin(elapsed * 1.15f + windPhase + n.y * 0.08f)
                cx += breeze * n.flex * 0.85f
                if (breeze > 0.55f && n.kind != NodeKind.CANOPY_HANG) {
                    cy -= (breeze - 0.55f) * n.flex * 0.7f
                }
            }

            var touchBoost = 0f
            for (r in ripples) {
                if (!r.active) continue
                val dx = cx - r.x
                val dy = cy - r.y
                val dist = hypot(dx, dy)
                val band = abs(dist - r.radius)
                if (band < 3.6f && dist > 0.1f) {
                    val influence = (1f - band / 3.6f) * r.strength
                    cx += (dx / dist) * influence * (0.6f + n.flex * 1.5f)
                    cy += (dy / dist) * influence * (0.4f + n.flex * 1.0f)
                    touchBoost = max(touchBoost, influence)
                }
            }

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

            var ch = n.ch
            var col: Int
            var glow = 0

            when (n.kind) {
                NodeKind.FAR_VINE -> {
                    col = colors.farVine
                }
                NodeKind.ROOT_ORGANIC -> {
                    col = if (pulseHit > 0.15f) {
                        glow = if (pulseHit > 0.6f) 1 else 0
                        SpectralRecipe.lerpColor(colors.rootMid, colors.pulseCore, pulseHit)
                    } else {
                        colors.rootMid
                    }
                }
                NodeKind.ROOT_PCB -> {
                    col = if (pulseHit > 0.15f) {
                        glow = if (pulseHit > 0.55f) 1 else 0
                        SpectralRecipe.lerpColor(colors.rootDim, colors.pulseTrail, pulseHit)
                    } else {
                        colors.rootDim
                    }
                }
                NodeKind.ROOT_PAD -> {
                    val padPulse = (sin(elapsed * 2.1f + n.phase) * 0.5f + 0.5f) * vitality
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
                NodeKind.STALK -> {
                    col = if (pulseHit > 0.12f) {
                        if (pulseHit > 0.55f) glow = 1
                        SpectralRecipe.lerpColor(colors.stalk, if (chargeHit) colors.pulseCore else colors.pulseTrail, pulseHit)
                    } else {
                        colors.stalk
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
                    col = if (pulseHit > 0.2f || touchBoost > 0.2f) {
                        SpectralRecipe.lerpColor(colors.branch, colors.leafBright, max(pulseHit, touchBoost))
                    } else {
                        colors.branch
                    }
                }
                NodeKind.LEAF -> {
                    val shimmerIdx = ((elapsed * 1.8f + n.phase * 3f).toInt() and 0x7FFFFFFF) % leafAnimChars.size
                    ch = leafAnimChars[shimmerIdx]
                    val boost = max(pulseHit, touchBoost)
                    col = if (boost > 0.15f) {
                        if (boost > 0.65f) glow = 1
                        SpectralRecipe.lerpColor(colors.leaf, colors.leafBright, boost)
                    } else {
                        colors.leaf
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
                    val bloom = (sin(elapsed * 1.4f + n.phase) * 0.5f + 0.5f) * vitality + pulseHit * 0.9f + touchBoost * 0.7f
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

    private fun drawUsbNexusCore() {
        val cx = (cols * 0.5f + smoothSwipeNorm * 0.4f).roundToInt()
        val cy = rows - 1
        val beat = sin(elapsed * if (isCharging) 5.0f else 1.8f) * 0.5f + 0.5f
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

        // Line 1: ORGANIC // HARDWARE signature
        val tagline = "── ORGANIC // HARDWARE ──"
        val tagY = anchorRow + logoH + 2
        val tagX = ((cols - tagline.length) * 0.5f + parallax).roundToInt()
        val tagCol = SpectralRecipe.lerpColor(colors.stalk, colors.branch, 0.85f)
        for (i in tagline.indices) {
            putCell(tagX + i, tagY, tagline[i], tagCol, 0)
        }

        // Line 2: Clean single info line — ONLY time · date · battery%
        val pct = (batteryLevel * 100f).roundToInt()
        val chgMark = if (isCharging) "+" else ""
        val infoLine = "$cachedTimeText · $cachedDateText · $pct%$chgMark"
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

    /**
     * Renders the smaller 32x5 3D KETTEN logo directly onto the Canvas with sub-pixel precision.
     * Driven directly by the phone's gyro-fused tilt sensor + homescreen swipe + tap spring,
     * with ultra-slow AMOLED pixel-shift burn-in protection.
     */
    private fun drawLogo3DDirect(canvas: Canvas) {
        // Subtle AMOLED burn-in protection: ±2.5px slow drift over ~10 minutes
        val burnShiftPxX = sin(elapsed * 0.011f) * 2.5f
        val burnShiftPxY = cos(elapsed * 0.008f) * 2.5f

        // Front face subtly counter-shifts against the 3D extrusion for true holographic window depth
        val anchorCol = (cols - logoW) * 0.5f - smoothSwipeNorm * 0.55f - smoothGyroX * 0.55f + logoTiltX * 0.22f
        val anchorRow = (rows * 0.42f).roundToInt().toFloat() - smoothGyroY * 0.22f

        // Gyro-driven 3D depth extrusion vector (sub-pixel smooth!)
        val idleSwayX = sin(elapsed * 0.48f) * 0.35f
        val swayCellsX = (smoothGyroX * 2.75f + smoothSwipeNorm * 1.6f + logoTiltX * 1.35f + idleSwayX)
            .coerceIn(-3.1f, 3.1f)
        val swayCellsY = (0.72f + smoothGyroY * 0.95f)
            .coerceIn(-0.45f, 1.55f)

        val extrudePxX = swayCellsX * cellW
        val extrudePxY = swayCellsY * cellH

        // Diagonal metallic sheen sweeping periodically across the 3D letters
        val sheenCycle = elapsed % 6.5f
        val sheenPos = if (sheenCycle < 2.1f) (sheenCycle / 2.1f) * (logoW + 14f) - 7f else -100f

        // Interactive tap shockwave across the 3D logo
        val logoWaveAge = elapsed - logoRippleTime
        val logoWaveActive = logoWaveAge in 0f..1.4f
        val logoWaveRadius = logoWaveAge * 26f

        // Pass 1: 5 smooth volumetric 3D depth slices behind the front face
        val depthSlices = 5
        for (slice in depthSlices downTo 1) {
            val frac = slice.toFloat() / depthSlices.toFloat()
            val sliceOx = extrudePxX * frac + burnShiftPxX
            val sliceOy = extrudePxY * frac + burnShiftPxY
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
                val baseY = offsetY + (anchorRow + ly) * cellH + baselineOffset + sliceOy
                for (lx in 0 until logoW) {
                    if (rowStr[lx] != ' ') {
                        val cx = (anchorCol + lx) * cellW + cellW * 0.5f + sliceOx
                        canvas.drawText(singleCharBuf, 0, 1, cx, baseY, textPaint)
                    }
                }
            }
        }

        // Pass 2: Crisp illuminated front face (with subtle top-bevel glow, sheen & tap wave)
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
        val now = System.currentTimeMillis()
        if (!force && now - lastClockCheckMs < 1000L) return
        lastClockCheckMs = now
        reusableDate.time = now
        cachedTimeText = clockFormat.format(reusableDate)
        cachedDateText = dateFormat.format(reusableDate).replace(".", "").lowercase(Locale.forLanguageTag("nl-NL"))
    }

    private fun seedAmbientSpores() {
        for (s in spores) s.active = false
        repeat(18) {
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
