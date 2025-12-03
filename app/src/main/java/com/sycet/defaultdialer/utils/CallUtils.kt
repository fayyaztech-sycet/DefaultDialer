package com.sycet.defaultdialer.utils

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object CallUtils {
    private const val TAG = "CallUtils"

    /**
     * Place a phone call using ACTION_CALL. Performs lightweight sanitization and strips any
     * leading '+' so the telecom stack receives a clean numeric tel: handle (tel:1234567890). Uses
     * Uri.fromParts to avoid percent-encoding.
     */
    fun placeCall(context: Context, rawNumber: String) {
        if (rawNumber.isBlank()) {
            Log.w("Dialer", "Blocked call: number empty")
            return
        }

        // 1. Clean number (telecom standard)
        val cleaned =
                rawNumber
                        .trim()
                        .replace("\\s+".toRegex(), "") // spaces
                        .replace("[().-]".toRegex(), "") // brackets / dashes / dots
                        .let { number ->
                            if (number.startsWith("+")) {
                                "+" + number.drop(1).replace("[^0-9]".toRegex(), "")
                            } else {
                                number.replace("[^0-9]".toRegex(), "")
                            }
                        }

        if (cleaned.isBlank()) {
            Log.w("Dialer", "Blocked call: cleaned number invalid → $rawNumber")
            return
        }

        Log.d("Dialer", "Calling cleaned='$cleaned' original='$rawNumber'")

        // 2. Build tel: URI
        val uri = "tel:$cleaned".toUri()

        // 3. Preferred flow: when app is the default dialer, use TelecomManager.placeCall
        try {
            val pm = context.packageManager

            // If we're the default dialer (Android Q+), use TelecomManager.placeCall which is
            // the recommended API for default phone apps.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                val isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false

                if (isDefaultDialer) {
                    try {
                        val telecom = context.getSystemService(TelecomManager::class.java)
                        telecom?.placeCall(uri, Bundle())
                        Log.d(TAG, "Placed call using TelecomManager.placeCall: $uri")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "TelecomManager.placeCall failed, falling back: ${e.message}", e)
                        // fall through to other strategies
                    }
                }
            }

            // Otherwise try ACTION_CALL when we have the CALL_PHONE permission and an activity
            val hasCallPhone =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                            PackageManager.PERMISSION_GRANTED

            if (hasCallPhone) {
                val callIntent =
                        Intent(Intent.ACTION_CALL).apply {
                            data = uri
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                if (callIntent.resolveActivity(pm) != null) {
                    context.startActivity(callIntent)
                    Log.d(TAG, "Placed call using ACTION_CALL: $uri")
                    return
                }
            }

            // Fallback to ACTION_DIAL — presents dialer UI with the number filled-in so the user
            // can confirm/complete the call. Works regardless of default-dialer role.
            val dialIntent =
                    Intent(Intent.ACTION_DIAL).apply {
                        data = uri
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }

            if (dialIntent.resolveActivity(pm) != null) {
                context.startActivity(dialIntent)
                Log.d(TAG, "Opened ACTION_DIAL as fallback: $uri")
                return
            }

            Log.e(TAG, "No available activity to place or dial call for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call", e)
        }
    }

    /**
     * Try to place a call if CALL_PHONE permission is already granted, otherwise invoke the
     * provided requestPermission lambda so the caller can trigger a permission request (e.g.
     * ActivityResultLauncher.launch).
     *
     * This keeps permission request flows out of utils (utils cannot hold an
     * ActivityResultLauncher) while still centralizing the permission check.
     */
    fun placeCallWithPermission(
            context: Context,
            rawNumber: String,
            requestPermission: () -> Unit
    ) {
        val permissionGranted =
                android.content.pm.PackageManager.PERMISSION_GRANTED ==
                        androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.CALL_PHONE
                        )

        if (permissionGranted) {
            placeCall(context, rawNumber)
        } else {
            Log.d(TAG, "Call permission missing; requesting permission for number='$rawNumber'")
            requestPermission()
        }
    }
}
