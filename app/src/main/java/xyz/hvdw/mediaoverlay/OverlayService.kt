package xyz.hvdw.mediaoverlay

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import xyz.hvdw.mediaoverlay.R


class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var overlayView: View
    private var layoutParams: WindowManager.LayoutParams? = null

    private var lastPackageName: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var poller: Runnable? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        loadLastPackageName()
        createOverlay()
        startForegroundNotification()
        startMetadataPolling()

        NotificationListener.callback = {
            try {
                updateOverlayFromNotification()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        poller?.let { handler.removeCallbacks(it) }
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.view_overlay, null)

        val titleView = overlayView.findViewById<TextView>(R.id.titleText)
        val artistView = overlayView.findViewById<TextView>(R.id.artistText)

        // Marquee activeren
        titleView.isSelected = true
        artistView.isSelected = true

        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val alpha = prefs.getInt("overlay_alpha", 200)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val root = overlayView.findViewById<View>(R.id.overlayRoot)
        root.background = resources.getDrawable(R.drawable.overlay_background, null)
        root.background.alpha = alpha

        setupOverlayUi(overlayView)
        windowManager.addView(overlayView, layoutParams)
        makeDraggable(root)
    }

    private fun setupOverlayUi(view: View) {
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPause)
        val btnNext = view.findViewById<ImageButton>(R.id.btnNext)
        val btnPrev = view.findViewById<ImageButton>(R.id.btnPrev)

        btnPlayPause.setOnClickListener { onPlayPauseClicked() }
        btnNext.setOnClickListener { sendTransportControl(PlaybackState.ACTION_SKIP_TO_NEXT) }
        btnPrev.setOnClickListener { sendTransportControl(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }
    }

    private fun makeDraggable(dragView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragView.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - initialTouchX).toInt()
                    lp.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun startMetadataPolling() {
        poller = object : Runnable {
            override fun run() {
                try {
                    updateOverlayFromNotification()
                } catch (_: Exception) {}

                val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                val interval = prefs.getInt("polling_interval", 10000)
                handler.postDelayed(this, interval.toLong())
            }
        }
        handler.post(poller!!)
    }

    private fun updateOverlayFromNotification() {
        val title = NotificationListener.lastTitle
        val artist = NotificationListener.lastArtist
        val art = NotificationListener.lastAlbumArt
        val pkg = NotificationListener.lastPackage
        val isPlaying = NotificationListener.lastIsPlaying

        if (title == null && artist == null && art == null) return

        lastPackageName = pkg
        saveLastPackageName(pkg)

        val titleView = overlayView.findViewById<TextView>(R.id.titleText)
        val artistView = overlayView.findViewById<TextView>(R.id.artistText)
        val artView = overlayView.findViewById<ImageView>(R.id.albumArt)
        val btnPlayPause = overlayView.findViewById<ImageButton>(R.id.btnPlayPause)

        titleView.text = title ?: getString(R.string.overlay_default_title)
        artistView.text = artist ?: getString(R.string.overlay_default_artist)
        startSmoothMarquee(titleView)
        startSmoothMarquee(artistView)

        if (art != null) {
            artView.setImageBitmap(art)
        } else {
            artView.setImageResource(R.drawable.ic_music_placeholder)
        }

        val controller = getCurrentController()
        val state = controller?.playbackState?.state

        val playing = state == PlaybackState.STATE_PLAYING

        btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    private fun getCurrentController(): MediaController? {
        val component = android.content.ComponentName(this, NotificationListener::class.java)

        val controllers = try {
            mediaSessionManager.getActiveSessions(component) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (controllers.isEmpty()) return null

        lastPackageName?.let { pkg ->
            controllers.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        return controllers.firstOrNull()
    }


    private fun sendTransportControl(action: Long) {
        val controller = getCurrentController() ?: return
        val transport = controller.transportControls

        when (action) {
            PlaybackState.ACTION_PLAY_PAUSE -> {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) transport.pause() else transport.play()
            }
            PlaybackState.ACTION_SKIP_TO_NEXT -> transport.skipToNext()
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> transport.skipToPrevious()
        }
    }

    private fun onPlayPauseClicked() {
        val controller = getCurrentController() ?: return
        val state = controller.playbackState?.state

        if (state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }


    private fun saveLastPackageName(pkg: String?) {
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_player_pkg", pkg).apply()
    }

    private fun loadLastPackageName() {
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        lastPackageName = prefs.getString("last_player_pkg", null)
    }

    private fun startForegroundNotification() {
        val channelId = "overlay_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Overlay service",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Media overlay actief")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startSmoothMarquee(textView: TextView, pauseMillis: Long = 2500) {
        textView.post {
            val textWidth = textView.paint.measureText(textView.text.toString())
            val containerWidth = textView.width.toFloat()

            if (textWidth <= containerWidth) {
                textView.translationX = 0f
                return@post
            }

            val distance = textWidth + containerWidth

            val animator = ValueAnimator.ofFloat(0f, -distance)
            animator.duration = (distance * 15).toLong()
            animator.interpolator = LinearInterpolator()
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.RESTART

            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    textView.postDelayed({
                        textView.translationX = 0f
                    }, pauseMillis)
                }
            })

            animator.addUpdateListener { value: ValueAnimator ->
                textView.translationX = value.animatedValue as Float
            }

            animator.start()
        }
    }

}
