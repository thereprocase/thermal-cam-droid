package com.thereprocase.thermalfield

import java.util.Locale

internal data class IsothermLimits(val lower: Float, val upper: Float)

internal fun parseIsotherm(mode: Int, lowerText: String, upperText: String, fahrenheit: Boolean): IsothermLimits? {
    fun parse(text: String): Float? = text.toFloatOrNull()?.let {
        val celsius = if (fahrenheit) (it-32)/1.8f else it
        celsius.takeIf(Float::isFinite)
    }
    return when (mode) {
        1 -> {
            val lower = parse(lowerText) ?: return null
            val upper = parse(upperText) ?: return null
            if (upper < lower) null else IsothermLimits(lower, upper)
        }
        // The native schema retains two ordered limits. Equal limits encode a
        // single threshold without making an unused editor field a constraint.
        2 -> parse(lowerText)?.let { IsothermLimits(it, it) }
        3 -> parse(upperText)?.let { IsothermLimits(it, it) }
        else -> null
    }
}

internal fun isothermDescription(mode: Int, lower: Double, upper: Double, fahrenheit: Boolean): String {
    fun temperature(celsius: Double): String = String.format(Locale.US, "%.1f °%s", if (fahrenheit) celsius*1.8+32 else celsius, if (fahrenheit) "F" else "C")
    return when (mode) {
        1 -> "band ${temperature(lower)}–${temperature(upper)}"
        2 -> "below ${temperature(lower)}"
        3 -> "above ${temperature(upper)}"
        else -> "off"
    }
}
