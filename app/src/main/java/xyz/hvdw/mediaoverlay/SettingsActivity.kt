package xyz.hvdw.mediaoverlay

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import xyz.hvdw.mediaoverlay.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var seekBar: SeekBar
    private lateinit var preview: FrameLayout
    private lateinit var switchAutostart: Switch
    private lateinit var switchMinimize: Switch
    private lateinit var editPolling: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = getString(R.string.settings_title)
        editPolling = findViewById(R.id.editPollingInterval)

        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val interval = prefs.getInt("polling_interval", 10000)

        editPolling.setText(interval.toString())
        editPolling.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: 1000
                prefs.edit().putInt("polling_interval", value).apply()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })


        // Transparency controls
        seekBar = findViewById(R.id.transparencySeekBar)
        preview = findViewById(R.id.overlayPreview)

        val alpha = prefs.getInt("overlay_alpha", 200)
        seekBar.progress = alpha
        updatePreview(alpha)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePreview(progress)
                prefs.edit().putInt("overlay_alpha", progress).apply()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Autostart switch
        switchAutostart = findViewById(R.id.switchAutostart)
        switchAutostart.isChecked = prefs.getBoolean("autostart_overlay", false)
        switchAutostart.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("autostart_overlay", value).apply()
        }

        // Minimize switch
        switchMinimize = findViewById(R.id.switchMinimize)
        switchMinimize.isChecked = prefs.getBoolean("minimize_on_start", false)
        switchMinimize.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("minimize_on_start", value).apply()
        }
    }

    private fun updatePreview(alpha: Int) {
        preview.background?.alpha = alpha
    }
}
