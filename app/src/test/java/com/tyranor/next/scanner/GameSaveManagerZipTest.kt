package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import com.tyranor.next.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameSaveManagerZipTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        EngineSettingsStore.setKrScopedSaveDir(context, false)
    }

    private fun newGameDir(name: String): File = temporaryFolder.newFolder(name)

    private fun game(root: File) = ScanGame(
        title = root.name,
        uri = Uri.fromFile(root).toString(),
        engine = EngineType.KIRIKIRI,
        launchTarget = "",
    )

    private fun manager() = GameSaveManager(context)

    private fun writeSaves(root: File) {
        File(root, "savedata/sub").mkdirs()
        File(root, "savedata/global.sav").writeText("SAVE-1")
        File(root, "savedata/sub/extra.dat").writeText("EXTRA")
    }

    @Test
    fun exportImportRoundTripRestoresAllFiles() {
        val root = newGameDir("roundtrip")
        writeSaves(root)
        val zipFile = File(temporaryFolder.root, "saves.zip")

        assertEquals(2, manager().exportToZip(game(root), Uri.fromFile(zipFile)))
        assertTrue(zipFile.length() > 0)

        assertEquals(2, manager().deleteSaves(game(root)))
        assertFalse(File(root, "savedata/global.sav").exists())

        assertEquals(2, manager().importFromZip(game(root), Uri.fromFile(zipFile)))
        assertEquals("SAVE-1", File(root, "savedata/global.sav").readText())
        assertEquals("EXTRA", File(root, "savedata/sub/extra.dat").readText())
    }

    @Test
    fun exportWithoutSaveDirectoryThrows() {
        val root = newGameDir("no-saves")
        val zipFile = File(temporaryFolder.root, "empty.zip")

        val error = assertThrows(IOException::class.java) {
            manager().exportToZip(game(root), Uri.fromFile(zipFile))
        }
        assertTrue(error.message!!.contains("存档"))
    }

    private fun writeRawStoredEntry(out: FileOutputStream, name: String, data: ByteArray) {
        val crc = java.util.zip.CRC32().apply { update(data) }
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )
        out.write(byteArrayOf(0x50, 0x4b.toByte(), 0x03, 0x04))
        out.write(le16(20))
        out.write(le16(0))
        out.write(le16(0))
        out.write(le16(0))
        out.write(le16(0))
        out.write(le32(crc.value.toInt()))
        out.write(le32(data.size))
        out.write(le32(data.size))
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.write(le16(nameBytes.size))
        out.write(le16(0))
        out.write(nameBytes)
        out.write(data)
    }

    @Test
    fun importRejectsZipWithDuplicateEntries() {
        val root = newGameDir("dup")
        File(root, "savedata").mkdirs()
        val zipFile = File(temporaryFolder.root, "dup.zip")
        FileOutputStream(zipFile).use { out ->
            repeat(2) { writeRawStoredEntry(out, "a.txt", "same".toByteArray()) }
        }

        val error = assertThrows(IOException::class.java) {
            manager().importFromZip(game(root), Uri.fromFile(zipFile))
        }
        assertTrue(error.message!!.contains("重复"))
    }

    @Test
    fun importRejectsZipWithPathTraversalEntry() {
        val root = newGameDir("traversal")
        File(root, "savedata").mkdirs()
        val outsideMarker = temporaryFolder.root.resolve("escaped.txt")
        val zipFile = File(temporaryFolder.root, "evil.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped.txt"))
            zip.write("malicious".toByteArray())
            zip.closeEntry()
        }

        assertThrows(IOException::class.java) {
            manager().importFromZip(game(root), Uri.fromFile(zipFile))
        }
        assertFalse(outsideMarker.exists())
    }
}
