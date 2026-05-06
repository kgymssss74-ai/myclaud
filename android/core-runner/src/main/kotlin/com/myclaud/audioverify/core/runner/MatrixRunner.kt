package com.myclaud.audioverify.core.runner

import android.content.Context
import android.os.Build
import com.myclaud.audioverify.core.engine.AAudioEngine
import com.myclaud.audioverify.core.engine.AudioFormat
import com.myclaud.audioverify.core.engine.AudioTrackEngine
import com.myclaud.audioverify.core.engine.OffloadEngine
import com.myclaud.audioverify.core.engine.PlaybackConfig
import com.myclaud.audioverify.core.engine.PlaybackEngine
import com.myclaud.audioverify.core.engine.PlaybackMetrics
import com.myclaud.audioverify.core.engine.StreamType
import com.myclaud.audioverify.core.engine.decode.PcmData
import com.myclaud.audioverify.core.engine.decode.PcmDecoder
import com.myclaud.audioverify.core.engine.decode.WavReader
import com.myclaud.audioverify.core.report.CaseRecord
import com.myclaud.audioverify.core.report.ReportModel
import com.myclaud.audioverify.core.routing.AudioRouteController
import com.myclaud.audioverify.core.routing.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

data class RunnerProgress(
    val total: Int,
    val completed: Int,
    val currentCaseId: String?,
)

