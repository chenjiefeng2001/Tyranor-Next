package com.tyranor.next.scanner

import com.tyranor.next.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineLauncherPureTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun game(
        launchTarget: String = "",
        launchFile: String? = null,
    ) = ScanGame(
        title = "t",
        uri = "/g",
        engine = EngineType.KIRIKIRI,
        launchTarget = launchTarget,
        launchFile = launchFile,
    )

    // ---------- parseStoragePath ----------

    @Test
    fun parsesPrimaryRootWithoutRelativePart() {
        assertEquals("primary" to "", EngineLauncher.parseStoragePath("/storage/emulated/0"))
        assertEquals("primary" to "", EngineLauncher.parseStoragePath("/sdcard"))
    }

    @Test
    fun parsesPrimaryWithRelativePath() {
        assertEquals(
            "primary" to "Games/Fate",
            EngineLauncher.parseStoragePath("/storage/emulated/0/Games/Fate"),
        )
        assertEquals("primary" to "Games", EngineLauncher.parseStoragePath("/sdcard/Games"))
    }

    @Test
    fun parsesRemovableVolume() {
        assertEquals(
            "0000-0001" to "Games",
            EngineLauncher.parseStoragePath("/storage/0000-0001/Games"),
        )
    }

    @Test
    fun rejectsMalformedStoragePaths() {
        assertNull(EngineLauncher.parseStoragePath("/storage/"))
        assertNull(EngineLauncher.parseStoragePath("C:/Games"))
        assertNull(EngineLauncher.parseStoragePath(""))
    }

    // ---------- normalizeKrkrsdl3Renderer ----------

    @Test
    fun normalizesRendererAliases() {
        assertEquals(EngineSettingsStore.RENDERER_OPENGL, EngineLauncher.normalizeKrkrsdl3Renderer("gl"))
        assertEquals(EngineSettingsStore.RENDERER_OPENGL, EngineLauncher.normalizeKrkrsdl3Renderer(" GPU "))
        assertEquals(EngineSettingsStore.RENDERER_SOFTWARE, EngineLauncher.normalizeKrkrsdl3Renderer("sw"))
    }

    @Test
    fun fallsBackToSoftwareForUnknownRenderer() {
        assertEquals(EngineSettingsStore.RENDERER_SOFTWARE, EngineLauncher.normalizeKrkrsdl3Renderer("vulkan"))
        assertEquals(EngineSettingsStore.RENDERER_SOFTWARE, EngineLauncher.normalizeKrkrsdl3Renderer(""))
    }

    // ---------- safeSharpnessValue ----------

    @Test
    fun acceptsSharpnessWithinBounds() {
        assertEquals("1.5", EngineLauncher.safeSharpnessValue("1.5"))
        assertEquals("0.1", EngineLauncher.safeSharpnessValue(" 0.1 "))
        assertEquals("10.0", EngineLauncher.safeSharpnessValue("10.0"))
    }

    @Test
    fun fallsBackToDefaultSharpnessOnInvalidInput() {
        for (input in listOf("", "abc", "NaN", "Infinity", "0", "-3", "10.01")) {
            assertEquals("input=$input", "2", EngineLauncher.safeSharpnessValue(input))
        }
    }

    // ---------- pickKrActivateEntry ----------

    private fun newDir(vararg entries: String): File {
        val root = temporaryFolder.newFolder("kr-${System.nanoTime()}")
        for (entry in entries) {
            val target = File(root, entry)
            if (entry.endsWith("/") || entry.endsWith("\\")) target.mkdirs() else target.writeText("x")
        }
        return root
    }

    @Test
    fun manualLaunchFileWinsOverEverything() {
        val root = newDir("data.xp3", "custom.xp3")
        assertEquals(
            File(root, "custom.xp3").absolutePath,
            EngineLauncher.pickKrActivateEntry(root.absolutePath, game(launchFile = "custom.xp3")),
        )
    }

    @Test
    fun preferredScriptArchiveMatchesCaseInsensitively() {
        val root = newDir("bgnoise.xp3", "Data.XP3")
        assertEquals(
            File(root, "Data.XP3").absolutePath,
            EngineLauncher.pickKrActivateEntry(root.absolutePath, game()),
        )
    }

    @Test
    fun preferredListOrderIsRespected() {
        val root = newDir("startup.tjs", "main.xp3")
        assertEquals(
            File(root, "main.xp3").absolutePath,
            EngineLauncher.pickKrActivateEntry(root.absolutePath, game()),
        )
    }

    @Test
    fun nonAssetLaunchTargetUsedWhenFileExists() {
        val root = newDir("scene.xp3")
        assertEquals(
            File(root, "scene.xp3").absolutePath,
            EngineLauncher.pickKrActivateEntry(root.absolutePath, game(launchTarget = "scene.xp3")),
        )
    }

    @Test
    fun bgPrefixedTargetAndArchivesAreSkippedThenDirectoryFallback() {
        val root = newDir("bgmovie.xp3")
        assertEquals(
            root.absolutePath,
            EngineLauncher.pickKrActivateEntry(root.absolutePath, game(launchTarget = "bgmovie.xp3")),
        )
    }
}
