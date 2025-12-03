package com.sycet.defaultdialer.ui.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sycet.defaultdialer.data.models.Contact
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ContentUris
import com.sycet.defaultdialer.utils.CallUtils

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ContactListScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pageSize = 50
    val contacts = remember { mutableStateOf<List<Contact>>(emptyList()) }
    val listState = rememberLazyListState()
    val showLoading = remember { mutableStateOf(false) }
    val hasPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasWritePermission = remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    ) }

    val filterState = remember { mutableStateOf("All") }
    val searchQuery = remember { mutableStateOf("") }
    val debouncedQuery = remember { mutableStateOf("") }
    val hasMore = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasWritePermission.value = granted }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // no-op here — we rely on the permission flow to send the permission result back to the UI.
        // We keep this because CallUtils.placeCallWithPermission will invoke this launcher when needed.
    }

    // initial load and reload when filter/search change
    LaunchedEffect(hasPermission.value, filterState.value, debouncedQuery.value) {
        if (!hasPermission.value) return@LaunchedEffect
        showLoading.value = true
        contacts.value = emptyList()
        val (firstPage, more) = getContactsPage(context, pageSize, 0, filterState.value, debouncedQuery.value)
        contacts.value = firstPage
        hasMore.value = more
        showLoading.value = false
    }

    // debounce search input
    LaunchedEffect(searchQuery.value) {
        delay(300)
        debouncedQuery.value = searchQuery.value
    }

    // lazy-load next page when near bottom
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx + 10 >= contacts.value.size && hasMore.value && !showLoading.value) {
                    coroutineScope.launch {
                        showLoading.value = true
                        val offset = contacts.value.size
                        val (next, more) = getContactsPage(context, pageSize, offset, filterState.value, debouncedQuery.value)
                        contacts.value = (contacts.value + next).distinctBy { Pair(it.phoneNumber, it.id) }
                        hasMore.value = more
                        showLoading.value = false
                    }
                }
            }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopAppBar(title = { Text("Contacts") })

        // TopAppBar already contains the title — don't render the heading again

        if (!hasPermission.value) {
            Text(
                text = "Contacts permission required",
                color = MaterialTheme.colorScheme.error
            )
        } else if (contacts.value.isEmpty()) {
            Text(
                text = "No contacts found",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Search and filters
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery.value,
                onValueChange = { searchQuery.value = it },
                placeholder = { Text("Search") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = filterState.value == "All", onClick = { filterState.value = "All" }, label = { Text("All") })
                androidx.compose.material3.FilterChip(selected = filterState.value == "A-M", onClick = { filterState.value = "A-M" }, label = { Text("A–M") })
                androidx.compose.material3.FilterChip(selected = filterState.value == "N-Z", onClick = { filterState.value = "N-Z" }, label = { Text("N–Z") })
            }

            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts.value) { contact ->
                    ContactItem(
                        contact = contact,
                        hasWritePermission = hasWritePermission.value,
                        onDelete = {
                            // delete a contact
                            coroutineScope.launch {
                                try {
                                    if (!hasWritePermission.value) {
                                        writePermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                                        return@launch
                                    }

                                    val deleted = try {
                                        // delete by raw contacts for the contact id
                                        context.contentResolver.delete(
                                            ContactsContract.RawContacts.CONTENT_URI,
                                            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                                            arrayOf(contact.id)
                                        )
                                    } catch (e: Exception) {
                                        0
                                    }

                                    if (deleted > 0) {
                                        contacts.value = contacts.value.filter { it.id != contact.id }
                                    }
                                } catch (se: SecurityException) {
                                    writePermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                                }
                            }
                        },
                        onCallClick = {
                            CallUtils.placeCallWithPermission(context, contact.phoneNumber) {
                                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            }
                        }
                    )
                }

                item {
                        if (showLoading.value) {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                    }
                }
            }
        }

        // clear-all removed — no global destructive action here
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ContactItem(contact: Contact, hasWritePermission: Boolean = false, onDelete: () -> Unit = {}, onCallClick: () -> Unit) {
    val showDeleteDialog = remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            // Contact icon
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Contact",
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = contact.phoneNumber,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onCallClick) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (hasWritePermission) {
                IconButton(onClick = { showDeleteDialog.value = true }) {
                    Icon(imageVector = Icons.Outlined.DeleteSweep, contentDescription = "Delete contact", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showDeleteDialog.value) {
        AlertDialog(onDismissRequest = { showDeleteDialog.value = false }, title = { Text("Delete contact") }, text = { Text("Are you sure you want to delete ${contact.name}? This will remove the contact permanently.") }, confirmButton = {
            TextButton(onClick = {
                showDeleteDialog.value = false
                onDelete()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { showDeleteDialog.value = false }) { Text("Cancel") } })
    }
}

private fun getContacts(context: android.content.Context): List<Contact> {
    val contactsList = mutableListOf<Contact>()
    
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return contactsList
    }

    val cursor: Cursor? = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ),
        null,
        null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
    )

    cursor?.use {
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

        while (it.moveToNext()) {
            val id = it.getString(idIndex) ?: ""
            val name = it.getString(nameIndex) ?: "Unknown"
            val number = it.getString(numberIndex) ?: ""

            if (number.isNotEmpty()) {
                contactsList.add(Contact(id, name, number))
            }
        }
    }

    return contactsList
}

