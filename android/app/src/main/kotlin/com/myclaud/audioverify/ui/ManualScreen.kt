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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myclaud.audioverify.core.engine.AAudioEngine
import com.myclaud.audioverify.core.engine.AudioFormat
import com.myclaud.audioverify.core.engine.AudioTrackEngine
import com.myclaud.audioverify.core.engine.OffloadCapabilityProbe
import com.myclaud.audioverify.core.engine.OffloadCaps
import com.myclaud.audioverify.core.engine.OffloadEngine
import com.myclaud.audioverify.core.engine.PerformanceMode
import com.myclaud.audioverify.core.engine.PlaybackConfig
import com.myclaud.audioverify.core.engine.PlaybackEngine
import com.myclaud.audioverify.core.engine.PlaybackMetrics
import com.myclaud.audioverify.core.engine.SharingMode
import com.myclaud.audioverify.core.engine.StreamType
import com.myclaud.audioverify.core.engine.decode.PcmData
import com.myclaud.audioverify.core.engine.decode.PcmDecoder
import com.myclaud.audioverify.core.engine.decode.WavReader
import com.myclaud.audioverify.core.routing.AudioRouteController
import com.myclaud.audioverify.core.routing.Route
import com.myclaud.audioverify.core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private enum class Source { TONE, FILE }

private data class EngineSlot(
    val label: String,
    val streamType: StreamType,
    val route: Route,
    val engine: PlaybackEngine,
)

private const val TAG = "ManualScreen"

