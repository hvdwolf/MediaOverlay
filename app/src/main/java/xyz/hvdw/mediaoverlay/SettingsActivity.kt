package xyz.hvdw.mediaoverlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private lateinit var switchStartOnPlay: Switch
    private lateinit var switchStartOnAppOpen: Switch
    private lateinit var mediaAppList: LinearLayout

    private val sizeLabels = arrayOf(
        "50%", "75%", "100%", "125%", "150%", "175%", "200%"
    )

    private val sizeScales = floatArrayOf(
        0.50f, 0.75f, 1.00f, 1.25f, 1.50f, 1.75f, 2.00f
    )


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
        // OVERLAY STIJL
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

        // -----------------------------
        // OVERLAY SIZE
        // -----------------------------
        val seekOverlaySize = findViewById<SeekBar>(R.id.seekOverlaySize)
        val txtOverlaySizeValue = findViewById<TextView>(R.id.txtOverlaySizeValue)

        val currentScale = prefs.getFloat("overlay_scale", 1.0f)
        val currentIndex = sizeScales.indexOfFirst { it == currentScale }.coerceAtLeast(2)

        seekOverlaySize.progress = currentIndex
        txtOverlaySizeValue.text = sizeLabels[currentIndex]

        seekOverlaySize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtOverlaySizeValue.text = sizeLabels[progress]
                prefs.edit().putFloat("overlay_scale", sizeScales[progress]).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        // -----------------------------
        // START AUTOMATICALLY
        // -----------------------------
        switchStartOnPlay = findViewById(R.id.switchStartOnPlay)
        switchStartOnAppOpen = findViewById(R.id.switchStartOnAppOpen)
        mediaAppList = findViewById(R.id.mediaAppList)

        switchStartOnPlay.isChecked = prefs.getBoolean("start_on_play", true)
        switchStartOnAppOpen.isChecked = prefs.getBoolean("start_on_app_open", false)

        switchStartOnPlay.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("start_on_play", value).apply()
        }

        switchStartOnAppOpen.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("start_on_app_open", value).apply()
        }

        // -----------------------------
        // Selectable list of possible media players
        // -----------------------------
        // Dynamische lijst van mediaspelers
        val installedPlayers = detectMediaApps()
        val selectedApps = prefs.getStringSet("auto_start_apps", emptySet())!!.toMutableSet()

        installedPlayers.forEach { pkg ->
            val cb = CheckBox(this).apply {
                text = pkg
                isChecked = selectedApps.contains(pkg)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedApps.add(pkg)
                    else selectedApps.remove(pkg)
                    prefs.edit().putStringSet("auto_start_apps", selectedApps).apply()
                }
            }
            mediaAppList.addView(cb)
        }

    }

    private fun updatePreview(alpha: Int) {
        preview.background?.alpha = alpha
    }

    private fun detectMediaApps(): List<String> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)

        val mediaApps = mutableSetOf<String>()

        // 1. Apps die een MediaButtonReceiver hebben
        val receivers = pm.queryBroadcastReceivers(intent, 0)
        receivers?.forEach { resolveInfo ->
            resolveInfo.activityInfo?.packageName?.let { mediaApps.add(it) }
        }

        // 2. Apps die een MediaBrowserService aanbieden
        val browserIntent = Intent("android.media.browse.MediaBrowserService")
        val services = pm.queryIntentServices(browserIntent, 0)
        services?.forEach { resolveInfo ->
            resolveInfo.serviceInfo?.packageName?.let { mediaApps.add(it) }
        }

        // 3. MediaSessionService (Media3)
        val media3Intent = Intent("androidx.media3.session.MediaSessionService")
        pm.queryIntentServices(media3Intent, 0)?.forEach { info ->
            info.serviceInfo?.packageName?.let { mediaApps.add(it) }
        }

        // 4. Fallback voor bekende spelers
        val known = listOf(
            "com.spotify.music",
            "com.maxmpz.audioplayer",
            "com.aimp.player",
            "de.zorillasoft.musicfolderplayer",
            "de.zorillasoft.musicfolderplayer.donate",
            "com.navradio"
        )

        mediaApps.addAll(known)

        return mediaApps.toList().sorted()
    }


    /*private fun detectMediaApps(): List<String> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)

        // This set will collect all detected media-capable apps
        val mediaApps = mutableSetOf<String>()

        // ---------------------------------------------------------
        // 1. Detect apps with an active MediaSession
        // ---------------------------------------------------------
        // This is the most reliable method: if an app exposes a MediaSession,
        // it is almost certainly a media player.
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        try {
            val controllers = msm.getActiveSessions(null)
            controllers.forEach { controller ->
                mediaApps.add(controller.packageName)
            }
        } catch (_: Exception) {
            // Some devices may not allow querying active sessions without notification access
        }

        // ---------------------------------------------------------
        // 2. Detect apps that request audio/media-related permissions
        // ---------------------------------------------------------
        // Many media players request permissions like RECORD_AUDIO, MODIFY_AUDIO_SETTINGS,
        // or other media-related permissions. This is a good fallback.
        apps.forEach { app ->
            val pkg = app.packageName
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                val perms = info.requestedPermissions ?: emptyArray()

                // Check if any permission contains AUDIO or MEDIA
                if (perms.any { p ->
                        p.contains("AUDIO", ignoreCase = true) ||
                        p.contains("MEDIA", ignoreCase = true)
                    }) {
                    mediaApps.add(pkg)
                }
            } catch (_: Exception) {
                // Some apps may not expose permissions cleanly; ignore errors
            }
        }

        // ---------------------------------------------------------
        // 3. Add known media players as fallback
        // ---------------------------------------------------------
        // Some apps (like FYT radio or custom car players) do NOT expose MediaSessions
        // and do NOT request audio permissions. We add them manually.
        val known = listOf(
            "com.maxmpz.audioplayer",   // Poweramp
            "com.aimp.player",          // AIMP
            "com.spotify.music",        // Spotify
            "com.google.android.music", // Google Play Music (legacy)
            "com.navradio",             // FYT NavRadio
            "com.android.car.media",    // Generic car media player
            "de.zorillasoft.musicfolderplayer",
            "de.zorillasoft.musicfolderplayer.donate"
        )

        mediaApps.addAll(known)

        // Return sorted list for a clean UI
        return mediaApps.toList().sorted()
    } */

}
