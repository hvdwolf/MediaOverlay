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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
        val scale = prefs.getFloat("overlay_scale", 1.0f)

        val layoutId = if (style == 1)
            R.layout.view_overlay_square
        else
            R.layout.view_overlay

        val content = inflater.inflate(layoutId, null, false)
        overlayView = content

        applyOverlayScaling(style, scale, content)

        val root = content.findViewById<View?>(R.id.overlayRoot)
        val titleView = content.findViewById<TextView?>(R.id.titleText)
        val artistView = content.findViewById<TextView?>(R.id.artistText)
        val albumView = content.findViewById<TextView?>(R.id.albumText)

        titleView?.isSelected = true
        artistView?.isSelected = true
        albumView?.isSelected = true

        layoutParams =
            if (style == 1) {
                // square: scale container
                val base = dpToPx(250)
                val scaled = (base * scale).toInt()

                WindowManager.LayoutParams(
                    scaled,
                    scaled,
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
                // classic: start with WRAP_CONTENT, we’ll adjust widths below
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
                val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                val savedX = prefs.getInt("overlay_pos_x", 100)
                val savedY = prefs.getInt("overlay_pos_y", 100)

                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
            }

        // background
        if (root != null) {
            val bg = if (style == 0)
                resources.getDrawable(R.drawable.overlay_background, null)
            else
                resources.getDrawable(R.drawable.square_overlay_background, null)

            bg.alpha = alpha
            root.background = bg
        }


        setupOverlayUi(content)
        windowManager.addView(overlayView, layoutParams)

        handler.post { updateOverlayFromMediaSession() }
        makeDraggable(overlayView)
    }

    private fun applyOverlayScaling(style: Int, scale: Float, content: View) {

        // Common views
        val titleView = content.findViewById<TextView?>(R.id.titleText)
        val artistView = content.findViewById<TextView?>(R.id.artistText)
        val albumView = content.findViewById<TextView?>(R.id.albumText)

        val btnPrev = content.findViewById<ImageButton?>(R.id.btnPrev)
        val btnPlay = content.findViewById<ImageButton?>(R.id.btnPlayPause)
        val btnNext = content.findViewById<ImageButton?>(R.id.btnNext)

        // -------------------------------
        // 🔹 TEXT SCALING (common)
        // -------------------------------
        if (titleView != null) titleView.textSize = (if (style == 1) 18f else 16f) * scale
        if (artistView != null) artistView.textSize = 14f * scale
        if (albumView != null) albumView.textSize = 14f * scale

        // -------------------------------
        // 🔹 BUTTON SIZE + ICON SCALING (common)
        // -------------------------------
        val btnSize = (dpToPx(40) * scale).toInt()

        listOf(btnPrev, btnPlay, btnNext).forEach { btn ->
            btn?.layoutParams?.width = btnSize
            btn?.layoutParams?.height = btnSize
            btn?.scaleX = scale
            btn?.scaleY = scale
            btn?.requestLayout()
        }

        // -------------------------------
        // 🔹 CLASSIC OVERLAY SPECIFIC
        // -------------------------------
        if (style == 0) {
            val albumArt = content.findViewById<ImageView?>(R.id.albumArt)
            val textBlock = content.findViewById<LinearLayout?>(R.id.textBlock)
            val rootClassic = content.findViewById<LinearLayout?>(R.id.overlayRoot)

            // Scale album art width (base 90dp)
            albumArt?.layoutParams?.width = (dpToPx(90) * scale).toInt()

            // Scale text block width (base 250dp)
            textBlock?.layoutParams?.width = (dpToPx(250) * scale).toInt()

            // Scale padding (base 8dp)
            val pad = (dpToPx(8) * scale).toInt()
            rootClassic?.setPadding(pad, pad, pad, pad)

            albumArt?.requestLayout()
            textBlock?.requestLayout()
            rootClassic?.requestLayout()
        }

        // -------------------------------
        // 🔹 SQUARE OVERLAY SPECIFIC
        // -------------------------------
        if (style == 1) {
            val rootSquare = content.findViewById<View?>(R.id.overlaySquareRoot)

            // Scale padding (base 12dp)
            val pad = (dpToPx(12) * scale).toInt()
            rootSquare?.setPadding(pad, pad, pad, pad)

            rootSquare?.requestLayout()
        }
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

                    // Save new position
                    val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("overlay_pos_x", lp.x)
                        .putInt("overlay_pos_y", lp.y)
                        .apply()

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
        //titleView?.let { startSmoothMarqueeIfNeeded(it) }
        //artistView?.let { startSmoothMarqueeIfNeeded(it) }
        //if (!album.isNullOrBlank()) albumView?.let { startSmoothMarqueeIfNeeded(it) }
        titleView?.let { it }
        artistView?.let { it }
        if (!album.isNullOrBlank()) albumView?.let { it }

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

        // Prefer the last known package if still active
        NotificationListener.lastPackage?.let { pkg ->
            controllers.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        lastPackageName?.let { pkg ->
            controllers.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        // Otherwise return the first active controller
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
