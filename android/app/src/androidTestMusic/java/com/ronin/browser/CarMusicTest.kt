package com.ronin.browser

import android.content.ComponentName
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What Android Auto does: connect to Ronin Music, read the list, pick an item (the page must start in the background). */
@RunWith(AndroidJUnit4::class)
class CarMusicTest {
    @Test fun carListAndPlayFromTheCar() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var browser: MediaBrowserCompat
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            browser = MediaBrowserCompat(ctx, ComponentName(ctx, CarMusicService::class.java), object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() { connected.countDown() }
            }, null)
            browser.connect()
        }
        assertTrue("car could not connect to Ronin Music", connected.await(20, TimeUnit.SECONDS))
        val loaded = CountDownLatch(1)
        var items: List<MediaBrowserCompat.MediaItem> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            browser.subscribe(browser.root, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) { items = children; loaded.countDown() }
            })
        }
        assertTrue(loaded.await(20, TimeUnit.SECONDS))
        assertEquals(listOf("Continue playing", "Liked songs", "My Supermix", "Hindi hits"), items.map { it.description.title.toString() })
        assertTrue(items.all { it.isPlayable })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MediaControllerCompat(ctx, browser.sessionToken).transportControls.playFromMediaId("hindi", null)
        }
        Thread.sleep(8000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("the page did not start in the background", SitePlayer.hasPage)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { browser.disconnect() }
    }
}
