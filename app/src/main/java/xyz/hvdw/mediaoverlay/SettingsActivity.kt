package xyz.hvdw.mediaoverlay

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import xyz.hvdw.mediaoverlay.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var seekBar: SeekBar
    private lateinit var preview: FrameLayout
    private lateinit var switchAutostart: Switch
    private lateinit var switchMinimize: Switch
    private lateinit var editPolling: EditText
    private lateinit var radioGroupStyle: RadioGroup
    private lateinit var radioStyleClassic: RadioButton
    private lateinit var radioStyleSquare: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = getString(R.string.settings_title)

        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)

        // -----------------------------
        // POLLING INTERVAL
        // -----------------------------
        editPolling = findViewById(R.id.editPollingInterval)
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

        // -----------------------------
        // TRANSPARANTIE
        // -----------------------------
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

        // -----------------------------
        // AUTOSTART
        // -----------------------------
        switchAutostart = findViewById(R.id.switchAutostart)
        switchAutostart.isChecked = prefs.getBoolean("autostart_overlay", false)
        switchAutostart.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("autostart_overlay", value).apply()
        }

        // -----------------------------
        // MINIMALISEREN
        // -----------------------------
        switchMinimize = findViewById(R.id.switchMinimize)
        switchMinimize.isChecked = prefs.getBoolean("minimize_on_start", false)
        switchMinimize.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("minimize_on_start", value).apply()
        }

        // -----------------------------
        // OVERLAY STIJL (NIEUW)
        // -----------------------------
        radioGroupStyle = findViewById(R.id.radioOverlayStyle)
        radioStyleClassic = findViewById(R.id.radioStyleClassic)
        radioStyleSquare = findViewById(R.id.radioStyleSquare)

        val style = prefs.getInt("overlay_style", 0)
        if (style == 0) radioStyleClassic.isChecked = true
        else radioStyleSquare.isChecked = true

        radioGroupStyle.setOnCheckedChangeListener { _, checkedId ->
            val newStyle = when (checkedId) {
                R.id.radioStyleSquare -> 1
                else -> 0
            }
            prefs.edit().putInt("overlay_style", newStyle).apply()
        }
    }

    private fun updatePreview(alpha: Int) {
        preview.background?.alpha = alpha
    }
}
