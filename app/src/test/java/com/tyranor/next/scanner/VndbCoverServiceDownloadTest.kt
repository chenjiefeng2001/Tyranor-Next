package com.tyranor.next.scanner

import android.content.Context
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C-3 回归护栏（audit/13 §二）：封面下载必须「临时文件 + 成功后原子改名」。
 *
 * 锁定三条不变量：
 * 1. 失败（HTTP 错误 / 流中断 / 超限）后目录内**既无最终 .jpg 也无 .part 残留**——
 *    回退为直写最终路径时，流中断会留下半截图片被缓存命中永久返回坏图；
 * 2. 缓存命中短路不发起网络请求；
 * 3. 文件名派生规则（vndb_<sk(uri)>_<sk(url)>）保持稳定，避免升级后缓存失联。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VndbCoverServiceDownloadTest {

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private val requestCount = AtomicInteger(0)
    private val serverExecutor = Executors.newFixedThreadPool(2)

    private val gameUri = "/storage/0000-0000/game"
    private val coverPath = "/cover.jpg"

    private lateinit var game: ScanGame

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        game = ScanGame(
            title = "t",
            uri = gameUri,
            engine = EngineType.VN,
            launchTarget = "",
        )
        requestCount.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext(coverPath) { exchange -> serve(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        serverExecutor.shutdownNow()
    }

    /** 可编程响应：null 表示只声明 Content-Length 后中断连接（模拟下载中途断流）。 */
    private var responder: ((HttpExchange) -> Unit)? = null

    private fun serve(exchange: HttpExchange) {
        requestCount.incrementAndGet()
        try {
            val handler = responder ?: error("responder not configured")
            handler(exchange)
        } catch (_: Exception) {
            // 连接层异常留给客户端感知，服务端吞掉即可
        } finally {
            exchange.close()
        }
    }

    private val coverUrl: String get() = "http://127.0.0.1:${server.address.port}$coverPath"

    private fun candidate(url: String = coverUrl) = VndbCandidate(
        id = "v17",
        title = "t",
        originalTitle = "",
        developer = "",
        released = "",
        coverUrl = url,
    )

    private fun coversDir(): File = File(context.filesDir, "covers_remote")

    private fun partFiles(): List<File> =
        coversDir().listFiles { f -> f.name.endsWith(".part") }?.toList() ?: emptyList()

    private fun targetFile(): File {
        val name = "vndb_${stableKey(gameUri)}_${stableKey(coverUrl)}.jpg"
        return File(coversDir(), name)
    }

    /** 与生产实现独立的 SHA-1 基准实现：锁定文件名派生契约。 */
    private fun stableKey(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    @Test(timeout = 30_000L)
    fun successfulDownloadWritesTargetAndLeavesNoPartFile() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        responder = { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val updated = VndbCoverService.bindCandidate(context, game, candidate())
        assertTrue(updated != null)
        assertEquals(fileUri(targetFile()), updated!!.coverUri)
        assertArrayEquals(payload, targetFile().readBytes())
        assertTrue("成功路径不应有 .part 残留", partFiles().isEmpty())
    }

    @Test(timeout = 30_000L)
    fun midStreamDisconnectLeavesNoCacheEntryNorPartFile() {
        val declared = 256 * 1024
        val partial = ByteArray(32 * 1024)
        responder = { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            // 声明完整长度但只写一小段后中断连接：客户端读到 premature EOF
            exchange.sendResponseHeaders(200, declared.toLong())
            exchange.responseBody.use { it.write(partial) }
            throw java.io.IOException("simulated mid-stream disconnect")
        }
        val updated = VndbCoverService.bindCandidate(context, game, candidate())
        assertNull("断流下载应失败返回 null", updated)
        assertFalse("半截文件不得进入缓存位", targetFile().exists())
        assertTrue("中断后不得遗留 .part 文件，实际 ${partFiles().map { it.name }}", partFiles().isEmpty())
    }

    @Test(timeout = 30_000L)
    fun httpErrorWritesNothing() {
        responder = { exchange ->
            exchange.sendResponseHeaders(500, -1)
        }
        val updated = VndbCoverService.bindCandidate(context, game, candidate())
        assertNull(updated)
        assertFalse(targetFile().exists())
        assertTrue(partFiles().isEmpty())
        assertEquals(1, requestCount.get())
    }

    @Test(timeout = 30_000L)
    fun oversizeContentLengthIsRejectedBeforeDownload() {
        responder = { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, 21L * 1024L * 1024L)
            exchange.responseBody.use { it.write(ByteArray(1024)) }
        }
        val updated = VndbCoverService.bindCandidate(context, game, candidate())
        assertNull("超限封面应在读取前拒绝", updated)
        assertFalse(targetFile().exists())
        assertTrue(partFiles().isEmpty())
    }

    @Test(timeout = 30_000L)
    fun cacheHitShortCircuitsWithoutNetworkRequest() {
        val dir = coversDir().apply { mkdirs() }
        val marker = ByteArray(128) { 7 }
        val existing = targetFile().apply { writeBytes(marker) }
        val before = requestCount.get()
        val updated = VndbCoverService.bindCandidate(context, game, candidate())
        assertTrue(updated != null)
        assertEquals(fileUri(existing), updated!!.coverUri)
        assertArrayEquals("已有缓存不得被覆写", marker, existing.readBytes())
        assertEquals("缓存命中不应发起网络请求", before, requestCount.get())
    }

    private fun fileUri(f: File): String = android.net.Uri.fromFile(f).toString()
}
