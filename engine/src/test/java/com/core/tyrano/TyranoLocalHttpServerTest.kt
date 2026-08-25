package com.core.tyrano

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Socket

class TyranoLocalHttpServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: TyranoLocalHttpServer
    private var started = false

    @Before
    fun setUp() {
        started = false
    }

    @After
    fun tearDown() {
        if (started) server.stop()
    }

    private fun start(
        hook: String = "",
        injectBeforeBody: Boolean = false,
        scriptAppends: Map<String, ByteArray> = emptyMap(),
        files: Map<String, String> = emptyMap(),
    ): Int {
        val root = temporaryFolder.newFolder("web-${System.nanoTime()}")
        for ((name, content) in files) {
            val target = File(root, name)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        server = TyranoLocalHttpServer(
            root,
            null,
            if (hook.isEmpty()) null else hook.toByteArray(),
            injectBeforeBody,
            scriptAppends,
        )
        server.start()
        started = true
        return server.port
    }

    private fun get(port: Int, path: String, rangeHeader: String? = null): Pair<String, String> {
        Socket("127.0.0.1", port).use { socket ->
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                if (rangeHeader != null) append("Range: $rangeHeader\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val raw = socket.getInputStream().readBytes()
            val separator = raw.indexOf("\r\n\r\n")
            val head = String(raw.copyOf(separator))
            val body = String(raw.copyOfRange(separator + 4, raw.size))
            return head to body
        }
    }

    private fun ByteArray.indexOf(pattern: String): Int {
        val needle = pattern.toByteArray()
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Test
    fun injectsHookScriptBeforeHeadClose() {
        val port = start(
            hook = "window.__hook__ = true;",
            files = mapOf("index.html" to "<html><head></head><body>x</body></html>"),
        )

        val (head, body) = get(port, "/index.html")

        assertTrue(head.startsWith("HTTP/1.1 200 OK"))
        val injected = "\n<script type='text/javascript'>\nwindow.__hook__ = true;\n</script>\n"
        assertEquals("<html><head>$injected</head><body>x</body></html>", body)
        assertTrue(body.indexOf(injected) < body.indexOf("</head>", body.indexOf(injected)))
    }

    @Test
    fun injectsHookBeforeBodyCloseWhenConfigured() {
        val port = start(
            hook = "var b = 1;",
            injectBeforeBody = true,
            files = mapOf("index.html" to "<html><head></head><body>x</body></html>"),
        )

        val (_, body) = get(port, "/index.html")

        val injected = "\n<script type='text/javascript'>\nvar b = 1;\n</script>\n"
        assertEquals("<html><head></head><body>x$injected</body></html>", body)
    }

    @Test
    fun appendsConfiguredScriptToMatchingJsRequests() {
        val port = start(
            scriptAppends = mapOf("data.js" to "\n;window.__appended__ = 1;".toByteArray()),
            files = mapOf("data.js" to "window.__base__ = 0;"),
        )

        val (head, body) = get(port, "/data.js")

        assertTrue(head.contains("200 OK"))
        assertEquals("window.__base__ = 0;\n;window.__appended__ = 1;", body)
    }

    @Test
    fun servesPartialContentForRangeRequests() {
        val payload = (0 until 100).joinToString("") { it.toString() }
        val port = start(files = mapOf("movie.bin" to payload))

        val (head, body) = get(port, "/movie.bin", rangeHeader = "bytes=10-19")

        assertTrue(head.startsWith("HTTP/1.1 206 Partial Content"))
        assertTrue(head.contains("Content-Range: bytes 10-19/${payload.length}"))
        assertEquals(payload.substring(10, 20), body)
    }

    @Test
    fun rejectsPathTraversalWith404() {
        temporaryFolder.newFile("outside-secret.txt").writeText("top secret")
        val port = start(files = mapOf("index.html" to "<html><body>i</body></html>"))

        val (head, _) = get(port, "/../outside-secret.txt")

        assertTrue(head.startsWith("HTTP/1.1 404 Not Found"))
    }

    @Test
    fun stopReleasesTheListeningPort() {
        val port = start(hook = "", files = mapOf("index.html" to "ok"))

        val (head, _) = get(port, "/index.html")
        assertTrue(head.startsWith("HTTP/1.1 200 OK"))

        server.stop()
        started = false

        assertThrows(IOException::class.java) { get(port, "/index.html") }
    }
}
