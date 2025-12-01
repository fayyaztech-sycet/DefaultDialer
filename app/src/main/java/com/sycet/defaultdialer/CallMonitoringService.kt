package com.sycet.defaultdialer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

class CallMonitoringService : Service() {
    
    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    
    private var callStartTime: Long = 0
    private var wasRinging = false
    private var wasOffHook = false
    
    companion object {
        const val TAG = "CallMonitoringService"
        var lastCallDisconnectedBy: String = "Unknown"
        var lastCallDuration: Long = 0
    }

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallbackForAndroid12Plus()
        } else {
            registerPhoneStateListenerForOlderVersions()
        }
        
        Log.d(TAG, "CallMonitoringService started")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallbackForAndroid12Plus() {
        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }
        
        try {
            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback as TelephonyCallback
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for phone state monitoring", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListenerForOlderVersions() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state)
            }
        }
        
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for phone state monitoring", e)
        }
    }

    private fun handleCallStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call ringing
                wasRinging = true
                Log.d(TAG, "Call State: RINGING")
            }
            
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call is active (outgoing or answered incoming)
                if (callStartTime == 0L) {
                    callStartTime = System.currentTimeMillis()
                }
                wasOffHook = true
                Log.d(TAG, "Call State: OFFHOOK (Active)")
            }
            
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended
                if (wasOffHook) {
                    // Call was active, now disconnected
                    lastCallDuration = if (callStartTime > 0) {
                        System.currentTimeMillis() - callStartTime
                    } else 0
                    
                    // Determine who disconnected
                    // Note: Android doesn't provide direct API to know who disconnected
                    // This is an estimation based on call duration
                    lastCallDisconnectedBy = estimateDisconnectedBy(lastCallDuration)
                    
                    Log.d(TAG, "Call disconnected. Duration: ${lastCallDuration}ms")
                    Log.d(TAG, "Estimated disconnected by: $lastCallDisconnectedBy")
                    
                    // Reset states
                    callStartTime = 0
                    wasRinging = false
                    wasOffHook = false
                } else if (wasRinging) {
                    // Call was ringing but never answered
                    lastCallDisconnectedBy = "Missed call"
                    Log.d(TAG, "Missed call")
                    wasRinging = false
                }
                
                Log.d(TAG, "Call State: IDLE")
            }
        }
    }
    
    private fun estimateDisconnectedBy(duration: Long): String {
        // This is an estimation - Android doesn't provide exact info
        // Very short calls (< 2 seconds) might be rejected/cancelled by caller
        // Longer calls are harder to determine
        return when {
            duration < 2000 -> "Likely Caller (Quick disconnect)"
            duration < 5000 -> "Unknown (Short call)"
            else -> "Unknown (Cannot determine from call duration alone)"
        }
        
        // Note: To get more accurate data, you would need:
        // 1. InCallService implementation (for Android 6+)
        // 2. Access to CallLog for more details
        // 3. Telecom framework integration
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
        
        Log.d(TAG, "CallMonitoringService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
