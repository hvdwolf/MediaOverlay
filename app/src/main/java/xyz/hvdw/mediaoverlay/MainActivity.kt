package xyz.hvdw.mediaoverlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean("autostart_overlay", false)

        if (autostart) {
            startOverlay()
        }

        val btnStartOverlay = findViewById<Button>(R.id.btnStartOverlay)
        val btnStopOverlay = findViewById<Button>(R.id.btnStopOverlay)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnMinimize = findViewById<Button>(R.id.btnMinimize)

        btnStartOverlay.setOnClickListener { startOverlay() }
        btnStopOverlay.setOnClickListener { stopOverlay() }
        btnSettings.setOnClickListener { openSettings() }
        btnMinimize.setOnClickListener { minimizeOnce() }
    }

    private fun startOverlay() {
        // 1. Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 2. Check notification listener permission
        if (!isNotificationAccessEnabled()) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            return
        }

        // 3. Start overlay service
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)

        // 4. Minimize app only when user wants it
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val minimize = prefs.getBoolean("minimize_on_start", false)

        if (minimize) {
            minimizeOnce()
        }
    }

    private fun stopOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)

        // Reset minimize flag
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("already_minimized", false).apply()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun minimizeOnce() {
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val alreadyMinimized = prefs.getBoolean("already_minimized", false)

        if (!alreadyMinimized) {
            moveTaskToBack(true)
            prefs.edit().putBoolean("already_minimized", true).apply()
        }
    }

    /**
     * Controleert of de NotificationListener-permissie aan staat.
     */
    private fun isNotificationAccessEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(packageName)
    }
}
