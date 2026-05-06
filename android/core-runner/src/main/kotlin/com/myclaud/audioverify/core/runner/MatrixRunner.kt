package com.myclaud.audioverify.core.runner

import android.content.Context
import android.os.Build
import com.myclaud.audioverify.core.engine.AAudioEngine
import com.myclaud.audioverify.core.engine.AudioFormat
import com.myclaud.audioverify.core.engine.AudioTrackEngine
import com.myclaud.audioverify.core.engine.OffloadCapabilityProbe
import com.myclaud.audioverify.core.engine.OffloadCaps
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
import com.myclaud.audioverify.core.routing.RoutePlan
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

private fun freqHz(streamType: StreamType): Int = when (streamType) {
    StreamType.RINGTONE -> 440
    StreamType.DEEP_BUFFER -> 523
    StreamType.FAST_LL -> 659
    StreamType.ULL -> 784
    StreamType.OFFLOAD -> 880
    StreamType.FAST_OTHERS -> 988
}

internal fun assetPathFor(streamType: StreamType, format: AudioFormat): String {
    val freq = freqHz(streamType)
    val ext = when (format) {
        AudioFormat.WAV -> "wav"
        AudioFormat.MP3 -> "mp3"
        AudioFormat.AAC -> "m4a"
        AudioFormat.MP4 -> "mp4"
    }
    return "audio/sine_${freq}_48k_5s.$ext"
}

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
        pruneOffloadByDeviceCaps: Boolean = false,
    ): ReportModel {
        val records = mutableListOf<CaseRecord>()
        val auto = cases.filter { it.mode == Mode.AUTO }
        val caps: OffloadCaps? = if (pruneOffloadByDeviceCaps) OffloadCapabilityProbe.probe(context) else null
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

            if (caps != null && case.streamType == StreamType.OFFLOAD &&
                caps.supported[case.format] != true
            ) {
                records += CaseRecord(
                    case = case,
                    overall = AssertionResult.PASS,
                    verdicts = emptyList(),
                    metricsByLabel = emptyMap(),
                    relaxDecision = "AUTO_SKIPPED",
                    skippedReason = "Device offload caps: ${case.format} not supported",
                )
                continue
            }

            val routePlan = routeController.resolve(case.route)
            if (routePlan.missingHints.isNotEmpty()) {
                records += CaseRecord(
                    case = case,
                    overall = AssertionResult.PASS,
                    verdicts = emptyList(),
                    metricsByLabel = emptyMap(),
                    relaxDecision = "AUTO_SKIPPED",
                    skippedReason = "No device connected for ${case.route} — ${routePlan.missingHints.joinToString("; ")}",
                )
                continue
            }

            val (verdicts, metricsByLabel) = runOneCase(case, durationSec, routePlan, thresholds, caps)
            var overall = Assertion.overall(verdicts)
            var relaxLabel: String? = null

            if (overall == AssertionResult.NEEDS_HUMAN) {
                val decision = gate.ask(case.id, verdicts, emptyList())
                relaxLabel = decision.name
                overall = when (decision) {
                    RelaxDecision.ACCEPT_RELAXED -> AssertionResult.PASS
                    RelaxDecision.MARK_FAIL -> AssertionResult.FAIL
                    RelaxDecision.RETRY -> {
                        val (v2, m2) = runOneCase(case, durationSec, routePlan, thresholds, caps)
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
            prunedByOffloadCaps = pruneOffloadByDeviceCaps,
        )
    }

    private suspend fun runOneCase(
        case: TestCase,
        durationSec: Int,
        routePlan: RoutePlan,
        thresholds: Thresholds,
        caps: OffloadCaps?,
    ): Pair<List<AssertionVerdict>, Map<String, PlaybackMetrics>> {
        val assetFile = extractAssetToCache(assetPathFor(case.streamType, case.format))
        val engine = buildEngine(case, assetFile, caps)
        val config = PlaybackConfig(
            streamType = case.streamType,
            format = case.format,
            assetPath = assetFile.absolutePath,
            preferredDevice = routePlan.primary,
            durationSec = durationSec,
        )

        try {
            engine.open(config)
        } catch (e: IllegalStateException) {
            return listOf(
                AssertionVerdict("stream-opened", AssertionResult.FAIL, "true", "false: ${e.message}", relaxable = false)
            ) to emptyMap()
        }

        engine.start()

        val sampleIntervalMs = (1000L / thresholds.samplingHz).coerceAtLeast(100)
        val totalSamples = (durationSec * thresholds.samplingHz).coerceAtLeast(1)
        repeat(totalSamples) { delay(sampleIntervalMs) }

        val finalMetrics = engine.snapshot()
        runCatching { engine.stop() }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val verdicts = Assertion.evaluate(case, finalMetrics, thresholds, routePlan.primary?.id, deviceModel)
        return verdicts to mapOf("primary" to finalMetrics)
    }

    private fun buildEngine(case: TestCase, assetFile: File, caps: OffloadCaps?): PlaybackEngine =
        when (case.streamType) {
            StreamType.OFFLOAD -> OffloadEngine(
                context,
                assetFile,
                case.format,
                caps ?: OffloadCapabilityProbe.probe(context),
            )
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
