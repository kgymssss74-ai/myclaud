package com.myclaud.audioverify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myclaud.audioverify.core.util.AppLogger

/**
 * Renders the in-app log buffer. Uses a regular Column + verticalScroll
 * (NOT LazyColumn) so it can safely live inside a parent verticalScroll
 * without triggering Compose's "infinite measurement" crash. Visible
 * lines are capped at [maxVisibleLines] to keep recomposition cheap.
 */
@Composable
fun LogPanel(
    modifier: Modifier = Modifier,
    maxVisibleLines: Int = 60,
    maxHeightDp: Int = 280,
) {
    val lines by AppLogger.lines.collectAsState()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Logs (${lines.size})", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { AppLogger.clear() }) { Text("Clear") }
            }
            if (lines.isEmpty()) {
                Text("(empty)", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeightDp.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    lines.take(maxVisibleLines).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    if (lines.size > maxVisibleLines) {
                        Text(
                            "… ${lines.size - maxVisibleLines} more (use Clear or adb logcat)",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
