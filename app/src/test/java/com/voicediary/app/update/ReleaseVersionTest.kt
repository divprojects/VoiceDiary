package com.voicediary.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {

    @Test
    fun recognizesHigherMajorAndMinorVersions() {
        assertTrue(ReleaseVersion.isNewer("1.9.7", "v2.0.0"))
        assertTrue(ReleaseVersion.isNewer("1.9.7", "v1.10.0"))
    }

    @Test
    fun recognizesPatchUpdatesAndRejectsOlderVersions() {
        assertTrue(ReleaseVersion.isNewer("1.9.7", "v1.9.8"))
        assertFalse(ReleaseVersion.isNewer("1.9.8", "v1.9.7"))
    }

    @Test
    fun treatsEquivalentVersionsAsCurrent() {
        assertFalse(ReleaseVersion.isNewer("v1.9.7", "1.9.7.0"))
    }
}