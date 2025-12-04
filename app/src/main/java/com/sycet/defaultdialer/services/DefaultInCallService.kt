package com.sycet.defaultdialer.services

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.util.Log
import com.sycet.defaultdialer.ui.call.CallScreenActivity

/**
 * In-call service implementation used by Telecom for presenting in-call UI and for controlling call
 * audio routing (mute / speaker). This used to be named "CallScreeningService" which collides with
 * the framework's android.telecom.CallScreeningService on newer Android releases.
 *
 * Rename to DefaultInCallService to avoid confusion and ensure Telecom binds the correct
 * InCallService implementation reliably on Android 13+.
 */
class DefaultInCallService : InCallService() {

    companion object {
        const val TAG = "DefaultInCallService"
        var currentCall: Call? = null
        var callDisconnectedBy: String = "Unknown"
        // Audio manager for muting / speaker control. Must be obtained from the InCallService
        private var audioManager: AudioManager? = null

        fun muteCall(isMuted: Boolean) {
            try {
                // Prefer the Telecom API to mute the active Call — this ensures proper
                // audio routing on Android 11+ where direct AudioManager microphone changes
                // are ignored for telecom-managed calls.
                var handled = false
                currentCall?.let { call ->
                    try {
                        // Use reflection to invoke setMuted so this code compiles with older
                        // compileSdk versions while still calling the API when available at runtime.
                        val method = call.javaClass.getMethod("setMuted", Boolean::class.javaPrimitiveType)
                        method.invoke(call, isMuted)
                        Log.d(TAG, "muteCall -> Call.setMuted($isMuted) [via reflection]")
                        handled = true
                    } catch (e: Exception) {
                        // Method unavailable or failed — fall back to AudioManager below.
                        Log.d(TAG, "Call.setMuted unavailable (reflection failed), falling back: ${e.message}")
                    }
                }

                // Fallback to global microphone mute for older devices or when no active call
                if (!handled) {
                    audioManager?.isMicrophoneMute = isMuted
                    Log.d(TAG, "muteCall -> AudioManager.isMicrophoneMute=$isMuted (fallback)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "muteCall failed", e)
            }
        }

        fun setSpeaker(enable: Boolean) {
            try {
                // Ensure audio mode is set to IN_COMMUNICATION before toggling speaker.
                // Some OEMs (Samsung/Xiaomi) require this mode change otherwise
                // speaker toggles may silently fail.
                try {
                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                } catch (_: Exception) {}

                audioManager?.isSpeakerphoneOn = enable
                Log.d(TAG, "setSpeaker -> $enable (mode=${'$'}{audioManager?.mode})")
            } catch (e: Exception) {
                Log.w(TAG, "setSpeaker failed", e)
            }
        }
    }

    private val callCallback =
            object : Call.Callback() {
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

                            callDisconnectedBy =
                                    when (disconnectCause?.code) {
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

                            // Launch/Update call screen to show the disconnect error/reason to the user
                            // This ensures that if a call fails immediately (e.g. Out of Service), the user sees it.
                            val reason = disconnectCause?.reason ?: "Unknown"
                            val stateLabel = if (disconnectCause?.code == DisconnectCause.ERROR || disconnectCause?.code == DisconnectCause.BUSY) {
                                "Error: $reason"
                            } else {
                                "Disconnected"
                            }
                            launchCallScreen(call, stateLabel)

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
            Log.d(TAG, "AudioManager initialized in InCallService: mode=${'$'}{audioManager?.mode}")
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
        // Try multiple methods to get phone number. For VoLTE/IMS/hidden numbers the
        // Telecom stack may not always provide a /schemeSpecificPart/ — check the
        // TelecomManager.EXTRA_INCOMING_NUMBER in the call details' intent extras
        // as a robust fallback (some carriers / OEMs populate it).
        var phoneNumber = call?.details?.handle?.schemeSpecificPart

        // Try Telecom extras fallback (VoLTE/IMS or hidden number cases)
        if (phoneNumber.isNullOrEmpty() || phoneNumber == "Unknown") {
            try {
                val extras = call?.details?.intentExtras
                // Compatible fallback for incoming number constant when compileSdk lacks the field
                val fromTelecom = extras?.getString("android.telecom.extra.INCOMING_NUMBER")
                phoneNumber =
                        if (!fromTelecom.isNullOrEmpty()) fromTelecom
                        else
                                call?.details
                                        ?.handle
                                        ?.toString()
                                        ?.substringAfter("tel:")
                                        ?.substringBefore("@")
                                        ?: "Unknown"
            } catch (_: Exception) {
                phoneNumber =
                        call?.details
                                ?.handle
                                ?.toString()
                                ?.substringAfter("tel:")
                                ?.substringBefore("@")
                                ?: "Unknown"
            }
        }

        // Clean up the phone number (remove any spaces or special formatting)
        phoneNumber = phoneNumber.replace(" ", "").replace("-", "")

        Log.d(TAG, "Launching call screen - Raw Handle: ${'$'}{call?.details?.handle}")
        Log.d(
                TAG,
                "Launching call screen - Extracted Number: ${'$'}phoneNumber, State: ${'$'}callState"
        )

        // Delegate actual activity creation/launch to shared helper to avoid races
        // when starting/updating the call screen from different call states.
        showCallScreen(phoneNumber, callState, canConference = false, canMerge = false)
    }

    private fun updateCallScreen(call: Call?, callState: String) {
        // In a real implementation, you would broadcast this state change
        // to update the CallScreenActivity
        Log.d(TAG, "Call state updated to: ${'$'}callState")
        // If call becomes active, tell CallScreenActivity it can show conference/merge options
        if (callState.contains("Active", ignoreCase = true)) {
            // For active calls prefer schemeSpecificPart but fall back to any
            // Telecom-provided incoming number (TelecomManager.EXTRA_INCOMING_NUMBER)
            // or the raw handle string. This improves handling for VoLTE/IMS calls.
            var phoneNumber = call?.details?.handle?.schemeSpecificPart
            if (phoneNumber.isNullOrEmpty() || phoneNumber == "Unknown") {
                try {
                    val extras = call?.details?.intentExtras
                    val fromTelecom = extras?.getString("android.telecom.extra.INCOMING_NUMBER")
                    phoneNumber =
                            if (!fromTelecom.isNullOrEmpty()) fromTelecom
                            else
                                    call?.details
                                            ?.handle
                                            ?.toString()
                                            ?.substringAfter("tel:")
                                            ?.substringBefore("@")
                                            ?: "Unknown"
                } catch (_: Exception) {
                    phoneNumber =
                            call?.details
                                    ?.handle
                                    ?.toString()
                                    ?.substringAfter("tel:")
                                    ?.substringBefore("@")
                                    ?: "Unknown"
                }
            }

            // Use a single helper to create and launch/update the call screen safely.
            showCallScreen(phoneNumber, callState, canConference = true, canMerge = true)
        }
    }

    // Centralized helper to start/update the CallScreenActivity. Consolidating intent
    // creation and flags here reduces the chance of race conditions when multiple
    // state changes attempt to update the activity at once and guarantees consistent
    // launch flags / extras across all code paths.
    private fun showCallScreen(
            phoneNumber: String,
            callState: String,
            canConference: Boolean,
            canMerge: Boolean
    ) {
        val intent =
                Intent(this, CallScreenActivity::class.java).apply {
                    putExtra("PHONE_NUMBER", phoneNumber)
                    putExtra("CALL_STATE", callState)
                    putExtra(CallScreenActivity.EXTRA_CAN_CONFERENCE, canConference)
                    putExtra(CallScreenActivity.EXTRA_CAN_MERGE, canMerge)
                    // Use NEW_TASK | CLEAR_TOP | NO_ANIMATION when launching from InCallService
                    // to avoid freezes/crashes on locked screen (Android 14+). Remove
                    // NO_USER_ACTION and REORDER_TO_FRONT which can cause UI issues.
                    addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }

        try {
            startActivity(intent)
            Log.d(TAG, "Call screen started/updated for: $phoneNumber, state=$callState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start/update call screen", e)
        }
    }
}