@Composable
fun ManualScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routeController = remember { AudioRouteController(context) }
    var caps: OffloadCaps by remember {
        mutableStateOf(OffloadCaps(emptyMap(), emptyMap(), 0L))
    }

    LaunchedEffect(Unit) {
        // Probe off the main thread — isOffloadedPlaybackSupported is an
        // audioserver IPC and was previously a documented ANR vector when
        // run synchronously inside the first composition.
        caps = withContext(Dispatchers.IO) { OffloadCapabilityProbe.probe(context) }
        AppLogger.i(TAG, "OFFLOAD probe done: $caps")
    }

    var selectedStreams by remember { mutableStateOf(setOf(StreamType.FAST_LL)) }
    var selectedRoutes by remember { mutableStateOf(setOf(Route.SPEAKER)) }
    var format by remember { mutableStateOf(AudioFormat.WAV) }
    var source by remember { mutableStateOf(Source.TONE) }
    var pickedFile by remember { mutableStateOf<File?>(null) }
    var pickedFileLabel by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    var slots by remember { mutableStateOf<List<EngineSlot>>(emptyList()) }
    val slotMetrics = remember { mutableStateMapOf<String, PlaybackMetrics>() }
    var status by remember { mutableStateOf("Idle") }

    var connectedRoutes by remember { mutableStateOf(routeController.connectedRoutes()) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        AppLogger.i(TAG, "OpenDocument returned uri=$uri")
        working = true
        status = "Importing picked file..."
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { importPickedFile(context, uri) }
                stopAndClear(slots, slotMetrics)
                slots = emptyList()
                pickedFile = imported.file
                pickedFileLabel = imported.label
                format = imported.format
                source = Source.FILE
                status = "Loaded ${imported.label} (detected ${imported.format})"
                AppLogger.i(TAG, "import OK label=${imported.label} format=${imported.format} bytes=${imported.file.length()}")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "import failed", t)
                status = "Pick failed: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                working = false
            }
        }
    }

    DisposableEffect(routeController) {
        val cb = routeController.registerHotplug {
            connectedRoutes = routeController.connectedRoutes()
            AppLogger.i(TAG, "hotplug connected=$connectedRoutes")
        }
        onDispose { routeController.unregisterHotplug(cb) }
    }

    LaunchedEffect(slots) {
        if (slots.isEmpty()) return@LaunchedEffect
        while (slots.isNotEmpty()) {
            slots.forEach { slotMetrics[it.label] = it.engine.snapshot() }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopAndClear(slots, slotMetrics) }
    }

    val routeDisabled: Set<Route> = Route.values()
        .filter { !connectedRoutes.contains(it) }
        .toSet()

    val formatDisabled: Set<AudioFormat> = if (selectedStreams.contains(StreamType.OFFLOAD)) {
        AudioFormat.values().filter { caps.supported[it] != true }.toSet()
    } else {
        emptySet()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Stream type (multi-select)", style = MaterialTheme.typography.labelLarge)
        MultiChipRow(
            values = StreamType.values().toList(),
            selected = selectedStreams,
            disabled = emptySet(),
            label = ::shortLabelStream,
        ) { type ->
            stopAndClear(slots, slotMetrics)
            slots = emptyList()
            selectedStreams = if (selectedStreams.contains(type)) selectedStreams - type
            else selectedStreams + type
            status = "Idle"
        }

        Text("Source", style = MaterialTheme.typography.labelLarge)
        SingleChipRow(
            values = Source.values().toList(),
            selected = source,
            label = ::shortLabelSource,
        ) {
            stopAndClear(slots, slotMetrics)
            slots = emptyList()
            source = it
            if (it == Source.TONE) {
                pickedFile = null
                pickedFileLabel = null
            }
            status = "Idle"
        }
        if (source == Source.FILE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickLauncher.launch(arrayOf("audio/*", "video/mp4")) },
                    enabled = !working,
                ) {
                    Text(if (pickedFile == null) "Pick file" else "Pick another")
                }
                pickedFileLabel?.let { Text("Loaded: $it") }
            }
        }

        Text("Format", style = MaterialTheme.typography.labelLarge)
        if (source == Source.TONE) {
            SingleChipRow(
                values = AudioFormat.values().toList(),
                selected = format,
                disabled = formatDisabled,
                label = ::shortLabelFormat,
            ) {
                stopAndClear(slots, slotMetrics)
                slots = emptyList()
                format = it
                status = "Idle"
            }
        } else {
            Text("Auto-detected: $format (from picked file)")
        }

        Text("Route (multi-select)", style = MaterialTheme.typography.labelLarge)
        MultiChipRow(
            values = Route.values().toList(),
            selected = selectedRoutes,
            disabled = routeDisabled,
            label = ::shortLabelRoute,
        ) { route ->
            stopAndClear(slots, slotMetrics)
            slots = emptyList()
            selectedRoutes = if (selectedRoutes.contains(route)) selectedRoutes - route
            else selectedRoutes + route
            status = "Idle"
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (selectedStreams.isEmpty() || selectedRoutes.isEmpty()) {
                        status = "Pick at least one stream type and one route."
                        return@Button
                    }
                    stopAndClear(slots, slotMetrics)
                    slots = emptyList()
                    slotMetrics.clear()
                    working = true
                    status = "Preparing..."
                    val streamsSnapshot = selectedStreams.toList()
                    val routesSnapshot = selectedRoutes.toList()
                    val sourceSnapshot = source
                    val formatSnapshot = format
                    val pickedSnapshot = pickedFile
                    AppLogger.i(
                        TAG,
                        "Play start streams=$streamsSnapshot routes=$routesSnapshot src=$sourceSnapshot fmt=$formatSnapshot",
                    )
                    scope.launch {
                        val newSlots = mutableListOf<EngineSlot>()
                        try {
                            for (st in streamsSnapshot) {
                                val srcFile = withContext(Dispatchers.IO) {
                                    sourceFileFor(context, sourceSnapshot, st, formatSnapshot, pickedSnapshot)
                                }
                                val sharedPcm: PcmData? = if (st != StreamType.OFFLOAD) {
                                    AppLogger.i(TAG, "decoding for stream=$st file=${srcFile.name}")
                                    withContext(Dispatchers.IO) { decode(formatSnapshot, srcFile) }
                                } else null

                                for (r in routesSnapshot) {
                                    val plan = routeController.resolve(r)
                                    if (plan.missingHints.isNotEmpty()) {
                                        val msg = "Skip ${shortLabelStream(st)}/${shortLabelRoute(r)}: ${plan.missingHints.joinToString()}"
                                        AppLogger.w(TAG, msg)
                                        status = msg
                                        continue
                                    }
                                    val engine: PlaybackEngine = try {
                                        when (st) {
                                            StreamType.OFFLOAD -> OffloadEngine(context, srcFile, formatSnapshot, caps)
                                            StreamType.ULL -> AAudioEngine(sharedPcm!!)
                                            else -> AudioTrackEngine(sharedPcm!!, st)
                                        }
                                    } catch (t: Throwable) {
                                        AppLogger.e(TAG, "engine ctor failed for $st/$r", t)
                                        status = "${shortLabelStream(st)}/${shortLabelRoute(r)} ctor: ${t.message}"
                                        continue
                                    }
                                    try {
                                        engine.open(
                                            PlaybackConfig(
                                                streamType = st,
                                                format = formatSnapshot,
                                                assetPath = srcFile.absolutePath,
                                                preferredDevice = plan.primary,
                                                durationSec = 999,
                                            )
                                        )
                                        engine.start()
                                        newSlots += EngineSlot(
                                            "${shortLabelStream(st)}@${shortLabelRoute(r)}",
                                            st, r, engine,
                                        )
                                        AppLogger.i(TAG, "started $st@$r")
                                    } catch (t: Throwable) {
                                        AppLogger.e(TAG, "open/start failed for $st/$r", t)
                                        status = "${shortLabelStream(st)}/${shortLabelRoute(r)} open: ${t.message}"
                                        runCatching { engine.stop() }
                                    }
                                }
                            }
                            slots = newSlots
                            status = if (newSlots.isEmpty()) "No engine started — see log"
                            else "Playing ${newSlots.size} engine(s)"
                        } catch (t: Throwable) {
                            AppLogger.e(TAG, "Play pipeline failed (outer)", t)
                            status = "Play failed: ${t.javaClass.simpleName}: ${t.message}"
                            newSlots.forEach { runCatching { it.engine.stop() } }
                            slots = emptyList()
                        } finally {
                            working = false
                        }
                    }
                },
                enabled = !working && slots.isEmpty() && selectedStreams.isNotEmpty() && selectedRoutes.isNotEmpty(),
            ) { Text(if (working) "Working..." else "Play") }

            Button(
                onClick = {
                    AppLogger.i(TAG, "Stop pressed")
                    stopAndClear(slots, slotMetrics)
                    slots = emptyList()
                    status = "Stopped"
                },
                enabled = slots.isNotEmpty(),
            ) { Text("Stop") }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Status: $status")
                Text("Selected: ${selectedStreams.joinToString { it.name }} / ${format.name} / ${selectedRoutes.joinToString { it.name }} / ${source.name}")
                if (selectedStreams.contains(StreamType.OFFLOAD)) {
                    val capsStr = AudioFormat.values()
                        .filter { it != AudioFormat.WAV }
                        .joinToString("  ") { "${it.name}${if (caps.supported[it] == true) " ✓" else " ✗"}" }
                    Text("OFFLOAD caps: $capsStr")
                }
                if (slots.isNotEmpty()) {
                    slots.forEach { slot ->
                        val m = slotMetrics[slot.label]
                        Text("— ${slot.label} —")
                        if (m != null) {
                            Text("  underrun=${m.underrunCount} xRun=${m.xRunCount}")
                            Text("  perf=${m.performanceMode} share=${m.sharingMode} mmap=${m.isMmap}")
                            Text("  routed dev id=${m.routedDeviceId} type=${m.routedDeviceType}")
                            m.errorMessage?.let { Text("  error=$it") }
                        }
                    }
                }
            }
        }

        val ullSlot = slots.firstOrNull { it.streamType == StreamType.ULL }
        if (ullSlot != null) {
            val m = slotMetrics[ullSlot.label]
            if (m != null && (m.performanceMode != PerformanceMode.LOW_LATENCY ||
                        m.sharingMode != SharingMode.EXCLUSIVE ||
                        !m.isMmap)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("ULL fallback active", style = MaterialTheme.typography.titleSmall)
                        Text("device returned perf=${m.performanceMode}, sharing=${m.sharingMode}, mmap=${m.isMmap}")
                        Text("Manual screen does not enforce. Matrix runner will prompt.")
                    }
                }
            }
        }

        Text(
            "로그는 Reports 탭 하단에서 확인 (이 화면에 두면 스크롤 충돌로 ANR 가능).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun <T> MultiChipRow(
    values: List<T>,
    selected: Set<T>,
    disabled: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (v in values) {
            FilterChip(
                selected = selected.contains(v),
                onClick = { onToggle(v) },
                label = { Text(label(v)) },
                enabled = !disabled.contains(v),
            )
        }
    }
}

