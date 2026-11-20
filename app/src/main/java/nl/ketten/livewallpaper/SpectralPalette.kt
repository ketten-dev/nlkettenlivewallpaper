package nl.ketten.livewallpaper

import android.graphics.Color
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

enum class SpectrumMode(val prefValue: String, val label: String, val fixedHour: Float?) {
    AUTO("auto", "Auto (24u)", null),
    DAWN("dawn", "Ochtend", 7.5f),
    DAY("day", "Dag", 13.0f),
    DUSK("dusk", "Avond", 19.5f),
    NIGHT("night", "Nacht", 1.5f);

    fun next(): SpectrumMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromPref(value: String?): SpectrumMode =
            entries.firstOrNull { it.prefValue == value } ?: AUTO
    }
}

class SpectralColors {
    var bg: Int = 0xFF000000.toInt()
    var farVine: Int = 0          // Atmospheric background: cooler, softer, darker
    var midStalk: Int = 0         // Midground depth layer
    var rootDim: Int = 0
    var rootMid: Int = 0
    var rootNode: Int = 0
    var trunkShadow: Int = 0      // 3D cylindrical dark side
    var trunkCore: Int = 0        // 3D cylindrical main body
    var trunkBark: Int = 0        // Organic curved bark nerve highlight
    var trunkRimWarm: Int = 0     // Warm rim-light facing lights
    var branch: Int = 0
    var leafDark: Int = 0
    var leaf: Int = 0
    var leafBright: Int = 0
    var logoShadow: Int = 0
    var logoSide2: Int = 0
    var logoSide1: Int = 0
    var logoBase: Int = 0
    var logoHigh: Int = 0
    var logoSheen: Int = 0
    var lightDim: Int = 0
    var lightMid: Int = 0
    var lightHigh: Int = 0
    var warmOrbCore: Int = 0      // Warm glowing light core for bloom orbs
    var warmOrbHalo: Int = 0      // Outer warm/spectral halo
    var pulseCore: Int = 0
    var pulseTrail: Int = 0
    var sporeDim: Int = 0
    var sporeBright: Int = 0
    var phaseTag: String = "DAY FULL-SPEC"
}

object SpectralRecipe {
    private data class Keyframe(
        val hour: Float,
        val tag: String,
        val farVine: Int,
        val midStalk: Int,
        val rootDim: Int,
        val rootMid: Int,
        val rootNode: Int,
        val trunkShadow: Int,
        val trunkCore: Int,
        val trunkBark: Int,
        val trunkRimWarm: Int,
        val branch: Int,
        val leafDark: Int,
        val leaf: Int,
        val leafBright: Int,
        val logoShadow: Int,
        val logoSide2: Int,
        val logoSide1: Int,
        val logoBase: Int,
        val logoHigh: Int,
        val logoSheen: Int,
        val lightDim: Int,
        val lightMid: Int,
        val lightHigh: Int,
        val warmOrbCore: Int,
        val warmOrbHalo: Int,
        val pulseCore: Int,
        val pulseTrail: Int,
        val sporeDim: Int,
        val sporeBright: Int,
    )

    // 24-hour spectral recipe keyframes: Night -> Dawn -> Day -> Dusk -> Night
    private val NIGHT = Keyframe(
        hour = 2.0f,
        tag = "NIGHT BIOLUM",
        farVine = 0xFF071619.toInt(),
        midStalk = 0xFF0E2B24.toInt(),
        rootDim = 0xFF0B241A.toInt(),
        rootMid = 0xFF164230.toInt(),
        rootNode = 0xFF2D7D5D.toInt(),
        trunkShadow = 0xFF071A13.toInt(),
        trunkCore = 0xFF143D2B.toInt(),
        trunkBark = 0xFF245E44.toInt(),
        trunkRimWarm = 0xFF7AD9A8.toInt(),
        branch = 0xFF1E573C.toInt(),
        leafDark = 0xFF1B4F37.toInt(),
        leaf = 0xFF398C64.toInt(),
        leafBright = 0xFF6EDBA8.toInt(),
        logoShadow = 0xFF071A12.toInt(),
        logoSide2 = 0xFF0E3021.toInt(),
        logoSide1 = 0xFF1B543B.toInt(),
        logoBase = 0xFF3E946E.toInt(),
        logoHigh = 0xFF9BE8C5.toInt(),
        logoSheen = 0xFFE3FFF3.toInt(),
        lightDim = 0xFF42124D.toInt(),
        lightMid = 0xFF9B38C7.toInt(),
        lightHigh = 0xFFFF91FF.toInt(),
        warmOrbCore = 0xFFFFE4B5.toInt(),
        warmOrbHalo = 0xFFD96EFF.toInt(),
        pulseCore = 0xFF7CFFE0.toInt(),
        pulseTrail = 0xFF28A886.toInt(),
        sporeDim = 0xFF255C48.toInt(),
        sporeBright = 0xFFC78CFF.toInt(),
    )