class MatrixRunner(
    private val context: Context,
    private val gate: RelaxationGate,
    private val routeController: AudioRouteController,
) {
    private val _progress = MutableStateFlow(RunnerProgress(0, 0, null))
    val progress: StateFlow<RunnerProgress> = _progress

    suspend fun run(
        cases: List<TestCase>,
        thresholds: Thresholds,
        durationSec: Int,
    ): ReportModel {
        val records = mutableListOf<CaseRecord>()
        val auto = cases.filter { it.mode == Mode.AUTO }
        _progress.value = RunnerProgress(auto.size, 0, null)

        for ((i, case) in auto.withIndex()) {
            _progress.value = RunnerProgress(auto.size, i, case.id)

            if (case.invalidReason != null) {
                records += CaseRecord(
                    case = case,
                    overall = AssertionResult.PASS,
                    verdicts = emptyList(),
                    metricsByLabel = emptyMap(),
                    relaxDecision = null,
                    skippedReason = case.invalidReason,
                )
                continue
            }

            var routePlan = routeController.resolve(case.route)
            while (routePlan.missingHints.isNotEmpty()) {
                val decision = gate.ask(
                    caseId = case.id,
                    verdicts = listOf(
                        AssertionVerdict(
                            "device-available",
                            AssertionResult.NEEDS_HUMAN,
                            "device connected",
                            routePlan.missingHints.joinToString("; "),
                            relaxable = true,
                        )
                    ),
                    missingHints = routePlan.missingHints,
                )
                when (decision) {
                    RelaxDecision.RETRY -> routePlan = routeController.resolve(case.route)
                    else -> {
                        records += CaseRecord(
                            case = case,
                            overall = if (decision == RelaxDecision.ACCEPT_RELAXED) AssertionResult.PASS else AssertionResult.FAIL,
                            verdicts = emptyList(),
                            metricsByLabel = emptyMap(),
                            relaxDecision = decision.name,
                            skippedReason = "Required device not connected",
                        )
                        break
                    }
                }
            }
            if (routePlan.missingHints.isNotEmpty()) continue

            val (verdicts, metricsByLabel) = runOneCase(case, durationSec, routePlan, thresholds)
            var overall = Assertion.overall(verdicts)
            var relaxLabel: String? = null

            if (overall == AssertionResult.NEEDS_HUMAN) {
                val decision = gate.ask(case.id, verdicts, emptyList())
                relaxLabel = decision.name
                overall = when (decision) {
                    RelaxDecision.ACCEPT_RELAXED -> AssertionResult.PASS
                    RelaxDecision.MARK_FAIL -> AssertionResult.FAIL
                    RelaxDecision.RETRY -> {
                        val (v2, m2) = runOneCase(case, durationSec, routePlan, thresholds)
                        records += CaseRecord(case, Assertion.overall(v2), v2, m2, "RETRY-${decision.name}", null)
                        continue
                    }
                }
            }

            records += CaseRecord(case, overall, verdicts, metricsByLabel, relaxLabel, null)
        }

        _progress.value = RunnerProgress(auto.size, auto.size, null)

        return ReportModel(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            socModel = Build.SOC_MODEL ?: "unknown",
            androidRelease = Build.VERSION.RELEASE,
            timestampMs = System.currentTimeMillis(),
            records = records,
        )
    }

    private suspend fun runOneCase(
        case: TestCase,
        durationSec: Int,
        routePlan: com.myclaud.audioverify.core.routing.RoutePlan,
        thresholds: Thresholds,
    ): Pair<List<AssertionVerdict>, Map<String, PlaybackMetrics>> {
        val assetFile = extractAssetToCache(assetPathFor(case.format))
        val engines = mutableListOf<Pair<String, PlaybackEngine>>()

        val primaryEngine = buildEngine(case, assetFile)
        val primaryConfig = PlaybackConfig(
            streamType = case.streamType,
            format = case.format,
            assetPath = assetFile.absolutePath,
            preferredDevice = routePlan.primary,
            durationSec = durationSec,
        )

        try {
            primaryEngine.open(primaryConfig)
        } catch (e: IllegalStateException) {
            return listOf(
                AssertionVerdict("stream-opened", AssertionResult.FAIL, "true", "false: ${e.message}", relaxable = false)
            ) to emptyMap()
        }
        engines += "primary" to primaryEngine

        if (routePlan.secondary != null) {
            val secondary = buildEngine(case, assetFile)
            val cfg2 = primaryConfig.copy(preferredDevice = routePlan.secondary)
            try {
                secondary.open(cfg2)
                engines += "secondary" to secondary
            } catch (e: IllegalStateException) {
                primaryEngine.stop()
                return listOf(
                    AssertionVerdict("secondary-stream-opened", AssertionResult.FAIL, "true", "false: ${e.message}", relaxable = false)
                ) to emptyMap()
            }
        }

        engines.forEach { it.second.start() }

        val samples = mutableMapOf<String, MutableList<PlaybackMetrics>>()
        val sampleIntervalMs = (1000L / thresholds.samplingHz).coerceAtLeast(100)
        val totalSamples = (durationSec * thresholds.samplingHz).coerceAtLeast(1)
        repeat(totalSamples) {
            delay(sampleIntervalMs)
            engines.forEach { (label, e) ->
                samples.getOrPut(label) { mutableListOf() } += e.snapshot()
            }
        }

        val finalMetrics = engines.associate { (label, e) -> label to e.snapshot() }
        engines.forEach { runCatching { it.second.stop() } }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val verdicts = mutableListOf<AssertionVerdict>()
        finalMetrics.forEach { (label, m) ->
            val requestedId = if (label == "primary") routePlan.primary?.id else routePlan.secondary?.id
            val labeled = Assertion.evaluate(case, m, thresholds, requestedId, deviceModel)
                .map { it.copy(name = "$label/${it.name}") }
            verdicts += labeled
        }
        return verdicts to finalMetrics
    }

    private fun assetPathFor(f: AudioFormat): String = when (f) {
        AudioFormat.WAV -> "audio/sine_1k_48k_16b_5s.wav"
        AudioFormat.MP3 -> "audio/sine_1k_48k_16b_5s.mp3"
        AudioFormat.AAC -> "audio/sine_1k_48k_16b_5s.m4a"
        AudioFormat.MP4 -> "audio/sine_1k_48k_16b_5s.mp4"
    }

    private fun buildEngine(case: TestCase, assetFile: File): PlaybackEngine = when (case.streamType) {
        StreamType.OFFLOAD -> OffloadEngine(context, assetFile, case.format)
        StreamType.ULL -> AAudioEngine(decodeToPcm(case.format, assetFile))
        else -> AudioTrackEngine(decodeToPcm(case.format, assetFile), case.streamType)
    }

    private fun decodeToPcm(format: AudioFormat, file: File): PcmData = when (format) {
        AudioFormat.WAV -> file.inputStream().use { WavReader.read(it) }
        else -> PcmDecoder.decodeToPcm(file)
    }

    private fun extractAssetToCache(assetPath: String): File {
        val name = assetPath.substringAfterLast('/')
        val out = File(context.cacheDir, "audio/$name")
        out.parentFile?.mkdirs()
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
        }
        return out
    }
}
