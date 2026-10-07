package com.thereprocase.thermalfield

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

data class CaptureRecord(val capture: SavedCapture, val timestamp: Long, val exportedAt: Long, val source: String, val summary: String) {
    val displayTime: String get() = Instant.ofEpochSecond(timestamp / 1_000_000_000).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
}
internal data class LoadedCapture(val composite: ByteArray, val metadata: JSONObject)

internal object CaptureCatalog {
    fun list(context: Context): List<CaptureRecord> {
        val files = mutableMapOf<String, Uri>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf("Download/ThermalField/"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith("ThermalField_")) files[name] = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return files.keys.filter { it.endsWith(".json") }.mapNotNull { name ->
            val stem = name.removeSuffix(".json")
            val image = files["$stem.png"] ?: return@mapNotNull null
            val raw = files["${stem}_raw.png"] ?: return@mapNotNull null
            val sidecar = files[name]!!
            runCatching {
                val metadata = readMetadata(context, sidecar)
                val capture = SavedCapture(stem, image, raw, sidecar, metadata.optString("preferred_share") == "radiometric_plane")
                val kind = metadata.optString("source", "unknown")
                val source = if (kind == "archive" && metadata.optJSONObject("identity")?.optString("original_source_kind") == "fixture") "saved synthetic" else kind
                val summary = "${if (metadata.optBoolean("correction_applied")) "Corrected" else "Apparent"} · ${metadata.optString("palette")} · ${metadata.optInt("rotation_degrees")}°"
                val timestamp = metadata.getLong("timestamp_unix_ns")
                require(timestamp > 0) { "Invalid capture timestamp" }
                CaptureRecord(capture, timestamp, metadata.optLong("exported_at_unix_ns", timestamp), source, summary)
            }.getOrNull()
        }.sortedByDescending { it.exportedAt }
    }

    private fun readMetadata(context: Context, uri: Uri): JSONObject {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(1_000_001) } ?: error("Capture metadata unavailable")
        require(bytes.size <= 1_000_000) { "Capture metadata is too large" }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }
    fun load(context: Context, capture: SavedCapture): LoadedCapture {
        val metadata = readMetadata(context, capture.metadata)
        require(metadata.optInt("raw_width") == 256 && metadata.optInt("raw_height") == 192 && metadata.optBoolean("raw_sensor_orientation")) { "Unsupported saved sensor geometry" }
        require(metadata.optString("raw_scaling") == "Kelvin × 64; Celsius = word / 64 - 273.15") { "Unsupported saved temperature scaling" }
        val png = context.contentResolver.openInputStream(capture.raw)?.use { it.readNBytes(2_000_001) } ?: error("Radiometric plane unavailable")
        return LoadedCapture(RadiometricPng.decode(png), metadata)
    }
}
