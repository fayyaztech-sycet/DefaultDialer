package com.sycet.defaultdialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import com.sycet.defaultdialer.ui.theme.DefaultDialerTheme
import kotlinx.coroutines.delay

class CallScreenActivity : ComponentActivity() {
    
    private var currentCall: Call? = null
    private var isFinishing = false
    private val phoneNumberState = mutableStateOf("Unknown")
    private val callStateState = mutableStateOf("Unknown")
    
    companion object {
        private var isActivityRunning = false
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
        
        Log.d("CallScreenActivity", "Received Intent - Number: ${phoneNumberState.value}, State: ${callStateState.value}")
        
        // Get the current call from CallScreeningService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            currentCall = CallScreeningService.currentCall
            
            // If still Unknown, try to get from current call
            if (phoneNumberState.value == "Unknown" && currentCall != null) {
                val number = currentCall?.details?.handle?.schemeSpecificPart
                if (!number.isNullOrEmpty()) {
                    phoneNumberState.value = number
                    Log.d("CallScreenActivity", "Retrieved number from currentCall: $number")
                }
            }
            
            // Register callback to monitor call state
            currentCall?.registerCallback(callCallback)
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
                        onAnswerCall = { answerCall() },
                        onRejectCall = { rejectCall() },
                        onEndCall = { endCall() },
                        onToggleMute = { toggleMute() },
                        onToggleSpeaker = { toggleSpeaker() },
                        getContactName = { number -> getContactName(number) }
                    )
                }
            }
        }
    }
    
    private val callCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : Call.Callback() {
            override fun onStateChanged(call: Call?, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> {
                        callStateState.value = "Active"
                    }
                    Call.STATE_DISCONNECTED -> {
                        val disconnectCause = call?.details?.disconnectCause
                        Log.d("CallScreenActivity", "Call disconnected: ${disconnectCause?.reason}")
                        endCall()
                    }
                }
            }
        }
    } else null
    
    private fun answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                currentCall?.answer(0)
                callStateState.value = "Connecting..."
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to answer call", e)
            }
        }
    }
    
    private fun rejectCall() {
        if (isFinishing) return
        isFinishing = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                currentCall?.reject(false, null)
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to reject call", e)
            }
        }
        finish()
    }
    
    private fun endCall() {
        if (isFinishing) return
        isFinishing = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                currentCall?.disconnect()
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to disconnect call", e)
            }
        }
        finish()
    }
    
    private fun toggleMute() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            currentCall?.let { call ->
                val isMuted = call.details.hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL)
                // Toggle mute state
                // Note: Actual mute implementation requires audio manager
            }
        }
    }
    
    private fun toggleSpeaker() {
        // Toggle speaker implementation
        // Requires AudioManager configuration
    }
    
    private fun getContactName(phoneNumber: String): String? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(phoneNumber)
            .build()
        
        var contactName: String? = null
        val cursor: Cursor? = contentResolver.query(
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
    
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            callCallback?.let { currentCall?.unregisterCallback(it) }
        }
        isActivityRunning = false
        isFinishing = false
        super.onDestroy()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important: update the activity's intent
        
        // Handle new intent if activity is already running
        val newPhoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: "Unknown"
        val newCallState = intent.getStringExtra("CALL_STATE") ?: "Unknown"
        
        Log.d("CallScreenActivity", "onNewIntent - Number: $newPhoneNumber, State: $newCallState")
        
        // Update states which will trigger recomposition
        phoneNumberState.value = newPhoneNumber
        callStateState.value = newCallState
        
        // Update the UI with new call information
        callCallback?.let { currentCall?.unregisterCallback(it) }
        currentCall = CallScreeningService.currentCall

        // Try to get number from call if still Unknown
        if (phoneNumberState.value == "Unknown" && currentCall != null) {
            val number = currentCall?.details?.handle?.schemeSpecificPart
            if (!number.isNullOrEmpty()) {
                phoneNumberState.value = number
                Log.d("CallScreenActivity", "onNewIntent - Retrieved from call: $number")
            }
        }

        callCallback?.let { currentCall?.registerCallback(it) }

        // Reset finishing flag to allow new call handling
        isFinishing = false
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
    getContactName: (String) -> String?
) {
    var callState by remember { mutableStateOf(initialCallState) }
    var elapsedTime by remember { mutableLongStateOf(0L) }
    var isActive by remember { mutableStateOf(initialCallState.contains("Active", ignoreCase = true)) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isRinging by remember { mutableStateOf(initialCallState.contains("Incoming", ignoreCase = true) || initialCallState.contains("Ringing", ignoreCase = true)) }
    
    val contactName = remember(phoneNumber) { getContactName(phoneNumber) }
    val displayName = if (contactName != null && phoneNumber != "Unknown") contactName else phoneNumber
    
    // Monitor call state from the Call object
    DisposableEffect(call) {
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                            Log.d("CallScreen", "Disconnected: ${disconnectCause?.reason}, Code: ${disconnectCause?.code}")
                            
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
        } else null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && callback != null) {
            call?.registerCallback(callback)
        }
        
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && callback != null) {
                call?.unregisterCallback(callback)
            }
        }
    }
    
    // Timer effect for call duration
    LaunchedEffect(isActive) {
        while (isActive) {
            delay(1000)
            elapsedTime += 1
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
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
                    modifier = Modifier
                        .size(120.dp)
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
                
                // Contact name or number
                Text(
                    text = displayName,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
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
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(
                                        color = if (isMuted) MaterialTheme.colorScheme.primary 
                                               else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Mute",
                                    tint = if (isMuted) Color.White 
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
                        
                        // Speaker button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = {
                                    isSpeakerOn = !isSpeakerOn
                                    onToggleSpeaker()
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(
                                        color = if (isSpeakerOn) MaterialTheme.colorScheme.primary 
                                               else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Speaker",
                                    tint = if (isSpeakerOn) Color.White 
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
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}
