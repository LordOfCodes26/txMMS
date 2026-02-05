package com.android.mms.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
        holder.nameTextView.text = contact.name
        holder.checkBox.isChecked = selectedPositions.contains(position)

        if (contact.icon != -1) {
            holder.avatarImageView.setImageResource(contact.icon)
            holder.avatarImageView.visibility = View.VISIBLE
            holder.initialTextView.visibility = View.GONE
            holder.avatarBackgroundView.visibility = View.GONE
        } else {
            holder.avatarImageView.visibility = View.GONE
            holder.initialTextView.visibility = View.VISIBLE
            holder.avatarBackgroundView.visibility = View.VISIBLE

            if (contact.name.isNotEmpty()) {
                val initial = contact.name.uppercase().first().toString()
                holder.initialTextView.text = initial
                val colors = getGradientColorsForInitial(initial)
                val gradientDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    colors
                )
                gradientDrawable.shape = GradientDrawable.OVAL
                holder.avatarBackgroundView.background = gradientDrawable
            }
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

    interface ContactPickerAdapterListener {
        fun onContactToggled(position: Int, isSelected: Boolean)
    }

    private fun getGradientColorsForInitial(initial: String): IntArray {
        val firstChar = initial.firstOrNull() ?: return intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt())
        val index = when {
            firstChar in 'A'..'Z' -> firstChar - 'A'
            firstChar in '0'..'9' -> return intArrayOf(0xFF8E8E93.toInt(), 0xFFAEAEB2.toInt())
            else -> 0
        }
        val colorPairs = arrayOf(
            intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt()),
            intArrayOf(0xFF5856D6.toInt(), 0xFFAF52DE.toInt()),
            intArrayOf(0xFFFF2D55.toInt(), 0xFFFF3B30.toInt()),
            intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt()),
            intArrayOf(0xFF34C759.toInt(), 0xFF30D158.toInt()),
            intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt()),
            intArrayOf(0xFF5856D6.toInt(), 0xFFAF52DE.toInt()),
            intArrayOf(0xFFFF2D55.toInt(), 0xFFFF3B30.toInt()),
            intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt()),
            intArrayOf(0xFF34C759.toInt(), 0xFF30D158.toInt()),
            intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt()),
            intArrayOf(0xFF5856D6.toInt(), 0xFFAF52DE.toInt()),
            intArrayOf(0xFFFF2D55.toInt(), 0xFFFF3B30.toInt()),
            intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt()),
            intArrayOf(0xFF34C759.toInt(), 0xFF30D158.toInt()),
            intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt()),
            intArrayOf(0xFF5856D6.toInt(), 0xFFAF52DE.toInt()),
            intArrayOf(0xFFFF2D55.toInt(), 0xFFFF3B30.toInt()),
            intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt()),
            intArrayOf(0xFF34C759.toInt(), 0xFF30D158.toInt()),
            intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt()),
            intArrayOf(0xFF5856D6.toInt(), 0xFFAF52DE.toInt()),
            intArrayOf(0xFFFF2D55.toInt(), 0xFFFF3B30.toInt()),
            intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt()),
            intArrayOf(0xFF34C759.toInt(), 0xFF30D158.toInt()),
            intArrayOf(0xFF007AFF.toInt(), 0xFF5AC8FA.toInt())
        )
        return colorPairs[index % colorPairs.size]
    }

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.tv_contact_name)
        val initialTextView: TextView = itemView.findViewById(R.id.tv_contact_initial)
        val avatarImageView: ImageView = itemView.findViewById(R.id.iv_contact_avatar)
        val avatarBackgroundView: View = itemView.findViewById(R.id.v_avatar_background)
        val checkBox: CheckBox = itemView.findViewById(R.id.cb_contact_select)
    }
}
