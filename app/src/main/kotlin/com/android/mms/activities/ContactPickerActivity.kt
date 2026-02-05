package com.android.mms.activities

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.common.helper.IconItem
import com.android.common.view.MImageButton
import com.android.common.view.MRippleToolBar
import com.android.common.view.MSearchView
import com.android.mms.R
import com.android.mms.adapters.ContactPickerAdapter
import com.android.mms.models.Contact
import com.google.android.material.appbar.AppBarLayout
import eightbitlab.com.blurview.BlurTarget

class ContactPickerActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_READ_CONTACTS = 100
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
    private var appBarLayout: AppBarLayout? = null
    private var titleView: TextView? = null
    private var verticalOffset = 0
    private var totalOffset = 0
    private var rootView: View? = null
    private var contactRecyclerView: RecyclerView? = null
    private var contactAdapter: ContactPickerAdapter? = null
    private val allContacts = ArrayList<Contact>()
    private val filteredContacts = ArrayList<Contact>()
    private val selectedPositions = HashSet<Int>()
    private var searchBtn: MImageButton? = null
    private var searchView: MSearchView? = null
    private var searchContainer: FrameLayout? = null
    private var searchString = ""
    private var contactsCursor: android.database.Cursor? = null
    private var isLoadingMore = false
    private var hasMoreContacts = true
    private val addedContactIds = HashSet<String>()
    private val alreadySelectedContactIds = HashSet<String>()
    private var bottomBarContainer: View? = null
    private var tabBar: MRippleToolBar? = null

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
        val barContainer = bottomBarContainer ?: return

        ViewCompat.setOnApplyWindowInsetsListener(rootView!!) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            bottomMask.layoutParams = bottomMask.layoutParams.apply { height = navHeight + dp(5) }

            val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
            val bottomOffset = dp(20)
            bottomBarLp.bottomMargin = if (ime.bottom > 0) ime.bottom + bottomOffset else navHeight + bottomOffset
            barContainer.layoutParams = bottomBarLp
            insets
        }
    }

    private fun initBouncy() {
        appBarLayout = findViewById(R.id.app_bar_layout)
        scrollView = findViewById(R.id.nest_scroll)
        titleView = findViewById(R.id.tv_title)
        appBarLayout?.post {
            totalOffset = appBarLayout?.totalScrollRange ?: 0
        }
    }

    private fun initBouncyListener() {
        appBarLayout?.addOnOffsetChangedListener { _, verticalOffset ->
            this.verticalOffset = verticalOffset
            val height = appBarLayout?.height ?: 1
            titleView?.scaleX = (1 + 0.7f * verticalOffset / height)
            titleView?.scaleY = (1 + 0.7f * verticalOffset / height)
        }
    }

    private fun initComponent() {
        titleView?.text = getString(R.string.select_contacts)

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

        searchBtn = findViewById(R.id.iv_search_btn)
        searchView = findViewById(R.id.m_search_view)
        searchContainer = findViewById(R.id.fl_search_container)

        contactRecyclerView = findViewById<RecyclerView>(R.id.contactRecyclerView).apply {
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
                if (!isLoadingMore && hasMoreContacts && searchString.isEmpty()) {
                    if (visible + first >= total - 5) loadMoreContacts()
                }
            }
        })

        contactAdapter?.setListener(object : ContactPickerAdapter.ContactPickerAdapterListener {
            override fun onContactToggled(position: Int, isSelected: Boolean) {
                if (position !in filteredContacts.indices) return
                val contact = filteredContacts[position]
                val idx = allContacts.indexOfFirst { it.contactId == contact.contactId && it.phoneNumber == contact.phoneNumber }
                if (idx >= 0) {
                    if (isSelected) selectedPositions.add(idx) else selectedPositions.remove(idx)
                }
            }
        })

        searchBtn?.setOnClickListener {
            searchContainer?.visibility = View.VISIBLE
            searchView?.startSearch()
        }

        searchView?.setOnStateListener(object : MSearchView.OnSearchStateListener {
            override fun onState(state: Int) {
                when (state) {
                    MSearchView.SEARCH_START -> {
                        appBarLayout?.setExpanded(false)
                        searchContainer?.visibility = View.VISIBLE
                        searchBtn?.visibility = View.GONE
                        titleView?.visibility = View.GONE
                    }
                    MSearchView.SEARCH_END -> {
                        appBarLayout?.setExpanded(true)
                        titleView?.visibility = View.VISIBLE
                        searchBtn?.visibility = View.VISIBLE
                        searchContainer?.visibility = View.INVISIBLE
                    }
                }
            }
            override fun onSearchTextChanged(s: String?) {
                searchString = s ?: ""
                searchListByQuery(searchString)
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
            val idx = allContacts.indexOfFirst { it.contactId == contact.contactId && it.phoneNumber == contact.phoneNumber }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_CONTACTS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadContacts()
            } else {
                Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun loadContacts() {
        allContacts.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        addedContactIds.clear()
        hasMoreContacts = true
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null

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