    private val DAWN = Keyframe(
        hour = 7.5f,
        tag = "DAWN 480nm",
        farVine = 0xFF091E24.toInt(),
        midStalk = 0xFF123632.toInt(),
        rootDim = 0xFF11302A.toInt(),
        rootMid = 0xFF1F594C.toInt(),
        rootNode = 0xFF45AD98.toInt(),
        trunkShadow = 0xFF09211C.toInt(),
        trunkCore = 0xFF1A4F42.toInt(),
        trunkBark = 0xFF2D7866.toInt(),
        trunkRimWarm = 0xFFAEEBD1.toInt(),
        branch = 0xFF297360.toInt(),
        leafDark = 0xFF215E4F.toInt(),
        leaf = 0xFF4EB898.toInt(),
        leafBright = 0xFF94F2D7.toInt(),
        logoShadow = 0xFF0A211C.toInt(),
        logoSide2 = 0xFF143D35.toInt(),
        logoSide1 = 0xFF24695B.toInt(),
        logoBase = 0xFF56BFA5.toInt(),
        logoHigh = 0xFFBCFCEB.toInt(),
        logoSheen = 0xFFFFFFFF.toInt(),
        lightDim = 0xFF4F1B4A.toInt(),
        lightMid = 0xFFBF42A8.toInt(),
        lightHigh = 0xFFFF8CE3.toInt(),
        warmOrbCore = 0xFFFFF0C4.toInt(),
        warmOrbHalo = 0xFFFF9478.toInt(),
        pulseCore = 0xFF9EFFFA.toInt(),
        pulseTrail = 0xFF37C7B4.toInt(),
        sporeDim = 0xFF2F7869.toInt(),
        sporeBright = 0xFFB0FFF2.toInt(),
    )

    private val DAY = Keyframe(
        hour = 13.5f,
        tag = "DAY FULL-SPEC",
        farVine = 0xFF0A1D1B.toInt(),
        midStalk = 0xFF153821.toInt(),
        rootDim = 0xFF13331A.toInt(),
        rootMid = 0xFF255E30.toInt(),
        rootNode = 0xFF54B062.toInt(),
        trunkShadow = 0xFF0A2110.toInt(),
        trunkCore = 0xFF1E5228.toInt(),
        trunkBark = 0xFF347A40.toInt(),
        trunkRimWarm = 0xFFC5F29E.toInt(),
        branch = 0xFF2F783C.toInt(),
        leafDark = 0xFF276332.toInt(),
        leaf = 0xFF5CB86C.toInt(),
        leafBright = 0xFF9EF5A5.toInt(),
        logoShadow = 0xFF0B2110.toInt(),
        logoSide2 = 0xFF163D1F.toInt(),
        logoSide1 = 0xFF286936.toInt(),
        logoBase = 0xFF65BF76.toInt(),
        logoHigh = 0xFFC7F7D2.toInt(),
        logoSheen = 0xFFFFFFFF.toInt(),
        lightDim = 0xFF52133D.toInt(),
        lightMid = 0xFFBD298B.toInt(),
        lightHigh = 0xFFFF66CC.toInt(),
        warmOrbCore = 0xFFFFF2C2.toInt(),
        warmOrbHalo = 0xFFFFB057.toInt(),
        pulseCore = 0xFFD0FFE0.toInt(),
        pulseTrail = 0xFF52C978.toInt(),
        sporeDim = 0xFF347341.toInt(),
        sporeBright = 0xFFFF9CE0.toInt(),
    )

