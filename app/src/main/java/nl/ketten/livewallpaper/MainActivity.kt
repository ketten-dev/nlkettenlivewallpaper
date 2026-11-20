package nl.ketten.livewallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.nlkettenlivewallpaper.R
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: KettenPreviewView
    private lateinit var btnSpectrum: MaterialButton
    private var currentMode: SpectrumMode = SpectrumMode.AUTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        btnSpectrum = findViewById(R.id.btnSpectrum)
        val btnSetWallpaper = findViewById<MaterialButton>(R.id.btnSetWallpaper)
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

        currentMode = WallpaperPrefs.getSpectrumMode(this)
        updateSpectrumButton()

        btnSpectrum.setOnClickListener {
            currentMode = currentMode.next()
            WallpaperPrefs.setSpectrumMode(this, currentMode)
            previewView.setSpectrumMode(currentMode)
            previewView.triggerPulse()
            updateSpectrumButton()
        }

        btnSetWallpaper.setOnClickListener {
            openLiveWallpaperPreview()
        }
    }

    private fun updateSpectrumButton() {
        btnSpectrum.text = getString(R.string.spectrum_button_fmt, currentMode.label)
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
