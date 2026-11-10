package com.voicediary.app.storage

import com.voicediary.app.recording.ReplayInterval
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal object SegmentFileMetadata {
    private val rangePattern = Regex("^(.+?) to (.+?)(?:_\\d+)?\\.m4a$")
    private val incompleteSegmentPattern = Regex("^loop_\\d+\\.m4a$")
    private val dateTimeFormatter =
        DateTimeFormatter.ofPattern("h-mm-ss.SSS a '['yyyy-MM-dd']'", Locale.US)
    private val legacyPattern =
        Regex("^.* \\d{4}-\\d{2}-\\d{2}__(\\d+)_(\\d+)\\.m4a$")

    fun parse(fileName: String): ReplayInterval? {
        val legacyMatch = legacyPattern.matchEntire(fileName)
        if (legacyMatch != null) {
            val startedAtMillis = legacyMatch.groupValues[1].toLongOrNull() ?: return null
            val endedAtMillis = legacyMatch.groupValues[2].toLongOrNull() ?: return null
            return ReplayInterval(startedAtMillis, endedAtMillis)
                .takeIf { it.endedAtMillis > it.startedAtMillis }
        }

        val match = rangePattern.matchEntire(fileName) ?: return null
        return try {
            val zone = ZoneId.systemDefault()
            val startedAtMillis = LocalDateTime
                .parse(match.groupValues[1], dateTimeFormatter)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            val endedAtMillis = LocalDateTime
                .parse(match.groupValues[2], dateTimeFormatter)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            ReplayInterval(startedAtMillis, endedAtMillis)
                .takeIf { it.endedAtMillis > it.startedAtMillis }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun fileName(startedAtMillis: Long, endedAtMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAtMillis), zone)
        val end = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(endedAtMillis), zone)
        return "${dateTimeFormatter.format(start)} to ${dateTimeFormatter.format(end)}.m4a"
    }

    fun isAudioChunk(fileName: String): Boolean =
        parse(fileName) != null || incompleteSegmentPattern.matches(fileName)
}
