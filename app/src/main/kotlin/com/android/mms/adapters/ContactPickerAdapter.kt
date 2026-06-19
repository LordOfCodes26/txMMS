package com.android.mms.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.beVisibleIf
import com.android.mms.extensions.formatGroupedSectionDateTime
import com.android.mms.extensions.nextGroupedTodayLabelRefreshDelayMillis
import com.android.mms.extensions.normalizeGroupedListRelativeTextForKorean
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperAccentColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.highlightTextPart
import com.android.mms.R
import com.android.mms.extensions.config
import com.android.mms.helpers.bindContactPickerAvatar
import com.android.mms.models.Contact
import com.android.mms.models.ContactPickerListRow
import com.goodwy.commons.views.ContactAvatarView
import com.goodwy.commons.views.MyTextView
import android.provider.CallLog.Calls
import com.android.mms.helpers.resolveSimIconTint
import com.android.mms.helpers.subscriptionIdForOneBasedSimSlot

class ContactPickerAdapter(
    private val context: Context,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<ContactPickerListRow> = emptyList()
    private var contactLookup: List<Contact> = emptyList()
    private val showContactThumbnails = context.config.showContactThumbnails
    private val selectedContactIndices = mutableSetOf<Int>()
    private var listener: ContactPickerAdapterListener? = null
    private var isCallLogMode = false
    private var textToHighlight = ""

    private val groupedTodayRefreshHandler = Handler(Looper.getMainLooper())
    private val groupedTodayRefreshRunnable = Runnable {
        refreshTodaySectionCallLogRows()
        scheduleGroupedTodayTimeRefresh()
    }

    override fun getItemViewType(position: Int): Int = when {
        !isCallLogMode -> VIEW_TYPE_CONTACT
        rows[position] is ContactPickerListRow.DateSection -> VIEW_TYPE_SECTION
        else -> VIEW_TYPE_CALL_LOG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> {
                val v = inflater.inflate(R.layout.item_contact_picker_section, parent, false)
                SectionViewHolder(v)
            }
            VIEW_TYPE_CONTACT -> {
                val v = inflater.inflate(R.layout.item_contact_picker, parent, false)
                ContactViewHolder(v)
            }
            else -> {
                val v = inflater.inflate(R.layout.item_contact_picker_call_log, parent, false)
                CallLogRowViewHolder(v)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (!isCallLogMode) {
            (holder as ContactViewHolder).bind(position)
            return
        }
        when (val row = rows[position]) {
            is ContactPickerListRow.DateSection -> (holder as SectionViewHolder).bind(row)
            is ContactPickerListRow.ContactRow -> (holder as CallLogRowViewHolder).bind(row, position)
        }
    }

    override fun getItemCount(): Int = if (isCallLogMode) rows.size else contactLookup.size

    fun setListener(l: ContactPickerAdapterListener?) {
        listener = l
    }

    /** Contacts tab: one row per contact, indices match [contacts] list. */
    fun setContactModeItems(
        contacts: List<Contact>?,
        selectedIndices: Set<Int>?,
        highlightQuery: String = "",
    ) {
        pauseGroupedTodayTimeRefresh()
        isCallLogMode = false
        rows = emptyList()
        contactLookup = if (contacts.isNullOrEmpty()) emptyList() else ArrayList(contacts)
        selectedContactIndices.clear()
        selectedContactIndices.addAll(selectedIndices ?: emptySet())
        textToHighlight = highlightQuery.trim()
        notifyDataSetChanged()
    }

    /** Call log tab: sections + rows; [contactLookup] is full [allContacts] for index resolution. */
    fun setCallLogModeItems(
        listRows: List<ContactPickerListRow>,
        contactSource: List<Contact>,
        selectedIndices: Set<Int>?,
        highlightQuery: String = "",
    ) {
        isCallLogMode = true
        contactLookup = contactSource
        selectedContactIndices.clear()
        selectedContactIndices.addAll(selectedIndices ?: emptySet())
        rows = listRows
        textToHighlight = highlightQuery.trim()
        notifyDataSetChanged()
        scheduleGroupedTodayTimeRefresh()
    }

    fun scheduleGroupedTodayTimeRefresh() {
        if (!isCallLogMode) return
        groupedTodayRefreshHandler.removeCallbacks(groupedTodayRefreshRunnable)
        var minDelay: Long? = null
        var section: String? = null
        for (row in rows) {
            when (row) {
                is ContactPickerListRow.DateSection -> section = row.dayCode
                is ContactPickerListRow.ContactRow -> {
                    if (section == ContactPickerListRow.DateSection.SECTION_TODAY) {
                        val d = nextGroupedTodayLabelRefreshDelayMillis(row.callTimestamp)
                        minDelay = if (minDelay == null) d else minOf(minDelay!!, d)
                    }
                }
            }
        }
        if (minDelay == null) return
        groupedTodayRefreshHandler.postDelayed(groupedTodayRefreshRunnable, minDelay)
    }

    fun pauseGroupedTodayTimeRefresh() {
        groupedTodayRefreshHandler.removeCallbacks(groupedTodayRefreshRunnable)
    }

    private fun refreshTodaySectionCallLogRows() {
        var section: String? = null
        rows.forEachIndexed { index, row ->
            when (row) {
                is ContactPickerListRow.DateSection -> section = row.dayCode
                is ContactPickerListRow.ContactRow -> {
                    if (section == ContactPickerListRow.DateSection.SECTION_TODAY) {
                        notifyItemChanged(index)
                    }
                }
            }
        }
    }

    fun setItems(newContacts: List<Contact>?) {
        setContactModeItems(newContacts, emptySet())
    }

    fun setItems(newContacts: List<Contact>?, newSelectedPositions: Set<Int>?) {
        setContactModeItems(newContacts, newSelectedPositions)
    }

    fun addItems(newContacts: List<Contact>, newSelectedPositions: Set<Int>?) {
        if (newContacts.isEmpty() || isCallLogMode) return
        val startIndex = contactLookup.size
        val merged = ArrayList<Contact>(contactLookup.size + newContacts.size)
        merged.addAll(contactLookup)
        merged.addAll(newContacts)
        contactLookup = merged
        newSelectedPositions?.forEach { pos ->
            selectedContactIndices.add(startIndex + pos)
        }
        notifyItemRangeInserted(startIndex, newContacts.size)
    }

    private fun sectionDayCodeForAdapterPosition(position: Int): String? {
        if (position !in rows.indices) return null
        for (i in position downTo 0) {
            when (val r = rows[i]) {
                is ContactPickerListRow.DateSection -> return r.dayCode
                else -> {}
            }
        }
        return null
    }

    private fun callTimeLabel(callTimestamp: Long, sectionDayCode: String?): CharSequence {
        val act = context as? android.app.Activity ?: return ""
        val text = formatGroupedSectionDateTime(act, callTimestamp, sectionDayCode)
        return normalizeGroupedListRelativeTextForKorean(act, text)
    }

    private inner class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: MyTextView = itemView.findViewById(R.id.contact_picker_section_title)

        fun bind(section: ContactPickerListRow.DateSection) {
            val act = context as? android.app.Activity ?: return
            title.setTextColor(act.getProperTextColor().adjustAlpha(0.7f))
            title.text = when (section.dayCode) {
                ContactPickerListRow.DateSection.SECTION_TODAY -> act.getString(R.string.today)
                ContactPickerListRow.DateSection.SECTION_YESTERDAY -> act.getString(R.string.yesterday)
                ContactPickerListRow.DateSection.SECTION_BEFORE -> act.getString(com.goodwy.commons.R.string.previous)
                else -> act.getString(com.goodwy.commons.R.string.previous)
            }
        }
    }

    private fun applyPickerSimBadge(badge: ImageView, simSlot: Int) {
        when (simSlot) {
            1 -> {
                badge.visibility = View.VISIBLE
                badge.setImageResource(com.android.common.R.drawable.ic_cmn_sim1)
            }
            2 -> {
                badge.visibility = View.VISIBLE
                badge.setImageResource(com.android.common.R.drawable.ic_cmn_sim2)
            }
            else -> {
                badge.visibility = View.GONE
                badge.setImageDrawable(null)
            }
        }
        if (badge.visibility == View.VISIBLE) {
//            badge.applyColorFilter(simIconColor)
            val subId = context.subscriptionIdForOneBasedSimSlot(simSlot)
            val simIconColor = context.resolveSimIconTint(context.getProperTextColor(), subId, simSlot)
            badge.imageTintList = ColorStateList.valueOf(simIconColor)
        } else {
            badge.imageTintList = null
        }
    }

    private fun bindPickerAvatar(avatarView: ContactAvatarView, contact: Contact) {
        val act = context as? BaseSimpleActivity ?: return
        avatarView.bindContactPickerAvatar(act, contact)
    }

    private fun applyRecentsDivider(divider: ImageView) {
        val act = context as? android.app.Activity ?: return
        divider.visibility = View.VISIBLE
        divider.setBackgroundColor(act.getProperTextColor())
    }

    private fun highlightedText(text: String, ignoreCharsBetweenDigits: Boolean = false): CharSequence {
        if (textToHighlight.isEmpty()) return text
        val act = context as? android.app.Activity ?: return text
        return text.highlightTextPart(
            textToHighlight,
            act.getProperPrimaryColor(),
            highlightAll = true,
            ignoreCharsBetweenDigits = ignoreCharsBetweenDigits,
        )
    }

    /** Contacts tab row: [R.layout.item_contact_picker]. */
    private inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private         val nameTextView: TextView = itemView.findViewById(R.id.tv_contact_name)
        private val phoneTextView: TextView = itemView.findViewById(R.id.tv_contact_phone)
        private val contactImage: ContactAvatarView = itemView.findViewById(R.id.contactImage)
        private val divider: ImageView = itemView.findViewById(R.id.divider)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_contact_select)

        private val contactSimBadge: ImageView = itemView.findViewById(R.id.contactSimBadge)

        fun bind(contactIndex: Int) {
            val contact = contactLookup.getOrNull(contactIndex) ?: return
            val hasContactName = contact.name.isNotEmpty() && contact.name != contact.phoneNumber

            if (hasContactName) {
                nameTextView.text = highlightedText(contact.name)
                if (contact.phoneNumber.isNotEmpty()) {
                    phoneTextView.text = highlightedText(contact.phoneNumber, ignoreCharsBetweenDigits = true)
                    phoneTextView.visibility = View.VISIBLE
                } else {
                    phoneTextView.visibility = View.GONE
                }
            } else {
                nameTextView.text = highlightedText(contact.phoneNumber, ignoreCharsBetweenDigits = true)
                phoneTextView.text = ""
                phoneTextView.visibility = View.GONE
            }
            checkBox.isChecked = selectedContactIndices.contains(contactIndex)

            contactImage.beVisibleIf(showContactThumbnails)
            if (showContactThumbnails) {
                bindPickerAvatar(contactImage, contact)
            }

            applyPickerSimBadge(contactSimBadge, contact.simSlot)

            applyRecentsDivider(divider)

            itemView.setOnClickListener {
                toggleRow(contactIndex)
            }
            checkBox.setOnClickListener {
                val checked = checkBox.isChecked
                if (checked) selectedContactIndices.add(contactIndex) else selectedContactIndices.remove(contactIndex)
                listener?.onContactToggled(contactIndex, checked)
            }
        }

        private fun toggleRow(contactIndex: Int) {
            val isChecked = !checkBox.isChecked
            checkBox.isChecked = isChecked
            if (isChecked) selectedContactIndices.add(contactIndex) else selectedContactIndices.remove(contactIndex)
            listener?.onContactToggled(contactIndex, isChecked)
        }
    }

    /** Call log tab row: [R.layout.item_contact_picker_call_log] (txDial-style). */
    private inner class CallLogRowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarView: ContactAvatarView = itemView.findViewById(R.id.item_recents_image)
        private val nameTextView: TextView = itemView.findViewById(R.id.item_recents_name)
        private val callCountView: TextView = itemView.findViewById(R.id.item_recents_call_count)
        private val typeIcon: ImageView = itemView.findViewById(R.id.item_recents_type)
        private val numberTextView: TextView = itemView.findViewById(R.id.item_recents_number)
        private val dateTimeView: TextView = itemView.findViewById(R.id.item_recents_date_time)
        private val divider: ImageView = itemView.findViewById(R.id.divider)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_contact_select)

        private val avatarSimBadge: ImageView = itemView.findViewById(R.id.item_recents_avatar_sim_badge)

        fun bind(row: ContactPickerListRow.ContactRow, adapterPosition: Int) {
            val contact = contactLookup.getOrNull(row.contactIndex) ?: return
            val act = context as? android.app.Activity ?: return
            if (row.groupedCallCount > 1) {
                callCountView.text = "(${row.groupedCallCount})"
                callCountView.alpha = 0.7f
                callCountView.setTextColor(act.getProperTextColor())
                callCountView.visibility = View.VISIBLE
            } else {
                callCountView.text = ""
                callCountView.visibility = View.GONE
            }
            dateTimeView.visibility = View.VISIBLE
            typeIcon.visibility = View.VISIBLE

            val hasContactName = contact.name.isNotEmpty() && contact.name != contact.phoneNumber
            val missedColor = ContextCompat.getColor(context, com.goodwy.commons.R.color.red_missed)
            val accentColor = act.getProperAccentColor()

            if (hasContactName) {
                nameTextView.text = highlightedText(contact.name)
                nameTextView.setTextColor(
                    if (row.callType == Calls.MISSED_TYPE) missedColor
                    else ContextCompat.getColor(context, com.android.common.R.color.tx_content_text),
                )
                if (contact.phoneNumber.isNotEmpty()) {
                    numberTextView.text = highlightedText(contact.phoneNumber, ignoreCharsBetweenDigits = true)
                    numberTextView.visibility = View.VISIBLE
                } else {
                    numberTextView.visibility = View.GONE
                }
            } else {
                nameTextView.text = highlightedText(contact.phoneNumber, ignoreCharsBetweenDigits = true)
                nameTextView.setTextColor(
                    if (row.callType == Calls.MISSED_TYPE) missedColor
                    else ContextCompat.getColor(context, com.android.common.R.color.tx_content_text),
                )
                numberTextView.text = ""
                numberTextView.visibility = View.GONE
            }

            val sectionCode = sectionDayCodeForAdapterPosition(adapterPosition)
            dateTimeView.text = callTimeLabel(row.callTimestamp, sectionCode)
            dateTimeView.setTextColor(ContextCompat.getColor(context, com.android.common.R.color.tx_sub_text))

            val drawableRes = when (row.callType) {
                Calls.OUTGOING_TYPE -> com.goodwy.commons.R.drawable.ic_cmn_out
                Calls.MISSED_TYPE -> com.goodwy.commons.R.drawable.ic_cmn_miss
                else -> com.goodwy.commons.R.drawable.ic_cmn_in
            }
            val iconTint = if (row.callType == Calls.MISSED_TYPE) missedColor else accentColor
            typeIcon.setImageDrawable(
                context.resources.getColoredDrawableWithColor(drawableRes, iconTint),
            )

            checkBox.isChecked = selectedContactIndices.contains(row.contactIndex)

            avatarView.beVisibleIf(showContactThumbnails)
            if (showContactThumbnails) {
                bindPickerAvatar(avatarView, contact)
            }
            applyPickerSimBadge(avatarSimBadge, contact.simSlot)
            applyRecentsDivider(divider)

            itemView.setOnClickListener {
                val isChecked = !checkBox.isChecked
                checkBox.isChecked = isChecked
                if (isChecked) selectedContactIndices.add(row.contactIndex) else selectedContactIndices.remove(row.contactIndex)
                listener?.onContactToggled(row.contactIndex, isChecked)
            }
            checkBox.setOnClickListener {
                val checked = checkBox.isChecked
                if (checked) selectedContactIndices.add(row.contactIndex) else selectedContactIndices.remove(row.contactIndex)
                listener?.onContactToggled(row.contactIndex, checked)
            }
        }
    }

    interface ContactPickerAdapterListener {
        fun onContactToggled(contactIndex: Int, isSelected: Boolean)
    }

    companion object {
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_CONTACT = 1
        private const val VIEW_TYPE_CALL_LOG = 2
    }
}
