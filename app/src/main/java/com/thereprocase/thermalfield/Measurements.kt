package com.thereprocase.thermalfield

import org.json.JSONObject

data class Measurement(
    val id: Int, val kind: Int, val x0: Double, val y0: Double, val x1: Double, val y1: Double,
    val minimum: Double, val maximum: Double, val average: Double, val valid: Int, val invalid: Int,
    val profile: List<Double>,
) {
    val label: String get() = "${when (kind) { 1 -> "P"; 2 -> "B"; else -> "L" }}$id"
}

internal fun measurements(json: JSONObject): List<Measurement> {
    val values = json.optJSONArray("measurements") ?: return emptyList()
    return (0 until values.length()).map { index ->
        val item = values.getJSONObject(index)
        val start = item.getJSONArray("display_start"); val end = item.getJSONArray("display_end")
        val profile = item.getJSONArray("profile_celsius")
        Measurement(item.getInt("id"), when (item.getString("kind")) { "spot" -> 1; "box" -> 2; else -> 3 },
            start.getDouble(0), start.getDouble(1), end.getDouble(0), end.getDouble(1),
            item.optDouble("minimum_celsius", Double.NaN), item.optDouble("maximum_celsius", Double.NaN), item.optDouble("average_celsius", Double.NaN),
            item.getInt("valid_samples"), item.getInt("invalid_samples"), (0 until profile.length()).map { profile.optDouble(it, Double.NaN) })
    }
}
