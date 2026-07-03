package com.motocallrecorder

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String,
    val type: Int,
    val date: Long,
    val duration: Long,
    val formattedDate: String,
    val formattedDuration: String
)

object CallLogHelper {
    private const val TAG = "CallLogHelper"

    fun getCallLog(context: Context, filterType: Int = -1): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val selection = if (filterType >= 0) "${CallLog.Calls.TYPE} = ?" else null
            val selectionArgs = if (filterType >= 0) arrayOf(filterType.toString()) else null
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )
            if (cursor == null) {
                Log.w(TAG, "Call log cursor is null — READ_CALL_LOG may be denied")
                return list
            }
            cursor.use {
                val dateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                var count = 0
                while (it.moveToNext() && count < 500) {
                    val id = it.getLong(0)
                    var number = it.getString(1) ?: ""
                    val name = it.getString(2) ?: ""
                    val type = it.getInt(3)
                    val date = it.getLong(4)
                    val duration = it.getLong(5)

                    if (number.isEmpty()) number = "Unknown"

                    val durStr = when {
                        duration < 60 -> "${duration}s"
                        duration < 3600 -> "${duration / 60}m ${duration % 60}s"
                        else -> "${duration / 3600}h ${(duration % 3600) / 60}m"
                    }

                    list.add(CallLogEntry(
                        id, number, name, type, date, duration,
                        dateFormat.format(Date(date)), durStr
                    ))
                    count++
                }
                Log.d(TAG, "Loaded $count call log entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call log: ${e.message}", e)
        }
        return list
    }
}
