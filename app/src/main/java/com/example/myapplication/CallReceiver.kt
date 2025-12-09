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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val PUB_ID = "283"
        private const val API_URL = "https://develop-api.zillout.com/api/v1/rbzo/exotel/read-from-app"

        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var incomingNumber: String? = null
        private var callWasPicked = false
        private var callStartTime: Long = 0L

        private fun generateCallSid(): String {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 32)
        }
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
                            callType = "incomplete",
                            dialCallStatus = "no-answer",
                            durationSeconds = 0
                        )
                    }
                }

                // COMPLETED CALL
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK && callWasPicked) {

                    val endTime = System.currentTimeMillis()
                    val durationMillis = endTime - callStartTime
                    val durationSeconds = (durationMillis / 1000).toInt()

                    Log.d(
                        "CallReceiver",
                        "CALL ENDED: $incomingNumber | Duration: $durationSeconds sec"
                    )

                    incomingNumber?.let {
                        sendCallDataToAPI(
                            phoneNumber = it,
                            callType = "completed",
                            dialCallStatus = "completed",
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

    private fun sendToWebhookTest(phoneNumber: String) {

        try {
            val url = URL("https://webhook.site/fdf6974a-9814-42b9-b1e4-f7fe5f39e4ec")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val jsonData = JSONObject().apply {
                put("phoneNumber", phoneNumber)
                put(
                    "timestamp",
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
                put("callType", "incoming")
            }

            connection.outputStream.use { os ->
                os.write(jsonData.toString().toByteArray(Charsets.UTF_8))
            }

            connection.responseCode
            connection.disconnect()

        } catch (e: Exception) {
            Log.e("WebhookTest", "ERROR -> ${e.message}")
        }
    }


    private fun sendCallDataToAPI(
        phoneNumber: String,
        callType: String,
        dialCallStatus: String,
        durationSeconds: Int,

    ) {

        CoroutineScope(Dispatchers.IO).launch {

            try {
                val baseUrl = API_URL
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

                val url = URL(baseUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                // Send body
                connection.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode

                Log.d(
                    "CallReceiver",
                    "API SENT -> $callType | $phoneNumber | ${durationSeconds}s | PUB:$PUB_ID | Response: $responseCode"
                )

                connection.disconnect()

            } catch (e: Exception) {

                Log.e("CallReceiver", "API ERROR -> ${e.message}")
                e.printStackTrace()
            }
        }
    }
}


