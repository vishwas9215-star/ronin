package com.ronin.browser.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** The phone / tablet proxy: home network and this device are refused for http, https tunnels and WebSockets. */
class AgentProxyTest {
    private fun inside(): ServerSocket {
        val s = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { c.use { it.getInputStream().read(ByteArray(4096)); it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nrouter".toByteArray()) } }
            }
        }
        return s
    }

    private fun ask(port: Int, req: String): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(req.toByteArray())
        s.getInputStream().readBytes().decodeToString()
    }

    @Test fun refusesThisDeviceAndTheHomeNetworkWhateverTheName() {
        val box = inside()
        val p = AgentProxy()
        val port = p.start()
        val r1 = ask(port, "GET http://127.0.0.1:${box.localPort}/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        val r2 = ask(port, "GET http://localhost:${box.localPort}/ HTTP/1.1\r\nHost: localhost\r\n\r\n")   // a name that resolves inside
        val r3 = ask(port, "CONNECT 127.0.0.1:${box.localPort} HTTP/1.1\r\nHost: x\r\n\r\n")
        for (r in listOf(r1, r2, r3)) assertTrue(r, r.startsWith("HTTP/1.1 403") && !r.contains("router"))
        assertEquals(3, p.refused.get())
        // rebinding: the first answer public, a second one private -> refused (all answers must be public)
        val q = AgentProxy(resolve = { arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1")) })
        assertTrue(q.target("evil.example", 443) == null)
        p.stop(); box.close()
    }

    @Test fun passesAllowedAddressesAndRewritesTheRequest() {
        val box = inside()
        val p = AgentProxy(allow = { true })
        val port = p.start()
        val r = ask(port, "GET http://127.0.0.1:${box.localPort}/x?a=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Connection: keep-alive\r\n\r\n")
        assertTrue(r, r.contains("router"))
        p.stop(); box.close()
    }
}
