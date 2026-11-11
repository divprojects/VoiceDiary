package com.voicediary.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.voicediary.app.R
import com.voicediary.app.recording.BootResumeReceiver
import com.voicediary.app.recording.BootResumeState
import com.voicediary.app.recording.RecordingService
import com.voicediary.app.storage.SavedRecording
import com.voicediary.app.storage.StorageManager
import com.voicediary.app.update.GitHubReleaseClient
import com.voicediary.app.update.ReleaseVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var storageManager: StorageManager
    private lateinit var statusText: TextView
    private lateinit var folderText: TextView
    private lateinit var availableDurationText: TextView
    private lateinit var recordingStateDot: View
    private lateinit var recordingStateText: TextView
    private lateinit var storageStatusText: TextView
    private lateinit var saveProgress: ProgressBar
    private lateinit var mainScrollView: android.widget.ScrollView
    private lateinit var mainContent: LinearLayout
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var changeFolderButton: Button
    private lateinit var openRecordingsButton: Button
    private lateinit var clearChunksButton: Button
    private lateinit var updateButton: ImageButton
    private lateinit var themeToggleButton: ImageButton
    private lateinit var segmentLengthPickerButton: TextView
    private lateinit var onboardingOverlay: FrameLayout
    private var onboardingStepIndex = 0
    private var onboardingTourSteps: List<CoachMarkStep>? = null
    private var onboardingBackCallback: OnBackPressedCallback? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var isSaving = false
    private var receiverRegistered = false
    private var startAfterFolderSelection = false
    private var changeFolderAfterStop = false
    private var isLightTheme = false
    private var lastStorageRefreshAt = 0L
    private var storageAccessAvailable: Boolean? = null
    private var resumeAfterRestartPending = false
    private var pendingApkToInstall: java.io.File? = null
    private data class UiPalette(
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
        val border: Int,
        val danger: Int
    )
    private val replayWindowUpdater = object : Runnable {
        override fun run() {
            updateReplayWindowLabel()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != RecordingService.ACTION_STATUS) return
            isRecording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
            isSaving = intent.getBooleanExtra(RecordingService.EXTRA_IS_SAVING, false)
            if (isRecording) {
                resumeAfterRestartPending = false
                BootResumeState.clearResumePending(this@MainActivity)
            }
            statusText.text = intent.getStringExtra(RecordingService.EXTRA_STATUS_MESSAGE)
                ?: if (isRecording) "Recording microphone audio." else "Recording stopped."
            if (statusText.text.startsWith("Saved ")) {
                Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_SHORT).show()
            }
            updateButtons()
            updateFolderLabel()
            if (changeFolderAfterStop && !isRecording) {
                changeFolderAfterStop = false
                launchFolderPicker(startWhenSelected = false)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationPermissionIfNeeded()
        } else {
            statusText.setText(R.string.permission_start_instructions)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        prepareToStartRecording()
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            startAfterFolderSelection = false
            statusText.setText(R.string.folder_selection_cancelled)
            return@registerForActivityResult
        }
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            storageManager.saveTreeUri(uri, flags)
            storageAccessAvailable = null
            val shouldStart = startAfterFolderSelection
            startAfterFolderSelection = false
            updateFolderLabel()
            if (shouldStart) {
                prepareToStartRecording()
            } else {
                statusText.setText(R.string.folder_changed)
            }
        } catch (exception: Exception) {
            startAfterFolderSelection = false
            statusText.text = getString(
                R.string.folder_use_failed,
                exception.message ?: getString(R.string.storage_access_failed)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        isLightTheme = getSharedPreferences(THEME_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_LIGHT_THEME, false)
        setTheme(if (isLightTheme) R.style.Theme_VoiceDiary_Light else R.style.Theme_VoiceDiary)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storageManager = StorageManager(this)
        mainScrollView = findViewById(R.id.mainScrollView)
        mainContent = findViewById(R.id.mainContent)
        statusText = findViewById(R.id.statusText)
        folderText = findViewById(R.id.folderText)
        storageStatusText = findViewById(R.id.storageStatusText)
        saveProgress = findViewById(R.id.saveProgress)
        availableDurationText = findViewById(R.id.availableDurationText)
        recordingStateDot = findViewById(R.id.recordingStateDot)
        recordingStateText = findViewById(R.id.recordingStateText)
        startButton = findViewById(R.id.startButton)
        saveButton = findViewById(R.id.saveButton)
        changeFolderButton = findViewById(R.id.changeFolderButton)
        openRecordingsButton = findViewById(R.id.openRecordingsButton)
        clearChunksButton = findViewById(R.id.clearChunksButton)
        updateButton = findViewById(R.id.updateButton)
        themeToggleButton = findViewById(R.id.themeToggleButton)
        segmentLengthPickerButton = findViewById(R.id.segmentLengthPickerButton)
        configureEdgeToEdgeInsets()

        updateDurationPickerLabel()
        updateReplayWindowLabel()
        segmentLengthPickerButton.setOnClickListener {
            if (!isRecording && !isSaving) showReplayLengthPicker()
        }

        startButton.setOnClickListener {
            if (isRecording) {
                confirmStopRecording()
            } else {
                requestMicrophoneAndStart()
            }
        }
        saveButton.setOnClickListener { saveReplay() }
        openRecordingsButton.setOnClickListener { showSavedRecordingsPlayerSheet() }
        clearChunksButton.setOnClickListener { confirmClearAudioChunks() }
        updateButton.setOnClickListener { showUpdateStatus() }
        themeToggleButton.setOnClickListener {
            isLightTheme = !isLightTheme
            getSharedPreferences(THEME_PREFS, MODE_PRIVATE).edit {
                putBoolean(KEY_LIGHT_THEME, isLightTheme)
            }
            recreate()
        }
        changeFolderButton.setOnClickListener {
            if (isRecording) {
                changeFolderAfterStop = true
                statusText.setText(R.string.stopping_to_change_folder)
                sendServiceAction(RecordingService.ACTION_STOP)
            } else {
                showStorageSelectionDialog(startWhenSelected = false)
            }
        }
        resumeAfterRestartPending = BootResumeState.isResumePending(this)
        isRecording = storageManager.isRecording() && !resumeAfterRestartPending
        applyTheme()
        updateFolderLabel()
        updateButtons()
        statusText.text = if (isRecording) {
            "Recording is active. Android may restrict microphone access during calls or when the app is stopped."
        } else if (resumeAfterRestartPending) {
            "Recording was active before the restart. Tap Start Recording to resume."
        } else {
            idleInstructions()
        }
        val interactiveViews = listOf(
            startButton,
            saveButton,
            changeFolderButton,
            openRecordingsButton,
            clearChunksButton,
            updateButton,
            themeToggleButton,
            segmentLengthPickerButton
        )
        interactiveViews.forEach { applyIosTouchFeedback(it) }

        val resumeFromBootNotification =
            intent?.action == BootResumeReceiver.ACTION_RESUME_RECORDING
        if (!resumeFromBootNotification) {
            mainContent.post {
                showOnboardingTourIfNeeded()
            }
        } else {
            mainContent.post { requestMicrophoneAndStart() }
        }
        if (storageManager.hasFolderSelection()) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        storageManager.deleteExpiredSegments(
                            System.currentTimeMillis(),
                            storageManager.getSegmentMinutes(),
                            storageManager.getActiveSegmentUri().takeIf { isRecording }
                        )
                    }
                } catch (exception: Exception) {
                    statusText.setText(R.string.cleanup_failed)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                serviceStatusReceiver,
                IntentFilter(RecordingService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        resumeAfterRestartPending = BootResumeState.isResumePending(this)
        isRecording = storageManager.isRecording() && !resumeAfterRestartPending
        checkMicrophonePermission()
        updateFolderLabel()
        updateButtons()
        uiHandler.removeCallbacks(replayWindowUpdater)
        uiHandler.post(replayWindowUpdater)
        if (pendingApkToInstall != null && packageManager.canRequestPackageInstalls()) {
            val apkFile = pendingApkToInstall
            pendingApkToInstall = null
            installApk(apkFile!!)
        }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(replayWindowUpdater)
        if (receiverRegistered) {
            unregisterReceiver(serviceStatusReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == BootResumeReceiver.ACTION_RESUME_RECORDING) {
            resumeAfterRestartPending = true
            isRecording = false
            statusText.setText(R.string.resuming_from_notification)
            requestMicrophoneAndStart()
        }
    }

    private fun requestMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionIfNeeded()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareToStartRecording()
        }
    }

    private fun prepareToStartRecording() {
        if (!storageManager.hasFolderSelection()) {
            showStorageSelectionDialog(startWhenSelected = true)
            return
        }
        startRecording()
    }

    private fun showStorageSelectionDialog(startWhenSelected: Boolean) {
        startAfterFolderSelection = startWhenSelected
        launchFolderPicker(startWhenSelected)
    }

    private fun launchFolderPicker(startWhenSelected: Boolean) {
        startAfterFolderSelection = startWhenSelected
        try {
            folderPicker.launch(storageManager.getSelectedTreeUri())
        } catch (exception: Exception) {
            startAfterFolderSelection = false
            changeFolderAfterStop = false
            statusText.text = getString(
                R.string.folder_picker_failed,
                exception.message ?: getString(R.string.system_error)
            )
        }
    }

    private fun startRecording() {
        val minutes = storageManager.getSegmentMinutes()
        storageManager.setSegmentMinutes(minutes)
        statusText.setText(R.string.recording_starting)
        isRecording = true
        resumeAfterRestartPending = false
        updateButtons()
        sendServiceAction(RecordingService.ACTION_START, minutes)
    }

    private fun sendServiceAction(
        action: String,
        segmentMinutes: Int? = null,
        replayWindowEndMillis: Long? = null
    ) {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
            segmentMinutes?.let { putExtra(RecordingService.EXTRA_SEGMENT_MINUTES, it) }
            replayWindowEndMillis?.let {
                putExtra(RecordingService.EXTRA_REPLAY_WINDOW_END_MILLIS, it)
            }
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (exception: Exception) {
            if (action == RecordingService.ACTION_START) {
                isRecording = false
                updateButtons()
            }
            if (action == RecordingService.ACTION_SAVE) {
                isSaving = false
                updateButtons()
            }
            statusText.text = getString(
                R.string.service_start_failed,
                exception.message ?: getString(R.string.system_restriction)
            )
        }
    }

    private fun saveReplay() {
        if (isSaving) return
        val tapTimeMillis = System.currentTimeMillis()
        val durationMinutes = storageManager.getSegmentMinutes()
        val replayEndMillis = if (isRecording) {
            tapTimeMillis
        } else {
            storageManager.getLatestAudioEndMillis().takeIf { it > 0L } ?: tapTimeMillis
        }
        isSaving = true
        saveButton.isEnabled = false
        updateButtons()
        statusText.setText(R.string.saving_available_audio)
        if (isRecording) {
            sendServiceAction(
                RecordingService.ACTION_SAVE,
                replayWindowEndMillis = replayEndMillis
            )
            return
        }
        lifecycleScope.launch {
            try {
                saveProgress.visibility = View.VISIBLE
                val savedReplay = withContext(Dispatchers.IO) {
                    storageManager.saveReplay(replayEndMillis, durationMinutes)
                }
                statusText.text = getString(
                    R.string.saved_audio_duration,
                    formatReplayDuration(savedReplay.durationMillis)
                )
                Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_SHORT).show()
            } catch (exception: Exception) {
                statusText.text = exception.message ?: "Could not save the replay."
            } finally {
                saveProgress.visibility = View.GONE
                isSaving = false
                updateButtons()
            }
        }
    }

    private fun updateFolderLabel() {
        val hasSelection = storageManager.hasFolderSelection()
        folderText.text = if (storageManager.hasFolderSelection()) {
            try {
                "Recordings folder: ${storageManager.getCurrentFolderLabel()}"
            } catch (exception: Exception) {
                "Recordings folder is unavailable. Choose a new folder before recording."
            }
        } else {
            "Recordings folder: not selected. Choose a folder before starting."
        }
        changeFolderButton.text = if (hasSelection) {
            "Change Recordings Folder"
        } else {
            "Choose Recordings Folder"
        }
        if (!hasSelection) {
            storageAccessAvailable = false
            storageStatusText.setText(R.string.choose_folder_storage_usage)
        } else if (storageAccessAvailable == null) {
            storageStatusText.setText(R.string.folder_storage_checking)
        }
        updateButtons()
        refreshStorageStatus(force = true)
    }

    private fun applyTheme() {
        val colors = palette()
        mainScrollView.setBackgroundColor(colors.background)
        mainContent.setBackgroundColor(colors.background)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLightTheme
            isAppearanceLightNavigationBars = isLightTheme
        }
        listOf(R.id.bufferCard, R.id.settingsCard, R.id.recordingsCard).forEach { id ->
            findViewById<LinearLayout>(id).background = roundedSurface(colors.border, colors.surface)
        }
        folderText.setTextColor(colors.secondaryText)
        storageStatusText.setTextColor(colors.secondaryText)
        updateButtons()
        styleButton(changeFolderButton, colors.surface, colors.accent, colors.border)
        styleButton(clearChunksButton, colors.surface, colors.danger, colors.border)
        styleButton(updateButton, colors.surface, colors.accent, colors.border)
        styleButton(themeToggleButton, colors.surface, colors.accent, colors.border)
        themeToggleButton.setImageResource(
            if (isLightTheme) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun
        )
        themeToggleButton.contentDescription =
            getString(if (isLightTheme) R.string.switch_to_dark_theme else R.string.switch_to_light_theme)
        updateDurationPickerLabel()
        saveProgress.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(colors.accent)
    }

    private fun configureEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(mainScrollView) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                safeInsets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(mainScrollView)
    }

    private fun styleButton(button: Button, fillColor: Int, textColor: Int, strokeColor: Int) {
        button.backgroundTintList = null
        val base = roundedControl(strokeColor, fillColor)
        button.background = rippleBackground(base, textColor)
        button.setTextColor(textColor)
    }

    private fun styleButton(button: ImageButton, fillColor: Int, tintColor: Int, strokeColor: Int) {
        button.backgroundTintList = null
        button.background = rippleBackground(roundedControl(strokeColor, fillColor), tintColor)
        button.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
    }

    private fun rippleBackground(content: android.graphics.drawable.Drawable?, color: Int) =
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                Color.argb(36, Color.red(color), Color.green(color), Color.blue(color))
            ),
            content,
            null
        )

    private fun palette() = if (isLightTheme) {
        UiPalette(
            ContextCompat.getColor(this, R.color.ios_light_background),
            ContextCompat.getColor(this, R.color.ios_light_surface),
            ContextCompat.getColor(this, R.color.ios_light_text),
            ContextCompat.getColor(this, R.color.ios_light_secondary),
            ContextCompat.getColor(this, R.color.ios_light_blue),
            ContextCompat.getColor(this, R.color.ios_light_border),
            ContextCompat.getColor(this, R.color.ios_red)
        )
    } else {
        UiPalette(
            ContextCompat.getColor(this, R.color.ios_dark_background),
            ContextCompat.getColor(this, R.color.ios_dark_surface),
            ContextCompat.getColor(this, R.color.ios_dark_text),
            ContextCompat.getColor(this, R.color.ios_dark_secondary),
            ContextCompat.getColor(this, R.color.ios_dark_blue),
            ContextCompat.getColor(this, R.color.ios_dark_border),
            ContextCompat.getColor(this, R.color.ios_dark_red)
        )
    }

    private fun roundedControl(strokeColor: Int, fillColor: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
            cornerRadius = 14 * resources.displayMetrics.density
        }

    private fun roundedSurface(strokeColor: Int, fillColor: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
            cornerRadius = 20 * resources.displayMetrics.density
        }

    private fun updateButtons() {
        startButton.isEnabled = !isSaving
        startButton.setText(if (isRecording) R.string.stop_recording else R.string.start_recording)
        startButton.contentDescription =
            getString(if (isRecording) R.string.stop_recording_accessibility else R.string.start_recording_accessibility)
        val colors = palette()
        styleButton(
            startButton,
            if (isRecording) colors.danger else colors.accent,
            if (isLightTheme) Color.WHITE else Color.BLACK,
            if (isRecording) colors.danger else colors.accent
        )
        val latestAudioEnd = storageManager.getLatestAudioEndMillis()
        val replayStillAvailable = latestAudioEnd >
            System.currentTimeMillis() - storageManager.getSegmentMinutes() * 60_000L
        val canSaveAudio = storageManager.hasFolderSelection() &&
            storageAccessAvailable == true &&
            (isRecording || replayStillAvailable)
        saveButton.isEnabled = !isSaving && canSaveAudio
        styleButton(
            saveButton,
            if (saveButton.isEnabled) colors.accent else colors.background,
            if (saveButton.isEnabled) {
                if (isLightTheme) Color.WHITE else Color.BLACK
            } else {
                colors.secondaryText
            },
            if (saveButton.isEnabled) colors.accent else colors.background
        )
        saveButton.setText(if (isSaving) R.string.saving_replay else R.string.save_available_audio)
        saveProgress.visibility = if (isSaving) View.VISIBLE else View.GONE
        recordingStateText.setText(if (isRecording) R.string.recording_audio else R.string.not_recording)
        val stateColor = if (isRecording) colors.danger else colors.secondaryText
        recordingStateText.setTextColor(stateColor)
        recordingStateDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(stateColor)
        }
        recordingStateDot.alpha = 1f
        recordingStateDot.scaleX = 1f
        recordingStateDot.scaleY = 1f
        val hasRecordingsFolder =
            storageManager.hasFolderSelection() && storageAccessAvailable == true
        openRecordingsButton.isEnabled = hasRecordingsFolder && !isSaving
        openRecordingsButton.contentDescription = if (hasRecordingsFolder) {
            getString(R.string.open_saved_audio_accessibility)
        } else {
            getString(R.string.choose_folder_before_opening_accessibility)
        }

        styleButton(
            openRecordingsButton,
            if (hasRecordingsFolder) colors.surface else colors.background,
            if (hasRecordingsFolder) colors.accent else colors.secondaryText,
            colors.border
        )
        val canChangeReplayLength = !isRecording && !isSaving
        segmentLengthPickerButton.isEnabled = canChangeReplayLength
        segmentLengthPickerButton.setTextColor(
            if (canChangeReplayLength) colors.text else colors.secondaryText
        )
        segmentLengthPickerButton.compoundDrawablesRelative[2]?.setTint(colors.secondaryText)
        val replayLengthBackground = if (canChangeReplayLength) {
            rippleBackground(
                roundedControl(colors.border, colors.surface),
                colors.accent
            )
        } else {
            roundedControl(colors.background, colors.background)
        }
        segmentLengthPickerButton.background = replayLengthBackground
        segmentLengthPickerButton.contentDescription = resources.getQuantityString(
            R.plurals.replay_length_accessibility,
            storageManager.getSegmentMinutes(),
            storageManager.getSegmentMinutes()
        )
        val canChangeFolder = !isSaving && !isRecording
        changeFolderButton.isEnabled = canChangeFolder
        styleButton(
            changeFolderButton,
            if (canChangeFolder) colors.surface else colors.background,
            if (canChangeFolder) colors.accent else colors.secondaryText,
            if (canChangeFolder) colors.border else colors.background
        )
        val canClearChunks = storageManager.hasFolderSelection() &&
            storageAccessAvailable == true && !isRecording && !isSaving
        clearChunksButton.isEnabled = canClearChunks
        styleButton(
            clearChunksButton,
            if (canClearChunks) colors.surface else colors.background,
            if (canClearChunks) colors.danger else colors.secondaryText,
            if (canClearChunks) colors.border else colors.background
        )
        clearChunksButton.contentDescription =
            if (isRecording) {
                getString(R.string.stop_before_clearing_accessibility)
            } else {
                getString(R.string.delete_unsaved_chunks_accessibility)
            }
        updateButton.isEnabled = !isSaving
    }

    private fun applyIosTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val isInside = event.x >= 0 && event.y >= 0 &&
                        event.x < v.width && event.y < v.height
                    if (isInside) v.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun updateDurationPickerLabel() {
        val minutes = storageManager.getSegmentMinutes()
        segmentLengthPickerButton.text = resources.getQuantityString(
            R.plurals.minutes_format,
            minutes,
            minutes
        )
    }

    private data class CoachMarkStep(
        val target: View,
        val title: String,
        val description: String
    )

    private fun showOnboardingTourIfNeeded() {
        if (getSharedPreferences(THEME_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_COMPLETED, false)
        ) return
        onboardingStepIndex = 0
        onboardingTourSteps = onboardingSteps()
        showOnboardingStep()
    }

    private fun onboardingSteps(): List<CoachMarkStep> = buildList {
        add(
            CoachMarkStep(
                startButton,
                "Start recording",
                "Allow microphone access and choose a writable recordings folder when prompted. Recording starts after setup."
            )
        )
        add(
            CoachMarkStep(
                segmentLengthPickerButton,
                "Choose replay length",
                "Choose how many recent minutes to keep. Older unsaved audio is removed automatically."
            )
        )
        add(
            CoachMarkStep(
                changeFolderButton,
                if (storageManager.hasFolderSelection()) "Change recordings folder"
                else "Choose recordings folder",
                "Select where Voice Diary stores temporary audio chunks and saved replays."
            )
        )
    }

    private fun showOnboardingStep() {
        val steps = onboardingTourSteps ?: onboardingSteps().also { onboardingTourSteps = it }
        if (onboardingStepIndex >= steps.size) {
            finishOnboardingTour()
            return
        }
        val step = steps[onboardingStepIndex]
        mainContent.post {
            if (isFinishing || isDestroyed || onboardingStepIndex >= steps.size) return@post
            scrollToGuideTarget(step.target)
            displayOnboardingStep(steps[onboardingStepIndex], steps.size)
        }
    }

    private fun scrollToGuideTarget(target: View) {
        val targetPosition = IntArray(2)
        val scrollPosition = IntArray(2)
        target.getLocationOnScreen(targetPosition)
        mainScrollView.getLocationOnScreen(scrollPosition)

        val margin = (24 * resources.displayMetrics.density).toInt()
        val targetTop = targetPosition[1] - scrollPosition[1]
        val targetBottom = targetTop + target.height
        val visibleTop = mainScrollView.paddingTop
        val visibleBottom = mainScrollView.height - mainScrollView.paddingBottom
        val scrollDelta = when {
            target.height > visibleBottom - visibleTop ->
                targetTop - visibleTop - margin
            targetBottom > visibleBottom - margin ->
                targetBottom - visibleBottom + margin
            targetTop < visibleTop + margin ->
                targetTop - visibleTop - margin
            else -> 0
        }
        if (scrollDelta != 0) mainScrollView.scrollBy(0, scrollDelta)
    }

    private fun displayOnboardingStep(step: CoachMarkStep, stepCount: Int) {
        val colors = palette()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val decor = window.decorView as ViewGroup
        if (onboardingBackCallback == null) {
            onboardingBackCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            }.also { onBackPressedDispatcher.addCallback(this, it) }
        }
        onboardingOverlay = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val spotlight = CoachMarkOverlayView(this).apply { setAccentColor(colors.accent) }
        onboardingOverlay.addView(
            spotlight,
            FrameLayout.LayoutParams(-1, -1)
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, Color.red(colors.surface), Color.green(colors.surface), Color.blue(colors.surface)))
                setStroke(dp(1), Color.argb(150, Color.red(colors.border), Color.green(colors.border), Color.blue(colors.border)))
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.guide_step_counter, onboardingStepIndex + 1, stepCount)
            textSize = 12f
            letterSpacing = 0.06f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(colors.accent)
        })
        card.addView(TextView(this).apply {
            text = step.title
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(colors.text)
            setPadding(0, dp(10), 0, dp(7))
        })
        card.addView(TextView(this).apply {
            text = step.description
            textSize = 15f
            setTextColor(colors.secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        val skip = TextView(this).apply {
            setText(R.string.skip)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(colors.accent)
            minHeight = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = getString(R.string.skip_app_guide)
            setOnClickListener { finishOnboardingTour() }
        }
        val next = TextView(this).apply {
            setText(if (onboardingStepIndex == stepCount - 1) R.string.done else R.string.next)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (isLightTheme) Color.WHITE else Color.BLACK)
            minHeight = dp(46)
            setPadding(dp(22), 0, dp(22), 0)
            background = GradientDrawable().apply {
                setColor(colors.accent)
                cornerRadius = dp(23).toFloat()
            }
            contentDescription =
                getString(
                    if (onboardingStepIndex == stepCount - 1) {
                        R.string.finish_app_guide
                    } else {
                        R.string.show_next_guide_step
                    }
                )
            setOnClickListener {
                (onboardingOverlay.parent as? ViewGroup)?.removeView(onboardingOverlay)
                onboardingStepIndex++
                showOnboardingStep()
            }
        }
        actions.addView(skip, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(next, LinearLayout.LayoutParams(-2, -2))
        card.addView(actions)
        onboardingOverlay.addView(
            card,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(420)),
                -2,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        decor.addView(onboardingOverlay, ViewGroup.LayoutParams(-1, -1))
        next.requestFocus()

        onboardingOverlay.post {
            if (onboardingOverlay.parent == null) return@post
            val targetPosition = IntArray(2)
            val overlayPosition = IntArray(2)
            step.target.getLocationOnScreen(targetPosition)
            onboardingOverlay.getLocationOnScreen(overlayPosition)
            val targetRect = RectF(
                (targetPosition[0] - overlayPosition[0] - dp(8)).toFloat(),
                (targetPosition[1] - overlayPosition[1] - dp(8)).toFloat(),
                (targetPosition[0] - overlayPosition[0] + step.target.width + dp(8)).toFloat(),
                (targetPosition[1] - overlayPosition[1] + step.target.height + dp(8)).toFloat()
            )
            spotlight.updateTarget(targetRect)

            val cardWidth = card.width
            val cardHeight = card.height
            val sideMargin = dp(20)
            val belowTop = targetRect.bottom + dp(22)
            val aboveTop = targetRect.top - cardHeight - dp(22)
            val cardTop = if (belowTop + cardHeight <= onboardingOverlay.height - sideMargin) {
                belowTop
            } else {
                aboveTop.coerceAtLeast(sideMargin.toFloat())
            }
            val cardLeft = ((onboardingOverlay.width - cardWidth) / 2).coerceAtLeast(sideMargin)
            (card.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = cardLeft
                topMargin = cardTop.toInt()
                gravity = Gravity.TOP or Gravity.START
                width = cardWidth
                height = cardHeight
            }.also(card::setLayoutParams)
            spotlight.updateCard(
                RectF(
                    cardLeft.toFloat(),
                    cardTop,
                    (cardLeft + cardWidth).toFloat(),
                    cardTop + cardHeight
                )
            )
        }
    }

    private fun finishOnboardingTour() {
        if (::onboardingOverlay.isInitialized) {
            (onboardingOverlay.parent as? ViewGroup)?.removeView(onboardingOverlay)
        }
        onboardingBackCallback?.remove()
        onboardingBackCallback = null
        getSharedPreferences(THEME_PREFS, MODE_PRIVATE).edit {
        putBoolean(KEY_ONBOARDING_COMPLETED, true)
        }
        onboardingTourSteps = null
    }

    private fun showReplayLengthPicker() {
        val colors = palette()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val picker = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = StorageManager.SEGMENT_LENGTHS_MINUTES.lastIndex
            displayedValues = StorageManager.SEGMENT_LENGTHS_MINUTES
                .map { resources.getQuantityString(R.plurals.minutes_format, it, it) }
                .toTypedArray()
            value = StorageManager.SEGMENT_LENGTHS_MINUTES
                .indexOf(storageManager.getSegmentMinutes())
                .coerceAtLeast(0)
            wrapSelectorWheel = false
            descendantFocusability = android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setOnLongPressUpdateInterval(100)
        }

        val dialog = android.app.Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = roundedSurface(colors.border, colors.surface)
        }

        val titleText = TextView(this).apply {
            setText(R.string.maximum_replay_buffer)
            textSize = 18f
            setTextColor(colors.text)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        panel.addView(titleText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        panel.addView(picker, LinearLayout.LayoutParams(-1, dp(180)))

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cancelButton = Button(this).apply {
            setText(R.string.cancel)
            textSize = 15f
            minHeight = dp(48)
            setTextColor(colors.secondaryText)
            background = roundedControl(colors.border, colors.surface)
            setOnClickListener { dialog.dismiss() }
        }

        val doneButton = Button(this).apply {
            setText(R.string.done)
            textSize = 15f
            minHeight = dp(48)
            setTextColor(if (isLightTheme) Color.WHITE else Color.BLACK)
            background = roundedControl(colors.accent, colors.accent)
            setOnClickListener {
                storageManager.setSegmentMinutes(
                    StorageManager.SEGMENT_LENGTHS_MINUTES[picker.value]
                )
                updateDurationPickerLabel()
                updateReplayWindowLabel()
                dialog.dismiss()
            }
        }

        buttonsRow.addView(cancelButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        buttonsRow.addView(doneButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        panel.addView(buttonsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(-1, -2)
        }
        configurePopupWindow(dialog)
        dialog.show()
    }

    private fun showSavedRecordingsPlayerSheet() {
        lifecycleScope.launch {
            try {
                val recordings = withContext(Dispatchers.IO) {
                    storageManager.listSavedRecordings()
                }
                if (!isFinishing && !isDestroyed) {
                    showSavedRecordingsPlayerSheet(recordings)
                }
            } catch (exception: Exception) {
                statusText.text = getString(
                    R.string.load_saved_recordings_failed,
                    exception.message ?: getString(R.string.storage_access_failed)
                )
            }
        }
    }

    private fun showSavedRecordingsPlayerSheet(recordings: List<SavedRecording>) {
        val colors = palette()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialog = android.app.Dialog(this)
        var activePlayer: android.media.MediaPlayer? = null
        var activePlayButton: TextView? = null
        var activePlayerName: String? = null

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(colors.surface)
                cornerRadius = dp(20).toFloat()
            }
        }

        val titleText = TextView(this).apply {
            setText(R.string.saved_recordings_title)
            textSize = 20f
            setTextColor(colors.text)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        panel.addView(titleText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        if (recordings.isEmpty()) {
            val emptyText = TextView(this).apply {
                setText(R.string.no_saved_recordings_yet)
                textSize = 15f
                setTextColor(colors.secondaryText)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
            panel.addView(emptyText)
        } else {
            val scrollView = android.widget.ScrollView(this).apply {
                isFillViewport = true
            }
            val listLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (rec in recordings) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = roundedControl(colors.border, Color.TRANSPARENT)
                }

                val rowHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val nameText = TextView(this).apply {
                    text = rec.name
                    textSize = 15f
                    setTextColor(colors.text)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                rowHeader.addView(nameText, LinearLayout.LayoutParams(0, -2, 1f))

                val playButton = TextView(this).apply {
                    setText(R.string.play)
                    textSize = 14f
                    setTextColor(colors.accent)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    minWidth = dp(64)
                    minHeight = dp(48)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = roundedControl(colors.accent, Color.TRANSPARENT)
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.play_recording_accessibility, rec.name)
                    applyIosTouchFeedback(this)
                }
                rowHeader.addView(playButton, LinearLayout.LayoutParams(-2, -2))

                row.addView(rowHeader, LinearLayout.LayoutParams(-1, -2))

                val dateStr = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(java.util.Date(rec.lastModifiedMillis))
                val sizeStr = String.format(
                    Locale.getDefault(),
                    getString(R.string.recording_size_megabytes),
                    rec.sizeBytes / (1024.0 * 1024.0)
                )
                val infoText = TextView(this).apply {
                    text = getString(R.string.recording_info_format, dateStr, sizeStr)
                    textSize = 12f
                    setTextColor(colors.secondaryText)
                }
                val infoParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                }
                row.addView(infoText, infoParams)

                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val shareButton = TextView(this).apply {
                    setText(R.string.share)
                    textSize = 13f
                    setTextColor(colors.accent)
                    minHeight = dp(48)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(16), dp(6))
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.share_recording_accessibility, rec.name)
                    applyIosTouchFeedback(this)
                    setOnClickListener {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/mp4"
                            putExtra(Intent.EXTRA_STREAM, rec.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_recording)))
                    }
                }
                actionsRow.addView(shareButton, LinearLayout.LayoutParams(-2, -2))

                val deleteButton = TextView(this).apply {
                    setText(R.string.delete)
                    textSize = 13f
                    setTextColor(colors.danger)
                    minHeight = dp(48)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.delete_recording_accessibility, rec.name)
                    applyIosTouchFeedback(this)
                    setOnClickListener {
                        isEnabled = false
                        lifecycleScope.launch {
                            try {
                                val deleted = withContext(Dispatchers.IO) {
                                    storageManager.deleteSavedRecording(rec.uri)
                                }
                                if (deleted) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.deleted_recording, rec.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    dialog.dismiss()
                                    showSavedRecordingsPlayerSheet()
                                } else {
                                    isEnabled = true
                                    Toast.makeText(
                                        this@MainActivity,
                                        R.string.delete_recording_failed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (exception: Exception) {
                                isEnabled = true
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(
                                        R.string.delete_recording_error,
                                        exception.message ?: getString(R.string.system_error)
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                actionsRow.addView(deleteButton, LinearLayout.LayoutParams(-2, -2))

                val actionsParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(6)
                }
                row.addView(actionsRow, actionsParams)

                playButton.setOnClickListener {
                    try {
                        if (activePlayer != null && activePlayButton === playButton) {
                            val player = activePlayer ?: return@setOnClickListener
                            if (player.isPlaying) {
                                player.pause()
                                playButton.setText(R.string.resume)
                                playButton.contentDescription =
                                    getString(R.string.resume_recording_accessibility, rec.name)
                            } else {
                                player.start()
                                playButton.setText(R.string.pause)
                                playButton.contentDescription =
                                    getString(R.string.pause_recording_accessibility, rec.name)
                            }
                            return@setOnClickListener
                        }
                        if (activePlayer != null) {
                            activePlayer?.release()
                            activePlayer = null
                            activePlayButton?.setText(R.string.play)
                            activePlayButton?.contentDescription = activePlayerName?.let {
                                getString(R.string.play_recording_accessibility, it)
                            }
                            activePlayButton = null
                            activePlayerName = null
                        }
                        val player = android.media.MediaPlayer.create(this@MainActivity, rec.uri)
                        if (player != null) {
                            activePlayer = player
                            activePlayButton = playButton
                            activePlayerName = rec.name
                            playButton.setText(R.string.pause)
                            playButton.contentDescription =
                                getString(R.string.pause_recording_accessibility, rec.name)
                            player.start()
                            player.setOnCompletionListener {
                                playButton.setText(R.string.play)
                                playButton.contentDescription =
                                    getString(R.string.play_recording_accessibility, rec.name)
                                activePlayer = null
                                activePlayButton = null
                                activePlayerName = null
                                player.release()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, R.string.could_not_play_audio, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.play_audio_error, e.message ?: getString(R.string.system_error)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                val rowParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(10)
                }
                listLayout.addView(row, rowParams)
            }
            val scaledChromeHeight = (260 * resources.configuration.fontScale * density).toInt()
            val listViewportHeight = (resources.displayMetrics.heightPixels - scaledChromeHeight)
                .coerceIn(dp(80), dp(300))
            scrollView.addView(listLayout, LinearLayout.LayoutParams(-1, listViewportHeight))
            panel.addView(scrollView, LinearLayout.LayoutParams(-1, -2))
        }

        val closeButton = Button(this).apply {
            setText(R.string.done)
            textSize = 16f
            minHeight = dp(50)
            setTextColor(if (isLightTheme) Color.WHITE else Color.BLACK)
            background = roundedControl(colors.accent, colors.accent)
            setOnClickListener { dialog.dismiss() }
            applyIosTouchFeedback(this)
        }
        panel.addView(closeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(-1, -2)
        }
        configurePopupWindow(dialog) {
            activePlayer?.release()
            activePlayer = null
            activePlayButton?.setText(R.string.play)
            activePlayButton?.contentDescription = activePlayerName?.let {
                getString(R.string.play_recording_accessibility, it)
            }
            activePlayButton = null
            activePlayerName = null
        }
        dialog.show()
    }

    private fun updateReplayWindowLabel() {
        val latestAudioEnd = storageManager.getLatestAudioEndMillis()
        if (!isRecording && latestAudioEnd <= 0L) {
            availableDurationText.setText(R.string.start_recording_for_replay)
            refreshStorageStatus()
            return
        }

        val now = System.currentTimeMillis()
        val selectedWindowStart = now - storageManager.getSegmentMinutes() * 60_000L
        val windowEnd = if (isRecording) {
            now
        } else {
            latestAudioEnd
        }
        val selectedEndStart = windowEnd - storageManager.getSegmentMinutes() * 60_000L
        val recordingStart = storageManager.getRecordingSessionStartMillis()
        val availableStart = if (recordingStart > 0L) {
            maxOf(selectedEndStart, recordingStart)
        } else {
            maxOf(selectedWindowStart, selectedEndStart)
        }
        val availableMillis = (windowEnd - availableStart).coerceAtLeast(0L)
        availableDurationText.text = if (availableMillis < 1_000L) {
            if (isRecording) getString(R.string.replay_building)
            else getString(R.string.start_recording_for_replay)
        } else {
            getString(R.string.available_audio_duration, formatReplayDuration(availableMillis))
        }
        refreshStorageStatus()
    }

    private fun refreshStorageStatus(force: Boolean = false) {
        if (!::storageStatusText.isInitialized || !storageManager.hasFolderSelection()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastStorageRefreshAt < STORAGE_STATUS_REFRESH_MILLIS) return
        lastStorageRefreshAt = now
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    storageManager.getCurrentFolderLabel()
                    storageManager.getSavedAudioFolderUri()
                    storageManager.getLoopStorageBytes()
                }
                if (!isFinishing && !isDestroyed && storageManager.hasFolderSelection()) {
                    storageAccessAvailable = true
                    storageStatusText.text = getString(
                        R.string.buffered_storage_status,
                        formatStorageSize(bytes),
                        storageManager.getSegmentMinutes()
                    )
                    storageStatusText.contentDescription = storageStatusText.text
                    updateButtons()
                }
            } catch (exception: Exception) {
                storageAccessAvailable = false
                storageStatusText.setText(R.string.selected_folder_unavailable)
                storageStatusText.contentDescription = getString(
                    R.string.storage_access_problem,
                    exception.message ?: getString(R.string.selected_folder_unavailable_short)
                )
                updateButtons()
                if (isRecording) {
                    statusText.setText(R.string.storage_lost_stopped)
                    sendServiceAction(RecordingService.ACTION_STOP)
                }
            }
        }
    }

    private fun checkMicrophonePermission() {
        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted && isRecording) {
            statusText.setText(R.string.microphone_permission_revoked)
            sendServiceAction(RecordingService.ACTION_STOP)
        } else if (!permissionGranted && !isRecording) {
            statusText.setText(R.string.microphone_permission_needed)
        }
    }

    private fun showUpdateStatus() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        val colors = palette()

        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.checking_for_updates)
            .setMessage(R.string.connecting_to_releases)
            .setCancelable(false)
            .create()

        progressDialog.window?.setBackgroundDrawable(roundedSurface(colors.border, colors.surface))
        configurePopupWindow(progressDialog)
        progressDialog.setOnShowListener {
            progressDialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(colors.text)
            progressDialog.findViewById<TextView>(android.R.id.message)
                ?.setTextColor(colors.secondaryText)
        }
        progressDialog.show()

        lifecycleScope.launch {
            val release = try {
                GitHubReleaseClient.fetchLatestRelease()
            } catch (e: Exception) {
                null
            }

            if (isFinishing || isDestroyed) return@launch
            progressDialog.dismiss()

            if (release == null) {
                showUpdateResultDialog(
                    title = getString(R.string.update_check_failed),
                    message = getString(R.string.update_check_failed_message, versionName),
                    positiveButton = getString(R.string.close)
                )
            } else if (ReleaseVersion.isNewer(versionName, release.tagName)) {
                val releaseTitle = release.name.takeIf { it.isNotBlank() } ?: release.tagName
                val releaseNotes = release.body.trim().takeIf { it.isNotEmpty() }
                    ?.let { "\n\n$it" }
                    .orEmpty()
                showUpdateResultDialog(
                    title = getString(R.string.update_available, release.tagName),
                    message = getString(
                        R.string.update_available_message,
                        releaseTitle,
                        versionName,
                        release.tagName,
                        releaseNotes
                    ),
                    positiveButton = if (release.apkDownloadUrl != null) {
                        getString(R.string.install_update)
                    } else {
                        getString(R.string.view_release)
                    },
                    positiveAction = {
                        if (release.apkDownloadUrl != null) {
                            downloadAndInstallUpdate(release.apkDownloadUrl)
                        } else {
                            openUrlInBrowser(release.htmlUrl)
                        }
                    },
                    negativeButton = getString(R.string.later),
                    negativeAction = null
                )
            } else {
                showUpdateResultDialog(
                    title = getString(R.string.up_to_date),
                    message = getString(R.string.up_to_date_message, versionName),
                    positiveButton = getString(R.string.done)
                )
            }
        }
    }

    private fun showUpdateResultDialog(
        title: String,
        message: String,
        positiveButton: String,
        positiveAction: (() -> Unit)? = null,
        negativeButton: String? = null,
        negativeAction: (() -> Unit)? = null,
        neutralButton: String? = null,
        neutralAction: (() -> Unit)? = null
    ) {
        val colors = palette()
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(positiveButton) { _, _ -> positiveAction?.invoke() }

        if (negativeButton != null) {
            builder.setNegativeButton(negativeButton) { _, _ -> negativeAction?.invoke() }
        }
        if (neutralButton != null) {
            builder.setNeutralButton(neutralButton) { _, _ -> neutralAction?.invoke() }
        }

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(roundedSurface(colors.border, colors.surface))
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(colors.text)
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextColor(colors.secondaryText)
                setLineSpacing(4 * resources.displayMetrics.density, 1f)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(colors.accent)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colors.secondaryText)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(colors.secondaryText)
        }
        configurePopupWindow(dialog)
        dialog.show()
    }

    private fun openUrlInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.could_not_open_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadAndInstallUpdate(downloadUrl: String) {
        val colors = palette()
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.downloading_update)
            .setMessage(R.string.downloading_update_message)
            .setCancelable(false)
            .create()
        progressDialog.window?.setBackgroundDrawable(roundedSurface(colors.border, colors.surface))
        configurePopupWindow(progressDialog)
        progressDialog.setOnShowListener {
            progressDialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(colors.text)
            progressDialog.findViewById<TextView>(android.R.id.message)
                ?.setTextColor(colors.secondaryText)
        }
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val apkFile = java.io.File(cacheDir, "voice-diary-update.apk")
            try {
                downloadApk(downloadUrl, apkFile)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    installApk(apkFile)
                }
            } catch (exception: Exception) {
                apkFile.delete()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.download_failed,
                            exception.localizedMessage ?: "Network error"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun downloadApk(downloadUrl: String, destination: java.io.File) {
        val url = java.net.URL(downloadUrl)
        require(url.protocol == "https") { "Update downloads must use HTTPS." }
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "VoiceDiary-AndroidApp")

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw java.io.IOException("Update download failed (HTTP $responseCode).")
            }
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                throw java.io.IOException("The update redirected to an insecure URL.")
            }
            if (connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                throw java.io.IOException("GitHub returned a web page instead of the APK.")
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            val isZipApk = destination.inputStream().use {
                it.read() == 'P'.code && it.read() == 'K'.code
            }
            if (destination.length() < 4L || !isZipApk) {
                throw java.io.IOException("Downloaded update is not a valid APK file.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun installApk(file: java.io.File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingApkToInstall = file
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri()
                    )
                )
            } catch (exception: Exception) {
                Log.e("VoiceDiaryUpdate", "Could not open install permission settings.", exception)
                pendingApkToInstall = null
                Toast.makeText(
                    this,
                    getString(R.string.installation_failed, exception.localizedMessage ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (exception: Exception) {
            Log.e("VoiceDiaryUpdate", "Could not open Android's package installer.", exception)
            Toast.makeText(
                this,
                getString(R.string.installation_failed, exception.localizedMessage ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun configurePopupWindow(
        dialog: android.app.Dialog,
        onDismiss: (() -> Unit)? = null
    ) {
        val popupWindow = dialog.window ?: return
        val screenWidth = mainScrollView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val horizontalMargin = (40 * resources.displayMetrics.density).toInt()
        popupWindow.setLayout(
            (screenWidth - horizontalMargin).coerceAtLeast(1),
            -2
        )
        popupWindow.setWindowAnimations(0)
        popupWindow.setGravity(Gravity.CENTER)
        popupWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        popupWindow.attributes = popupWindow.attributes.apply { dimAmount = 0.32f }
        dialog.setOnDismissListener {
            onDismiss?.invoke()
        }
    }

    private fun formatStorageSize(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return if (megabytes < 1.0) {
            "${(bytes / 1024L).coerceAtLeast(1L)} KB"
        } else {
            String.format(Locale.getDefault(), "%.1f MB", megabytes)
        }
    }

    private fun applyDialogPalette(
        dialog: androidx.appcompat.app.AlertDialog,
        colors: UiPalette,
        positiveColor: Int = colors.accent,
        negativeColor: Int = colors.accent
    ) {
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            ?.setTextColor(colors.text)
        dialog.findViewById<TextView>(android.R.id.message)
            ?.apply {
                setTextColor(colors.secondaryText)
                setLineSpacing(4 * resources.displayMetrics.density, 1f)
            }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(positiveColor)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(negativeColor)
    }

    private fun confirmStopRecording() {
        val colors = palette()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.stop_audio_replay)
            .setMessage(R.string.stop_recording_confirmation)
            .setNegativeButton(R.string.keep_recording, null)
            .setPositiveButton(R.string.stop) { _, _ ->
                sendServiceAction(RecordingService.ACTION_STOP)
            }
            .create()
            .apply {
                window?.setBackgroundDrawable(roundedSurface(colors.border, colors.surface))
                configurePopupWindow(this)
                setOnShowListener {
                    applyDialogPalette(this, colors, positiveColor = colors.danger)
                }
                show()
            }
    }

    private fun confirmClearAudioChunks() {
        if (isRecording || isSaving || !storageManager.hasFolderSelection()) return
        val colors = palette()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.clear_audio_chunks_confirmation_title)
            .setMessage(R.string.clear_audio_chunks_confirmation_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_chunks) { _, _ -> clearAudioChunks() }
            .create()
            .apply {
                window?.setBackgroundDrawable(roundedSurface(colors.border, colors.surface))
                configurePopupWindow(this)
                setOnShowListener {
                    applyDialogPalette(this, colors, positiveColor = colors.danger)
                }
                show()
            }
    }

    private fun clearAudioChunks() {
        isSaving = true
        statusText.setText(R.string.clearing_audio_chunks)
        updateButtons()
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    storageManager.clearLoopSegments(exceptUri = null)
                }
                statusText.text = if (count == 0) {
                    getString(R.string.no_chunks_to_clear)
                } else {
                    resources.getQuantityString(R.plurals.cleared_audio_chunks, count, count)
                }
                Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_SHORT).show()
                updateReplayWindowLabel()
                refreshStorageStatus(force = true)
            } catch (exception: Exception) {
                statusText.text = getString(
                    R.string.clear_audio_chunks_failed,
                    exception.message ?: getString(R.string.storage_access_failed)
                )
            } finally {
                isSaving = false
                updateButtons()
            }
        }
    }

    private fun idleInstructions(): String {
        val hasMicrophonePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            !hasMicrophonePermission ->
                getString(R.string.start_and_allow_microphone)
            !storageManager.hasFolderSelection() ->
                getString(R.string.start_and_choose_storage)
            storageAccessAvailable == false ->
                getString(R.string.folder_unavailable_choose_another)
            else -> getString(R.string.start_rolling_replay)
        }
    }

    private fun formatReplayDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> {
                val remainingMinutes = (totalSeconds % 3_600L) / 60L
                if (remainingMinutes > 0L) getString(
                    R.string.duration_hours_minutes,
                    hours,
                    remainingMinutes
                ) else getString(R.string.duration_hours, hours)
            }
            minutes > 0L -> resources.getQuantityString(
                R.plurals.duration_minutes_seconds,
                seconds.toInt(),
                minutes,
                seconds
            )
            else -> resources.getQuantityString(
                R.plurals.duration_seconds,
                seconds.toInt(),
                seconds
            )
        }
    }

    companion object {
        private const val THEME_PREFS = "voice_diary_theme"
        private const val KEY_LIGHT_THEME = "light_theme"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val STORAGE_STATUS_REFRESH_MILLIS = 15_000L
    }
}
