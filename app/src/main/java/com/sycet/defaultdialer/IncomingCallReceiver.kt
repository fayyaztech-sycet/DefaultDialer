package com.sycet.defaultdialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

class IncomingCallReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "IncomingCallReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        val action = intent.action
        Log.d(TAG, "Received action: $action")
        
        when (action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                Log.d(TAG, "Phone state: $state, Number: $incomingNumber")
                
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        // Incoming call ringing
                        Log.d(TAG, "Incoming call from: $incomingNumber")
                        launchCallScreen(context, incomingNumber ?: "Unknown", "Incoming call...")
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        // Call answered or outgoing call started
                        Log.d(TAG, "Call active")
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // Call ended or no call
                        Log.d(TAG, "Call idle")
                    }
                }
            }
        }
    }
    
    private fun launchCallScreen(context: Context, phoneNumber: String, callState: String) {
        val intent = Intent(context, CallScreenActivity::class.java).apply {
            putExtra("PHONE_NUMBER", phoneNumber)
            putExtra("CALL_STATE", callState)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        
        try {
            context.startActivity(intent)
            Log.d(TAG, "Call screen launched for incoming call")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch call screen for incoming call", e)
        }
    }
}
