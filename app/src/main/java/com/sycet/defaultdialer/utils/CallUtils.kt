package com.sycet.defaultdialer.utils

import android.content.Context
import android.content.Intent
import android.util.Log
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

        // 3. Start call (only works when app is default dialer)
        try {
            val intent =
                    Intent(Intent.ACTION_CALL).apply {
                        data = uri
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Dialer", "Failed to place call", e)
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
