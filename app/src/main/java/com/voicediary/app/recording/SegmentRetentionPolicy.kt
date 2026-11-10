package com.voicediary.app.recording

internal data class ReplayInterval(val startedAtMillis: Long, val endedAtMillis: Long)

internal object SegmentRetentionPolicy {
    fun nextExpirationMillis(
        segments: List<ReplayInterval>,
        replayLengthMinutes: Int
    ): Long? {
        require(replayLengthMinutes > 0) { "Replay duration must be positive." }
        val retentionMillis = replayLengthMinutes * 60_000L
        return segments.minOfOrNull { it.endedAtMillis + retentionMillis }
    }

    fun availableWindow(
        segments: List<ReplayInterval>,
        requestedStartMillis: Long,
        requestedEndMillis: Long,
        allowedGapMillis: Long = 2_000L
    ): ReplayInterval? {
        if (requestedEndMillis <= requestedStartMillis) return null
        val relevant = segments
            .filter {
                it.endedAtMillis > requestedStartMillis &&
                    it.startedAtMillis < requestedEndMillis
            }
            .sortedByDescending { it.startedAtMillis }
        val latest = relevant.firstOrNull() ?: return null
        if (latest.endedAtMillis < requestedEndMillis - allowedGapMillis) return null

        var coveredStart = minOf(latest.startedAtMillis, requestedEndMillis)
        for (segment in relevant.drop(1)) {
            if (segment.endedAtMillis < coveredStart - allowedGapMillis) break
            coveredStart = minOf(coveredStart, segment.startedAtMillis)
            if (coveredStart <= requestedStartMillis) break
        }

        val actualStart = maxOf(requestedStartMillis, coveredStart)
        return ReplayInterval(actualStart, requestedEndMillis)
            .takeIf { it.endedAtMillis > it.startedAtMillis }
    }

    fun isExpired(segmentEndMillis: Long, nowMillis: Long, replayLengthMinutes: Int): Boolean {
        val retentionMillis = replayLengthMinutes * 60_000L
        return nowMillis >= segmentEndMillis + retentionMillis
    }

}
