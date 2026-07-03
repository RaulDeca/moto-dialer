package com.motocallrecorder

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactHelper {
    fun getContactName(context: Context, phoneNumber: String): String {
        if (phoneNumber.isEmpty()) return ""
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0) ?: ""
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }
}
