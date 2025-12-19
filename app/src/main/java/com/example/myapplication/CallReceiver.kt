//package com.example.myapplication
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.telephony.TelephonyManager
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import org.json.JSONObject
//import java.net.HttpURLConnection
//import java.net.URL
//import java.text.SimpleDateFormat
//import java.util.*
//
//class CallReceiver : BroadcastReceiver() {
//
//    companion object {
//        private const val PUB_ID = "818"
//        private const val API_URL =
//            "https://api.zillout.com/api/v1/rbzo/exotel/read-from-app"
//
//        private var lastState = TelephonyManager.CALL_STATE_IDLE
//
//        private var incomingNumber: String? = null
//        private var callWasPicked = false
//        private var callStartTime: Long = 0L
//        private var isOnCall = false
//
//        // Track multiple waiting calls: (phoneNumber to ringStartTime)
//        private val waitingCalls = mutableListOf<Pair<String, Long>>()
//
//        private fun generateCallSid(): String =
//            UUID.randomUUID().toString().replace("-", "").substring(0, 32)
//    }
//
//    override fun onReceive(context: Context?, intent: Intent?) {
//
//        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
//
//        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
//        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
//
//        val state = when (stateStr) {
//            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
//            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
//            else -> TelephonyManager.CALL_STATE_IDLE
//        }
//
//        if (number != null) {
//            onCallStateChanged(state, number)
//        }
//    }
//
//    private fun onCallStateChanged(state: Int, number: String) {
//
//        if (state == lastState) return
//
//        when (state) {
//
//            /* ---------------- RINGING ---------------- */
//            TelephonyManager.CALL_STATE_RINGING -> {
//
//                if (isOnCall && callWasPicked) {
//                    // Already on a call - add to waiting list
//                    val ringTime = System.currentTimeMillis()
//                    waitingCalls.add(number to ringTime)
//                    Log.d("CallReceiver", "WAITING CALL #${waitingCalls.size}: $number")
//                } else {
//                    // First incoming call
//                    incomingNumber = number
//                    callWasPicked = false
//                    Log.d("CallReceiver", "RINGING: $number")
//                }
//            }
//
//            /* ---------------- OFFHOOK ---------------- */
//            TelephonyManager.CALL_STATE_OFFHOOK -> {
//
//                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
//
//                    if (waitingCalls.isNotEmpty()) {
//                        // Picking up a waiting call - Android picks the most recent one
//                        val switchTime = System.currentTimeMillis()
//                        val (pickedNumber, _) = waitingCalls.removeAt(waitingCalls.lastIndex)
//
//                        Log.d("CallReceiver", "WAITING CALL PICKED: $pickedNumber")
//
//                        // End current active call
//                        if (incomingNumber != null && callWasPicked && callStartTime > 0) {
//                            val duration = ((switchTime - callStartTime) / 1000).toInt()
//                            Log.d("CallReceiver", "PREVIOUS CALL ENDED: $incomingNumber | $duration sec")
//
//                            sendCallDataToAPI(
//                                phoneNumber = incomingNumber!!,
//                                callType = "completed",
//                                dialCallStatus = "completed",
//                                durationSeconds = duration
//                            )
//                        }
//
//                        // Switch to waiting call
//                        incomingNumber = pickedNumber
//                        callStartTime = switchTime
//                        callWasPicked = true
//                        isOnCall = true
//
//                    } else {
//                        // Normal first call pickup
//                        callWasPicked = true
//                        callStartTime = System.currentTimeMillis()
//                        isOnCall = true
//                        Log.d("CallReceiver", "CALL PICKED: $incomingNumber")
//                    }
//                }
//            }
//
//            /* ---------------- IDLE ---------------- */
//            TelephonyManager.CALL_STATE_IDLE -> {
//
//                // Handle all waiting calls that were NOT picked (rejected/missed)
//                if (waitingCalls.isNotEmpty()) {
//                    Log.d("CallReceiver", "CLEARING ${waitingCalls.size} WAITING CALL(S)")
//
//                    waitingCalls.forEach { (phone, _) ->
//                        Log.d("CallReceiver", "WAITING CALL MISSED: $phone")
//                        sendCallDataToAPI(
//                            phoneNumber = phone,
//                            callType = "incomplete",
//                            dialCallStatus = "no-answer",
//                            durationSeconds = 0
//                        )
//                    }
//                    waitingCalls.clear()
//                }
//
//                // Handle missed first/main incoming call
//                if (lastState == TelephonyManager.CALL_STATE_RINGING && !callWasPicked) {
//                    Log.d("CallReceiver", "MISSED CALL: $incomingNumber")
//
//                    incomingNumber?.let {
//                        sendCallDataToAPI(
//                            phoneNumber = it,
//                            callType = "incomplete",
//                            dialCallStatus = "no-answer",
//                            durationSeconds = 0
//                        )
//                    }
//                }
//
//                // Handle completed/ended call
//                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK && callWasPicked) {
//                    val endTime = System.currentTimeMillis()
//                    val duration = ((endTime - callStartTime) / 1000).toInt()
//
//                    Log.d("CallReceiver", "CALL ENDED: $incomingNumber | $duration sec")
//
//                    incomingNumber?.let {
//                        sendCallDataToAPI(
//                            phoneNumber = it,
//                            callType = "completed",
//                            dialCallStatus = "completed",
//                            durationSeconds = duration
//                        )
//                    }
//                }
//
//                // Reset all state
//                incomingNumber = null
//                callWasPicked = false
//                callStartTime = 0L
//                isOnCall = false
//            }
//        }
//
//        lastState = state
//    }
//
//    private fun sendCallDataToAPI(
//        phoneNumber: String,
//        callType: String,
//        dialCallStatus: String,
//        durationSeconds: Int
//    ) {
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                val body = JSONObject().apply {
//                    put("callSid", "app_${generateCallSid()}")
//                    put("callFrom", phoneNumber)
//                    put("callType", callType)
//                    put(
//                        "created",
//                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH)
//                            .format(Date())
//                    )
//                    put("dialCallDuration", durationSeconds)
//                    put("dialCallStatus", dialCallStatus)
//                    put("direction", "incoming")
//                    put("pubId", PUB_ID)
//                }
//
//                Log.d("CallReceiver", "API BODY: $body")
//
//                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
//                    requestMethod = "POST"
//                    setRequestProperty("Content-Type", "application/json")
//                    connectTimeout = 10000
//                    readTimeout = 10000
//                    doOutput = true
//                }
//
//                conn.outputStream.use {
//                    it.write(body.toString().toByteArray(Charsets.UTF_8))
//                }
//
//                val responseCode = conn.responseCode
//                Log.d(
//                    "CallReceiver",
//                    "API SENT → $callType | $phoneNumber | ${durationSeconds}s | Response: $responseCode"
//                )
//
//                conn.disconnect()
//
//            } catch (e: Exception) {
//                Log.e("CallReceiver", "API ERROR → ${e.message}")
//                e.printStackTrace()
//            }
//        }
//    }
//}

//package com.example.myapplication
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.telephony.TelephonyManager
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import org.json.JSONObject
//import java.net.HttpURLConnection
//import java.net.URL
//import java.text.SimpleDateFormat
//import java.util.*
//
//class CallReceiver : BroadcastReceiver() {
//
//    companion object {
//        private const val PUB_ID = "818"
//        private const val API_URL =
//            "https://api.zillout.com/api/v1/rbzo/exotel/read-from-app"
//
//        private var lastState = TelephonyManager.CALL_STATE_IDLE
//
//        // Store ALL calls with their details
//        data class CallData(
//            val phoneNumber: String,
//            val startTime: Long,
//            var isPicked: Boolean = false,
//            var endTime: Long = 0L
//        )
//
//        private val allCalls = mutableListOf<CallData>()
//
//        private fun generateCallSid(): String =
//            UUID.randomUUID().toString().replace("-", "").substring(0, 32)
//    }
//
//    override fun onReceive(context: Context?, intent: Intent?) {
//
//        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
//
//        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
//        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
//
//        val state = when (stateStr) {
//            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
//            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
//            else -> TelephonyManager.CALL_STATE_IDLE
//        }
//
//        if (number != null) {
//            onCallStateChanged(state, number)
//        }
//    }
//
//    private fun onCallStateChanged(state: Int, number: String) {
//
//        if (state == lastState) return
//
//        when (state) {
//
//            /* ---------------- RINGING ---------------- */
//            TelephonyManager.CALL_STATE_RINGING -> {
//                // Add every ringing call to list
//                val callData = CallData(
//                    phoneNumber = number,
//                    startTime = System.currentTimeMillis(),
//                    isPicked = false
//                )
//                allCalls.add(callData)
//                Log.d("CallReceiver", "RINGING #${allCalls.size}: $number")
//            }
//
//            /* ---------------- OFFHOOK ---------------- */
//            TelephonyManager.CALL_STATE_OFFHOOK -> {
//                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
//                    // Find the most recent unpicked call and mark it as picked
//                    val pickedCall = allCalls.lastOrNull { !it.isPicked }
//
//                    if (pickedCall != null) {
//                        pickedCall.isPicked = true
//                        Log.d("CallReceiver", "CALL PICKED: ${pickedCall.phoneNumber}")
//
//                        // Check if there was a previous picked call - end it
//                        val previousCall = allCalls.filter { it.isPicked }.dropLast(1).lastOrNull()
//                        if (previousCall != null && previousCall.endTime == 0L) {
//                            previousCall.endTime = System.currentTimeMillis()
//                            Log.d("CallReceiver", "PREVIOUS CALL ENDED: ${previousCall.phoneNumber}")
//                        }
//                    }
//                }
//            }
//
//            /* ---------------- IDLE ---------------- */
//            TelephonyManager.CALL_STATE_IDLE -> {
//
//                val currentTime = System.currentTimeMillis()
//
//                // Mark end time for any active picked call
//                allCalls.filter { it.isPicked && it.endTime == 0L }.forEach {
//                    it.endTime = currentTime
//                }
//
//                Log.d("CallReceiver", "IDLE - Processing ${allCalls.size} call(s)")
//
//                // Send ALL calls to API
//                allCalls.forEach { call ->
//                    if (call.isPicked) {
//                        // Completed call
//                        val duration = ((call.endTime - call.startTime) / 1000).toInt()
//                        Log.d("CallReceiver", "COMPLETED: ${call.phoneNumber} | $duration sec")
//
//                        sendCallDataToAPI(
//                            phoneNumber = call.phoneNumber,
//                            callType = "completed",
//                            dialCallStatus = "completed",
//                            durationSeconds = duration
//                        )
//                    } else {
//                        // Missed/Incomplete call
//                        Log.d("CallReceiver", "MISSED: ${call.phoneNumber}")
//
//                        sendCallDataToAPI(
//                            phoneNumber = call.phoneNumber,
//                            callType = "incomplete",
//                            dialCallStatus = "no-answer",
//                            durationSeconds = 0
//                        )
//                    }
//                }
//
//                // Clear all calls
//                allCalls.clear()
//                Log.d("CallReceiver", "All calls cleared")
//            }
//        }
//
//        lastState = state
//    }
//
//    private fun sendCallDataToAPI(
//        phoneNumber: String,
//        callType: String,
//        dialCallStatus: String,
//        durationSeconds: Int
//    ) {
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                val body = JSONObject().apply {
//                    put("callSid", "app_${generateCallSid()}")
//                    put("callFrom", phoneNumber)
//                    put("callType", callType)
//                    put(
//                        "created",
//                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH)
//                            .format(Date())
//                    )
//                    put("dialCallDuration", durationSeconds)
//                    put("dialCallStatus", dialCallStatus)
//                    put("direction", "incoming")
//                    put("pubId", PUB_ID)
//                }
//
//                Log.d("CallReceiver", "API BODY: $body")
//
//                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
//                    requestMethod = "POST"
//                    setRequestProperty("Content-Type", "application/json")
//                    connectTimeout = 10000
//                    readTimeout = 10000
//                    doOutput = true
//                }
//
//                conn.outputStream.use {
//                    it.write(body.toString().toByteArray(Charsets.UTF_8))
//                }
//
//                val responseCode = conn.responseCode
//                Log.d(
//                    "CallReceiver",
//                    "API SENT → $callType | $phoneNumber | ${durationSeconds}s | Response: $responseCode"
//                )
//
//                conn.disconnect()
//
//            } catch (e: Exception) {
//                Log.e("CallReceiver", "API ERROR → ${e.message}")
//                e.printStackTrace()
//            }
//        }
//    }
//}

//package com.example.myapplication
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.database.Cursor
//import android.provider.CallLog
//import android.telephony.TelephonyManager
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import org.json.JSONObject
//import java.net.HttpURLConnection
//import java.net.URL
//import java.text.SimpleDateFormat
//import java.util.*
//
//class CallReceiver : BroadcastReceiver() {
//
//    companion object {
//        private const val PUB_ID = "818"
//        private const val API_URL = "https://api.zillout.com/api/v1/rbzo/exotel/read-from-app"
//
//        private var lastState = TelephonyManager.CALL_STATE_IDLE
//        private var lastProcessedCallTime: Long = 0L
//        private val processedCallIds = mutableSetOf<String>()
//
//        private fun generateCallSid(): String =
//            UUID.randomUUID().toString().replace("-", "").substring(0, 32)
//    }
//
//    override fun onReceive(context: Context?, intent: Intent?) {
//
//        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
//
//        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
//
//        val state = when (stateStr) {
//            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
//            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
//            else -> TelephonyManager.CALL_STATE_IDLE
//        }
//
//        onCallStateChanged(context, state)
//    }
//
//    private fun onCallStateChanged(context: Context?, state: Int) {
//
//        if (state == lastState) return
//
//        when (state) {
//            TelephonyManager.CALL_STATE_RINGING -> {
//                Log.d("CallReceiver", "RINGING state detected")
//            }
//
//            TelephonyManager.CALL_STATE_OFFHOOK -> {
//                Log.d("CallReceiver", "OFFHOOK state detected")
//            }
//
//            TelephonyManager.CALL_STATE_IDLE -> {
//                Log.d("CallReceiver", "IDLE state - Reading call log")
//
//                // Wait a bit for call log to update
//                CoroutineScope(Dispatchers.IO).launch {
//                    delay(1000) // 1 second delay
//                    context?.let { readAndSendCallLog(it) }
//                }
//            }
//        }
//
//        lastState = state
//    }
//
//    private fun readAndSendCallLog(context: Context) {
//        try {
//            val projection = arrayOf(
//                CallLog.Calls._ID,
//                CallLog.Calls.NUMBER,
//                CallLog.Calls.TYPE,
//                CallLog.Calls.DATE,
//                CallLog.Calls.DURATION
//            )
//
//            val cursor: Cursor? = context.contentResolver.query(
//                CallLog.Calls.CONTENT_URI,
//                projection,
//                "${CallLog.Calls.DATE} > ?",
//                arrayOf(lastProcessedCallTime.toString()),
//                "${CallLog.Calls.DATE} DESC"
//            )
//
//            cursor?.use {
//                while (it.moveToNext()) {
//                    val id = it.getString(it.getColumnIndexOrThrow(CallLog.Calls._ID))
//
//                    // Skip if already processed
//                    if (processedCallIds.contains(id)) continue
//
//                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
//                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
//                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
//                    val duration = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
//
//                    // Only process incoming calls
//                    if (type == CallLog.Calls.INCOMING_TYPE) {
//
//                        val callType: String
//                        val callStatus: String
//
//                        if (duration > 0) {
//                            callType = "completed"
//                            callStatus = "completed"
//                            Log.d("CallReceiver", "Call Log - COMPLETED: $number | $duration sec")
//                        } else {
//                            callType = "incomplete"
//                            callStatus = "no-answer"
//                            Log.d("CallReceiver", "Call Log - MISSED: $number")
//                        }
//
//                        sendCallDataToAPI(
//                            phoneNumber = number ?: "Unknown",
//                            callType = callType,
//                            dialCallStatus = callStatus,
//                            durationSeconds = duration
//                        )
//
//                        // Mark as processed
//                        processedCallIds.add(id)
//                        lastProcessedCallTime = date
//                    }
//                }
//            }
//
//        } catch (e: Exception) {
//            Log.e("CallReceiver", "Call Log Read Error: ${e.message}")
//            e.printStackTrace()
//        }
//    }
//
//    private fun sendCallDataToAPI(
//        phoneNumber: String,
//        callType: String,
//        dialCallStatus: String,
//        durationSeconds: Int
//    ) {
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                val body = JSONObject().apply {
//                    put("callSid", "app_${generateCallSid()}")
//                    put("callFrom", phoneNumber)
//                    put("callType", callType)
//                    put(
//                        "created",
//                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH)
//                            .format(Date())
//                    )
//                    put("dialCallDuration", durationSeconds)
//                    put("dialCallStatus", dialCallStatus)
//                    put("direction", "incoming")
//                    put("pubId", PUB_ID)
//                }
//
//                Log.d("CallReceiver", "API BODY: $body")
//
//                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
//                    requestMethod = "POST"
//                    setRequestProperty("Content-Type", "application/json")
//                    connectTimeout = 10000
//                    readTimeout = 10000
//                    doOutput = true
//                }
//
//                conn.outputStream.use {
//                    it.write(body.toString().toByteArray(Charsets.UTF_8))
//                }
//
//                val responseCode = conn.responseCode
//                Log.d(
//                    "CallReceiver",
//                    "API SENT → $callType | $phoneNumber | ${durationSeconds}s | Response: $responseCode"
//                )
//
//                conn.disconnect()
//
//            } catch (e: Exception) {
//                Log.e("CallReceiver", "API ERROR → ${e.message}")
//                e.printStackTrace()
//            }
//        }
//    }
//}
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
        private const val API_URL = "https://api.zillout.com/api/v1/rbzo/exotel/read-from-app"

        private var lastState = TelephonyManager.CALL_STATE_IDLE

        // Track picked calls
        private var incomingNumber: String? = null
        private var callWasPicked = false
        private var callStartTime: Long = 0L
        private var isOnCall = false

        // Track ONLY latest ringing session
        private var latestRingingStartTime: Long = 0L
        private var shouldCheckCallLog = false

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

        onCallStateChanged(context, state, number)
    }

    private fun onCallStateChanged(context: Context?, state: Int, number: String?) {

        if (state == lastState) return

        when (state) {

            /* ---------------- RINGING ---------------- */
            TelephonyManager.CALL_STATE_RINGING -> {

                // Update to LATEST ringing time (overwrite previous)
                latestRingingStartTime = System.currentTimeMillis()
                shouldCheckCallLog = false
                Log.d("CallReceiver", "LATEST RINGING at: $latestRingingStartTime")

                if (isOnCall && callWasPicked) {
                    // Call waiting - mark to check call log
                    shouldCheckCallLog = true
                    Log.d("CallReceiver", "CALL WAITING - will check call log")
                } else if (number != null) {
                    incomingNumber = number
                    callWasPicked = false
                    Log.d("CallReceiver", "RINGING: $number")
                }
            }

            /* ---------------- OFFHOOK ---------------- */
            TelephonyManager.CALL_STATE_OFFHOOK -> {

                if (lastState == TelephonyManager.CALL_STATE_RINGING) {

                    if (isOnCall && callWasPicked && incomingNumber != null) {
                        // Switching calls
                        val switchTime = System.currentTimeMillis()
                        val duration = ((switchTime - callStartTime) / 1000).toInt()

                        Log.d("CallReceiver", "SWITCHING - Previous ended: $incomingNumber | $duration sec")

                        sendCallDataToAPI(
                            phoneNumber = incomingNumber!!,
                            callType = "completed",
                            dialCallStatus = "completed",
                            durationSeconds = duration
                        )

                        callStartTime = switchTime

                    } else {
                        // Normal pickup
                        callWasPicked = true
                        callStartTime = System.currentTimeMillis()
                        isOnCall = true
                        Log.d("CallReceiver", "CALL PICKED: $incomingNumber")
                    }
                }
            }

            /* ---------------- IDLE ---------------- */
            TelephonyManager.CALL_STATE_IDLE -> {

                Log.d("CallReceiver", "IDLE state")

                // Handle missed call
                if (lastState == TelephonyManager.CALL_STATE_RINGING && !callWasPicked) {

                    Log.d("CallReceiver", "MISSED CALL - Checking LATEST ringing session only")

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000) // Wait for call log to update
                        context?.let { checkLatestMissedCalls(it) }
                    }
                }

                // Handle completed call
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

                    // Check for waiting calls that were missed
                    if (shouldCheckCallLog) {
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(2000)
                            context?.let { checkLatestMissedCalls(it) }
                        }
                    }
                }

                // Reset
                incomingNumber = null
                callWasPicked = false
                callStartTime = 0L
                isOnCall = false
                shouldCheckCallLog = false
                // DON'T reset latestRingingStartTime here - keep it for call log query
            }
        }

        lastState = state
    }

    private fun checkLatestMissedCalls(context: Context) {

        if (latestRingingStartTime == 0L) {
            Log.d("CallReceiver", "No ringing session to check")
            return
        }

        val checkFrom = latestRingingStartTime - 2000 // 2 seconds buffer before
        val checkUntil = System.currentTimeMillis()

        Log.d("CallReceiver", "Checking LATEST ringing session: $checkFrom to $checkUntil")

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            // Get ONLY missed incoming calls from latest session
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
                "${CallLog.Calls.DATE} DESC"
            )

            val missedNumbers = mutableSetOf<String>()

            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    // Avoid duplicates
                    if (number != null && !missedNumbers.contains(number)) {
                        missedNumbers.add(number)
                        Log.d("CallReceiver", "MISSED (latest session): $number at $date")

                        sendCallDataToAPI(
                            phoneNumber = number,
                            callType = "incomplete",
                            dialCallStatus = "no-answer",
                            durationSeconds = 0
                        )
                    }
                }
            }

            Log.d("CallReceiver", "Sent ${missedNumbers.size} missed call(s) from LATEST session")

            // Now reset latestRingingStartTime after processing
            latestRingingStartTime = 0L

        } catch (e: Exception) {
            Log.e("CallReceiver", "Call Log Error: ${e.message}")
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

                Log.d("CallReceiver", "API BODY: $body")

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
                Log.d(
                    "CallReceiver",
                    "API SENT → $callType | $phoneNumber | ${durationSeconds}s | Response: $responseCode"
                )

                conn.disconnect()

            } catch (e: Exception) {
                Log.e("CallReceiver", "API ERROR → ${e.message}")
                e.printStackTrace()
            }
        }
    }
}