package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.CallLog
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends every incoming call logged since the last one the server accepted.
 *
 * Runs as a WorkManager job, so it survives the app being killed and the phone rebooting, waits
 * for internet, and retries with backoff. The "last sent" marker only moves after the server
 * answers 2xx, so a failed send is retried instead of lost. Each call's id is derived from its
 * call-log entry, so a resend of a call the server already has is dropped by the server's unique
 * index on call_sid: no duplicate row, no second WhatsApp to the caller.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CallSyncWorker"
        private const val WORK_NAME = "call-sync"

        // Same file and key the receiver used before, so installed phones keep their place.
        private const val PREFS_NAME = "CallReceiverPrefs"
        private const val KEY_LAST_CALL_ID = "last_call_log_id"
        private const val KEY_LAST_SENT_AT = "last_sent_at"

        /** Queues a sync. Safe to call often: a newer request replaces one still waiting. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS) // the call log is written just after the call ends
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Sends from here on: calls already in the log are history, not ours to send. Called once
         * call-log access is granted, so even the first call after setup goes out. No-op once set.
         */
        fun startFromNow(context: Context) {
            val prefs = prefs(context)
            if (prefs.getLong(KEY_LAST_CALL_ID, -1L) != -1L) return
            try {
                prefs.edit { putLong(KEY_LAST_CALL_ID, latestCallId(context)) }
            } catch (e: SecurityException) {
                Log.w(TAG, "No call log access yet: ${e.message}")
            }
        }

        private fun latestCallId(context: Context): Long {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                "${CallLog.Calls._ID} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
            return 0L
        }

        /** When the server last accepted a call, or null if it never has. Shown on the main screen. */
        fun lastSentAt(context: Context): Long? =
            prefs(context).getLong(KEY_LAST_SENT_AT, 0L).takeIf { it > 0L }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = prefs(applicationContext)
            val lastId = prefs.getLong(KEY_LAST_CALL_ID, -1L)
            if (lastId == -1L) {
                // Setup never reached the app screen (e.g. permissions granted from outside it).
                startFromNow(applicationContext)
                return@withContext Result.success()
            }
            for (call in newIncomingCalls(lastId)) {
                if (!send(call)) return@withContext Result.retry() // marker stays; resume here
                prefs.edit {
                    putLong(KEY_LAST_CALL_ID, call.id)
                    putLong(KEY_LAST_SENT_AT, System.currentTimeMillis())
                }
            }
            Result.success()
        } catch (e: SecurityException) {
            Log.e(TAG, "Call log permission missing: ${e.message}")
            Result.failure() // nothing to retry until the permission is granted again
        }
    }

    /** Incoming, missed and rejected calls after [lastId], oldest first (by id, so the marker never goes back). */
    @SuppressLint("Range")
    private fun newIncomingCalls(lastId: Long): List<CallData> {
        val calls = mutableListOf<CallData>()
        applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            ),
            "${CallLog.Calls.TYPE} IN (?, ?, ?) AND ${CallLog.Calls._ID} > ?",
            arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                CallLog.Calls.REJECTED_TYPE.toString(),
                lastId.toString()
            ),
            "${CallLog.Calls._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE))
                val duration = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.DURATION))
                // An incoming call with talk time was answered; everything else went unanswered.
                val answered = type == CallLog.Calls.INCOMING_TYPE && duration > 0
                calls.add(
                    CallData(
                        id = cursor.getLong(cursor.getColumnIndex(CallLog.Calls._ID)),
                        number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER))
                            ?: "Unknown",
                        duration = duration,
                        date = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE)),
                        callType = if (answered) "completed" else "incomplete",
                        status = if (answered) "completed" else "no-answer"
                    )
                )
            }
        }
        return calls
    }

    /** Posts one call. True when the server accepted it (2xx). */
    private fun send(call: CallData): Boolean {
        val callSid = "app_" + UUID.nameUUIDFromBytes("$PUB_ID:${call.id}:${call.date}".toByteArray())
            .toString().replace("-", "")
        val body = JSONObject().apply {
            put("callSid", callSid)
            put("callFrom", call.number)
            put("callType", call.callType)
            put("created", SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH).format(Date(call.date)))
            put("dialCallDuration", call.duration)
            put("dialCallStatus", call.status)
            put("direction", "incoming")
            put("pubId", PUB_ID)
        }
        return try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                Log.d(TAG, "Sent ${call.callType} call ${call.id}: HTTP $code")
                code in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send failed for call ${call.id}: ${e.message}")
            false
        }
    }

    private data class CallData(
        val id: Long,
        val number: String,
        val duration: Int,
        val date: Long,
        val callType: String,
        val status: String
    )
}
