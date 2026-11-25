package com.ronin.browser

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Ronin Music in the car (Android Auto): a short list of big items (no video - Google's driving rule) and
 * play / pause / next / previous. Works even when the app is closed on the phone: the page is made in the background.
 */
class CarMusicService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "RoninMusicCar").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { SitePlayer.play(this@CarMusicService, "resume") }
                override fun onPause() { PlayerBridge.control?.invoke("pause") }
                override fun onSkipToNext() { SitePlayer.page(this@CarMusicService); PlayerBridge.control?.invoke("nexttrack") }
                override fun onSkipToPrevious() { SitePlayer.page(this@CarMusicService); PlayerBridge.control?.invoke("previoustrack") }
                override fun onStop() { PlayerBridge.control?.invoke("pause") }
                override fun onPlayFromMediaId(id: String?, extras: Bundle?) {
                    val item = ITEMS.firstOrNull { it.first == id } ?: return
                    SitePlayer.play(this@CarMusicService, item.third)
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    // "Hey Google, play <song> on Ronin Music"
                    val q = query?.trim().orEmpty()
                    SitePlayer.play(this@CarMusicService, if (q.isEmpty()) "resume" else "https://music.youtube.com/search?q=" + android.net.Uri.encode(q))
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        SitePlayer.onState = { t, a, p -> show(t, a, p) }
        show(SitePlayer.nowTitle, SitePlayer.nowArtist, SitePlayer.isPlaying)
    }

    private fun show(title: String, artist: String, playing: Boolean) {
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifBlank { "Ronin Music" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist).build())
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
            .build())
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT) { result.sendResult(mutableListOf()); return }
        result.sendResult(ITEMS.map { (id, name, _) ->
            MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder().setMediaId(id).setTitle(name).build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }.toMutableList())
    }

    override fun onDestroy() {
        SitePlayer.onState = null
        session.release()
        super.onDestroy()
    }

    companion object {
        const val ROOT = "ronin-music-root"
        /** id, name shown in the car, what plays. */
        val ITEMS = listOf(
            Triple("resume", "Continue playing", "resume"),
            Triple("liked", "Liked songs", "https://music.youtube.com/watch?list=LM"),
            Triple("supermix", "My Supermix", "https://music.youtube.com/watch?list=RTM"),
            Triple("hindi", "Hindi hits", "https://music.youtube.com/search?q=hindi+hits"),
        )
    }
}
