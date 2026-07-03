package com.motocallrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log

class CallStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var isCallActive = false
        private var incomingNumber = ""
        private var outgoingNumber = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (!Prefs(context).isEnabled) return

            when (intent.action) {
                Intent.ACTION_NEW_OUTGOING_CALL -> {
                    outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
                    Log.d(TAG, "Outgoing to: $outgoingNumber")
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            incomingNumber = number
                            lastState = TelephonyManager.CALL_STATE_RINGING
                            Log.d(TAG, "Incoming from: $number")
                        }
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            if (!isCallActive) {
                                isCallActive = true
                                var num = if (incomingNumber.isNotEmpty()) incomingNumber
                                    else outgoingNumber
                                if (num.isEmpty()) {
                                    num = queryLastCallNumber(context)
                                }
                                Log.d(TAG, "Call started with: $num")
                                startRecording(context.applicationContext, num)
                            }
                            lastState = TelephonyManager.CALL_STATE_OFFHOOK
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            if (isCallActive) {
                                isCallActive = false
                                Log.d(TAG, "Call ended")
                                stopRecording(context.applicationContext)
                            }
                            incomingNumber = ""
                            outgoingNumber = ""
                            lastState = TelephonyManager.CALL_STATE_IDLE
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
        }
    }

    private fun queryLastCallNumber(context: Context): String {
        return try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.NUMBER)
            val selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE} AND ${CallLog.Calls.NEW} = 1"
            var number = ""
            context.contentResolver.query(uri, projection, selection, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    number = cursor.getString(0) ?: ""
                }
            }
            number
        } catch (_: Exception) { "" }
    }

    private fun startRecording(context: Context, number: String) {
        try {
            val contactName = try {
                ContactHelper.getContactName(context, number)
            } catch (_: Exception) { "" }
            val finalName = if (contactName.isNotEmpty()) contactName else number
            val isIncoming = incomingNumber.isNotEmpty()

            Log.d(TAG, "Starting recording - number: $number, name: $finalName, incoming: $isIncoming")

            val intent = Intent(context, RecordService::class.java).apply {
                action = RecordService.ACTION_START_RECORDING
                putExtra(RecordService.EXTRA_PHONE_NUMBER, number)
                putExtra(RecordService.EXTRA_CONTACT_NAME, finalName)
                putExtra(RecordService.EXTRA_IS_INCOMING, isIncoming)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}", e)
        }
    }

    private fun stopRecording(context: Context) {
        try {
            context.startService(Intent(context, RecordService::class.java).apply {
                action = RecordService.ACTION_STOP_RECORDING
            })
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed: ${e.message}", e)
        }
    }
}
