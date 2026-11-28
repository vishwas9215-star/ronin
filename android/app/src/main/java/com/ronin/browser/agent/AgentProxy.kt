package com.ronin.browser.agent

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every connection of the Claude tab goes through this small local proxy, which checks the address it ACTUALLY
 * connects to (after DNS) and connects to exactly that address. So a name that answers "public" to one lookup and
 * "192.168.1.1" to the next (DNS rebinding), a WebSocket, a service worker or a page script can never reach the
 * home network, this phone or the router. (The same as the laptop's agentcore.Proxy.)
 * Pure Java sockets: tested on the JVM.
 */
class AgentProxy(
    private val blocked: (String) -> Unit = {},
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val allow: (ByteArray) -> Boolean = { !AgentRules.privateAddress(it) },
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "ronin-proxy").apply { isDaemon = true } }
    val refused = AtomicInteger(0)
    var port = 0
        private set

    fun start(): Int {
        val s = ServerSocket()
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { serve(c) }
            }
        }
        return port
    }

    fun stop() { try { server?.close() } catch (e: Exception) {} }

    /** The checked address for host:port, or null (refused). All answers must be public: one bad answer = refused. */
    fun target(host: String, port: Int): InetSocketAddress? {
        val h = host.trim('[', ']')
        val addrs = try { resolve(h) } catch (e: Exception) { return null }
        if (addrs.isEmpty() || addrs.any { !allow(it.address) }) { refused.incrementAndGet(); blocked("$h:$port"); return null }
        return InetSocketAddress(addrs[0], port)
    }

    private fun readHead(inp: InputStream): String? {
        val b = StringBuilder()
        var last4 = 0
        while (b.length < 65536) {
            val c = inp.read()
            if (c < 0) return null
            b.append(c.toChar())
            last4 = (last4 shl 8) or c
            if (last4 == 0x0d0a0d0a) return b.toString()
        }
        return null
    }

    private fun refuse(out: OutputStream) {
        try { out.write("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: 16\r\nConnection: close\r\n\r\nBlocked by Ronin".toByteArray()); out.flush() } catch (e: Exception) {}
    }

    private fun serve(c: Socket) {
        c.use { client ->
            client.soTimeout = 60_000
            val inp = client.getInputStream(); val out = client.getOutputStream()
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 3) return
            val (method, dest) = parts[0] to parts[1]
            if (method == "CONNECT") { // HTTPS and secure WebSockets: a tunnel to the checked address
                val host = dest.substringBeforeLast(':'); val port = dest.substringAfterLast(':').toIntOrNull() ?: 443
                val addr = target(host, port) ?: return refuse(out)
                val up = try { Socket().apply { connect(addr, 20_000) } } catch (e: Exception) { return refuse(out) }
                up.use {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); out.flush()
                    pipe(client, up)
                }
                return
            }
            // plain http:// (and ws://): one request per connection, sent to the checked address
            val u = try { URI(dest) } catch (e: Exception) { return refuse(out) }
            if (u.scheme != "http" || u.host.isNullOrEmpty()) return refuse(out)
            val port = if (u.port > 0) u.port else 80
            val addr = target(u.host, port) ?: return refuse(out)
            val path = (u.rawPath ?: "/").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            val ws = lines.any { it.startsWith("Upgrade:", true) && it.contains("websocket", true) }
            val sb = StringBuilder("$method $path ${parts[2]}\r\n")
            for (l in lines.drop(1)) {
                if (l.isEmpty()) continue
                val k = l.substringBefore(':').trim().lowercase()
                if (k == "proxy-connection" || k == "proxy-authorization" || (!ws && (k == "connection" || k == "keep-alive"))) continue
                sb.append(l).append("\r\n")
            }
            if (!ws) sb.append("Connection: close\r\n") // the next request may be for another site: a new check
            sb.append("\r\n")
            val up = try { Socket().apply { connect(addr, 20_000) } } catch (e: Exception) { return refuse(out) }
            up.use {
                it.getOutputStream().write(sb.toString().toByteArray()); it.getOutputStream().flush()
                pipe(client, it)
            }
        }
    }

    private fun pipe(a: Socket, b: Socket) {
        val t = Thread({ try { a.getInputStream().copyTo(b.getOutputStream()) } catch (e: Exception) {} ; try { b.shutdownOutput() } catch (e: Exception) {} }, "ronin-proxy-up")
        t.isDaemon = true; t.start()
        try { b.getInputStream().copyTo(a.getOutputStream()) } catch (e: Exception) {}
        try { a.shutdownOutput() } catch (e: Exception) {}
        t.join(60_000)
    }
}
