package nl.ketten.livewallpaper

import android.graphics.Color
import java.util.Calendar
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
    var logDim: Int = 0
    var logMid: Int = 0
    var farVine: Int = 0
    var rootDim: Int = 0
    var rootMid: Int = 0
    var rootNode: Int = 0
    var stalk: Int = 0
    var branch: Int = 0
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
        val logDim: Int,
        val logMid: Int,
        val farVine: Int,
        val rootDim: Int,
        val rootMid: Int,
        val rootNode: Int,
        val stalk: Int,
        val branch: Int,
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
        val pulseCore: Int,
        val pulseTrail: Int,
        val sporeDim: Int,
        val sporeBright: Int,
    )

    // 24-hour spectral recipe keyframes: Night -> Dawn -> Day -> Dusk -> Night
    private val NIGHT = Keyframe(
        hour = 2.0f,
        tag = "NIGHT BIOLUM",
        logDim = 0xFF06120B.toInt(),
        logMid = 0xFF0C2418.toInt(),
        farVine = 0xFF081B12.toInt(),
        rootDim = 0xFF0D291C.toInt(),
        rootMid = 0xFF174530.toInt(),
        rootNode = 0xFF2B7A58.toInt(),
        stalk = 0xFF123826.toInt(),
        branch = 0xFF1E573C.toInt(),
        leaf = 0xFF398C64.toInt(),
        leafBright = 0xFF6AD4A4.toInt(),
        logoShadow = 0xFF071A12.toInt(),
        logoSide2 = 0xFF0E3021.toInt(),
        logoSide1 = 0xFF1B543B.toInt(),
        logoBase = 0xFF3E946E.toInt(),
        logoHigh = 0xFF9BE8C5.toInt(),
        logoSheen = 0xFFE3FFF3.toInt(),
        lightDim = 0xFF3A0C4A.toInt(),
        lightMid = 0xFF8B26B8.toInt(),
        lightHigh = 0xFFE066FF.toInt(),
        pulseCore = 0xFF7CFFE0.toInt(),
        pulseTrail = 0xFF28A886.toInt(),
        sporeDim = 0xFF255C48.toInt(),
        sporeBright = 0xFFB87BFF.toInt(),
    )

    private val DAWN = Keyframe(
        hour = 7.5f,
        tag = "DAWN 480nm",
        logDim = 0xFF081A18.toInt(),
        logMid = 0xFF10332E.toInt(),
        farVine = 0xFF0B2420.toInt(),
        rootDim = 0xFF13362E.toInt(),
        rootMid = 0xFF205A4D.toInt(),
        rootNode = 0xFF42A894.toInt(),
        stalk = 0xFF194A3E.toInt(),
        branch = 0xFF297360.toInt(),
        leaf = 0xFF4EB898.toInt(),
        leafBright = 0xFF8EEBD0.toInt(),
        logoShadow = 0xFF0A211C.toInt(),
        logoSide2 = 0xFF143D35.toInt(),
        logoSide1 = 0xFF24695B.toInt(),
        logoBase = 0xFF56BFA5.toInt(),
        logoHigh = 0xFFBCFCEB.toInt(),
        logoSheen = 0xFFFFFFFF.toInt(),
        lightDim = 0xFF4A1248.toInt(),
        lightMid = 0xFFA6299C.toInt(),
        lightHigh = 0xFFFF66DB.toInt(),
        pulseCore = 0xFF9EFFFA.toInt(),
        pulseTrail = 0xFF37C7B4.toInt(),
        sporeDim = 0xFF2F7869.toInt(),
        sporeBright = 0xFFA8FFF0.toInt(),
    )

    private val DAY = Keyframe(
        hour = 13.5f,
        tag = "DAY FULL-SPEC",
        logDim = 0xFF0A1D0E.toInt(),
        logMid = 0xFF13361A.toInt(),
        farVine = 0xFF0C2412.toInt(),
        rootDim = 0xFF15381D.toInt(),
        rootMid = 0xFF255E30.toInt(),
        rootNode = 0xFF4FA85D.toInt(),
        stalk = 0xFF1D4F26.toInt(),
        branch = 0xFF2F783C.toInt(),
        leaf = 0xFF5CB86C.toInt(),
        leafBright = 0xFF96ED9E.toInt(),
        logoShadow = 0xFF0B2110.toInt(),
        logoSide2 = 0xFF163D1F.toInt(),
        logoSide1 = 0xFF286936.toInt(),
        logoBase = 0xFF65BF76.toInt(),
        logoHigh = 0xFFC7F7D2.toInt(),
        logoSheen = 0xFFFFFFFF.toInt(),
        lightDim = 0xFF4D0C3A.toInt(),
        lightMid = 0xFFA81D7E.toInt(),
        lightHigh = 0xFFF244C4.toInt(),
        pulseCore = 0xFFD0FFE0.toInt(),
        pulseTrail = 0xFF52C978.toInt(),
        sporeDim = 0xFF347341.toInt(),
        sporeBright = 0xFFF27AD2.toInt(),
    )

    private val DUSK = Keyframe(
        hour = 19.5f,
        tag = "DUSK 660nm",
        logDim = 0xFF141118.toInt(),
        logMid = 0xFF241B29.toInt(),
        farVine = 0xFF102016.toInt(),
        rootDim = 0xFF1D3020.toInt(),
        rootMid = 0xFF335434.toInt(),
        rootNode = 0xFF8C366B.toInt(),
        stalk = 0xFF1A4223.toInt(),
        branch = 0xFF2D6637.toInt(),
        leaf = 0xFF54A362.toInt(),
        leafBright = 0xFF96E09B.toInt(),
        logoShadow = 0xFF1A0D18.toInt(),
        logoSide2 = 0xFF291D29.toInt(),
        logoSide1 = 0xFF366141.toInt(),
        logoBase = 0xFF68B87A.toInt(),
        logoHigh = 0xFFD6F7D2.toInt(),
        logoSheen = 0xFFFFD6F0.toInt(),
        lightDim = 0xFF5E0B2F.toInt(),
        lightMid = 0xFFC91C6A.toInt(),
        lightHigh = 0xFFFF4D9E.toInt(),
        pulseCore = 0xFFFF8AC4.toInt(),
        pulseTrail = 0xFFC42B7B.toInt(),
        sporeDim = 0xFF592549.toInt(),
        sporeBright = 0xFFFF70B8.toInt(),
    )

    private val keyframes = arrayOf(NIGHT, DAWN, DAY, DUSK)

    fun evaluate(mode: SpectrumMode, out: SpectralColors) {
        val hour = mode.fixedHour ?: currentHourOfDay()
        var from = keyframes.last()
        var to = keyframes.first()
        var span = (24f - from.hour) + to.hour
        var dist = if (hour >= from.hour) hour - from.hour else (24f - from.hour) + hour

        for (i in 0 until keyframes.size - 1) {
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
        // Smoothstep for gentle transitions across the day
        val t = rawT * rawT * (3f - 2f * rawT)

        out.bg = 0xFF000000.toInt()
        out.logDim = lerpColor(from.logDim, to.logDim, t)
        out.logMid = lerpColor(from.logMid, to.logMid, t)
        out.farVine = lerpColor(from.farVine, to.farVine, t)
        out.rootDim = lerpColor(from.rootDim, to.rootDim, t)
        out.rootMid = lerpColor(from.rootMid, to.rootMid, t)
        out.rootNode = lerpColor(from.rootNode, to.rootNode, t)
        out.stalk = lerpColor(from.stalk, to.stalk, t)
        out.branch = lerpColor(from.branch, to.branch, t)
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
        out.pulseCore = lerpColor(from.pulseCore, to.pulseCore, t)
        out.pulseTrail = lerpColor(from.pulseTrail, to.pulseTrail, t)
        out.sporeDim = lerpColor(from.sporeDim, to.sporeDim, t)
        out.sporeBright = lerpColor(from.sporeBright, to.sporeBright, t)
        out.phaseTag = if (t < 0.5f) from.tag else to.tag
    }

    private fun currentHourOfDay(): Float {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
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
