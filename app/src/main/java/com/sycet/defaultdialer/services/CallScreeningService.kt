package com.sycet.defaultdialer.services

import android.content.Intent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.RequiresApi
import com.sycet.defaultdialer.ui.call.CallScreenActivity

class CallScreeningService : InCallService() {

    companion object {
        const val TAG = "CallScreeningService"
        var currentCall: Call? = null
        var callDisconnectedBy: String = "Unknown"
        // Audio manager for muting / speaker control. Must be obtained from the InCallService
        private var audioManager: AudioManager? = null

        fun muteCall(isMuted: Boolean) {
            try {
                audioManager?.isMicrophoneMute = isMuted
                Log.d(TAG, "muteCall -> $isMuted")
            } catch (e: Exception) {
                Log.w(TAG, "muteCall failed", e)
            }
        }

        fun setSpeaker(enable: Boolean) {
            try {
                audioManager?.isSpeakerphoneOn = enable
                Log.d(TAG, "setSpeaker -> $enable")
            } catch (e: Exception) {
                Log.w(TAG, "setSpeaker failed", e)
            }
        }
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
                        DisconnectCause.LOCAL -> "You (Local User)"
                        DisconnectCause.REMOTE -> "Other Party (Remote User)"
                        DisconnectCause.REJECTED -> "Call Rejected"
                        DisconnectCause.MISSED -> "Missed Call"
                        DisconnectCause.CANCELED -> "Call Canceled"
                        DisconnectCause.BUSY -> "Busy"
                        DisconnectCause.RESTRICTED -> "Restricted"
                        DisconnectCause.ERROR -> "Error"
                        DisconnectCause.UNKNOWN -> "Unknown"
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
        // Initialize audio manager from the InCallService context — this is required
        // to reliably toggle microphone and speaker for telecom-managed calls.
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.d(TAG, "AudioManager initialized in InCallService: mode=${audioManager?.mode}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize AudioManager in InCallService", e)
        }
        call?.registerCallback(callCallback)
        Log.d(TAG, "Call added")
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        call?.unregisterCallback(callCallback)
        if (currentCall == call) currentCall = null
        // Clear audio manager when call is removed — keep conservative cleanup
        try {
            audioManager = null
        } catch (_: Exception) {}
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
            // For dialing/ringing state we do not allow conference/merge
            putExtra(CallScreenActivity.EXTRA_CAN_CONFERENCE, false)
            putExtra(CallScreenActivity.EXTRA_CAN_MERGE, false)
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
        // If call becomes active, tell CallScreenActivity it can show conference/merge options
        if (callState.contains("Active", ignoreCase = true)) {
            val phoneNumber = call?.details?.handle?.schemeSpecificPart
                ?: call?.details?.handle?.toString()?.substringAfter("tel:")?.substringBefore("@") ?: "Unknown"

            val intent = Intent(this, CallScreenActivity::class.java).apply {
                putExtra("PHONE_NUMBER", phoneNumber)
                putExtra("CALL_STATE", callState)
                putExtra(CallScreenActivity.EXTRA_CAN_CONFERENCE, true)
                putExtra(CallScreenActivity.EXTRA_CAN_MERGE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }

            try {
                startActivity(intent)
                Log.d(TAG, "Call screen updated for active call: $phoneNumber")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update call screen for active call", e)
            }
        }
    }
}