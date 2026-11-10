package com.voicediary.app.storage

import com.voicediary.app.recording.ReplayInterval
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal object SegmentFileMetadata {
    private val rangePattern = Regex("^(.+?) to (.+?)(?:_\\d+)?\\.m4a$")
    private val incompleteSegmentPattern = Regex("^loop_\\d+\\.m4a$")
    private val timeFormatter = DateTimeFormatter.ofPattern("h-mm a", Locale.US)
    private val timestampPattern = Regex("^.*__(\\d+)_(\\d+)(?:_\\d+)?\\.m4a$")
    private val timeFormatters = listOf(
        DateTimeFormatter.ofPattern("h-mm a", Locale.US),
        DateTimeFormatter.ofPattern("h a", Locale.US),
        DateTimeFormatter.ofPattern("H:mm", Locale.US)
    )

    fun parse(fileName: String): ReplayInterval? {
        val timestampMatch = timestampPattern.matchEntire(fileName)
        if (timestampMatch != null) {
            val startedAtMillis = timestampMatch.groupValues[1].toLongOrNull() ?: return null
            val endedAtMillis = timestampMatch.groupValues[2].toLongOrNull() ?: return null
            return ReplayInterval(startedAtMillis, endedAtMillis)
                .takeIf { it.endedAtMillis > it.startedAtMillis }
        }

        val match = rangePattern.matchEntire(fileName) ?: return null
        val startStr = match.groupValues[1]
        val endStr = match.groupValues[2]

        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)

        val startTime = parseTime(startStr) ?: return null
        val endTime = parseTime(endStr) ?: return null

        val startedAtMillis = startTime.atDate(today).atZone(zone).toInstant().toEpochMilli()
        val endDate = if (endTime < startTime) today.plusDays(1) else today
        val endedAtMillis = endTime.atDate(endDate).atZone(zone).toInstant().toEpochMilli()
        return ReplayInterval(startedAtMillis, endedAtMillis)
            .takeIf { it.endedAtMillis >= it.startedAtMillis }
    }

    private fun parseTime(str: String): LocalTime? {
        for (formatter in timeFormatters) {
            try {
                return LocalTime.parse(str, formatter)
            } catch (_: DateTimeParseException) {}
        }
        return null
    }

    fun fileName(startedAtMillis: Long, endedAtMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAtMillis), zone)
        val end = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(endedAtMillis), zone)
        return "${timeFormatter.format(start)} to ${timeFormatter.format(end)}__${startedAtMillis}_${endedAtMillis}.m4a"
    }

    fun isAudioChunk(fileName: String): Boolean =
        parse(fileName) != null || incompleteSegmentPattern.matches(fileName)

    fun latestEndMillis(fileNames: Iterable<String>): Long =
        fileNames.mapNotNull(::parse).maxOfOrNull { it.endedAtMillis } ?: 0L
}
