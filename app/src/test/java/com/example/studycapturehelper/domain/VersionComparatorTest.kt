package com.example.studycapturehelper.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    private val comparator = VersionComparator()

    @Test
    fun `recognizes newer semantic version`() {
        assertTrue(comparator.isNewer("v1.2.0", "1.1.9"))
    }

    @Test
    fun `ignores release prefix and suffix`() {
        assertTrue(comparator.isNewer("V2.0.0-beta", "1.9.9"))
    }

    @Test
    fun `does not update to same or older version`() {
        assertFalse(comparator.isNewer("1.0", "1.0.0"))
        assertFalse(comparator.isNewer("0.9.9", "1.0.0"))
    }
}
