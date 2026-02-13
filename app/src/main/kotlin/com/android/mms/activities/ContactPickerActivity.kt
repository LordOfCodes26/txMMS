package com.android.mms.activities

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.commons.views.MyTextView
import com.android.common.helper.IconItem
import com.android.common.view.MRippleToolBar
import com.android.common.view.MSearchView
import com.android.mms.R
import com.android.mms.adapters.ContactPickerAdapter
import com.android.mms.models.Contact
import com.goodwy.commons.views.BlurAppBarLayout
import eightbitlab.com.blurview.BlurTarget

class ContactPickerActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_READ_CONTACTS = 100
        private const val PERMISSION_REQUEST_READ_CALL_LOG = 101
        private const val CALL_LOG_LIMIT = 500
        const val EXTRA_SELECTED_CONTACTS = "selected_contacts"
        const val EXTRA_ALREADY_SELECTED_CONTACTS = "already_selected_contacts"
        const val EXTRA_SELECTED_DISPLAY_TEXTS = "selected_display_texts"
        const val EXTRA_SELECTED_PHONE_NUMBERS = "selected_phone_numbers"
        private const val BATCH_SIZE = 35

        fun getSelectedContacts(data: Intent?): ArrayList<Contact> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_CONTACTS)) {
                @Suppress("UNCHECKED_CAST")
                val contacts = data.getParcelableArrayListExtra<Contact>(EXTRA_SELECTED_CONTACTS)
                return contacts ?: arrayListOf()
            }
            return arrayListOf()
        }

        fun getSelectedDisplayTexts(data: Intent?): ArrayList<String> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_DISPLAY_TEXTS)) {
                return data.getStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS) ?: arrayListOf()
            }
            return arrayListOf()
        }

        fun getSelectedPhoneNumbers(data: Intent?): ArrayList<String> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_PHONE_NUMBERS)) {
                return data.getStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS) ?: arrayListOf()
            }
            return arrayListOf()
        }
    }

    private var scrollView: View? = null
    private var blurAppBarLayout: BlurAppBarLayout? = null
    private var totalOffset = 0
    private var rootView: View? = null
    private var contactRecyclerView: MyRecyclerView? = null
    private var contactAdapter: ContactPickerAdapter? = null
    private val allContacts = ArrayList<Contact>()
    private val filteredContacts = ArrayList<Contact>()
    private val selectedPositions = HashSet<Int>()
    private var searchString = ""
    private var contactsCursor: android.database.Cursor? = null
    private var isLoadingMore = false
    private var hasMoreContacts = true
    private val addedContactIds = HashSet<String>()
    private val alreadySelectedContactIds = HashSet<String>()
    private var bottomBarContainer: View? = null
    private var tabBar: MRippleToolBar? = null
    private var isCallLogMode = false
    private var filterCallLog: MyTextView? = null
    private var filterContacts: MyTextView? = null
    private var callLogPlaceholder: View? = null
    private var contactPickerFilterBar: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_picker)

        rootView = findViewById(R.id.root_view)
        initTheme()
        initBouncy()
        initBouncyListener()
        initComponent()
        makeSystemBarsToTransparent()

        if (checkContactsPermission()) {
            loadContacts()
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    override fun onResume() {
        super.onResume()
        if (isDarkTheme()) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
        }
    }

    private fun isDarkTheme(): Boolean {
        return (getSystemService(UI_MODE_SERVICE) as UiModeManager).nightMode == UiModeManager.MODE_NIGHT_YES;
    }

    override fun onDestroy() {
        super.onDestroy()
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bottomMask = findViewById<View>(R.id.bottomMask)

        ViewCompat.setOnApplyWindowInsetsListener(rootView!!) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            bottomMask.layoutParams = bottomMask.layoutParams.apply { height = navHeight + dp(5) }

            val barContainer = bottomBarContainer
            if (barContainer != null) {
                val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
                val bottomOffset = dp(0)
                if (ime.bottom > 0) {
                    bottomBarLp.bottomMargin = ime.bottom + bottomOffset
                    contactRecyclerView?.setPadding(0, dp(200), 0, dp(40) + navHeight + ime.bottom)
                    contactRecyclerView?.scrollToPosition((contactAdapter?.itemCount ?: 1) - 1)
                } else {
                    bottomBarLp.bottomMargin = navHeight + bottomOffset
                    contactRecyclerView?.setPadding(0, dp(200), 0, dp(90) + navHeight)
                }
                barContainer.layoutParams = bottomBarLp
            }
            insets
        }
    }

    private fun initBouncy() {
        blurAppBarLayout = findViewById(R.id.blur_app_bar_layout)
        scrollView = findViewById(R.id.nest_scroll)
        blurAppBarLayout?.post {
            totalOffset = blurAppBarLayout?.totalScrollRange ?: 0
        }
    }

    private fun initBouncyListener() {
        blurAppBarLayout?.setupOffsetListener { verticalOffset, height ->
            val h = if (height > 0) height else 1
            blurAppBarLayout?.titleView?.scaleX = (1 + 0.7f * verticalOffset / h)
            blurAppBarLayout?.titleView?.scaleY = (1 + 0.7f * verticalOffset / h)
        }
    }

    private fun initComponent() {
        blurAppBarLayout?.setTitle(getString(R.string.select_contacts))

        bottomBarContainer = findViewById(R.id.lyt_action)
        tabBar = findViewById(R.id.confirm_tab)

        val items = ArrayList<IconItem>().apply {
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_cancel
                title = getString(com.android.common.R.string.cancel_common)
            })
            add(IconItem().apply {
                icon = R.drawable.ic_check_double_vector
                title = getString(com.android.common.R.string.confirm_common)
            })
        }
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
        tabBar?.setTabs(this, items, blurTarget)

        tabBar?.setOnClickedListener { index ->
            when (index) {
                0 -> {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                1 -> returnSelectedContacts()
            }
        }

        blurAppBarLayout?.toolbar?.apply {
            inflateMenu(R.menu.menu_contact_picker)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.search) {
                    blurAppBarLayout?.startSearch()
                    true
                } else false
            }
        }
        blurAppBarLayout?.setOnSearchStateListener(object : BlurAppBarLayout.OnSearchStateListener {
            override fun onState(state: Int) {
                when (state) {
                    MSearchView.SEARCH_START -> {
                        contactRecyclerView?.isNestedScrollingEnabled = false
                        contactRecyclerView?.scrollToPosition((contactAdapter?.itemCount ?: 1) - 1)
                    }
                    MSearchView.SEARCH_END -> {
                        contactRecyclerView?.isNestedScrollingEnabled = true
                    }
                }
            }
            override fun onSearchTextChanged(s: String?) {
                searchString = s ?: ""
                searchListByQuery(searchString)
            }
        })

        contactRecyclerView = findViewById<MyRecyclerView>(R.id.contactRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@ContactPickerActivity)
            setHasFixedSize(false)
        }
        contactAdapter = ContactPickerAdapter(this)
        contactRecyclerView?.adapter = contactAdapter

        contactRecyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val visible = lm.childCount
                val total = lm.itemCount
                val first = lm.findFirstVisibleItemPosition()
                if (!isCallLogMode && !isLoadingMore && hasMoreContacts && searchString.isEmpty()) {
                    if (visible + first >= total - 5) loadMoreContacts()
                }
            }
        })

        contactAdapter?.setListener(object : ContactPickerAdapter.ContactPickerAdapterListener {
            override fun onContactToggled(position: Int, isSelected: Boolean) {
                if (position !in filteredContacts.indices) return
                val contact = filteredContacts[position]
                val idx = allContacts.indexOfFirst {
                    if (contact.contactId.isEmpty()) it.phoneNumber == contact.phoneNumber
                    else it.contactId == contact.contactId && it.phoneNumber == contact.phoneNumber
                }
                if (idx >= 0) {
                    if (isSelected) selectedPositions.add(idx) else selectedPositions.remove(idx)
                }
            }
        })

        contactPickerFilterBar = findViewById(R.id.contact_picker_filter_bar)
        val filterBar = contactPickerFilterBar as? ViewGroup
        callLogPlaceholder = findViewById(R.id.call_log_placeholder)
        setupFilterBarScrollBehavior()
        if (filterBar != null && filterBar.childCount >= 2) {
            filterCallLog = filterBar.getChildAt(0) as? MyTextView
            filterContacts = filterBar.getChildAt(1) as? MyTextView
        } else {
            filterCallLog = findViewById(R.id.filter_call_log)
            filterContacts = findViewById(R.id.filter_contacts)
        }

        filterCallLog?.let { callLogTab ->
            callLogTab.isClickable = true
            callLogTab.isFocusable = true
            callLogTab.setOnClickListener {
                if (!isCallLogMode) {
                    isCallLogMode = true
                    updateFilterBar()
                    if (checkCallLogPermission()) loadCallLog()
                }
            }
        }
        filterContacts?.let { contactsTab ->
            contactsTab.isClickable = true
            contactsTab.isFocusable = true
            contactsTab.setOnClickListener {
                if (isCallLogMode) {
                    isCallLogMode = false
                    updateFilterBar()
                    if (checkContactsPermission()) loadContacts()
                }
            }
        }
        updateFilterBar()
    }

    private fun updateFilterBar() {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        if (isCallLogMode) {
            filterCallLog?.setTextColor(primaryColor)
            filterContacts?.setTextColor(textColor)
        } else {
            filterCallLog?.setTextColor(textColor)
            filterContacts?.setTextColor(primaryColor)
        }
    }

    /** Hides filter bar when app bar is scrolled (top item going up); shows when app bar is back at original (expanded). */
    private fun setupFilterBarScrollBehavior() {
        val bar = blurAppBarLayout ?: return
        val filterBar = contactPickerFilterBar ?: return
        bar.addOnOffsetChangedListener(object : AppBarLayout.OnOffsetChangedListener {
            override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
                val expanded = verticalOffset >= -dp(8)
                filterBar.visibility = if (expanded) View.VISIBLE else View.GONE
            }
        })
    }

    private fun searchListByQuery(s: String) {
        searchString = s
        if (s.trim().isEmpty()) {
            filteredContacts.clear()
            filteredContacts.addAll(allContacts)
            updateAdapterWithFilteredContacts()
            return
        }
        val query = s.lowercase().trim()
        filteredContacts.clear()
        for (contact in allContacts) {
            val matches = (contact.name?.lowercase()?.contains(query) == true) ||
                (contact.phoneNumber?.lowercase()?.contains(query) == true) ||
                (contact.address?.lowercase()?.contains(query) == true) ||
                (contact.organizationName?.lowercase()?.contains(query) == true)
            if (matches) filteredContacts.add(contact)
        }
        updateAdapterWithFilteredContacts()
    }

    private fun updateAdapterWithFilteredContacts() {
        val filteredSelected = HashSet<Int>()
        filteredContacts.forEachIndexed { i, contact ->
            val idx = allContacts.indexOfFirst {
                if (contact.contactId.isEmpty()) it.phoneNumber == contact.phoneNumber
                else it.contactId == contact.contactId && it.phoneNumber == contact.phoneNumber
            }
            if (idx >= 0 && selectedPositions.contains(idx)) filteredSelected.add(i)
        }
        contactAdapter?.setItems(filteredContacts, filteredSelected)
    }

    private fun checkContactsPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_READ_CONTACTS)
            false
        } else true
    }

    private fun checkCallLogPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), PERMISSION_REQUEST_READ_CALL_LOG)
            false
        } else true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_READ_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadContacts()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            PERMISSION_REQUEST_READ_CALL_LOG -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadCallLog()
                } else {
                    Toast.makeText(this, R.string.call_log_permission_required, Toast.LENGTH_LONG).show()
                    isCallLogMode = false
                    updateFilterBar()
                }
            }
        }
    }

    private fun loadCallLog() {
        allContacts.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        callLogPlaceholder?.visibility = View.GONE
        contactRecyclerView?.visibility = View.VISIBLE

        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(EXTRA_ALREADY_SELECTED_CONTACTS) ?: arrayListOf()
        val alreadyNumbers = alreadySelected.map { normalizePhoneNumber(it.phoneNumber) }.toSet()

        Thread {
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE
                )
                var cursor: android.database.Cursor? = null
                try {
                    cursor = contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                } catch (_: SecurityException) {
                    runOnUiThread {
                        callLogPlaceholder?.visibility = View.VISIBLE
                        contactRecyclerView?.visibility = View.GONE
                        contactAdapter?.setItems(emptyList(), emptySet())
                    }
                    return@Thread
                }

                val list = ArrayList<Contact>()
                val seenNumbers = HashSet<String>()
                var nameCol = -1
                cursor?.use { c ->
                    nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val numberCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                    if (numberCol < 0) return@use
                    var count = 0
                    while (c.moveToNext() && count < CALL_LOG_LIMIT) {
                        val number = c.getString(numberCol) ?: continue
                        if (number.isBlank()) continue
                        val normalized = normalizePhoneNumber(number)
                        if (seenNumbers.contains(normalized)) continue
                        seenNumbers.add(normalized)
                        var name = number
                        if (nameCol >= 0) {
                            val cached = c.getString(nameCol)
                            if (!cached.isNullOrBlank()) name = cached
                        }
                        list.add(Contact(name = name, contactId = "", phoneNumber = number))
                        count++
                    }
                }

                val selected = HashSet<Int>()
                list.forEachIndexed { index, contact ->
                    if (alreadyNumbers.contains(normalizePhoneNumber(contact.phoneNumber))) {
                        selected.add(index)
                    }
                }
                runOnUiThread {
                    allContacts.clear()
                    allContacts.addAll(list)
                    filteredContacts.clear()
                    if (searchString.trim().isEmpty()) {
                        filteredContacts.addAll(list)
                        contactAdapter?.setItems(list, selected)
                    } else {
                        searchListByQuery(searchString)
                    }
                    selectedPositions.clear()
                    selected.forEach { selectedPositions.add(it) }
                    if (list.isEmpty()) {
                        callLogPlaceholder?.visibility = View.VISIBLE
                        contactRecyclerView?.visibility = View.GONE
                    } else {
                        callLogPlaceholder?.visibility = View.GONE
                        contactRecyclerView?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    callLogPlaceholder?.visibility = View.VISIBLE
                    contactRecyclerView?.visibility = View.GONE
                    contactAdapter?.setItems(emptyList(), emptySet())
                }
            }
        }.start()
    }

    private fun loadContacts() {
        allContacts.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        addedContactIds.clear()
        hasMoreContacts = true
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null
        contactAdapter?.setItems(emptyList(), emptySet())
        callLogPlaceholder?.visibility = View.GONE
        contactRecyclerView?.visibility = View.VISIBLE

        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(EXTRA_ALREADY_SELECTED_CONTACTS) ?: arrayListOf()
        alreadySelectedContactIds.clear()
        alreadySelected.forEach { c ->
            if (c.contactId.isNotEmpty()) {
                val key = contactNumberKey(c.contactId, c.phoneNumber)
                alreadySelectedContactIds.add(key)
            }
        }

        try {
            contactsCursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
            if (contactsCursor != null) loadMoreContacts()
        } catch (e: Exception) {
            e.printStackTrace()
            hasMoreContacts = false
        }
    }

    private fun loadMoreContacts() {
        val cursor = contactsCursor ?: return
        if (isLoadingMore || cursor.isClosed) return
        isLoadingMore = true

        Thread {
            val batch = ArrayList<Contact>()
            val batchSelected = HashSet<Int>()
            var loaded = 0
            val currentPosition = allContacts.size

            try {
                while (cursor.moveToNext() && loaded < BATCH_SIZE) {
                    val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    if (name.isNullOrEmpty()) continue

                    val phoneNumbers = getAllPhoneNumbersForContact(contactId)
                    val address = getAddressForContact(contactId)
                    val organizationName = getOrganizationNameForContact(contactId)
                    val numbersToAdd = if (phoneNumbers.isEmpty()) listOf("") else phoneNumbers
                    for (phoneNumber in numbersToAdd) {
                        val key = contactNumberKey(contactId, phoneNumber)
                        if (addedContactIds.contains(key)) continue
                        val contact = Contact(name, contactId, -1, phoneNumber, address ?: "", organizationName ?: "")
                        batch.add(contact)
                        addedContactIds.add(key)
                        if (alreadySelectedContactIds.contains(key)) {
                            batchSelected.add(loaded)
                            selectedPositions.add(currentPosition + loaded)
                        }
                        loaded++
                        if (loaded >= BATCH_SIZE) break
                    }
                }
                hasMoreContacts = loaded >= BATCH_SIZE
                val finalBatch = ArrayList(batch)
                val finalSelected = HashSet(batchSelected)
                val batchStart = allContacts.size
                runOnUiThread {
                    if (finalBatch.isNotEmpty()) {
                        allContacts.addAll(finalBatch)
                        if (searchString.trim().isEmpty()) {
                            filteredContacts.addAll(finalBatch)
                            contactAdapter?.addItems(finalBatch, finalSelected)
                        } else {
                            filterAndAddBatch(finalBatch, batchStart, finalSelected)
                        }
                    }
                    isLoadingMore = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    isLoadingMore = false
                    hasMoreContacts = false
                }
            }
        }.start()
    }

    private fun filterAndAddBatch(
        batchContacts: List<Contact>,
        batchStartPosition: Int,
        batchSelectedPositions: Set<Int>
    ) {
        if (searchString.trim().isEmpty()) return
        val query = searchString.lowercase().trim()
        val matching = ArrayList<Contact>()
        val matchingSelected = HashSet<Int>()
        var filteredIndex = filteredContacts.size
        for (i in batchContacts.indices) {
            val c = batchContacts[i]
            val matches = (c.name.lowercase().contains(query)) ||
                (c.phoneNumber.lowercase().contains(query)) ||
                (c.address.lowercase().contains(query)) ||
                (c.organizationName.lowercase().contains(query))
            if (matches) {
                matching.add(c)
                val posInAll = batchStartPosition + i
                if (batchSelectedPositions.contains(i) || selectedPositions.contains(posInAll)) {
                    matchingSelected.add(filteredIndex)
                }
                filteredIndex++
            }
        }
        if (matching.isNotEmpty()) {
            filteredContacts.addAll(matching)
            contactAdapter?.addItems(matching, matchingSelected)
        }
    }

    private fun contactNumberKey(contactId: String, phoneNumber: String): String {
        return "$contactId|${normalizePhoneNumber(phoneNumber)}"
    }

    private fun getAllPhoneNumbersForContact(contactId: String): List<String> {
        val numbers = ArrayList<String>()
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone._ID} ASC"
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    if (!number.isNullOrEmpty()) numbers.add(number)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return numbers
    }

    private fun getAddressForContact(contactId: String): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                    ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                    ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                    ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY
                ),
                "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.StructuredPostal.IS_PRIMARY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                if (idx >= 0) {
                    val addr = cursor.getString(idx)
                    if (!addr.isNullOrEmpty()) return addr
                }
                val parts = listOf(
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY))
                ).filter { !it.isNullOrEmpty() }
                return parts.joinToString(", ").trim().trimEnd(',')
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return ""
    }

    private fun getOrganizationNameForContact(contactId: String): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Organization.COMPANY,
                    ContactsContract.Data.IS_PRIMARY
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                "${ContactsContract.Data.IS_PRIMARY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                if (idx >= 0) {
                    val company = cursor.getString(idx)
                    if (!company.isNullOrEmpty()) return company
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return ""
    }

    private fun returnSelectedContacts() {
        val selectedContacts = ArrayList<Contact>()
        for (pos in selectedPositions.sorted()) {
            if (pos in allContacts.indices) selectedContacts.add(allContacts[pos])
        }

        val displayTexts = ArrayList<String>()
        val normalizedNumbers = ArrayList<String>()
        selectedContacts.forEach { c ->
            displayTexts.add(if (c.name.isNotEmpty()) c.name else c.phoneNumber)
            normalizedNumbers.add(normalizePhoneNumber(c.phoneNumber))
        }

        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(EXTRA_SELECTED_CONTACTS, selectedContacts)
            putStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS, displayTexts)
            putStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS, normalizedNumbers)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun normalizePhoneNumber(phone: String): String {
        return phone.filter { it.isDigit() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
