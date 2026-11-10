package com.voicediary.app.storage

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.edit
import com.voicediary.app.recording.ReplayInterval
import com.voicediary.app.recording.SegmentRetentionPolicy
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LoopSegment(
    val uri: Uri,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val displayName: String
)

data class ReplaySaveResult(
    val uri: Uri,
    val startedAtMillis: Long,
    val endedAtMillis: Long
) {
    val durationMillis: Long
        get() = endedAtMillis - startedAtMillis
}

class StorageManager(private val context: Context) {

    companion object {
        val SEGMENT_LENGTHS_MINUTES = listOf(5, 10, 15, 30, 45, 60)
        const val DEFAULT_SEGMENT_MINUTES = 5
        const val REPLAY_CHUNK_MILLIS = 60_000L
        const val AUDIO_CHUNKS_DIRECTORY = "Audio Chunks"
        const val SAVED_AUDIO_DIRECTORY = "Your Saved Audio"

        private const val PREFS_NAME = "loop_recorder_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_ACTIVE_URI = "active_uri"
        private const val KEY_SESSION_START = "session_start"
        private const val KEY_LATEST_AUDIO_END = "latest_audio_end"
        private const val KEY_SEGMENT_MINUTES = "segment_minutes"
        private const val TAG = "VoiceDiaryStorage"
    }

    private val preferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasFolderSelection(): Boolean {
        val treeUri = getTreeUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    }

    fun saveTreeUri(uri: Uri, grantFlags: Int) {
        val accessFlags =
            grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if ((accessFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 ||
            (accessFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
        ) {
            throw IOException("The selected folder did not grant read and write access.")
        }
        val directory = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("The selected storage folder could not be opened.")
        if (!directory.isDirectory) throw IOException("Please select a folder.")
        val previousTreeUri = getTreeUri()
        context.contentResolver.takePersistableUriPermission(uri, accessFlags)
        try {
            getOrCreateChildDirectory(directory, AUDIO_CHUNKS_DIRECTORY)
            getOrCreateChildDirectory(directory, SAVED_AUDIO_DIRECTORY)
            preferences.edit { putString(KEY_FOLDER_URI, uri.toString()) }
            if (previousTreeUri != null && previousTreeUri != uri) {
                val previousFlags = context.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == previousTreeUri }
                    ?.let {
                        (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                            (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    } ?: 0
                if (previousFlags != 0) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(
                            previousTreeUri,
                            previousFlags
                        )
                    } catch (exception: SecurityException) {
                        Log.w(TAG, "Could not release the previous recordings-folder permission.", exception)
                    }
                }
            }
        } catch (exception: Exception) {
            if (previousTreeUri != uri) {
                try {
                    context.contentResolver.releasePersistableUriPermission(uri, accessFlags)
                } catch (releaseException: SecurityException) {
                    exception.addSuppressed(releaseException)
                }
            }
            throw exception
        }
    }

    fun getCurrentFolderLabel(): String {
        return DocumentFile.fromTreeUri(context, requireSelectedTreeUri())?.name
            ?: "Selected device folder"
    }

    fun getSavedAudioFolderUri(): Uri {
        val root = DocumentFile.fromTreeUri(context, requireSelectedTreeUri())
            ?: throw IOException("The selected recordings folder is unavailable.")
        return getOrCreateChildDirectory(root, SAVED_AUDIO_DIRECTORY).uri
    }

    fun getSegmentMinutes(): Int {
        val selected = preferences.getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES)
        return selected.takeIf { it in SEGMENT_LENGTHS_MINUTES } ?: DEFAULT_SEGMENT_MINUTES
    }

    fun setSegmentMinutes(minutes: Int) {
        require(minutes in SEGMENT_LENGTHS_MINUTES) { "Unsupported segment length: $minutes" }
        preferences.edit { putInt(KEY_SEGMENT_MINUTES, minutes) }
    }

    fun isRecording(): Boolean = preferences.getBoolean(KEY_IS_RECORDING, false)

    fun getSelectedTreeUri(): Uri? = getTreeUri()

