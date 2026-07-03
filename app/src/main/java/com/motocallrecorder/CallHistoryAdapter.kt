package com.motocallrecorder

import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CallAction(
    val number: String,
    val isVideo: Boolean
)

class CallHistoryAdapter(
    private var entries: List<CallLogEntry>,
    private val onVoiceCall: (String) -> Unit,
    private val onVideoCall: (String) -> Unit
) : RecyclerView.Adapter<CallHistoryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size

    fun updateData(newList: List<CallLogEntry>) {
        entries = newList
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

        fun bind(entry: CallLogEntry) {
            tvName.text = if (entry.name.isNotEmpty()) entry.name else entry.number
            tvNumber.text = if (entry.name.isNotEmpty()) entry.number else ""

            tvDate.text = entry.formattedDate
            tvDuration.text = entry.formattedDuration

            tvType.text = when (entry.type) {
                CallLog.Calls.INCOMING_TYPE -> "IN"
                CallLog.Calls.OUTGOING_TYPE -> "OUT"
                CallLog.Calls.MISSED_TYPE -> "MISSED"
                else -> "?"
            }
            tvType.setTextColor(when (entry.type) {
                CallLog.Calls.INCOMING_TYPE -> 0xFF4CAF50.toInt()
                CallLog.Calls.OUTGOING_TYPE -> 0xFF2196F3.toInt()
                CallLog.Calls.MISSED_TYPE -> 0xFFE94560.toInt()
                else -> 0xFF8899AA.toInt()
            })

            itemView.setOnClickListener { onVoiceCall(entry.number) }
            btnVoice.setOnClickListener { onVoiceCall(entry.number) }
            btnVideo.setOnClickListener { onVideoCall(entry.number) }
        }
    }
}
