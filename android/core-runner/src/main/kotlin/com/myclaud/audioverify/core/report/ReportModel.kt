package com.myclaud.audioverify.core.report

import com.myclaud.audioverify.core.engine.PlaybackMetrics
import com.myclaud.audioverify.core.runner.AssertionResult
import com.myclaud.audioverify.core.runner.AssertionVerdict
import com.myclaud.audioverify.core.runner.TestCase

data class CaseRecord(
    val case: TestCase,
    val overall: AssertionResult,
    val verdicts: List<AssertionVerdict>,
    val metricsByLabel: Map<String, PlaybackMetrics>,
    val relaxDecision: String?,
    val skippedReason: String?,
)

data class ReportModel(
    val deviceModel: String,
    val socModel: String,
    val androidRelease: String,
    val timestampMs: Long,
    val records: List<CaseRecord>,
)