    private val DUSK = Keyframe(
        hour = 19.5f,
        tag = "DUSK 660nm",
        farVine = 0xFF111622.toInt(),
        midStalk = 0xFF1A3321.toInt(),
        rootDim = 0xFF1D2E1E.toInt(),
        rootMid = 0xFF365734.toInt(),
        rootNode = 0xFFA3427A.toInt(),
        trunkShadow = 0xFF0E1F13.toInt(),
        trunkCore = 0xFF214A29.toInt(),
        trunkBark = 0xFF3B7040.toInt(),
        trunkRimWarm = 0xFFFFBA8C.toInt(),
        branch = 0xFF2F6B3A.toInt(),
        leafDark = 0xFF24522E.toInt(),
        leaf = 0xFF54A662.toInt(),
        leafBright = 0xFFA5EB9E.toInt(),
        logoShadow = 0xFF1A0D18.toInt(),
        logoSide2 = 0xFF291D29.toInt(),
        logoSide1 = 0xFF366141.toInt(),
        logoBase = 0xFF68B87A.toInt(),
        logoHigh = 0xFFD6F7D2.toInt(),
        logoSheen = 0xFFFFD6F0.toInt(),
        lightDim = 0xFF661034.toInt(),
        lightMid = 0xFFD92677.toInt(),
        lightHigh = 0xFFFF69B4.toInt(),
        warmOrbCore = 0xFFFFE5A8.toInt(),
        warmOrbHalo = 0xFFFF6B9D.toInt(),
        pulseCore = 0xFFFF9ED0.toInt(),
        pulseTrail = 0xFFD13283.toInt(),
        sporeDim = 0xFF5E2B4E.toInt(),
        sporeBright = 0xFFFF8CC6.toInt(),
    )

    private val keyframes = arrayOf(NIGHT, DAWN, DAY, DUSK)

    fun evaluate(mode: SpectrumMode, hourOverride: Float?, out: SpectralColors) {
        val hour = (mode.fixedHour ?: hourOverride ?: currentHourOfDay()).let { normalizeHour(it) }
        var from = keyframes.last()
        var to = keyframes.first()
        var span = (24f - from.hour) + to.hour
        var dist = if (hour >= from.hour) hour - from.hour else (24f - from.hour) + hour

        for (i in 0 until (keyframes.size - 1)) {
            val a = keyframes[i]
            val b = keyframes[i + 1]
            if (hour >= a.hour && hour < b.hour) {
                from = a
                to = b
                span = b.hour - a.hour
                dist = hour - a.hour
                break
            }
        }

        val rawT = (dist / span).coerceIn(0f, 1f)
        val t = smoothstep(rawT)

        out.bg = 0xFF000000.toInt()
        out.farVine = lerpColor(from.farVine, to.farVine, t)
        out.midStalk = lerpColor(from.midStalk, to.midStalk, t)
        out.rootDim = lerpColor(from.rootDim, to.rootDim, t)
        out.rootMid = lerpColor(from.rootMid, to.rootMid, t)
        out.rootNode = lerpColor(from.rootNode, to.rootNode, t)
        out.trunkShadow = lerpColor(from.trunkShadow, to.trunkShadow, t)
        out.trunkCore = lerpColor(from.trunkCore, to.trunkCore, t)
        out.trunkBark = lerpColor(from.trunkBark, to.trunkBark, t)
        out.trunkRimWarm = lerpColor(from.trunkRimWarm, to.trunkRimWarm, t)
        out.branch = lerpColor(from.branch, to.branch, t)
        out.leafDark = lerpColor(from.leafDark, to.leafDark, t)
        out.leaf = lerpColor(from.leaf, to.leaf, t)
        out.leafBright = lerpColor(from.leafBright, to.leafBright, t)
        out.logoShadow = lerpColor(from.logoShadow, to.logoShadow, t)
        out.logoSide2 = lerpColor(from.logoSide2, to.logoSide2, t)
        out.logoSide1 = lerpColor(from.logoSide1, to.logoSide1, t)
        out.logoBase = lerpColor(from.logoBase, to.logoBase, t)
        out.logoHigh = lerpColor(from.logoHigh, to.logoHigh, t)
        out.logoSheen = lerpColor(from.logoSheen, to.logoSheen, t)
        out.lightDim = lerpColor(from.lightDim, to.lightDim, t)
        out.lightMid = lerpColor(from.lightMid, to.lightMid, t)
        out.lightHigh = lerpColor(from.lightHigh, to.lightHigh, t)
        out.warmOrbCore = lerpColor(from.warmOrbCore, to.warmOrbCore, t)
        out.warmOrbHalo = lerpColor(from.warmOrbHalo, to.warmOrbHalo, t)
        out.pulseCore = lerpColor(from.pulseCore, to.pulseCore, t)
        out.pulseTrail = lerpColor(from.pulseTrail, to.pulseTrail, t)
        out.sporeDim = lerpColor(from.sporeDim, to.sporeDim, t)
        out.sporeBright = lerpColor(from.sporeBright, to.sporeBright, t)
        out.phaseTag = if (t < 0.5f) from.tag else to.tag
    }

