package com.example.myapplication

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

// Add to build.gradle
// implementation "androidx.work:work-runtime-ktx:2.9.0"

class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val KEY_PHONE = "phone_number"
        private const val KEY_TYPE = "call_type"
        private const val KEY_STATUS = "call_status"
        private const val KEY_DURATION = "duration"

        fun enqueue(
            context: Context,
            phoneNumber: String,
            callType: String,
            dialCallStatus: String,
            durationSeconds: Int
        ) {
            val data = workDataOf(
                KEY_PHONE to phoneNumber,
                KEY_TYPE to callType,
                KEY_STATUS to dialCallStatus,
                KEY_DURATION to durationSeconds
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val phoneNumber = inputData.getString(KEY_PHONE) ?: return Result.failure()
        val callType = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val dialCallStatus = inputData.getString(KEY_STATUS) ?: return Result.failure()
        val durationSeconds = inputData.getInt(KEY_DURATION, 0)

        return try {
            sendCallDataToAPI(phoneNumber, callType, dialCallStatus, durationSeconds)
            Log.d("CallSyncWorker", "Successfully synced call data")
            Result.success()
        } catch (e: Exception) {
            Log.e("CallSyncWorker", "Failed to sync: ${e.message}")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun sendCallDataToAPI(
        phoneNumber: String,
        callType: String,
        dialCallStatus: String,
        durationSeconds: Int
    ) = withContext(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH)
        val createdAt = dateFormat.format(Date())
        val callSid = "app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32)

        val jsonBody = JSONObject().apply {
            put("callSid", callSid)
            put("callFrom", phoneNumber)
            put("callType", callType)
            put("created", createdAt)
            put("dialCallDuration", durationSeconds)
            put("dialCallStatus", dialCallStatus)
            put("direction", "incoming")
            put("pubId", PUB_ID)
        }

        val url = URL(API_URL)
        val connection = url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true

        connection.outputStream.use { os ->
            os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        connection.disconnect()

        if (responseCode !in 200..299) {
            throw IOException("HTTP error: $responseCode")
        }
    }
}

// Update CallReceiver to use WorkManager
fun sendCallDataToAPINew(
    context: Context,
    phoneNumber: String,
    callType: String,
    dialCallStatus: String,
    durationSeconds: Int
) {
    CallSyncWorker.enqueue(
        context,
        phoneNumber,
        callType,
        dialCallStatus,
        durationSeconds
    )
}