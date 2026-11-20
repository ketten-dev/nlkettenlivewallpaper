package nl.ketten.livewallpaper

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class WallpaperConfig(
    val spectrumMode: SpectrumMode = SpectrumMode.AUTO,
    val animationSpeed: Float = 1.0f,
    val lightCount: Int = 14,
    val windEnabled: Boolean = true,
    val lightsEnabled: Boolean = true,
    val growthEnabled: Boolean = true,
    val useSystemTime: Boolean = true,
    val simulatedMinutes: Int = 19 * 60 + 30, // 19:30 default when testing simulation
)

object WallpaperPrefs {
    private const val PREFS_NAME = "ketten_wallpaper_prefs"

    const val KEY_SPECTRUM_MODE = "spectrum_mode"
    const val KEY_ANIMATION_SPEED = "animation_speed"
    const val KEY_LIGHT_COUNT = "light_count"
    const val KEY_WIND_ENABLED = "wind_enabled"
    const val KEY_LIGHTS_ENABLED = "lights_enabled"
    const val KEY_GROWTH_ENABLED = "growth_enabled"
    const val KEY_USE_SYSTEM_TIME = "use_system_time"
    const val KEY_SIMULATED_MINUTES = "simulated_minutes"

    const val MIN_SPEED = 0.2f
    const val MAX_SPEED = 2.5f
    const val MIN_LIGHTS = 0
    const val MAX_LIGHTS = 24

    fun getPreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): WallpaperConfig {
        val prefs = getPreferences(context)
        return WallpaperConfig(
            spectrumMode = SpectrumMode.fromPref(prefs.getString(KEY_SPECTRUM_MODE, SpectrumMode.AUTO.prefValue)),
            animationSpeed = prefs.getFloat(KEY_ANIMATION_SPEED, 1.0f).coerceIn(MIN_SPEED, MAX_SPEED),
            lightCount = prefs.getInt(KEY_LIGHT_COUNT, 14).coerceIn(MIN_LIGHTS, MAX_LIGHTS),
            windEnabled = prefs.getBoolean(KEY_WIND_ENABLED, true),
            lightsEnabled = prefs.getBoolean(KEY_LIGHTS_ENABLED, true),
            growthEnabled = prefs.getBoolean(KEY_GROWTH_ENABLED, true),
            useSystemTime = prefs.getBoolean(KEY_USE_SYSTEM_TIME, true),
            simulatedMinutes = prefs.getInt(KEY_SIMULATED_MINUTES, 19 * 60 + 30).coerceIn(0, 23 * 60 + 59),
        )
    }

    fun getSpectrumMode(context: Context): SpectrumMode = loadConfig(context).spectrumMode

    fun setSpectrumMode(context: Context, mode: SpectrumMode) {
        getPreferences(context).edit { putString(KEY_SPECTRUM_MODE, mode.prefValue) }
    }

    fun setAnimationSpeed(context: Context, speed: Float) {
        getPreferences(context).edit { putFloat(KEY_ANIMATION_SPEED, speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
    }

    fun setLightCount(context: Context, count: Int) {
        getPreferences(context).edit { putInt(KEY_LIGHT_COUNT, count.coerceIn(MIN_LIGHTS, MAX_LIGHTS)) }
    }

    fun setWindEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_WIND_ENABLED, enabled) }
    }

    fun setLightsEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_LIGHTS_ENABLED, enabled) }
    }

    fun setGrowthEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_GROWTH_ENABLED, enabled) }
    }

    fun setUseSystemTime(context: Context, useSystem: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_USE_SYSTEM_TIME, useSystem) }
    }

    fun setSimulatedMinutes(context: Context, minutes: Int) {
        getPreferences(context).edit { putInt(KEY_SIMULATED_MINUTES, minutes.coerceIn(0, 23 * 60 + 59)) }
    }

    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        getPreferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        getPreferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
