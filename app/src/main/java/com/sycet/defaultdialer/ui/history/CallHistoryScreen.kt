package com.sycet.defaultdialer.ui.history

/*
 * Call history viewer with pagination support
 *
 * - getCallHistoryPage(context, limit, offset) returns a page of results and a boolean
 *   indicating whether more pages exist. It attempts to use LIMIT/OFFSET in the query
 *   and falls back to scanning the cursor if the provider doesn't accept LIMIT.
 * - getCallHistory(context) is kept as a compatibility helper that loads all pages.
 * - CallHistoryScreen() loads the call history lazily using a default pageSize of 50,
 *   appends unique records across pages and automatically loads more when the user
 *   scrolls near the bottom.
 */

import com.sycet.defaultdialer.data.models.CallRecord
import com.sycet.defaultdialer.utils.PhoneUtils

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** Utility to resolve contact name */
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

/**
 * Get a single page of call history.
 * Returns Pair(records, hasMore) where hasMore indicates whether there's another page.
 * Uses LIMIT/OFFSET in the sortOrder when supported; falls back to scanning the cursor.
 */
fun getCallHistoryPage(
    context: Context,
    limit: Int = 50,
    offset: Int = 0,
    filter: String = "All",
    search: String = ""
): Pair<List<CallRecord>, Boolean> {
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
    ) return Pair(emptyList(), false)

    val requestLimit = limit + 1 // request one extra row to detect 'hasMore'

    // Build selection & args for server-side filtering/search
    val selectionParts = mutableListOf<String>()
    val selectionArgs = mutableListOf<String>()

    when (filter) {
        "Incoming" -> {
            selectionParts.add("${CallLog.Calls.TYPE} = ?")
            selectionArgs.add(CallLog.Calls.INCOMING_TYPE.toString())
        }
        "Outgoing" -> {
            selectionParts.add("${CallLog.Calls.TYPE} = ?")
            selectionArgs.add(CallLog.Calls.OUTGOING_TYPE.toString())
        }
        "Missed" -> {
            selectionParts.add("${CallLog.Calls.TYPE} = ?")
            selectionArgs.add(CallLog.Calls.MISSED_TYPE.toString())
        }
        else -> {
            // All – no filter
        }
    }

    if (search.isNotBlank()) {
        val orParts = mutableListOf<String>()
        val orArgs = mutableListOf<String>()

        // match number substring
        orParts.add("${CallLog.Calls.NUMBER} LIKE ?")
        orArgs.add("%$search%")

        // if we can read contacts, include contact name matches by resolving numbers
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val nameMatches = mutableSetOf<String>()
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$search%"),
                    null
                )?.use { c ->
                    val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val raw = c.getString(idx) ?: continue
                        val n = PhoneUtils.normalizePhone(raw)
                        if (n.isNotBlank()) nameMatches.add(n)
                    }
                }

                if (nameMatches.isNotEmpty()) {
                    val placeholders = nameMatches.joinToString(",") { "?" }
                    orParts.add("${CallLog.Calls.NUMBER} IN ($placeholders)")
                    orArgs.addAll(nameMatches)
                }
            } catch (e: Exception) {
                // ignore errors from contact lookup and fallback to number-only search
            }
        }

        if (orParts.isNotEmpty()) {
            selectionParts.add("(${orParts.joinToString(" OR ")})")
            selectionArgs.addAll(orArgs)
        }
    }

    val selection: String? = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
    val selectionArguments: Array<String>? = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null
    var cursor = try {
        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $requestLimit OFFSET $offset"
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            selection,
            selectionArguments,
            sortOrder
        )
    } catch (e: Exception) {
        // provider might not support LIMIT/OFFSET — we'll fall back to full query
        null
    }

    val fallback = cursor == null
    if (fallback) {
        cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            selection,
            selectionArguments,
            "${CallLog.Calls.DATE} DESC"
        )
    }

    val records = mutableListOf<CallRecord>()

    cursor?.use {
        val idxId = it.getColumnIndex(CallLog.Calls._ID)
        val idxNumber = it.getColumnIndex(CallLog.Calls.NUMBER)
        val idxType = it.getColumnIndex(CallLog.Calls.TYPE)
        val idxDate = it.getColumnIndex(CallLog.Calls.DATE)
        val idxDuration = it.getColumnIndex(CallLog.Calls.DURATION)

        if (fallback) {
            // move to offset or return empty if not available
            if (!it.moveToPosition(offset)) return Pair(emptyList(), false)
        }

        var taken = 0
        while (it.moveToNext()) {
            if (taken >= requestLimit) break

            val id = it.getLong(idxId)
            val rawNumber = it.getString(idxNumber) ?: "Unknown"
            val number = PhoneUtils.normalizePhone(rawNumber)
            val type = it.getInt(idxType)
            val date = it.getLong(idxDate)
            val duration = it.getLong(idxDuration)

            val contactName = getContactName(context, number)

            records.add(CallRecord(id, number, contactName, type, date, duration))
            taken++
        }
    }

    // If we have more than 'limit' rows, signal that there's another page available.
    val hasMore = records.size > limit
    val out = if (hasMore) records.subList(0, limit) else records

    // remove duplicates safely for the returned page
    return Pair(out.distinctBy { Pair(it.number, it.date / 1000) }, hasMore)
}

