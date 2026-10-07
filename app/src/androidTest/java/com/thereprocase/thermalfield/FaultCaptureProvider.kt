package com.thereprocase.thermalfield

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

// An in-process provider forces failures without changing the real MediaStore.
// Temporary byte streams use private test files and are removed after the run.
internal class FaultCaptureProvider(private val failWrite: Int = 0, private val refusePublish: Int = 0, private val refuseDelete: Long = -1) : ContentProvider(), AutoCloseable {
    val entries = mutableMapOf(0L to ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "existing.png") })
    private val files = mutableMapOf<Long, File>()
    val deleted = mutableListOf<Long>()
    var inserted = 0
    private var publications = 0

    fun contextFor(base: Context): Context {
        attachInfo(base, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        val wrapped = ContentResolver.wrap(this)
        return object : ContextWrapper(base) { override fun getContentResolver(): ContentResolver = wrapped }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = (++inserted).toLong()
        entries[id] = ContentValues(values ?: error("Missing capture values"))
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = ContentUris.parseId(uri)
        if (id == failWrite.toLong()) throw FileNotFoundException("Injected capture write failure")
        val file = files.getOrPut(id) { File.createTempFile("thermal-validation-", ".bin", context!!.cacheDir) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        publications++
        if (publications == refusePublish) return 0
        val entry = entries[ContentUris.parseId(uri)] ?: return 0
        entry.putAll(values ?: return 0)
        return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        val id = ContentUris.parseId(uri)
        deleted += id
        if (id == refuseDelete) return 0
        files.remove(id)?.delete()
        return if (entries.remove(id) != null) 1 else 0
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
        return MatrixCursor(columns).apply {
            for ((id, values) in entries) addRow(columns.map { if (it == MediaStore.MediaColumns._ID) id else values.get(it) }.toTypedArray())
        }
    }
    override fun close() { files.values.forEach { it.delete() }; files.clear() }
}
