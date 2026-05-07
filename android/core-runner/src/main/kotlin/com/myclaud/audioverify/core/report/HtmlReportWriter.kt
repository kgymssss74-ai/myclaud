package com.myclaud.audioverify.core.report

import com.myclaud.audioverify.core.runner.AssertionResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HtmlReportWriter {

    fun write(report: ReportModel, outDir: File): File {
        val sb = StringBuilder()
        sb.appendLine("<!doctype html><html><head><meta charset=utf-8>")
        sb.appendLine("<title>Audio Verify Report</title>")
        sb.appendLine("<style>")
        sb.appendLine("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:1em}")
        sb.appendLine("table{border-collapse:collapse;width:100%}")
        sb.appendLine("td,th{border:1px solid #ccc;padding:6px;font-size:13px;vertical-align:top}")
        sb.appendLine("th{background:#eee}")
        sb.appendLine(".pass{background:#d6f5d6}.fail{background:#f5d6d6}.relaxed{background:#fff2cc}.skipped{background:#e0e0e0}")
        sb.appendLine("</style></head><body>")
        sb.appendLine("<h1>Audio Verify Report</h1>")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.appendLine("<p><b>Device:</b> ${esc(report.deviceModel)} &middot; <b>SoC:</b> ${esc(report.socModel)} &middot; <b>Android:</b> ${esc(report.androidRelease)}</p>")
        sb.appendLine("<p><b>Run:</b> ${fmt.format(Date(report.timestampMs))}</p>")

        val pass = report.records.count { it.overall == AssertionResult.PASS && it.relaxDecision == null && it.skippedReason == null }
        val fail = report.records.count { it.overall == AssertionResult.FAIL }
        val relaxed = report.records.count { it.relaxDecision != null }
        val skipped = report.records.count { it.skippedReason != null }
        sb.appendLine("<p><b>Summary:</b> $pass PASS &middot; $fail FAIL &middot; $relaxed RELAXED &middot; $skipped SKIPPED &middot; ${report.records.size} total</p>")

        sb.appendLine("<table><thead><tr>")
        sb.appendLine("<th>Case</th><th>Result</th><th>Verdicts</th><th>Metrics</th><th>Notes</th>")
        sb.appendLine("</tr></thead><tbody>")
        for (r in report.records) {
            val cls = when {
                r.skippedReason != null -> "skipped"
                r.relaxDecision != null -> "relaxed"
                r.overall == AssertionResult.PASS -> "pass"
                r.overall == AssertionResult.FAIL -> "fail"
                else -> "relaxed"
            }
            sb.appendLine("<tr class=$cls>")
            sb.appendLine("<td>${esc(r.case.id)}</td>")
            sb.appendLine("<td>${r.overall.name}${r.relaxDecision?.let { " ($it)" } ?: ""}</td>")
            sb.append("<td>")
            for (v in r.verdicts) sb.append("${esc(v.name)}: ${v.result.name} (exp=${esc(v.expected)} act=${esc(v.actual)})<br>")
            sb.appendLine("</td>")
            sb.append("<td>")
            for ((label, m) in r.metricsByLabel) {
                sb.append("<b>$label</b>: ur=${m.underrunCount} xr=${m.xRunCount} perf=${m.performanceMode} share=${m.sharingMode} mmap=${m.isMmap} dev=${m.routedDeviceId} type=${m.routedDeviceType}<br>")
            }
            sb.appendLine("</td>")
            sb.appendLine("<td>${esc(r.skippedReason ?: "")}</td>")
            sb.appendLine("</tr>")
        }
        sb.appendLine("</tbody></table></body></html>")

        val out = File(outDir, "report.html")
        out.writeText(sb.toString())
        return out
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
