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
//        private var lastState = TelephonyManager.CALL_STATE_IDLE
//        private var incomingNumber: String? = null
//        private var callWasPicked = false
//    }
//
//    @Suppress("DEPRECATION")
//    override fun onReceive(context: Context?, intent: Intent?) {
//        if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
//
//            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
//            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
//
//
//            var state = 0
//
//            when (stateStr) {
//                TelephonyManager.EXTRA_STATE_IDLE -> state = TelephonyManager.CALL_STATE_IDLE
//                TelephonyManager.EXTRA_STATE_OFFHOOK -> state = TelephonyManager.CALL_STATE_OFFHOOK
//                TelephonyManager.EXTRA_STATE_RINGING -> state = TelephonyManager.CALL_STATE_RINGING
//            }
//
//            if(number!=null){
//                onCallStateChanged(state, number)
//
//            }
//        }
//    }
//
//    private fun onCallStateChanged(state: Int, number: String?) {
//
//        // same state ignore
//        if (lastState == state) return
//
//        when (state) {
//
//            TelephonyManager.CALL_STATE_RINGING -> {
//                incomingNumber = number
//                callWasPicked = false
//
//                Log.d("CallReceiver", "RINGING from : $incomingNumber")
//            }
//
//            TelephonyManager.CALL_STATE_OFFHOOK -> {
//                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
//                    callWasPicked = true
//
//                    Log.d("CallReceiver", "PICKED from: $incomingNumber")
//
//                    incomingNumber?.let {
//                        sendCallDataToAPI(it, "picked")
//                    }
//                }
//            }
//
//            TelephonyManager.CALL_STATE_IDLE -> {
//                if (lastState == TelephonyManager.CALL_STATE_RINGING && !callWasPicked) {
//
//                    Log.d("CallReceiver", "MISSED from: $incomingNumber")
//
//                    incomingNumber?.let {
//                        sendCallDataToAPI(it, "missed")
//                    }
//                }
//
//                // ✅ reset
//                incomingNumber = null
//                callWasPicked = false
//            }
//        }
//
//        lastState = state
//    }
//
//    private fun sendCallDataToAPI(phoneNumber: String, status: String) {
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//
//                val apiUrl = "https://webhook.site/fdf6974a-9814-42b9-b1e4-f7fe5f39e4ec"
//
//                val url = URL(apiUrl)
//                val connection = url.openConnection() as HttpURLConnection
//
//                connection.requestMethod = "POST"
//                connection.setRequestProperty("Content-Type", "application/json")
//                connection.doOutput = true
//
//                val jsonData = JSONObject().apply {
//                    put("phoneNumber", phoneNumber)
//                    put(
//                        "timestamp",
//                        SimpleDateFormat(
//                            "yyyy-MM-dd HH:mm:ss",
//                            Locale.getDefault()
//                        ).format(Date())
//                    )
//                    put("callType", "incoming")
//                    put("status", status)
//                }
//
//                connection.outputStream.use { os ->
//                    os.write(jsonData.toString().toByteArray(Charsets.UTF_8))
//                }
//
//                val responseCode = connection.responseCode
//
//                Log.d(
//                    "CallReceiver",
//                    "API RESPONSE -> $responseCode for [$status] : $phoneNumber"
//                )
//
//                connection.disconnect()
//
//            } catch (e: Exception) {
//                Log.e("CallReceiver", "API ERROR -> ${e.message}")
//            }
//        }
//    }
//}

package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class CallReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var incomingNumber: String? = null
        private var callWasPicked = false
        private var callStartTime: Long = 0L
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {

        if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {

            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            var state = TelephonyManager.CALL_STATE_IDLE

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_IDLE ->
                    state = TelephonyManager.CALL_STATE_IDLE

                TelephonyManager.EXTRA_STATE_OFFHOOK ->
                    state = TelephonyManager.CALL_STATE_OFFHOOK

                TelephonyManager.EXTRA_STATE_RINGING ->
                    state = TelephonyManager.CALL_STATE_RINGING
            }

            if (number != null) {
                onCallStateChanged(state, number)
            }
        }
    }

    private fun onCallStateChanged(state: Int, number: String?) {

        if (lastState == state) return

        when (state) {

            TelephonyManager.CALL_STATE_RINGING -> {
                incomingNumber = number
                callWasPicked = false

                Log.d("CallReceiver", "RINGING FROM: $incomingNumber")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {

                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    callWasPicked = true
                    callStartTime = System.currentTimeMillis()

                    Log.d("CallReceiver", "CALL PICKED: $incomingNumber")
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {

                // MISSED CALL
                if (lastState == TelephonyManager.CALL_STATE_RINGING && !callWasPicked) {

                    Log.d("CallReceiver", "MISSED CALL: $incomingNumber")

                    incomingNumber?.let {
                        sendCallDataToAPI(
                            phoneNumber = it,
                            status = "missed",
                            durationSeconds = 0
                        )
                    }
                }

                // COMPLETED CALL
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK && callWasPicked) {

                    val endTime = System.currentTimeMillis()
                    val durationMillis = endTime - callStartTime
                    val durationSeconds = (durationMillis / 1000)

                    Log.d(
                        "CallReceiver",
                        "CALL ENDED: $incomingNumber | Duration: $durationSeconds sec"
                    )

                    incomingNumber?.let {
                        sendCallDataToAPI(
                            phoneNumber = it,
                            status = "picked",
                            durationSeconds = durationSeconds
                        )
                    }
                }

                incomingNumber = null
                callWasPicked = false
                callStartTime = 0L
            }
        }

        lastState = state
    }

    private fun sendCallDataToAPI(
        phoneNumber: String,
        status: String,
        durationSeconds: Long
    ) {

        CoroutineScope(Dispatchers.IO).launch {

            try {

                val apiUrl =
                    "https://webhook.site/fdf6974a-9814-42b9-b1e4-f7fe5f39e4ec"

                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonData = JSONObject().apply {

                    put("phoneNumber", phoneNumber)

                    put(
                        "timestamp",
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date())
                    )

                    put("callType", "incoming")
                    put("status", status)

                    put("durationInSeconds", durationSeconds)
                }

                connection.outputStream.use { os ->
                    os.write(jsonData.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode

                Log.d(
                    "CallReceiver",
                    "API SENT -> $status | $phoneNumber | ${durationSeconds}s | Response: $responseCode"
                )

                connection.disconnect()

            } catch (e: Exception) {

                Log.e("CallReceiver", "API ERROR -> ${e.message}")
            }
        }
    }
}


