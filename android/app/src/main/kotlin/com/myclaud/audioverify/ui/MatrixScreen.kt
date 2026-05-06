package com.myclaud.audioverify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myclaud.audioverify.core.report.HtmlReportWriter
import com.myclaud.audioverify.core.report.JsonReportWriter
import com.myclaud.audioverify.core.report.Storage
import com.myclaud.audioverify.core.routing.AudioRouteController
import com.myclaud.audioverify.core.runner.MatrixDefinition
import com.myclaud.audioverify.core.runner.MatrixRunner
import com.myclaud.audioverify.core.runner.RelaxationGate
import kotlinx.coroutines.launch

@Composable
fun MatrixScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val gate = remember { RelaxationGate() }
    val routeController = remember { AudioRouteController(context) }
    val runner = remember { MatrixRunner(context, gate, routeController) }
    val scope = rememberCoroutineScope()

    val progress by runner.progress.collectAsState()
    val pending by gate.pending.collectAsState()
    var summary by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                running = true
                summary = null
                scope.launch {
                    val matrixJson = context.assets.open("matrix.json").bufferedReader().use { it.readText() }
                    val thresholdsJson = context.assets.open("thresholds.json").bufferedReader().use { it.readText() }
                    val (cases, thresholds) = MatrixDefinition.expand(matrixJson, thresholdsJson)
                    val durationSec = org.json.JSONObject(matrixJson).optInt("playbackDurationSec", 5)
                    val report = runner.run(cases, thresholds, durationSec)
                    val dir = Storage.newRunDir(context, report.timestampMs)
                    JsonReportWriter.write(report, dir)
                    HtmlReportWriter.write(report, dir)
                    summary = "Done — ${report.records.size} cases, report at ${dir.absolutePath}"
                    running = false
                }
            }, enabled = !running) { Text("Start matrix") }
        }
        Text("Progress: ${progress.completed}/${progress.total} ${progress.currentCaseId.orEmpty()}")
        summary?.let { Text(it) }

        pending?.let { req ->
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Methodology decision required", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text("Case: ${req.caseId}")
                    if (req.missingHints.isNotEmpty()) Text("Missing: ${req.missingHints.joinToString()}")
                    LazyColumn {
                        items(req.verdicts) { v ->
                            Text("- ${v.name}: ${v.result} (exp=${v.expected}, act=${v.actual})")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { gate.resolve(com.myclaud.audioverify.core.runner.RelaxDecision.ACCEPT_RELAXED) }) { Text("Accept relaxed") }
                        Button(onClick = { gate.resolve(com.myclaud.audioverify.core.runner.RelaxDecision.MARK_FAIL) }) { Text("Mark FAIL") }
                        Button(onClick = { gate.resolve(com.myclaud.audioverify.core.runner.RelaxDecision.RETRY) }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
