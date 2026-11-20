package nl.ketten.livewallpaper

import android.content.Context
import androidx.core.content.edit

object WallpaperPrefs {
    private const val PREFS_NAME = "ketten_wallpaper_prefs"
    private const val KEY_SPECTRUM_MODE = "spectrum_mode"

    fun getSpectrumMode(context: Context): SpectrumMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SpectrumMode.fromPref(prefs.getString(KEY_SPECTRUM_MODE, SpectrumMode.AUTO.prefValue))
    }

    fun setSpectrumMode(context: Context, mode: SpectrumMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_SPECTRUM_MODE, mode.prefValue) }
    }
}
