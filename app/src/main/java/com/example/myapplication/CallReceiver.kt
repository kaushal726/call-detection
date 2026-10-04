package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Android wakes this on every phone state change, even when the app is closed (but not when it
 * has been force-stopped). When the line goes idle a call has just ended, so queue a sync; the
 * job works out what is new from the call log, which also covers calls whose end we never saw.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_IDLE) {
            Log.d("CallReceiver", "Line idle, queueing call sync")
            CallSyncWorker.enqueue(context)
        }
    }
}
