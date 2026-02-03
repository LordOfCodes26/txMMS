package com.android.mms.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.SimpleContact

class ContactPickerAdapter(
    private val activity: SimpleActivity,
    private var items: ArrayList<ContactPhonePair>,
    private val selectedPositions: MutableSet<Int>,
    private val listener: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<ContactPickerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.item_contact_picker_image)
        val name: TextView = view.findViewById(R.id.item_contact_picker_name)
        val number: TextView = view.findViewById(R.id.item_contact_picker_number)
        val checkbox: CheckBox = view.findViewById(R.id.item_contact_picker_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pair = items[position]
        val contact = pair.contact
        val phoneNumber = pair.phoneNumber

        holder.name.text = contact.name
        holder.number.text = phoneNumber.value
        holder.number.beVisible()
        holder.checkbox.isChecked = selectedPositions.contains(position)

        if (contact.photoUri.isNotEmpty()) {
            SimpleContactsHelper(activity).loadContactImage(
                contact.photoUri,
                holder.image,
                contact.name
            )
        } else {
            holder.image.setImageResource(com.goodwy.commons.R.drawable.ic_person_vector)
            holder.image.applyColorFilter(activity.getProperTextColor())
        }

        holder.itemView.setOnClickListener {
            val isSelected = !selectedPositions.contains(position)
            toggleSelection(position, isSelected)
            listener(position, isSelected)
        }

        holder.checkbox.isClickable = false
        holder.checkbox.isFocusable = false
    }

    private fun toggleSelection(position: Int, isSelected: Boolean) {
        if (isSelected) {
            selectedPositions.add(position)
        } else {
            selectedPositions.remove(position)
        }
        notifyItemChanged(position)
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: ArrayList<ContactPhonePair>) {
        items = newItems
        notifyDataSetChanged()
    }
}
