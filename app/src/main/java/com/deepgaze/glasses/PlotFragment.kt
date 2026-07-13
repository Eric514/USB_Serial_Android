package com.deepgaze.glasses

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private var allDataPoints = mutableListOf<DataManager.CapacitanceDataPoint>()
    private val DISPLAY_POINTS = 200 // Number of points to display at once
    private val TAG = "PlotFragment"
    private var dataManager: DataManager? = null
    private var patientId = ""
    private var patientName = ""
    private var currentFilePath: String = ""

    // Viewport tracking for scrolling
    private var currentStartIndex = 0
    private var currentEndIndex = 0
    private var totalDataPoints = 0

    // Custom value formatter for x-axis
    private val xAxisFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val index = value.toInt()
            return if (index >= 0 && index < allDataPoints.size) {
                allDataPoints[index].formattedTime
            } else {
                ""
            }
        }
    }

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
                    val filePath = args.getString("filePath", "") ?: ""

                    Log.d(TAG, "Arguments received - patientId: $patientId, filePath: $filePath")

                    if (filePath.isNotEmpty()) {
                        loadFileFromPath(filePath)
                    } else {
                        Log.d(TAG, "No file path provided, loading latest file")
                        loadLatestFile()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing arguments", e)
                }
            }

            setupChart()
            setupButtons()

            if (allDataPoints.isNotEmpty()) {
                updateChart()
                updateScrollButtons()
            } else {
                showEmptyState()
                binding.textFileInfo.text = "Select a file to plot"
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
                setDrawGridBackground(false)
                setDrawBorders(false)

                // Enable scrolling
                setVisibleXRangeMaximum(DISPLAY_POINTS.toFloat())
                setVisibleXRangeMinimum(10f)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = Color.BLACK
                    setDrawGridLines(true)
                    gridColor = Color.LTGRAY
                    valueFormatter = xAxisFormatter
                    granularity = 1f
                    labelCount = 10
                    setAvoidFirstLastClipping(true)
                    setDrawAxisLine(true)
                }

                axisLeft.apply {
                    textColor = Color.BLACK
                    setDrawGridLines(true)
                    gridColor = Color.LTGRAY
                    axisMinimum = 0f
                    setDrawZeroLine(true)
                    zeroLineColor = Color.GRAY
                    zeroLineWidth = 1f
                    setDrawAxisLine(true)
                }

                axisRight.isEnabled = false

                legend.apply {
                    isEnabled = true
                    textColor = Color.BLACK
                    textSize = 10f
                }

                // Disable highlight for better performance
                setMaxHighlightDistance(0f)

                // Set viewport offsets
                setViewPortOffsets(10f, 10f, 10f, 10f)
                setExtraOffsets(5f, 5f, 5f, 5f)
            }

            showEmptyState()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up chart", e)
        }
    }

    private fun setupButtons() {
        try {
            binding.buttonBack.setOnClickListener {
                findNavController().navigateUp()
            }

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
                    // Reset to show the first 200 points or all if less
                    if (allDataPoints.size > DISPLAY_POINTS) {
                        currentStartIndex = 0
                        currentEndIndex = DISPLAY_POINTS
                    } else {
                        currentStartIndex = 0
                        currentEndIndex = allDataPoints.size
                    }
                    updateChartWithRange(currentStartIndex, currentEndIndex)
                    updateScrollButtons()
                    lineChart.fitScreen()
                } catch (e: Exception) {
                    Log.e(TAG, "Error resetting zoom", e)
                }
            }

            // Navigation buttons for scrolling through data
            binding.buttonScrollLeft.setOnClickListener {
                scrollData(-DISPLAY_POINTS)
            }

            binding.buttonScrollRight.setOnClickListener {
                scrollData(DISPLAY_POINTS)
            }

            // Initially hide scroll buttons if not needed
            binding.buttonScrollLeft.visibility = View.GONE
            binding.buttonScrollRight.visibility = View.GONE

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up buttons", e)
        }
    }

    /**
     * Scroll the data view by the specified amount
     */
    private fun scrollData(step: Int) {
        if (allDataPoints.isEmpty() || allDataPoints.size <= DISPLAY_POINTS) return

        var newStart = currentStartIndex + step
        var newEnd = currentEndIndex + step

        // Ensure we don't go out of bounds
        if (newStart < 0) {
            newStart = 0
            newEnd = Math.min(DISPLAY_POINTS, allDataPoints.size)
        }

        if (newEnd > allDataPoints.size) {
            newEnd = allDataPoints.size
            newStart = Math.max(0, allDataPoints.size - DISPLAY_POINTS)
        }

        // Ensure we have at least some data to show
        if (newEnd - newStart < 10) {
            if (newStart > 0) {
                newStart = Math.max(0, newEnd - 10)
            } else {
                newEnd = Math.min(allDataPoints.size, newStart + 10)
            }
        }

        currentStartIndex = newStart
        currentEndIndex = newEnd

        updateChartWithRange(currentStartIndex, currentEndIndex)
        updateScrollButtons()
    }

    /**
     * Update chart with a specific range of data
     */
    private fun updateChartWithRange(startIndex: Int, endIndex: Int) {
        try {
            if (!isAdded || isDetached || allDataPoints.isEmpty()) {
                return
            }

            // Ensure valid range
            val safeStart = startIndex.coerceIn(0, allDataPoints.size - 1)
            val safeEnd = endIndex.coerceIn(1, allDataPoints.size)

            if (safeStart >= safeEnd) {
                return
            }

            Log.d(TAG, "Updating chart: $safeStart to $safeEnd of ${allDataPoints.size}")

            val entries = mutableListOf<Entry>()
            for (i in safeStart until safeEnd) {
                val point = allDataPoints[i]
                // Adjust x-value to start from 0 for this viewport
                entries.add(Entry((i - safeStart).toFloat(), point.capacitance.toFloat()))
            }

            if (entries.isEmpty()) {
                showEmptyState()
                return
            }

            // Create dataset with optimized settings
            val dataSet = LineDataSet(entries, "Capacitance (pF)").apply {
                color = Color.BLUE
                setCircleColor(Color.BLUE)
                lineWidth = 2f
                circleRadius = 2f
                setDrawCircleHole(false)
                valueTextColor = Color.BLACK
                valueTextSize = 8f
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = Color.argb(50, 0, 0, 255)
                fillAlpha = 50
                mode = LineDataSet.Mode.LINEAR
                setDrawHorizontalHighlightIndicator(false)
                setDrawVerticalHighlightIndicator(false)
            }

            val lineData = LineData(dataSet)
            lineChart.data = lineData

            // Set the visible range
            lineChart.setVisibleXRangeMaximum(DISPLAY_POINTS.toFloat())

            // Fit to screen to show all data in range
            lineChart.fitScreen()

            // Update info text
            val infoText = """
                Showing: ${safeStart + 1} - ${safeEnd} of ${allDataPoints.size} points
                File: ${File(currentFilePath).name}
            """.trimIndent()
            binding.textFileInfo.text = infoText

            // Update latest value if we're at the end
            if (safeEnd == allDataPoints.size && allDataPoints.isNotEmpty()) {
                val lastPoint = allDataPoints.last()
                binding.textLatestValue.text = "Latest: ${lastPoint.capacitance} pF"
            } else {
                // Show the last value in the current view
                val lastPoint = allDataPoints[safeEnd - 1]
                binding.textLatestValue.text = "Last in view: ${lastPoint.capacitance} pF"
            }


            lineChart.invalidate()
            currentStartIndex = safeStart
            currentEndIndex = safeEnd

        } catch (e: Exception) {
            Log.e(TAG, "Error updating chart with range", e)
        }
    }

    /**
     * Update the scroll button visibility and states
     */
    private fun updateScrollButtons() {
        if (allDataPoints.isEmpty() || allDataPoints.size <= DISPLAY_POINTS) {
            binding.buttonScrollLeft.visibility = View.GONE
            binding.buttonScrollRight.visibility = View.GONE
            binding.textRangeInfo.visibility = View.GONE
            return
        }

        binding.buttonScrollLeft.visibility = View.VISIBLE
        binding.buttonScrollRight.visibility = View.VISIBLE
        binding.textRangeInfo.visibility = View.VISIBLE

        // Enable/disable buttons based on position
        binding.buttonScrollLeft.isEnabled = currentStartIndex > 0
        binding.buttonScrollRight.isEnabled = currentEndIndex < allDataPoints.size

        // Update button text with positions
        binding.buttonScrollLeft.text = "◀ ${currentStartIndex + 1}"
        binding.buttonScrollRight.text = "${currentEndIndex} ▶"

        // Update info text with range
        val rangeText = "Showing: ${currentStartIndex + 1} - ${currentEndIndex} of ${allDataPoints.size}"
        binding.textRangeInfo.text = rangeText
    }

    // ==================== FILE LOADING FUNCTIONS ====================

    /**
     * Load all data from a CSV file
     */
    private fun loadAllDataFromFile(file: File): List<DataManager.CapacitanceDataPoint> {
        val points = mutableListOf<DataManager.CapacitanceDataPoint>()
        try {
            file.bufferedReader().use { reader ->
                // Skip header if exists
                var firstLine = true
                reader.forEachLine { line ->
                    if (firstLine) {
                        // Check if it's a header
                        if (line.contains("timestamp") || line.contains("time") || line.contains("capacitance")) {
                            firstLine = false
                            return@forEachLine
                        }
                        firstLine = false
                    }

                    try {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            val timestamp = parts[0].toLongOrNull()
                            val capacitance = parts[1].toDoubleOrNull()
                            if (timestamp != null && capacitance != null) {
                                val date = Date(timestamp)
                                val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
                                points.add(DataManager.CapacitanceDataPoint(timestamp, capacitance, formattedTime))
                            }
                        }
                    } catch (e: Exception) {
                        // Skip invalid lines
                    }
                }
            }
            Log.d(TAG, "Loaded ${points.size} data points from file")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading data from file", e)
        }
        return points
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
            } else {
                Log.d(TAG, "No files found to load")
                showEmptyState()
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
                AlertDialog.Builder(requireContext())
                    .setTitle("No Data Files Found")
                    .setMessage("No data files found in the app's storage.\n\nPlease save some data first using the Serial tab.")
                    .setPositiveButton("OK", null)
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
            val documentFile = DocumentFile.fromSingleUri(requireContext(), uri)
            if (documentFile != null && documentFile.exists()) {
                val fileName = documentFile.name ?: "unknown_file"
                val content = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText()

                if (content != null && content.isNotEmpty()) {
                    val serialDataDir = getSerialDataDirectory()
                    val destFile = File(serialDataDir, fileName)

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
     * Load file from path - NOW LOADS ALL DATA
     */
    private fun loadFileFromPath(filePath: String) {
        try {
            currentFilePath = filePath
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(requireContext(), "File does not exist: $filePath", Toast.LENGTH_SHORT).show()
                return
            }

            // Load ALL data directly from file
            val allPoints = loadAllDataFromFile(file)

            if (allPoints.isNotEmpty()) {
                allDataPoints.clear()
                allDataPoints.addAll(allPoints)

                // Sort by timestamp if needed
                allDataPoints.sortBy { it.timestamp }

                Log.d(TAG, "Loaded ${allDataPoints.size} total data points")

                val displayText = """
                    File: ${file.name}
                    Total Points: ${allDataPoints.size}
                    Size: ${formatFileSize(file.length())}
                """.trimIndent()
                binding.textFileInfo.text = displayText

                // Reset viewport
                currentStartIndex = 0
                currentEndIndex = if (allDataPoints.size > DISPLAY_POINTS) {
                    DISPLAY_POINTS
                } else {
                    allDataPoints.size
                }

                // Update the DataManager with the loaded data (optional)
                dataManager?.let { dm ->
                    // You might want to add a method to set data directly
                    // For now, we'll just use the loaded data
                }

                updateChartWithRange(currentStartIndex, currentEndIndex)
                updateScrollButtons()

                Toast.makeText(requireContext(), "Loaded ${allDataPoints.size} data points", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No valid data points found in file", Toast.LENGTH_SHORT).show()
                showEmptyState()
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

    // ==================== UI STATE FUNCTIONS ====================

    private fun updateChart() {
        // Update chart with current range
        updateChartWithRange(currentStartIndex, currentEndIndex)
    }

    private fun showEmptyState() {
        try {
            allDataPoints.clear()
            val emptyData = LineData()
            lineChart.data = emptyData
            lineChart.invalidate()
            binding.textFileInfo.text = "No data loaded. Click 'Load File' to select a file."
            binding.textLatestValue.text = "Latest: --"
            binding.textRangeInfo.visibility = View.GONE
            binding.buttonScrollLeft.visibility = View.GONE
            binding.buttonScrollRight.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error showing empty state", e)
        }
    }

    private fun clearData() {
        try {
            allDataPoints.clear()
            currentFilePath = ""
            currentStartIndex = 0
            currentEndIndex = 0
            dataManager?.clearPlotData()
            showEmptyState()
            Toast.makeText(requireContext(), "Data cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing data", e)
            Toast.makeText(requireContext(), "Error clearing data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(filePath: String = ""): PlotFragment {
            val fragment = PlotFragment()
            val args = Bundle()
            args.putString("filePath", filePath)
            fragment.arguments = args
            return fragment
        }
    }
}