/**
 * Compatibility helper that returns all records by requesting pages repeatedly.
 */
fun getCallHistory(context: Context): List<CallRecord> {
    val pageSize = 200
    val all = mutableListOf<CallRecord>()
    var offset = 0
    while (true) {
        val (page, hasMore) = getCallHistoryPage(context, pageSize, offset)
        // avoid duplicates across pages
        val existing = all.map { Pair(it.number, it.date / 1000) }.toSet()
        val unique = page.filter { Pair(it.number, it.date / 1000) !in existing }
        all.addAll(unique)
        if (!hasMore || page.isEmpty()) break
        offset += pageSize
    }
    return all
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
                            imageVector = Icons.Outlined.Call,
                            contentDescription = "Call Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Delete button
                    if (hasWritePermission) {
                        IconButton(onClick = { showDeleteDialog.value = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    IconButton(onClick = { menuExpanded.value = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(expanded = menuExpanded.value, onDismissRequest = { menuExpanded.value = false }) {
                        DropdownMenuItem(text = { Text("View Details") }, onClick = { /* TODO */ })
                        DropdownMenuItem(text = { Text("Add note") }, onClick = { /* TODO */ })
                    }
                }
            }
        )
    }

    if (showDeleteDialog.value) {
        AlertDialog(onDismissRequest = { showDeleteDialog.value = false }, title = { Text("Delete call record") }, text = { Text("Are you sure you want to delete this call log entry?") }, confirmButton = {
            TextButton(onClick = {
                showDeleteDialog.value = false
                onDelete()
            }) {
                Text("Delete")
            }
        }, dismissButton = {
            TextButton(onClick = { showDeleteDialog.value = false }) { Text("Cancel") }
        })
    }
}

@Composable
fun CallHistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pageSize = 50
    val callList = remember { mutableStateOf<List<CallRecord>>(emptyList()) }
    val listState = rememberLazyListState()
    val showLoading = remember { mutableStateOf(false) }
    val hasWritePermission = remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    ) }
    val page = remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // lazy-loading + pagination
    LaunchedEffect(Unit) {
        showLoading.value = true
        val (initialPage, hasMore) = getCallHistoryPage(context, pageSize, 0)
        callList.value = initialPage
        showLoading.value = false

        // load next pages lazily when user scrolls near bottom
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx + 10 >= callList.value.size && hasMore) {
                    // load more
                    coroutineScope.launch {
                        showLoading.value = true
                        val (next, more) = getCallHistoryPage(context, pageSize, callList.value.size)
                        callList.value = (callList.value + next).distinctBy { Pair(it.number, it.date / 1000) }
                        showLoading.value = false
                    }
                }
            }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        TopAppBar(title = { Text("Call History") }, actions = {
            IconButton(onClick = { /* TODO refresh */ }) { Icon(Icons.Outlined.History, contentDescription = "Refresh") }
            IconButton(onClick = { /* TODO */ }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        })

        OutlinedTextField(value = "", onValueChange = {}, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())

        FilterChip(selected = false, onClick = {}) { Text("All") }

        Spacer(modifier = Modifier.height(8.dp))

        PullToRefreshBox(state = rememberPullToRefreshState(onRefresh = {})) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(callList.value) { record ->
                    CallHistoryItem(record = record, hasWritePermission = hasWritePermission.value, onDelete = {
                        // TODO implement delete
                    })
                }

                item {
                    if (showLoading.value) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
