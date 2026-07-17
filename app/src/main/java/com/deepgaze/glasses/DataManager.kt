package com.deepgaze.glasses

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileWriter
import java.io.IOException
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

            // Also add to plot data
            try {
                val capacitance = record.Capacitance.toDouble()
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

            var writer: FileWriter? = null
            try {
                writer = FileWriter(file)
                writer.write("Timestamp,Capacitance\n")
                for (record in records) {
                    writer.write("${record.Time},${record.Capacitance}\n")
                }
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing CSV", e)
                return null
            } finally {
                try {
                    writer?.close()
                } catch (e: IOException) {
                    // Ignore close error
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

            // Safe null handling
            currentFilePath?.let { filePath ->
                savePlotDataToFile(filePath)
            }

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
                var removed = 0
                while (removed < excess) {
                    plotDataPoints.removeAt(0)
                    removed++
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
        return if (plotDataPoints.size <= count) {
            plotDataPoints.toList()
        } else {
            plotDataPoints.subList(plotDataPoints.size - count, plotDataPoints.size)
        }
    }

    fun getLatestPlotPoint(): CapacitanceDataPoint? {
        return if (plotDataPoints.isNotEmpty()) plotDataPoints.last() else null
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
            var writer: FileWriter? = null
            try {
                writer = FileWriter(file, true)
                if (plotDataPoints.isNotEmpty()) {
                    val latestPoint = plotDataPoints.last()
                    writer.write("${latestPoint.timestamp} ${latestPoint.capacitance}\n")
                }
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error saving plot data to file", e)
                return false
            } finally {
                try {
                    writer?.close()
                } catch (e: IOException) {
                    // Ignore
                }
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
            var reader: java.io.BufferedReader? = null

            try {
                reader = file.bufferedReader()
                var line: String? = reader.readLine()
                while (line != null) {
                    try {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty()) {
                            val parts = trimmedLine.split(Regex("\\s+"))
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
                        }
                    } catch (e: Exception) {
                        // Skip invalid lines
                        Log.d(TAG, "Skipping line: $line")
                    }
                    line = reader.readLine()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error reading file", e)
                return false
            } finally {
                try {
                    reader?.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }

            if (points.isNotEmpty()) {
                plotDataPoints.clear()
                val pointsToAdd = if (points.size > maxPlotPoints) {
                    points.subList(points.size - maxPlotPoints, points.size)
                } else {
                    points
                }
                plotDataPoints.addAll(pointsToAdd)
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

            var writer: FileWriter? = null
            try {
                writer = FileWriter(file)
                writer.write("Timestamp,Capacitance\n")
                for (point in plotDataPoints) {
                    writer.write("${point.timestamp},${point.capacitance}\n")
                }
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing CSV", e)
                return null
            } finally {
                try {
                    writer?.close()
                } catch (e: IOException) {
                    // Ignore
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