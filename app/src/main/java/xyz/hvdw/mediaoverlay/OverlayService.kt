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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var overlayView: View
    private lateinit var root: View
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
                updateOverlayFromMediaSession()
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
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val style = prefs.getInt("overlay_style", 0)
        val alpha = prefs.getInt("overlay_alpha", 200)

        // Kies layout
        val layoutId = if (style == 1)
            R.layout.view_overlay_square
        else
            R.layout.view_overlay

        // Correct inflaten
        overlayView = inflater.inflate(layoutId, null, false)

        val titleView = overlayView.findViewById<TextView?>(R.id.titleText)
        val artistView = overlayView.findViewById<TextView?>(R.id.artistText)
        val albumView = overlayView.findViewById<TextView?>(R.id.albumText)
        val root = overlayView.findViewById<View?>(R.id.overlayRoot)

        // Marquee-ready
        titleView?.isSelected = true
        artistView?.isSelected = true
        albumView?.isSelected = true

        // LayoutParams PER STIJL
        layoutParams =
            if (style == 1) {
                // Square overlay: vaste maat
                WindowManager.LayoutParams(
                    dpToPx(250),
                    dpToPx(250),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
            } else {
                // Classic overlay: wrap_content
                WindowManager.LayoutParams(
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
                )
            }.apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }

        // Achtergrond per stijl
        if (root != null) {
            if (style == 0) {
                // Klassieke overlay → overlay_background gebruiken
                val bg = resources.getDrawable(R.drawable.overlay_background, null)
                bg.alpha = alpha
                root.background = bg
            } else {
                // Vierkante overlay → zwarte achtergrond + ronde hoeken
                val bg = resources.getDrawable(R.drawable.square_overlay_background, null)
                bg.alpha = alpha
                root.background = bg
            }
        }

        setupOverlayUi(overlayView)
        windowManager.addView(overlayView, layoutParams)

        // Marquee pas starten NA toevoegen overlay
        handler.post { updateOverlayFromMediaSession() }

        root?.let { makeDraggable(it) }
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
                    updateOverlayFromMediaSession()
                } catch (_: Exception) {}

                val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                val interval = prefs.getInt("polling_interval", 10000)
                handler.postDelayed(this, interval.toLong())
            }
        }
        handler.post(poller!!)
    }

    private fun updateOverlayFromMediaSession() {
        val controller = getCurrentController() ?: return
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val art =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        lastPackageName = controller.packageName
        saveLastPackageName(controller.packageName)

        // Alle views optioneel maken
        val titleView = overlayView.findViewById<TextView?>(R.id.titleText)
        val artistView = overlayView.findViewById<TextView?>(R.id.artistText)
        val albumView = overlayView.findViewById<TextView?>(R.id.albumText)
        val artView = overlayView.findViewById<ImageView?>(R.id.albumArt)
        val bgArtView = overlayView.findViewById<ImageView?>(R.id.bgAlbumArt)
        val btnPlayPause = overlayView.findViewById<ImageButton?>(R.id.btnPlayPause)

        // Tekst
        titleView?.text = title
        artistView?.text = artist

        if (albumView != null) {
            if (album.isNullOrBlank()) {
                albumView.visibility = View.GONE
            } else {
                albumView.visibility = View.VISIBLE
                albumView.text = album
            }
        }

        // Marquee
        titleView?.let { startSmoothMarqueeIfNeeded(it) }
        artistView?.let { startSmoothMarqueeIfNeeded(it) }
        if (!album.isNullOrBlank()) albumView?.let { startSmoothMarqueeIfNeeded(it) }

        // Albumart als achtergrond (square overlay)
        if (bgArtView != null) {
            if (art != null) bgArtView.setImageBitmap(art)
            else bgArtView.setImageResource(R.drawable.ic_music_placeholder)
        }

        // Albumart als icoon (classic overlay)
        if (artView != null) {
            if (art != null) artView.setImageBitmap(art)
            else artView.setImageResource(R.drawable.ic_music_placeholder)
        }

        // Play/pause icoon
        if (btnPlayPause != null) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            btnPlayPause.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }


    private fun getCurrentController(): MediaController? {
        val component = ComponentName(this, NotificationListener::class.java)

        val controllers = try {
            mediaSessionManager.getActiveSessions(component) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (controllers.isEmpty()) return null

        NotificationListener.lastPackage?.let { pkg ->
            controllers.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        lastPackageName?.let { pkg ->
            controllers.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        return controllers.firstOrNull()
    }

    private fun sendTransportControl(action: Long) {
        val controller = getCurrentController() ?: return
        val transport = controller.transportControls

        when (action) {
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }


    private fun startSmoothMarqueeIfNeeded(textView: TextView, pauseMillis: Long = 200) {
        textView.post {
            val text = textView.text?.toString() ?: return@post
            if (text.isBlank()) return@post

            // Cancel any previous animator stored in the tag
            (textView.getTag(R.id.marquee_animator) as? ValueAnimator)?.cancel()

            fun measureAndStart() {
                val textWidth = textView.paint.measureText(text)
                val containerWidth = textView.width.toFloat()

                // If width is still 0, retry shortly
                if (containerWidth == 0f) {
                    textView.postDelayed({ measureAndStart() }, 30)
                    return
                }

                // Only scroll if text is actually too long
                if (textWidth <= containerWidth) {
                    textView.translationX = 0f
                    return
                }

                // Start offset (1.3 × width)
                val startOffset = containerWidth * 1.3f
                val distance = textWidth + startOffset
                val duration = (distance * 12).toLong()

                val animator = ValueAnimator.ofFloat(startOffset, -textWidth).apply {
                    this.duration = duration
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART

                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationRepeat(animation: Animator) {
                            textView.postDelayed({
                                textView.translationX = startOffset
                            }, pauseMillis)
                        }
                    })

                    addUpdateListener { value ->
                        textView.translationX = value.animatedValue as Float
                    }
                }

                // Store animator so we can cancel it later
                textView.setTag(R.id.marquee_animator, animator)

                textView.translationX = startOffset
                animator.start()
            }

            // Start measurement
            measureAndStart()
        }
    }

}
