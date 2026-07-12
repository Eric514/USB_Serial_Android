package com.deepgaze.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.deepgaze.glasses.databinding.FragmentPlotBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PlotFragment : Fragment() {
    private var _binding: FragmentPlotBinding? = null
    private val binding get() = _binding!!

    private lateinit var lineChart: LineChart
    private val handler = Handler(Looper.getMainLooper())
    private var isRealTimeMode = false
    private var dataPoints = mutableListOf<DataManager.CapacitanceDataPoint>()
    private val maxDisplayPoints = 200
    private val TAG = "PlotFragment"
    private var dataManager: DataManager? = null
    private var patientId = ""
    private var patientName = ""
    private var isFragmentActive = true
    private var currentFilePath: String = ""

    // File picker for app's files
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleFilePickerResult(uri)
            }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showAppFilesDialog()
        } else {
            Toast.makeText(
                requireContext(),
                "Storage permission required to load files",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isFragmentActive || !isAdded || isDetached) {
                return
            }

            if (isRealTimeMode) {
                try {
                    dataManager?.let { dm ->
                        val latestData = dm.getPlotDataPoints(maxDisplayPoints)
                        dataPoints = latestData.toMutableList()

                        if (dataPoints.isNotEmpty()) {
                            updateChart()
                            try {
                                val stats = dm.getPlotStatistics()
                                binding.textStats.text = stats.toString()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting statistics", e)
                            }
                        }
                    }

                    if (isFragmentActive) {
                        handler.postDelayed(this, 500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in updateRunnable", e)
                    if (isFragmentActive) {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            dataManager = DataManager(requireContext())

            arguments?.let { args ->
                try {
                    patientId = args.getString("patientId", "") ?: ""
                    patientName = args.getString("patientName", "") ?: ""
                    val mode = args.getString("mode", "") ?: ""
                    val filePath = args.getString("filePath", "") ?: ""

                    Log.d(TAG, "Arguments received - mode: $mode, patientId: $patientId, filePath: $filePath")

                    when {
                        mode == "realtime" -> {
                            dataManager?.let { dm ->
                                dataPoints = dm.getPlotDataPoints(maxDisplayPoints).toMutableList()
                                if (dataPoints.isNotEmpty()) {
                                    Toast.makeText(requireContext(), "Loaded ${dataPoints.size} data points", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        filePath.isNotEmpty() -> {
                            loadFileFromPath(filePath)
                        }
                        else -> {
                            Log.d(TAG, "No mode specified or file path provided")
                            // Try to load the latest file automatically
                            loadLatestFile()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing arguments", e)
                }
            }

            setupChart()
            setupButtons()

            if (dataPoints.isNotEmpty()) {
                updateChart()
            } else {
                showEmptyState()
                binding.textFileInfo.text = "Select a file or click 'Start' for real-time plotting"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
            Toast.makeText(requireContext(), "Error initializing plot: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupChart() {
        try {
            lineChart = binding.lineChart

            lineChart.apply {
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                setBackgroundColor(Color.WHITE)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = Color.BLACK
                    setDrawGridLines(true)
                    gridColor = Color.LTGRAY
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            val index = value.toInt()
                            return if (index >= 0 && index < dataPoints.size) {
                                dataPoints[index].formattedTime
                            } else {
                                ""
                            }
                        }
                    }
                    granularity = 1f
                    labelCount = 5
                }

                axisLeft.apply {
                    textColor = Color.BLACK
                    setDrawGridLines(true)
                    gridColor = Color.LTGRAY
                    axisMinimum = 0f
                    setDrawZeroLine(true)
                    zeroLineColor = Color.GRAY
                    zeroLineWidth = 1f
                }

                axisRight.isEnabled = false

                legend.apply {
                    isEnabled = true
                    textColor = Color.BLACK
                }

                animateX(500)
                animateY(500)
            }

            showEmptyState()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up chart", e)
        }
    }

    private fun setupButtons() {
        try {
            binding.buttonBack.setOnClickListener {
                stopRealTimeMode()
                findNavController().navigateUp()
            }

            binding.buttonRealTime.setOnClickListener {
                try {
                    if (isRealTimeMode) {
                        stopRealTimeMode()
                    } else {
                        startRealTimeMode()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling real-time mode", e)
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // Updated: Load File button shows app's files
            binding.buttonLoadFile.setOnClickListener {
                try {
                    showAppFilesDialog()
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing file dialog", e)
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            binding.buttonClear.setOnClickListener {
                try {
                    clearData()
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing data", e)
                    Toast.makeText(requireContext(), "Error clearing data: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            binding.buttonZoomReset.setOnClickListener {
                try {
                    lineChart.fitScreen()
                } catch (e: Exception) {
                    Log.e(TAG, "Error resetting zoom", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up buttons", e)
        }
    }

    /**
     * Get the SerialData directory
     */
    private fun getSerialDataDirectory(): File {
        val baseDir = requireContext().getExternalFilesDir(null)
        val serialDataDir = File(baseDir, "SerialData")
        if (!serialDataDir.exists()) {
            serialDataDir.mkdirs()
        }
        return serialDataDir
    }

    /**
     * Get all data files from the app's SerialData directory
     */
    private fun getAppDataFiles(): List<File> {
        val serialDataDir = getSerialDataDirectory()
        return serialDataDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".csv") || file.name.endsWith(".txt") || file.name.endsWith(".log"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Load the latest file automatically
     */
    private fun loadLatestFile() {
        try {
            val files = getAppDataFiles()
            if (files.isNotEmpty()) {
                val latestFile = files.first()
                loadFileFromPath(latestFile.absolutePath)
                Log.d(TAG, "Auto-loaded latest file: ${latestFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading latest file", e)
        }
    }

    /**
     * Show dialog with app's stored files
     */
    private fun showAppFilesDialog() {
        try {
            val files = getAppDataFiles()

            if (files.isEmpty()) {
                // No files found - offer to create some or go to real-time
                AlertDialog.Builder(requireContext())
                    .setTitle("No Data Files Found")
                    .setMessage("No data files found in the app's storage.\n\nWould you like to:")
                    .setPositiveButton("Start Real-Time") { _, _ ->
                        startRealTimeMode()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }

            // Create list of file names with details
            val fileItems = files.map { file ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val size = formatFileSize(file.length())
                val date = dateFormat.format(Date(file.lastModified()))
                "${file.name} (${size}, ${date})"
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("Select Data File to Plot")
                .setItems(fileItems) { _, which ->
                    val selectedFile = files[which]
                    loadFileFromPath(selectedFile.absolutePath)
                    Toast.makeText(requireContext(), "Loaded: ${selectedFile.name}", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("Open File Manager") { _, _ ->
                    openFileManager()
                }
                .setNegativeButton("Cancel", null)
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing app files dialog", e)
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open file manager at the app's SerialData directory
     */
    private fun openFileManager() {
        try {
            val serialDataDir = getSerialDataDirectory()

            // Create intent to open the folder
            val intent = Intent(Intent.ACTION_VIEW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    serialDataDir
                )
                intent.setDataAndType(uri, "resource/folder")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                intent.setDataAndType(Uri.fromFile(serialDataDir), "resource/folder")
            }

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Open SerialData Folder"))
            } else {
                Toast.makeText(
                    requireContext(),
                    "Folder location: ${serialDataDir.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error opening file manager", e)
            Toast.makeText(
                requireContext(),
                "SerialData folder: ${getSerialDataDirectory().absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle file picker result (for external files if needed)
     */
    private fun handleFilePickerResult(uri: Uri) {
        try {
            // Try to get the file from the URI
            val documentFile = DocumentFile.fromSingleUri(requireContext(), uri)
            if (documentFile != null && documentFile.exists()) {
                val fileName = documentFile.name ?: "unknown_file"
                val content = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText()

                if (content != null && content.isNotEmpty()) {
                    // If it's not already in our app's directory, copy it
                    val serialDataDir = getSerialDataDirectory()
                    val destFile = File(serialDataDir, fileName)

                    // Check if it's already in our directory
                    if (!destFile.absolutePath.equals(documentFile.uri.path)) {
                        destFile.writeText(content)
                        Toast.makeText(requireContext(), "File copied to app storage: $fileName", Toast.LENGTH_SHORT).show()
                    }

                    loadFileFromPath(destFile.absolutePath)
                    Toast.makeText(requireContext(), "Loaded: $fileName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "File is empty or cannot be read", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Cannot access file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file picker result", e)
            Toast.makeText(requireContext(), "Error loading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Load file from path
     */
    private fun loadFileFromPath(filePath: String) {
        try {
            currentFilePath = filePath
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(requireContext(), "File does not exist: $filePath", Toast.LENGTH_SHORT).show()
                return
            }

            dataManager?.let { dm ->
                dm.loadPlotDataFromFile(file)
                dataPoints = dm.getPlotDataPoints(maxDisplayPoints).toMutableList()

                val displayText = """
                    File: ${file.name}
                    Points: ${dataPoints.size}
                    Size: ${formatFileSize(file.length())}
                """.trimIndent()
                binding.textFileInfo.text = displayText

                if (dataPoints.isNotEmpty()) {
                    updateChart()
                    Toast.makeText(requireContext(), "Loaded ${dataPoints.size} data points", Toast.LENGTH_SHORT).show()

                    // Stop real-time mode if it's running
                    if (isRealTimeMode) {
                        stopRealTimeMode()
                    }
                } else {
                    Toast.makeText(requireContext(), "No valid data points found in file", Toast.LENGTH_SHORT).show()
                    showEmptyState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading file from path", e)
            Toast.makeText(requireContext(), "Error loading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Format file size
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            size >= 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format("%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    private fun startRealTimeMode() {
        try {
            isRealTimeMode = true
            isFragmentActive = true
            binding.buttonRealTime.text = "⏹ Stop"
            binding.buttonRealTime.setBackgroundColor(Color.RED)

            dataManager?.let { dm ->
                dataPoints = dm.getPlotDataPoints(maxDisplayPoints).toMutableList()
                if (dataPoints.isNotEmpty()) {
                    Toast.makeText(requireContext(), "Real-time started with ${dataPoints.size} points", Toast.LENGTH_SHORT).show()
                    updateChart()
                } else {
                    Toast.makeText(requireContext(), "Real-time started - waiting for data", Toast.LENGTH_SHORT).show()
                }
            }

            handler.post(updateRunnable)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting real-time mode", e)
            isRealTimeMode = false
            Toast.makeText(requireContext(), "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRealTimeMode() {
        isRealTimeMode = false
        isFragmentActive = false
        handler.removeCallbacks(updateRunnable)
        binding.buttonRealTime.text = "▶ Start"
        binding.buttonRealTime.setBackgroundColor(Color.GREEN)
        Toast.makeText(requireContext(), "Real-time stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateChart() {
        try {
            if (!isAdded || isDetached) {
                return
            }

            val entries = mutableListOf<Entry>()
            val startIndex = if (dataPoints.size > maxDisplayPoints) {
                dataPoints.size - maxDisplayPoints
            } else {
                0
            }

            for (i in startIndex until dataPoints.size) {
                val point = dataPoints[i]
                entries.add(Entry((i - startIndex).toFloat(), point.capacitance.toFloat()))
            }

            if (entries.isEmpty()) {
                showEmptyState()
                return
            }

            val dataSet = LineDataSet(entries, "Capacitance (pF)").apply {
                color = Color.BLUE
                setCircleColor(Color.BLUE)
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                valueTextColor = Color.BLACK
                valueTextSize = 10f
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = Color.argb(50, 0, 0, 255)
                fillAlpha = 50
            }

            val lineData = LineData(dataSet)
            lineChart.data = lineData

            // Update latest value
            if (dataPoints.isNotEmpty()) {
                val point = dataPoints.last()
                binding.textLatestValue.text = "Latest: ${point.capacitance} pF"

                val patientInfo = if (patientName.isNotEmpty()) {
                    " | Patient: $patientName"
                } else {
                    ""
                }
                val fileInfo = if (currentFilePath.isNotEmpty()) {
                    " | File: ${File(currentFilePath).name}"
                } else {
                    ""
                }
                binding.textFileInfo.text = "Points: ${dataPoints.size}$patientInfo$fileInfo"
            }

            // Update statistics
            dataManager?.let { dm ->
                try {
                    val stats = dm.getPlotStatistics()
                    binding.textStats.text = stats.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting statistics", e)
                }
            }

            lineChart.invalidate()

        } catch (e: Exception) {
            Log.e(TAG, "Error updating chart", e)
        }
    }

    private fun showEmptyState() {
        try {
            val emptyData = LineData()
            lineChart.data = emptyData
            lineChart.invalidate()
            binding.textFileInfo.text = "No data loaded. Click 'Load File' to select a file or 'Start' for real-time."
            binding.textLatestValue.text = "Latest: --"
            binding.textStats.text = "Min: -- | Max: -- | Avg: -- | Median: -- | Count: 0"
        } catch (e: Exception) {
            Log.e(TAG, "Error showing empty state", e)
        }
    }

    private fun clearData() {
        try {
            dataPoints.clear()
            currentFilePath = ""
            dataManager?.clearPlotData()
            showEmptyState()
            Toast.makeText(requireContext(), "Data cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing data", e)
            Toast.makeText(requireContext(), "Error clearing data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        stopRealTimeMode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentActive = false
        stopRealTimeMode()
        _binding = null
    }

    companion object {
        fun newInstance(filePath: String = "", mode: String = ""): PlotFragment {
            val fragment = PlotFragment()
            val args = Bundle()
            args.putString("filePath", filePath)
            args.putString("mode", mode)
            fragment.arguments = args
            return fragment
        }
    }
}