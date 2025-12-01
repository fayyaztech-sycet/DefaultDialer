package com.sycet.defaultdialer

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class CallScreeningService : InCallService() {
    
    companion object {
        const val TAG = "CallScreeningService"
        var currentCall: Call? = null
        var callDisconnectedBy: String = "Unknown"
    }
    
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            super.onStateChanged(call, state)
            
            when (state) {
                Call.STATE_DIALING -> {
                    Log.d(TAG, "Call State: DIALING")
                    launchCallScreen(call, "Dialing...")
                }
                Call.STATE_RINGING -> {
                    Log.d(TAG, "Call State: RINGING")
                    launchCallScreen(call, "Incoming call...")
                }
                Call.STATE_ACTIVE -> {
                    Log.d(TAG, "Call State: ACTIVE")
                    updateCallScreen(call, "Active")
                }
                Call.STATE_DISCONNECTED -> {
                    val disconnectCause = call?.details?.disconnectCause
                    
                    callDisconnectedBy = when (disconnectCause?.code) {
                        android.telecom.DisconnectCause.LOCAL -> "You (Local User)"
                        android.telecom.DisconnectCause.REMOTE -> "Other Party (Remote User)"
                        android.telecom.DisconnectCause.REJECTED -> "Call Rejected"
                        android.telecom.DisconnectCause.MISSED -> "Missed Call"
                        android.telecom.DisconnectCause.CANCELED -> "Call Canceled"
                        android.telecom.DisconnectCause.BUSY -> "Busy"
                        android.telecom.DisconnectCause.RESTRICTED -> "Restricted"
                        android.telecom.DisconnectCause.ERROR -> "Error"
                        android.telecom.DisconnectCause.UNKNOWN -> "Unknown"
                        else -> "Unknown (${disconnectCause?.code})"
                    }
                    
                    Log.d(TAG, "Call disconnected by: $callDisconnectedBy")
                    Log.d(TAG, "Disconnect reason: ${disconnectCause?.reason}")
                    
                    call?.unregisterCallback(this)
                    currentCall = null
                }
            }
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        currentCall = call
        call?.registerCallback(callCallback)
        Log.d(TAG, "Call added")
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        call?.unregisterCallback(callCallback)
        currentCall = null
        Log.d(TAG, "Call removed")
    }
    
    private fun launchCallScreen(call: Call?, callState: String) {
        // Try multiple methods to get phone number
        var phoneNumber = call?.details?.handle?.schemeSpecificPart
        
        // Fallback: try getting from URI string
        if (phoneNumber.isNullOrEmpty() || phoneNumber == "Unknown") {
            phoneNumber = call?.details?.handle?.toString()?.substringAfter("tel:")?.substringBefore("@") ?: "Unknown"
        }
        
        // Clean up the phone number (remove any spaces or special formatting)
        phoneNumber = phoneNumber.replace(" ", "").replace("-", "")
        
        Log.d(TAG, "Launching call screen - Raw Handle: ${call?.details?.handle}")
        Log.d(TAG, "Launching call screen - Extracted Number: $phoneNumber, State: $callState")
        
        val intent = Intent(this, CallScreenActivity::class.java).apply {
            putExtra("PHONE_NUMBER", phoneNumber)
            putExtra("CALL_STATE", callState)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        
        try {
            startActivity(intent)
            Log.d(TAG, "Call screen launched for: $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch call screen", e)
        }
    }
    
    private fun updateCallScreen(call: Call?, callState: String) {
        // In a real implementation, you would broadcast this state change
        // to update the CallScreenActivity
        Log.d(TAG, "Call state updated to: $callState")
    }
}