    fun getActiveSegmentUri(): Uri? =
        preferences.getString(KEY_ACTIVE_URI, null)?.let(Uri::parse)

    fun getRecordingSessionStartMillis(): Long =
        preferences.getLong(KEY_SESSION_START, 0L)

    fun getLatestAudioEndMillis(): Long =
        preferences.getLong(KEY_LATEST_AUDIO_END, 0L)

    fun markRecording(uri: Uri, startedAtMillis: Long, segmentMinutes: Int) {
        preferences.edit {
            putBoolean(KEY_IS_RECORDING, true)
            putString(KEY_ACTIVE_URI, uri.toString())
            putInt(KEY_SEGMENT_MINUTES, segmentMinutes)
            if (!isRecording()) putLong(KEY_SESSION_START, startedAtMillis)
        }
    }

    fun clearRecordingState() {
        preferences.edit {
            putBoolean(KEY_IS_RECORDING, false)
            remove(KEY_ACTIVE_URI)
        }
    }

    fun createLoopSegment(startedAtMillis: Long): Uri =
        createAudioFile(
            AUDIO_CHUNKS_DIRECTORY,
            SegmentFileMetadata.fileName(
                startedAtMillis,
                startedAtMillis + REPLAY_CHUNK_MILLIS
            )
        )

    fun openForRecording(uri: Uri): ParcelFileDescriptor {
        return if (uri.scheme == "file") {
            ParcelFileDescriptor.open(
                File(uri.path ?: throw IOException("Invalid recording path.")),
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE
            )
        } else {
            context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open the selected recording file.")
        }
    }

    fun finalizeLoopSegment(uri: Uri, startedAtMillis: Long, endedAtMillis: Long): Uri {
        val targetName = segmentFileName(startedAtMillis, endedAtMillis)
        val finalizedUri = if (uri.scheme == "file") {
            val source = File(uri.path ?: throw IOException("Invalid recording path."))
            val destination = uniqueSiblingFile(source, targetName)
            if (!source.renameTo(destination)) {
                throw IOException("Could not name the audio segment by its recording time.")
            }
            Uri.fromFile(destination)
        } else {
            val document = findDocument(uri)
                ?: throw IOException("The completed audio segment could not be found.")
            val destinationName = uniqueDocumentName(document, targetName)
            if (!document.renameTo(destinationName)) {
                throw IOException("Could not name the audio segment by its recording time.")
            }
            findDocumentByName(AUDIO_CHUNKS_DIRECTORY, destinationName)?.uri
                ?: throw IOException("The renamed audio segment could not be found.")
        }
        preferences.edit { putLong(KEY_LATEST_AUDIO_END, endedAtMillis) }
        return finalizedUri
    }

