package com.deepgaze.glasses

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class DataManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("serial_data_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val TAG = "DataManager"

    // In-memory cache for real-time plotting
    private val plotDataPoints = mutableListOf<CapacitanceDataPoint>()
    private val maxPlotPoints = 1000
    private var currentFilePath: String? = null

    companion object {
        private const val MAX_SAVED_RECORDS = 1000
        private const val KEY_SAVED_RECORDS = "saved_records"
        private const val KEY_RECORD_COUNT = "record_count"
    }

    // Data class for capacitance readings (for plotting)
    data class CapacitanceDataPoint(
        val timestamp: Long,
        val capacitance: Double,
        val formattedTime: String = ""
    )

    // ========== EXISTING METHODS ==========

    fun saveRecord(record: SerialDataRecord): Boolean {
        try {
            val records = getRecords().toMutableList()
            records.add(0, record)

            if (records.size > MAX_SAVED_RECORDS) {
                records.removeAt(records.size - 1)
            }

            val json = gson.toJson(records)
            prefs.edit().putString(KEY_SAVED_RECORDS, json).apply()
            prefs.edit().putInt(KEY_RECORD_COUNT, records.size).apply()

            // Also add to plot data - FIX: Convert Capacitance to Double properly
            try {
                val capacitance = record.Capacitance.toDouble() // Use toDouble() instead of toDoubleOrNull()
                addPlotDataPoint(capacitance)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to plot data", e)
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun getRecords(): List<SerialDataRecord> {
        try {
            val json = prefs.getString(KEY_SAVED_RECORDS, null)
            if (json == null) return emptyList()

            val type = object : TypeToken<List<SerialDataRecord>>() {}.type
            return gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    fun getRecordCount(): Int {
        return prefs.getInt(KEY_RECORD_COUNT, 0)
    }

    fun clearRecords(): Boolean {
        return try {
            prefs.edit().remove(KEY_SAVED_RECORDS).remove(KEY_RECORD_COUNT).apply()
            plotDataPoints.clear()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun saveToCSV(records: List<SerialDataRecord>): File? {
        try {
            val fileName = "serial_data_${dateFormat.format(Date())}.csv"
            val file = File(context.getExternalFilesDir(null), fileName)

            FileWriter(file).use { writer ->
                writer.write("Timestamp,Capacitance\n")
                records.forEach { record ->
                    writer.write("${record.Time},${record.Capacitance}\n")
                }
            }

            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ========== NEW PLOTTING METHODS ==========

    fun addPlotDataPoint(capacitance: Double): Boolean {
        try {
            val timestamp = System.currentTimeMillis()
            val formattedTime = logDateFormat.format(Date(timestamp))

            val point = CapacitanceDataPoint(timestamp, capacitance, formattedTime)
            plotDataPoints.add(point)

            if (plotDataPoints.size > maxPlotPoints) {
                plotDataPoints.removeAt(0)
            }

            currentFilePath?.let { savePlotDataToFile(it) }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding plot data point", e)
            return false
        }
    }

    fun addPlotDataPoints(points: List<CapacitanceDataPoint>) {
        try {
            plotDataPoints.addAll(points)
            if (plotDataPoints.size > maxPlotPoints) {
                val excess = plotDataPoints.size - maxPlotPoints
                repeat(excess) {
                    plotDataPoints.removeAt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding plot data points", e)
        }
    }

    fun getPlotDataPoints(): List<CapacitanceDataPoint> {
        return plotDataPoints.toList()
    }

    fun getPlotDataPoints(count: Int): List<CapacitanceDataPoint> {
        return plotDataPoints.takeLast(count)
    }

    fun getLatestPlotPoint(): CapacitanceDataPoint? {
        return plotDataPoints.lastOrNull()
    }

    fun getPlotPointCount(): Int {
        return plotDataPoints.size
    }

    fun clearPlotData() {
        plotDataPoints.clear()
        Log.d(TAG, "Plot data cleared")
    }

    fun setPlotDataFilePath(path: String) {
        currentFilePath = path
        Log.d(TAG, "Plot data file path set to: $path")
    }

    fun savePlotDataToFile(filePath: String): Boolean {
        try {
            val file = File(filePath)
            FileWriter(file, true).use { writer ->
                val latestPoint = plotDataPoints.lastOrNull()
                latestPoint?.let {
                    writer.write("${it.timestamp} ${it.capacitance}\n")
                }
                writer.flush()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving plot data to file", e)
            return false
        }
    }

    fun loadPlotDataFromFile(file: File): Boolean {
        try {
            val points = mutableListOf<CapacitanceDataPoint>()

            file.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    try {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val capacitance = parts.last().toDouble()
                            val timestamp = if (parts.first().toLongOrNull() != null) {
                                parts.first().toLong()
                            } else {
                                try {
                                    logDateFormat.parse("${parts.first()}.000")?.time ?: System.currentTimeMillis()
                                } catch (e: Exception) {
                                    System.currentTimeMillis()
                                }
                            }
                            val formattedTime = logDateFormat.format(Date(timestamp))
                            points.add(CapacitanceDataPoint(timestamp, capacitance, formattedTime))
                        }
                    } catch (e: Exception) {
                        // Skip invalid lines
                        Log.d(TAG, "Skipping line: $line")
                    }
                }
            }

            if (points.isNotEmpty()) {
                plotDataPoints.clear()
                plotDataPoints.addAll(points.takeLast(maxPlotPoints))
                Log.d(TAG, "Loaded ${plotDataPoints.size} plot data points from file")
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading plot data from file", e)
            return false
        }
    }

    fun getPlotStatistics(): PlotStatistics {
        return if (plotDataPoints.isEmpty()) {
            PlotStatistics(0.0, 0.0, 0.0, 0.0, 0)
        } else {
            val capacities = plotDataPoints.map { it.capacitance }.sorted()
            val min = capacities.first()
            val max = capacities.last()
            val avg = capacities.average()
            val median = capacities[capacities.size / 2]
            PlotStatistics(min, max, avg, median, capacities.size)
        }
    }

    fun exportPlotDataToCSV(): File? {
        try {
            val fileName = "plot_data_${dateFormat.format(Date())}.csv"
            val file = File(context.getExternalFilesDir(null), fileName)

            FileWriter(file).use { writer ->
                writer.write("Timestamp,Capacitance\n")
                plotDataPoints.forEach { point ->
                    writer.write("${point.timestamp},${point.capacitance}\n")
                }
            }

            Log.d(TAG, "Plot data exported to: ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting plot data", e)
            return null
        }
    }

    // ========== HELPER CLASSES ==========

    data class PlotStatistics(
        val min: Double,
        val max: Double,
        val avg: Double,
        val median: Double,
        val count: Int
    ) {
        override fun toString(): String {
            return "Min: %.1f | Max: %.1f | Avg: %.1f | Median: %.1f | Count: %d".format(min, max, avg, median, count)
        }
    }
}
