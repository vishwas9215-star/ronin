package com.ronin.browser.agent

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The device side of the Ronin Agent link: HTTPS long polling to the owner's relay.
 *  - the relay must prove it is OUR relay (its public key is baked into the app) before anything runs;
 *  - every frame has a sequence number + HMAC of the session key (ECDH P-256);
 *  - every command must carry the owner's grant, signed on one of HIS devices, with an epoch not revoked;
 *  - the device's own signing key is a non-exportable P-256 key in Android Keystore (StrongBox when there is one).
 */
class AgentLink(
    private val base: String,               // https://relay.example
    private val relayPubPem: String,        // baked into the app at build time
    private val name: String,               // "tablet" / "phone"
    private val prefs: SharedPreferences,
    private val driver: AgentDriver,
    private val approve: (String) -> Boolean, // the native "Connect Claude?" card (press and hold)
) {
    @Volatile var running = false
    @Volatile var online = false
    private val lock = Any()            // session key, id and sequence numbers (held only for moments, never over the network)
    private val postLock = Any()        // one POST /agent/out at a time, in sequence order
    private var sid = ""
    private var k = ByteArray(0)
    private var seqIn = 0L
    private var seqOut = 0L
    private val generation = java.util.concurrent.atomic.AtomicInteger(0) // Stop / Start: an old loop sees a new number and ends
    private val alias = "ronin-agent-$name"
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ronin-agent-work").apply { isDaemon = true } }
    /** "Claude जोड़ें": only while the owner opened it on THIS device (3 minutes) may a Connect card show here. */
    @Volatile var connectUntil = 0L

    private fun log(m: String) = Log.i("RoninAgent", "link: $m")
    private fun now() = System.currentTimeMillis()

    // ---------- keys ----------
    // the device's name tag at the relay. Not enough on its own: every hello is also signed by the Keystore key.
    // (allowBackup=false: this file never leaves the device in a backup)
    private val secret: String get() = prefs.getString("deviceKey", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }.also { prefs.edit().putString("deviceKey", it).commit() }

    private fun signingKey(): PrivateKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? PrivateKey)?.let { return it }
        fun gen(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
        }
        try { gen(true); prefs.edit().putString("keyProtection", if (Build.VERSION.SDK_INT >= 28) "StrongBox" else "Keystore").apply() }
        catch (e: Exception) { gen(false); prefs.edit().putString("keyProtection", "TEE").apply() }
        return ks.getKey(alias, null) as PrivateKey
    }

    fun publicPem(): String {
        signingKey()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val der = ks.getCertificate(alias).publicKey.encoded
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(der, Base64.DEFAULT) + "-----END PUBLIC KEY-----\n"
    }

    private fun sign(text: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(signingKey()); update(text.toByteArray()); Base64.encodeToString(sign(), Base64.NO_WRAP)
    }

    companion object {
        val PEER_NAMES = setOf("phone", "tablet")
        fun pemToKey(pem: String): PublicKey {
            val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)))
        }
        fun verify(pem: String, text: String, sigB64: String): Boolean = try {
            Signature.getInstance("SHA256withECDSA").run { initVerify(pemToKey(pem)); update(text.toByteArray()); verify(Base64.decode(sigB64, Base64.DEFAULT)) }
        } catch (e: Exception) { false }
        fun rawPoint(pub: ECPublicKey): ByteArray {
            fun fix(b: BigInteger): ByteArray { val a = b.toByteArray(); return when { a.size == 32 -> a; a.size > 32 -> a.copyOfRange(a.size - 32, a.size); else -> ByteArray(32 - a.size) + a } }
            return byteArrayOf(4) + fix(pub.w.affineX) + fix(pub.w.affineY)
        }
        fun fromRaw(raw: ByteArray, like: ECPublicKey): PublicKey {
            val x = BigInteger(1, raw.copyOfRange(1, 33)); val y = BigInteger(1, raw.copyOfRange(33, 65))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), like.params))
        }
        fun hmac(k: ByteArray, s: String): String = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); Base64.encodeToString(doFinal(s.toByteArray()), Base64.NO_WRAP) }
    }

    // ---------- HTTP ----------
    private fun http(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 15_000, auth: Boolean = true): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = timeoutMs
        if (auth) c.setRequestProperty("Authorization", "Device $secret")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return code to text
    }

    /** Registers this device while the server's owner has opened pairing. The relay must prove it is OURS first. */
    fun pair(): Boolean = try { pairOnce() } catch (e: Exception) { log("pair: ${e.javaClass.simpleName}"); false }

    private fun pairOnce(): Boolean {
        // is this our relay? (its public key is baked into the app) - checked before the device's name tag is sent.
        // A man in the middle could still copy that page; he gains nothing: every hello needs the Keystore signature.
        val (wc, wt) = try { http("GET", "/.well-known/ronin-relay.json", auth = false) } catch (e: Exception) { return false }
        if (wc != 200 || JSONObject(wt).optString("pub").trim() != relayPubPem.trim()) { log("pair: NOT our relay"); return false }
        val (code, text) = http("POST", "/agent/pair", JSONObject().put("name", name).put("key", secret).put("pub", publicPem()))
        if (code != 200) { log("pair refused ($code)"); return false }
        val got = JSONObject(text).optString("relay_pub")
        if (got.trim() != relayPubPem.trim()) { log("pair: NOT our relay"); return false }
        JSONObject(text).optJSONObject("peers")?.let { p -> for (n in p.keys()) if (n != name && n in PEER_NAMES && prefs.getString("peer_$n", null) == null) rememberPeer(n, p.getString(n)) }
        prefs.edit().putBoolean("paired", true).commit()
        return true
    }

    private fun hello(gen: Int): Boolean {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val my = Base64.encodeToString(rawPoint(eph.public as ECPublicKey), Base64.NO_WRAP)
        // the secret names the device; the signature (non-exportable Keystore key) proves it is really this device
        val (code, text) = http("POST", "/agent/hello", JSONObject().put("nonce", nonce).put("ecdh", my).put("sig", sign("devhello|$nonce|$my")))
        if (code != 200) { log("hello refused ($code)"); return false }
        val m = JSONObject(text)
        val theirs = m.getString("ecdh")
        if (!verify(relayPubPem, "relay|$nonce|$my|$theirs", m.getString("proof"))) { log("FAKE relay: stopping"); running = false; return false }
        val ka = KeyAgreement.getInstance("ECDH").apply { init(eph.private); doPhase(fromRaw(Base64.decode(theirs, Base64.DEFAULT), eph.public as ECPublicKey), true) }
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(ka.generateSecret() + nonce.toByteArray())
        // other devices: a NEW name is learnt only while the owner has "Claude जोड़ें" open here; a known one never changes
        // (pilot: only "phone" / "tablet" exist; a "laptop" is learnt once the Windows agent ships, so a stranger who
        // paired that name while pairing was open is never trusted)
        if (now() < connectUntil) learnPeers(text)
        // a "Disconnect Claude" pressed on another device while this one was offline
        m.optJSONObject("epoch")?.let { applyEpoch(it.optString("by"), it.optLong("epoch"), it.optString("sig")) }
        synchronized(lock) {
            if (gen != generation.get()) return false
            sid = m.getString("session"); k = key; seqIn = 0; seqOut = 0
        }
        if (send(JSONObject().put("hello", JSONObject().put("kind", name).put("app", "Ronin").put("foreground", true).put("rendering", true).put("key", prefs.getString("keyProtection", "?")))) == null) return false
        prefs.getString("pendingDisconnect", null)?.let { if (send(JSONObject(it)) == null) return false; prefs.edit().remove("pendingDisconnect").commit() } // kept until really delivered
        if (now() < connectUntil) send(JSONObject().put("connectOpen", true))?.let { learnPeers(it) }
        online = true
        log("online")
        return true
    }

    /**
     * One POST at a time, in sequence order. The number + MAC are taken under [lock] for a moment; the network wait is
     * outside it, so STOP and frame checks never wait on the network. A failed POST is sent again unchanged (the relay
     * answers "done already" if the first one did arrive). Returns the relay's reply body, or null.
     */
    private fun send(m: JSONObject): String? = synchronized(postLock) {
        val f = m.toString()
        val (s, body) = synchronized(lock) { seqOut++; sid to JSONObject().put("f", f).put("seq", seqOut).put("mac", hmac(k, "$seqOut|$f")) }
        for (attempt in 0..1) {
            try { val (c, t) = http("POST", "/agent/out?s=$s", body); if (c in 200..299) return@synchronized t; if (c == 401 || c == 400) break } catch (e: Exception) { log("send: ${e.javaClass.simpleName}") }
        }
        online = false
        null
    }

    /** Other devices' keys: a NEW name only while the owner has "Claude जोड़ें" open here; a known one never changes. */
    private fun learnPeers(reply: String) {
        if (now() >= connectUntil) return
        val p = try { JSONObject(reply).optJSONObject("peers") } catch (e: Exception) { null } ?: return
        for (n in p.keys()) if (n != name && n in PEER_NAMES && prefs.getString("peer_$n", null) == null) { rememberPeer(n, p.getString(n)); log("learnt peer $n") }
    }

    // ---------- the loop ----------
    fun start() {
        val gen = synchronized(lock) { if (running) return; running = true; generation.incrementAndGet() }
        thread(name = "ronin-agent-link", isDaemon = true) {
            var backoff = 1000L
            while (running && gen == generation.get()) {
                try {
                    if (!online && !hello(gen)) { Thread.sleep(backoff); backoff = minOf(backoff * 2, 30_000); continue }
                    backoff = 1000
                    val s = synchronized(lock) { sid }
                    val (code, text) = http("GET", "/agent/next?s=$s", null, 35_000)
                    if (!running || gen != generation.get()) break // STOP pressed while waiting: nothing that arrived runs
                    when (code) {
                        200 -> handle(JSONObject(text))
                        204 -> {}
                        else -> online = false
                    }
                } catch (e: Exception) { online = false; log("network: ${e.javaClass.simpleName}"); Thread.sleep(backoff); backoff = minOf(backoff * 2, 30_000) }
            }
        }
    }

    fun stop() { running = false; generation.incrementAndGet(); online = false } // no lock: never waits on the network

    /** The owner tapped "Claude जोड़ें" here: for 3 minutes a Claude sign-in may ask him on THIS device. */
    fun openConnectWindow() {
        connectUntil = now() + 180_000
        if (online) thread { send(JSONObject().put("connectOpen", true))?.let { learnPeers(it) } }
    }

    private fun handle(fr: JSONObject) {
        val f = fr.optString("f"); val seq = fr.optLong("seq", -1)
        val c = synchronized(lock) {
            if (seq <= seqIn || fr.optString("mac") != hmac(k, "$seq|$f")) { log("bad frame dropped"); return }
            val parsed = try { JSONObject(f) } catch (e: Exception) { null }
            if (parsed == null || parsed.optString("id").isEmpty() || parsed.optString("tool").isEmpty()) { log("bad command dropped"); return }
            seqIn = seq // only a well-formed, authentic frame uses up its number
            parsed
        }
        val id = c.getString("id"); val tool = c.getString("tool"); val args = c.optJSONObject("args") ?: JSONObject()
        when (tool) {
            // the owner's STOP and a new epoch never wait behind a running command
            "__stop" -> { driver.stopped = true; send(answer(id, JSONObject().put("ok", true))) }
            "__epoch" -> { val ok = applyEpoch(args.optString("by"), args.optLong("epoch"), args.optString("sig")); send(answer(id, JSONObject().put("ok", ok).also { if (!ok) it.put("error", "unknown or wrong signer: open Claude जोड़ें on this device once") })) }
            "__approve" -> worker.execute { send(answer(id, approveCard(args))) }
            else -> worker.execute {
                val result = try {
                    if (!running) JSONObject().put("error", "the owner stopped Claude on this device")
                    else grantProblem(c.optJSONObject("grant"))?.let { JSONObject().put("error", it) } ?: driver.run(id, tool, args)
                } catch (t: Throwable) { JSONObject().put("error", (t.message ?: t.javaClass.simpleName).take(200)) }
                send(answer(id, result))
            }
        }
    }

    private fun answer(id: String, result: JSONObject) = JSONObject().put("id", id).put("result", result)

    /** The one-time card. Never shown unless the owner opened "Claude जोड़ें" here a moment ago (one card per tap). */
    private fun approveCard(args: JSONObject): JSONObject {
        if (driver.stopped || now() >= connectUntil) { log("Connect card refused: \"Claude जोड़ें\" was not open here"); return JSONObject().put("ok", false) }
        connectUntil = 0
        val text = "Claude को आपके Ronin से जोड़ें? Code ${args.optString("code")} (Claude के page पर भी यही code होना चाहिए). Devices: ${args.optString("scope")}"
        val epoch = prefs.getLong("epoch", 0); val issued = now()
        return if (approve(text)) JSONObject().put("ok", true).put("epoch", epoch).put("issued", issued)
            .put("sig", sign(listOf("approve", args.optString("nonce"), args.optString("client"), args.optString("redirect"), args.optString("challenge"), args.optString("scope"), args.optString("code"), epoch, issued).joinToString("|")))
        else JSONObject().put("ok", false)
    }

    /** A new epoch, signed by one of the owner's devices (this one or a known peer): grants older than it are dead. */
    private fun applyEpoch(by: String, e: Long, sig: String): Boolean {
        val pub = if (by == name) publicPem() else prefs.getString("peer_$by", null) ?: return false
        if (!verify(pub, "epoch|$e", sig)) return false
        if (e > prefs.getLong("epoch", 0)) { prefs.edit().putLong("epoch", e).commit(); log("epoch $e from $by") }
        return true
    }

    /** The owner's grant: signed by one of HIS paired devices, this device in scope, not revoked by "Disconnect Claude". */
    fun grantProblem(g: JSONObject?): String? {
        if (g == null) return "no owner grant"
        val dev = g.optString("dev")
        val pub = if (dev == name) publicPem() else prefs.getString("peer_$dev", null) ?: return "grant from an unknown device"
        if (name !in g.optString("scope").split(", ")) return "this device is not in the owner's grant"
        if (g.optLong("epoch") < prefs.getLong("epoch", 0)) return "Claude was disconnected by the owner: connect again"
        val text = listOf("approve", g.optString("nonce"), g.optString("client"), g.optString("redirect"), g.optString("challenge"), g.optString("scope"), g.optString("code"), g.optLong("epoch"), g.optLong("issued")).joinToString("|")
        return if (verify(pub, text, g.optString("sig"))) null else "the grant's signature is wrong"
    }

    /**
     * "Disconnect Claude" pressed on THIS device: a new epoch at once (no network needed), told to the others, signed.
     * The epoch is at least the clock time, so it is always above any grant made before it on any device.
     */
    fun disconnectClaude() {
        val e = maxOf(prefs.getLong("epoch", 0) + 1, now())
        prefs.edit().putLong("epoch", e).commit()
        val msg = JSONObject().put("disconnectClaude", true).put("epoch", e).put("sig", sign("epoch|$e"))
        prefs.edit().putString("pendingDisconnect", msg.toString()).commit() // sent again at the next hello if this fails
        thread { if (online && send(msg) != null) prefs.edit().remove("pendingDisconnect").commit() }
    }

    fun rememberPeer(dev: String, pem: String) { prefs.edit().putString("peer_$dev", pem).commit() }
}