    fun listLoopSegments(): List<LoopSegment> {
        return getDirectoryDocuments(AUDIO_CHUNKS_DIRECTORY).mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            val interval = SegmentFileMetadata.parse(name) ?: return@mapNotNull null
            LoopSegment(
                document.uri,
                interval.startedAtMillis,
                interval.endedAtMillis,
                formatTimeRange(interval.startedAtMillis, interval.endedAtMillis)
            )
        }.sortedBy { it.startedAtMillis }
    }

    fun getLoopStorageBytes(): Long =
        getDirectoryDocuments(AUDIO_CHUNKS_DIRECTORY).sumOf { it.length().coerceAtLeast(0L) }

    fun saveReplay(windowEndMillis: Long, windowMinutes: Int): ReplaySaveResult {
        require(windowMinutes > 0) { "Replay duration must be positive." }
        val requestedStartMillis = windowEndMillis - windowMinutes * 60_000L
        pruneExpiredSegments(windowEndMillis, windowMinutes, getActiveSegmentUri())

        val segments = listLoopSegments()
        val actualWindow = SegmentRetentionPolicy.availableWindow(
            segments.map { ReplayInterval(it.startedAtMillis, it.endedAtMillis) },
            requestedStartMillis,
            windowEndMillis
        ) ?: throw IOException("No recorded audio is available for the selected replay window.")
        val replaySegments = segments.filter {
            it.endedAtMillis > actualWindow.startedAtMillis &&
                it.startedAtMillis < actualWindow.endedAtMillis
        }

        val destination = createAudioFile(
            SAVED_AUDIO_DIRECTORY,
            replayFileName(actualWindow.startedAtMillis, actualWindow.endedAtMillis)
        )
        try {
            muxReplay(
                replaySegments,
                destination,
                actualWindow.startedAtMillis,
                actualWindow.endedAtMillis
            )
        } catch (exception: Exception) {
            deleteUri(destination)
            throw exception
        }
        return ReplaySaveResult(destination, actualWindow.startedAtMillis, actualWindow.endedAtMillis)
    }

    fun deleteExpiredSegments(nowMillis: Long, segmentMinutes: Int, exceptUri: Uri?) {
        pruneExpiredSegments(nowMillis, segmentMinutes, exceptUri)
    }

    fun clearLoopSegments(exceptUri: Uri?): Int {
        val documents = getDirectoryDocuments(AUDIO_CHUNKS_DIRECTORY)
            .filter { it.isFile && SegmentFileMetadata.isAudioChunk(it.name.orEmpty()) }
        var deletedCount = 0
        documents.forEach { document ->
            if (document.uri == exceptUri) return@forEach
            if (!deleteUri(document.uri)) {
                throw IOException(
                    "Could not delete audio chunk ${document.name ?: document.uri}. " +
                        "Choose a folder that allows file deletion."
                )
            }
            deletedCount++
        }
        val latestRemainingEnd = documents.asSequence()
            .filter { it.uri != exceptUri }
            .mapNotNull { it.name?.let(SegmentFileMetadata::parse)?.endedAtMillis }
            .maxOrNull() ?: 0L
        if (exceptUri == null) {
            preferences.edit {
                putLong(KEY_LATEST_AUDIO_END, latestRemainingEnd)
                remove(KEY_SESSION_START)
            }
        } else {
            preferences.edit { putLong(KEY_LATEST_AUDIO_END, latestRemainingEnd) }
        }
        return deletedCount
    }

    fun nextSegmentExpirationMillis(segmentMinutes: Int, exceptUri: Uri?): Long? =
        SegmentRetentionPolicy.nextExpirationMillis(
            listLoopSegments()
                .filter { it.uri != exceptUri }
                .map { ReplayInterval(it.startedAtMillis, it.endedAtMillis) },
            segmentMinutes
        )

    fun deleteUri(uri: Uri): Boolean {
        if (uri.scheme == "file") return File(uri.path ?: return false).delete()

        try {
            if (android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                return true
            }
        } catch (_: UnsupportedOperationException) {
            // Some document providers only implement deletion through their document wrapper.
        } catch (_: IllegalArgumentException) {
            // Fall through and try the provider's standard delete operation.
        }
        try {
            if (DocumentFile.fromSingleUri(context, uri)?.delete() == true) return true
        } catch (_: UnsupportedOperationException) {
            // Fall through to the content resolver.
        }
        return context.contentResolver.delete(uri, null, null) > 0
    }

    private fun pruneExpiredSegments(nowMillis: Long, replayMinutes: Int, exceptUri: Uri?) {
        val documents = getDirectoryDocuments(AUDIO_CHUNKS_DIRECTORY)
        val remainingDocuments = mutableListOf<DocumentFile>()
        documents.forEach { document ->
            if (document.uri == exceptUri) {
                remainingDocuments += document
                return@forEach
            }
            val name = document.name ?: return@forEach
            val interval = SegmentFileMetadata.parse(name)
            val expired = if (interval != null) {
                SegmentRetentionPolicy.isExpired(interval.endedAtMillis, nowMillis, replayMinutes)
            } else {
                val isIncompleteSegment = SegmentFileMetadata.isAudioChunk(name)
                isIncompleteSegment &&
                    SegmentRetentionPolicy.isExpired(document.lastModified(), nowMillis, replayMinutes)
            }
            if (expired && !deleteUri(document.uri)) {
                throw IOException(
                    "Could not remove an expired audio chunk. Check that the selected folder allows deletion, " +
                        "or use Clear Audio Chunks to remove unsaved recordings."
                )
            }
            if (!expired) remainingDocuments += document
        }
        val latestAudioEnd = remainingDocuments.asSequence()
            .mapNotNull { it.name?.let(SegmentFileMetadata::parse)?.endedAtMillis }
            .maxOrNull() ?: 0L
        preferences.edit { putLong(KEY_LATEST_AUDIO_END, latestAudioEnd) }
    }

    private fun muxReplay(
        segments: List<LoopSegment>,
        destination: Uri,
        windowStartMillis: Long,
        windowEndMillis: Long
    ) {
        val outputDescriptor = openForRecording(destination)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputTrack = -1
        var expectedFormat: MediaFormat? = null
        var operationFailure: Exception? = null
        val windowStartUs = windowStartMillis * 1_000L
        val windowEndUs = windowEndMillis * 1_000L
        val sampleBuffer = ByteBuffer.allocateDirect(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            muxer = MediaMuxer(outputDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            segments.forEach { segment ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, segment.uri, null)
                    val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { track ->
                        extractor.getTrackFormat(track)
                            .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IOException("No audio track was found in ${segment.displayName}.")
                    val format = extractor.getTrackFormat(audioTrackIndex)
                    if (expectedFormat == null) {
                        expectedFormat = format
                        outputTrack = muxer.addTrack(format)
                    } else if (!hasCompatibleAudioFormat(expectedFormat!!, format)) {
                        throw IOException("Audio format changed between loop segments.")
                    }

                    extractor.selectTrack(audioTrackIndex)
                    val segmentStartUs = segment.startedAtMillis * 1_000L
                    val segmentEndUs = segment.endedAtMillis * 1_000L
                    while (true) {
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L) break
                        val absoluteSampleTimeUs = segmentStartUs + sampleTimeUs
                        if (absoluteSampleTimeUs >= minOf(segmentEndUs, windowEndUs)) break
                        if (absoluteSampleTimeUs >= windowStartUs) {
                            sampleBuffer.clear()
                            val sampleSize = extractor.readSampleData(sampleBuffer, 0)
                            if (sampleSize < 0) break
                            val extractorFlags = extractor.sampleFlags
                            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                                throw IOException("Encrypted audio segments cannot be replayed.")
                            }
                            var muxerFlags = 0
                            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                                muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                            }
                            if (!muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }
                            bufferInfo.set(
                                0,
                                sampleSize,
                                absoluteSampleTimeUs - windowStartUs,
                                muxerFlags
                            )
                            sampleBuffer.position(0)
                            sampleBuffer.limit(sampleSize)
                            muxer.writeSampleData(outputTrack, sampleBuffer, bufferInfo)
                        }
                        extractor.advance()
                    }
                } finally {
                    extractor.release()
                }
            }
            if (!muxerStarted) {
                throw IOException("No audio was recorded in the selected replay window.")
            }
        } catch (exception: Exception) {
            operationFailure = exception
            throw exception
        } finally {
            var cleanupFailure: Exception? = null
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (exception: Exception) {
                    cleanupFailure = exception
                }
            }
            try {
                muxer?.release()
            } catch (exception: Exception) {
                if (cleanupFailure == null) cleanupFailure = exception
                else cleanupFailure.addSuppressed(exception)
            }
            try {
                outputDescriptor.close()
            } catch (exception: Exception) {
                if (cleanupFailure == null) cleanupFailure = exception
                else cleanupFailure.addSuppressed(exception)
            }
            cleanupFailure?.let { failure ->
                if (operationFailure == null) throw failure
                operationFailure.addSuppressed(failure)
            }
        }
    }

    private fun hasCompatibleAudioFormat(first: MediaFormat, next: MediaFormat): Boolean {
        val keys = listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)
        return keys.all { key ->
            !first.containsKey(key) || !next.containsKey(key) ||
                first.getStringOrNumber(key) == next.getStringOrNumber(key)
        }
    }

    private fun MediaFormat.getStringOrNumber(key: String): String? =
        if (key == MediaFormat.KEY_MIME) getString(key) else getInteger(key).toString()

    private fun segmentFileName(startMillis: Long, endMillis: Long): String {
        return SegmentFileMetadata.fileName(startMillis, endMillis)
    }

    private fun replayFileName(startMillis: Long, endMillis: Long): String {
        return SegmentFileMetadata.fileName(startMillis, endMillis)
    }

    private fun formatTimeRange(startMillis: Long, endMillis: Long): String {
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "${formatter.format(Date(startMillis))} to ${formatter.format(Date(endMillis))}"
    }

    private fun uniqueSiblingFile(source: File, requestedName: String): File {
        var candidate = File(source.parentFile, requestedName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(source.parentFile, requestedName.removeSuffix(".m4a") + "_$suffix.m4a")
            suffix++
        }
        return candidate
    }

    private fun uniqueDocumentName(document: DocumentFile, requestedName: String): String {
        val directory = findDirectory(AUDIO_CHUNKS_DIRECTORY)
        var candidate = requestedName
        var suffix = 1
        while (directory.findFile(candidate) != null && directory.findFile(candidate)?.uri != document.uri) {
            candidate = requestedName.removeSuffix(".m4a") + "_$suffix.m4a"
            suffix++
        }
        return candidate
    }

    private fun findDocument(uri: Uri): DocumentFile? {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            return if (file.exists()) DocumentFile.fromFile(file) else null
        }
        return getDirectoryDocuments(AUDIO_CHUNKS_DIRECTORY).firstOrNull { it.uri == uri }
    }

    private fun findDocumentByName(directoryName: String, name: String): DocumentFile? =
        getDirectoryDocuments(directoryName).firstOrNull { it.name == name }

    private fun createAudioFile(directoryName: String, requestedName: String): Uri {
        if (!hasFolderSelection()) {
            throw IOException("Choose a recordings folder before creating audio files.")
        }
        val root = DocumentFile.fromTreeUri(context, requireSelectedTreeUri())
            ?: throw IOException("The selected storage folder is no longer available.")
        val directory = getOrCreateChildDirectory(root, directoryName)
        var name = requestedName
        var suffix = 1
        while (directory.findFile(name) != null) {
            name = requestedName.removeSuffix(".m4a") + "_$suffix.m4a"
            suffix++
        }
        return directory.createFile("audio/mp4", name)?.uri
            ?: throw IOException("Could not create $name in the selected folder.")
    }

    private fun getDirectoryDocuments(directoryName: String): List<DocumentFile> {
        if (!hasFolderSelection()) {
            throw IOException("Choose a recordings folder before accessing audio files.")
        }
        val root = DocumentFile.fromTreeUri(context, requireSelectedTreeUri())
            ?: throw IOException("The selected storage folder is no longer available.")
        return getOrCreateChildDirectory(root, directoryName).listFiles().toList()
    }

    private fun findDirectory(name: String): DocumentFile {
        val treeUri = getTreeUri()
            ?: throw IOException("Document folders are unavailable in app storage.")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("The selected storage folder is no longer available.")
        return getOrCreateChildDirectory(root, name)
    }

    private fun getOrCreateChildDirectory(parent: DocumentFile, name: String): DocumentFile {
        val child = parent.findFile(name)
        if (child != null) {
            if (!child.isDirectory) throw IOException("$name exists but is not a folder.")
            return child
        }
        val legacyName = when (name) {
            AUDIO_CHUNKS_DIRECTORY -> "Temp"
            SAVED_AUDIO_DIRECTORY -> "Saved"
            else -> null
        }
        val legacyDirectory = legacyName?.let(parent::findFile)
        if (legacyDirectory?.isDirectory == true && legacyDirectory.renameTo(name)) {
            return parent.findFile(name)
                ?: throw IOException("Could not reopen the renamed $name folder.")
        }
        return parent.createDirectory(name) ?: throw IOException("Could not create the $name folder.")
    }

    private fun getTreeUri(): Uri? =
        preferences.getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    private fun requireSelectedTreeUri(): Uri {
        val treeUri = getTreeUri()
            ?: throw IOException("Choose a recordings folder before accessing audio files.")
        if (!hasFolderSelection()) {
            throw IOException("The recordings folder permission is unavailable. Choose it again.")
        }
        return treeUri
    }

}
