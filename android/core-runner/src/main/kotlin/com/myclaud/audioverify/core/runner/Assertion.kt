package com.myclaud.audioverify.core.runner

import com.myclaud.audioverify.core.engine.PerformanceMode
import com.myclaud.audioverify.core.engine.PlaybackMetrics
import com.myclaud.audioverify.core.engine.SharingMode
import com.myclaud.audioverify.core.engine.StreamType

enum class AssertionResult { PASS, FAIL, NEEDS_HUMAN }

data class AssertionVerdict(
    val name: String,
    val result: AssertionResult,
    val expected: String,
    val actual: String,
    val relaxable: Boolean,
)

/**
 * Computes per-case verdicts. Anything that *could* be a legitimate device
 * limitation is marked relaxable=true so the runner surfaces a [RelaxationGate]
 * prompt instead of silently passing or hard-failing. The user must decide.
 */
object Assertion {

    fun evaluate(
        case: TestCase,
        metrics: PlaybackMetrics,
        thresholds: Thresholds,
        requestedDeviceId: Int?,
        deviceModel: String,
    ): List<AssertionVerdict> {
        val out = mutableListOf<AssertionVerdict>()

        out += AssertionVerdict(
            name = "stream-opened",
            result = if (metrics.opened) AssertionResult.PASS else AssertionResult.FAIL,
            expected = "true",
            actual = metrics.opened.toString(),
            relaxable = false,
        )

        if (case.streamType == StreamType.ULL) {
            val perfOk = metrics.performanceMode == PerformanceMode.LOW_LATENCY
            out += AssertionVerdict(
                "ull-performance-mode",
                if (perfOk) AssertionResult.PASS else AssertionResult.NEEDS_HUMAN,
                "LOW_LATENCY",
                metrics.performanceMode.name,
                relaxable = true,
            )
            val excOk = metrics.sharingMode == SharingMode.EXCLUSIVE
            out += AssertionVerdict(
                "ull-sharing-mode",
                if (excOk) AssertionResult.PASS else AssertionResult.NEEDS_HUMAN,
                "EXCLUSIVE",
                metrics.sharingMode.name,
                relaxable = true,
            )
            out += AssertionVerdict(
                "ull-mmap",
                if (metrics.isMmap) AssertionResult.PASS else AssertionResult.NEEDS_HUMAN,
                "true",
                metrics.isMmap.toString(),
                relaxable = true,
            )

            val frames = metrics.framesPerBurst ?: 0
            val sr = metrics.sampleRate ?: 48_000
            val latencyMs = if (frames > 0 && sr > 0) (frames.toDouble() * 2 / sr * 1000).toInt() else Int.MAX_VALUE
            val budget = if (deviceModel.contains("S25", ignoreCase = true)) {
                thresholds.ullLatencyBudgetMsS25
            } else {
                thresholds.ullLatencyBudgetMsTabS10InfoOnly
            }
            val enforced = deviceModel.contains("S25", ignoreCase = true)
            out += AssertionVerdict(
                "ull-latency-ms",
                when {
                    latencyMs <= budget -> AssertionResult.PASS
                    enforced -> AssertionResult.NEEDS_HUMAN
                    else -> AssertionResult.PASS
                },
                "<=$budget",
                latencyMs.toString(),
                relaxable = enforced,
            )
        }

        if (requestedDeviceId != null && requestedDeviceId != 0) {
            val match = metrics.routedDeviceId == requestedDeviceId
            out += AssertionVerdict(
                "routed-device-matches",
                if (match) AssertionResult.PASS else AssertionResult.FAIL,
                "id=$requestedDeviceId",
                "id=${metrics.routedDeviceId}",
                relaxable = false,
            )
        }

        val ulMax = thresholds.underrunMaxOver5s[case.streamType] ?: Int.MAX_VALUE
        val totalGlitches = metrics.underrunCount + metrics.xRunCount
        out += AssertionVerdict(
            "underrun-budget",
            if (totalGlitches <= ulMax) AssertionResult.PASS else AssertionResult.FAIL,
            "<=$ulMax (5s)",
            "underrun=${metrics.underrunCount} xRun=${metrics.xRunCount}",
            relaxable = false,
        )

        return out
    }

    fun overall(verdicts: List<AssertionVerdict>): AssertionResult = when {
        verdicts.any { it.result == AssertionResult.FAIL } -> AssertionResult.FAIL
        verdicts.any { it.result == AssertionResult.NEEDS_HUMAN } -> AssertionResult.NEEDS_HUMAN
        else -> AssertionResult.PASS
    }
}
