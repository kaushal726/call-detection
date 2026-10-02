package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // QUICKBOOT_POWERON: what some Xiaomi/HTC phones send instead of BOOT_COMPLETED.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        Log.d("BootReceiver", "Device booted, starting CallMonitorService")

        // Started right here: Android 12+ lets a boot receiver start a foreground service, but
        // refuses the same start from a WorkManager job a few seconds later.
        try {
            ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
        } catch (e: Exception) {
            Log.e("BootReceiver", "Could not start CallMonitorService: ${e.message}")
        }
        CallSyncWorker.enqueue(context)
    }
}
