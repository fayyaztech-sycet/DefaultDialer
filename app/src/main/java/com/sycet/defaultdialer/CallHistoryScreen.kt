package com.sycet.defaultdialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallRecord(
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long
)

@Composable
fun CallHistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val callHistory = remember { mutableStateOf<List<CallRecord>>(emptyList()) }
    val hasPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(hasPermission.value) {
        if (hasPermission.value) {
            callHistory.value = getCallHistory(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Call History",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (!hasPermission.value) {
            Text(
                text = "Call log permission required",
                color = MaterialTheme.colorScheme.error
            )
        } else if (callHistory.value.isEmpty()) {
            Text(
                text = "No call history",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(callHistory.value) { record ->
                    CallHistoryItem(record)
                }
            }
        }
    }
}

@Composable
fun CallHistoryItem(record: CallRecord) {
    val context = LocalContext.current
    val menuExpanded = remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* TODO: Call back */ },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Call type icon
            Icon(
                imageVector = when (record.type) {
                    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade
                    CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Filled.CallReceived
                    CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed
                    else -> Icons.Default.Call
                },
                contentDescription = "Call Type",
                tint = when (record.type) {
                    CallLog.Calls.MISSED_TYPE -> Color.Red
                    CallLog.Calls.OUTGOING_TYPE -> Color.Green
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.name ?: record.number,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = formatDate(record.date),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = formatDuration(record.duration),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            IconButton(onClick = { menuExpanded.value = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }

            DropdownMenu(
                expanded = menuExpanded.value,
                onDismissRequest = { menuExpanded.value = false }
            ) {
                if (record.name == null) {
                    DropdownMenuItem(
                        text = { Text("Save to Contacts") },
                        onClick = {
                            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                type = ContactsContract.RawContacts.CONTENT_TYPE
                                putExtra(ContactsContract.Intents.Insert.PHONE, record.number)
                            }
                            context.startActivity(intent)
                            menuExpanded.value = false
                        }
                    )
                }
            }
        }
    }
}

private fun getCallHistory(context: android.content.Context): List<CallRecord> {
    val callRecords = mutableListOf<CallRecord>()
    
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return callRecords
    }

    val cursor: Cursor? = context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        ),
        null,
        null,
        "${CallLog.Calls.DATE} DESC"
    )

    cursor?.use {
        val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
        val nameIndex = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
        val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
        val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
        val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

        while (it.moveToNext()) {
            val number = it.getString(numberIndex) ?: "Unknown"
            val name = it.getString(nameIndex)
            val type = it.getInt(typeIndex)
            val date = it.getLong(dateIndex)
            val duration = it.getLong(durationIndex)

            callRecords.add(CallRecord(number, name, type, date, duration))
        }
    }

    return callRecords.groupBy { Triple(it.number, it.date, it.type) }.map { it.value.first() }
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
