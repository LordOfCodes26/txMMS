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
    private var fullListRows = emptyList<ContactPickerListRow>()
    private var displayedContacts = emptyList<Contact>()
    private var listTopInsetPx = 0
    private var listBottomInsetPx = 0
    private var dataLoaded = false
    private var searchQuery = ""

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
        (activity as? RecipientSelectActivity)?.bindListScrollBehavior(recyclerView!!)
        adapter = ContactPickerAdapter(requireContext()).also { pickerAdapter ->
            pickerAdapter.setListener(object : ContactPickerAdapter.ContactPickerAdapterListener {
                override fun onContactToggled(contactIndex: Int, isSelected: Boolean) {
                    val host = selectionHost() ?: return
                    val contact = resolveToggledContact(contactIndex) ?: return
                    host.onRecipientToggled(pageIndex, contactIndex, contact, isSelected)
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
        } else if (pageIndex == PAGE_RECENT && searchQuery.isEmpty()) {
            adapter?.scheduleGroupedTodayTimeRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        adapter?.pauseGroupedTodayTimeRefresh()
    }

    fun listRecyclerView(): MyRecyclerView? = recyclerView

    fun applyListInsets(
        topPx: Int,
        bottomPx: Int,
        stabilizeTopPadding: Boolean = false,
        animateTopPaddingStabilization: Boolean = false,
    ) {
        val rv = recyclerView ?: return
        val oldTop = rv.paddingTop
        listTopInsetPx = topPx
        listBottomInsetPx = bottomPx
        if (oldTop != topPx || rv.paddingBottom != bottomPx) {
            rv.setPadding(0, topPx, 0, bottomPx)
        }
        if (stabilizeTopPadding && oldTop != topPx) {
            rv.animate().cancel()
            rv.translationY += (oldTop - topPx).toFloat()
            if (animateTopPaddingStabilization) {
                rv.animate()
                    .translationY(0f)
                    .setDuration(TX_SEARCH_LIST_PADDING_ANIM_MS)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .start()
            }
        }
    }

    fun resetListTranslation() {
        recyclerView?.animate()?.cancel()
        recyclerView?.translationY = 0f
    }

    fun reloadData() {
        dataLoaded = false
        searchQuery = ""
        loadData()
    }

    fun applySearchQuery(query: String) {
        searchQuery = query.trim()
        if (!dataLoaded) return
        updateDisplayedList()
    }

    fun clearSearch() {
        searchQuery = ""
        if (!dataLoaded) return
        updateDisplayedList()
    }

    fun refreshSelectionFromHost() {
        if (!dataLoaded) return
        updateDisplayedList()
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
                        fullListRows = rows
                        searchQuery = ""
                        placeholder?.visibility = View.GONE
                        recyclerView?.visibility = View.VISIBLE
                        adapter?.setCallLogModeItems(rows, allContacts, selected, searchQuery)
                    }
                },
                onEmpty = {
                    if (!isAdded) return@load
                    activity.runOnUiThread {
                        dataLoaded = true
                        allContacts.clear()
                        fullListRows = emptyList()
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
                        searchQuery = ""
                        placeholder?.visibility = View.GONE
                        recyclerView?.visibility = View.VISIBLE
                        adapter?.setContactModeItems(allContacts, selected, searchQuery)
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

    private fun updateDisplayedList() {
        val host = selectionHost() ?: return
        val selectedNormalized = host.selectedNormalizedNumbers()
        val query = searchQuery.lowercase()

        if (pageIndex == PAGE_RECENT) {
            if (allContacts.isEmpty()) return
            val rows = if (query.isEmpty()) {
                fullListRows
            } else {
                val matchingIndices = allContacts.indices.filter { contactMatches(allContacts[it], query) }.toSet()
                filterCallLogRows(fullListRows, matchingIndices)
            }
            val selected = indicesForNormalized(selectedNormalized)
            if (rows.isEmpty()) {
                showPlaceholder(getString(R.string.no_call_log))
            } else {
                placeholder?.visibility = View.GONE
                recyclerView?.visibility = View.VISIBLE
                adapter?.setCallLogModeItems(rows, allContacts, selected, searchQuery)
                if (query.isEmpty()) {
                    adapter?.scheduleGroupedTodayTimeRefresh()
                }
            }
            return
        }

        val displayed = if (query.isEmpty()) {
            allContacts
        } else {
            allContacts.filter { contactMatches(it, query) }
        }
        displayedContacts = displayed
        val displayedSelected = displayed.mapIndexedNotNull { index, contact ->
            if (selectedNormalized.contains(normalizePhone(contact.phoneNumber))) index else null
        }.toSet()
        if (displayed.isEmpty()) {
            showPlaceholder(getString(com.goodwy.commons.R.string.no_contacts_found))
        } else {
            placeholder?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter?.setContactModeItems(displayed, displayedSelected, searchQuery)
        }
    }

    private fun resolveToggledContact(contactIndex: Int): Contact? {
        if (pageIndex == PAGE_CONTACTS && searchQuery.isNotEmpty()) {
            return displayedContacts.getOrNull(contactIndex)
        }
        return allContacts.getOrNull(contactIndex)
    }

    private fun filterCallLogRows(
        rows: List<ContactPickerListRow>,
        matchingIndices: Set<Int>,
    ): List<ContactPickerListRow> {
        val filtered = ArrayList<ContactPickerListRow>()
        var pendingSection: ContactPickerListRow.DateSection? = null
        for (row in rows) {
            when (row) {
                is ContactPickerListRow.DateSection -> pendingSection = row
                is ContactPickerListRow.ContactRow -> {
                    if (matchingIndices.contains(row.contactIndex)) {
                        pendingSection?.let {
                            filtered.add(it)
                            pendingSection = null
                        }
                        filtered.add(row)
                    }
                }
            }
        }
        return filtered
    }

    private fun contactMatches(contact: Contact, query: String): Boolean {
        return contact.name.lowercase().contains(query) ||
            contact.phoneNumber.lowercase().contains(query) ||
            contact.address.lowercase().contains(query) ||
            contact.organizationName.lowercase().contains(query)
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
        return activity as? RecipientSelectionHost
    }

    private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() }

    companion object {
        private const val ARG_PAGE = "page"
        const val PAGE_RECENT = 0
        const val PAGE_CONTACTS = 1
        private const val TX_SEARCH_LIST_PADDING_ANIM_MS = 300L

        fun newInstance(page: Int): RecipientSelectListFragment {
            return RecipientSelectListFragment().apply {
                arguments = Bundle().apply { putInt(ARG_PAGE, page) }
            }
        }
    }
}
