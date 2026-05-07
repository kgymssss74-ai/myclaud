package com.myclaud.audioverify.core.report

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object JsonReportWriter {
    fun write(report: ReportModel, outDir: File): File {
        val root = JSONObject().apply {
            put("device", report.deviceModel)
            put("soc", report.socModel)
            put("androidRelease", report.androidRelease)
            put("timestampMs", report.timestampMs)
            put("prunedByOffloadCaps", report.prunedByOffloadCaps)
            val arr = JSONArray()
            for (r in report.records) {
                arr.put(JSONObject().apply {
                    put("caseId", r.case.id)
                    put("streamType", r.case.streamType.name)
                    put("format", r.case.format.name)
                    put("route", r.case.route.name)
                    put("mode", r.case.mode.name)
                    put("overall", r.overall.name)
                    r.relaxDecision?.let { put("relaxDecision", it) }
                    r.skippedReason?.let { put("skippedReason", it) }
                    val vArr = JSONArray()
                    for (v in r.verdicts) {
                        vArr.put(JSONObject().apply {
                            put("name", v.name)
                            put("result", v.result.name)
                            put("expected", v.expected)
                            put("actual", v.actual)
                            put("relaxable", v.relaxable)
                        })
                    }
                    put("verdicts", vArr)
                    val mObj = JSONObject()
                    for ((label, m) in r.metricsByLabel) {
                        mObj.put(label, JSONObject().apply {
                            put("opened", m.opened)
                            put("performanceMode", m.performanceMode.name)
                            put("sharingMode", m.sharingMode.name)
                            put("isMmap", m.isMmap)
                            put("routedDeviceId", m.routedDeviceId ?: JSONObject.NULL)
                            put("routedDeviceType", m.routedDeviceType ?: JSONObject.NULL)
                            put("underrunCount", m.underrunCount)
                            put("xRunCount", m.xRunCount)
                            put("framesPerBurst", m.framesPerBurst ?: JSONObject.NULL)
                            put("sampleRate", m.sampleRate ?: JSONObject.NULL)
                            put("errorMessage", m.errorMessage ?: JSONObject.NULL)
                        })
                    }
                    put("metrics", mObj)
                })
            }
            put("records", arr)
        }
        val out = File(outDir, "report.json")
        out.writeText(root.toString(2))
        return out
    }
}
