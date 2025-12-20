package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val PUB_ID = "818"
        private const val API_URL = "https://webhook.site/6d3546a0-f38d-4c64-9dab-94fbb778694b"

        private var lastState = TelephonyManager.CALL_STATE_IDLE

        private var incomingNumber: String? = null
        private var callWasPicked = false
        private var callStartTime: Long = 0L
        private var isOnCall = false

        // Track multiple waiting calls
        private val waitingCalls = mutableListOf<Pair<String, Long>>()

        // NEW: For multiple ringing detection
        private var multipleRingingStartTime: Long = 0L
        private var ringingCount = 0
        private val sentNumbers = mutableSetOf<String>() // Prevent duplicates in same session

        private fun generateCallSid(): String =
            UUID.randomUUID().toString().replace("-", "").substring(0, 32)
    }

    override fun onReceive(context: Context?, intent: Intent?) {

        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> TelephonyManager.CALL_STATE_IDLE
        }

        if (number != null) {
            onCallStateChanged(context, state, number)
        }
    }

    private fun onCallStateChanged(context: Context?, state: Int, number: String) {

        if (state == lastState) return

        when (state) {

            /* ---------------- RINGING ---------------- */
            TelephonyManager.CALL_STATE_RINGING -> {

                if (isOnCall && callWasPicked) {
                    // Already on a call - add to waiting list
                    val ringTime = System.currentTimeMillis()
                    waitingCalls.add(number to ringTime)
                    Log.d("CallReceiver", "WAITING CALL #${waitingCalls.size}: $number")
                } else {
                    // First incoming call OR multiple ringing

                    // NEW: Track multiple ringing
                    if (incomingNumber == null) {
                        // First ring
                        multipleRingingStartTime = System.currentTimeMillis()
                        ringingCount = 1
                        sentNumbers.clear()
                        Log.d("CallReceiver", "FIRST RING: $number at $multipleRingingStartTime")
                    } else {
                        // Multiple calls ringing
                        ringingCount++
                        Log.d("CallReceiver", "MULTIPLE RING #$ringingCount: $number (first was: $incomingNumber)")
                    }

                    incomingNumber = number
                    callWasPicked = false
                }
            }

            /* ---------------- OFFHOOK ---------------- */
            TelephonyManager.CALL_STATE_OFFHOOK -> {

                if (lastState == TelephonyManager.CALL_STATE_RINGING) {

                    if (waitingCalls.isNotEmpty()) {
                        // Picking up a waiting call
                        val switchTime = System.currentTimeMillis()
                        val (pickedNumber, _) = waitingCalls.removeAt(waitingCalls.lastIndex)

                        Log.d("CallReceiver", "WAITING CALL PICKED: $pickedNumber")

                        // End current active call
                        if (incomingNumber != null && callWasPicked && callStartTime > 0) {
                            val duration = ((switchTime - callStartTime) / 1000).toInt()
                            Log.d("CallReceiver", "PREVIOUS CALL ENDED: $incomingNumber | $duration sec")

                            sendCallDataToAPI(
                                phoneNumber = incomingNumber!!,
                                callType = "completed",
                                dialCallStatus = "completed",
                                durationSeconds = duration
                            )
                        }

                        // Switch to waiting call
                        incomingNumber = pickedNumber
                        callStartTime = switchTime
                        callWasPicked = true
                        isOnCall = true

                    } else {
                        // Normal first call pickup
                        callWasPicked = true
                        callStartTime = System.currentTimeMillis()
                        isOnCall = true

                        // NEW: Reset multiple ringing tracking since call was picked
                        multipleRingingStartTime = 0L
                        ringingCount = 0

                        Log.d("CallReceiver", "CALL PICKED: $incomingNumber")
                    }
                }
            }

            /* ---------------- IDLE ---------------- */
            TelephonyManager.CALL_STATE_IDLE -> {

                // Handle all waiting calls that were NOT picked
                if (waitingCalls.isNotEmpty()) {
                    Log.d("CallReceiver", "CLEARING ${waitingCalls.size} WAITING CALL(S)")

                    waitingCalls.forEach { (phone, _) ->
                        Log.d("CallReceiver", "WAITING CALL MISSED: $phone")
                        sendCallDataToAPI(
                            phoneNumber = phone,
                            callType = "incomplete",
                            dialCallStatus = "no-answer",
                            durationSeconds = 0
                        )
                    }
                    waitingCalls.clear()
                }

                // NEW: Check if multiple calls were ringing and none picked
                if (lastState == TelephonyManager.CALL_STATE_RINGING && !callWasPicked && ringingCount > 1) {
                    Log.d("CallReceiver", "MULTIPLE RINGING DETECTED ($ringingCount) - Checking call log")

                    val sessionStart = multipleRingingStartTime

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000) // Wait for call log
                        context?.let { checkMissedRingingCalls(it, sessionStart) }

                        // Reset
                        multipleRingingStartTime = 0L
                        ringingCount = 0
                        sentNumbers.clear()
                    }

                } else if (lastState == TelephonyManager.CALL_STATE_RINGING && !callWasPicked) {
                    // Single missed call - handle normally
                    Log.d("CallReceiver", "MISSED CALL: $incomingNumber")

                    incomingNumber?.let {
                        sendCallDataToAPI(
                            phoneNumber = it,
                            callType = "incomplete",
                            dialCallStatus = "no-answer",
                            durationSeconds = 0
                        )
                    }

                    // Reset
                    multipleRingingStartTime = 0L
                    ringingCount = 0
                }

                // Handle completed/ended call
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK && callWasPicked) {
                    val endTime = System.currentTimeMillis()
                    val duration = ((endTime - callStartTime) / 1000).toInt()

                    Log.d("CallReceiver", "CALL ENDED: $incomingNumber | $duration sec")

                    incomingNumber?.let {
                        sendCallDataToAPI(
                            phoneNumber = it,
                            callType = "completed",
                            dialCallStatus = "completed",
                            durationSeconds = duration
                        )
                    }
                }

                // Reset all state
                incomingNumber = null
                callWasPicked = false
                callStartTime = 0L
                isOnCall = false
            }
        }

        lastState = state
    }

    private fun checkMissedRingingCalls(context: Context, sessionStart: Long) {

        if (sessionStart == 0L) return

        val checkFrom = sessionStart - 2000L
        val checkUntil = System.currentTimeMillis()

        // EXTRA SAFETY: Don't check if session was too long ago
        val sessionAge = System.currentTimeMillis() - sessionStart
        if (sessionAge > 30000L) { // 30 seconds max
            Log.d("CallReceiver", "Session too old ($sessionAge ms), skipping")
            return
        }

        Log.d("CallReceiver", "Checking call log from $checkFrom to $checkUntil (window: ${checkUntil - checkFrom}ms)")

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE
            )

            val selection = """
            ${CallLog.Calls.DATE} >= ? AND 
            ${CallLog.Calls.DATE} <= ? AND 
            ${CallLog.Calls.TYPE} = ? AND
            ${CallLog.Calls.DURATION} = 0
        """.trimIndent()

            val selectionArgs = arrayOf(
                checkFrom.toString(),
                checkUntil.toString(),
                CallLog.Calls.INCOMING_TYPE.toString()
            )

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC LIMIT 10"  // EXTRA SAFETY: Max 10 results
            )

            var processedCount = 0

            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    if (number != null && sentNumbers.add(number)) {
                        Log.d("CallReceiver", "MISSED #${processedCount + 1}: $number at $date")

                        sendCallDataToAPI(
                            phoneNumber = number,
                            callType = "incomplete",
                            dialCallStatus = "no-answer",
                            durationSeconds = 0
                        )

                        processedCount++
                    }
                }
            }

            Log.d("CallReceiver", "✅ Sent $processedCount missed calls (window: ${checkUntil - checkFrom}ms)")

        } catch (e: Exception) {
            Log.e("CallReceiver", "❌ Call log error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun sendCallDataToAPI(
        phoneNumber: String,
        callType: String,
        dialCallStatus: String,
        durationSeconds: Int
    ) {

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("callSid", "app_${generateCallSid()}")
                    put("callFrom", phoneNumber)
                    put("callType", callType)
                    put(
                        "created",
                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH)
                            .format(Date())
                    )
                    put("dialCallDuration", durationSeconds)
                    put("dialCallStatus", dialCallStatus)
                    put("direction", "incoming")
                    put("pubId", PUB_ID)
                }

                Log.d("CallReceiver", "🚀 API: $callType | $phoneNumber | ${durationSeconds}s")

                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }

                conn.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                Log.d("CallReceiver", "✅ Response: $responseCode")

                conn.disconnect()

            } catch (e: Exception) {
                Log.e("CallReceiver", "❌ API Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}