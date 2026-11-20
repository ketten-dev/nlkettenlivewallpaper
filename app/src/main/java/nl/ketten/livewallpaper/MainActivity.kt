package nl.ketten.livewallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.example.nlkettenlivewallpaper.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: KettenPreviewView
    private lateinit var settingsScroll: View
    private lateinit var btnToggleSettings: MaterialButton
    private lateinit var btnSpectrum: MaterialButton
    private lateinit var btnSetWallpaper: MaterialButton

    private lateinit var tvSpeedValue: TextView
    private lateinit var sliderSpeed: Slider
    private lateinit var tvLightsValue: TextView
    private lateinit var sliderLights: Slider
    private lateinit var switchWind: SwitchMaterial
    private lateinit var switchLightsAnim: SwitchMaterial
    private lateinit var switchGrowth: SwitchMaterial
    private lateinit var switchSystemTime: SwitchMaterial
    private lateinit var tvTimeSimValue: TextView
    private lateinit var sliderTimeSim: Slider

    private var config: WallpaperConfig = WallpaperConfig()
    private var bindingControls = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        settingsScroll = findViewById(R.id.settingsScroll)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        btnSpectrum = findViewById(R.id.btnSpectrum)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)

        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        sliderSpeed = findViewById(R.id.sliderSpeed)
        tvLightsValue = findViewById(R.id.tvLightsValue)
        sliderLights = findViewById(R.id.sliderLights)
        switchWind = findViewById(R.id.switchWind)
        switchLightsAnim = findViewById(R.id.switchLightsAnim)
        switchGrowth = findViewById(R.id.switchGrowth)
        switchSystemTime = findViewById(R.id.switchSystemTime)
        tvTimeSimValue = findViewById(R.id.tvTimeSimValue)
        sliderTimeSim = findViewById(R.id.sliderTimeSim)

        val controlsContainer = findViewById<View>(R.id.controlsContainer)
        val baseBottomPadding = controlsContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(controlsContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                baseBottomPadding + bars.bottom,
            )
            insets
        }

        // Open settings expanded immediately when launched via wallpaper picker Settings button
        val launchedFromPicker = intent?.action == Intent.ACTION_APPLICATION_PREFERENCES ||
            intent?.categories?.contains(Intent.CATEGORY_LAUNCHER) != true
        settingsScroll.isVisible = launchedFromPicker
        updateToggleSettingsButton()

        if (launchedFromPicker) {
            btnSetWallpaper.setText(R.string.done_button)
        }

        config = WallpaperPrefs.loadConfig(this)
        bindUiFromConfig()
        setupListeners(launchedFromPicker)
    }

    private fun bindUiFromConfig() {
        bindingControls = true
        try {
            val snappedSpeed = ((config.animationSpeed * 10f).roundToInt() / 10f)
                .coerceIn(WallpaperPrefs.MIN_SPEED, WallpaperPrefs.MAX_SPEED)
            sliderSpeed.value = snappedSpeed
            tvSpeedValue.text = String.format(Locale.US, "%.1fx", snappedSpeed)

            sliderLights.value = config.lightCount.toFloat()
            tvLightsValue.text = config.lightCount.toString()

            switchWind.isChecked = config.windEnabled
            switchLightsAnim.isChecked = config.lightsEnabled
            switchGrowth.isChecked = config.growthEnabled
            switchSystemTime.isChecked = config.useSystemTime

            sliderTimeSim.value = config.simulatedMinutes.toFloat()
            updateTimeSimReadout()
            updateSpectrumButton()
        } finally {
            bindingControls = false
        }
    }

    private fun setupListeners(launchedFromPicker: Boolean) {
        btnToggleSettings.setOnClickListener {
            settingsScroll.isVisible = !settingsScroll.isVisible
            updateToggleSettingsButton()
        }

        btnSpectrum.setOnClickListener {
            val nextMode = config.spectrumMode.next()
            config = config.copy(spectrumMode = nextMode)
            WallpaperPrefs.setSpectrumMode(this, nextMode)
            previewView.applyConfig(config)
            previewView.triggerPulse()
            updateSpectrumButton()
        }

        btnSetWallpaper.setOnClickListener {
            if (launchedFromPicker) {
                finish()
            } else {
                openLiveWallpaperPreview()
            }
        }

        sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (bindingControls || !fromUser) return@addOnChangeListener
            val rounded = (value * 10f).roundToInt() / 10f
            tvSpeedValue.text = String.format(Locale.US, "%.1fx", rounded)
            config = config.copy(animationSpeed = rounded)
            WallpaperPrefs.setAnimationSpeed(this, rounded)
            previewView.applyConfig(config)
        }

        sliderLights.addOnChangeListener { _, value, fromUser ->
            if (bindingControls || !fromUser) return@addOnChangeListener
            val count = value.roundToInt().coerceIn(WallpaperPrefs.MIN_LIGHTS, WallpaperPrefs.MAX_LIGHTS)
            tvLightsValue.text = count.toString()
            config = config.copy(lightCount = count)
            WallpaperPrefs.setLightCount(this, count)
            previewView.applyConfig(config)
        }

        switchWind.setOnCheckedChangeListener { _, isChecked ->
            if (bindingControls) return@setOnCheckedChangeListener
            config = config.copy(windEnabled = isChecked)
            WallpaperPrefs.setWindEnabled(this, isChecked)
            previewView.applyConfig(config)
        }

        switchLightsAnim.setOnCheckedChangeListener { _, isChecked ->
            if (bindingControls) return@setOnCheckedChangeListener
            config = config.copy(lightsEnabled = isChecked)
            WallpaperPrefs.setLightsEnabled(this, isChecked)
            previewView.applyConfig(config)
        }

        switchGrowth.setOnCheckedChangeListener { _, isChecked ->
            if (bindingControls) return@setOnCheckedChangeListener
            config = config.copy(growthEnabled = isChecked)
            WallpaperPrefs.setGrowthEnabled(this, isChecked)
            previewView.applyConfig(config)
        }

        switchSystemTime.setOnCheckedChangeListener { _, isChecked ->
            if (bindingControls) return@setOnCheckedChangeListener
            config = config.copy(useSystemTime = isChecked)
            WallpaperPrefs.setUseSystemTime(this, isChecked)
            updateTimeSimReadout()
            previewView.applyConfig(config)
        }

        sliderTimeSim.addOnChangeListener { _, value, fromUser ->
            if (bindingControls || !fromUser) return@addOnChangeListener
            val mins = value.roundToInt().coerceIn(0, 23 * 60 + 59)
            if (config.useSystemTime) {
                bindingControls = true
                switchSystemTime.isChecked = false
                bindingControls = false
                WallpaperPrefs.setUseSystemTime(this, false)
            }
            config = config.copy(
                useSystemTime = false,
                simulatedMinutes = mins,
            )
            WallpaperPrefs.setSimulatedMinutes(this, mins)
            updateTimeSimReadout()
            previewView.applyConfig(config)
        }
    }

    private fun updateToggleSettingsButton() {
        btnToggleSettings.text = getString(
            if (settingsScroll.isVisible) R.string.settings_hide_button else R.string.settings_show_button,
        )
    }

    private fun updateSpectrumButton() {
        btnSpectrum.text = getString(R.string.spectrum_button_fmt, config.spectrumMode.label)
    }

    private fun updateTimeSimReadout() {
        val mins = config.simulatedMinutes
        val h = mins / 60
        val m = mins % 60
        val phase = SpectralRecipe.growthPhaseLabel(mins / 60f)
        tvTimeSimValue.text = if (config.useSystemTime) {
            getString(R.string.time_sim_system_active)
        } else {
            String.format(Locale.US, "%02d:%02d · %s", h, m, phase)
        }
        sliderTimeSim.alpha = if (config.useSystemTime) 0.45f else 1.0f
    }

    private fun openLiveWallpaperPreview() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, KettenWallpaperService::class.java),
            )
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.wallpaper_picker_fallback_error,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
