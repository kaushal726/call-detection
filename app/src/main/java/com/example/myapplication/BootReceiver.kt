package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted, scheduling service start")

            context?.let {
                // Use WorkManager to start service after boot
                val workRequest = OneTimeWorkRequestBuilder<ServiceStartWorker>()
                    .setInitialDelay(10, TimeUnit.SECONDS)  // Wait 10 seconds after boot
                    .build()

                WorkManager.getInstance(it).enqueue(workRequest)
            }
        }
    }
}