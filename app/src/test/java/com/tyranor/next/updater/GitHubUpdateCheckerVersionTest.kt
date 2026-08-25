package com.tyranor.next.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerVersionTest {

    private fun compare(left: String, right: String): Int {
        val method = GitHubUpdateChecker::class.java.getDeclaredMethod(
            "compareVersions",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val instance = GitHubUpdateChecker::class.java.getDeclaredField("INSTANCE").get(null)
        return method.invoke(instance, left, right) as Int
    }

    @Test
    fun newerPatchVersionIsGreater() {
        assertTrue(compare("1.16", "1.15") > 0)
        assertTrue(compare("1.15", "1.16") < 0)
    }

    @Test
    fun equalVersionsCompareAsZero() {
        assertEquals(0, compare("1.16", "1.16"))
        assertEquals(0, compare("v1.16", "1.16.0"))
    }

    @Test
    fun numericSegmentsAreNotComparedLexicographically() {
        assertTrue(compare("1.9", "1.10") < 0)
        assertTrue(compare("1.10", "1.9") > 0)
    }

    @Test
    fun longerVersionWinsWhenPrefixMatches() {
        assertTrue(compare("1.15.1", "1.15") > 0)
        assertTrue(compare("1.15", "1.15.1") < 0)
    }

    @Test
    fun tagPrefixesDoNotAffectComparison() {
        assertEquals(0, compare("beta-1.16", "refs/tags/v1.16"))
        assertTrue(compare("refs/tags/beta-1.17", "beta-1.16") > 0)
    }

    @Test
    fun versionsWithoutDigitsFallBackToZero() {
        assertEquals(0, compare("beta", "release"))
        assertTrue(compare("beta", "1") < 0)
    }
}
