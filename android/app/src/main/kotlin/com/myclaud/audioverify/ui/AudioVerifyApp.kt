package com.myclaud.audioverify.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myclaud.audioverify.permissions.PermissionGate

private enum class Tab(val label: String) { Manual("Manual"), Matrix("Matrix"), Reports("Reports") }

@Composable
fun AudioVerifyApp() {
    PermissionGate {
        var tab by remember { mutableStateOf(Tab.Manual) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.Manual,
                        onClick = { tab = Tab.Manual },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("Manual") },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Matrix,
                        onClick = { tab = Tab.Matrix },
                        icon = { Icon(Icons.Filled.PlaylistPlay, contentDescription = null) },
                        label = { Text("Matrix") },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Reports,
                        onClick = { tab = Tab.Reports },
                        icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                        label = { Text("Reports") },
                    )
                }
            }
        ) { padding ->
            val mod = Modifier.padding(padding)
            when (tab) {
                Tab.Manual -> ManualScreen(mod)
                Tab.Matrix -> MatrixScreen(mod)
                Tab.Reports -> ReportScreen(mod)
            }
        }
    }
}
