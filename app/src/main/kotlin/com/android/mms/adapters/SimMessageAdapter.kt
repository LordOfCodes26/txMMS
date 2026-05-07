package com.android.mms.adapters

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.mms.databinding.ItemSimMessageBinding
import com.android.mms.models.SimMessage
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import java.util.Date

class SimMessageAdapter(
    private val context: Context,
    private val messages: List<SimMessage>,
    private val onLongClick: (SimMessage) -> Unit,
) : RecyclerView.Adapter<SimMessageAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSimMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class ViewHolder(private val binding: ItemSimMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: SimMessage) {
            val timeStr = formatTime(message.date)

            if (message.isIncoming) {
                binding.simMsgReceivedContainer.beVisible()
                binding.simMsgSentContainer.beGone()
                binding.simMsgAddress.text = message.address
                binding.simMsgBody.text = message.body
                binding.simMsgTime.text = timeStr
            } else {
                binding.simMsgSentContainer.beVisible()
                binding.simMsgReceivedContainer.beGone()
                binding.simMsgBodySent.text = message.body
                binding.simMsgTimeSent.text = timeStr
            }

            binding.root.setOnLongClickListener {
                onLongClick(message)
                true
            }
        }

        private fun formatTime(millis: Long): String {
            val date = Date(millis)
            val dateFormat = DateFormat.getDateFormat(context)
            val timeFormat = DateFormat.getTimeFormat(context)
            val now = System.currentTimeMillis()
            val diff = now - millis
            val oneDayMs = 24 * 60 * 60 * 1000L
            return if (diff < oneDayMs) {
                timeFormat.format(date)
            } else {
                "${dateFormat.format(date)} ${timeFormat.format(date)}"
            }
        }
    }
}
