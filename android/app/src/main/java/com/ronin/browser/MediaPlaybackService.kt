package com.ronin.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

/** Connects the notification / lock screen / car buttons with the page that plays. */
object PlayerBridge {
    /** Set by MainActivity: runs "play", "pause", "nexttrack", "previoustrack" or "stop" on the page. */
    @Volatile var control: ((String) -> Unit)? = null
}

/**
 * Keeps a song or video playing when Ronin is swiped away or the screen is off,
 * and shows it in the notification bar and on the lock screen with
 * previous / play-pause / next / close. Car Bluetooth buttons reach the same
 * media session.
 */
class MediaPlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private var wake: PowerManager.WakeLock? = null
    private var title = "Ronin"
    private var artist = ""
    private var playing = false
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        session = MediaSessionCompat(this, "Ronin").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send("play")
                override fun onPause() = send("pause")
                override fun onSkipToNext() = send("nexttrack")
                override fun onSkipToPrevious() = send("previoustrack")
                override fun onStop() = send("stop")
            })
            setSessionActivity(openApp())
            isActive = true
        }
    }

    private fun send(a: String) {
        PlayerBridge.control?.invoke(a)
        if (a == "stop") stopEverything()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                title = intent.getStringExtra("title") ?: title
                artist = intent.getStringExtra("artist") ?: artist
                playing = intent.getBooleanExtra("playing", false)
                show()
            }
            ACTION_PLAY_PAUSE -> { send(if (playing) "pause" else "play"); playing = !playing; show() }
            ACTION_NEXT -> send("nexttrack")
            ACTION_PREV -> send("previoustrack")
            // Close: the song pauses AND Ronin lets go of it (with "pause" the next media check put a paused card back)
            ACTION_CLOSE -> { send("stop"); stopEverything() }
            ACTION_QUIET -> stopEverything() // Ronin let go of the page: no notification, no wake lock, page not touched
            else -> if (!foreground) show()
        }
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(a: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, MediaPlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE
    )

    private fun show() {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f
                )
                .build()
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playing now", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Song or video playing in Ronin"
                    setShowBadge(false)
                }
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ronin)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openApp())
            .setDeleteIntent(action(ACTION_CLOSE, 9))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", action(ACTION_PLAY_PAUSE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", action(ACTION_CLOSE, 4))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (!foreground) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            foreground = true
        } else {
            nm.notify(NOTIFICATION_ID, n)
        }
        holdWake(playing)
    }

    private val wakeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseWake = Runnable { wake?.let { if (it.isHeld) it.release() }; wake = null }

    /** Keeps the phone awake while playing, and for 90 s after a pause (the next song starts in that gap). */
    private fun holdWake(on: Boolean) {
        wakeHandler.removeCallbacks(releaseWake)
        if (on && wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ronin:media").apply { acquire(6 * 60 * 60 * 1000L) }
        } else if (!on) {
            wakeHandler.postDelayed(releaseWake, 90_000)
        }
    }

    private fun dropWake() { wakeHandler.removeCallbacks(releaseWake); releaseWake.run() }

    private fun stopEverything() {
        dropWake()
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        dropWake()
        session.release()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        const val CHANNEL = "media"
        const val NOTIFICATION_ID = 77
        const val ACTION_UPDATE = "com.ronin.browser.UPDATE"
        const val ACTION_PLAY_PAUSE = "com.ronin.browser.PLAY_PAUSE"
        const val ACTION_NEXT = "com.ronin.browser.NEXT"
        const val ACTION_PREV = "com.ronin.browser.PREV"
        const val ACTION_CLOSE = "com.ronin.browser.CLOSE"
        const val ACTION_QUIET = "com.ronin.browser.QUIET"

        /** Ends the background player (notification + wake lock) without pausing the page. */
        fun stop(ctx: Context) {
            if (!running) return
            try { ctx.startService(Intent(ctx, MediaPlaybackService::class.java).setAction(ACTION_QUIET)) } catch (e: Exception) {}
        }

        fun update(ctx: Context, title: String, artist: String, playing: Boolean) {
            val i = Intent(ctx, MediaPlaybackService::class.java).setAction(ACTION_UPDATE)
                .putExtra("title", title).putExtra("artist", artist).putExtra("playing", playing)
            try {
                // a running player service only needs the news; starting a new one needs the foreground start
                if (running || Build.VERSION.SDK_INT < 26) {
                    try { ctx.startService(i) } catch (e: IllegalStateException) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) }
                } else ctx.startForegroundService(i)
            } catch (e: Exception) {
                // the app is in the background and Android refused: the service is already running then
            }
        }
    }
}
