package com.example.studycapturehelper.domain

import javax.inject.Inject

class VersionComparator @Inject constructor() {
    fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = parse(candidate)
        val currentParts = parse(current)
        val size = maxOf(candidateParts.size, currentParts.size)
        repeat(size) { index ->
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private fun parse(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .map { component -> component.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
