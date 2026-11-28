package com.ronin.browser.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** What the link hands Claude's commands to: one page (a driver) or all the tabs. */
interface AgentRunner {
    fun run(id: String, tool: String, args: JSONObject): JSONObject
    var stopped: Boolean
}

/**
 * Several tabs in Ronin Agent (owner, 28 Nov): 🤖 C's tabs (up to [maxClaude]) and 👤 the owner's own tabs.
 *  - C works only in C's tabs; the owner's tabs are never listed to C, nor reachable, until he taps "C को दें".
 *  - Without `tab`, a command goes to the MAIN C tab (t1), never to another one: two Claude chats can't mix up.
 *  - open new_tab=true makes a new C tab and answers its id; every later call for that work passes it.
 *  - A tab that is not on screen keeps working (background-fill / background-press, page kept awake).
 * PAUSE / STOP / RESUME apply to all of C's tabs. [makeTab] creates a page + its driver on the main thread.
 */
class AgentTabs(
    private val makeTab: (claude: Boolean) -> AgentDriver?,
    private val first: AgentDriver,
    /** the Census app helper on this phone (null in tests / on devices without it) */
    private val census: CensusBridge? = null,
    val maxClaude: Int = 4,
    val maxAll: Int = 8,
) : AgentRunner {
    class Tab(val id: String, val driver: AgentDriver, @Volatile var claude: Boolean)

    val tabs = CopyOnWriteArrayList(listOf(Tab("t1", first, true)))
    @Volatile var shown = "t1"
    private val doneApp = object : LinkedHashMap<String, JSONObject>() { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, JSONObject>?) = size > 100 }
    private val newTabFor = object : LinkedHashMap<String, String>() { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 50 }
    @Volatile private var seq = 1
    /** the tabs or the shown tab changed (UI) */
    var onChanged: (() -> Unit)? = null

    val main: Tab get() = tabs.first { it.id == "t1" }
    fun tab(id: String) = tabs.firstOrNull { it.id == id }
    fun claudeCount() = tabs.count { it.claude }

    override var stopped: Boolean
        get() = main.driver.stopped
        set(v) { tabs.forEach { it.driver.stopped = v } }
    val paused get() = main.driver.paused
    val ownerHolding get() = tabs.any { it.driver.ownerHolding }
    fun ownerPause() = tabs.forEach { it.driver.ownerPause() }
    fun resume() = tabs.forEach { it.driver.resume() }
    fun ownerStop() = tabs.forEach { it.driver.ownerStop() }
    fun ownerDone() = tabs.forEach { if (it.driver.ownerHolding) it.driver.ownerDone() }

    /** A new tab. Claude's: refused past [maxClaude]. Returns null when full. */
    @Synchronized fun add(claude: Boolean): Tab? {
        if (tabs.size >= maxAll || (claude && claudeCount() >= maxClaude)) return null
        val d = makeTab(claude) ?: return null
        d.stopped = main.driver.stopped; if (main.driver.paused) d.ownerPause()
        val t = Tab("t${++seq}", d, claude)
        tabs.add(t)
        onChanged?.invoke()
        return t
    }

    /** Close a tab (never the main one). */
    @Synchronized fun close(id: String): Boolean {
        val t = tab(id) ?: return false
        if (t.id == "t1") return false
        tabs.remove(t)
        if (shown == id) shown = "t1"
        onChanged?.invoke()
        return true
    }

    /** The owner shares his tab with C ("C को दें") or takes it back ("वापस लें"). The main tab is always C's. */
    @Synchronized fun setClaude(id: String, on: Boolean): Boolean {
        val t = tab(id) ?: return false
        if (t.id == "t1" || t.claude == on) return t.claude == on
        if (on && claudeCount() >= maxClaude) return false
        t.claude = on
        onChanged?.invoke()
        return true
    }

    fun show(id: String) { if (tab(id) != null) { shown = id; onChanged?.invoke() } }

    override fun run(id: String, tool: String, args: JSONObject): JSONObject {
        if (tool in CensusBridge.TOOLS) {
            // PAUSE / STOP of the owner hold here too
            if (main.driver.stopped) return JSONObject().put("error", "the owner pressed STOP on this device. Claude must wait until he turns Claude on again.")
            if (main.driver.paused) return JSONObject().put("error", "the owner pressed PAUSE. Claude waits until he taps RESUME.")
            val c = census ?: return JSONObject().put("error", "no census helper on this device")
            synchronized(doneApp) { doneApp[id] }?.let { return it } // the same command again: never a second fill / press
            return c.run(tool, args).also { r -> synchronized(doneApp) { doneApp[id] = r } }
        }
        if (tool == "tabs") {
            args.optString("close").takeIf { it.isNotEmpty() }?.let { c ->
                val t = tab(c)
                if (t == null || !t.claude) return JSONObject().put("error", "no such Claude tab: $c")
                if (c == "t1") return JSONObject().put("error", "the main Claude tab stays open")
                close(c)
            }
            val list = JSONArray()
            for (t in tabs) if (t.claude) list.put(JSONObject().put("tab", t.id).put("url", t.driver.currentUrl).put("main", t.id == "t1").put("on_screen", t.id == shown))
            return JSONObject().put("tabs", list).put("max", maxClaude)
                .put("note", "the owner's own tabs are not shown; pass tab=<id> to work in one of these")
        }
        if (tool == "open" && args.optBoolean("new_tab")) {
            if (main.driver.stopped) return main.driver.run(id, tool, args) // the same STOP answer
            // the same command again (its answer was lost on the way): the same tab, never a second new one
            val t = synchronized(newTabFor) { newTabFor[id]?.let { tab(it) } ?: add(true)?.also { newTabFor[id] = it.id } }
                ?: return JSONObject().put("error", "Claude already has $maxClaude tabs on this device: close one first (tabs close=<id>)")
            return t.driver.run(id, tool, args).put("tab", t.id)
        }
        val want = args.optString("tab").ifEmpty { "t1" }
        val t = tab(want) ?: return JSONObject().put("error", "no such tab: $want (call tabs)")
        if (!t.claude) return JSONObject().put("error", "tab $want is the owner's own: Claude may not use it")
        return t.driver.run(id, tool, args).put("tab", t.id)
    }
}
