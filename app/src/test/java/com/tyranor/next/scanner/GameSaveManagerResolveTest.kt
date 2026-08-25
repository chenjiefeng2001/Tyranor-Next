package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import com.tyranor.next.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameSaveManagerResolveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication() as Context

    private fun game(engine: EngineType, root: File) = ScanGame(
        title = root.name,
        uri = Uri.fromFile(root).toString(),
        engine = engine,
        launchTarget = "",
    )

    private fun newGameDir(name: String): File =
        temporaryFolder.newFolder(name)

    private fun assertSameDirectory(expected: File, actual: File?) {
        assertEquals(expected.canonicalPath, actual?.canonicalPath)
    }

    @Test
    fun kirikiriScopedDefaultMirrorsIntoInternalSavedata() {
        EngineSettingsStore.setKrKernel(context, EngineSettingsStore.KERNEL_KIRIKIRI2)
        val root = newGameDir("Kr Game")

        val location = GameSaveManager(context).resolveSaveLocation(game(EngineType.KIRIKIRI, root))

        assertTrue(location.available)
        assertSameDirectory(
            File(File(File(context.filesDir, "krkr_mirror"), "Kr Game"), "savedata"),
            location.directory,
        )
    }

    @Test
    fun kirikiriUnscopedFallsBackToGameDirectory() {
        EngineSettingsStore.setKrScopedSaveDir(context, false)
        val root = newGameDir("Kr Loose")

        val location = GameSaveManager(context).resolveSaveLocation(game(EngineType.KIRIKIRI, root))

        assertTrue(location.available)
        assertSameDirectory(File(root, "savedata"), location.directory)
    }

    @Test
    fun tyranoScopedUsesExternalTyranoFolder() {
        val root = newGameDir("Ty Game")

        val location = GameSaveManager(context).resolveSaveLocation(game(EngineType.TYRANO, root))

        assertTrue(location.available)
        val external = requireNotNull(context.getExternalFilesDir(null))
        assertSameDirectory(
            File(File(File(external, "save"), "tyrano"), "Ty Game"),
            location.directory,
        )
    }

    @Test
    fun onsScopedUsesExternalFolderNamedAfterDirectory() {
        val root = newGameDir("Ons Game")

        val location = GameSaveManager(context).resolveSaveLocation(game(EngineType.ONS, root))

        assertTrue(location.available)
        val external = requireNotNull(context.getExternalFilesDir(null))
        assertSameDirectory(
            File(File(external, "save"), "Ons Game"),
            location.directory,
        )
    }

    @Test
    fun rpgMakerEnginesShareTyranoScopedLayout() {
        val root = newGameDir("Mv Game")

        val mv = GameSaveManager(context).resolveSaveLocation(game(EngineType.RPG_MV, root))
        val mz = GameSaveManager(context).resolveSaveLocation(game(EngineType.RPG_MZ, root))

        assertTrue(mv.available)
        assertEquals(mv.directory?.canonicalPath, mz.directory?.canonicalPath)
    }

    @Test
    fun artemisSavesLiveInsideGameRoot() {
        val root = newGameDir("Art Game")

        val location = GameSaveManager(context).resolveSaveLocation(game(EngineType.ARTEMIS, root))

        assertTrue(location.available)
        assertSameDirectory(root, location.directory)
    }

    @Test
    fun enginesWithoutFileSaveInterfaceAreUnavailable() {
        for (engine in listOf(EngineType.VN, EngineType.WEB_OTHER, EngineType.UNKNOWN)) {
            val root = newGameDir("NoSave-${engine.name}")

            val location = GameSaveManager(context).resolveSaveLocation(game(engine, root))

            assertFalse("engine=$engine should be unavailable", location.available)
            assertNull(location.directory)
        }
    }

    @Test
    fun unresolvableUriReportsUnavailable() {
        val game = ScanGame(
            title = "remote",
            uri = "https://example.com/game",
            engine = EngineType.TYRANO,
            launchTarget = "",
        )

        val location = GameSaveManager(context).resolveSaveLocation(game)

        assertFalse(location.available)
        assertNull(location.directory)
    }
}
