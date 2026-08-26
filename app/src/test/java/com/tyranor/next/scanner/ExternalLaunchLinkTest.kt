package com.tyranor.next.scanner

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalLaunchLinkTest {

    private fun parse(link: String) = EngineLauncher.parseExternalLaunchLink(Uri.parse(link))

    @Test
    fun parsesFullExternalLaunchLink() {
        val parsed = parse("tyranor://launch?path=%2Fstorage%2Femulated%2F0%2FG&engine=kirikiri&launchFile=data.xp3")
        assertNotNull(parsed)
        assertEquals("/storage/emulated/0/G", parsed!!.path)
        assertEquals(EngineType.KIRIKIRI, parsed.engine)
        assertEquals("data.xp3", parsed.launchFile)
    }

    @Test
    fun engineOmittedMeansAutoDetect() {
        val parsed = parse("tyranor://launch?path=%2Fg")
        assertNotNull(parsed)
        assertEquals("/g", parsed!!.path)
        assertNull(parsed.engine)
        assertNull(parsed.launchFile)
    }

    @Test
    fun fileUrlPrefixIsStrippedFromPath() {
        val parsed = parse("tyranor://launch?path=file%3A%2F%2F%2Fg%2FH")
        assertNotNull(parsed)
        assertEquals("/g/H", parsed!!.path)
    }

    @Test
    fun unknownEngineFallsBackToAutoDetect() {
        val parsed = parse("tyranor://launch?path=%2Fg&engine=NOT_AN_ENGINE")
        assertNotNull(parsed)
        assertNull(parsed!!.engine)
    }

    @Test
    fun rejectsWrongHostMissingPathAndBlankPath() {
        assertNull(parse("tyranor://open?path=%2Fg"))
        assertNull(parse("tyranor://launch"))
        assertNull(parse("tyranor://launch?path="))
        assertNull(parse("tyranor://launch?engine=ONS"))
    }
}
