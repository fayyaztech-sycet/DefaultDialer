package com.sycet.defaultdialer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Backwards-compatibility wrapper: forward calls to the new implementation in
 * com.sycet.defaultdialer.ui.history.CallHistoryScreen
 */
@Deprecated("Moved to com.sycet.defaultdialer.ui.history.CallHistoryScreen")
@Composable
fun CallHistoryScreen(modifier: Modifier = Modifier) {
    com.sycet.defaultdialer.ui.history.CallHistoryScreen(modifier)
    // paginated call history state
    // pageSize controls how many items will be loaded per page. Tune this value depending on
    // your memory/time requirements. Default 50 is a reasonable tradeoff.
    val pageSize = 50
    val callHistory = remember { mutableStateOf<List<CallRecord>>(emptyList()) }
    val pageOffset = remember { mutableStateOf(0) }
    val isLoading = remember { mutableStateOf(false) }
    val endReached = remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
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

    // helper to load a page and append unique entries
    fun doLoadPage() {
        if (isLoading.value || endReached.value) return
        coroutineScope.launch {
                isLoading.value = true
                val (page, hasMore) = getCallHistoryPage(
                    context,
                    pageSize,
                    pageOffset.value,
                    selectedFilter.value,
                    searchQuery.value
                )
            val existing = callHistory.value.map { Pair(it.number, it.date / 1000) }.toSet()
            val unique = page.filter { Pair(it.number, it.date / 1000) !in existing }
            callHistory.value = callHistory.value + unique
            pageOffset.value += page.size
            endReached.value = !hasMore
            isLoading.value = false
        }
    }

    LaunchedEffect(hasCallLogPermission.value) {
        if (hasCallLogPermission.value) {
            // reset state and load first page
            pageOffset.value = 0
            endReached.value = false
            callHistory.value = emptyList()
            doLoadPage()
        }
    }

    // reset and restart paging when filter or search changes
    LaunchedEffect(selectedFilter.value, searchQuery.value) {
        if (!hasCallLogPermission.value) return@LaunchedEffect
        pageOffset.value = 0
        endReached.value = false
        callHistory.value = emptyList()
        doLoadPage()
    }

    // observe scroll and load more when nearing the end
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = lazyListState.layoutInfo.totalItemsCount
                if (lastVisible >= total - 1 - 3 && !isLoading.value && !endReached.value) {
                    doLoadPage()
                }
            }
    }

        // callHistory already contains items filtered by `selectedFilter` and `searchQuery`
        val filteredHistory = callHistory.value

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
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredHistory) { record ->
                    CallHistoryItem(record, hasWriteCallLogPermission.value) {
                        // Refresh after delete: reset paging and reload
                        pageOffset.value = 0
                        endReached.value = false
                        callHistory.value = emptyList()
                        doLoadPage()
                    }
                }

                // footer: loading indicator
                item {
                    if (isLoading.value) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (endReached.value && callHistory.value.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
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
                        // reset paging and reload
                        pageOffset.value = 0
                        endReached.value = false
                        callHistory.value = emptyList()
                        doLoadPage()
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