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
    private var sid = ""
    private var k = ByteArray(0)
    private var seqIn = 0L
    private var seqOut = 0L
    private val alias = "ronin-agent-$name"

    private fun log(m: String) = Log.i("RoninAgent", "link: $m")

    // ---------- keys ----------
    private val secret: String get() = prefs.getString("deviceKey", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }.also { prefs.edit().putString("deviceKey", it).apply() }

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
        try { gen(true); prefs.edit().putString("keyProtection", "StrongBox").apply() }
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
    private fun http(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 15_000): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Device $secret")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return code to text
    }

    /** Registers this device while the server's owner has opened pairing. Saves the other devices' public keys. */
    fun pair(): Boolean {
        val (code, text) = http("POST", "/agent/pair", JSONObject().put("name", name).put("key", secret).put("pub", publicPem()))
        if (code != 200) { log("pair refused ($code)"); return false }
        val got = JSONObject(text).optString("relay_pub")
        if (got.trim() != relayPubPem.trim()) { log("pair: NOT our relay"); return false }
        JSONObject(text).optJSONObject("peers")?.let { p -> for (n in p.keys()) if (n != name) rememberPeer(n, p.getString(n)) }
        prefs.edit().putBoolean("paired", true).apply()
        return true
    }

    private fun hello(): Boolean {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val eph = gen.generateKeyPair()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val my = Base64.encodeToString(rawPoint(eph.public as ECPublicKey), Base64.NO_WRAP)
        val (code, text) = http("POST", "/agent/hello", JSONObject().put("nonce", nonce).put("ecdh", my))
        if (code != 200) { log("hello refused ($code)"); return false }
        val m = JSONObject(text)
        val theirs = m.getString("ecdh")
        if (!verify(relayPubPem, "relay|$nonce|$my|$theirs", m.getString("proof"))) { log("FAKE relay: stopping"); running = false; return false }
        val ka = KeyAgreement.getInstance("ECDH").apply { init(eph.private); doPhase(fromRaw(Base64.decode(theirs, Base64.DEFAULT), eph.public as ECPublicKey), true) }
        k = java.security.MessageDigest.getInstance("SHA-256").digest(ka.generateSecret() + nonce.toByteArray())
        sid = m.getString("session"); seqIn = 0; seqOut = 0
        send(JSONObject().put("hello", JSONObject().put("kind", name).put("app", "Ronin").put("foreground", true).put("rendering", true).put("key", prefs.getString("keyProtection", "?"))))
        online = true
        log("online")
        return true
    }

    private fun send(m: JSONObject) {
        val f = m.toString(); seqOut++
        http("POST", "/agent/out?s=$sid", JSONObject().put("f", f).put("seq", seqOut).put("mac", hmac(k, "$seqOut|$f")))
    }

    // ---------- the loop ----------
    fun start() {
        if (running) return
        running = true
        thread(name = "ronin-agent-link", isDaemon = true) {
            var backoff = 1000L
            while (running) {
                try {
                    if (!online && !hello()) { Thread.sleep(backoff); backoff = minOf(backoff * 2, 30_000); continue }
                    backoff = 1000
                    val (code, text) = http("GET", "/agent/next?s=$sid", null, 35_000)
                    when (code) {
                        200 -> handle(JSONObject(text))
                        204 -> {}
                        else -> online = false
                    }
                } catch (e: Exception) { online = false; log("network: ${e.javaClass.simpleName}"); Thread.sleep(backoff); backoff = minOf(backoff * 2, 30_000) }
            }
        }
    }

    fun stop() { running = false }

    private fun handle(fr: JSONObject) {
        val f = fr.getString("f"); val seq = fr.getLong("seq")
        if (seq <= seqIn || fr.getString("mac") != hmac(k, "$seq|$f")) { log("bad frame dropped"); return }
        seqIn = seq
        val c = JSONObject(f)
        val id = c.getString("id"); val tool = c.getString("tool"); val args = c.optJSONObject("args") ?: JSONObject()
        val result = when (tool) {
            "__approve" -> {
                val text = "Connect Claude to your Ronin? Code ${args.optString("code")}. Devices: ${args.optString("scope")}"
                val epoch = prefs.getLong("epoch", 0); val issued = System.currentTimeMillis()
                if (!driver.stopped && approve(text)) JSONObject().put("ok", true).put("epoch", epoch).put("issued", issued)
                    .put("sig", sign(listOf("approve", args.optString("nonce"), args.optString("client"), args.optString("redirect"), args.optString("challenge"), args.optString("scope"), args.optString("code"), epoch, issued).joinToString("|")))
                else JSONObject().put("ok", false)
            }
            "__stop" -> { driver.stopped = true; JSONObject().put("ok", true) }
            "__epoch" -> {
                val by = args.optString("by"); val e = args.optLong("epoch"); val pub = prefs.getString("peer_$by", null)
                if (pub != null && verify(pub, "epoch|$e", args.optString("sig")) && e > prefs.getLong("epoch", 0)) prefs.edit().putLong("epoch", e).apply()
                JSONObject().put("ok", true)
            }
            else -> {
                val why = grantProblem(c.optJSONObject("grant"))
                if (why != null) JSONObject().put("error", why) else driver.run(id, tool, args)
            }
        }
        send(JSONObject().put("id", id).put("result", result))
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

    /** "Disconnect Claude" pressed on THIS device: new epoch at once (no network needed), told to the others, signed. */
    fun disconnectClaude() {
        val e = prefs.getLong("epoch", 0) + 1
        prefs.edit().putLong("epoch", e).apply()
        thread { try { send(JSONObject().put("disconnectClaude", true).put("epoch", e).put("sig", sign("epoch|$e"))) } catch (_: Exception) {} }
    }

    fun rememberPeer(dev: String, pem: String) { prefs.edit().putString("peer_$dev", pem).apply() }
}
