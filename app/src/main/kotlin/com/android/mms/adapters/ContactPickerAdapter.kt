package com.android.mms.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.android.mms.R
import com.android.mms.models.Contact

class ContactPickerAdapter(
    private val context: android.content.Context
) : RecyclerView.Adapter<ContactPickerAdapter.ContactViewHolder>() {

    private var contacts = mutableListOf<Contact>()
    private val selectedPositions = mutableSetOf<Int>()
    private var listener: ContactPickerAdapterListener? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact_picker, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        val hasContactName = contact.name.isNotEmpty() && contact.name != contact.phoneNumber

        if (hasContactName) {
            holder.nameTextView.text = contact.name
            if (contact.phoneNumber.isNotEmpty()) {
                holder.phoneTextView.text = contact.phoneNumber
                holder.phoneTextView.visibility = View.VISIBLE
            } else {
                holder.phoneTextView.visibility = View.GONE
            }
        } else {
            holder.nameTextView.text = contact.phoneNumber
            holder.phoneTextView.text = ""
            holder.phoneTextView.visibility = View.GONE
        }
        holder.checkBox.isChecked = selectedPositions.contains(position)

        // Same drawing logic as conversationImage in BaseConversationsAdapter: use loadContactImage
        // so placeholder (letter in circle) and circleCrop match the conversation list
        val displayName = contact.name.ifEmpty { contact.phoneNumber }
        if (contact.icon != -1) {
            holder.contactImage.setImageResource(contact.icon)
        } else {
            SimpleContactsHelper(context).loadContactImage(
                path = "",
                imageView = holder.contactImage,
                placeholderName = displayName,
                placeholderImage = null
            )
        }

        holder.itemView.setOnClickListener {
            val isChecked = !holder.checkBox.isChecked
            holder.checkBox.isChecked = isChecked
            if (isChecked) selectedPositions.add(position) else selectedPositions.remove(position)
            listener?.onContactToggled(position, isChecked)
        }

        holder.checkBox.setOnClickListener {
            val isChecked = holder.checkBox.isChecked
            if (isChecked) selectedPositions.add(position) else selectedPositions.remove(position)
            listener?.onContactToggled(position, isChecked)
        }
    }

    override fun getItemCount(): Int = contacts.size

    fun setItems(newContacts: List<Contact>?) {
        contacts = (newContacts ?: emptyList()).toMutableList()
        notifyDataSetChanged()
    }

    fun setItems(newContacts: List<Contact>?, newSelectedPositions: Set<Int>?) {
        contacts = (newContacts ?: emptyList()).toMutableList()
        selectedPositions.clear()
        selectedPositions.addAll(newSelectedPositions ?: emptySet())
        notifyDataSetChanged()
    }

    fun addItems(newContacts: List<Contact>, newSelectedPositions: Set<Int>?) {
        if (newContacts.isEmpty()) return
        val startPosition = contacts.size
        contacts.addAll(newContacts)
        newSelectedPositions?.forEach { pos ->
            selectedPositions.add(startPosition + pos)
        }
        notifyItemRangeInserted(startPosition, newContacts.size)
    }

    fun setListener(l: ContactPickerAdapterListener?) {
        listener = l
    }

    override fun onViewRecycled(holder: ContactViewHolder) {
        super.onViewRecycled(holder)
        if (context is android.app.Activity && !(context as android.app.Activity).isDestroyed && !(context as android.app.Activity).isFinishing) {
            Glide.with(context).clear(holder.contactImage)
        }
    }

    interface ContactPickerAdapterListener {
        fun onContactToggled(position: Int, isSelected: Boolean)
    }

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.tv_contact_name)
        val phoneTextView: TextView = itemView.findViewById(R.id.tv_contact_phone)
        val contactImage: ImageView = itemView.findViewById(R.id.contactImage)
        val checkBox: CheckBox = itemView.findViewById(R.id.cb_contact_select)
    }
}
