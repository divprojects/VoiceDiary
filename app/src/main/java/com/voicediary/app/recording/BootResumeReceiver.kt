package com.voicediary.app.recording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.voicediary.app.ui.MainActivity

internal object BootResumeState {
    private const val PREFS_NAME = "voice_diary_boot_resume"
    private const val KEY_RESUME_PENDING = "resume_pending"

    fun isResumePending(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESUME_PENDING, false)

    fun setResumePending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_RESUME_PENDING, pending) }
    }

    fun clearResumePending(context: Context) = setResumePending(context, false)
}

class BootResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val preferences = context.getSharedPreferences("loop_recorder_prefs", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("is_recording", false)) return

        BootResumeState.setResumePending(context, true)
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                "Resume Voice Diary recording",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tap to resume microphone recording after a device restart"
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_RESUME_RECORDING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            RESUME_PENDING_INTENT_REQUEST,
            resumeIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Voice Diary is ready to resume")
            .setContentText("Recording was active before restart. Tap to resume.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(
                0,
                "Resume recording",
                pendingIntent
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_RESUME_RECORDING = "com.voicediary.app.RESUME_AFTER_BOOT"

        private const val CHANNEL_ID = "voice_diary_boot_resume"
        private const val NOTIFICATION_ID = 1002
        private const val RESUME_PENDING_INTENT_REQUEST = 1002
    }
}
