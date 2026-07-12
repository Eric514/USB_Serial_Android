// Create a new file: DataManager.kt
package com.deepgaze.glasses

import android.content.Context
import android.content.SharedPreferences
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

    companion object {
        private const val MAX_SAVED_RECORDS = 1000
        private const val KEY_SAVED_RECORDS = "saved_records"
        private const val KEY_RECORD_COUNT = "record_count"
    }

    // Save individual record to SharedPreferences
    fun saveRecord(record: SerialDataRecord): Boolean {
        try {
            // Get existing records
            val records = getRecords().toMutableList()

            // Add new record
            records.add(0, record) // Add to beginning for most recent first

            // Limit the number of records
            if (records.size > MAX_SAVED_RECORDS) {
                records.removeAt(records.size - 1)
            }

            // Save back
            val json = gson.toJson(records)
            prefs.edit().putString(KEY_SAVED_RECORDS, json).apply()

            // Update count
            prefs.edit().putInt(KEY_RECORD_COUNT, records.size).apply()

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // Get all records
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

    // Get record count
    fun getRecordCount(): Int {
        return prefs.getInt(KEY_RECORD_COUNT, 0)
    }

    // Clear all records
    fun clearRecords(): Boolean {
        return try {
            prefs.edit().remove(KEY_SAVED_RECORDS).remove(KEY_RECORD_COUNT).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    // Save record to CSV file
    fun saveToCSV(records: List<SerialDataRecord>): File? {
        try {
            val fileName = "serial_data_${dateFormat.format(Date())}.csv"
            val file = File(context.getExternalFilesDir(null), fileName)

            FileWriter(file).use { writer ->
                // Write header
                writer.write("Timestamp,Patient ID,Patient Name,Data,Baud Rate,Data Bits,Stop Bits,Parity\n")

                // Write data
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
}