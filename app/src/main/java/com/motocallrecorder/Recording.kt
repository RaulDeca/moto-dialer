package com.motocallrecorder

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class Recording(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val duration: Long,
    val timestamp: Long,
    val isIncoming: Boolean = true,
    val contactName: String = "Unknown",
    val phoneNumber: String = ""
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

    val formattedDuration: String
        get() {
            val sec = duration / 1000
            return String.format("%02d:%02d", sec / 60, sec % 60)
        }

    val file: File get() = File(filePath)

    val fileSize: String
        get() {
            val bytes = file.length()
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
}
