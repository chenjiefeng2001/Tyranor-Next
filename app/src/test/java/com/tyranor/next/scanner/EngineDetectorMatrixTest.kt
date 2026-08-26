package com.tyranor.next.scanner

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineDetectorMatrixTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun detect(vararg layout: String): EngineScanner.Detection {
        val root = temporaryFolder.newFolder("game-${System.nanoTime()}")
        for (entry in layout) {
            val target = File(root, entry)
            if (entry.endsWith("/")) target.mkdirs() else {
                target.parentFile?.mkdirs()
                target.writeText("fixture")
            }
        }
        return EngineScanner.detectEngine(root)
    }

    private data class Expected(val engine: EngineType, val confidence: Int)

    private fun assertDetects(expected: EngineType, confidence: Int, vararg layout: String) {
        val d = detect(*layout)
        assertEquals("$layout engine", expected, d.engine)
        assertEquals("$layout confidence", confidence, d.confidence)
    }

    // ---------- Artemis（最高优先级） ----------

    @Test
    fun artemisViaSystemIniAndFirstIet() {
        assertDetects(EngineType.ARTEMIS, 95, "system.ini", "system/first.iet")
    }

    @Test
    fun artemisViaRootPfsBeatsTyranoMarkers() {
        assertDetects(EngineType.ARTEMIS, 95, "root.pfs", "index.html", "tyrano/")
    }

    @Test
    fun artemisViaAnyPfsGetsLowerConfidence() {
        assertDetects(EngineType.ARTEMIS, 90, "scene.pfs")
    }

    // ---------- Tyrano ----------

    @Test
    fun tyranoViaIndexAndTyranoDirectory() {
        assertDetects(EngineType.TYRANO, 95, "index.html", "tyrano/data.ks")
    }

    @Test
    fun tyranoBeatsRpgMakerWhenBothPresent() {
        assertDetects(EngineType.TYRANO, 95, "index.html", "tyrano/", "www/js/rpg_core.js")
    }

    @Test
    fun tyranoFromAppAsarDowngradedConfidence() {
        assertDetects(EngineType.TYRANO, 80, "app.asar")
    }

    // ---------- RPG Maker / VN / Web ----------

    @Test
    fun rpgMvViaWwwJsCore() {
        assertDetects(EngineType.RPG_MV, 95, "index.html", "www/js/rpg_core.js")
    }

    @Test
    fun rpgMzViaWwwJsCore() {
        assertDetects(EngineType.RPG_MZ, 95, "index.html", "www/js/rmmz_core.js")
    }

    @Test
    fun vnViaIndexAndVnData() {
        assertDetects(EngineType.VN, 90, "index.html", "globalData.vndata")
    }

    @Test
    fun webOtherForPlainIndex() {
        assertDetects(EngineType.WEB_OTHER, 70, "index.html")
    }

    // ---------- Kirikiri / ONS ----------

    @Test
    fun kirikiriViaXp3ReportsArchiveAsTarget() {
        val d = detect("data.xp3")
        assertEquals(EngineType.KIRIKIRI, d.engine)
        assertEquals(95, d.confidence)
        assertEquals("data.xp3", d.launchTarget)
    }

    @Test
    fun kirikiriPreservesRealCaseLaunchTargetForCaseSensitiveStorage() {
        // 上游 issue #34：大写目录/文件名曾被小写化存储，导致大小写敏感设备无法回配启动文件
        val d = detect("DATA.XP3")
        assertEquals(EngineType.KIRIKIRI, d.engine)
        assertEquals("DATA.XP3", d.launchTarget)

        val nested = detect("data/SCENE.XP3")
        assertEquals(EngineType.KIRIKIRI, nested.engine)
        assertEquals("data/SCENE.XP3", nested.launchTarget)
    }

    @Test
    fun kirikiriViaStartupTjsOnlyGetsLowerConfidence() {
        assertDetects(EngineType.KIRIKIRI, 80, "startup.tjs")
    }

    @Test
    fun kirikiriBeatsOnsWhenBothMarkerFamiliesExist() {
        val d = detect("data.xp3", "nscript.dat")
        assertEquals(EngineType.KIRIKIRI, d.engine)
    }

    // ---------- ONS ----------

    @Test
    fun onsViaScriptMarker() {
        for (marker in listOf("nscript.dat", "00.txt", "onscript.nt3")) {
            val d = detect(marker)
            assertEquals("marker=$marker", EngineType.ONS, d.engine)
            assertEquals("marker=$marker", 90, d.confidence)
        }
    }

    @Test
    fun onsViaArchiveOnlyGetsLowerConfidence() {
        assertDetects(EngineType.ONS, 70, "bgm.sar")
    }

    // ---------- UNKNOWN ----------

    @Test
    fun unknownForEmptyOrIrrelevantDirectory() {
        assertEquals(Expected(EngineType.UNKNOWN, 0).engine, detect("readme.md").engine)
        assertEquals(0, detect("readme.md").confidence)
    }

    // ---------- DocumentFile 变体（真机 SAF 路径） ----------

    private fun detectViaDocumentFile(vararg layout: String): EngineScanner.Detection {
        val root = temporaryFolder.newFolder("game-df-${System.nanoTime()}")
        for (entry in layout) {
            val target = File(root, entry)
            if (entry.endsWith("/")) target.mkdirs() else {
                target.parentFile?.mkdirs()
                target.writeText("fixture")
            }
        }
        return EngineScanner.detectEngine(androidx.documentfile.provider.DocumentFile.fromFile(root))
    }

    @Test
    fun documentFileVariantMatchesFileVariantOnKeyBranches() {
        assertEquals(EngineType.ARTEMIS, detectViaDocumentFile("root.pfs", "index.html", "tyrano/").engine)
        assertEquals(95, detectViaDocumentFile("root.pfs", "index.html", "tyrano/").confidence)

        assertEquals(EngineType.TYRANO, detectViaDocumentFile("index.html", "tyrano/data.ks").engine)

        val mv = detectViaDocumentFile("index.html", "www/js/rpg_core.js")
        assertEquals(EngineType.RPG_MV, mv.engine)
        assertEquals(95, mv.confidence)

        assertEquals(EngineType.TYRANO, detectViaDocumentFile("app.asar").engine)
        assertEquals(80, detectViaDocumentFile("app.asar").confidence)

        val kr = detectViaDocumentFile("data.xp3")
        assertEquals(EngineType.KIRIKIRI, kr.engine)
        assertEquals("data.xp3", kr.launchTarget)

        val unknown = detectViaDocumentFile("readme.md")
        assertEquals(EngineType.UNKNOWN, unknown.engine)
        assertEquals(0, unknown.confidence)
    }
}
