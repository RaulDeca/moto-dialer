package com.motocallrecorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CallRecorderAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CallAccessibilitySvc"
        private const val VOIP_END_DELAY_MS = 5000L
        var isServiceRunning = false
        var isVoipCallActive = false
        private var activeVoipPackage = ""

        private val VOIP_PACKAGES = listOf(
            "com.whatsapp",
            "com.facebook.orca",
            "com.google.android.apps.meetings",
            "com.skype.raider",
            "org.telegram.messenger",
            "com.skype.m2",
            "com.discord"
        )
    }

    private val endHandler = Handler(Looper.getMainLooper())
    private val endRunnable = Runnable { stopVoip() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        activeVoipPackage = ""
        Log.d(TAG, "Connected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 1000
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            if (!Prefs(this).isEnabled || !Prefs(this).recordVoip) return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val packageName = event.packageName?.toString() ?: return

            if (!isVoipCallActive) {
                if (packageName in VOIP_PACKAGES) {
                    isVoipCallActive = true
                    activeVoipPackage = packageName
                    Log.d(TAG, "VoIP started: $packageName")
                    val appName = try {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                    } catch (_: Exception) { packageName }
                    val intent = Intent(this, RecordService::class.java).apply {
                        action = RecordService.ACTION_START_RECORDING
                        putExtra(RecordService.EXTRA_PHONE_NUMBER, "")
                        putExtra(RecordService.EXTRA_CONTACT_NAME, appName)
                        putExtra(RecordService.EXTRA_IS_INCOMING, true)
                        putExtra(RecordService.EXTRA_IS_VOIP, true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                    else startService(intent)
                }
            } else {
                if (packageName == activeVoipPackage || packageName == "android") {
                    endHandler.removeCallbacks(endRunnable)
                } else {
                    if (!endHandler.hasCallbacks(endRunnable)) {
                        Log.d(TAG, "VoIP away from $activeVoipPackage, scheduling end in ${VOIP_END_DELAY_MS}ms")
                        endHandler.postDelayed(endRunnable, VOIP_END_DELAY_MS)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
        }
    }

    override fun onInterrupt() { stopVoip() }

    override fun onDestroy() {
        isServiceRunning = false
        stopVoip()
        endHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun stopVoip() {
        endHandler.removeCallbacks(endRunnable)
        if (!isVoipCallActive) return
        isVoipCallActive = false
        activeVoipPackage = ""
        Log.d(TAG, "VoIP ended")
        try { startService(Intent(this, RecordService::class.java).apply { action = RecordService.ACTION_STOP_RECORDING }) }
        catch (_: Exception) {}
    }
}
