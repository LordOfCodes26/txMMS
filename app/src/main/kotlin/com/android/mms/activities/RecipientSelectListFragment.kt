package com.android.mms.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.mms.R
import com.android.mms.adapters.ContactPickerAdapter
import com.android.mms.helpers.RecipientSelectCallLogLoader
import com.android.mms.helpers.RecipientSelectContactsLoader
import com.android.mms.helpers.RecipientSelectionHost
import com.android.mms.models.Contact
import com.android.mms.models.ContactPickerListRow
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.commons.views.MyTextView

class RecipientSelectListFragment : Fragment() {

    private var pageIndex = PAGE_RECENT
    private var recyclerView: MyRecyclerView? = null
    private var placeholder: MyTextView? = null
    private var adapter: ContactPickerAdapter? = null
    private val allContacts = ArrayList<Contact>()
    private var listRows = emptyList<ContactPickerListRow>()
    private var listTopInsetPx = 0
    private var listBottomInsetPx = 0
    private var dataLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageIndex = arguments?.getInt(ARG_PAGE, PAGE_RECENT) ?: PAGE_RECENT
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_recipient_select_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        placeholder = view.findViewById(R.id.recipient_select_placeholder)
        recyclerView = view.findViewById<MyRecyclerView>(R.id.recipient_select_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }
        adapter = ContactPickerAdapter(requireContext()).also { pickerAdapter ->
            pickerAdapter.setListener(object : ContactPickerAdapter.ContactPickerAdapterListener {
                override fun onContactToggled(contactIndex: Int, isSelected: Boolean) {
                    val host = selectionHost() ?: return
                    if (contactIndex !in allContacts.indices) return
                    host.onRecipientToggled(pageIndex, contactIndex, allContacts[contactIndex], isSelected)
                }
            })
            recyclerView?.adapter = pickerAdapter
        }
        applyListPadding()
    }

    override fun onResume() {
        super.onResume()
        if (!dataLoaded) {
            loadData()
        } else if (pageIndex == PAGE_RECENT) {
            adapter?.scheduleGroupedTodayTimeRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        adapter?.pauseGroupedTodayTimeRefresh()
    }

    fun applyListInsets(topPx: Int, bottomPx: Int) {
        listTopInsetPx = topPx
        listBottomInsetPx = bottomPx
        applyListPadding()
    }

    fun reloadData() {
        dataLoaded = false
        loadData()
    }

    fun refreshSelectionFromHost() {
        val host = selectionHost() ?: return
        val selected = indicesForNormalized(host.selectedNormalizedNumbers())
        if (pageIndex == PAGE_RECENT) {
            adapter?.setCallLogModeItems(listRows, allContacts, selected)
        } else {
            adapter?.setContactModeItems(allContacts, selected)
        }
    }

    private fun loadData() {
        val host = selectionHost() ?: return
        val activity = host as? RecipientSelectActivity ?: return
        val alreadySelected = host.selectedNormalizedNumbers()
        if (pageIndex == PAGE_RECENT) {
            if (!activity.hasCallLogPermission()) {
                showPlaceholder(getString(R.string.no_call_log))
                return
            }
            RecipientSelectCallLogLoader.load(
                context = requireContext(),
                alreadySelectedNormalized = alreadySelected,
                onResult = { contacts, rows, selected ->
                    if (!isAdded) return@load
                    activity.runOnUiThread {
                        dataLoaded = true
                        allContacts.clear()
                        allContacts.addAll(contacts)
                        listRows = rows
                        placeholder?.visibility = View.GONE
                        recyclerView?.visibility = View.VISIBLE
                        adapter?.setCallLogModeItems(rows, allContacts, selected)
                    }
                },
                onEmpty = {
                    if (!isAdded) return@load
                    activity.runOnUiThread {
                        dataLoaded = true
                        allContacts.clear()
                        listRows = emptyList()
                        showPlaceholder(getString(R.string.no_call_log))
                    }
                },
                onPermissionDenied = {
                    if (!isAdded) return@load
                    activity.runOnUiThread {
                        showPlaceholder(getString(R.string.no_call_log))
                    }
                },
            )
        } else {
            if (!activity.hasContactsPermission()) {
                showPlaceholder(getString(com.goodwy.commons.R.string.no_contacts_found))
                return
            }
            RecipientSelectContactsLoader.load(
                context = requireContext(),
                alreadySelectedNormalized = alreadySelected,
                onResult = { contacts, selected ->
                    if (!isAdded) return@load
                    activity.runOnUiThread {
                        dataLoaded = true
                        allContacts.clear()
                        allContacts.addAll(contacts)
                        placeholder?.visibility = View.GONE
                        recyclerView?.visibility = View.VISIBLE
                        adapter?.setContactModeItems(allContacts, selected)
                    }
                },
                onEmpty = {
                    if (!isAdded) return@load
                    activity.runOnUiThread {
                        dataLoaded = true
                        allContacts.clear()
                        showPlaceholder(getString(com.goodwy.commons.R.string.no_contacts_found))
                    }
                },
            )
        }
    }

    private fun showPlaceholder(text: String) {
        placeholder?.text = text
        placeholder?.visibility = View.VISIBLE
        recyclerView?.visibility = View.GONE
    }

    private fun applyListPadding() {
        recyclerView?.setPadding(0, listTopInsetPx, 0, listBottomInsetPx)
    }

    private fun indicesForNormalized(normalized: Set<String>): Set<Int> {
        val out = HashSet<Int>()
        allContacts.forEachIndexed { index, contact ->
            if (normalized.contains(normalizePhone(contact.phoneNumber))) {
                out.add(index)
            }
        }
        return out
    }

    private fun selectionHost(): RecipientSelectionHost? {
        val activity = activity
        return activity as? RecipientSelectionHost
    }

    private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() }

    companion object {
        private const val ARG_PAGE = "page"
        const val PAGE_RECENT = 0
        const val PAGE_CONTACTS = 1

        fun newInstance(page: Int): RecipientSelectListFragment {
            return RecipientSelectListFragment().apply {
                arguments = Bundle().apply { putInt(ARG_PAGE, page) }
            }
        }
    }
}
