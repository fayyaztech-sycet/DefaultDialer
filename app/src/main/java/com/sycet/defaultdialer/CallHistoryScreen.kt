package com.sycet.defaultdialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sycet.defaultdialer.utils.PhoneUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallRecord(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long
)

fun getContactName(context: Context, phone: String): String? {
    val normalized = PhoneUtils.normalizePhone(phone)
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(normalized)
    )

    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getString(0)
        }
    }
    return null
}

fun getCallHistory(context: Context): List<CallRecord> {
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
    ) return emptyList()

    val callList = mutableListOf<CallRecord>()

    val cursor = context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        ),
        null,
        null,
        "${CallLog.Calls.DATE} DESC"
    )

    cursor?.use {
        val idxId = it.getColumnIndex(CallLog.Calls._ID)
        val idxNumber = it.getColumnIndex(CallLog.Calls.NUMBER)
        val idxType = it.getColumnIndex(CallLog.Calls.TYPE)
        val idxDate = it.getColumnIndex(CallLog.Calls.DATE)
        val idxDuration = it.getColumnIndex(CallLog.Calls.DURATION)

        while (it.moveToNext()) {
            val id = it.getLong(idxId)
            val rawNumber = it.getString(idxNumber) ?: "Unknown"
            val number = PhoneUtils.normalizePhone(rawNumber)
            val type = it.getInt(idxType)
            val date = it.getLong(idxDate)
            val duration = it.getLong(idxDuration)

            val contactName = getContactName(context, number)

            callList.add(
                CallRecord(id, number, contactName, type, date, duration)
            )
        }
    }

    // remove duplicates safely
    return callList.distinctBy {
        Pair(it.number, it.date / 1000)   // normalizes milliseconds/seconds mismatch
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Long): String {
    return if (seconds > 0) {
        val minutes = seconds / 60
        val secs = seconds % 60
        String.format("%d:%02d", minutes, secs)
    } else {
        "Not answered"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryItem(record: CallRecord, hasWritePermission: Boolean, onDelete: () -> Unit) {
    val localContext = LocalContext.current
    val menuExpanded = remember { mutableStateOf(false) }
    val showDeleteDialog = remember { mutableStateOf(false) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${record.number}")
            }
            localContext.startActivity(intent)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = when (record.type) {
                        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade
                        CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Filled.CallReceived
                        CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed
                        else -> Icons.Default.Call
                    },
                    contentDescription = "Call Type",
                    tint = when (record.type) {
                        CallLog.Calls.MISSED_TYPE -> Color(0xFFD32F2F) // red600
                        CallLog.Calls.OUTGOING_TYPE -> Color(0xFF388E3C) // green600
                        else -> MaterialTheme.colorScheme.primary // blue
                    },
                    modifier = Modifier.size(24.dp)
                )
            },
            headlineContent = {
                Text(
                    text = record.name ?: record.number,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            supportingContent = {
                Text(
                    text = "${formatDate(record.date)} • ${formatDuration(record.duration)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Call back button
                    IconButton(onClick = {
                        when (PackageManager.PERMISSION_GRANTED) {
                            ContextCompat.checkSelfPermission(
                                localContext,
                                Manifest.permission.CALL_PHONE
                            ) -> {
                                val intent = Intent(Intent.ACTION_CALL).apply {
                                    data = Uri.parse("tel:${record.number}")
                                }
                                localContext.startActivity(intent)
                            }
                            else -> {
                                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            }
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Call,
                            contentDescription = "Call back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Three-dot menu
                    IconButton(onClick = { menuExpanded.value = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        DropdownMenu(
            expanded = menuExpanded.value,
            onDismissRequest = { menuExpanded.value = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (record.name == null) {
                DropdownMenuItem(
                    text = { Text("Save to Contacts", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                            type = ContactsContract.RawContacts.CONTENT_TYPE
                            putExtra(ContactsContract.Intents.Insert.PHONE, record.number)
                        }
                        localContext.startActivity(intent)
                        menuExpanded.value = false
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Message", color = MaterialTheme.colorScheme.onSurface) },
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("sms:${record.number}")
                    }
                    localContext.startActivity(intent)
                    menuExpanded.value = false
                }
            )
            if (hasWritePermission) {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showDeleteDialog.value = true
                        menuExpanded.value = false
                    }
                )
            }
        }
    }

    if (showDeleteDialog.value) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog.value = false },
            title = { Text("Delete Call Record") },
            text = { Text("Are you sure you want to delete this call record?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        localContext.contentResolver.delete(
                            CallLog.Calls.CONTENT_URI,
                            "${CallLog.Calls._ID} = ?",
                            arrayOf(record.id.toString())
                        )
                        onDelete()
                        showDeleteDialog.value = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val callHistory = remember { mutableStateOf<List<CallRecord>>(emptyList()) }
    val hasCallLogPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasContactsPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasWriteCallLogPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val selectedFilter = remember { mutableStateOf("All") }
    val searchQuery = remember { mutableStateOf("") }
    val showClearAllDialog = remember { mutableStateOf(false) }

    LaunchedEffect(hasCallLogPermission.value, hasContactsPermission.value) {
        if (hasCallLogPermission.value) {
            callHistory.value = getCallHistory(context)
        }
    }

    val filteredHistory = callHistory.value.filter { record ->
        val matchesFilter = selectedFilter.value == "All" ||
                (selectedFilter.value == "Incoming" && record.type == CallLog.Calls.INCOMING_TYPE) ||
                (selectedFilter.value == "Outgoing" && record.type == CallLog.Calls.OUTGOING_TYPE) ||
                (selectedFilter.value == "Missed" && record.type == CallLog.Calls.MISSED_TYPE)
        val matchesSearch = searchQuery.value.isEmpty() ||
                (record.name ?: record.number).contains(searchQuery.value, ignoreCase = true)
        matchesFilter && matchesSearch
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("Call History") },
            actions = {
                if (hasWriteCallLogPermission.value) {
                    IconButton(onClick = {
                        showClearAllDialog.value = true
                    }) {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear All",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

        // Search field
        OutlinedTextField(
            value = searchQuery.value,
            onValueChange = { searchQuery.value = it },
            label = { Text("Search by name or number") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            trailingIcon = {
                if (searchQuery.value.isNotEmpty()) {
                    IconButton(onClick = { searchQuery.value = "" }) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color.Gray.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter.value == "All",
                onClick = { selectedFilter.value = "All" },
                label = { Text("All") }
            )
            FilterChip(
                selected = selectedFilter.value == "Incoming",
                onClick = { selectedFilter.value = "Incoming" },
                label = { Text("Incoming") }
            )
            FilterChip(
                selected = selectedFilter.value == "Outgoing",
                onClick = { selectedFilter.value = "Outgoing" },
                label = { Text("Outgoing") }
            )
            FilterChip(
                selected = selectedFilter.value == "Missed",
                onClick = { selectedFilter.value = "Missed" },
                label = { Text("Missed") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!hasCallLogPermission.value) {
            Text(
                text = "Call log permission required",
                color = MaterialTheme.colorScheme.error
            )
        } else if (!hasContactsPermission.value) {
            Text(
                text = "Contacts permission required for names",
                color = MaterialTheme.colorScheme.secondary
            )
        } else if (filteredHistory.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (callHistory.value.isEmpty()) "No call history found" else "No matching calls",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredHistory) { record ->
                    CallHistoryItem(record, hasWriteCallLogPermission.value) {
                        // Refresh after delete
                        callHistory.value = getCallHistory(context)
                    }
                }
                }
            }
        }
    }

    if (showClearAllDialog.value) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog.value = false },
            title = { Text("Clear All Call History") },
            text = { Text("Are you sure you want to delete all call records? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
                        callHistory.value = getCallHistory(context)
                        showClearAllDialog.value = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}