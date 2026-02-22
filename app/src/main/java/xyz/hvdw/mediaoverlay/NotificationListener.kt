package xyz.hvdw.mediaoverlay

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

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

        // Alleen media-notificaties verwerken
        val isMedia = notif.category == Notification.CATEGORY_TRANSPORT ||
                      extras.containsKey("android.mediaSession")

        if (!isMedia) return

        // Bewaar alleen het pakket
        lastPackage = pkg

        // Laat OverlayService weten dat er iets veranderd is
        callback?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Niet wissen! Sommige spelers verwijderen notificaties bij pauze.
        // We laten lastPackage gewoon staan.
    }
}
