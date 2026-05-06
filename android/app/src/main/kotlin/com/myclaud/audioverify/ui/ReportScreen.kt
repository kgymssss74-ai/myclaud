package com.myclaud.audioverify.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.myclaud.audioverify.core.report.Storage
import java.io.File

@Composable
fun ReportScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runs = remember { mutableStateOf(Storage.listRuns(context)) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (runs.value.isEmpty()) {
            Text("No reports yet. Run the matrix to generate one.")
            return@Column
        }
        LazyColumn {
            items(runs.value) { dir ->
                ReportRow(dir)
            }
        }
    }
}

@Composable
private fun ReportRow(dir: File) {
    val context = LocalContext.current
    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(dir.name)
            val htmlFile = File(dir, "report.html")
            val jsonFile = File(dir, "report.json")
            Text("HTML: ${if (htmlFile.exists()) htmlFile.absolutePath else "(missing)"}")
            Text("JSON: ${if (jsonFile.exists()) jsonFile.absolutePath else "(missing)"}")
            if (htmlFile.exists()) {
                androidx.compose.material3.Button(onClick = {
                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        htmlFile,
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/html"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share report"))
                }) { Text("Share HTML") }
            }
        }
    }
}