    /**
     * Smooth 24-hour organic growth cycle (0.0 .. 1.0) with zero midnight jump:
     *  - 06:00 - 09:00 (Morning): calm open baseline (0.15 -> 0.24)
     *  - 09:00 - 18:00 (Day): steady lush growth (0.24 -> 0.90)
     *  - 18:00 - 23:00 (Evening peak): lush maximum (0.90 -> 1.00 -> 0.92)
     *  - 23:00 - 06:00 (Night wind-down): smooth wrap-around back to 0.15
     */
    fun computeGrowthFactor(rawHour: Float): Float {
        val h = normalizeHour(rawHour)
        return when {
            h in 6.0f..9.0f -> {
                val u = (h - 6.0f) / 3.0f
                0.15f + 0.09f * smoothstep(u)
            }
            h in 9.0f..18.0f -> {
                val u = (h - 9.0f) / 9.0f
                0.24f + 0.66f * smoothstep(u)
            }
            h in 18.0f..23.0f -> {
                // Smooth dome peaking at 20:30 (1.0f) and easing into 0.92f at 23:00
                val u = (h - 18.0f) / 5.0f
                val peak = 0.5f - 0.5f * cos(u * 2f * PI.toFloat())
                val base = 0.90f + 0.02f * u
                (base + 0.09f * peak).coerceIn(0.90f, 1.0f)
            }
            else -> {
                // 23:00 -> 06:00 (7-hour seamless wrap across midnight)
                val dist = if (h >= 23.0f) h - 23.0f else (h + 1.0f)
                val u = (dist / 7.0f).coerceIn(0f, 1f)
                0.92f - (0.92f - 0.15f) * smoothstep(u)
            }
        }
    }

    fun growthPhaseLabel(rawHour: Float): String {
        val h = normalizeHour(rawHour)
        return when {
            h in 6.0f..9.0f -> "Ochtend (rustig)"
            h in 9.0f..18.0f -> "Overdag (groei)"
            h in 18.0f..23.0f -> "Avondpiek (vol)"
            else -> "Nacht (afbouw)"
        }
    }

    fun currentHourOfDay(): Float {
        val cal = Calendar.getInstance()
        return cal[Calendar.HOUR_OF_DAY] + cal[Calendar.MINUTE] / 60f
    }

    private fun normalizeHour(hour: Float): Float {
        var h = hour % 24f
        if (h < 0f) h += 24f
        return h
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF
        val r = (r1 + (r2 - r1) * f).roundToInt()
        val g = (g1 + (g2 - g1) * f).roundToInt()
        val b = (b1 + (b2 - b1) * f).roundToInt()
        return Color.rgb(r, g, b)
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }
}
