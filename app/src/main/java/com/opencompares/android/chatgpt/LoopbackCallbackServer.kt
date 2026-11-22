package com.opencompares.android.chatgpt

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A one-shot HTTP listener on 127.0.0.1 that receives OpenAI's redirect after sign-in.
 *
 * OpenAI only documents loopback redirects for this flow, so the app briefly runs this
 * server while the user signs in in the browser. It answers exactly one request to the
 * callback path and ignores everything else (favicon probes and the like).
 */
class LoopbackCallbackServer private constructor(private val socket: ServerSocket) : Closeable {

    val port: Int get() = socket.localPort

    /**
     * Waits for the callback and returns its query parameters. [page] renders the HTML the
     * browser shows; it gets the same parameters so it can say whether sign-in was denied.
     * Cancelling the coroutine closes the socket.
     */
    suspend fun awaitCallback(page: (Map<String, String>) -> String): Map<String, String> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { close() }
            thread(name = "siwc-callback", isDaemon = true) {
                try {
                    while (true) {
                        val client = socket.accept()
                        val params = client.use { handle(it, page) }
                        if (params != null) {
                            if (cont.isActive) cont.resume(params)
                            return@thread
                        }
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    close()
                }
            }
        }

    /** Returns the callback's parameters, or null if this request wasn't the callback. */
    private fun handle(client: Socket, page: (Map<String, String>) -> String): Map<String, String>? {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return null
        // Skip headers; the request line holds everything needed.
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") {
            respond(client, 405, "text/plain", "Method not allowed")
            return null
        }
        val uri = runCatching { URI(parts[1]) }.getOrNull()
        if (uri == null || uri.path != SiwcConfig.CALLBACK_PATH) {
            respond(client, 404, "text/plain", "Not found")
            return null
        }
        val params = parseQuery(uri.rawQuery)
        respond(client, 200, "text/html; charset=utf-8", page(params))
        return params
    }

    private fun respond(client: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByName(SiwcConfig.CALLBACK_HOST)

        /** Binds the preferred port, or any free port if it's taken; only the port may vary. */
        fun start(preferredPort: Int = SiwcConfig.PREFERRED_PORT): LoopbackCallbackServer {
            val socket = try {
                ServerSocket(preferredPort, 4, LOOPBACK)
            } catch (_: BindException) {
                ServerSocket(0, 4, LOOPBACK)
            }
            return LoopbackCallbackServer(socket)
        }

        /** The page the browser shows after the redirect, with a way back into the app. */
        fun callbackPage(params: Map<String, String>, returnUri: String): String {
            val denied = params.containsKey("error")
            val title = if (denied) "Sign-in cancelled" else "Almost done"
            val body = if (denied) "ChatGPT sign-in was cancelled." else "Return to Open Compares to finish signing in."
            return """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$title</title>
                <style>body{font-family:sans-serif;text-align:center;padding:48px 24px;color:#1d1b20}
                a{display:inline-block;margin-top:24px;padding:12px 24px;border-radius:24px;
                background:#6750a4;color:#fff;text-decoration:none;font-weight:600}</style></head>
                <body><h2>$title</h2><p>$body</p>
                <a href="$returnUri">Return to Open Compares</a>
                <script>setTimeout(function(){location.href="$returnUri";},300);</script>
                </body></html>
            """.trimIndent()
        }
    }
}
