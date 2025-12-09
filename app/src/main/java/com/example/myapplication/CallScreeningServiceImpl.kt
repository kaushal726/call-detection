package com.example.myapplication

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

@RequiresApi(Build.VERSION_CODES.N)
class CallScreeningServiceImpl : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Get phone number
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: "Unknown"

        // Check if it's an incoming call
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        if (isIncoming) {
            Log.d("CallScreening", "Incoming call from: $phoneNumber")
            sendCallDataToAPI(phoneNumber)
        }

        // Allow the call to proceed normally
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }

    private fun sendCallDataToAPI(phoneNumber: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Replace with your actual API endpoint
                val apiUrl = "https://webhook.site/fdf6974a-9814-42b9-b1e4-f7fe5f39e4ec"

                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                // Create JSON data
                val jsonData = JSONObject().apply {
                    put("phoneNumber", phoneNumber)
                    put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    put("callType", "incoming")
                    put("deviceInfo", "Android ${Build.VERSION.RELEASE}")
                }

                // Send data
                connection.outputStream.use { os ->
                    val input = jsonData.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                // Get response
                val responseCode = connection.responseCode
                Log.d("CallScreening", "API Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d("CallScreening", "API Response: $response")
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e("CallScreening", "Error sending data to API: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}