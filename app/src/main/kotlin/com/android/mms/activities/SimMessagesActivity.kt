package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.common.dialogs.MConfirmDialog
import com.android.mms.R
import com.android.mms.adapters.SimMessageAdapter
import com.android.mms.databinding.ActivitySimMessagesBinding
import com.android.mms.models.SimMessage
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.showMPopupMenu
import eightbitlab.com.blurview.BlurTarget

class SimMessagesActivity : SimpleActivity() {

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_SIM_LABEL = "sim_label"

        private val ICC_URI = Uri.parse("content://sms/icc")
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
    }

    private lateinit var binding: ActivitySimMessagesBinding
    private var subscriptionId: Int = 0
    private var simLabel: String = ""
    private val messages = mutableListOf<SimMessage>()
    private var adapter: SimMessageAdapter? = null
    private var simMessagesAppBarVerticalOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, 0)
        simLabel = intent.getStringExtra(EXTRA_SIM_LABEL) ?: getString(R.string.sim_card_messages)

        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupSimMessagesTopAppBar()
        setupNestBouncyScroll()
        applyWindowSurfaces()
        setupRecyclerView()
        loadMessages()
        scrollingView = binding.simMessagesList
        binding.simMessagesAppbar.addOnOffsetChangedListener { _, verticalOffset ->
            simMessagesAppBarVerticalOffset = verticalOffset
            binding.mVerticalSideFrameTop.update()
            syncListTopPadding()
        }
        binding.simMessagesList.post {
            binding.simMessagesAppbar.dismissCollapse()
            simMessagesAppBarVerticalOffset = 0
            applyTransparentMAppBarChrome()
            syncListTopPadding()
            refreshSideFrameBlurAndInsets()
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
        setupSimMessagesTopAppBar()
        binding.simMessagesAppbar.translationY = 0f
        applyTransparentMAppBarChrome()
        syncListTopPadding()
        refreshSideFrameBlurAndInsets()
    }

    override fun onDestroy() {
        binding.simMessagesList.onOverscrollTranslationChanged = null
        super.onDestroy()
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

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.simMessagesList.setBackgroundColor(backgroundColor)
        scrollingView = binding.simMessagesList
        applyTransparentMAppBarChrome()
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.simMessagesAppbar.getBackArrow()?.bindBlurTarget(
                this@SimMessagesActivity,
                binding.mainBlurTarget,
            )
            binding.simMessagesAppbar.getActionBarView()?.bindBlurTarget(
                this@SimMessagesActivity,
                binding.mainBlurTarget,
            )
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
    }

    /** Glass top chrome: keep [MAppBarLayout] transparent so [MVSideFrame] blur shows through (txCommon). */
    private fun applyTransparentMAppBarChrome() {
        binding.simMessagesAppbar.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun setupSimMessagesTopAppBar() {
        binding.simMessagesAppbar.setTitle(simLabel)

        binding.simMessagesAppbar.getBackArrow()?.apply {
            bindBlurTarget(this@SimMessagesActivity, binding.mainBlurTarget)
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

        binding.simMessagesAppbar.getSearchView()?.visibility = View.GONE
        binding.simMessagesAppbar.getActionBarView()?.visibility = View.GONE
        applyTransparentMAppBarChrome()
    }

    /** Pinned toolbar row height when [MAppBarLayout] is fully collapsed (txCommon). */
    private fun getCollapsedAppBarHeightPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_margin_top) +
            resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_height)

    private fun getExpandedAppBarHeightPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_nest_bouncy_content_padding_top)

    private fun listTopPaddingForAppBarOffset(verticalOffset: Int): Int {
        val expanded = getExpandedAppBarHeightPx()
        val collapsed = getCollapsedAppBarHeightPx()
        val totalRange = binding.simMessagesAppbar.totalScrollRange
        if (totalRange <= 0) return expanded
        val collapseFraction = (
            kotlin.math.abs(verticalOffset).toFloat() / totalRange.toFloat()
            ).coerceIn(0f, 1f)
        return kotlin.math.round(collapsed + (expanded - collapsed) * (1f - collapseFraction)).toInt()
    }

    /** Keep list content aligned with visible top chrome (expanded / collapsed). */
    private fun syncListTopPadding() {
        val topPad = listTopPaddingForAppBarOffset(simMessagesAppBarVerticalOffset)
        binding.simMessagesList.updatePadding(top = topPad)
        binding.simMessagesEmptyHolder.updatePadding(top = topPad)
    }

    private fun setupNestBouncyScroll() {
        val list = binding.simMessagesList
        list.setOnScrollChangeListener { _, _, _, _, _ ->
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
        list.onOverscrollTranslationChanged = { overScrolledDistance ->
            val overscrollTranslation = overScrolledDistance * NEST_BOUNCY_OVERSCROLL_FACTOR
            binding.simMessagesAppbar.translationY = overscrollTranslation
        }
    }

    private fun setupRecyclerView() {
        adapter = SimMessageAdapter(this, messages) { message, view ->
            showMessageOptions(message, view)
        }
        binding.simMessagesList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.simMessagesList.updateLayoutParams<ViewGroup.LayoutParams> {
            height = resources.displayMetrics.heightPixels - 100
        }
        binding.simMessagesList.adapter = adapter
    }

    @SuppressLint("MissingPermission")
    private fun loadMessages() {
        binding.simMessagesProgress.beVisible()
        binding.simMessagesEmpty.beGone()
        binding.noSimPlaceholderImg.beGone()
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
                    binding.noSimPlaceholderImg.beVisible()
                    binding.simMessagesList.beGone()
                } else {
                    binding.simMessagesEmpty.beGone()
                    binding.noSimPlaceholderImg.beGone()
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

    @SuppressLint("RestrictedApi")
    private fun showMessageOptions(message: SimMessage, view: View) {
        val menu = MenuBuilder(this)
        menu.add(1, 0, 0, R.string.sim_copy_to_phone)
        menu.add(1, 1, 1, R.string.sim_delete_message)

        val blurTarget = this.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
        showMPopupMenu(
            context = this,
            anchor = view,
            menu = menu,
            gravity = Gravity.START,
            blurTarget = blurTarget,
            listener = MenuItem.OnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> copyToPhone(message)
                    1 -> confirmDelete(message)
                    else -> {
                    }
                }
                true
            },
        )
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
        showMConfirmDialog(resources.getString(R.string.sim_confirm_delete)) {
            ensureBackgroundThread {
                deleteFromSim(message)
            }
        }
    }

    private fun showMConfirmDialog(question: String, onConfirm: () -> Unit) {
        val blurTarget = this.findViewById<BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        val dialog = MConfirmDialog(this)
        dialog.bindBlurTarget(blurTarget)
        dialog.setContent(question)
        dialog.setConfirmTitle(resources.getString(com.goodwy.commons.R.string.ok))
        dialog.setCancelTitle(resources.getString(com.goodwy.commons.R.string.cancel))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCompleteListener { isConfirm ->
            if (isConfirm) {
                onConfirm()
            }
        }
        dialog.show()
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
                            binding.noSimPlaceholderImg.beVisible()
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
