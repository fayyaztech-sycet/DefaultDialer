package com.sycet.defaultdialer.ui.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.sycet.defaultdialer.services.CallStateObserverService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sycet.defaultdialer.utils.CallUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen() {
    var phoneNumber by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Role manager for setting as default dialer (Android 10+)
    val roleManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(RoleManager::class.java)
            } else null

    val defaultDialerLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                // Check if app is now the default dialer
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false
                    // You can show a toast or update UI based on this
                }
            }

    val callPermissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    makePhoneCall(context, phoneNumber)
                }
            }
    
    // Multiple permissions launcher for call monitoring
    val multiplePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Start call monitoring service
            val serviceIntent = Intent(context, CallStateObserverService::class.java)
            context.startService(serviceIntent)
        }
    }

    // Check if app is default dialer on launch
    LaunchedEffect(Unit) {
        // Diagnostic checks: determine whether the app is eligible to handle
        // tel: ACTION_DIAL/ACTION_CALL and whether RoleManager request intent is
        // available. These logs help troubleshoot why the system may not show
        // the app as a candidate for the Phone (dialer) role.
        try {
            val pm = context.packageManager
            val pkg = context.packageName

            val testDial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:123"))
            val testCall = Intent(Intent.ACTION_CALL, Uri.parse("tel:123"))

            val dialResolved = pm.queryIntentActivities(testDial, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == pkg }
            val callResolved = pm.queryIntentActivities(testCall, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == pkg }

            val roleRequestIntent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)

            android.util.Log.d("Dialer-DEBUG", "eligible: dialResolved=$dialResolved, callResolved=$callResolved, roleIntentNull=${roleRequestIntent==null}")
        } catch (e: Exception) {
            android.util.Log.w("Dialer-DEBUG", "eligiblity check failed", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false
            if (!isDefaultDialer) {
                // Request to be set as default dialer
                val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                intent?.let { defaultDialerLauncher.launch(it) }
                android.util.Log.d("Dialer-DEBUG", "App is not default dialer; tried to launch role request intent: ${intent != null}")
            } else {
                // If already default dialer, request call monitoring permissions
                requestCallMonitoringPermissions(context, multiplePermissionsLauncher)
            }
        } else {
            // For older Android versions, just request call monitoring permissions
            requestCallMonitoringPermissions(context, multiplePermissionsLauncher)
        }
    }

    // Simple debug indicator visible in the UI to show default-role eligibility
    var eligibleText by remember { mutableStateOf("Checking default-eligibility…") }
    LaunchedEffect(Unit) {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            val testDial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:123"))
            val testCall = Intent(Intent.ACTION_CALL, Uri.parse("tel:123"))
            val dialResolved = pm.queryIntentActivities(testDial, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == pkg }
            val callResolved = pm.queryIntentActivities(testCall, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == pkg }
            val roleRequestIntent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            eligibleText = "Dial:$dialResolved Call:$callResolved RoleIntent:${roleRequestIntent != null}"
        } catch (e: Exception) {
            eligibleText = "Eligibility check failed"
        }
    }

        Column(
            modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize()
            .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopAppBar(title = { Text("Dialer") })
        // Debug: show eligibility status for being a default dialer
        androidx.compose.material3.Text(
            text = eligibleText,
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            textAlign = TextAlign.Center
        )
        // bring background in sync with app theme
        Spacer(modifier = Modifier.height(8.dp))
        // Phone number display
        Card(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                        text = phoneNumber.ifEmpty { "Enter number" },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color =
                                if (phoneNumber.isEmpty())
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                        )
                                else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Number pad
        NumberPad(
                onNumberClick = { number -> phoneNumber += number },
                onBackspaceClick = {
                    if (phoneNumber.isNotEmpty()) {
                        phoneNumber = phoneNumber.dropLast(1)
                    }
                },
                onCallClick = {
                    if (phoneNumber.isNotEmpty()) {
                        // Let CallUtils centralize the permission check and call flow.
                        CallUtils.placeCallWithPermission(context, phoneNumber) {
                            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        }
                    }
                }
        )
    }
}

@Composable
fun NumberPad(
        onNumberClick: (String) -> Unit,
        onBackspaceClick: () -> Unit,
        onCallClick: () -> Unit
) {
    Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Rows 1-3
        val buttons =
                listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("*", "0", "#")
                )

        buttons.forEach { row ->
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { number ->
                    DialerButton(text = number, onClick = { onNumberClick(number) })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Call and backspace buttons
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
        ) {
            // Backspace button
            IconButton(onClick = onBackspaceClick, modifier = Modifier.size(72.dp)) {
                Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                )
            }

            // Call button
                FloatingActionButton(
                    onClick = onCallClick,
                    modifier = Modifier.size(72.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    modifier = Modifier.size(32.dp)
                )
                }

            // Empty space for symmetry
            Spacer(modifier = Modifier.size(72.dp))
        }
    }
}

@Composable
fun DialerButton(text: String, onClick: () -> Unit) {
    Button(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors =
                    ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
    ) { Text(text = text, fontSize = 24.sp, fontWeight = FontWeight.Medium) }
}

private fun makePhoneCall(context: android.content.Context, phoneNumber: String) {
    CallUtils.placeCall(context, phoneNumber)
}

private fun requestCallMonitoringPermissions(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val permissionsToRequest = mutableListOf<String>()
    
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
    }
    
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
    }
    
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
    }
    
    if (permissionsToRequest.isNotEmpty()) {
        launcher.launch(permissionsToRequest.toTypedArray())
    } else {
        // All permissions already granted, start service
        val serviceIntent = Intent(context, CallStateObserverService::class.java)
        context.startService(serviceIntent)
    }
}
