package com.deepgaze.glasses

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class StorageManager(private val context: Context) {
    private val TAG = "StorageManager"
    private val prefs: SharedPreferences = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_STORAGE_PATH = "storage_path"
        private const val DEFAULT_STORAGE_NAME = "SerialData"
    }

    /**
     * Get the main storage directory
     */
    fun getStorageDirectory(): File {
        val customPath = prefs.getString(KEY_STORAGE_PATH, null)

        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
        }

        // Default: Use app's external files directory
        val defaultDir = File(context.getExternalFilesDir(null), DEFAULT_STORAGE_NAME)
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        return defaultDir
    }

    /**
     * Set custom storage path
     */
    fun setStoragePath(path: String): Boolean {
        try {
            val dir = File(path)
            if (dir.exists() || dir.mkdirs()) {
                prefs.edit().putString(KEY_STORAGE_PATH, path).apply()
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting storage path", e)
            return false
        }
    }

    /**
     * Get storage statistics
     */
    fun getStorageStats(): StorageStats {
        val dir = getStorageDirectory()
        val files = dir.listFiles() ?: emptyArray()

        var totalSize = 0L
        var fileCount = 0
        var totalRecords = 0

        files.forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
                fileCount++
                // Try to parse record count from filename or content
                if (file.name.endsWith(".csv") || file.name.endsWith(".txt") || file.name.endsWith(".log")) {
                    // Estimate records based on file size (rough estimate)
                    val lines = file.readLines().size
                    if (file.name.endsWith(".csv")) {
                        totalRecords += (lines - 1).coerceAtLeast(0) // Subtract header
                    } else {
                        totalRecords += lines
                    }
                }
            }
        }

        return StorageStats(
            directory = dir.absolutePath,
            fileCount = fileCount,
            totalSize = totalSize,
            totalRecords = totalRecords,
            freeSpace = dir.freeSpace,
            totalSpace = dir.totalSpace,
            usableSpace = dir.usableSpace
        )
    }

    /**
     * Get list of all data files
     */
    fun getDataFiles(): List<DataFileInfo> {
        val dir = getStorageDirectory()
        val files = dir.listFiles() ?: return emptyList()

        return files
            .filter { it.isFile }
            .map { file ->
                DataFileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    modifiedDate = Date(file.lastModified()),
                    fileType = getFileType(file.name),
                    canRead = file.canRead(),
                    canWrite = file.canWrite()
                )
            }
            .sortedByDescending { it.modifiedDate }
    }

    /**
     * Get file type from extension
     */
    private fun getFileType(filename: String): String {
        return when {
            filename.endsWith(".csv") -> "CSV"
            filename.endsWith(".txt") -> "Text"
            filename.endsWith(".log") -> "Log"
            filename.endsWith(".json") -> "JSON"
            filename.endsWith(".xml") -> "XML"
            else -> "Unknown"
        }
    }

    /**
     * Delete a file
     */
    fun deleteFile(filename: String): Boolean {
        try {
            val dir = getStorageDirectory()
            val file = File(dir, filename)
            return if (file.exists()) {
                file.delete()
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file", e)
            return false
        }
    }

    /**
     * Delete all data files
     */
    fun deleteAllFiles(): Int {
        try {
            val dir = getStorageDirectory()
            val files = dir.listFiles() ?: return 0
            var deleted = 0
            files.forEach { file ->
                if (file.isFile && file.delete()) {
                    deleted++
                }
            }
            return deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting files", e)
            return 0
        }
    }

    /**
     * Check if directory is writable
     */
    fun isDirectoryWritable(): Boolean {
        return try {
            val dir = getStorageDirectory()
            dir.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get formatted size string
     */
    fun formatSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            size >= 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format("%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }
}

/**
 * Storage statistics data class
 */
data class StorageStats(
    val directory: String,
    val fileCount: Int,
    val totalSize: Long,
    val totalRecords: Int,
    val freeSpace: Long,
    val totalSpace: Long,
    val usableSpace: Long
)

/**
 * Data file information data class
 */
data class DataFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedDate: Date,
    val fileType: String,
    val canRead: Boolean,
    val canWrite: Boolean
)