/**
 * Get a single page of contacts. Returns Pair<contacts, hasMore> where hasMore indicates more pages.
 */
fun getContactsPage(
    context: android.content.Context,
    limit: Int = 50,
    offset: Int = 0,
    filter: String = "All",
    search: String = ""
): Pair<List<Contact>, Boolean> {
    val contactsList = mutableListOf<Contact>()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return Pair(emptyList(), false)
    }

    val requestLimit = limit + 1

    val selectionParts = mutableListOf<String>()
    val selectionArgs = mutableListOf<String>()

    when (filter) {
        "A-M" -> {
            // first letter between A and M (inclusive)
            selectionParts.add("UPPER(SUBSTR(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},1,1)) BETWEEN ? AND ?")
            selectionArgs.add("A")
            selectionArgs.add("M")
        }
        "N-Z" -> {
            selectionParts.add("UPPER(SUBSTR(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},1,1)) BETWEEN ? AND ?")
            selectionArgs.add("N")
            selectionArgs.add("Z")
        }
        else -> { /* All */ }
    }

    if (search.isNotBlank()) {
        val orParts = mutableListOf<String>()
        val orArgs = mutableListOf<String>()
        orParts.add("${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?")
        orArgs.add("%$search%")
        orParts.add("${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?")
        orArgs.add("%$search%")
        selectionParts.add("(${orParts.joinToString(" OR ")})")
        selectionArgs.addAll(orArgs)
    }

    val selection: String? = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
    val selectionArguments: Array<String>? = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

    var cursor = try {
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $requestLimit OFFSET $offset"
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            selection,
            selectionArguments,
            sortOrder
        )
    } catch (e: Exception) {
        null
    }

    val fallback = cursor == null
    if (fallback) {
        cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            selection,
            selectionArguments,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )
    }

    cursor?.use {
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

        if (fallback) {
            if (!it.moveToPosition(offset)) return Pair(emptyList(), false)
        }

        var taken = 0
        while (it.moveToNext()) {
            if (taken >= requestLimit) break
            val id = it.getString(idIndex) ?: ""
            val name = it.getString(nameIndex) ?: "Unknown"
            val number = it.getString(numberIndex) ?: ""

            if (number.isNotEmpty()) contactsList.add(Contact(id, name, number))
            taken++
        }
    }

    val hasMore = contactsList.size > limit
    val out = if (hasMore) contactsList.subList(0, limit) else contactsList
    return Pair(out.distinctBy { Pair(it.phoneNumber, it.id) }, hasMore)
}
