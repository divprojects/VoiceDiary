package com.voicediary.app.update

internal object ReleaseVersion {
    fun isNewer(currentVersion: String, latestVersion: String): Boolean {
        val current = parse(currentVersion)
        val latest = parse(latestVersion)

        for (index in 0 until maxOf(current.size, latest.size)) {
            val currentPart = current.getOrElse(index) { 0 }
            val latestPart = latest.getOrElse(index) { 0 }
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.trimStart('v', 'V').split('.').mapNotNull(String::toIntOrNull)
}