@Composable
private fun <T> SingleChipRow(
    values: List<T>,
    selected: T,
    disabled: Set<T> = emptySet(),
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (v in values) {
            FilterChip(
                selected = v == selected,
                onClick = { if (v != selected) onSelect(v) },
                label = { Text(label(v)) },
                enabled = !disabled.contains(v),
            )
        }
    }
}

private fun shortLabelStream(s: StreamType): String = when (s) {
    StreamType.RINGTONE -> "RING"
    StreamType.DEEP_BUFFER -> "DEEP"
    StreamType.FAST_LL -> "LL"
    StreamType.ULL -> "ULL"
    StreamType.OFFLOAD -> "OFL"
    StreamType.FAST_OTHERS -> "FAST"
}

private fun shortLabelFormat(f: AudioFormat): String = f.name

private fun shortLabelRoute(r: Route): String = when (r) {
    Route.SPEAKER -> "SPK"
    Route.BT -> "BT"
    Route.USB -> "USB"
}

private fun shortLabelSource(s: Source): String = when (s) {
    Source.TONE -> "TONE"
    Source.FILE -> "FILE"
}

private fun freqHz(s: StreamType): Int = when (s) {
    StreamType.RINGTONE -> 440
    StreamType.DEEP_BUFFER -> 523
    StreamType.FAST_LL -> 659
    StreamType.ULL -> 784
    StreamType.OFFLOAD -> 880
    StreamType.FAST_OTHERS -> 988
}

