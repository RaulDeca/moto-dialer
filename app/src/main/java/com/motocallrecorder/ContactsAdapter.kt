package com.motocallrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactsAdapter(
    private var contacts: List<ContactEntry>,
    private val onVoiceCall: (String) -> Unit,
    private val onVideoCall: (String) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount() = contacts.size

    fun updateData(newList: List<ContactEntry>) {
        contacts = newList
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvCallName)
        private val tvNumber: TextView = itemView.findViewById(R.id.tvCallNumber)
        private val tvDate: TextView = itemView.findViewById(R.id.tvCallDate)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvCallDuration)
        private val tvType: TextView = itemView.findViewById(R.id.tvCallType)
        private val btnVoice: ImageButton = itemView.findViewById(R.id.btnVoiceCall)
        private val btnVideo: ImageButton = itemView.findViewById(R.id.btnVideoCall)

        fun bind(contact: ContactEntry) {
            tvName.text = contact.name
            tvNumber.text = contact.number
            tvDate.visibility = View.GONE
            tvDuration.visibility = View.GONE
            tvType.visibility = View.GONE

            itemView.setOnClickListener { onVoiceCall(contact.number) }
            btnVoice.setOnClickListener { onVoiceCall(contact.number) }
            btnVideo.setOnClickListener { onVideoCall(contact.number) }
        }
    }
}

data class ContactEntry(
    val name: String,
    val number: String
)
