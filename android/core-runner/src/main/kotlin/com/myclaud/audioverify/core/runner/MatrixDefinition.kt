package com.myclaud.audioverify.core.runner

import com.myclaud.audioverify.core.engine.AudioFormat
import com.myclaud.audioverify.core.engine.StreamType
import com.myclaud.audioverify.core.routing.Route
import org.json.JSONObject

enum class Mode { AUTO, MANUAL }

data class TestCase(
    val streamType: StreamType,
    val format: AudioFormat,
    val route: Route,
    val mode: Mode,
    val invalidReason: String? = null,
) {
    val id: String get() = "${streamType.name}-${format.name}-${route.name}-${mode.name}"
}

data class Thresholds(
    val underrunMaxOver5s: Map<StreamType, Int>,
    val ullLatencyBudgetMsS25: Int,
    val ullLatencyBudgetMsTabS10InfoOnly: Int,
    val samplingHz: Int,
)

object MatrixDefinition {

    fun expand(matrixJson: String, thresholdsJson: String): Pair<List<TestCase>, Thresholds> {
        val m = JSONObject(matrixJson)
        val streamTypes = m.getJSONArray("streamTypes").asStringList().map { StreamType.valueOf(it) }
        val formats = m.getJSONArray("formats").asStringList().map { AudioFormat.valueOf(it) }
        val routes = m.getJSONArray("routes").asStringList().map { Route.valueOf(it) }
        val modes = m.getJSONArray("modes").asStringList().map { Mode.valueOf(it) }

        val invalids = mutableListOf<Pair<Map<String, String>, String>>()
        val invArr = m.optJSONArray("invalidCombinations")
        if (invArr != null) {
            for (i in 0 until invArr.length()) {
                val obj = invArr.getJSONObject(i)
                val match = obj.getJSONObject("match")
                val map = mutableMapOf<String, String>()
                match.keys().forEach { k -> map[k] = match.getString(k) }
                invalids += map to obj.getString("reason")
            }
        }

        val cases = mutableListOf<TestCase>()
        for (s in streamTypes) for (f in formats) for (r in routes) for (mo in modes) {
            val invalidReason = invalids.firstOrNull { (match, _) ->
                match.all { (k, v) ->
                    when (k) {
                        "streamType" -> s.name == v
                        "format" -> f.name == v
                        "route" -> r.name == v
                        "mode" -> mo.name == v
                        else -> false
                    }
                }
            }?.second
            cases += TestCase(s, f, r, mo, invalidReason)
        }

        val t = JSONObject(thresholdsJson)
        val tu = t.getJSONObject("underrunMaxOver5s")
        val underrun = StreamType.values().associateWith { st ->
            tu.optInt(st.name, Int.MAX_VALUE)
        }
        val lat = t.getJSONObject("ullLatencyBudgetMs")
        val thresholds = Thresholds(
            underrunMaxOver5s = underrun,
            ullLatencyBudgetMsS25 = lat.getInt("S25_ENFORCE"),
            ullLatencyBudgetMsTabS10InfoOnly = lat.getInt("TAB_S10_INFO_ONLY"),
            samplingHz = t.optInt("samplingHz", 1),
        )
        return cases to thresholds
    }

    private fun org.json.JSONArray.asStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