private fun assetPathFor(s: StreamType, f: AudioFormat): String {
    val ext = when (f) {
        AudioFormat.WAV -> "wav"
        AudioFormat.MP3 -> "mp3"
        AudioFormat.AAC -> "m4a"
        AudioFormat.MP4 -> "mp4"
    }
    return "audio/sine_${freqHz(s)}_48k_5s.$ext"
}

private fun sourceFileFor(
    context: Context,
    source: Source,
    streamType: StreamType,
    format: AudioFormat,
    pickedFile: File?,
): File = when (source) {
    Source.TONE -> extractBuiltinAsset(context, assetPathFor(streamType, format))
    Source.FILE -> pickedFile ?: error("No file picked")
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
    AppLogger.i(TAG, "importPickedFile display=$display mime=$mime detected=$format")
    val safeName = display.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val outName = "user-${System.currentTimeMillis()}-$safeName"
    val out = File(context.cacheDir, "audio/$outName")
    out.parentFile?.mkdirs()
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("ContentResolver.openInputStream returned null for $uri")
    input.use {
        FileOutputStream(out).use { dst -> it.copyTo(dst) }
    }
    if (out.length() == 0L) {
        throw IllegalStateException("Imported file is empty: $outName (uri=$uri)")
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

private fun decode(format: AudioFormat, file: File): PcmData = when (format) {
    AudioFormat.WAV -> file.inputStream().use { WavReader.read(it) }
    else -> PcmDecoder.decodeToPcm(file)
}

private fun stopAndClear(
    slots: List<EngineSlot>,
    metrics: MutableMap<String, PlaybackMetrics>,
) {
    slots.forEach { runCatching { it.engine.stop() } }
    metrics.clear()
}
