package com.tyranor.next.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class ArtemisPfsUnpackerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newGameDir(name: String): File = temporaryFolder.newFolder(name)

    private fun le(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun writePfs(dir: File, name: String, entries: List<Pair<String, ByteArray>>) {
        val encodedNames = entries.map { it.first.toByteArray(Charsets.UTF_8) }
        var tableSize = 4
        for (encodedName in encodedNames) tableSize += 16 + encodedName.size
        val dataStart = 7 + tableSize

        val offsets = IntArray(entries.size)
        var cursor = dataStart
        for (index in entries.indices) {
            offsets[index] = cursor
            cursor += entries[index].second.size
        }

        val table = ByteArrayOutputStream()
        table.write(le(entries.size))
        for (index in entries.indices) {
            table.write(le(encodedNames[index].size))
            table.write(encodedNames[index])
            table.write(le(0))
            table.write(le(offsets[index]))
            table.write(le(entries[index].second.size))
        }
        val tableBytes = table.toByteArray()
        assertEquals(tableSize, tableBytes.size)
        val key = MessageDigest.getInstance("SHA-1").digest(tableBytes)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x70, 0x66, 0x00))
        out.write(le(tableSize))
        out.write(tableBytes)
        for (index in entries.indices) {
            var data = entries[index].second
            if (data.size >= 8) {
                data = ByteArray(data.size) { position ->
                    (data[position].toInt() xor key[position % key.size].toInt()).toByte()
                }
            }
            out.write(data)
        }
        File(dir, name).writeBytes(out.toByteArray())
    }

    @Test
    fun needsBasePatchRequiresMissingSystemIniAndPfsArchive() {
        val root = newGameDir("patch-needed")
        assertFalse(ArtemisPfsUnpacker.needsBasePatch(root.absolutePath))

        writePfs(root, "root.pfs", listOf("system.ini" to "[SYSTEM]".toByteArray()))
        assertTrue(ArtemisPfsUnpacker.needsBasePatch(root.absolutePath))

        File(root, "system.ini").writeText("[SYSTEM]")
        assertFalse(ArtemisPfsUnpacker.needsBasePatch(root.absolutePath))
    }

    @Test
    fun needsBasePatchRejectsSafUriAndMissingDirectory() {
        assertFalse(ArtemisPfsUnpacker.needsBasePatch("content://com.android.externalstorage.documents/tree/primary%3AGames"))
        assertFalse(ArtemisPfsUnpacker.needsBasePatch(temporaryFolder.root.resolve("missing").absolutePath))
        assertFalse(ArtemisPfsUnpacker.needsBasePatch(null))
    }

    @Test
    fun applyBasePatchExtractsWhitelistedEntriesOnly() {
        val root = newGameDir("extract")
        val iniContent = "[SYSTEM]\r\nWIDTH = 1280\r\n"
        val tblContent = "config_tablet=0,\r\nconfig_tabletui=0,\r\n"
        val movieBytes = ByteArray(64) { it.toByte() }
        writePfs(
            root,
            "root.pfs",
            listOf(
                "system.ini" to iniContent.toByteArray(),
                "list_windows.tbl" to tblContent.toByteArray(),
                "movie/test.mp4" to movieBytes,
                "readme.txt" to "do not extract".toByteArray(),
            ),
        )

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(root.absolutePath))

        val patchedIni = File(root, "system.ini").readText()
        assertTrue(patchedIni.contains("[SYSTEM]"))
        assertTrue(patchedIni.contains("[ANDROID]"))
        assertTrue(patchedIni.contains("BOOT = system/first.iet"))

        val renamedTbl = File(root, "list_android.tbl")
        assertTrue(renamedTbl.isFile)
        assertFalse(File(root, "list_windows.tbl").exists())
        val tbl = renamedTbl.readText()
        assertTrue(tbl.contains("config_tablet=1"))
        assertTrue(tbl.contains("config_tabletui=1"))

        assertTrue(File(root, "movie/test.mp4").isFile)
        assertEquals(movieBytes.toList(), File(root, "movie/test.mp4").readBytes().toList())

        assertFalse(File(root, "readme.txt").exists())
        assertFalse(ArtemisPfsUnpacker.needsBasePatch(root.absolutePath))
    }

    @Test
    fun applyBasePatchBlocksPathTraversalEntries() {
        val root = newGameDir("traversal")
        val outside = temporaryFolder.root
        writePfs(
            root,
            "root.pfs",
            listOf("../../escaped-system.ini".lowercase() to "malicious".toByteArray()),
        )

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(root.absolutePath))

        assertFalse(outside.resolve("escaped-system.ini").exists())
        assertFalse(outside.parentFile?.resolve("escaped-system.ini")?.exists() == true)
        assertTrue(File(root, "system.ini").isFile)
    }

    @Test
    fun applyBasePatchGeneratesFallbackSystemIniForBrokenArchives() {
        val root = newGameDir("broken")
        File(root, "garbage.pfs").writeBytes("this is not a pfs archive".toByteArray())

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(root.absolutePath))

        val fallback = File(root, "system.ini")
        assertTrue(fallback.isFile)
        val text = fallback.readText()
        assertTrue(text.contains("[SYSTEM]"))
        assertTrue(text.contains("[ANDROID]"))
    }
}
