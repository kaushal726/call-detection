package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class ServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            Log.d("ServiceStartWorker", "Starting CallMonitorService from boot")
            
            val serviceIntent = Intent(applicationContext, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("ServiceStartWorker", "Failed to start service: ${e.message}")
            Result.failure()
        }
    }
}