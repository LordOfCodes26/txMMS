package com.android.mms.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.goodwy.commons.extensions.*
import com.android.mms.R
import com.android.mms.adapters.ContactPhonePair
import com.android.mms.adapters.ContactPickerAdapter
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.SimpleContact
import com.goodwy.commons.models.PhoneNumber

class ContactPickerActivity : SimpleActivity() {

    private var contactPhonePairs = ArrayList<ContactPhonePair>()
    private val selectedPositions = mutableSetOf<Int>()
    private var adapter: ContactPickerAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_picker)

        title = getString(R.string.select_contacts)
        updateTextColors(findViewById(R.id.contact_picker_appbar))

        findViewById<android.view.View>(R.id.contact_picker_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        findViewById<android.view.View>(R.id.contact_picker_done).setOnClickListener {
            returnSelectedContacts()
        }

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.contact_picker_recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        handlePermission(PERMISSION_READ_CONTACTS) { granted ->
            if (granted) {
                loadContacts()
            } else {
                toast(com.goodwy.commons.R.string.no_contacts_permission, length = android.widget.Toast.LENGTH_LONG)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(findViewById(R.id.contact_picker_appbar), NavigationIcon.Arrow)
    }

    private fun loadContacts() {
        ensureBackgroundThread {
            SimpleContactsHelper(this).getAvailableContacts(false) { contacts ->
                contactPhonePairs.clear()
                contacts.forEach { contact ->
                    if (contact.phoneNumbers.isEmpty()) return@forEach
                    if (contact.phoneNumbers.size == 1) {
                        contactPhonePairs.add(ContactPhonePair(contact, contact.phoneNumbers.first()))
                    } else {
                        contact.phoneNumbers.forEach { phoneNumber ->
                            contactPhonePairs.add(ContactPhonePair(contact, phoneNumber))
                        }
                    }
                }
                contactPhonePairs.sortWith(compareBy { it.contact.name.lowercase() })
                runOnUiThread {
                    adapter = ContactPickerAdapter(
                        activity = this,
                        items = contactPhonePairs,
                        selectedPositions = selectedPositions,
                        listener = { _, _ -> }
                    )
                    findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.contact_picker_recycler).adapter = adapter
                    adapter?.setItems(contactPhonePairs)
                }
            }
        }
    }

    private fun getDisplayTextForPhoneNumber(phoneNumber: PhoneNumber, contact: SimpleContact): String {
        val contactName = contact.name.ifEmpty { phoneNumber.normalizedNumber }
        return if (contact.phoneNumbers.size > 1) {
            "$contactName (${phoneNumber.value})"
        } else {
            contactName
        }
    }

    private fun returnSelectedContacts() {
        val displayTexts = ArrayList<String>()
        val normalizedNumbers = ArrayList<String>()
        selectedPositions.sorted().forEach { position ->
            if (position in contactPhonePairs.indices) {
                val pair = contactPhonePairs[position]
                val displayText = getDisplayTextForPhoneNumber(pair.phoneNumber, pair.contact)
                displayTexts.add(displayText)
                normalizedNumbers.add(pair.phoneNumber.normalizedNumber)
            }
        }

        val resultIntent = Intent().apply {
            putStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS, displayTexts)
            putStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS, normalizedNumbers)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_SELECTED_DISPLAY_TEXTS = "selected_display_texts"
        const val EXTRA_SELECTED_PHONE_NUMBERS = "selected_phone_numbers"

        fun getSelectedDisplayTexts(data: Intent?): ArrayList<String> {
            return data?.getStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS) ?: arrayListOf()
        }

        fun getSelectedPhoneNumbers(data: Intent?): ArrayList<String> {
            return data?.getStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS) ?: arrayListOf()
        }
    }
}
