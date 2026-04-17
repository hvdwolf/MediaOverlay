package xyz.hvdw.mediaoverlay

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

class NotificationListener : NotificationListenerService() {

    companion object {
        var lastPackage: String? = null
        var callback: (() -> Unit)? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val notif = sbn.notification
        val extras = notif.extras
        val pkg = sbn.packageName

        val isMedia = notif.category == Notification.CATEGORY_TRANSPORT ||
                  extras.containsKey("android.mediaSession")
        if (!isMedia) return

        val prefs = getSharedPreferences("overlay_prefs", MODE_PRIVATE)
        val startOnPlay = prefs.getBoolean("start_on_play", false)
        val autoStartApps = prefs.getStringSet("auto_start_apps", emptySet()) ?: emptySet()

        // 🔴 don't act on not selected apps
        if (!autoStartApps.contains(pkg)) {
            return
        }

        // Only selected apps get here
        lastPackage = pkg

        if (startOnPlay) {
            val intent = Intent(applicationContext, OverlayService::class.java)
            ContextCompat.startForegroundService(applicationContext, intent)
        }

        callback?.invoke()
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Do nothing — some players remove notifications on pause.
    }
}
