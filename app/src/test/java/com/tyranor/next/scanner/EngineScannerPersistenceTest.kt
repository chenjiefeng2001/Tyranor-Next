package com.tyranor.next.scanner

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineScannerPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        resetCaches()
    }

    private fun resetCaches() {
        val instance = GameStore::class.java.getDeclaredField("INSTANCE").get(null)
        for (name in listOf("gamesCache", "recentGamesCache", "quickLaunchCache")) {
            GameStore::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(instance, null)
        }
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences("game_scanner", Context.MODE_PRIVATE)

    @Test
    fun saveThenLoadRoundTripsAllFields() {
        val game = ScanGame(
            title = "Fate/stay night",
            uri = "/storage/emulated/0/Fate",
            engine = EngineType.KIRIKIRI,
            launchTarget = "start.tjs",
            coverUri = "content://cover",
            vndbId = "v7",
            metadataTitle = "meta title",
            launchFile = "data.xp3",
            openTime = 1724572800000,
        )

        EngineScanner.saveGames(context, listOf(game))
        resetCaches()
        val loaded = EngineScanner.loadGames(context)

        assertEquals(listOf(game), loaded)
    }

    @Test
    fun sanitizeTitleContainingSeparatorsOnPersist() {
        val dirty = ScanGame(
            title = "line1\nline2\u0001col",
            uri = "/storage/emulated/0/G",
            engine = EngineType.TYRANO,
            launchTarget = "index.html",
            metadataTitle = "meta\nvalue\u0001x",
        )

        EngineScanner.saveRecentGames(context, listOf(dirty))
        resetCaches()
        val loaded = EngineScanner.loadRecentGames(context)

        assertEquals(1, loaded.size)
        assertEquals("line1 line2 col", loaded[0].title)
        assertEquals("meta value x", loaded[0].metadataTitle)
        assertEquals("/storage/emulated/0/G", loaded[0].uri)
    }

    @Test
    fun optionalFieldsSurviveEmptyRoundTrip() {
        val minimal = ScanGame(
            title = "t",
            uri = "/g",
            engine = EngineType.ARTEMIS,
            launchTarget = "",
        )

        EngineScanner.saveGames(context, listOf(minimal))
        resetCaches()
        val loaded = EngineScanner.loadGames(context)

        assertEquals(minimal.copy(coverUri = null, vndbId = null, metadataTitle = null), loaded[0])
        assertNull(loaded[0].vndbId)
        assertNull(loaded[0].launchFile)
    }

    @Test
    fun malformedLinesAreDroppedAndUnknownEngineFallsBack() {
        val raw = listOf(
            "only-two\u0001fields",
            "Valid\u0001/g\u0001NOT_AN_ENGINE\u0001target",
            "Ok\u0001/g2\u0001ONS\u0001t\u0001\u0001\u0001\u0001not-a-number",
        ).joinToString("\n")
        prefs().edit().putString("scan_games", raw).apply()

        val loaded = EngineScanner.loadGames(context)

        assertEquals(2, loaded.size)
        assertEquals(EngineType.UNKNOWN, loaded[0].engine)
        assertEquals("Valid", loaded[0].title)
        val ons = loaded[1]
        assertEquals(EngineType.ONS, ons.engine)
        assertEquals(0, ons.openTime)
        assertNull(ons.coverUri)
    }

    @Test
    fun quickLaunchPersistsIndependentlyFromLibrary() {
        val game = ScanGame(title = "q", uri = "/q", engine = EngineType.ONS, launchTarget = "default.exe")

        assertTrue(EngineScanner.addQuickLaunch(context, game))
        resetCaches()
        assertEquals(listOf(game), EngineScanner.loadQuickLaunch(context))

        EngineScanner.removeQuickLaunch(context, game.uri)
        resetCaches()
        assertTrue(EngineScanner.loadQuickLaunch(context).isEmpty())
    }

    @Test
    fun removeGameDropsOnlyMatchingUri() {
        val a = ScanGame(title = "a", uri = "/a", engine = EngineType.VN, launchTarget = "")
        val b = ScanGame(title = "b", uri = "/b", engine = EngineType.VN, launchTarget = "")

        EngineScanner.saveGames(context, listOf(a, b))
        EngineScanner.removeGame(context, a.uri)
        resetCaches()

        assertEquals(listOf(b), EngineScanner.loadGames(context))
    }
}
