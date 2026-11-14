package com.ronin.browser

/**
 * Ronin Mail and Ronin Social: the family's email / social apps as apps of their own. Same guarded
 * Safe Social tab as in the browser, but in their own window (own task, own entry in Recents), so
 * Back and the Recents screen behave like any other app, never jumping into the browser.
 */
class MailActivity : MainActivity() {
    override val appMode = "mail"
}

class SocialActivity : MainActivity() {
    override val appMode = "social"
}
