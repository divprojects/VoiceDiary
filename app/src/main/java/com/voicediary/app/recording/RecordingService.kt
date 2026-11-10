package com.voicediary.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voicediary.app.storage.ReplaySaveResult
import com.voicediary.app.storage.LoopSegment
import com.voicediary.app.storage.StorageManager
import com.voicediary.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.voicediary.app.START"
        const val ACTION_STOP = "com.voicediary.app.STOP"
        const val ACTION_SAVE = "com.voicediary.app.SAVE"
        const val ACTION_STATUS = "com.voicediary.app.STATUS"
        const val EXTRA_SEGMENT_MINUTES = "segment_minutes"
        const val EXTRA_REPLAY_WINDOW_END_MILLIS = "replay_window_end_millis"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_IS_SAVING = "is_saving"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        private const val CHANNEL_ID = "voice_diary_recording"
        private const val NOTIFICATION_ID = 1001
        private const val MINIMUM_SEGMENT_DURATION_MILLIS = 2_000L
        private const val CLEANUP_AND_WAKE_LOCK_RENEWAL_INTERVAL_MILLIS = 60_000L
        private const val TAG = "VoiceDiaryRecorder"
    }

    private fun formatReplayDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) "$minutes min $seconds sec" else "$seconds sec"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var storageManager: StorageManager
    private var recorder: MediaRecorder? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null
    private var currentSegmentStartedAtMillis = 0L
    private var segmentRunnable: Runnable? = null
    private var cleanupRunnable: Runnable? = null
    private var expirationRunnable: Runnable? = null
    private var saveRetryRunnable: Runnable? = null
    private var pendingReplayWindowEndMillis = 0L
    private var segmentMinutes = StorageManager.DEFAULT_SEGMENT_MINUTES
    private var isRecording = false
    private var isSaving = false
    private var isForeground = false
    private var recordingWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        storageManager = StorageManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                promoteToForeground("Preparing microphone recording…")
                val requested = intent.getIntExtra(
                    EXTRA_SEGMENT_MINUTES,
                    storageManager.getSegmentMinutes()
                )
                try {
                    startRecording(requested)
                } catch (exception: Exception) {
                    failRecording(
                        "Recording could not start: ${exception.message ?: "microphone or storage error"}",
                        exception
                    )
                }
            }
            ACTION_STOP -> {
                if (!isForeground) promoteToForeground("Stopping recording…")
                stopRecording()
                publishStatus("Recording stopped.")
            }
            ACTION_SAVE -> {
                if (!isForeground) promoteToForeground("Saving the instant replay…")
                saveReplay(
                    intent.getLongExtra(
                        EXTRA_REPLAY_WINDOW_END_MILLIS,
                        System.currentTimeMillis()
                    )
                )
            }
            null -> {
                if (storageManager.isRecording()) {
                    try {
                        promoteToForeground("Resuming microphone recording…")
                        startRecording(storageManager.getSegmentMinutes())
                    } catch (exception: Exception) {
                        failRecording(
                            "Recording could not resume: ${exception.message ?: "microphone or storage error"}",
                            exception
                        )
                    }
                } else {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return if (isRecording) START_STICKY else START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Diary recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when microphone recording is active"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val saveIntent = Intent(this, RecordingService::class.java).setAction(ACTION_SAVE)
        val savePendingIntent = PendingIntent.getService(
            this,
            3,
            saveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Diary")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_call_mute)
            .setContentIntent(openAppPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isRecording || isSaving)
            .addAction(0, "Save", savePendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun promoteToForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun updateNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun startRecording(requestedMinutes: Int) {
        if (isRecording) return
        segmentMinutes = requestedMinutes.takeIf {
            it in StorageManager.SEGMENT_LENGTHS_MINUTES
        } ?: StorageManager.DEFAULT_SEGMENT_MINUTES
        storageManager.getActiveSegmentUri()?.let { abandonedUri ->
            if (!storageManager.deleteUri(abandonedUri)) {
                Log.w(TAG, "Could not remove the incomplete recording left by a previous service.")
            }
        }
        storageManager.clearRecordingState()

        cleanupExpiredSegments()
        startNewSegment()
        isRecording = true
        acquireRecordingWakeLock()
        scheduleNextRotation()
        scheduleCleanup()
        publishStatus("Recording microphone audio. Save keeps up to $segmentMinutes minutes.")
        updateNotification("Microphone recording active")
    }

    private fun startNewSegment() {
        val uri = storageManager.createLoopSegment(System.currentTimeMillis())
        var descriptor: ParcelFileDescriptor? = null
        var newRecorder: MediaRecorder? = null
        try {
            descriptor = storageManager.openForRecording(uri)
            newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(descriptor.fileDescriptor)
                prepare()
                start()
            }
        } catch (exception: Exception) {
            try {
                newRecorder?.release()
            } catch (releaseException: RuntimeException) {
                Log.w(TAG, "Could not release recorder after setup failure.", releaseException)
            }
            try {
                descriptor?.close()
            } catch (closeException: IOException) {
                Log.w(TAG, "Could not close audio file after setup failure.", closeException)
            }
            storageManager.deleteUri(uri)
            throw exception
        }
        recorder = newRecorder
        outputDescriptor = descriptor
        currentUri = uri
        currentSegmentStartedAtMillis = System.currentTimeMillis()
        storageManager.markRecording(uri, currentSegmentStartedAtMillis, segmentMinutes)
    }

    private fun scheduleNextRotation() {
        segmentRunnable?.let(mainHandler::removeCallbacks)
        segmentRunnable = Runnable {
            try {
                finishCurrentSegment()
                startNewSegment()
                scheduleNextRotation()
                cleanupExpiredSegments()
                scheduleNextExpirationCleanup()
                updateNotification("Microphone recording active")
            } catch (exception: Exception) {
                failRecording(
                    "Recording stopped after a segment error: ${exception.message ?: "unknown error"}",
                    exception
                )
            }
        }
        mainHandler.postDelayed(segmentRunnable!!, StorageManager.REPLAY_CHUNK_MILLIS)
    }

    private fun acquireRecordingWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        recordingWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:recording"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60_000L)
        }
    }

    private fun renewRecordingWakeLock() {
        recordingWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) {
                wakeLock.release()
                wakeLock.acquire(10 * 60_000L)
            }
        }
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
        }
        recordingWakeLock = null
    }

    private fun scheduleCleanup() {
        cleanupRunnable?.let(mainHandler::removeCallbacks)
        cleanupRunnable = object : Runnable {
            override fun run() {
                if (!isRecording) return
                cleanupExpiredSegments()
                scheduleNextExpirationCleanup()
                renewRecordingWakeLock()
                mainHandler.postDelayed(this, CLEANUP_AND_WAKE_LOCK_RENEWAL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(cleanupRunnable!!, CLEANUP_AND_WAKE_LOCK_RENEWAL_INTERVAL_MILLIS)
        scheduleNextExpirationCleanup()
    }

    private fun scheduleNextExpirationCleanup(retryDelayMillis: Long? = null) {
        expirationRunnable?.let(mainHandler::removeCallbacks)
        if (!isRecording) return
        try {
            val delayMillis = retryDelayMillis ?: run {
                val expiresAtMillis =
                    storageManager.nextSegmentExpirationMillis(segmentMinutes, currentUri)
                        ?: return
                (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            expirationRunnable = Runnable {
                if (cleanupExpiredSegments()) {
                    scheduleNextExpirationCleanup()
                } else {
                    scheduleNextExpirationCleanup(CLEANUP_AND_WAKE_LOCK_RENEWAL_INTERVAL_MILLIS)
                }
            }
            mainHandler.postDelayed(expirationRunnable!!, delayMillis)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not schedule expired audio cleanup.", exception)
            publishStatus("Recording continues, but expired audio cleanup could not be scheduled: ${exception.message}")
            scheduleNextExpirationCleanup(CLEANUP_AND_WAKE_LOCK_RENEWAL_INTERVAL_MILLIS)
        }
    }

    private fun cleanupExpiredSegments(): Boolean {
        try {
            storageManager.deleteExpiredSegments(
                System.currentTimeMillis(),
                segmentMinutes,
                currentUri
            )
            return true
        } catch (exception: Exception) {
            Log.e(TAG, "Could not clean up expired loop recordings.", exception)
            publishStatus("Recording continues, but expired audio could not be deleted: ${exception.message}")
            return false
        }
    }

    private fun finishCurrentSegment(): LoopSegment? {
        val uri = currentUri ?: return null
        val startedAtMillis = currentSegmentStartedAtMillis
        var stopFailure: Exception? = null
        var endedAtMillis = System.currentTimeMillis()
        try {
            recorder?.stop()
            endedAtMillis = System.currentTimeMillis()
        } catch (exception: Exception) {
            stopFailure = exception
        } finally {
            try {
                recorder?.release()
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Could not release the media recorder.", exception)
            }
            recorder = null
            try {
                outputDescriptor?.close()
            } catch (exception: IOException) {
                Log.e(TAG, "Could not close the active recording.", exception)
            }
            outputDescriptor = null
            currentUri = null
            currentSegmentStartedAtMillis = 0L
        }
        if (stopFailure != null) {
            storageManager.deleteUri(uri)
            throw IOException("The audio segment could not be finalized.", stopFailure)
        }
        return try {
            val finalizedUri = storageManager.finalizeLoopSegment(uri, startedAtMillis, endedAtMillis)
            LoopSegment(
                finalizedUri,
                startedAtMillis,
                endedAtMillis,
                "$startedAtMillis to $endedAtMillis"
            )
        } catch (exception: Exception) {
            storageManager.deleteUri(uri)
            throw exception
        }
    }

    private fun saveReplay(windowEndMillis: Long) {
        if (isSaving) {
            publishStatus("A replay is already being saved.")
            return
        }
        isSaving = true
        pendingReplayWindowEndMillis = windowEndMillis
        updateNotification("Preparing the instant replay…")
        continueSavingReplay()
    }

    private fun continueSavingReplay() {
        val durationMinutes = storageManager.getSegmentMinutes()
        if (isRecording) {
            val segmentAge = System.currentTimeMillis() - currentSegmentStartedAtMillis
            val waitMillis = MINIMUM_SEGMENT_DURATION_MILLIS - segmentAge
            if (waitMillis > 0) {
                publishStatus("Preparing the replay…")
                saveRetryRunnable?.let(mainHandler::removeCallbacks)
                saveRetryRunnable = Runnable { continueSavingReplay() }
                mainHandler.postDelayed(saveRetryRunnable!!, waitMillis)
                return
            }
            segmentRunnable?.let(mainHandler::removeCallbacks)
            segmentRunnable = null
            try {
                finishCurrentSegment()
                startNewSegment()
                scheduleNextRotation()
                scheduleNextExpirationCleanup()
            } catch (exception: Exception) {
                isSaving = false
                failRecording(
                    "Could not prepare the replay: ${exception.message ?: "recording segment error"}",
                    exception
                )
                publishStatus("Could not save the replay: ${exception.message ?: "recording error"}")
                return
            }
        }

        val windowEndMillis = pendingReplayWindowEndMillis
        updateNotification("Saving available recent audio…")
        publishStatus("Saving available recent audio…")

        serviceScope.launch {
            var resultMessage = "Could not save the replay."
            try {
                lateinit var savedReplay: ReplaySaveResult
                withContext(Dispatchers.IO) {
                    savedReplay = storageManager.saveReplay(windowEndMillis, durationMinutes)
                }
                resultMessage =
                    "Saved ${formatReplayDuration(savedReplay.durationMillis)} of available audio."
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save the instant replay.", exception)
                resultMessage = exception.message ?: "Could not save the replay."
            } finally {
                isSaving = false
                if (isRecording) {
                    cleanupExpiredSegments()
                    scheduleNextExpirationCleanup()
                    updateNotification("Microphone recording active")
                } else if (isForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
                publishStatus(
                    if (isRecording) "$resultMessage Recording continues." else resultMessage
                )
            }
        }
    }

    private fun stopRecording() {
        segmentRunnable?.let(mainHandler::removeCallbacks)
        cleanupRunnable?.let(mainHandler::removeCallbacks)
        expirationRunnable?.let(mainHandler::removeCallbacks)
        segmentRunnable = null
        cleanupRunnable = null
        expirationRunnable = null
        if (isRecording) {
            try {
                finishCurrentSegment()
            } catch (exception: Exception) {
                Log.e(TAG, "Could not finalize the last recording segment.", exception)
            }
        }
        isRecording = false
        storageManager.clearRecordingState()
        releaseRecordingWakeLock()
        if (isForeground && !isSaving) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        if (!isSaving) stopSelf()
    }

    private fun failRecording(message: String, exception: Exception) {
        Log.e(TAG, message, exception)
        segmentRunnable?.let(mainHandler::removeCallbacks)
        cleanupRunnable?.let(mainHandler::removeCallbacks)
        expirationRunnable?.let(mainHandler::removeCallbacks)
        try {
            recorder?.release()
        } catch (releaseException: RuntimeException) {
            Log.w(TAG, "Could not release recorder after recording failure.", releaseException)
        }
        recorder = null
        try {
            outputDescriptor?.close()
        } catch (closeException: IOException) {
            Log.w(TAG, "Could not close the active file after recording failure.", closeException)
        }
        outputDescriptor = null
        currentUri?.let(storageManager::deleteUri)
        currentUri = null
        isRecording = false
        storageManager.clearRecordingState()
        releaseRecordingWakeLock()
        publishStatus(message)
        if (isForeground && !isSaving) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        if (!isSaving) stopSelf()
    }

    private fun publishStatus(message: String) {
        val statusIntent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_IS_RECORDING, isRecording)
            .putExtra(EXTRA_IS_SAVING, isSaving)
            .putExtra(EXTRA_STATUS_MESSAGE, message)
        sendBroadcast(statusIntent)
    }

    override fun onDestroy() {
        segmentRunnable?.let(mainHandler::removeCallbacks)
        cleanupRunnable?.let(mainHandler::removeCallbacks)
        expirationRunnable?.let(mainHandler::removeCallbacks)
        saveRetryRunnable?.let(mainHandler::removeCallbacks)
        if (isRecording) {
            try {
                finishCurrentSegment()
            } catch (exception: Exception) {
                Log.e(TAG, "Could not finalize recording during service shutdown.", exception)
            }
            isRecording = false
            storageManager.clearRecordingState()
            releaseRecordingWakeLock()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
