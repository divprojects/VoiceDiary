package com.voicediary.app.recording

import com.voicediary.app.storage.SegmentFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

class SegmentRetentionPolicyTest {

    @Test
    fun segmentFileMetadataReadsTheEndTimestampForRetention() {
        val interval = SegmentFileMetadata.parse(
            "11 AM to 12 PM.m4a"
        )

        val parsed = interval ?: throw AssertionError("Expected a valid segment filename.")
        assertTrue(parsed.endedAtMillis > parsed.startedAtMillis)
    }

    @Test
    fun visibleFileNameContainsReadableStartEndTimesAndCanBeParsed() {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val start = LocalTime.of(17, 54).atDate(today).atZone(zone).toInstant().toEpochMilli()
        val end = LocalTime.of(17, 59).atDate(today).atZone(zone).toInstant().toEpochMilli()
        val name = SegmentFileMetadata.fileName(start, end)

        assertEquals(
            "5-54 PM to 5-59 PM.m4a",
            name
        )
        assertEquals(ReplayInterval(start, end), SegmentFileMetadata.parse(name))
    }

    @Test
    fun sixtyMinuteReplayExpiresWhenSegmentEndFallsOutsideTheLastHour() {
        val segmentEnd = 2 * 60 * 60_000L
        val expiry = segmentEnd + 60 * 60_000L

        assertFalse(SegmentRetentionPolicy.isExpired(segmentEnd, expiry - 1, 60))
        assertTrue(SegmentRetentionPolicy.isExpired(segmentEnd, expiry, 60))
    }

    @Test
    fun sixtyMinuteReplayAtThreeFortySevenKeepsTheTwoToThreeChunk() {
        val oneToTwoChunkEnd = 2 * 60 * 60_000L
        val twoToThreeChunkEnd = 3 * 60 * 60_000L
        val now = twoToThreeChunkEnd + 47 * 60_000L

        assertTrue(SegmentRetentionPolicy.isExpired(oneToTwoChunkEnd, now, 60))
        assertFalse(SegmentRetentionPolicy.isExpired(twoToThreeChunkEnd, now, 60))
    }

    @Test
    fun fiveMinuteReplayExpiresChunksOlderThanTheReplayWindow() {
        val segmentEnd = 10 * 60_000L
        val expiry = segmentEnd + 5 * 60_000L

        assertFalse(SegmentRetentionPolicy.isExpired(segmentEnd, expiry - 1, 5))
        assertTrue(SegmentRetentionPolicy.isExpired(segmentEnd, expiry, 5))
    }

    @Test
    fun nextChunkDeletionIsScheduledForTheExactReplayWindowBoundary() {
        val segments = listOf(
            ReplayInterval(1_000L, 61_000L),
            ReplayInterval(61_000L, 121_000L)
        )

        assertEquals(
            361_000L,
            SegmentRetentionPolicy.nextExpirationMillis(segments, 5)
        )
    }

    @Test
    fun availableWindowIncludesContinuousAudioAcrossChunkBoundaries() {
        val minute = 60_000L
        val segments = listOf(
            segment(0, 15, minute),
            segment(15, 30, minute),
            segment(30, 45, minute),
            segment(45, 60, minute)
        )

        assertEquals(
            ReplayInterval(5 * minute, 60 * minute),
            SegmentRetentionPolicy.availableWindow(segments, 5 * minute, 60 * minute)
        )
    }

    @Test
    fun availableWindowDoesNotBridgeLargeAudioGaps() {
        val minute = 60_000L
        val segments = listOf(
            segment(0, 15, minute),
            segment(30, 45, minute),
            segment(45, 60, minute)
        )

        assertEquals(
            ReplayInterval(30 * minute, 60 * minute),
            SegmentRetentionPolicy.availableWindow(segments, 5 * minute, 60 * minute)
        )
    }

    @Test
    fun availableWindowAtFourThirtySevenUsesOnlyAudioSinceThreeThirtySeven() {
        val minute = 60_000L
        val segments = listOf(
            segment(180, 240, minute),
            segment(240, 277, minute)
        )

        assertEquals(
            ReplayInterval(217 * minute, 277 * minute),
            SegmentRetentionPolicy.availableWindow(segments, 217 * minute, 277 * minute)
        )
    }

    @Test
    fun onlyVoiceDiaryAudioChunkNamesAreEligibleForClear() {
        assertTrue(SegmentFileMetadata.isAudioChunk("loop_12345.m4a"))
        assertTrue(
            SegmentFileMetadata.isAudioChunk(
                "5-54 PM to 5-59 PM.m4a"
            )
        )
        assertFalse(SegmentFileMetadata.isAudioChunk("family-recording.m4a"))
        assertFalse(SegmentFileMetadata.isAudioChunk("notes.txt"))
    }

    @Test
    fun savesAllThreeAvailableMinutesWhenFiveMinutesWereSelected() {
        val minute = 60_000L
        val segments = listOf(
            segment(0, 1, minute),
            segment(1, 2, minute),
            segment(2, 3, minute)
        )

        assertEquals(
            ReplayInterval(0, 3 * minute),
            SegmentRetentionPolicy.availableWindow(segments, -2 * minute, 3 * minute)
        )
    }

    @Test
    fun partialSaveUsesOnlyContinuousAudioWhenThereIsAChunkGap() {
        val minute = 60_000L
        val segments = listOf(
            segment(0, 1, minute),
            segment(2, 3, minute)
        )

        assertEquals(
            ReplayInterval(2 * minute, 3 * minute),
            SegmentRetentionPolicy.availableWindow(segments, -2 * minute, 3 * minute)
        )
    }

    private fun segment(startMinute: Int, endMinute: Int, minuteMillis: Long) =
        ReplayInterval(
            startMinute * minuteMillis,
            endMinute * minuteMillis
        )
}
