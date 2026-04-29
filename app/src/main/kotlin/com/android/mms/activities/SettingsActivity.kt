package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.appcompat.content.res.AppCompatResources
import com.goodwy.commons.dialogs.*
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.android.mms.R
import com.android.mms.databinding.ActivitySettingsBinding
import com.android.mms.dialogs.ExportMessagesDialog
import com.android.mms.extensions.*
import com.android.mms.helpers.*
import com.android.common.view.MVSideFrame
import eightbitlab.com.blurview.BlurTarget

class SettingsActivity : SimpleActivity() {
    /** Pending ringtone to apply to [RingtoneManager.setActualDefaultRingtoneUri] after user grants [Settings.ACTION_MANAGE_WRITE_SETTINGS]. */
    private var pendingSystemNotificationSoundUri: Uri? = null
    private var blockedNumbersAtPause = -1
    private var currentlyPlayingRingtone: android.media.Ringtone? = null
    private var isRebindingSettings = false
    private val messagesFileType = "application/json"
    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        if (!isQPlus()) {
            add("application/octet-stream")
        }
    }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                MessagesImporter(this).importMessages(uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(com.goodwy.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    private val notificationSoundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val extras = result.data?.extras
                if (extras?.containsKey(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true) {
                    val uri = extras.getParcelable<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    if (uri != null) {
                        config.notificationSound = uri.toString()
                        updateNotificationSoundDisplay()
                        // Update system notification sound
                        updateSystemNotificationSound(uri)
                        // Play the selected sound
                        try {
                            stopCurrentlyPlayingRingtone()
                            val ringtone = RingtoneManager.getRingtone(this, uri)
                            ringtone?.play()
                            currentlyPlayingRingtone = ringtone
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    } else {
                        // Silent was selected
                        config.notificationSound = SILENT
                        updateNotificationSoundDisplay()
                        // Update system notification sound to null (silent)
                        updateSystemNotificationSound(null)
                    }
                }
            }
        }

    private val deliveryReportSoundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val extras = result.data?.extras
                if (extras?.containsKey(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true) {
                    val uri = extras.getParcelable<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    if (uri != null) {
                        config.deliveryReportSound = uri.toString()
                        updateDeliveryReportSoundDisplay()
                        // Play the selected sound
                        try {
                            stopCurrentlyPlayingRingtone()
                            val ringtone = RingtoneManager.getRingtone(this, uri)
                            ringtone?.play()
                            currentlyPlayingRingtone = ringtone
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    } else {
                        // Silent was selected
                        config.deliveryReportSound = SILENT
                        updateDeliveryReportSoundDisplay()
                    }
                }
            }
        }


    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.Material3.Dark windowBackground is dark; paint window + decor before inflation so edge-to-edge does not flash behind transparent bars.
        paintSettingsWindowBeforeContentView()
        setContentView(binding.root)
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupOptionsMenu()
        setupSettingsTopAppBar()
        applySettingsWindowBackgroundsAndTopChrome()

        binding.settingsNestedScrollview.post {
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.settingsMenu,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.settingsHolder,
            )
            scrollingView = binding.settingsNestedScrollview
            if (config.changeColourTopBar) {
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.settingsNestedScrollview,
                    binding.settingsMenu,
                    useSurfaceColor,
                )
            }
            refreshSettingsTopBarColors()
        }
    }

    /** Same surface / background logic as [MainActivity.mainContentBackgroundColor]. */
    private fun mainContentBackgroundColor(): Int {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        return if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
    }

    /**
     * Runs after [super.onCreate] and **before** [setContentView]: replaces the dark Material3 Dark
     * `windowBackground` so transparent system bars do not reveal it before settings content loads.
     */
    private fun paintSettingsWindowBeforeContentView() {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
    }

    private fun applySettingsWindowBackgroundsAndTopChrome() {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.settingsNestedScrollview.setBackgroundColor(Color.TRANSPARENT)
        binding.settingsHolder.setBackgroundColor(backgroundColor)
        scrollingView = binding.settingsNestedScrollview
        refreshSettingsTopBarColors()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
//            val navHeight = nav.bottom
//            val dp5 = (5 * resources.displayMetrics.density).toInt()
//            binding.mVerticalSideFrameBottom.layoutParams =
//                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            insets
        }
    }

    /** Same sequence as [MessageBubblePickerActivity.onResume] for the MySearchMenu chrome. */
    private fun setSettingsTransparentAppBarBackground() {
        binding.settingsMenu.setBackgroundColor(Color.TRANSPARENT)
        binding.settingsMenu.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun refreshSettingsTopBarColors() {
        val useSurfaceColor = isDynamicTheme() && !isNightDisplay()
        val backColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.settingsMenu.updateColors(
            backColor,
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        setSettingsTransparentAppBarBackground()
    }

    private fun setupSettingsTopAppBar() {
        binding.settingsMenu.applyLargeTitleOnly(getString(com.goodwy.commons.R.string.settings))
        binding.settingsMenu.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@SettingsActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor,
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
        binding.settingsMenu.searchBeVisibleIf(false)
    }

    override fun onResume() {
        super.onResume()

        flushPendingSystemNotificationSoundIfPossible()
        if (isSystemInDarkMode()) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
        }

        applySettingsWindowBackgroundsAndTopChrome()

        isRebindingSettings = true
        stopCurrentlyPlayingRingtone()
        setupSettingsTopAppBar()
        scrollingView = binding.settingsNestedScrollview

        setupEnableDeliveryReports()
        setupDeliveryReportSound()
        setupNotificationSound()
        setupLockScreenVisibility()
        setupNotifyTurnsOnScreen()

        setupShowPhoneNumber()
        setupShowSmsRemainedCount()
        setupShowCharacterCounter()
        setupMessageSendDelay()

        setupMessageBubble()
        setupManageQuickTexts()
        setupSoundOnOutGoingMessages()
        // Hide "Sound on out going messages" option
        binding.settingsSoundOnOutGoingMessagesHolder.beGone()

        setupMessagesExport()
        setupMessagesImport()

        updateTextColors(binding.rootView)

        binding.settingsGeneralLabel.beGone()
        binding.settingsGeneralHolder.beGone()

        binding.settingsCustomizeNotificationsHolder.beGone()
        binding.settingsLockScreenVisibilityHolder.background =
            AppCompatResources.getDrawable(this, R.drawable.ripple_all_corners)
        binding.settingsCopyNumberAndDeleteHolder.beGone()
        binding.settingsSendOnEnterHolder.beGone()

        // Hide MMS-related items
        binding.settingsSendLongMessageMmsHolder.beGone()
        binding.settingsSendGroupMessageMmsHolder.beGone()
        binding.settingsMmsFileSizeLimitHolder.beGone()

        if (blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()) {
            refreshConversations()
        }

        binding.apply {
            val cardBgColor = resources.getColor(com.android.common.R.color.tx_cardview_bg)
            arrayOf(
                settingsNotificationsHolder,
                settingsMessagesHolder,
                settingsOutgoingMessagesHolder,
                settingsBackupsHolder
            ).forEach {
                it.setCardBackgroundColor(cardBgColor)
            }

            val properTextColor = getProperTextColor()
            arrayOf(
                settingsManageQuickTextsChevron,
                settingsImportMessagesChevron,
                settingsExportMessagesChevron
            ).forEach {
                it.applyColorFilter(properTextColor)
            }
//            deleted by sun
//            because duplicate style and text color
//            ------------->
//            arrayOf(
//                settingsDeliveryReportSummary,
//                settingsShowPhoneNumberSummary,
//                settingsShowSmsRemainedCountSummary
//            ).forEach {
//                it.setColors(com.android.common.R.color.tx_cardview_summary,
//                    com.android.common.R.color.tx_cardview_summary,
//                    com.android.common.R.color.tx_cardview_summary)
//            }
//            <--------------
        }
        binding.settingsMenu.requireCustomToolbar().menu.let { updateMenuItemColors(it) }
        isRebindingSettings = false

        refreshSettingsTopBarColors()
        refreshSideFrameBlurAndInsets()
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.settingsMenu,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.settingsHolder,
            )
        }
    }

    private fun setNeedRestartIfUserAction() {
        if (!isRebindingSettings) {
            config.needRestart = true
        }
    }

    private fun setupMessagesExport() {
        binding.settingsExportMessagesHolder.setOnClickListener {
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            exportMessagesDialog = ExportMessagesDialog(this, blurTarget) { fileName ->
                saveDocument.launch("$fileName.json")
            }
        }
    }

    private fun setupMessagesImport() {
        binding.settingsImportMessagesHolder.setOnClickListener {
            getContent.launch(messageImportFileTypes.toTypedArray())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            when (requestCode) {
                PICK_NOTIFICATION_SOUND_INTENT_ID -> {
                    val alarmSound = storeNewYourAlarmSound(resultData)
                    config.notificationSound = alarmSound.uri
                    updateNotificationSoundDisplay()
                    // Update system notification sound
                    val uri = if (alarmSound.uri.isNotEmpty() && alarmSound.uri != SILENT) {
                        android.net.Uri.parse(alarmSound.uri)
                    } else {
                        null
                    }
                    updateSystemNotificationSound(uri)
                }
                PICK_DELIVERY_REPORT_SOUND_INTENT_ID -> {
                    val alarmSound = storeNewYourAlarmSound(resultData)
                    config.deliveryReportSound = alarmSound.uri
                    updateDeliveryReportSoundDisplay()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopCurrentlyPlayingRingtone()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    private fun stopCurrentlyPlayingRingtone() {
        try {
            currentlyPlayingRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            currentlyPlayingRingtone = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupNotificationSound() = binding.apply {
        updateNotificationSoundDisplay()
        settingsNotificationSoundHolder.setOnClickListener {
            hideKeyboard()
            val ringtonePickerIntent = getNotificationSoundPickerIntent()
            try {
                notificationSoundPicker.launch(ringtonePickerIntent)
            } catch (e: Exception) {
                val currentRingtone = config.notificationSound ?: getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION).uri
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                SelectAlarmSoundDialog(
                    this@SettingsActivity,
                    currentRingtone,
                    AudioManager.STREAM_NOTIFICATION,
                    PICK_NOTIFICATION_SOUND_INTENT_ID,
                    RingtoneManager.TYPE_NOTIFICATION,
                    false,
                    onAlarmPicked = { alarmSound ->
                        config.notificationSound = alarmSound?.uri
                        updateNotificationSoundDisplay()
                        // Update system notification sound
                        val uri = if (alarmSound?.uri?.isNotEmpty() == true && alarmSound.uri != SILENT) {
                            android.net.Uri.parse(alarmSound.uri)
                        } else {
                            null
                        }
                        updateSystemNotificationSound(uri)
                    },
                    onAlarmSoundDeleted = { alarmSound ->
                        if (config.notificationSound == alarmSound.uri) {
                            val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                            config.notificationSound = default.uri
                            updateNotificationSoundDisplay()
                            // Update system notification sound to default
                            val uri = if (default.uri.isNotEmpty() && default.uri != SILENT) {
                                android.net.Uri.parse(default.uri)
                            } else {
                                null
                            }
                            updateSystemNotificationSound(uri)
                        }
                    }
                )
            }
        }
    }

    private fun getNotificationSoundPickerIntent(): Intent {
        val defaultRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val currentRingtoneUri = config.notificationSound?.let { android.net.Uri.parse(it) }
            ?: defaultRingtoneUri

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.notification_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultRingtoneUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri)
        }
    }

    companion object {
        private const val PICK_NOTIFICATION_SOUND_INTENT_ID = 1001
        private const val PICK_DELIVERY_REPORT_SOUND_INTENT_ID = 1002
    }

    /**
     * Updates the global “Default notification sound” in system settings.
     * On API 23+, [android.Manifest.permission.WRITE_SETTINGS] requires the user to allow
     * “Modify system settings” in app settings; [Settings.System.canWrite] reflects that.
     */
    private fun updateSystemNotificationSound(uri: android.net.Uri?) {
        try {
            val canModifySystemSettings =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)
            if (canModifySystemSettings) {
                RingtoneManager.setActualDefaultRingtoneUri(
                    this,
                    RingtoneManager.TYPE_NOTIFICATION,
                    uri
                )
                pendingSystemNotificationSoundUri = null
                return
            }
            pendingSystemNotificationSoundUri = uri
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) {
                toast(R.string.allow_modify_system_settings_default_notification_sound)
                startActivity(intent)
            } else {
                pendingSystemNotificationSoundUri = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun flushPendingSystemNotificationSoundIfPossible() {
        val pending = pendingSystemNotificationSoundUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            return
        }
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                this,
                RingtoneManager.TYPE_NOTIFICATION,
                pending
            )
            pendingSystemNotificationSoundUri = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotificationSoundDisplay() {
        val soundUriString = config.notificationSound
        val soundName = when {
            soundUriString == null -> {
                val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                default.title
            }
            soundUriString == SILENT -> getString(com.goodwy.commons.R.string.no_sound)
            soundUriString.isEmpty() -> getString(com.goodwy.commons.R.string.no_sound)
            else -> {
                try {
                    val uri = android.net.Uri.parse(soundUriString)
                    RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getString(com.goodwy.commons.R.string.none)
                } catch (e: Exception) {
                    getString(com.goodwy.commons.R.string.none)
                }
            }
        }
        binding.settingsNotificationSoundValue.text = soundName
    }

    private fun setupMessageBubble() = binding.apply {
        settingsMessageBubbleIcon.text = getString(R.string.message_bubble_type, config.bubbleDrawableSet)
        settingsMessageBubbleHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, MessageBubblePickerActivity::class.java))
        }
    }

    private fun setupManageQuickTexts() = binding.apply {
        @SuppressLint("SetTextI18n")
        settingsManageQuickTextsCount.text = config.quickTexts.size.toString()
        settingsManageQuickTextsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageQuickTextsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupShowPhoneNumber() = binding.apply {
        settingsShowPhoneNumber.isChecked = config.showPhoneNumber
        settingsShowPhoneNumber.setOnCheckedChangeListener { isChecked ->
            config.showPhoneNumber = isChecked
            setNeedRestartIfUserAction()
        }
        settingsShowPhoneNumberHolder.setOnClickListener {
            settingsShowPhoneNumber.toggle()
        }
    }

    private fun setupShowSmsRemainedCount() = binding.apply {
        settingsShowSmsRemainedCount.isChecked = config.showSmsRemainedCount
        settingsShowSmsRemainedCount.setOnCheckedChangeListener { isChecked ->
            config.showSmsRemainedCount = isChecked
        }
        settingsShowSmsRemainedCountHolder.setOnClickListener {
            settingsShowSmsRemainedCount.toggle()
        }
    }

    private fun setupShowCharacterCounter() = binding.apply {
        settingsShowCharacterCounter.isChecked = config.showCharacterCounter
        settingsShowCharacterCounter.setOnCheckedChangeListener { isChecked ->
            config.showCharacterCounter = isChecked
        }
        settingsShowCharacterCounterHolder.setOnClickListener {
            settingsShowCharacterCounter.toggle()
        }
    }

    private fun setupMessageSendDelay() = binding.apply {
        settingsMessageSendDelay.text = getMessageSendDelayText()
        settingsMessageSendDelayHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(0, getString(R.string.no_delay)),
                RadioItem(3, getString(R.string.delay_3s)),
                RadioItem(5, getString(R.string.delay_5s)),
                RadioItem(10, getString(R.string.delay_10s))
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.messageSendDelay, R.string.message_send_delay, blurTarget = blurTarget) {
                config.messageSendDelay = it as Int
                settingsMessageSendDelay.text = getMessageSendDelayText()
            }
        }
    }

    private fun getMessageSendDelayText() = getString(
        when (config.messageSendDelay) {
            0 -> R.string.no_delay
            3 -> R.string.delay_3s
            5 -> R.string.delay_5s
            10 -> R.string.delay_10s
            else -> R.string.no_delay
        }
    )

    private fun setupSoundOnOutGoingMessages() = binding.apply {
        settingsSoundOnOutGoingMessages.isChecked = config.soundOnOutGoingMessages
        settingsSoundOnOutGoingMessages.setOnCheckedChangeListener { isChecked ->
            config.soundOnOutGoingMessages = isChecked
        }
        settingsSoundOnOutGoingMessagesHolder.setOnClickListener {
            settingsSoundOnOutGoingMessages.toggle()
        }
    }

    private fun setupEnableDeliveryReports() = binding.apply {
        settingsEnableDeliveryReports.isChecked = config.enableDeliveryReports
        settingsEnableDeliveryReports.setOnCheckedChangeListener { isChecked ->
            config.enableDeliveryReports = isChecked
            updateDeliveryReportSoundVisibility()
        }
        settingsEnableDeliveryReportsHolder.setOnClickListener {
            settingsEnableDeliveryReports.toggle()
        }
        updateDeliveryReportSoundVisibility()
    }

    private fun updateDeliveryReportSoundVisibility() {
        binding.settingsDeliveryReportSoundHolder.beVisibleIf(config.enableDeliveryReports)
    }

    private fun setupDeliveryReportSound() = binding.apply {
        updateDeliveryReportSoundVisibility()
        updateDeliveryReportSoundDisplay()
        settingsDeliveryReportSoundHolder.setOnClickListener {
            hideKeyboard()
            val ringtonePickerIntent = getDeliveryReportSoundPickerIntent()
            try {
                deliveryReportSoundPicker.launch(ringtonePickerIntent)
            } catch (e: Exception) {
                val currentRingtone = config.deliveryReportSound ?: getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION).uri
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                SelectAlarmSoundDialog(
                    this@SettingsActivity,
                    currentRingtone,
                    AudioManager.STREAM_NOTIFICATION,
                    PICK_DELIVERY_REPORT_SOUND_INTENT_ID,
                    RingtoneManager.TYPE_NOTIFICATION,
                    false,
                    onAlarmPicked = { alarmSound ->
                        config.deliveryReportSound = alarmSound?.uri
                        updateDeliveryReportSoundDisplay()
                    },
                    onAlarmSoundDeleted = { alarmSound ->
                        if (config.deliveryReportSound == alarmSound.uri) {
                            val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                            config.deliveryReportSound = default.uri
                            updateDeliveryReportSoundDisplay()
                        }
                    }
                )
            }
        }
    }

    private fun getDeliveryReportSoundPickerIntent(): Intent {
        val defaultRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val currentRingtoneUri = config.deliveryReportSound?.let { android.net.Uri.parse(it) }
            ?: defaultRingtoneUri

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.delivery_report_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultRingtoneUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri)
        }
    }

    private fun updateDeliveryReportSoundDisplay() {
        val soundUriString = config.deliveryReportSound
        val soundName = when {
            soundUriString == null -> {
                val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                default.title
            }
            soundUriString == SILENT -> getString(com.goodwy.commons.R.string.no_sound)
            soundUriString.isEmpty() -> getString(com.goodwy.commons.R.string.no_sound)
            else -> {
                try {
                    val uri = android.net.Uri.parse(soundUriString)
                    RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getString(com.goodwy.commons.R.string.none)
                } catch (e: Exception) {
                    getString(com.goodwy.commons.R.string.none)
                }
            }
        }
        binding.settingsDeliveryReportSoundValue.text = soundName
    }

    private fun setupLockScreenVisibility() = binding.apply {
        settingsLockScreenVisibility.text = getLockScreenVisibilityText()
        settingsLockScreenVisibilityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
                RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
                RadioItem(LOCK_SCREEN_NOTHING, getString(com.goodwy.commons.R.string.nothing)),
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.lockScreenVisibilitySetting, R.string.lock_screen_visibility, blurTarget = blurTarget) {
                config.lockScreenVisibilitySetting = it as Int
                settingsLockScreenVisibility.text = getLockScreenVisibilityText()
            }
        }
    }

    private fun setupNotifyTurnsOnScreen() = binding.apply {
        settingsNotifyTurnsOnScreen.isChecked = config.notifyTurnsOnScreen
        settingsNotifyTurnsOnScreen.setOnCheckedChangeListener { isChecked ->
            config.notifyTurnsOnScreen = isChecked
        }
        settingsNotifyTurnsOnScreenHolder.setOnClickListener {
            settingsNotifyTurnsOnScreen.toggle()
        }
    }

    private fun getLockScreenVisibilityText() = getString(
        when (config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> com.goodwy.commons.R.string.nothing
        }
    )

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupOptionsMenu() {
        binding.settingsMenu.requireCustomToolbar().apply {
            inflateMenu(R.menu.menu_settings)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.whats_new -> {
                        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                            ?: throw IllegalStateException("mainBlurTarget not found")
                        WhatsNewDialog(this@SettingsActivity, whatsNewList(), blurTarget = blurTarget)
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
