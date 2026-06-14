package com.example.studycapturehelper.domain

import javax.inject.Inject

class CaptureIntervalPolicy @Inject constructor() {
    fun delayMillis(intervalSeconds: Int, thermalMultiplier: Int): Long {
        val safeSeconds = intervalSeconds.coerceIn(15, 3_600)
        val safeMultiplier = thermalMultiplier.coerceIn(1, 4)
        return safeSeconds * safeMultiplier * 1_000L
    }
}
