package com.thereprocase.thermalfield

import org.json.JSONObject

// Store sensor coordinates, not displayed positions or measured temperatures:
// orientation and correction may differ when the next session starts.
internal data class MeasurementLayout(
    val geometry: IntArray, val first: Int, val second: Int,
    val isotherm: Int, val lower: Float, val upper: Float, val nextId: Int,
) {
    fun restore(bridge: NativeBridge, engine: Long) {
        bridge.restoreMeasurements(engine, geometry, first, second, isotherm, lower, upper, nextId)
    }

    companion object {
        fun decode(text: String): MeasurementLayout {
            require(text.length <= 16_384) { "Stored measurement layout is too large" }
            val json = JSONObject(text)
            fun integer(value: Any): Int {
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "Stored measurement integer is invalid" }
                return value.toInt()
            }
            fun integer(key: String) = integer(json.get(key))
            require(integer("version") == 1) { "Unsupported stored measurement version" }
            val values = json.getJSONArray("geometry")
            require(values.length() <= 16) { "Too many stored measurements" }
            val packed = IntArray(values.length()*6)
            val ids = mutableSetOf<Int>()
            for (index in 0 until values.length()) {
                val geometry = values.getJSONArray(index)
                require(geometry.length() == 6) { "Invalid stored geometry width" }
                for (column in 0 until 6) packed[index*6+column] = integer(geometry.get(column))
                val start = index*6
                require(packed[start] in 1 until Int.MAX_VALUE && ids.add(packed[start]) && packed[start+1] in 1..3) { "Invalid stored measurement id or kind" }
                require(packed[start+2] in 0..255 && packed[start+4] in 0..255 && packed[start+3] in 0..191 && packed[start+5] in 0..191) { "Stored geometry is outside the sensor" }
            }
            val next = integer("next_id")
            require(next > (ids.maxOrNull() ?: 0) && next < Int.MAX_VALUE) { "Invalid next measurement id" }
            val first = integer("first"); val second = integer("second"); val mode = integer("isotherm")
            val lower = json.getDouble("lower").toFloat(); val upper = json.getDouble("upper").toFloat()
            require(first >= 0 && second >= 0 && mode in 0..3 && lower.isFinite() && upper.isFinite() && upper >= lower) { "Invalid stored measurement options" }
            return MeasurementLayout(packed, first, second, mode, lower, upper, next)
        }
    }
}
