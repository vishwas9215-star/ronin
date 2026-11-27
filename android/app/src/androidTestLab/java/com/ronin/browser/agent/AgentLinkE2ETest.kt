package com.ronin.browser.agent

import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * End to end, as in real life: this test plays CLAUDE (custom connector: register, OAuth 2.1 + PKCE, MCP tools)
 * against the real Go relay running on the CI machine (10.0.2.2), which talks to the lab app on this emulator
 * through the device link (relay key proof, ECDH + HMAC frames, the owner's signed grant).
 * Runs only when the CI passes relayUrl + relayPub.
 */
@RunWith(AndroidJUnit4::class)
class AgentLinkE2ETest {
    private val args = InstrumentationRegistry.getArguments()
    private val relay = args.getString("relayUrl") ?: ""
    private var token = ""
    private var rpc = 0

    private fun http(method: String, path: String, body: String? = null, type: String = "application/json", auth: String? = null): Triple<Int, String, String?> {
        val c = URL(relay + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 120_000
        auth?.let { c.setRequestProperty("Authorization", it) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        return Triple(code, text, c.getHeaderField("Location"))
    }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun mcp(method: String, params: JSONObject = JSONObject()): Pair<Int, JSONObject> {
        val (code, text, _) = http("POST", "/mcp", JSONObject().put("jsonrpc", "2.0").put("id", ++rpc).put("method", method).put("params", params).toString(), auth = "Bearer $token")
        return code to (if (code == 200) JSONObject(text) else JSONObject())
    }

    private fun tool(name: String, vararg kv: Pair<String, Any>): JSONObject {
        val a = JSONObject(); for ((k, v) in kv) a.put(k, v)
        val (_, r) = mcp("tools/call", JSONObject().put("name", name).put("arguments", a))
        val t = r.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        Log.i("RoninAgent", "E2E $name -> ${t.take(160)}")
        return JSONObject(t)
    }

    @Test fun claudeDrivesTheLabAppThroughTheRelay() {
        assumeTrue("needs the CI relay", relay.isNotEmpty())
        val pub = String(Base64.decode(args.getString("relayPub") ?: "", Base64.DEFAULT))
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, AgentActivity::class.java)
            .putExtra("relayUrl", relay).putExtra("relayPub", pub).putExtra("autoApprove", true)
        val scenario = ActivityScenario.launch<AgentActivity>(intent)
        try {
            var w = 0
            while ((AgentActivity.link?.online != true) && w < 120) { Thread.sleep(500); w++ }
            assertTrue("device link online (relay key proof + ECDH)", AgentActivity.link?.online == true)
            val d = AgentActivity.driver!!
            d.testPages.clear(); d.testPages.putAll(AgentTestPages.all)

            // ---- Claude signs in (custom connector) ----
            val (rc, rt, _) = http("POST", "/register", """{"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}""")
            assertEquals(201, rc)
            val client = JSONObject(rt).getString("client_id")
            val verifier = b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val q = "response_type=code&client_id=$client&redirect_uri=" + URLEncoder.encode("https://claude.ai/api/mcp/auth_callback", "UTF-8") +
                "&code_challenge=$challenge&code_challenge_method=S256&state=s1"
            // the sign-in page shows the code and refreshes itself until the owner held Allow on the device
            var (ac, page, loc) = http("GET", "/authorize?$q")
            var n = 0
            while (ac == 200 && n < 120) {
                val next = Regex("url=(/authorize/wait\\?t=[^\"]+)").find(page)?.groupValues?.get(1) ?: break
                Thread.sleep(500); n++
                val r = http("GET", next); ac = r.first; page = r.second; loc = r.third
            }
            assertEquals("authorize redirects back to Claude: $page", 302, ac)
            val code = Regex("code=([^&]+)").find(loc ?: "")?.groupValues?.get(1)
            assertTrue("the device-signed Allow gave a code: $loc", code != null)
            val form = "grant_type=authorization_code&code=$code&client_id=$client&redirect_uri=" + URLEncoder.encode("https://claude.ai/api/mcp/auth_callback", "UTF-8") + "&code_verifier=$verifier"
            val (tc, tt, _) = http("POST", "/token", form, "application/x-www-form-urlencoded")
            assertEquals(200, tc)
            token = JSONObject(tt).getString("access_token")
            // a second sign-in (a stranger's Claude, say) gets no card: the owner's tap was used up by the first one
            val (sc, sp, _) = http("GET", "/authorize?$q")
            assertTrue("no window = no card: $sp", sc == 200 && sp.contains("Claude जोड़ें") && !sp.contains("authorize/wait"))

            // ---- Claude works ----
            assertTrue(mcp("tools/list").second.getJSONObject("result").getJSONArray("tools").length() >= 16)
            val dev = AgentActivity.link!!.let { if (it.online) (tool("devices").getJSONArray("devices").getJSONObject(0).getString("device")) else "phone" }
            tool("open", "device" to dev, "url" to "https://epds.example.gov.in/")
            val snap = tool("snapshot", "device" to dev)
            val els: JSONArray = snap.getJSONArray("elements")
            fun ref(re: String): String { for (i in 0 until els.length()) if (Regex(re).containsMatchIn(els.getString(i))) return Regex("\\[(e\\d+)]").find(els.getString(i))!!.groupValues[1]; error(re) }
            val t = tool("type", "device" to dev, "ref" to ref("Employee name"), "text" to "Ramesh Kumar")
            assertEquals("$t", "verified", t.optString("check"))
            tool("select", "device" to dev, "ref" to ref("select"), "option" to "Head Master")
            tool("click", "device" to dev, "ref" to ref("button \"Save\""))
            Thread.sleep(500)
            assertEquals("the form was saved once, through Claude -> relay -> device", "1", d.pageValue("window.saves"))
            tool("open", "device" to dev, "url" to "https://web.dhan.co/")
            assertTrue(tool("click", "device" to dev, "ref" to "e1").optString("error").contains("READ-ONLY"))

            // ---- the owner disconnects Claude: tokens die, and old grants are refused on the device itself ----
            AgentActivity.link!!.disconnectClaude()
            Thread.sleep(1500)
            assertEquals(401, mcp("tools/list").first)
            Log.i("RoninAgent", "MECH e2e PASS")
        } finally { scenario.close() }
    }
}
