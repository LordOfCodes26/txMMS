package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.mms.R
import com.android.mms.adapters.SimMessageAdapter
import com.android.mms.databinding.ActivitySimMessagesBinding
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.models.SimMessage
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateTextColors

class SimMessagesActivity : SimpleActivity() {

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_SIM_LABEL = "sim_label"

        private val ICC_URI = Uri.parse("content://sms/icc")
    }

    private lateinit var binding: ActivitySimMessagesBinding
    private var subscriptionId: Int = 0
    private var simLabel: String = ""
    private val messages = mutableListOf<SimMessage>()
    private var adapter: SimMessageAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, 0)
        simLabel = intent.getStringExtra(EXTRA_SIM_LABEL) ?: getString(R.string.sim_card_messages)

        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupTopBar()
        applyWindowSurfaces()
        setupRecyclerView()
        loadMessages()

        binding.simMessagesList.post {
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.simMessagesAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.simMessagesList,
            )
            setupMySearchMenuSpringSync(binding.simMessagesAppbar, null)
            if (config.changeColourTopBar) {
                scrollingView = binding.simMessagesList
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.simMessagesList,
                    binding.simMessagesAppbar,
                    useSurfaceColor,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        applyWindowSurfaces()
        updateTextColors(binding.root)
        setupTopBar()
        refreshSideFrameBlurAndInsets()
    }

    override fun onDestroy() {
        clearMySearchMenuSpringSync(binding.simMessagesAppbar, null)
        super.onDestroy()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets -> insets }
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        scrollingView = binding.simMessagesList
        binding.simMessagesAppbar.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        binding.simMessagesAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.simMessagesAppbar.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.simMessagesAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.simMessagesList,
            )
        }
    }

    private fun setupTopBar() {
        binding.simMessagesAppbar.applyLargeTitleOnly(simLabel)
        binding.simMessagesAppbar.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@SimMessagesActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor,
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
        binding.simMessagesAppbar.searchBeVisibleIf(false)
    }

    private fun setupRecyclerView() {
        adapter = SimMessageAdapter(this, messages) { message ->
            showMessageOptions(message)
        }
        binding.simMessagesList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.simMessagesList.adapter = adapter
    }

    @SuppressLint("MissingPermission")
    private fun loadMessages() {
        binding.simMessagesProgress.beVisible()
        binding.simMessagesEmpty.beGone()
        binding.simMessagesList.beGone()

        Thread {
            val loaded = querySimMessages()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                binding.simMessagesProgress.beGone()
                messages.clear()
                messages.addAll(loaded)
                adapter?.notifyDataSetChanged()
                if (messages.isEmpty()) {
                    binding.simMessagesEmpty.beVisible()
                    binding.simMessagesList.beGone()
                } else {
                    binding.simMessagesEmpty.beGone()
                    binding.simMessagesList.beVisible()
                    binding.simMessagesList.scrollToPosition(messages.size - 1)
                }
            }
        }.start()
    }

    private fun buildSimUri(): Uri = ICC_URI.buildUpon()
        .appendQueryParameter("subscription", subscriptionId.toString())
        .build()

    @SuppressLint("MissingPermission")
    private fun querySimMessages(): List<SimMessage> {
        val result = mutableListOf<SimMessage>()
        return try {
            val uri = buildSimUri()
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex("_id").takeIf { it >= 0 } ?: return@use
                val addressCol = cursor.getColumnIndex("address").takeIf { it >= 0 } ?: return@use
                val bodyCol = cursor.getColumnIndex("body").takeIf { it >= 0 } ?: return@use
                val dateCol = cursor.getColumnIndex("date").takeIf { it >= 0 } ?: return@use
                val statusCol = cursor.getColumnIndex("status").takeIf { it >= 0 } ?: return@use
                val indexCol = cursor.getColumnIndex("index_on_icc").takeIf { it >= 0 }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val address = cursor.getString(addressCol)?.trim().orEmpty()
                    val body = cursor.getString(bodyCol).orEmpty()
                    val date = cursor.getLong(dateCol)
                    val status = cursor.getInt(statusCol)
                    val indexOnIcc = if (indexCol != null) cursor.getString(indexCol).orEmpty() else id.toString()
                    result.add(SimMessage(id, address, body, date, status, indexOnIcc))
                }
            }
            result.sortedBy { it.date }
        } catch (e: Exception) {
            runOnUiThread { showErrorToast(e) }
            result
        }
    }

    private fun showMessageOptions(message: SimMessage) {
        val options = arrayOf(
            getString(R.string.sim_copy_to_phone),
            getString(R.string.sim_delete_message),
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToPhone(message)
                    1 -> confirmDelete(message)
                }
            }
            .show()
    }

    private fun copyToPhone(message: SimMessage) {
        Thread {
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, message.address)
                    put(Telephony.Sms.BODY, message.body)
                    put(Telephony.Sms.DATE, message.date)
                    put(Telephony.Sms.READ, 1)
                    put(
                        Telephony.Sms.TYPE,
                        if (message.isIncoming) Telephony.Sms.MESSAGE_TYPE_INBOX
                        else Telephony.Sms.MESSAGE_TYPE_SENT
                    )
                }
                contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                runOnUiThread { toast(R.string.sim_message_copied) }
            } catch (e: Exception) {
                runOnUiThread { showErrorToast(e) }
            }
        }.start()
    }

    private fun confirmDelete(message: SimMessage) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sim_delete_message)
            .setMessage(R.string.sim_confirm_delete)
            .setPositiveButton(com.goodwy.commons.R.string.yes) { _, _ ->
                deleteFromSim(message)
            }
            .setNegativeButton(com.goodwy.commons.R.string.no, null)
            .show()
    }

    private fun deleteFromSim(message: SimMessage) {
        Thread {
            try {
                val uri = buildSimUri()
                val indexParts = message.indexOnIcc.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                val selectionArgs = indexParts.ifEmpty { listOf(message.indexOnIcc) }.toTypedArray()
                contentResolver.delete(uri, "ForMultiDelete", selectionArgs)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        messages.remove(message)
                        adapter?.notifyDataSetChanged()
                        if (messages.isEmpty()) {
                            binding.simMessagesEmpty.beVisible()
                            binding.simMessagesList.beGone()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorToast(e) }
            }
        }.start()
    }
}
