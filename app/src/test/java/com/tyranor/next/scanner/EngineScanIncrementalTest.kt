package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 增量扫描剪枝与全量重建合并语义（扫描性能优化 P1 的正确性前提）。
 * Robolectric 下 DocumentFile.fromTreeUri 无 provider 支撑 → 走 File 回退遍历路径，
 * 与真机 SAF 路径共享同一套剪枝/合并逻辑分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineScanIncrementalTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        GameStoreTestUtil.resetCaches()
        GameStore.saveGames(context, emptyList())
        GameStoreTestUtil.resetCaches()
    }

    private fun newRoot(name: String): File = temporaryFolder.newFolder(name)

    private fun makeGameDir(root: File, name: String, vararg files: String): File {
        val dir = File(root, name).apply { mkdirs() }
        for (f in files) File(dir, f).writeText("fixture")
        return dir
    }

    private fun seed(vararg games: ScanGame) {
        GameStore.saveGames(context, games.toList())
        GameStoreTestUtil.resetCaches()
    }

    private fun registerRoot(root: File) {
        EngineScanner.saveRoot(context, Uri.parse(root.absolutePath))
    }

    private fun incremental() = runBlocking { EngineScanner.incrementalScan(context) }

    private fun rescan() = runBlocking { EngineScanner.rescanLibrary(context) }

    // ---------- T1 已知游戏剪枝 ----------

    @Test
    fun knownGameDirectoriesArePrunedAndPassedThroughUntouched() {
        val root = newRoot("lib")
        val g1 = makeGameDir(root, "KnownGame", "data.xp3")
        val g2 = makeGameDir(root, "FreshGame", "data.xp3")
        registerRoot(root)

        seed(
            ScanGame(
                title = "磁盘上的旧名",
                uri = g1.absolutePath,
                engine = EngineType.KIRIKIRI,
                launchTarget = "KEEP-MARKER",
                openTime = 42,
            ),
        )

        val result = incremental()

        assertEquals(2, result.size)
        val known = result.first { it.uri == g1.absolutePath }
        // 剪枝生效：已知条目原样透传，未被磁盘重新识别覆盖
        assertEquals("KEEP-MARKER", known.launchTarget)
        assertEquals("磁盘上的旧名", known.title)
        assertEquals(42, known.openTime)

        val fresh = result.firstOrNull { it.uri == g2.absolutePath }
        assertNotNull("新游戏应被发现", fresh)
        assertEquals(EngineType.KIRIKIRI, fresh!!.engine)
        assertEquals("data.xp3", fresh.launchTarget)
    }

    @Test
    fun nestedNewGamesAreFoundUnderUnexploredParents() {
        val root = newRoot("lib2")
        val nestedParent = File(root, "collection").apply { mkdirs() }
        val deepGame = makeGameDir(nestedParent, "DeepGame", "startup.tjs")
        registerRoot(root)

        val result = incremental()

        assertEquals(listOf(deepGame.absolutePath), result.map { it.uri })
        assertEquals(EngineType.KIRIKIRI, result[0].engine)
        // 非 xp3 启动标记（startup.tjs）不充当 launchTarget，保持目录占位
        assertEquals("[游戏目录]", result[0].launchTarget)
    }

    // ---------- T2 已删除游戏保留 ----------

    @Test
    fun deletedGamesAreKeptByIncrementalScan() {
        val root = newRoot("lib3")
        val alive = makeGameDir(root, "Alive", "data.xp3")
        val ghostUri = temporaryFolder.root.resolve("lib3/Ghost").absolutePath
        registerRoot(root)

        seed(
            ScanGame(title = "alive", uri = alive.absolutePath, engine = EngineType.KIRIKIRI, launchTarget = ""),
            ScanGame(title = "ghost", uri = ghostUri, engine = EngineType.ONS, launchTarget = ""),
        )

        val result = incremental()

        assertEquals(setOf(alive.absolutePath, ghostUri), result.map { it.uri }.toSet())
    }

    // ---------- T3 rescanLibrary 全量重建合并语义 ----------

    @Test
    fun rescanDropsStaleEntriesButPreservesUserMetadataForSurvivors() {
        val root = newRoot("lib4")
        val g1 = makeGameDir(root, "Survivor", "data.xp3", "cover.jpg")
        val g2 = makeGameDir(root, "Newcomer", "nscript.dat", "icon.png")
        val ghost = root.resolve("Ghost").absolutePath
        registerRoot(root)

        seed(
            ScanGame(
                title = "old-title",
                uri = g1.absolutePath,
                engine = EngineType.KIRIKIRI,
                launchTarget = "old-target",
                coverUri = "/custom/vndb-cover.webp",
                vndbId = "v17",
                metadataTitle = "meta",
                launchFile = "manual.xp3",
                openTime = 777,
            ),
            ScanGame(title = "stale", uri = ghost, engine = EngineType.ONS, launchTarget = ""),
        )

        val result = rescan()

        assertEquals("已删除条目被全量重建清除", setOf(g1.absolutePath, g2.absolutePath), result.map { it.uri }.toSet())

        val survivor = result.first { it.uri == g1.absolutePath }
        assertEquals("用户元数据保留：vndbId", "v17", survivor.vndbId)
        assertEquals("用户元数据保留：metadataTitle", "meta", survivor.metadataTitle)
        assertEquals("用户元数据保留：launchFile", "manual.xp3", survivor.launchFile)
        assertEquals("用户元数据保留：openTime", 777, survivor.openTime)
        assertEquals("已有封面优先于本地发现", "/custom/vndb-cover.webp", survivor.coverUri)
        assertEquals("启动目标刷新为磁盘识别值", "data.xp3", survivor.launchTarget)

        val newcomer = result.first { it.uri == g2.absolutePath }
        assertNull(newcomer.vndbId)
        assertTrue("新游戏发现本地封面", newcomer.coverUri?.endsWith("icon.png") == true)
    }
}
