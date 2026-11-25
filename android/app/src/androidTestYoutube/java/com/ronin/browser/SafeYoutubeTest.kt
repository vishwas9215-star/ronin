package com.ronin.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bug 25-Nov (owner): vulgar Shorts on Ronin YouTube home -> Restricted Mode always on, no Shorts. */
@RunWith(AndroidJUnit4::class)
class SafeYoutubeTest {
    @Test fun restrictedModeOnAndNoShorts() {
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync { SitePlayer.page(ins.targetContext) }
        Thread.sleep(20000)
        val done = CountDownLatch(1); var r: String? = null
        ins.runOnMainSync {
            SitePlayer.eval("""String((function(){
              var m = document.cookie.match(/f2=([0-9a-f]+)/); var rm = !!m && (parseInt(m[1],16) & 0x8000000) !== 0;
              var st = document.getElementById('ronin-safe');
              var shelves = Array.prototype.filter.call(document.querySelectorAll('ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model'), function(e){ return e.offsetHeight > 0; }).length;
              return rm && !!st && shelves === 0;
            })())""") { r = it; done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals("\"true\"", r)
    }
}
