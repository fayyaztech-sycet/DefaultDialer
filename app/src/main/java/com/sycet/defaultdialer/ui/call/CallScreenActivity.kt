package com.sycet.defaultdialer.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.sycet.defaultdialer.services.DefaultInCallService
import com.sycet.defaultdialer.ui.theme.DefaultDialerTheme
import java.util.Locale
import kotlinx.coroutines.delay

class CallScreenActivity : ComponentActivity() {

    private var currentCall: Call? = null
    private var isFinishing = false
    private val phoneNumberState = mutableStateOf("Unknown")
    private val callStateState = mutableStateOf("Unknown")
    private val canConferenceState = mutableStateOf(false)
    private val canMergeState = mutableStateOf(false)
    private val isOnHoldState = mutableStateOf(false)
    private val callCountState = mutableStateOf(1)
    private val handler = Handler(Looper.getMainLooper())
    private var showKeypad by mutableStateOf(false)

    companion object {
        private var isActivityRunning = false
        const val EXTRA_CAN_CONFERENCE = "CAN_CONFERENCE"
        const val EXTRA_CAN_MERGE = "CAN_MERGE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent multiple instances
        if (isActivityRunning) {
            finish()
            return
        }
        isActivityRunning = true

        // Set up window flags to show over lock screen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        phoneNumberState.value = intent.getStringExtra("PHONE_NUMBER") ?: "Unknown"
        callStateState.value = intent.getStringExtra("CALL_STATE") ?: "Unknown"
        // Read the available actions from the launching intent
        canConferenceState.value = intent.getBooleanExtra(EXTRA_CAN_CONFERENCE, false)
        canMergeState.value = intent.getBooleanExtra(EXTRA_CAN_MERGE, false)

        Log.d(
                "CallScreenActivity",
                "Received Intent - Number: ${phoneNumberState.value}, State: ${callStateState.value}"
        )

        // Get the current call from the in-call service
        currentCall = DefaultInCallService.currentCall

        // Ensure phone number is populated preferably from the current Call details.
        refreshPhoneNumberFromCallOrIntent()

        // Update call count
        updateCallCount()

        // Register callback to monitor call state
        currentCall?.registerCallback(callCallback)

        // Acquire proximity wake lock by default for earpiece mode (unless speaker is already on)
        // This ensures screen turns off when phone is near face during calls
        if (callStateState.value.contains("Active", ignoreCase = true) ||
                        callStateState.value.contains("Dialing", ignoreCase = true)
        ) {
            acquireProximityWakeLock()
        }

        setContent {
            DefaultDialerTheme {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    CallScreen(
                            phoneNumber = phoneNumberState.value,
                            initialCallState = callStateState.value,
                            call = currentCall,
                            canConference = canConferenceState.value,
                            canMerge = canMergeState.value,
                            callCount = callCountState.value,
                            onAnswerCall = { answerCall() },
                            onRejectCall = { rejectCall() },
                            onEndCall = { endCall() },
                            onToggleMute = { toggleMute() },
                            onToggleSpeaker = { toggleSpeaker() },
                            isOnHold = isOnHoldState.value,
                            onToggleHold = { toggleHold() },
                            onConference = { onConference() },
                            onMerge = { onMerge() },
                            onAddCall = { onAddCall() },
                            onSendDtmf = { digit -> sendDtmf(digit) },
                            showKeypad = showKeypad,
                            onToggleKeypad = { showKeypad = !showKeypad },
                            getContactName = { number -> getContactName(number) }
                    )
                }
            }
        }
    }

    // AudioManager for mute / speaker control
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Proximity wake lock to turn screen off when phone is near face (earpiece mode)
    private var proximityWakeLock: PowerManager.WakeLock? = null

    // Audio focus helpers (used to ensure speakerphone changes take effect reliably)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private val afChangeListener =
            AudioManager.OnAudioFocusChangeListener { focusChange ->
                // We don't need fine-grained handling, just log for diagnostics
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN ->
                            Log.d("CallScreenActivity", "Audio focus gained")
                    AudioManager.AUDIOFOCUS_LOSS -> Log.d("CallScreenActivity", "Audio focus lost")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                            Log.d("CallScreenActivity", "Audio focus lost transient")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            Log.d("CallScreenActivity", "Audio focus lost transient (duck)")
                    else -> Log.d("CallScreenActivity", "Audio focus changed: $focusChange")
                }
            }

    private fun requestAudioFocusIfNeeded() {
        if (hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val aa =
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()

                val req =
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                .setAudioAttributes(aa)
                                .setAcceptsDelayedFocusGain(false)
                                .setOnAudioFocusChangeListener(afChangeListener)
                                .build()

                val status = audioManager.requestAudioFocus(req)
                audioFocusRequest = req
                hasAudioFocus = status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                // If transient focus was not granted, try requesting a non-transient (longer) focus
                // as a fallback
                if (!hasAudioFocus) {
                    Log.d(
                            "CallScreenActivity",
                            "Transient audio focus denied, trying AUDIOFOCUS_GAIN"
                    )
                    val req2 =
                            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                    .setAudioAttributes(aa)
                                    .setAcceptsDelayedFocusGain(false)
                                    .setOnAudioFocusChangeListener(afChangeListener)
                                    .build()
                    val status2 = audioManager.requestAudioFocus(req2)
                    if (status2 == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        audioFocusRequest = req2
                        hasAudioFocus = true
                    } else {
                        Log.w("CallScreenActivity", "AUDIOFOCUS_GAIN also denied on fallback (O+)")
                    }
                }
            } else {
                val status =
                        audioManager.requestAudioFocus(
                                afChangeListener,
                                AudioManager.STREAM_VOICE_CALL,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                        )
                hasAudioFocus = status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

                // fallback: if transient focus denied, request persistent focus
                if (!hasAudioFocus) {
                    Log.d(
                            "CallScreenActivity",
                            "Transient audio focus denied (pre-O), trying AUDIOFOCUS_GAIN"
                    )
                    val status2 =
                            audioManager.requestAudioFocus(
                                    afChangeListener,
                                    AudioManager.STREAM_VOICE_CALL,
                                    AudioManager.AUDIOFOCUS_GAIN
                            )
                    hasAudioFocus = status2 == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!hasAudioFocus) {
                        Log.w("CallScreenActivity", "AUDIOFOCUS_GAIN denied (pre-O fallback)")
                    }
                }
            }
            Log.d("CallScreenActivity", "requestAudioFocusIfNeeded - granted=$hasAudioFocus")
            if (!hasAudioFocus) {
                // Provide more diagnostics to help understand why focus was denied on some devices
                try {
                    Log.w(
                            "CallScreenActivity",
                            "Audio focus denied — diagnostics: mode=${audioManager.mode}, speaker=${audioManager.isSpeakerphoneOn}, btSco=${audioManager.isBluetoothScoOn}, btA2dp=${audioManager.isBluetoothA2dpOn}, musicActive=${audioManager.isMusicActive}, voiceVol=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}"
                    )
                } catch (e: Exception) {
                    Log.w("CallScreenActivity", "Failed to print audio diagnostics", e)
                }
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "requestAudioFocusIfNeeded failed", e)
        }
    }

    private fun abandonAudioFocusIfNeeded() {
        if (!hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                audioManager.abandonAudioFocus(afChangeListener)
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "abandonAudioFocusIfNeeded failed", e)
        } finally {
            hasAudioFocus = false
            Log.d("CallScreenActivity", "abandonAudioFocusIfNeeded - released")
        }
    }

    /**
     * Acquire proximity wake lock to turn screen off when phone is near face. This prevents
     * accidental touches when using earpiece.
     */
    private fun acquireProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                Log.d("CallScreenActivity", "Proximity wake lock already held")
                return
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            "CallScreenActivity::ProximityWakeLock"
                    )
            // Acquire with 10 minute timeout (typical call duration)
            proximityWakeLock?.acquire(10 * 60 * 1000L)
            Log.d(
                    "CallScreenActivity",
                    "Proximity wake lock acquired - screen will turn off when near face"
            )
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to acquire proximity wake lock", e)
        }
    }

    /**
     * Release proximity wake lock to allow screen to stay on. Used when speaker is enabled or call
     * ends.
     */
    private fun releaseProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
                proximityWakeLock = null
                Log.d("CallScreenActivity", "Proximity wake lock released - screen can stay on")
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to release proximity wake lock", e)
        }
    }

    /**
     * Set speakerphone on/off in a robust way:
     * - ensures audio focus
     * - selects an appropriate audio mode for communications
     */
    private fun setSpeakerphoneOn(enabled: Boolean) {
        try {
            requestAudioFocusIfNeeded()

            // prefer MODE_IN_COMMUNICATION for in-app/telecom routing to allow speakerphone
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // If we couldn't get audio focus, attempt a more aggressive fallback to ensure routing
            if (!hasAudioFocus) {
                Log.d("CallScreenActivity", "Audio focus not granted — running speaker fallback")

                // If a BT SCO connection is active, stop it first to allow switching to loudspeaker
                try {
                    if (audioManager.isBluetoothScoOn) {
                        Log.d(
                                "CallScreenActivity",
                                "Bluetooth SCO ON — stopping SCO to force speaker routing"
                        )
                        audioManager.stopBluetoothSco()
                        audioManager.setBluetoothScoOn(false)
                    }
                } catch (e: Exception) {
                    Log.w("CallScreenActivity", "Failed toggling Bluetooth SCO during fallback", e)
                }

                // Ensure speaker property is set, then try alternate modes if needed
                audioManager.isSpeakerphoneOn = enabled
                if (audioManager.isSpeakerphoneOn != enabled) {
                    // try fallback to MODE_IN_CALL which some vendors expect
                    Log.d(
                            "CallScreenActivity",
                            "Speakerstate did not take — trying MODE_IN_CALL fallback"
                    )
                    try {
                        audioManager.mode = AudioManager.MODE_IN_CALL
                    } catch (_: Exception) {}
                    audioManager.isSpeakerphoneOn = enabled
                }
            } else {
                // normal path
                audioManager.isSpeakerphoneOn = enabled
            }

            Log.d(
                    "CallScreenActivity",
                    "setSpeakerphoneOn -> $enabled (mode=${audioManager.mode}) speaker=${audioManager.isSpeakerphoneOn} btSco=${audioManager.isBluetoothScoOn}"
            )
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to toggle speakerphone", e)
        }
    }

    private val callCallback =
            object : Call.Callback() {
                override fun onStateChanged(call: Call?, state: Int) {
                    when (state) {
                        Call.STATE_ACTIVE -> {
                            callStateState.value = "Active"
                            // Refresh phone number when the call becomes active
                            refreshPhoneNumberFromCallOrIntent()
                            // Acquire proximity wake lock for active call (earpiece mode)
                            acquireProximityWakeLock()
                            isOnHoldState.value = false
                            updateCallCount()
                        }
                        Call.STATE_HOLDING -> {
                            isOnHoldState.value = true
                            updateCallCount()
                        }
                        Call.STATE_DISCONNECTING -> {
                            Log.d("CallScreenActivity", "Call is disconnecting...")
                            callStateState.value = "Disconnecting..."
                            updateCallCount()
                        }
                        Call.STATE_DISCONNECTED -> {
                            val disconnectCause = call?.details?.disconnectCause
                            Log.d(
                                    "CallScreenActivity",
                                    "Call disconnected: ${disconnectCause?.reason}"
                            )
                            callStateState.value = "Disconnected"
                            updateCallCount()

                            // Only call endCall if we're not already finishing
                            if (!isFinishing) {
                                handler.postDelayed({ endCall() }, 100)
                            }
                        }
                    }
                }
            }

    private fun answerCall() {
        try {
            currentCall?.answer(0)
            callStateState.value = "Connecting..."
            try {
                // ensure we have audio focus and prefer communication mode so speaker routing works
                // reliably
                requestAudioFocusIfNeeded()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                // Acquire proximity wake lock for earpiece mode (default)
                acquireProximityWakeLock()
            } catch (e: Exception) {
                Log.w("CallScreenActivity", "Unable to set audio mode to IN_CALL", e)
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to answer call", e)
        }
    }

    private fun rejectCall() {
        if (isFinishing) return
        isFinishing = true

        Log.d("CallScreenActivity", "Attempting to reject call")

        var rejected = false

        // Try to reject the call
        try {
            currentCall?.let {
                it.reject(false, null)
                Log.d("CallScreenActivity", "Call.reject() called")
                rejected = true
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to reject call", e)
        }

        // If reject failed, try disconnect
        if (!rejected) {
            try {
                currentCall?.disconnect()
                Log.d("CallScreenActivity", "Call.disconnect() called as fallback")
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to disconnect call", e)
            }
        }

        // Delay before finishing
        handler.postDelayed({ finish() }, 200)
    }

    private fun endCall() {
        if (isFinishing) return
        isFinishing = true

        Log.d("CallScreenActivity", "Attempting to end call")

        try {
            // Reset audio settings when ending call
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
            // release any audio focus we might have acquired
            abandonAudioFocusIfNeeded()
            // release proximity wake lock
            releaseProximityWakeLock()
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to reset audio settings", e)
        }

        // Try multiple methods to disconnect the call
        var disconnected = false

        // Method 1: Disconnect current call
        try {
            currentCall?.let {
                it.disconnect()
                Log.d("CallScreenActivity", "Call.disconnect() called on currentCall")
                disconnected = true
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to disconnect currentCall", e)
        }

        // Method 2: Disconnect all calls from service
        if (!disconnected) {
            try {
                val allCalls = DefaultInCallService.getAllCalls()
                allCalls.forEach { call ->
                    try {
                        call.disconnect()
                        Log.d("CallScreenActivity", "Call.disconnect() called on service call")
                    } catch (e: Exception) {
                        Log.e("CallScreenActivity", "Failed to disconnect service call", e)
                    }
                }
                if (allCalls.isNotEmpty()) {
                    disconnected = true
                }
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to get calls from service", e)
            }
        }

        // Method 3: Use TelecomManager as last resort
        if (!disconnected) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager =
                            getSystemService(Context.TELECOM_SERVICE) as?
                                    android.telecom.TelecomManager
                    telecomManager?.endCall()
                    Log.d("CallScreenActivity", "TelecomManager.endCall() called")
                }
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to end call via TelecomManager", e)
            }
        }

        // Give the disconnect a moment to process before finishing
        handler.postDelayed(
                {
                    // Use finishAndRemoveTask to completely close and remove from recents
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                },
                200
        )
    }

    private fun toggleMute() {
        try {
            // Ask the InCallService to toggle microphone mute. AudioManager used from
            // InCallService is the reliable place to perform telecom audio routing and
            // microphone toggles.
            val newMuted = !audioManager.isMicrophoneMute
            DefaultInCallService.muteCall(newMuted)
            Log.d("CallScreenActivity", "Requested mute -> $newMuted")
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Mute failed", e)
        }
    }

    private fun toggleSpeaker() {
        try {
            @Suppress("DEPRECATION") val currentSpeakerState = audioManager.isSpeakerphoneOn
            val newState = !currentSpeakerState

            // Use InCallService's setSpeaker which will use setAudioRoute() properly
            DefaultInCallService.setSpeaker(newState)

            // Manage proximity wake lock based on speaker state
            if (newState) {
                // Speaker ON - release proximity lock to keep screen on
                releaseProximityWakeLock()
            } else {
                // Speaker OFF (earpiece) - acquire proximity lock to turn screen off near face
                acquireProximityWakeLock()
            }

            @Suppress("DEPRECATION")
            Log.d(
                    "CallScreenActivity",
                    "Requested speaker -> $newState (actual=${audioManager.isSpeakerphoneOn})"
            )
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Speaker toggle failed", e)
        }
    }

    private fun toggleHold() {
        try {
            if (isOnHoldState.value) {
                currentCall?.unhold()
                Log.d("CallScreenActivity", "Requested unhold")
            } else {
                currentCall?.hold()
                Log.d("CallScreenActivity", "Requested hold")
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Hold toggle failed", e)
        }
    }

    private fun sendDtmf(digit: Char) {
        try {
            currentCall?.playDtmfTone(digit)
            handler.postDelayed({ currentCall?.stopDtmfTone() }, 100)
            Log.d("CallScreenActivity", "Sent DTMF: $digit")
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "DTMF failed", e)
        }
    }

    private fun getContactName(phoneNumber: String): String? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri =
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                        .buildUpon()
                        .appendPath(phoneNumber)
                        .build()

        var contactName: String? = null
        val cursor: Cursor? =
                contentResolver.query(
                        uri,
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                        null,
                        null,
                        null
                )

        cursor?.use {
            if (it.moveToFirst()) {
                contactName = it.getString(0)
            }
        }

        return contactName
    }

    /** Update the call count and determine if merge option should be available */
    private fun updateCallCount() {
        handler.post {
            try {
                val activeCallCount = DefaultInCallService.getActiveCallCount()
                callCountState.value = maxOf(activeCallCount, 1)

                // Show merge option if there are 2+ calls
                canMergeState.value = activeCallCount >= 2

                Log.d(
                        "CallScreenActivity",
                        "Active calls: $activeCallCount, canMerge: ${canMergeState.value}"
                )
            } catch (e: Exception) {
                Log.w("CallScreenActivity", "Failed to get call count: ${e.message}")
                callCountState.value = 1
                canMergeState.value = false
            }
        }
    }

    /**
     * Ensures phoneNumberState is populated. Prefer the number from the active Call's handle
     * (schemeSpecificPart) and fall back to the launching intent extra if needed.
     */
    private fun refreshPhoneNumberFromCallOrIntent() {
        val numberFromCall = currentCall?.details?.handle?.schemeSpecificPart
        if (!numberFromCall.isNullOrBlank()) {
            if (phoneNumberState.value != numberFromCall) {
                phoneNumberState.value = numberFromCall
                Log.d("CallScreenActivity", "Using number from Call details: $numberFromCall")
            }
            return
        }

        // If call doesn't provide a number, fall back to intent extra if available
        val numberFromIntent = intent.getStringExtra("PHONE_NUMBER")
        if (!numberFromIntent.isNullOrBlank() && phoneNumberState.value != numberFromIntent) {
            phoneNumberState.value = numberFromIntent
            Log.d("CallScreenActivity", "Using number from Intent: $numberFromIntent")
        }
    }

    override fun onDestroy() {
        // ensure audio is returned to normal when the activity is destroyed
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
            // Ensure we release audio focus when activity is destroyed
            abandonAudioFocusIfNeeded()
            // Ensure we release proximity wake lock when activity is destroyed
            releaseProximityWakeLock()
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to reset audio manager on destroy", e)
        }
        currentCall?.unregisterCallback(callCallback)
        isActivityRunning = false
        isFinishing = false
        Log.d("CallScreenActivity", "Activity destroyed and cleaned up")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important: update the activity's intent

        // Handle new intent if activity is already running
        val newPhoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: "Unknown"
        val newCallState = intent.getStringExtra("CALL_STATE") ?: "Unknown"
        val newCanConference = intent.getBooleanExtra(EXTRA_CAN_CONFERENCE, false)
        val newCanMerge = intent.getBooleanExtra(EXTRA_CAN_MERGE, false)

        Log.d("CallScreenActivity", "onNewIntent - Number: $newPhoneNumber, State: $newCallState")

        // Update states which will trigger recomposition (phone number is set from call/details
        // when available)
        callStateState.value = newCallState
        canConferenceState.value = newCanConference
        canMergeState.value = newCanMerge

        // Update the UI with new call information
        callCallback?.let { currentCall?.unregisterCallback(it) }
        currentCall = DefaultInCallService.currentCall

        // Refresh phone number preference (prefer currentCall details over intent extras)
        refreshPhoneNumberFromCallOrIntent()

        callCallback?.let { currentCall?.registerCallback(it) }

        // Reset finishing flag to allow new call handling
        isFinishing = false
    }

    // Conference action — placeholder (logs only). Real conference/merge requires telecom provider
    // support.
    private fun onConference() {
        Log.d(
                "CallScreenActivity",
                "Conference action requested — canConference=${canConferenceState.value}"
        )

        if (!canConferenceState.value) {
            Log.d("CallScreenActivity", "Conference not available for this call")
            return
        }

        try {
            // Stub: actual conference operation would require Telecom/ConnectionService integration
            Log.d(
                    "CallScreenActivity",
                    "(Stub) Performing conference operation on currentCall: $currentCall"
            )
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to perform conference", e)
        }
    }

    private fun onMerge() {
        Log.d("CallScreenActivity", "Merge action requested — canMerge=${canMergeState.value}")

        if (!canMergeState.value) {
            Log.d("CallScreenActivity", "Merge not available for this call")
            return
        }

        try {
            // Get all active calls and attempt to conference them
            val calls = DefaultInCallService.getAllCalls()
            if (calls.size >= 2) {
                // Conference the calls together
                val firstCall = calls[0]
                val secondCall = calls[1]

                Log.d("CallScreenActivity", "Attempting to conference ${calls.size} calls")

                // Use the conference method to merge calls
                firstCall.conference(secondCall)

                Log.d("CallScreenActivity", "Conference request sent")
            } else {
                Log.d("CallScreenActivity", "Not enough calls to merge: ${calls.size}")
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to perform merge", e)
        }
    }

    private fun onAddCall() {
        Log.d("CallScreenActivity", "Add call action requested")
        try {
            // Open dialer screen to make a new call
            val dialerIntent =
                    Intent(this, com.sycet.defaultdialer.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("SHOW_DIALER", true)
                    }
            startActivity(dialerIntent)
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to open dialer for add call", e)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, secs)
    }
}

@Composable
fun Keypad(onSendDtmf: (Char) -> Unit, onClose: () -> Unit) {
    val keypadButtons =
            listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("*", "0", "#")
            )

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Close button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) {
                Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close Keypad",
                        tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        keypadButtons.forEach { row ->
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { digit ->
                    FloatingActionButton(
                            onClick = { onSendDtmf(digit[0]) },
                            modifier = Modifier.size(64.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                                text = digit,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun CallScreen(
        phoneNumber: String,
        initialCallState: String,
        call: Call?,
        onAnswerCall: () -> Unit,
        onRejectCall: () -> Unit,
        onEndCall: () -> Unit,
        onToggleMute: () -> Unit,
        onToggleSpeaker: () -> Unit,
        isOnHold: Boolean = false,
        onToggleHold: () -> Unit = {},
        canConference: Boolean = false,
        canMerge: Boolean = false,
        callCount: Int = 1,
        onConference: () -> Unit = {},
        onMerge: () -> Unit = {},
        onAddCall: () -> Unit = {},
        onSendDtmf: (Char) -> Unit = {},
        showKeypad: Boolean,
        onToggleKeypad: () -> Unit,
        getContactName: (String) -> String?
) {
    var callState by remember { mutableStateOf(initialCallState) }
    var elapsedTime by remember { mutableLongStateOf(0L) }
    var isActive by remember {
        mutableStateOf(initialCallState.contains("Active", ignoreCase = true))
    }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isRinging by remember {
        mutableStateOf(
                initialCallState.contains("Incoming", ignoreCase = true) ||
                        initialCallState.contains("Ringing", ignoreCase = true)
        )
    }

    // Prefer the number from the active Call's handle if available, otherwise use the passed
    // phoneNumber
    val resolvedNumber =
            call?.details?.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: phoneNumber

    // Fetch contact name for the resolved number (if permission is granted). Recompute when number
    // changes.
    val contactName = remember(resolvedNumber) { getContactName(resolvedNumber) }

    // Display name: show contact name if available; otherwise show the resolved number
    val displayName =
            if (!contactName.isNullOrBlank() && resolvedNumber != "Unknown") contactName
            else resolvedNumber

    // Monitor call state from the Call object
    DisposableEffect(call) {
        val callback =
                object : Call.Callback() {
                    override fun onStateChanged(call: Call?, state: Int) {
                        when (state) {
                            Call.STATE_ACTIVE -> {
                                callState = "Active"
                                isActive = true
                                isRinging = false
                            }
                            Call.STATE_DISCONNECTED -> {
                                val disconnectCause = call?.details?.disconnectCause
                                Log.d(
                                        "CallScreen",
                                        "Disconnected: ${disconnectCause?.reason}, Code: ${disconnectCause?.code}"
                                )

                                // Close screen for missed calls or any disconnect
                                onEndCall()
                            }
                            Call.STATE_RINGING -> {
                                isRinging = true
                                isActive = false
                            }
                        }
                    }
                }

        call?.registerCallback(callback)

        onDispose { call?.unregisterCallback(callback) }
    }

    // Timer effect for call duration
    LaunchedEffect(isActive) {
        while (isActive) {
            delay(1000)
            elapsedTime += 1
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section - Contact info
            Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 64.dp)
            ) {
                // Contact avatar
                Box(
                        modifier =
                                Modifier.size(120.dp)
                                        .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = CircleShape
                                        ),
                        contentAlignment = Alignment.Center
                ) {
                    Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Contact",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Contact name (or number if no contact) — large
                Text(
                        text = displayName,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                )

                // Always show the phone number (compulsory requirement).
                // If displayName already equals the number, this will duplicate; that's acceptable
                // to guarantee the number is visible. Use a smaller, secondary-style text.
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                        text = resolvedNumber,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Call state
                Text(
                        text = callState,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Call duration
                if (isActive) {
                    Text(
                            text = formatDuration(elapsedTime),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Bottom section - Call controls
            Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 32.dp)
            ) {
                // Show answer/reject buttons for incoming calls
                if (isRinging) {
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Reject button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                    onClick = onRejectCall,
                                    modifier = Modifier.size(72.dp),
                                    containerColor = Color(0xFFE53935)
                            ) {
                                Icon(
                                        imageVector = Icons.Default.CallEnd,
                                        contentDescription = "Reject",
                                        modifier = Modifier.size(32.dp),
                                        tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                    text = "Reject",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Answer button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                    onClick = onAnswerCall,
                                    modifier = Modifier.size(72.dp),
                                    containerColor = Color(0xFF4CAF50)
                            ) {
                                Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = "Answer",
                                        modifier = Modifier.size(32.dp),
                                        tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                    text = "Answer",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Control buttons for active call
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                    onClick = {
                                        isMuted = !isMuted
                                        onToggleMute()
                                    },
                                    modifier =
                                            Modifier.size(64.dp)
                                                    .background(
                                                            color =
                                                                    if (isMuted)
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .primary
                                                                    else
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surfaceVariant,
                                                            shape = CircleShape
                                                    )
                            ) {
                                Icon(
                                        imageVector =
                                                if (isMuted) Icons.Default.MicOff
                                                else Icons.Default.Mic,
                                        contentDescription = "Mute",
                                        tint =
                                                if (isMuted) Color.White
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                    text = if (isMuted) "Unmute" else "Mute",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Hold button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                    onClick = onToggleHold,
                                    modifier =
                                            Modifier.size(64.dp)
                                                    .background(
                                                            color =
                                                                    if (isOnHold)
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .primary
                                                                    else
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surfaceVariant,
                                                            shape = CircleShape
                                                    )
                            ) {
                                Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = "Hold",
                                        tint =
                                                if (isOnHold) Color.White
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                    text = if (isOnHold) "Resume" else "Hold",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Keypad button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                    onClick = onToggleKeypad,
                                    modifier =
                                            Modifier.size(64.dp)
                                                    .background(
                                                            color =
                                                                    if (showKeypad)
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .primary
                                                                    else
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surfaceVariant,
                                                            shape = CircleShape
                                                    )
                            ) {
                                Icon(
                                        imageVector = Icons.Filled.Dialpad,
                                        contentDescription = "Keypad",
                                        tint =
                                                if (showKeypad) Color.White
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                    text = "Keypad",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Conference or Add Call button based on call count
                        if (isActive) {
                            if (callCount >= 2 && canMerge) {
                                // Show Merge button when 2+ calls active
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                            onClick = { onMerge() },
                                            modifier =
                                                    Modifier.size(64.dp)
                                                            .background(
                                                                    color =
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surfaceVariant,
                                                                    shape = CircleShape
                                                            )
                                    ) {
                                        Icon(
                                                imageVector = Icons.Default.Call,
                                                contentDescription = "Merge",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                            text = "Merge",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                // Show Add Call button when single call active
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                            onClick = { onAddCall() },
                                            modifier =
                                                    Modifier.size(64.dp)
                                                            .background(
                                                                    color =
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surfaceVariant,
                                                                    shape = CircleShape
                                                            )
                                    ) {
                                        Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = "Add Call",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                            text = "Add Call",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Speaker button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                    onClick = {
                                        isSpeakerOn = !isSpeakerOn
                                        onToggleSpeaker()
                                    },
                                    modifier =
                                            Modifier.size(64.dp)
                                                    .background(
                                                            color =
                                                                    if (isSpeakerOn)
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .primary
                                                                    else
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surfaceVariant,
                                                            shape = CircleShape
                                                    )
                            ) {
                                Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Speaker",
                                        tint =
                                                if (isSpeakerOn) Color.White
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                    text = "Speaker",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    // End call button
                    FloatingActionButton(
                            onClick = onEndCall,
                            modifier = Modifier.size(72.dp),
                            containerColor = Color(0xFFE53935)
                    ) {
                        Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                modifier = Modifier.size(32.dp),
                                tint = Color.White
                        )
                    }

                    // Keypad
                    if (showKeypad) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Keypad(onSendDtmf = onSendDtmf, onClose = onToggleKeypad)
                    }
                }
            }
        }
    }
}
