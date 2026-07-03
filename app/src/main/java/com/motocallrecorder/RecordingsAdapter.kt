package com.motocallrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class RecordingsAdapter(
    private var recordings: List<Recording>,
    private val onPlay: (Recording) -> Unit,
    private val onShare: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(recordings[position])
    }

    override fun getItemCount() = recordings.size

    fun updateData(newList: List<Recording>) {
        recordings = newList
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val contactName: TextView = itemView.findViewById(R.id.tvContactName)
        private val phoneNumber: TextView = itemView.findViewById(R.id.tvPhoneNumber)
        private val date: TextView = itemView.findViewById(R.id.tvDate)
        private val duration: TextView = itemView.findViewById(R.id.tvDuration)
        private val fileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val direction: TextView = itemView.findViewById(R.id.tvDirection)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btnShare)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(recording: Recording) {
            contactName.text = recording.contactName
            phoneNumber.text = if (recording.phoneNumber.isNotEmpty())
                recording.phoneNumber else "Unknown number"
            date.text = recording.formattedDate
            duration.text = recording.formattedDuration
            fileSize.text = recording.fileSize
            direction.text = if (recording.isIncoming) "IN" else "OUT"

            btnPlay.setOnClickListener { onPlay(recording) }
            btnShare.setOnClickListener { onShare(recording) }
            btnDelete.setOnClickListener { onDelete(recording) }
        }
    }
}
