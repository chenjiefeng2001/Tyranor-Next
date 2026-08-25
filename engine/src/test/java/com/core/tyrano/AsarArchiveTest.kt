package com.core.tyrano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsarArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val indexContent = "hello world"
    private val appContent = "hello"

    private fun headerJson(): String {
        val files = mapOf(
            "index.html" to """{"size":${indexContent.length},"offset":"0"}""",
            "www" to """{"files":{"js":{"files":{}},"app.js":{"size":${appContent.length},"offset":"${indexContent.length}"}}}""",
        )
        return """{"files":{${files.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}}
"""
            .trimIndent()
    }

    private fun payload(): ByteArray = indexContent.toByteArray() + appContent.toByteArray()

    private fun le32(value: Long, out: ByteArrayOutputStream) {
        for (shift in 0 until 4) {
            out.write(((value shr (8 * shift)) and 0xFF).toInt())
        }
    }

    private fun writeAsar(
        name: String = "app.asar",
        magic: Long = 4,
        headerSizeOverride: Long? = null,
        json: String = headerJson(),
        data: ByteArray = payload(),
    ): File {
        val jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        le32(magic, out)
        le32(headerSizeOverride ?: (8L + jsonBytes.size), out)
        le32(0, out)
        le32(jsonBytes.size.toLong(), out)
        out.write(jsonBytes)
        out.write(data)
        return temporaryFolder.newFile(name).apply { writeBytes(out.toByteArray()) }
    }

    @Test
    fun parsesHeaderAndReadsFileEntries() {
        AsarArchive(writeAsar()).use { asar ->
            assertTrue(asar.has("index.html"))
            assertEquals(indexContent, String(asar.read("index.html")!!))
            assertEquals(appContent, String(asar.read("www/app.js")!!))
            assertEquals("app.asar", asar.getArchiveName())
        }
    }

    @Test
    fun normalizesLeadingSeparatorsOnLookup() {
        AsarArchive(writeAsar()).use { asar ->
            assertTrue(asar.has("/index.html"))
            assertTrue(asar.has("\\index.html"))
            assertTrue(asar.has("www/js"))
            assertFalse(asar.has("missing.txt"))
        }
    }

    @Test
    fun distinguishesDirectoriesFromFiles() {
        AsarArchive(writeAsar()).use { asar ->
            assertTrue(asar.isDirectory("www"))
            assertTrue(asar.isDirectory("www/js"))
            assertFalse(asar.isDirectory("index.html"))
            assertFalse(asar.isDirectory("nope"))
        }
    }

    @Test
    fun directoryAndMissingPathsReadAsNull() {
        AsarArchive(writeAsar()).use { asar ->
            assertNull(asar.read("www"))
            assertNull(asar.read("does/not/exist"))
        }
    }

    @Test
    fun rejectsNonAsarMagic() {
        val file = writeAsar(name = "bad-magic.asar", magic = 7)
        assertThrows(IllegalStateException::class.java) { AsarArchive(file) }
    }

    @Test
    fun rejectsOversizedHeaderDeclaration() {
        val file = writeAsar(name = "bad-header.asar", headerSizeOverride = 4)
        assertThrows(java.io.IOException::class.java) { AsarArchive(file) }
    }

    @Test
    fun rejectsJsonLengthExceedingHeaderBoundary() {
        val jsonBytes = headerJson().toByteArray(StandardCharsets.UTF_8)
        val file = writeAsar(name = "bad-jsonlen.asar", headerSizeOverride = jsonBytes.size.toLong() + 4)
        assertThrows(java.io.IOException::class.java) { AsarArchive(file) }
    }

    @Test
    fun rejectsEntryRangeBeyondArchiveEnd() {
        val json = """{"files":{"big.bin":{"size":100000,"offset":"0"}}}"""
        val file = writeAsar(name = "bad-range.asar", json = json, data = ByteArray(8))
        assertThrows(java.io.IOException::class.java) { AsarArchive(file) }
    }

    @Test
    fun rejectsNegativeEntrySize() {
        val json = """{"files":{"evil.bin":{"size":-1,"offset":"0"}}}"""
        val file = writeAsar(name = "bad-size.asar", json = json, data = ByteArray(4))
        assertThrows(java.io.IOException::class.java) { AsarArchive(file) }
    }

    @Test
    fun rejectsMissingArchiveFile() {
        val missing = File(temporaryFolder.root, "absent.asar")
        assertThrows(IllegalArgumentException::class.java) { AsarArchive(missing) }
    }

    @Test
    fun readsAfterCloseFailSoftWithNull() {
        val asar = AsarArchive(writeAsar())
        asar.close()
        assertNull(asar.read("index.html"))
    }
}
