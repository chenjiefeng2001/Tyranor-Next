package com.tyranor.next.scanner

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** GameStore 锁与缓存在并发读写下的行为压测（死锁/丢写可见性哨兵）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStoreConcurrencyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        GameStoreTestUtil.resetCaches()
        GameStore.saveGames(context, emptyList())
        GameStoreTestUtil.resetCaches()
    }

    @Test
    fun concurrentWritersAndReadersDoNotDeadlockOrCorrupt() {
        val threads = 8
        val iterations = 60
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = pool.let { p ->
            val futures = (0 until threads).map { t ->
                p.submit(Callable {
                    ready.countDown()
                    ready.await(5, TimeUnit.SECONDS)
                    repeat(iterations) { i ->
                        val g = ScanGame(
                            title = "t-$t-$i",
                            uri = "/uri-$t-$i",
                            engine = EngineType.VN,
                            launchTarget = "",
                        )
                        GameStore.saveGames(context, listOf(g))
                        // 读者路径：触发缓存命中或磁盘回读
                        GameStore.loadGames(context)
                        if (i % 7 == 0) {
                            GameStore.recordRecentGame(context, g)
                            GameStore.loadRecentGames(context)
                        }
                    }
                    true
                })
            }
            p.shutdown()
            p.awaitTermination(60, TimeUnit.SECONDS)
            futures.map { it.get(30, TimeUnit.SECONDS) }
        }

        assertTrue("所有写入线程应正常完成（无死锁/无异常）", done.all { it })
        val finalGames = GameStore.loadGames(context)
        assertEquals("最后一条写入应完整可见", 1, finalGames.size)
        assertTrue(finalGames[0].uri.startsWith("/uri-"))
    }

    private class Callable<T>(private val block: () -> T) : java.util.concurrent.Callable<T> {
        override fun call(): T = block()
    }
}
