package com.example.studycapturehelper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureIntervalPolicyTest {
    private val policy = CaptureIntervalPolicy()

    @Test
    fun `uses configured interval at normal temperature`() {
        assertEquals(50_000L, policy.delayMillis(50, 1))
    }

    @Test
    fun `increases interval when device is hot`() {
        assertEquals(200_000L, policy.delayMillis(50, 4))
    }

    @Test
    fun `clamps unsafe values`() {
        assertEquals(15_000L, policy.delayMillis(0, 0))
    }
}
