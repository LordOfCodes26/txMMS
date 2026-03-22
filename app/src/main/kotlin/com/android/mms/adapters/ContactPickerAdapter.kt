package com.android.mms.adapters

import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.getAvatarDrawableIndexForName
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperAccentColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.MonogramGenerator
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.android.mms.R
import com.android.mms.models.Contact
import com.android.mms.models.ContactPickerListRow
import com.goodwy.commons.views.ContactAvatarView
import com.goodwy.commons.views.MyTextView
import android.provider.CallLog.Calls
import java.util.Locale
import kotlin.math.abs

class ContactPickerAdapter(
    private val context: Context,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<ContactPickerListRow> = emptyList()
    private var contactLookup: List<Contact> = emptyList()
    private val selectedContactIndices = mutableSetOf<Int>()
    private var listener: ContactPickerAdapterListener? = null
    private var isCallLogMode = false

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ContactPickerListRow.DateSection -> VIEW_TYPE_SECTION
        is ContactPickerListRow.ContactRow ->
            if (isCallLogMode) VIEW_TYPE_CALL_LOG else VIEW_TYPE_CONTACT
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
        when (val row = rows[position]) {
            is ContactPickerListRow.DateSection -> (holder as SectionViewHolder).bind(row)
            is ContactPickerListRow.ContactRow -> when (holder) {
                is ContactViewHolder -> holder.bind(row)
                is CallLogRowViewHolder -> holder.bind(row, position)
                else -> {}
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    fun setListener(l: ContactPickerAdapterListener?) {
        listener = l
    }

    /** Contacts tab: one row per contact, indices match [contacts] list. */
    fun setContactModeItems(contacts: List<Contact>?, selectedIndices: Set<Int>?) {
        isCallLogMode = false
        contactLookup = contacts ?: emptyList()
        selectedContactIndices.clear()
        selectedContactIndices.addAll(selectedIndices ?: emptySet())
        rows = contactLookup.indices.map { i ->
            ContactPickerListRow.ContactRow(
                contactIndex = i,
                callType = Calls.INCOMING_TYPE,
                callTimestamp = 0L,
            )
        }
        notifyDataSetChanged()
    }

    /** Call log tab: sections + rows; [contactLookup] is full [allContacts] for index resolution. */
    fun setCallLogModeItems(
        listRows: List<ContactPickerListRow>,
        contactSource: List<Contact>,
        selectedIndices: Set<Int>?,
    ) {
        isCallLogMode = true
        contactLookup = contactSource
        selectedContactIndices.clear()
        selectedContactIndices.addAll(selectedIndices ?: emptySet())
        rows = listRows
        notifyDataSetChanged()
    }

    fun setItems(newContacts: List<Contact>?) {
        setContactModeItems(newContacts, emptySet())
    }

    fun setItems(newContacts: List<Contact>?, newSelectedPositions: Set<Int>?) {
        setContactModeItems(newContacts, newSelectedPositions)
    }

    fun addItems(newContacts: List<Contact>, newSelectedPositions: Set<Int>?) {
        if (newContacts.isEmpty()) return
        val startIndex = contactLookup.size
        contactLookup = contactLookup + newContacts
        val newRows = newContacts.indices.map { i ->
            ContactPickerListRow.ContactRow(
                contactIndex = startIndex + i,
                callType = Calls.INCOMING_TYPE,
                callTimestamp = 0L,
            )
        }
        rows = rows + newRows
        newSelectedPositions?.forEach { pos ->
            selectedContactIndices.add(startIndex + pos)
        }
        notifyItemRangeInserted(rows.size - newRows.size, newRows.size)
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
        return when (sectionDayCode) {
            ContactPickerListRow.DateSection.SECTION_TODAY -> {
                val now = System.currentTimeMillis()
                if (abs(now - callTimestamp) < DateUtils.MINUTE_IN_MILLIS) {
                    val lang = act.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
                    if (lang == Locale.KOREAN.language) "지금" else "now"
                } else {
                    DateUtils.getRelativeTimeSpanString(
                        callTimestamp,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    )
                }
            }
            ContactPickerListRow.DateSection.SECTION_YESTERDAY -> act.getString(R.string.yesterday)
            ContactPickerListRow.DateSection.SECTION_BEFORE -> callTimestamp.formatDateOrTime(
                context = act,
                hideTimeOnOtherDays = true,
                showCurrentYear = false,
            )
            else -> callTimestamp.formatDateOrTime(
                context = act,
                hideTimeOnOtherDays = true,
                showCurrentYear = false,
            )
        }
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

    private fun bindPickerAvatar(avatarView: ContactAvatarView, contact: Contact) {
        val act = context as? android.app.Activity ?: return
        val hasContactName = contact.name.isNotEmpty() && contact.name != contact.phoneNumber
        if (contact.icon != -1) {
            avatarView.bind(
                AvatarSource.Drawable(
                    drawableResId = contact.icon,
                    tintColor = Color.WHITE,
                    backgroundColor = act.getProperPrimaryColor(),
                    backgroundDrawableIndex = null,
                ),
                previewMode = true,
            )
            return
        }
        // Call log / dialer recents: unknown numbers use profile icon on gradient, not a digit letter (matches txDial RecentCallsAdapter).
        if (!hasContactName) {
            avatarView.bind(
                AvatarSource.Monogram(
                    initials = "",
                    gradientColors = MonogramGenerator.generateGradientColors(contact.phoneNumber),
                    drawableIndex = context.getAvatarDrawableIndexForName(contact.phoneNumber).takeIf { it >= 0 },
                    showProfileIcon = true,
                ),
                previewMode = true,
            )
            return
        }
        val displayName = contact.name
        val seed = displayName.ifBlank { contact.phoneNumber }
        val initials = MonogramGenerator.generateInitials(displayName.ifEmpty { contact.phoneNumber })
        val gradientColors = MonogramGenerator.generateGradientColors(seed)
        val drawableIndex = context.getAvatarDrawableIndexForName(seed).takeIf { it >= 0 }
        avatarView.bind(
            AvatarSource.Monogram(
                initials = initials,
                gradientColors = gradientColors,
                drawableIndex = drawableIndex,
            ),
            previewMode = true,
        )
    }

    private fun applyRecentsDivider(divider: ImageView) {
        val act = context as? android.app.Activity ?: return
        divider.visibility = View.VISIBLE
        divider.setBackgroundColor(act.getProperTextColor())
    }

    /** Contacts tab row: [R.layout.item_contact_picker]. */
    private inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.tv_contact_name)
        private val phoneTextView: TextView = itemView.findViewById(R.id.tv_contact_phone)
        val contactImage: ImageView = itemView.findViewById(R.id.contactImage)
        private val divider: ImageView = itemView.findViewById(R.id.divider)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_contact_select)

        fun bind(row: ContactPickerListRow.ContactRow) {
            val contact = contactLookup.getOrNull(row.contactIndex) ?: return
            val hasContactName = contact.name.isNotEmpty() && contact.name != contact.phoneNumber

            if (hasContactName) {
                nameTextView.text = contact.name
                if (contact.phoneNumber.isNotEmpty()) {
                    phoneTextView.text = contact.phoneNumber
                    phoneTextView.visibility = View.VISIBLE
                } else {
                    phoneTextView.visibility = View.GONE
                }
            } else {
                nameTextView.text = contact.phoneNumber
                phoneTextView.text = ""
                phoneTextView.visibility = View.GONE
            }
            checkBox.isChecked = selectedContactIndices.contains(row.contactIndex)

            val displayName = contact.name.ifEmpty { contact.phoneNumber }
            if (contact.icon != -1) {
                contactImage.setImageResource(contact.icon)
            } else {
                SimpleContactsHelper(context).loadContactImage(
                    path = "",
                    imageView = contactImage,
                    placeholderName = displayName,
                    placeholderImage = null,
                )
            }

            applyRecentsDivider(divider)

            itemView.setOnClickListener {
                toggleRow(row)
            }
            checkBox.setOnClickListener {
                val checked = checkBox.isChecked
                if (checked) selectedContactIndices.add(row.contactIndex) else selectedContactIndices.remove(row.contactIndex)
                listener?.onContactToggled(row.contactIndex, checked)
            }
        }

        private fun toggleRow(row: ContactPickerListRow.ContactRow) {
            val isChecked = !checkBox.isChecked
            checkBox.isChecked = isChecked
            if (isChecked) selectedContactIndices.add(row.contactIndex) else selectedContactIndices.remove(row.contactIndex)
            listener?.onContactToggled(row.contactIndex, isChecked)
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

        fun bind(row: ContactPickerListRow.ContactRow, adapterPosition: Int) {
            val contact = contactLookup.getOrNull(row.contactIndex) ?: return
            val act = context as? android.app.Activity ?: return
            if (row.groupedCallCount > 1) {
                callCountView.text = "(${row.groupedCallCount})"
                callCountView.alpha = 0.8f
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
                nameTextView.text = contact.name
                nameTextView.setTextColor(
                    if (row.callType == Calls.MISSED_TYPE) missedColor
                    else ContextCompat.getColor(context, com.android.common.R.color.tx_content_text),
                )
                if (contact.phoneNumber.isNotEmpty()) {
                    numberTextView.text = contact.phoneNumber
                    numberTextView.visibility = View.VISIBLE
                } else {
                    numberTextView.visibility = View.GONE
                }
            } else {
                nameTextView.text = contact.phoneNumber
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

            bindPickerAvatar(avatarView, contact)
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ContactViewHolder) {
            if (context is android.app.Activity) {
                val a = context as android.app.Activity
                if (!a.isDestroyed && !a.isFinishing) {
                    Glide.with(context).clear(holder.contactImage)
                }
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
