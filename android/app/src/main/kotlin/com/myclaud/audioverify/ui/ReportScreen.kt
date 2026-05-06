package com.myclaud.audioverify.ui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.myclaud.audioverify.core.engine.AudioFormat
import com.myclaud.audioverify.core.engine.OffloadCapabilityProbe
import com.myclaud.audioverify.core.engine.OffloadCaps
import com.myclaud.audioverify.core.report.Storage
import java.io.File

@Composable
fun ReportScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runs = remember { mutableStateOf(Storage.listRuns(context)) }
    var caps by remember { mutableStateOf(OffloadCapabilityProbe.probe(context)) }
    var devices by remember { mutableStateOf(listOutputDevices(context)) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CapabilitiesCard(
            caps = caps,
            devices = devices,
            onReprobe = {
                caps = OffloadCapabilityProbe.probe(context)
                devices = listOutputDevices(context)
            },
        )
        LogPanel()
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
private fun CapabilitiesCard(
    caps: OffloadCaps,
    devices: List<DeviceLine>,
    onReprobe: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device capabilities", style = MaterialTheme.typography.titleMedium)
            Text("${Build.MANUFACTURER} ${Build.MODEL}  ·  SoC: ${Build.SOC_MODEL ?: "?"}  ·  Android ${Build.VERSION.RELEASE}")
            Text("OFFLOAD support:", style = MaterialTheme.typography.titleSmall)
            for (f in listOf(AudioFormat.MP3, AudioFormat.AAC, AudioFormat.MP4)) {
                val ok = caps.supported[f] == true
                val sr = caps.perFormatSampleRate[f]
                Text("  ${f.name}: ${if (ok) "✓ at ${sr ?: "?"} Hz" else "✗"}")
            }
            Text("Output devices (${devices.size}):", style = MaterialTheme.typography.titleSmall)
            for (d in devices) {
                Text("  • ${d.typeName}  id=${d.id}  ${d.product}")
                Text("     SRs=${d.sampleRates}  channels=${d.channelCounts}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onReprobe) { Text("Re-probe") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
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
}

private data class DeviceLine(
    val id: Int,
    val typeName: String,
    val product: String,
    val sampleRates: String,
    val channelCounts: String,
)

private fun listOutputDevices(context: Context): List<DeviceLine> {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
        DeviceLine(
            id = d.id,
            typeName = typeName(d.type),
            product = (d.productName ?: "?").toString(),
            sampleRates = d.sampleRates.joinToString(),
            channelCounts = d.channelCounts.joinToString(),
        )
    }
}

private fun typeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    else -> "TYPE_$type"
}
