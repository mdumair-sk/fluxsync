package com.fluxsync.android.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile

private const val PREFS_NAME = "fluxsync_android_file_access"
private const val KEY_DROP_ZONE_URI = "drop_zone_uri"
private const val TEMP_SUFFIX = ".fluxdownload"
private const val FLUXPART_SUFFIX = ".fluxpart"
private const val NO_MEDIA_FILE_NAME = ".nomedia"
private const val TAG = "AndroidFileAccess"

class AndroidFileAccess(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveDropZoneUri(uri: Uri) {
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, readWriteFlags)
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Failed to persist drop zone URI permission for $uri", securityException)
            throw securityException
        }

        prefs.edit().putString(KEY_DROP_ZONE_URI, uri.toString()).apply()
        Log.i(TAG, "Saved drop zone URI: $uri")
    }

    fun getDropZoneUri(): Uri? {
        val stored = prefs.getString(KEY_DROP_ZONE_URI, null) ?: return null
        return Uri.parse(stored)
    }

    fun createTempFile(fileName: String, sizeBytes: Long): AndroidTempFile? {
        require(sizeBytes >= 0L) { "sizeBytes must be >= 0" }

        val dropZoneUri = getDropZoneUri() ?: run {
            Log.e(TAG, "createTempFile failed: no drop zone URI is configured")
            return null
        }

        val dropZone = DocumentFile.fromTreeUri(context, dropZoneUri)
        if (dropZone == null || !dropZone.isDirectory) {
            Log.e(TAG, "createTempFile failed: drop zone URI is not a readable directory: $dropZoneUri")
            return null
        }

        ensureNoMediaFile(dropZone)

        val tempName = "$fileName$TEMP_SUFFIX"
        val fluxDownloadUri = if (dropZone.findFile(tempName) == null) {
            dropZone.createFile("application/octet-stream", tempName)?.uri
        } else {
            dropZone.findFile(tempName)?.uri
        } ?: run {
            Log.e(TAG, "createTempFile failed: unable to create $tempName in drop zone")
            return null
        }

        val randomAccessFile = openSafRandomAccessFile(fluxDownloadUri) ?: run {
            Log.e(TAG, "createTempFile failed: unable to open RandomAccessFile for $fluxDownloadUri")
            return null
        }

        try {
            randomAccessFile.setLength(sizeBytes)
        } catch (t: Throwable) {
            Log.e(TAG, "createTempFile failed: unable to pre-allocate $sizeBytes bytes for $fluxDownloadUri", t)
            randomAccessFile.close()
            return null
        }

        val fluxPartFile = File(context.cacheDir, "$fileName$FLUXPART_SUFFIX")
        if (!fluxPartFile.exists()) {
            fluxPartFile.createNewFile()
        }

        return AndroidTempFile(
            fluxDownloadUri = fluxDownloadUri,
            fluxPartFile = fluxPartFile,
            randomAccessFile = randomAccessFile,
        )
    }

    fun promoteToFinal(tempFile: AndroidTempFile): Boolean {
        return try {
            val renamed = DocumentsContract.renameDocument(
                context.contentResolver,
                tempFile.fluxDownloadUri,
                tempFile.fluxPartFile.name.removeSuffix(FLUXPART_SUFFIX),
            )
            if (renamed == null) {
                Log.e(TAG, "promoteToFinal failed: rename returned null for ${tempFile.fluxDownloadUri}")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "promoteToFinal failed for ${tempFile.fluxDownloadUri}", t)
            false
        }
    }

    fun findOrphanedFluxParts(maxAgeMs: Long): List<OrphanedTransfer> {
        require(maxAgeMs >= 0L) { "maxAgeMs must be >= 0" }

        val now = System.currentTimeMillis()
        val candidates = context.cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(FLUXPART_SUFFIX)
        }.orEmpty()

        return candidates.mapNotNull { file ->
            val ageMs = now - file.lastModified()
            if (ageMs > maxAgeMs) {
                OrphanedTransfer(
                    fluxPartFile = file,
                    ageMs = ageMs,
                    sizeBytes = file.length(),
                )
            } else {
                null
            }
        }
    }

    private fun ensureNoMediaFile(dropZone: DocumentFile) {
        val noMediaExists = dropZone.findFile(NO_MEDIA_FILE_NAME) != null
        if (noMediaExists) {
            return
        }

        try {
            val noMediaUri = dropZone.createFile("application/octet-stream", NO_MEDIA_FILE_NAME)?.uri ?: run {
                Log.w(TAG, "Unable to create .nomedia file in drop zone ${dropZone.uri}")
                return
            }
            context.contentResolver.openOutputStream(noMediaUri, "wt")?.use {
                // Keep file empty.
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write .nomedia file in drop zone ${dropZone.uri}", t)
        }
    }

    private fun openSafRandomAccessFile(uri: Uri): RandomAccessFile? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
        return try {
            RandomAccessFile("/proc/self/fd/${descriptor.fd}", "rw")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create RandomAccessFile for uri $uri", t)
            null
        } finally {
            descriptor.close()
        }
    }
}

data class AndroidTempFile(
    val fluxDownloadUri: Uri,
    val fluxPartFile: File,
    val randomAccessFile: RandomAccessFile,
)

data class OrphanedTransfer(
    val fluxPartFile: File,
    val ageMs: Long,
    val sizeBytes: Long,
)
