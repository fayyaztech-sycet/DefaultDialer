package com.sycet.defaultdialer.ui.history

/*
 * Call history viewer with pagination support
 *
 * Logging & testing notes:
 * - Uses android.util.Log with tag "CallHistoryScreen" for debug/info/warn events.
 * - Important log points:
 *   - getContactName(): lookup attempts
 *   - getCallHistoryPage(): parsing each row, fallback to full cursor, offset handling
 *   - CallHistoryItem: permission grants, attempted calls, blocked attempts due to missing numbers
 * - Use `adb logcat -s CallHistoryScreen` while exercising the app (tap call on history, delete, or page results)
 *   to see these messages. That helps reproduce the dialing->disconnected issue and confirm the fix.
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
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.CircularProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import com.sycet.defaultdialer.utils.CallUtils
import androidx.core.net.toUri


/** Utility to resolve contact name */
fun getContactName(context: Context, phone: String): String? {
    Log.d("CallHistoryScreen", "getContactName(): looking up contact for phone=$phone")
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

// placeCall moved to utils: CallUtils.placeCall(context, number)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryItem(record: CallRecord, hasWritePermission: Boolean, onDelete: () -> Unit) {
    val localContext = LocalContext.current
    val menuExpanded = remember { mutableStateOf(false) }
    val showDeleteDialog = remember { mutableStateOf(false) }
    val showInvalidNumberDialog = remember { mutableStateOf(false) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("CallHistoryScreen", "CALL_PHONE permission granted for record id=${record.id} number='${record.number}'")
            if (record.number.isNotBlank()) {
                CallUtils.placeCall(localContext, record.number)
            } else {
                Log.w("CallHistoryScreen", "Permission granted but record.number is blank for id=${record.id}")
                showInvalidNumberDialog.value = true
            }
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
                    text = record.name ?: record.number.ifBlank { "Unknown" },
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
                        // Delegate permission handling to CallUtils.placeCallWithPermission.
                        CallUtils.placeCallWithPermission(localContext, record.number) {
                            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
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

                    if (showInvalidNumberDialog.value) {
                        AlertDialog(
                            onDismissRequest = { showInvalidNumberDialog.value = false },
                            title = { Text("Phone number unavailable") },
                            text = { Text("This call log entry doesn't contain a valid phone number.") },
                            confirmButton = {
                                TextButton(onClick = { showInvalidNumberDialog.value = false }) { Text("OK") }
                            }
                        )
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


@OptIn(ExperimentalMaterial3Api::class)
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
    val hasMore = remember { mutableStateOf(false) }
    val filterState = remember { mutableStateOf("All") }
    val searchQuery = remember { mutableStateOf("") }
    val debouncedQuery = remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasWritePermission.value = granted
    }
    val showClearAllDialog = remember { mutableStateOf(false) }

    // lazy-loading + pagination
    // initial load + support reloading when filter/search changes
    LaunchedEffect(filterState.value, debouncedQuery.value) {
        showLoading.value = true
        // reset paging state
        callList.value = emptyList()
        val (initialPage, more) = getCallHistoryPage(context, pageSize, 0, filterState.value, debouncedQuery.value)
        callList.value = initialPage
        hasMore.value = more
        showLoading.value = false
    }

    // debounce search input
    LaunchedEffect(searchQuery.value) {
        // small debounce for typing
        delay(300)
        debouncedQuery.value = searchQuery.value
    }

    // lazy-loading: load next page when near the bottom
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx + 10 >= callList.value.size && hasMore.value && !showLoading.value) {
                    coroutineScope.launch {
                        showLoading.value = true
                        val offset = callList.value.size
                        val (next, more) = getCallHistoryPage(context, pageSize, offset, filterState.value, debouncedQuery.value)
                        // append unique entries
                        callList.value = (callList.value + next).distinctBy { Pair(it.number, it.date / 1000) }
                        hasMore.value = more
                        showLoading.value = false
                    }
                }
            }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background).fillMaxSize().padding(16.dp)) {
        TopAppBar(title = { Text("Call History") }, actions = {
            IconButton(onClick = { /* TODO refresh */ }) { Icon(Icons.Outlined.History, contentDescription = "Refresh") }
            if (hasWritePermission.value) {
                IconButton(onClick = { showClearAllDialog.value = true }) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear all") }
            } else {
                IconButton(onClick = { writePermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG) }) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "Request permission for clear all") }
            }
            IconButton(onClick = { /* TODO */ }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        })

        OutlinedTextField(
            value = searchQuery.value,
            onValueChange = { searchQuery.value = it },
            placeholder = { Text("Search") },
            modifier = Modifier.fillMaxWidth()
        )

        // Filters: All, Missed, Answered, Incoming
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filterState.value == "All", onClick = { filterState.value = "All" }, label = { Text("All") })
            FilterChip(selected = filterState.value == "Missed", onClick = { filterState.value = "Missed" }, label = { Text("Missed") })
            FilterChip(selected = filterState.value == "Answered", onClick = { filterState.value = "Answered" }, label = { Text("Answered") })
            FilterChip(selected = filterState.value == "Incoming", onClick = { filterState.value = "Incoming" }, label = { Text("Incoming") })
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(callList.value) { record ->
                CallHistoryItem(record = record, hasWritePermission = hasWritePermission.value, onDelete = {
                    // delete single record
                    coroutineScope.launch {
                        try {
                            if (!hasWritePermission.value) {
                                writePermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
                                return@launch
                            }

                            val deleted = context.contentResolver.delete(
                                CallLog.Calls.CONTENT_URI,
                                "${CallLog.Calls._ID} = ?",
                                arrayOf(record.id.toString())
                            )

                            if (deleted > 0) {
                                // remove locally
                                callList.value = callList.value.filter { it.id != record.id }
                            }
                        } catch (e: SecurityException) {
                            // permission issue - request permission
                            writePermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
                        }
                    }
                })
            }

            item {
                if (showLoading.value) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (showClearAllDialog.value) {
            AlertDialog(onDismissRequest = { showClearAllDialog.value = false }, title = { Text("Clear call history") }, text = { Text("Are you sure you want to permanently delete all call history? This action cannot be undone.") }, confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog.value = false
                    coroutineScope.launch {
                        try {
                            if (!hasWritePermission.value) {
                                writePermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
                                return@launch
                            }

                            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
                            callList.value = emptyList()
                            hasMore.value = false
                        } catch (e: SecurityException) {
                            writePermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
                        }
                    }

                }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = {
                TextButton(onClick = { showClearAllDialog.value = false }) { Text("Cancel") }
            })
        }
    }
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
        "Answered" -> {
            // answered = any type that isn't 'missed' (incoming + outgoing)
            selectionParts.add("${CallLog.Calls.TYPE} != ?")
            selectionArgs.add(CallLog.Calls.MISSED_TYPE.toString())
        }
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
        Log.i("CallHistoryScreen", "Provider rejected LIMIT/OFFSET query — falling back to full cursor. error=${e.message}")
        null
    }

    val fallback = cursor == null
    if (fallback) {
        Log.d("CallHistoryScreen", "Using fallback cursor (no LIMIT/OFFSET support) — will scan to offset=$offset")
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
            if (!it.moveToPosition(offset)) {
//                Log.w("CallHistoryScreen", "Cursor shorter than requested offset=$offset — returning empty page")
                return Pair(emptyList(), false)
            }
        }

        var taken = 0
        while (it.moveToNext()) {
            if (taken >= requestLimit) break

            val id = it.getLong(idxId)
            val rawNumber = it.getString(idxNumber) ?: ""
            val number = PhoneUtils.normalizePhone(rawNumber)
            val type = it.getInt(idxType)
            val date = it.getLong(idxDate)
            val duration = it.getLong(idxDuration)
//            Log.d(
//                "CallHistoryScreen",
//                "Parsed call row id=$id raw='$rawNumber' normalized='$number' type=$type date=$date duration=$duration"
//            )

            val contactName = if (number.isNotBlank()) getContactName(context, number) else null

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

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@SuppressLint("DefaultLocale")
private fun formatDuration(seconds: Long): String {
    return if (seconds > 0) {
        val minutes = seconds / 60
        val secs = seconds % 60
        String.format("%d:%02d", minutes, secs)
    } else {
        "Not answered"
    }

}
