package com.tyranor.next.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PathResolverTest {

    // ---------- safUriToPath ----------

    @Test
    fun passesThroughNullBlankAndAbsolutePaths() {
        assertNull(PathResolver.safUriToPath(null))
        // 契约：绝对路径与空白串均原样透传（由调用方决定后续处理）
        assertEquals("/storage/emulated/0/G", PathResolver.safUriToPath("/storage/emulated/0/G"))
        assertEquals("   ", PathResolver.safUriToPath("   "))
    }

    @Test
    fun mapsFileSchemeToItsPath() {
        assertEquals("/a/b", PathResolver.safUriToPath("file:///a/b"))
        assertEquals("/a b", PathResolver.safUriToPath("file:///a%20b"))
    }

    @Test
    fun mapsPrimaryDocumentUriToEmulatedStorage() {
        val uri = "content://com.android.externalstorage.documents" +
            "/tree/primary%3AGames/document/primary%3AGames%2FMyGame"
        assertEquals("/storage/emulated/0/Games/MyGame", PathResolver.safUriToPath(uri))
    }

    @Test
    fun mapsTreeOnlyUriViaDocumentsContract() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AGames"
        assertEquals("/storage/emulated/0/Games", PathResolver.safUriToPath(uri))
    }

    @Test
    fun mapsSecondaryVolume() {
        val uri = "content://com.android.externalstorage.documents" +
            "/document/0000-0001%3APics%2Fimg.png"
        assertEquals("/storage/0000-0001/Pics/img.png", PathResolver.safUriToPath(uri))
    }

    @Test
    fun mapsVolumeRootWithoutRelativePart() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3A"
        assertEquals("/storage/emulated/0", PathResolver.safUriToPath(uri))
    }

    @Test
    fun rejectsNonContentSchemes() {
        assertNull(PathResolver.safUriToPath("https://example.com/game"))
        assertNull(PathResolver.safUriToPath("ftp://x/y"))
    }

    // ---------- isRemovableStoragePath ----------

    @Test
    fun detectsRemovableVolumes() {
        assertTrue(PathResolver.isRemovableStoragePath("/storage/0000-0001"))
        assertTrue(PathResolver.isRemovableStoragePath("/storage/0000-0001/Games/x"))
    }

    @Test
    fun rejectsPrimaryAndNonStoragePaths() {
        for (p in listOf(
            "/storage/emulated/0",
            "/storage/emulated/0/Games",
            "/sdcard",
            "C:/Games",
            "",
            "/storage/",
        )) {
            org.junit.Assert.assertFalse("path=$p", PathResolver.isRemovableStoragePath(p))
        }
    }

    // ---------- safeSaveName ----------

    @Test
    fun keepsPlainDirectoryName() {
        assertEquals("My Game", PathResolver.safeSaveName("/g/My Game"))
    }

    @Test
    fun replacesIllegalFilenameCharacters() {
        // 用根路径夹具避免 Windows 下 "x:name" 被解析为盘符
        assertEquals("a_b_c__d", PathResolver.safeSaveName("/g/a:b*c??d"))
    }

    @Test
    fun fallsBackToNumericHashWhenNameIsBlank() {
        val out = PathResolver.safeSaveName("   ")
        assertTrue("got=$out", Regex("\\d+").matches(out))
    }

    @Test
    fun hashFallbackIsNumericForUnnamedPaths() {
        val out = PathResolver.safeSaveName("/")
        assertTrue("got=$out", Regex("\\d+").matches(out))
    }
}
