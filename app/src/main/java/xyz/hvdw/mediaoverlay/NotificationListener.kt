package xyz.hvdw.mediaoverlay

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

class NotificationListener : NotificationListenerService() {

    companion object {
        var lastTitle: String? = null
        var lastArtist: String? = null
        var lastAlbumArt: Bitmap? = null
        var lastPackage: String? = null
        var lastIsPlaying: Boolean = false

        var callback: (() -> Unit)? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val notif = sbn.notification

        // 1. Alleen muzieknotificaties verwerken
        val isMedia = notif.category == Notification.CATEGORY_TRANSPORT ||
                      notif.extras.containsKey("android.mediaSession")

        if (!isMedia) return

        val pkg = sbn.packageName
        val extras: Bundle = notif.extras

        lastTitle = extras.getString("android.title")
        lastArtist = extras.getString("android.text")
        lastAlbumArt = extractAlbumArt(extras, pkg)
        lastPackage = pkg

        updatePlaybackState(pkg)

        callback?.invoke()
    }


    private fun extractAlbumArt(extras: Bundle, pkg: String): Bitmap? {

        // 1. android.largeIcon (Bitmap of Icon)
        extras.get("android.largeIcon")?.let { raw ->
            when (raw) {
                is Bitmap -> return raw
                is Icon -> try {
                    return raw.loadDrawable(this)?.toBitmap()
                } catch (_: Exception) {}
            }
        }

        // 2. android.picture (Bitmap)
        extras.get("android.picture")?.let { raw ->
            if (raw is Bitmap) return raw
        }

        // 3. android.bigPicture (Bitmap)
        extras.get("android.bigPicture")?.let { raw ->
            if (raw is Bitmap) return raw
        }

        // 4. MediaSession metadata (meest betrouwbaar)
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(null)

            val controller = controllers.firstOrNull { it.packageName == pkg }
            val metadata = controller?.metadata

            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }

        } catch (_: Exception) {}

        // 5. Geen album‑art gevonden
        return null
    }


    private fun updatePlaybackState(pkg: String) {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return

            val controllers: List<MediaController> = try {
                msm.getActiveSessions(null) ?: emptyList()
            } catch (_: SecurityException) {
                return
            } catch (_: Exception) {
                return
            }

            val controller = controllers.firstOrNull { it.packageName == pkg }
            val state = controller?.playbackState

            lastIsPlaying = state?.state == PlaybackState.STATE_PLAYING

        } catch (_: Exception) {
            lastIsPlaying = false
        }
    }
}
