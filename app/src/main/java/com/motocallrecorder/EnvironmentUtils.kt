package com.motocallrecorder

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

object EnvironmentUtils {
    fun getContactDir(context: Context, contactName: String, @Suppress("UNUSED_PARAMETER") phoneNumber: String): File {
        val baseDir = getBaseDir(context)
        val safeName = contactName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val folderName = if (safeName.isNotBlank()) safeName else "Unknown"
        val dir = File(baseDir, folderName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getBaseDir(context: Context): File {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()) {
                File(File(Environment.getExternalStorageDirectory(), "Recorder"), "MotoCallRecorder")
                    .also { it.mkdirs() }
            } else {
                val recDir = File(Environment.getExternalStorageDirectory(), "Recorder")
                val dir = File(recDir, "MotoCallRecorder")
                if (dir.mkdirs() || dir.exists()) dir
                else File(context.getExternalFilesDir(null), "MotoCallRecorder")
                    .also { it.mkdirs() }
            }
        } catch (_: Exception) {
            File(context.getExternalFilesDir(null), "MotoCallRecorder")
                .also { it.mkdirs() }
        }
    }

    fun getAllRecordingFiles(context: Context): List<File> {
        val result = mutableListOf<File>()
        val baseDir = getBaseDir(context)
        if (!baseDir.exists()) return result

        val entries = baseDir.listFiles() ?: return result
        for (entry in entries) {
            if (entry.isDirectory) {
                val files = entry.listFiles { f ->
                    f.isFile && f.extension.lowercase() in listOf("3gp", "mp4", "m4a", "amr", "wav", "mp3")
                }
                if (files != null) result.addAll(files)
            } else if (entry.isFile && entry.extension.lowercase() in listOf("3gp", "mp4", "m4a", "amr", "wav", "mp3")) {
                result.add(entry)
            }
        }
        return result.sortedByDescending { it.lastModified() }
    }
}
