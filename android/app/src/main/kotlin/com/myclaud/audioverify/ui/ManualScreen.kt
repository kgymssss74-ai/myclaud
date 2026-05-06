package com.myclaud.audioverify.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myclaud.audioverify.core.engine.AAudioEngine
import com.myclaud.audioverify.core.engine.AudioFormat
import com.myclaud.audioverify.core.engine.AudioTrackEngine
import com.myclaud.audioverify.core.engine.OffloadEngine
import com.myclaud.audioverify.core.engine.PlaybackConfig
import com.myclaud.audioverify.core.engine.PlaybackEngine
import com.myclaud.audioverify.core.engine.PlaybackMetrics
import com.myclaud.audioverify.core.engine.StreamType
import com.myclaud.audioverify.core.engine.decode.PcmDecoder
import com.myclaud.audioverify.core.engine.decode.WavReader
import com.myclaud.audioverify.core.routing.AudioRouteController
import com.myclaud.audioverify.core.routing.Route
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

private enum class Source { BUILTIN_TONE, USER_FILE }

@Composable
fun ManualScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val routeController = remember { AudioRouteController(context) }

    var streamType by remember { mutableStateOf(StreamType.FAST_LL) }
    var format by remember { mutableStateOf(AudioFormat.WAV) }
    var route by remember { mutableStateOf(Route.SPEAKER) }
    var source by remember { mutableStateOf(Source.BUILTIN_TONE) }
    var pickedFile by remember { mutableStateOf<File?>(null) }
    var pickedFileLabel by remember { mutableStateOf<String?>(null) }

    var engine by remember { mutableStateOf<PlaybackEngine?>(null) }
    var metrics by remember { mutableStateOf<PlaybackMetrics?>(null) }
    var status by remember { mutableStateOf("Idle") }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val (file, label, detected) = importPickedFile(context, uri)
            pickedFile = file
            pickedFileLabel = label
            format = detected
            source = Source.USER_FILE
            status = "Loaded ${label} (detected ${detected})"
        } catch (e: Exception) {
            status = "Pick failed: ${e.message}"
        }
    }

    LaunchedEffect(engine) {
        while (engine != null) {
            metrics = engine?.snapshot()
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine?.stop() }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Stream type", style = MaterialTheme.typography.labelLarge)
        ChipRow(StreamType.values().toList(), streamType) { streamType = it }

        Text("Source", style = MaterialTheme.typography.labelLarge)
        ChipRow(Source.values().toList(), source) {
            source = it
            if (it == Source.BUILTIN_TONE) {
                pickedFile = null
                pickedFileLabel = null
            }
        }
        if (source == Source.USER_FILE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickLauncher.launch(arrayOf("audio/*", "video/mp4")) }) {
                    Text(if (pickedFile == null) "Pick file" else "Pick another")
                }
                pickedFileLabel?.let { Text("Loaded: $it") }
            }
        }

        Text("Format", style = MaterialTheme.typography.labelLarge)
        if (source == Source.BUILTIN_TONE) {
            ChipRow(AudioFormat.values().toList(), format) { format = it }
        } else {
            Text("Auto-detected: $format (from picked file)")
        }

        Text("Route", style = MaterialTheme.typography.labelLarge)
        ChipRow(Route.values().toList(), route) { route = it }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                runCatching {
                    val file = when (source) {
                        Source.BUILTIN_TONE -> extractBuiltinAsset(context, assetPathFor(format))
                        Source.USER_FILE -> pickedFile ?: error("No file picked")
                    }
                    val plan = routeController.resolve(route)
                    if (plan.missingHints.isNotEmpty()) {
                        status = "Missing: ${plan.missingHints.joinToString()}"
                        return@runCatching
                    }
                    val newEngine: PlaybackEngine = when (streamType) {
                        StreamType.OFFLOAD -> OffloadEngine(context, file, format)
                        StreamType.ULL -> AAudioEngine(decode(format, file))
                        else -> AudioTrackEngine(decode(format, file), streamType)
                    }
                    newEngine.open(PlaybackConfig(streamType, format, file.absolutePath, plan.primary, durationSec = 999))
                    newEngine.start()
                    engine = newEngine
                    status = "Playing"
                }.onFailure {
                    status = "Open failed: ${it.message}"
                }
            }, enabled = engine == null) { Text("Play") }

            Button(onClick = {
                engine?.stop()
                engine = null
                status = "Stopped"
            }, enabled = engine != null) { Text("Stop") }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Status: $status")
                metrics?.let { m ->
                    Text("Underrun: ${m.underrunCount}  xRun: ${m.xRunCount}")
                    Text("Performance: ${m.performanceMode}  Sharing: ${m.sharingMode}  MMAP: ${m.isMmap}")
                    Text("Routed device: id=${m.routedDeviceId} type=${m.routedDeviceType}")
                    m.framesPerBurst?.let { Text("framesPerBurst: $it  sampleRate: ${m.sampleRate}") }
                    m.errorMessage?.let { Text("Error: $it") }
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(values: List<T>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (v in values) {
            AssistChip(
                onClick = { onSelect(v) },
                label = { Text(v.toString()) },
                enabled = v != selected,
            )
        }
    }
}

private fun assetPathFor(f: AudioFormat): String = when (f) {
    AudioFormat.WAV -> "audio/sine_1k_48k_16b_5s.wav"
    AudioFormat.MP3 -> "audio/sine_1k_48k_16b_5s.mp3"
    AudioFormat.AAC -> "audio/sine_1k_48k_16b_5s.m4a"
    AudioFormat.MP4 -> "audio/sine_1k_48k_16b_5s.mp4"
}

private fun extractBuiltinAsset(context: Context, path: String): File {
    val name = path.substringAfterLast('/')
    val out = File(context.cacheDir, "audio/$name")
    out.parentFile?.mkdirs()
    if (!out.exists() || out.length() == 0L) {
        context.assets.open(path).use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
    }
    return out
}

private data class PickedFile(val file: File, val label: String, val format: AudioFormat)

private fun importPickedFile(context: Context, uri: Uri): PickedFile {
    val display = queryDisplayName(context, uri) ?: "picked-${System.currentTimeMillis()}"
    val mime = context.contentResolver.getType(uri)
    val format = detectFormat(display, mime)
    val outName = "user-${System.currentTimeMillis()}-$display"
    val out = File(context.cacheDir, "audio/$outName")
    out.parentFile?.mkdirs()
    context.contentResolver.openInputStream(uri)!!.use { input ->
        FileOutputStream(out).use { input.copyTo(it) }
    }
    return PickedFile(out, display, format)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx < 0) return null
        return it.getString(idx)
    }
}

private fun detectFormat(name: String, mime: String?): AudioFormat {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "wav" || mime == "audio/wav" || mime == "audio/x-wav" -> AudioFormat.WAV
        ext == "mp3" || mime == "audio/mpeg" || mime == "audio/mp3" -> AudioFormat.MP3
        ext == "m4a" || ext == "aac" || mime == "audio/aac" || mime == "audio/mp4" -> AudioFormat.AAC
        ext == "mp4" || mime == "video/mp4" -> AudioFormat.MP4
        else -> AudioFormat.WAV
    }
}

private fun decode(format: AudioFormat, file: File) = when (format) {
    AudioFormat.WAV -> file.inputStream().use { WavReader.read(it) }
    else -> PcmDecoder.decodeToPcm(file)
}
