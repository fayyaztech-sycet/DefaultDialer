package com.sycet.defaultdialer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Backwards-compatibility wrapper: forward to the new implementation in
 * com.sycet.defaultdialer.ui.contacts.ContactListScreen
 */
@Deprecated("Moved to com.sycet.defaultdialer.ui.contacts.ContactListScreen")
@Composable
fun ContactListScreen(modifier: Modifier = Modifier) {
    com.sycet.defaultdialer.ui.contacts.ContactListScreen(modifier)
}
