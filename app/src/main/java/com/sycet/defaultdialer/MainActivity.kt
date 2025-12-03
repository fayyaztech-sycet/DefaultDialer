package com.sycet.defaultdialer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sycet.defaultdialer.ui.theme.DefaultDialerTheme
import com.sycet.defaultdialer.ui.history.*
import com.sycet.defaultdialer.ui.dialer.DialerScreen
import com.sycet.defaultdialer.ui.contacts.ContactListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DefaultDialerTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                // showDialer toggles the full‑screen Dialer overlay — we removed the Dialer bottom tab
                var showDialer by remember { mutableStateOf(false) }
                
                Scaffold(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                    floatingActionButton = {
                        // Show a floating 9-dot keypad button on the History tab which opens the Dialer overlay when pressed
                        if (selectedTab == 0) {
                            FloatingActionButton(onClick = { showDialer = true }) {
                                Icon(Icons.Default.Apps, contentDescription = "Open Dialer")
                            }
                        }
                    },
                    floatingActionButtonPosition = FabPosition.End,
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.History, contentDescription = "History") },
                                label = { Text("History") }
                            )
                            // Dialer tab removed — Contacts becomes the second tab
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "Contacts") },
                                label = { Text("Contacts") }
                            )
                        }
                    }
                ) { innerPadding ->
                    // If the dialer overlay is requested, render it on top. Otherwise render the selected tab.
                    if (showDialer) {
                        // When the overlay is visible, pass a callback so it can be closed
                        DialerScreen(onClose = { showDialer = false })
                    } else {
                        when (selectedTab) {
                            0 -> com.sycet.defaultdialer.ui.history.CallHistoryScreen(modifier = Modifier.padding(innerPadding))
                            1 -> ContactListScreen(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
}