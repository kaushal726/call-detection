package com.example.myapplication

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit
import kotlin.math.log

class CallReceiver : BroadcastReceiver() {

    companion object {

        private const val PREFS_NAME = "CallReceiverPrefs"
        private const val KEY_LAST_CALL_ID = "last_call_log_id"

        private var lastState = TelephonyManager.CALL_STATE_IDLE

        private fun generateCallSid(): String {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 32)
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        var state = TelephonyManager.CALL_STATE_IDLE

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_IDLE -> state = TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.EXTRA_STATE_OFFHOOK -> state = TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_RINGING -> state = TelephonyManager.CALL_STATE_RINGING
        }

        onCallStateChanged(context, state)
    }

    private fun onCallStateChanged(context: Context, state: Int) {
        if (lastState == state) return

        // When call ends (transitions to IDLE), query the CallLog
        if (state == TelephonyManager.CALL_STATE_IDLE &&
            (lastState == TelephonyManager.CALL_STATE_RINGING ||
                    lastState == TelephonyManager.CALL_STATE_OFFHOOK)) {

            Log.d("CallReceiver", "Call ended, querying CallLog")

            // Use a short delay to ensure CallLog is updated
            Handler(Looper.getMainLooper()).postDelayed({
                queryAndSendLatestCall(context)
            }, 1000) // 1 second delay
        }

        lastState = state
    }

    private fun queryAndSendLatestCall(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // First time initialization: get the latest call ID to start from
                var lastProcessedId = prefs.getLong(KEY_LAST_CALL_ID, -1)
                if (lastProcessedId == -1L) {
                    lastProcessedId = getLatestCallId(context)
                    prefs.edit().putLong(KEY_LAST_CALL_ID, lastProcessedId).apply()
                    Log.d("CallReceiver", "First run: initialized with ID $lastProcessedId")
                    return@launch  // Don't process old calls
                }

                Log.i("CallReceiver", "Querying CallLog from ID $lastProcessedId")
                val allNewCalls = getAllNewIncomingCalls(context, lastProcessedId)

                if (allNewCalls.isNotEmpty()) {
                    Log.d("CallReceiver", "Found ${allNewCalls.size} new call(s)")

                    var maxId = lastProcessedId

                    // Process all new calls
                    allNewCalls.forEach { callData ->
                        Log.d("CallReceiver", "Processing: ${callData.number}, type: ${callData.type}, duration: ${callData.duration}s")

                        sendCallDataToAPI(
                            phoneNumber = callData.number,
                            callType = callData.callType,
                            dialCallStatus = callData.status,
                            durationSeconds = callData.duration
                        )

                        if (callData.id > maxId) {
                            maxId = callData.id
                        }
                    }

                    // Save the highest ID processed
                    prefs.edit().putLong(KEY_LAST_CALL_ID, maxId).apply()
                } else {
                    Log.d("CallReceiver", "No new calls to process")
                }
            } catch (e: Exception) {
                Log.e("CallReceiver", "Error querying CallLog: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("Range")
    private fun getLatestCallId(context: Context): Long {
        val projection = arrayOf(CallLog.Calls._ID)
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndex(CallLog.Calls._ID))
            }
        }
        return 0L
    }

    private fun getAllNewIncomingCalls(context: Context, lastProcessedId: Long): List<CallData> {
        val callList = mutableListOf<CallData>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE
        )

        val selection =
            "${CallLog.Calls.TYPE} IN (?, ?, ?) AND ${CallLog.Calls._ID} > ?"

        val selectionArgs = arrayOf(
            CallLog.Calls.INCOMING_TYPE.toString(),
            CallLog.Calls.MISSED_TYPE.toString(),
            CallLog.Calls.REJECTED_TYPE.toString(),
            lastProcessedId.toString()
        )

        val sortOrder = "${CallLog.Calls.DATE} DESC"

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)

                val id = cursor.getLong(idIndex)
                val number = cursor.getString(numberIndex) ?: "Unknown"
                val type = cursor.getInt(typeIndex)
                val duration = cursor.getInt(durationIndex)
                val date = cursor.getLong(dateIndex)

                // Determine call type and status
                val (callType, status) = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> {
                        if (duration > 0) {
                            "completed" to "completed"
                        } else {
                            "incomplete" to "no-answer"
                        }
                    }
                    CallLog.Calls.MISSED_TYPE -> "incomplete" to "no-answer"
                    CallLog.Calls.REJECTED_TYPE -> "incomplete" to "no-answer"
                    else -> continue
                }

                callList.add(CallData(id, number, type, duration, date, callType, status))
            }
        }

        return callList.sortedBy { it.date }
    }

    private fun sendCallDataToAPI(
        phoneNumber: String,
        callType: String,
        dialCallStatus: String,
        durationSeconds: Int
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH)
                val createdAt = dateFormat.format(Date())
                val callSid = "app_" + generateCallSid()

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

                Log.d("CallReceiver", "API BODY: $jsonBody")

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
                Log.d("CallReceiver", "API Response: $responseCode for $callType | $phoneNumber | ${durationSeconds}s")

                connection.disconnect()

            } catch (e: Exception) {
                Log.e("CallReceiver", "API ERROR: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    data class CallData(
        val id: Long,
        val number: String,
        val type: Int,
        val duration: Int,
        val date: Long,
        val callType: String,
        val status: String
    )
}


