package com.thereprocase.thermalfield

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

data class SavedCapture(val id: String, val rendered: Uri, val raw: Uri, val metadata: Uri, val rawPreferred: Boolean)

internal object CaptureStore {
    fun save(context: Context, packet: ByteArray, fahrenheit: Boolean, rawPreferred: Boolean): SavedCapture {
        val length = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        require(length > 0 && length < packet.size - 4) { "Malformed capture snapshot" }
        val metadata = JSONObject(String(packet, 4, length, Charsets.UTF_8))
        val width = metadata.getInt("rendered_width")
        val height = metadata.getInt("rendered_height")
        require(packet.size == 4 + length + 196608 + width * height * 4) { "Incomplete capture snapshot" }
        val composite = packet.copyOfRange(4 + length, 4 + length + 196608)
        val rgba = packet.copyOfRange(4 + length + 196608, packet.size)
        val timestamp = metadata.getLong("timestamp_unix_ns")
        val id = "ThermalField_${Instant.ofEpochSecond(timestamp / 1_000_000_000, timestamp % 1_000_000_000).toString().replace(":", "-")}"
        metadata.put("app_version", BuildConfig.VERSION_NAME)
        metadata.put("display_unit", if (fahrenheit) "F" else "C")
        metadata.put("raw_width", 256).put("raw_height", 192).put("raw_sensor_orientation", true)
        metadata.put("raw_scaling", "Kelvin × 64; Celsius = word / 64 - 273.15")
        metadata.put("rendered_file", "$id.png").put("raw_file", "${id}_raw.png").put("sidecar_file", "$id.json")
        // Persistent device identifiers are omitted from shared files by default.
        val identity = metadata.optJSONObject("identity")
        identity?.remove("serial")
        metadata.put("persistent_device_identifiers_included", false)
        val configured = identity?.optJSONArray("configured_properties")
        if (!metadata.has("gain_mode")) metadata.put("gain_mode", if (configured?.optInt(5, 1) == 0) "low" else "high")
        metadata.put("atmospheric_transmission_assumed", 1.0)
        val rendered = annotated(context, metadata, rgba, width, height, fahrenheit)
        val raw = RadiometricPng.encode(composite)
        val entries = mutableListOf<Uri>()
        val resolver = context.contentResolver
        try {
            val imageUri = write(resolver, "$id.png", "image/png", rendered, entries)
            val rawUri = write(resolver, "${id}_raw.png", "image/png", raw, entries)
            val jsonUri = write(resolver, "$id.json", "application/json", metadata.toString(2).toByteArray(), entries)
            entries.forEach { resolver.update(it, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
            return SavedCapture(id, imageUri, rawUri, jsonUri, rawPreferred)
        } catch (error: Exception) {
            entries.forEach { runCatching { resolver.delete(it, null, null) } }
            throw error
        }
    }

    private fun write(resolver: ContentResolver, name: String, mime: String, bytes: ByteArray, entries: MutableList<Uri>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ThermalField/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create capture file")
        entries += uri
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open capture file")
        return uri
    }

    private fun annotated(context: Context, metadata: JSONObject, rgba: ByteArray, width: Int, height: Int, fahrenheit: Boolean): ByteArray {
        val colors = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = (y * width + x) * 4
            // glReadPixels starts with the bottom row. This is the single
            // conversion to image-top-first order for screenshots and labels.
            colors[(height - 1 - y) * width + x] = Color.argb(255, rgba[offset].toInt() and 255,
                rgba[offset + 1].toInt() and 255, rgba[offset + 2].toInt() and 255)
        }
        val source = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val scale = 4
        val bitmap = Bitmap.createBitmap(width * scale, height * scale + 264, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, width * scale, height * scale), Paint())
        source.recycle()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.plex_mono_regular); textSize = 22f
        }
        fun temperature(value: Double): String = if (!value.isFinite()) "—" else String.format(Locale.US, "%.1f °%s",
            if (fahrenheit) value * 9 / 5 + 32 else value, if (fahrenheit) "F" else "C")
        for ((label, position, key) in listOf(Triple("MIN", "min_display", "minimum_celsius"),
            Triple("MAX", "max_display", "maximum_celsius"), Triple("C", "center_display", "center_celsius"))) {
            val point = metadata.getJSONArray(position)
            val text = "$label ${temperature(metadata.optDouble(key, Double.NaN))}"
            val x = (point.getDouble(0).toFloat() * width * scale + 12).coerceIn(8f, bitmap.width - paint.measureText(text) - 8)
            val y = (point.getDouble(1).toFloat() * height * scale - 12).coerceIn(28f, height * scale - 12f)
            paint.color = Color.argb(205, 0, 0, 0)
            canvas.drawRect(x - 5, y - 24, x + paint.measureText(text) + 5, y + 6, paint)
            paint.color = Color.WHITE; canvas.drawText(text, x, y, paint)
        }
        paint.color = Color.rgb(16, 16, 16)
        val row = height * scale + 34f
        for ((index, item) in listOf("MIN" to "minimum_celsius", "C" to "center_celsius", "MAX" to "maximum_celsius").withIndex())
            canvas.drawText("${item.first} ${temperature(metadata.optDouble(item.second, Double.NaN))}", 16f + index * (bitmap.width - 32f) / 3, row, paint)
        val paletteColors = when (metadata.getString("palette")) {
            "white_hot" -> intArrayOf(Color.BLACK, Color.WHITE)
            "rainbow" -> intArrayOf(0xff000064.toInt(), 0xff005aff.toInt(), 0xff00dcb4.toInt(), 0xffbeff00.toInt(), 0xffff8c00.toInt(), 0xffb40000.toInt())
            else -> intArrayOf(Color.BLACK, 0xff2d0050.toInt(), 0xffaa1946.toInt(), 0xfff56e0f.toInt(), 0xffffdc46.toInt(), Color.WHITE)
        }
        paint.shader = LinearGradient(16f, 0f, bitmap.width - 16f, 0f, paletteColors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(16f, row + 14, bitmap.width - 16f, row + 34, paint); paint.shader = null
        canvas.drawText("${if (metadata.getBoolean("automatic_span")) "AUTO" else "LOCKED"}  ${temperature(metadata.optDouble("lower_celsius", Double.NaN))} — ${temperature(metadata.optDouble("upper_celsius", Double.NaN))}", 16f, row + 62, paint)
        canvas.drawText("${if (metadata.optBoolean("correction_applied")) "Corrected ε ${String.format(Locale.US, "%.3f", metadata.getDouble("emissivity"))} R ${temperature(metadata.getDouble("reflected_apparent_celsius"))}" else "Apparent temperature"} · ${metadata.getString("palette")}", 16f, row + 96, paint)
        val demo = metadata.getString("source") == "fixture"
        paint.color = if (demo) Color.rgb(255, 244, 220) else Color.rgb(232, 232, 232)
        canvas.drawRect(0f, row + 112, bitmap.width.toFloat(), row + 144, paint)
        paint.color = Color.rgb(16, 16, 16); paint.textSize = 20f
        val sourceLabel = when (metadata.getString("source")) { "fixture" -> "SYNTHETIC DEMO — NOT A MEASUREMENT"; "camera" -> "SOURCE USB"; "network" -> "SOURCE NETWORK BRIDGE"; else -> "SOURCE UNKNOWN" }
        canvas.drawText(sourceLabel, 16f, row + 135, paint)
        paint.textSize = 16f
        canvas.drawText(if (demo) "Original synthetic scene · temperatures are illustrative" else "Camera baseline and comparison accuracy validation pending", 16f, row + 168, paint)
        if (metadata.optInt("invalid_pixels") > 0) {
            paint.color = Color.rgb(191, 0, 191); canvas.drawRect(16f, row + 180, 32f, row + 196, paint)
            paint.color = Color.BLACK; canvas.drawText("Invalid radiance solution", 40f, row + 194, paint)
        }
        val time = Instant.ofEpochSecond(metadata.getLong("timestamp_unix_ns") / 1_000_000_000).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX"))
        canvas.drawText(time, 16f, row + 221, paint)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
        bitmap.recycle()
        return output.toByteArray()
    }
}
