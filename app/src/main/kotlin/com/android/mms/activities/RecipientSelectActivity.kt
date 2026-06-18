package com.android.mms.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.android.common.helper.IconItem
import com.android.common.view.MAppBarLayout
import com.android.common.view.MRippleToolBar
import com.android.common.view.MVSideFrame
import com.android.mms.R
import com.android.mms.extensions.setRippleTabEnabledWidthAlpha
import com.android.mms.helpers.RecipientSelectionHost
import com.android.mms.models.Contact
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.views.MyTextView
import eightbitlab.com.blurview.BlurTarget

class RecipientSelectActivity : SimpleActivity(), RecipientSelectionHost {

    private var rootView: View? = null
    private var appBar: MAppBarLayout? = null
    private var viewPager: ViewPager? = null
    private var filterBar: View? = null
    private var filterRecent: MyTextView? = null
    private var filterContacts: MyTextView? = null
    private var filterRecentLine: ImageView? = null
    private var filterContactsLine: ImageView? = null
    private var confirmTab: MRippleToolBar? = null
    private var bottomBarContainer: View? = null
    private var appBarVerticalOffset = 0
    private var listTopInsetPx = -1
    private var filterBarInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val selectedByNormalized = LinkedHashMap<String, Contact>()
    private val pageFragments = arrayOfNulls<RecipientSelectListFragment>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recipient_select)
        rootView = findViewById(R.id.root_view)
        appBar = findViewById(R.id.recipient_select_appbar)
        viewPager = findViewById(R.id.recipient_select_pager)
        filterBar = findViewById(R.id.recipient_select_filter_bar)
        filterRecent = findViewById(R.id.filter_recent)
        filterContacts = findViewById(R.id.filter_contacts)
        filterRecentLine = findViewById(R.id.filter_recent_line)
        filterContactsLine = findViewById(R.id.filter_contacts_line)
        bottomBarContainer = findViewById(R.id.lyt_action)
        confirmTab = findViewById(R.id.confirm_tab)

        seedAlreadySelected()
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsTransparent()
        applyWindowSurfaces()
        setupAppBar()
        setupViewPager()
        setupFilterTabs()
        setupBottomActionTabs()
        initMVSideFrames()
        syncListInsets()
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
        setupBottomActionTabs()
        refreshSideFrameBlurAndInsets()
        if (viewPager?.currentItem == PAGE_RECENT) {
            pageFragments[PAGE_RECENT]?.let { /* refresh call log times */ }
        }
    }

    override fun onDestroy() {
        clearFilterBarInsetListener()
        super.onDestroy()
    }

    override fun onRecipientToggled(pageIndex: Int, contactIndex: Int, contact: Contact, selected: Boolean) {
        val normalized = normalizePhone(contact.phoneNumber)
        if (normalized.isEmpty()) return
        if (selected) {
            selectedByNormalized[normalized] = contact
        } else {
            selectedByNormalized.remove(normalized)
        }
        onSelectionChanged()
    }

    override fun selectedNormalizedNumbers(): Set<String> = selectedByNormalized.keys.toSet()

    override fun onSelectionChanged() {
        updateConfirmTabEnable()
        pageFragments.forEach { it?.refreshSelectionFromHost() }
    }

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCallLogPermissionIfNeeded(): Boolean {
        if (hasCallLogPermission()) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), PERMISSION_REQUEST_READ_CALL_LOG)
        return false
    }

    private fun requestContactsPermissionIfNeeded(): Boolean {
        if (hasContactsPermission()) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_READ_CONTACTS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_READ_CALL_LOG -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pageFragments[PAGE_RECENT]?.reloadData()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                }
            }
            PERMISSION_REQUEST_READ_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pageFragments[PAGE_CONTACTS]?.reloadData()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun seedAlreadySelected() {
        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(ContactPickerActivity.EXTRA_ALREADY_SELECTED_CONTACTS)
            ?: arrayListOf()
        alreadySelected.forEach { contact ->
            val normalized = normalizePhone(contact.phoneNumber)
            if (normalized.isNotEmpty()) {
                selectedByNormalized[normalized] = contact
            }
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        rootView?.setBackgroundColor(backgroundColor)
        findViewById<BlurTarget>(R.id.mainBlurTarget)?.setBackgroundColor(backgroundColor)
        appBar?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun setupAppBar() {
        appBar?.setTitle(getString(R.string.select_contacts))
        appBar?.getBackArrow()?.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                setResult(RESULT_CANCELED)
                finish()
                true
            } else {
                false
            }
        }
        appBar?.addOnOffsetChangedListener { _, verticalOffset ->
            appBarVerticalOffset = verticalOffset
            syncFilterBarTranslation()
        }
        findViewById<BlurTarget>(R.id.mainBlurTarget)?.post {
            appBar?.dismissCollapse()
            appBarVerticalOffset = 0
            syncListInsets()
            refreshSideFrameBlurAndInsets()
        }
    }

    private fun setupViewPager() {
        val pager = viewPager ?: return
        pager.adapter = RecipientPagerAdapter(supportFragmentManager)
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateFilterBar(position)
                requestPermissionForPage(position)
            }
        })
        pager.currentItem = PAGE_RECENT
        updateFilterBar(PAGE_RECENT)
        requestPermissionForPage(PAGE_RECENT)
    }

    private fun requestPermissionForPage(page: Int) {
        when (page) {
            PAGE_RECENT -> requestCallLogPermissionIfNeeded()
            PAGE_CONTACTS -> requestContactsPermissionIfNeeded()
        }
    }

    private fun setupFilterTabs() {
        filterRecent?.setOnClickListener {
            viewPager?.currentItem = PAGE_RECENT
        }
        filterContacts?.setOnClickListener {
            viewPager?.currentItem = PAGE_CONTACTS
        }
        updateFilterBar(PAGE_RECENT)
    }

    private fun updateFilterBar(activePage: Int) {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        val isRecent = activePage == PAGE_RECENT
        filterRecentLine?.setBackgroundColor(if (isRecent) primaryColor else textColor)
        filterContactsLine?.setBackgroundColor(if (isRecent) textColor else primaryColor)
        filterRecentLine?.visibility = if (isRecent) View.VISIBLE else View.GONE
        filterContactsLine?.visibility = if (isRecent) View.GONE else View.VISIBLE
    }

    private fun syncFilterBarTranslation() {
        filterBar?.translationY = appBarVerticalOffset.toFloat()
        syncListInsets()
    }

    private fun setupBottomActionTabs() {
        val rippleBlurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return
        val items = ArrayList<IconItem>().apply {
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_cancel_fill
                title = getString(com.android.common.R.string.cancel_common)
            })
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_circle_check_fill
                title = getString(com.android.common.R.string.confirm_common)
            })
        }
        confirmTab?.setTabs(this, items, rippleBlurTarget)
        confirmTab?.setOnClickedListener { index ->
            when (index) {
                0 -> {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                1 -> {
                    if (selectedByNormalized.isEmpty()) return@setOnClickedListener
                    returnSelectedRecipients()
                }
            }
        }
        updateConfirmTabEnable()
    }

    private fun updateConfirmTabEnable() {
        confirmTab?.setRippleTabEnabledWidthAlpha(1, selectedByNormalized.isNotEmpty())
    }

    private fun returnSelectedRecipients() {
        val selectedContacts = ArrayList(selectedByNormalized.values)
        val contactNumbersCount = HashMap<String, HashSet<String>>()
        selectedContacts.forEach { contact ->
            if (contact.contactId.isNotEmpty() && contact.phoneNumber.isNotEmpty()) {
                val normalized = normalizePhone(contact.phoneNumber)
                if (normalized.isNotEmpty()) {
                    contactNumbersCount.getOrPut(contact.contactId) { HashSet() }.add(normalized)
                }
            }
        }
        val multiNumberContactIds = contactNumbersCount.filterValues { it.size > 1 }.keys
        val displayTexts = ArrayList<String>()
        val normalizedNumbers = ArrayList<String>()
        val usedDisplayTexts = HashSet<String>()
        selectedContacts.forEach { contact ->
            val normalizedNumber = normalizePhone(contact.phoneNumber)
            val baseName = contact.name.ifEmpty { contact.phoneNumber }
            val shouldShowNumber = contact.contactId.isNotEmpty() && multiNumberContactIds.contains(contact.contactId)
            val baseDisplayText = if (shouldShowNumber && contact.phoneNumber.isNotEmpty()) {
                "$baseName (${contact.phoneNumber})"
            } else {
                baseName
            }
            val uniqueDisplayText = if (!usedDisplayTexts.contains(baseDisplayText)) {
                baseDisplayText
            } else if (contact.phoneNumber.isNotEmpty()) {
                "$baseDisplayText (${contact.phoneNumber})"
            } else {
                "$baseDisplayText ($normalizedNumber)"
            }
            usedDisplayTexts.add(uniqueDisplayText)
            displayTexts.add(uniqueDisplayText)
            normalizedNumbers.add(normalizedNumber)
        }
        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(ContactPickerActivity.EXTRA_SELECTED_CONTACTS, selectedContacts)
            putStringArrayListExtra(ContactPickerActivity.EXTRA_SELECTED_DISPLAY_TEXTS, displayTexts)
            putStringArrayListExtra(ContactPickerActivity.EXTRA_SELECTED_PHONE_NUMBERS, normalizedNumbers)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun makeSystemBarsTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bottomSideFrame = findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)
        ViewCompat.setOnApplyWindowInsetsListener(rootView!!) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            bottomSideFrame.layoutParams = bottomSideFrame.layoutParams.apply { height = navHeight + dp5 }
            bottomBarContainer?.let { barContainer ->
                val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
                bottomBarLp.bottomMargin = if (ime.bottom > 0) ime.bottom else navHeight
                barContainer.layoutParams = bottomBarLp
            }
            applyListBottomInset(if (ime.bottom > 0) ime.bottom else navHeight)
            insets
        }
    }

    private fun applyListBottomInset(bottomInset: Int) {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val bottomPx = bottomInset + activityMargin + dp(90)
        resolveListTopInsetIfNeeded()
        val topPx = if (listTopInsetPx >= 0) listTopInsetPx else 0
        pageFragments.forEach { it?.applyListInsets(topPx, bottomPx) }
    }

    private fun syncListInsets() {
        resolveListTopInsetIfNeeded()
        if (listTopInsetPx < 0) {
            scheduleListTopInsetAfterFilterBarLayout()
            return
        }
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val nav = ViewCompat.getRootWindowInsets(rootView ?: return)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val bottomPx = nav + activityMargin + dp(90)
        pageFragments.forEach { it?.applyListInsets(listTopInsetPx, bottomPx) }
    }

    private fun resolveListTopInsetIfNeeded() {
        if (listTopInsetPx >= 0) return
        val bar = filterBar ?: return
        if (bar.height <= 0) return
        val loc = IntArray(2)
        bar.getLocationInWindow(loc)
        listTopInsetPx = loc[1] + bar.height
    }

    private fun scheduleListTopInsetAfterFilterBarLayout() {
        val bar = filterBar ?: return
        if (filterBarInsetListener != null) return
        filterBarInsetListener = ViewTreeObserver.OnGlobalLayoutListener {
            resolveListTopInsetIfNeeded()
            if (listTopInsetPx >= 0) {
                clearFilterBarInsetListener()
                syncListInsets()
            }
        }
        bar.viewTreeObserver.addOnGlobalLayoutListener(filterBarInsetListener)
    }

    private fun clearFilterBarInsetListener() {
        val bar = filterBar ?: return
        filterBarInsetListener?.let { listener ->
            bar.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            filterBarInsetListener = null
        }
    }

    private fun initMVSideFrames() {
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)?.bindBlurTarget(blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)?.bindBlurTarget(blurTarget)
    }

    private fun refreshSideFrameBlurAndInsets() {
        rootView?.post {
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return@post
            ViewCompat.requestApplyInsets(rootView!!)
            findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)?.bindBlurTarget(blurTarget)
            findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)?.bindBlurTarget(blurTarget)
            appBar?.getBackArrow()?.bindBlurTarget(this@RecipientSelectActivity, blurTarget)
            appBar?.getActionBarView()?.bindBlurTarget(this@RecipientSelectActivity, blurTarget)
            findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)?.update()
            syncListInsets()
        }
    }

    private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class RecipientPagerAdapter(fragmentManager: FragmentManager) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getCount(): Int = 2

        override fun getItem(position: Int): Fragment {
            val fragment = RecipientSelectListFragment.newInstance(position)
            pageFragments[position] = fragment
            return fragment
        }
    }

    companion object {
        const val REQUEST_CODE_RECIPIENT_SELECT = 1013
        private const val PERMISSION_REQUEST_READ_CONTACTS = 200
        private const val PERMISSION_REQUEST_READ_CALL_LOG = 201
        private const val PAGE_RECENT = RecipientSelectListFragment.PAGE_RECENT
        private const val PAGE_CONTACTS = RecipientSelectListFragment.PAGE_CONTACTS
    }
}
