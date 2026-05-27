package com.android.mms.activities

import android.Manifest
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
import android.telephony.SmsManager
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import com.android.common.dialogs.MRenameDialog
import com.android.common.view.MActionBar
import com.google.android.material.appbar.CollapsingToolbarLayout
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
import com.android.mms.databinding.ItemSmsServiceCenterSimBinding
import com.android.mms.databinding.ItemSmsStorageLocationSimBinding
import com.android.mms.models.SIMCard
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
        setupSettingsTopAppBar()
        setupNestBouncyScroll()
        applySettingsWindowBackgroundsAndTopChrome()
        scrollingView = binding.settingsNestedScrollview
        binding.settingsMenu.addOnOffsetChangedListener { _, _ ->
            binding.mVerticalSideFrameTop.update()
        }
        binding.settingsNestedScrollview.post {
            binding.settingsMenu.dismissCollapse()
            applyTransparentMAppBarChrome()
            refreshSideFrameBlurAndInsets()
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
        applyTransparentMAppBarChrome()
    }

    private fun setupSettingsTopAppBar() {
        binding.settingsMenu.setTitle(getString(com.goodwy.commons.R.string.settings))

        binding.settingsMenu.getBackArrow()?.apply {
            bindBlurTarget(this@SettingsActivity, binding.mainBlurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    hideKeyboard()
                    finish()
                    true
                } else {
                    false
                }
            }
        }

        binding.settingsMenu.getSearchView()?.visibility = View.GONE
        binding.settingsMenu.getActionBarView()?.let(::setupSettingsActionBarMenu)
        applyTransparentMAppBarChrome()
    }

    /** Glass top chrome: keep [MAppBarLayout] and scrims transparent so [MVSideFrame] blur shows through (txCommon). */
    private fun applyTransparentMAppBarChrome() {
        binding.settingsMenu.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            for (i in 0 until childCount) {
                when (val child = getChildAt(i)) {
                    is CollapsingToolbarLayout -> {
                        child.setBackgroundColor(Color.TRANSPARENT)
                        child.setContentScrimColor(Color.TRANSPARENT)
                        child.setStatusBarScrimColor(Color.TRANSPARENT)
                        for (j in 0 until child.childCount) {
                            child.getChildAt(j).setBackgroundColor(Color.TRANSPARENT)
                        }
                    }
                    is Toolbar -> child.setBackgroundColor(Color.TRANSPARENT)
                    else -> child.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
    }

    private fun refreshSettingsTopBarColors() {
        applyTransparentMAppBarChrome()
    }

    private fun setupNestBouncyScroll() {
        val scroll = binding.settingsNestedScrollview
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            refreshSettingsTopBarColors()
            binding.mVerticalSideFrameTop.update()
        }
        scroll.setOnOverScrollListener { _, overScrolledDistance ->
            val overscrollTranslation = overScrolledDistance * NEST_BOUNCY_OVERSCROLL_FACTOR
            binding.settingsMenu.translationY = overscrollTranslation
            binding.mVerticalSideFrameTop.translationY = overscrollTranslation
        }
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

        initSIMCardManage()
        setupSimCardMessages()
        setupSmsServiceCenter()
        setupSmsStorageLocation()

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
                settingsSimCardMessagesChevron,
                settingsSmsServiceCenterChevron,
                settingsSmsStorageLocationChevron,
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
        isRebindingSettings = false
        binding.settingsMenu.translationY = 0f
        binding.mVerticalSideFrameTop.translationY = 0f
        applyTransparentMAppBarChrome()
        refreshSideFrameBlurAndInsets()
    }
    // for edit sms service center --------->
    @SuppressLint("MissingPermission")
    private fun editSmsForSim(sim: SIMCard) {
        Thread {
            val current = readSmscAddress(sim.subscriptionId)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val dialog = MRenameDialog(this@SettingsActivity)
                dialog.bindBlurTarget(binding.mainBlurTarget)
                dialog.setTitle(getString(R.string.sms_service_center))
                dialog.setContentText(current)
                dialog.setEditEnabled(true)
                dialog.setOnRenameListener { newAddress ->
                    if (!isValidSmscAddress(newAddress)) {
                        toast(R.string.invalid_smsc_number)
                        return@setOnRenameListener
                    }
                    Thread {
                        try {
                            writeSmscAddress(sim.subscriptionId, newAddress)
                            runOnUiThread {
                                if (!isDestroyed && !isFinishing) {
                                    binding.settingsSmsServiceCenterValue.text = newAddress
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { showErrorToast(e) }
                        }
                    }.start()
                }
                dialog.show()
                dialog.window?.decorView?.findViewById<EditText>(com.android.common.R.id.input_text)
                    ?.let { et ->
                        et.inputType = InputType.TYPE_CLASS_PHONE
                        et.post { showKeyboard(et) }
                    }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun readSmscAddress(subscriptionId: Int): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId).getSmscAddress() ?: ""
        } else ""
    } catch (e: Exception) { "" }

    @SuppressLint("MissingPermission")
    private fun writeSmscAddress(subscriptionId: Int, address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId).setSmscAddress(address)
        }
    }

    private fun isValidSmscAddress(address: String): Boolean {
        if (address.isEmpty()) return true
        return if (address.startsWith("+")) address.drop(1).all { it.isDigit() }
        else address.all { it.isDigit() }
    }
    // <-------------

    // for sms storage ----------->

    private fun updateRowValue(subscriptionId: Int) {
        val location = config.getSmsStorageLocation(subscriptionId)
        binding.settingsSmsStorageLocationValue.text = getLocationLabel(location)
    }

    private fun getLocationLabel(location: Int): String = when (location) {
        SMS_SAVE_LOCATION_SIM -> getString(R.string.sms_storage_location_sim)
        else -> getString(R.string.sms_storage_location_phone)
    }

    private fun showLocationPickerDialog(sim: SIMCard) {
        val items = arrayListOf(
            RadioItem(SMS_SAVE_LOCATION_PHONE, getString(R.string.sms_storage_location_phone)),
            RadioItem(SMS_SAVE_LOCATION_SIM, getString(R.string.sms_storage_location_sim)),
        )
        val currentLocation = config.getSmsStorageLocation(sim.subscriptionId)
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RadioGroupDialog(
            this,
            items,
            currentLocation,
            R.string.sms_storage_location,
            blurTarget = blurTarget,
            requireConfirmButton =true,
        ) { selected ->
            config.setSmsStorageLocation(sim.subscriptionId, selected as Int)
            updateRowValue(sim.subscriptionId)
        }
    }

    // <------------------

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun isSIMCardInserted() {
        if (getSIMCard().isEmpty()) {
            binding.settingsSimCardMessagesHolder.alpha = 0.6F
            binding.settingsSmsServiceCenterHolder.alpha = 0.6F
            binding.settingsSmsStorageLocationHolder.alpha = 0.6F
        } else {
            binding.settingsSimCardMessagesHolder.alpha = 1.0F
            binding.settingsSmsServiceCenterHolder.alpha = 1.0F
            binding.settingsSmsStorageLocationHolder.alpha = 1.0F
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSIMCard(): List<SIMCard> {
        val availableSIMs = subscriptionManagerCompat().activeSubscriptionInfoList
            ?: return emptyList()
        return availableSIMs.mapIndexed { index, subscriptionInfo ->
            var label = subscriptionInfo.displayName?.toString()
                ?: getString(com.goodwy.commons.R.string.contact_list_sim_slot, index + 1)
            when (subscriptionInfo.mnc) {
                5 -> label = getString(R.string.koryo_label)
                6 -> label = getString(R.string.kangsong_label)
                3 -> label = getString(R.string.mirae_label)
            }
            SIMCard(
                id = index + 1,
                subscriptionId = subscriptionInfo.subscriptionId,
                label = label,
                mnc = subscriptionInfo.mnc,
                number = subscriptionInfo.number?.trim().orEmpty(),
            )
        }
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.settingsMenu.getBackArrow()?.bindBlurTarget(this@SettingsActivity, binding.mainBlurTarget)
            binding.settingsMenu.getActionBarView()?.bindBlurTarget(this@SettingsActivity, binding.mainBlurTarget)
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
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
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
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

    private fun initSIMCardManage() = binding.apply {
        if (getSIMCard().count() == 1){
            binding.settingsSmsServiceCenterValue.visibility = View.VISIBLE
            binding.settingsSmsStorageLocationValue.visibility = View.VISIBLE
            binding.settingsSmsServiceCenterChevron.visibility = View.GONE
            binding.settingsSmsStorageLocationChevron.visibility = View.GONE
            updateRowValue(getSIMCard()[0].subscriptionId)
            binding.settingsSmsServiceCenterValue.text = readSmscAddress(getSIMCard()[0].subscriptionId)
        }
        else {
            binding.settingsSmsServiceCenterValue.visibility = View.GONE
            binding.settingsSmsStorageLocationValue.visibility = View.GONE
            binding.settingsSmsServiceCenterChevron.visibility = View.VISIBLE
            binding.settingsSmsStorageLocationChevron.visibility = View.VISIBLE
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupSimCardMessages() = binding.apply {
        isSIMCardInserted()
        settingsSimCardMessagesHolder.setOnClickListener {
            if (getSIMCard().isEmpty()) return@setOnClickListener
            else if (getSIMCard().count() == 1) {
                Intent(this@SettingsActivity, SimMessagesActivity::class.java).apply {
                    putExtra(SimMessagesActivity.EXTRA_SUBSCRIPTION_ID, getSIMCard()[0].subscriptionId)
                    putExtra(SimMessagesActivity.EXTRA_SIM_LABEL, getSIMCard()[0].label)
                    startActivity(this)
                }
            } else {
                startActivity(Intent(this@SettingsActivity, ManageSimMessagesActivity::class.java))
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun setupSmsServiceCenter() = binding.apply {
        isSIMCardInserted()
        settingsSmsServiceCenterHolder.setOnClickListener {
            if (getSIMCard().isEmpty()) return@setOnClickListener
            else if (getSIMCard().count() == 1){
                editSmsForSim(getSIMCard()[0])
                binding.settingsSmsServiceCenterValue.visibility = View.VISIBLE
                binding.settingsSmsServiceCenterChevron.visibility = View.GONE
            } else
            startActivity(Intent(this@SettingsActivity, SmsServiceCenterActivity::class.java))
        }
    }
    @SuppressLint("MissingPermission")
    private fun setupSmsStorageLocation() = binding.apply {
        isSIMCardInserted()
        settingsSmsStorageLocationHolder.setOnClickListener {
            if (getSIMCard().isEmpty()) return@setOnClickListener
            else if (getSIMCard().count() == 1){
                showLocationPickerDialog(getSIMCard()[0])
                binding.settingsSmsStorageLocationValue.visibility = View.VISIBLE
                binding.settingsSmsStorageLocationChevron.visibility = View.GONE
            } else
            startActivity(Intent(this@SettingsActivity, SmsStorageLocationActivity::class.java))
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
            RadioGroupDialog(this@SettingsActivity, items, config.messageSendDelay, R.string.message_send_delay, requireConfirmButton = true, blurTarget = blurTarget) {
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
            RadioGroupDialog(this@SettingsActivity, items, config.lockScreenVisibilitySetting, R.string.lock_screen_visibility, requireConfirmButton = true, blurTarget = blurTarget) {
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

    private fun setupSettingsActionBarMenu(actionBar: MActionBar) {
        actionBar.bindBlurTarget(this, binding.mainBlurTarget)
        actionBar.setPosition("right")
        actionBar.inflateMenu(R.menu.menu_settings)
        actionBar.setOnMenuItemClickListener { menuItem ->
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

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            insets
        }
    }
}
