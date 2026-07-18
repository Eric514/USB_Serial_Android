package com.deepgaze.glasses

import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.deepgaze.glasses.databinding.FragmentPlotBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PlotFragment : Fragment() {
    private var _binding: FragmentPlotBinding? = null
    private val binding get() = _binding!!

    private lateinit var lineChart: LineChart
    private var allDataPoints = mutableListOf<DataManager.CapacitanceDataPoint>()
    private val DISPLAY_POINTS = 1000
    private val TAG = "PlotFragment"
    private var dataManager: DataManager? = null
    private var patientId = ""
    private var patientName = ""
    private var currentFilePath: String = ""

    // Blink detection variables
    private var currentData: DoubleArray = doubleArrayOf()
    private var currentTimestamps: DoubleArray = doubleArrayOf()
    private var currentSpikeIndices: List<Int> = emptyList()
    private var currentFilteredData: DoubleArray = doubleArrayOf()
    private var isDetectingBlink = false
    private var showFilteredData = false

    // Viewport tracking for scrolling
    private var currentStartIndex = 0
    private var currentEndIndex = 0

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
                    // Don't set axisMinimum or axisMaximum for x-axis
                }

                axisLeft.apply {
                    textColor = Color.BLACK
                    setDrawGridLines(true)
                    gridColor = Color.LTGRAY
                    // ✅ Don't set axisMinimum - let it auto-calculate
                    setDrawZeroLine(false) // Don't force zero line
                    setDrawAxisLine(true)
                    // No axisMinimum or axisMaximum set - will auto-calculate from data
                }

                axisRight.isEnabled = false

                legend.apply {
                    isEnabled = true
                    textColor = Color.BLACK
                    textSize = 10f
                }

                setMaxHighlightDistance(0f)
                setViewPortOffsets(10f, 10f, 10f, 10f)
                setExtraOffsets(5f, 5f, 5f, 5f)

                // ✅ Enable auto-scaling
                setAutoScaleMinMaxEnabled(true)
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

            // Blink Detection Button
            binding.buttonDetectBlink.setOnClickListener {
                if (allDataPoints.isEmpty()) {
                    Toast.makeText(requireContext(), "No data to analyze. Load a file first.", Toast.LENGTH_SHORT).show()
                } else {
                    detectBlink()
                }
            }

            // Navigation buttons
            binding.buttonScrollLeft.setOnClickListener {
                scrollData(-DISPLAY_POINTS)
            }

            binding.buttonScrollRight.setOnClickListener {
                scrollData(DISPLAY_POINTS)
            }

            binding.buttonScrollLeft.visibility = View.GONE
            binding.buttonScrollRight.visibility = View.GONE

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up buttons", e)
        }
    }

    // ==================== BLINK DETECTION FUNCTIONS ====================


    private fun detectBlink() {
        if (allDataPoints.isEmpty()) {
            Toast.makeText(requireContext(), "No data to analyze. Load a file first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isDetectingBlink) {
            Toast.makeText(requireContext(), "Blink detection already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        prepareDataForDetection()

        if (currentData.isEmpty()) {
            Toast.makeText(requireContext(), "No valid data for detection", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            isDetectingBlink = true
            binding.buttonDetectBlink.isEnabled = false
            binding.buttonDetectBlink.text = "⏳ Detecting..."

            Toast.makeText(requireContext(), "Processing data for blink detection...", Toast.LENGTH_SHORT).show()

            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        // Create StepNoiseCorrector with appropriate parameters
                        val corrector = StepNoiseCorrector(
                            detrend = true,
                            detrendMethod = "smoothing",
                            medianFilter = true,
                            medianKernel = 5,
                            filterNoise = true,
                            cutoffFreq = 0.1,
                            filterOrder = 4,
                            window = 30,
                            requiredStable = 30,
                            maxIterations = 20,
                            noiseThreshold = 3.0,
                            lookAhead = 500
                        )

                        // Process the data
                        val processedResult = corrector.process(currentData, currentTimestamps)

                        // Get the final processed data - use finalData as fallback
                        val processedData = if (processedResult.detrendedData != null) {
                            processedResult.detrendedData!!
                        } else {
                            processedResult.finalData
                        }

                        Log.d(TAG, "Processed data size: ${processedData.size}")
                        Log.d(TAG, "Detrended data available: ${processedResult.detrendedData != null}")
                        Log.d(TAG, "Filtered data available: ${processedResult.filteredData != null}")

                        // Apply Savitzky-Golay filter
                        val filtered = savitzkyGolayFilter(processedData, 41, 4)

                        // Detect positive spikes
                        val peaks = detectPositivePeaks(filtered)

                        Log.d(TAG, "Blink detection results: ${peaks.size} peaks found")

                        Pair(filtered, peaks)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error in StepNoiseCorrector", e)
                        // Fallback: simple Savitzky-Golay on original data
                        val filtered = savitzkyGolayFilter(currentData, 41, 4)
                        val peaks = detectPositivePeaks(filtered)
                        Pair(filtered, peaks)
                    }
                }

                val (filteredData, spikeIndices) = result

                currentFilteredData = filteredData
                currentSpikeIndices = spikeIndices
                showFilteredData = true

                withContext(Dispatchers.Main) {
                    // Update chart with filtered data and blink markers
                    updateChartWithFilteredData()

                    val message = if (spikeIndices.isNotEmpty()) {
                        "Found ${spikeIndices.size} blinks! 🎉"
                    } else {
                        "No blinks detected. Try adjusting parameters."
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

                    // Update info text
                    if (spikeIndices.isNotEmpty()) {
                        val blinkInfo = "Blinks: ${spikeIndices.size}"
                        binding.textLatestValue.text = "$blinkInfo | Latest: ${allDataPoints.last().capacitance} pF"
                    }

                    // Show blink detection options
                    if (spikeIndices.isNotEmpty()) {
                        showBlinkDetectionOptions(spikeIndices)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isDetectingBlink = false
                    binding.buttonDetectBlink.isEnabled = true
                    binding.buttonDetectBlink.text = "👁️ Detect Blinks"
                }
            }
        }
    }

    private fun prepareDataForDetection() {
        try {
            if (allDataPoints.isEmpty()) {
                currentData = doubleArrayOf()
                currentTimestamps = doubleArrayOf()
                return
            }

            val values = DoubleArray(allDataPoints.size) { index ->
                allDataPoints[index].capacitance
            }

            val timestamps = DoubleArray(allDataPoints.size) { index ->
                allDataPoints[index].timestamp.toDouble()
            }

            currentData = values
            currentTimestamps = timestamps

            Log.d(TAG, "Prepared ${currentData.size} data points for detection")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing data", e)
            currentData = doubleArrayOf()
            currentTimestamps = doubleArrayOf()
        }
    }

    private fun detectPositivePeaks(data: DoubleArray): List<Int> {
        if (data.size < 3) return emptyList()

        val mean = data.average()
        val std = data.std()

        // Try multiple thresholds to find the best peaks
        val multipliers = listOf(1.5, 2.0, 2.5, 3.0, 3.5, 4.0)
        var bestPeaks = emptyList<Int>()
        var bestScore = 0

        for (multiplier in multipliers) {
            val threshold = mean + (multiplier * std)
            val peaks = findPeaksWithThreshold(data, threshold)

            // Score peaks: prefer finding between 3 and 30 peaks with good spacing
            if (peaks.isNotEmpty() && peaks.size in 3..30 && peaks.size > bestScore) {
                bestPeaks = peaks
                bestScore = peaks.size
                Log.d(TAG, "Threshold multiplier $multiplier found ${peaks.size} peaks")
            }
        }

        // If no peaks found with standard thresholds, try lower threshold
        if (bestPeaks.isEmpty()) {
            val threshold = mean + (0.5 * std)
            bestPeaks = findPeaksWithThreshold(data, threshold)
            Log.d(TAG, "Using low threshold, found ${bestPeaks.size} peaks")
        }

        return bestPeaks
    }

    private fun findPeaksWithThreshold(data: DoubleArray, threshold: Double): List<Int> {
        val peaks = mutableListOf<Int>()
        val minDistance = 5

        for (i in 1 until data.size - 1) {
            // Check if it's a local maximum AND exceeds threshold
            if (data[i] > data[i - 1] &&
                data[i] > data[i + 1] &&
                data[i] > threshold
            ) {
                // Check distance from previous peak
                if (peaks.isEmpty() || i - peaks.last() >= minDistance) {
                    peaks.add(i)
                }
            }
        }

        return peaks
    }

    private fun savitzkyGolayFilter(data: DoubleArray, windowLength: Int, polyorder: Int = 3): DoubleArray {
        if (data.isEmpty()) return data

        val halfWindow = windowLength / 2
        val result = DoubleArray(data.size)

        for (i in data.indices) {
            var sum = 0.0
            var count = 0
            for (j in -halfWindow..halfWindow) {
                val idx = i + j
                if (idx in data.indices) {
                    sum += data[idx]
                    count++
                }
            }
            result[i] = if (count > 0) sum / count else data[i]
        }
        return result
    }

    private fun DoubleArray.std(): Double {
        if (isEmpty()) return 0.0
        val mean = average()
        val variance = map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }

    private fun updateChartWithFilteredData() {
        try {
            if (!isAdded || isDetached || allDataPoints.isEmpty()) {
                return
            }

            val safeStart = currentStartIndex.coerceIn(0, allDataPoints.size - 1)
            val safeEnd = currentEndIndex.coerceIn(1, allDataPoints.size)

            if (safeStart >= safeEnd) {
                return
            }

            val entries = mutableListOf<Entry>()
            val blinkEntries = mutableListOf<Entry>()

            for (i in safeStart until safeEnd) {
                // Use filtered data if available and showFilteredData is true
                val value = if (showFilteredData && i < currentFilteredData.size) {
                    currentFilteredData[i]
                } else {
                    allDataPoints[i].capacitance
                }

                entries.add(Entry((i - safeStart).toFloat(), value.toFloat()))

                // Mark blinks if this index is in the spike list
                if (currentSpikeIndices.contains(i)) {
                    blinkEntries.add(Entry((i - safeStart).toFloat(), value.toFloat()))
                }
            }

            if (entries.isEmpty()) {
                showEmptyState()
                return
            }

            // Create data set for the main data
            val dataSet = LineDataSet(entries, if (showFilteredData) "Filtered Data" else "Capacitance (pF)").apply {
                color = if (showFilteredData) Color.parseColor("#FF6B35") else Color.BLUE
                setCircleColor(if (showFilteredData) Color.parseColor("#FF6B35") else Color.BLUE)
                lineWidth = 2f
                circleRadius = 2f
                setDrawCircleHole(false)
                valueTextColor = Color.BLACK
                valueTextSize = 8f
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = if (showFilteredData) Color.argb(50, 255, 107, 53) else Color.argb(50, 0, 0, 255)
                fillAlpha = 50
                mode = LineDataSet.Mode.LINEAR
                setDrawHorizontalHighlightIndicator(false)
                setDrawVerticalHighlightIndicator(false)
            }

            val lineData = LineData(dataSet)

            // Add blink markers as a separate data set if blinks exist
            if (blinkEntries.isNotEmpty()) {
                val blinkDataSet = LineDataSet(blinkEntries, "Blinks Detected").apply {
                    color = Color.RED
                    setCircleColor(Color.RED)
                    circleRadius = 8f
                    setDrawCircleHole(true)
                    circleHoleColor = Color.RED
                    lineWidth = 0f
                    setDrawValues(false)
                    setDrawFilled(false)
                    setDrawHorizontalHighlightIndicator(false)
                    setDrawVerticalHighlightIndicator(false)
                }
                lineData.addDataSet(blinkDataSet)

                val blinkCount = currentSpikeIndices.count { it in safeStart until safeEnd }
                val totalBlinkCount = currentSpikeIndices.size
                binding.textFileInfo.text = """
                    File: ${File(currentFilePath).name}
                    ${if (showFilteredData) "Filtered Data" else "Raw Data"}
                    Blinks: $blinkCount (total: $totalBlinkCount) shown in view
                """.trimIndent()
            } else {
                val infoText = """
                    File: ${File(currentFilePath).name}
                    ${if (showFilteredData) "Filtered Data" else "Raw Data"}
                    Points: ${allDataPoints.size}
                    ${if (currentSpikeIndices.isNotEmpty()) "Blinks: ${currentSpikeIndices.size}" else "No blinks detected"}
                """.trimIndent()
                binding.textFileInfo.text = infoText
            }

            lineChart.data = lineData
            lineChart.setVisibleXRangeMaximum(DISPLAY_POINTS.toFloat())
            lineChart.fitScreen()
            lineChart.invalidate()

        } catch (e: Exception) {
            Log.e(TAG, "Error updating chart with filtered data", e)
        }
    }

    private fun showBlinkDetectionOptions(spikeIndices: List<Int>) {
        if (spikeIndices.isEmpty()) return

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("📊 Show Blink Statistics")
        actions.add { showBlinkStatistics() }

        options.add("📋 Copy Blink Data")
        actions.add { copyBlinkData() }

        options.add("🎯 Show Only Blinks")
        actions.add { showOnlyBlinks() }

        options.add("🔄 Show Raw Data")
        actions.add {
            showFilteredData = false
            updateChartWithFilteredData()
            Toast.makeText(requireContext(), "Showing raw data", Toast.LENGTH_SHORT).show()
        }

        options.add("🔄 Show Filtered Data")
        actions.add {
            showFilteredData = true
            updateChartWithFilteredData()
            Toast.makeText(requireContext(), "Showing filtered data", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Blink Detection Results")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBlinkStatistics() {
        if (currentSpikeIndices.isEmpty()) {
            Toast.makeText(requireContext(), "No blinks detected", Toast.LENGTH_SHORT).show()
            return
        }

        val blinkData = currentSpikeIndices.map { index ->
            allDataPoints.getOrNull(index)
        }.filterNotNull()

        if (blinkData.isEmpty()) {
            Toast.makeText(requireContext(), "No blink data available", Toast.LENGTH_SHORT).show()
            return
        }

        val stats = buildString {
            appendLine("📊 BLINK STATISTICS")
            appendLine("═".repeat(40))
            appendLine()
            appendLine("Total Blinks: ${blinkData.size}")
            appendLine()

            if (blinkData.size > 1) {
                val intervals = mutableListOf<Double>()
                for (i in 0 until blinkData.size - 1) {
                    val interval = (blinkData[i + 1].timestamp - blinkData[i].timestamp).toDouble()
                    intervals.add(interval)
                }

                val avgInterval = intervals.average()
                val minInterval = intervals.minOrNull() ?: 0.0
                val maxInterval = intervals.maxOrNull() ?: 0.0
                val blinkRate = if (avgInterval > 0) 60000.0 / avgInterval else 0.0

                appendLine("Interval Statistics:")
                appendLine("  Average: ${String.format("%.1f", avgInterval)} ms")
                appendLine("  Min: ${String.format("%.1f", minInterval)} ms")
                appendLine("  Max: ${String.format("%.1f", maxInterval)} ms")
                appendLine("  Blink Rate: ${String.format("%.1f", blinkRate)} blinks/min")
                appendLine()
            }

            if (blinkData.size > 0) {
                appendLine("First 5 blinks:")
                blinkData.take(5).forEachIndexed { index, point ->
                    appendLine("  ${index + 1}. ${point.formattedTime} - ${point.capacitance} pF")
                }
                if (blinkData.size > 10) {
                    appendLine("  ...")
                    blinkData.takeLast(5).forEachIndexed { index, point ->
                        val actualIndex = blinkData.size - 5 + index
                        appendLine("  ${actualIndex + 1}. ${point.formattedTime} - ${point.capacitance} pF")
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Blink Statistics")
            .setMessage(stats)
            .setPositiveButton("Close", null)
            .setNegativeButton("Copy") { _, _ ->
                copyToClipboard(stats)
            }
            .show()
    }

    private fun copyBlinkData() {
        if (currentSpikeIndices.isEmpty()) {
            Toast.makeText(requireContext(), "No blink data to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val blinkData = currentSpikeIndices.map { index ->
            val point = allDataPoints.getOrNull(index)
            if (point != null) {
                "${point.formattedTime}, ${point.capacitance}"
            } else null
        }.filterNotNull()

        val dataString = blinkData.joinToString("\n")
        copyToClipboard(dataString)
        Toast.makeText(requireContext(), "Blink data copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showOnlyBlinks() {
        if (currentSpikeIndices.isEmpty()) {
            Toast.makeText(requireContext(), "No blinks to show", Toast.LENGTH_SHORT).show()
            return
        }

        val firstBlink = currentSpikeIndices.firstOrNull() ?: return
        val start = (firstBlink - 50).coerceAtLeast(0)
        val end = (firstBlink + 100).coerceAtMost(allDataPoints.size)

        currentStartIndex = start
        currentEndIndex = end
        updateChartWithFilteredData()
        updateScrollButtons()
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Blink Data", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }

    // ==================== SCROLLING FUNCTIONS ====================

    private fun scrollData(step: Int) {
        if (allDataPoints.isEmpty() || allDataPoints.size <= DISPLAY_POINTS) return

        var newStart = currentStartIndex + step
        var newEnd = currentEndIndex + step

        if (newStart < 0) {
            newStart = 0
            newEnd = Math.min(DISPLAY_POINTS, allDataPoints.size)
        }

        if (newEnd > allDataPoints.size) {
            newEnd = allDataPoints.size
            newStart = Math.max(0, allDataPoints.size - DISPLAY_POINTS)
        }

        if (newEnd - newStart < 10) {
            if (newStart > 0) {
                newStart = Math.max(0, newEnd - 10)
            } else {
                newEnd = Math.min(allDataPoints.size, newStart + 10)
            }
        }

        currentStartIndex = newStart
        currentEndIndex = newEnd

        if (currentSpikeIndices.isNotEmpty() || showFilteredData) {
            updateChartWithFilteredData()
        } else {
            updateChartWithRange(currentStartIndex, currentEndIndex)
        }
        updateScrollButtons()
    }

    private fun updateChartWithRange(startIndex: Int, endIndex: Int) {
        try {
            if (!isAdded || isDetached || allDataPoints.isEmpty()) {
                return
            }

            val safeStart = startIndex.coerceIn(0, allDataPoints.size - 1)
            val safeEnd = endIndex.coerceIn(1, allDataPoints.size)

            if (safeStart >= safeEnd) {
                return
            }

            val entries = mutableListOf<Entry>()
            for (i in safeStart until safeEnd) {
                val point = allDataPoints[i]
                entries.add(Entry((i - safeStart).toFloat(), point.capacitance.toFloat()))
            }

            if (entries.isEmpty()) {
                showEmptyState()
                return
            }

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

            lineChart.setVisibleXRangeMaximum(DISPLAY_POINTS.toFloat())
            lineChart.fitScreen()

            val infoText = """
                Showing: ${safeStart + 1} - ${safeEnd} of ${allDataPoints.size} points
                File: ${File(currentFilePath).name}
            """.trimIndent()
            binding.textFileInfo.text = infoText

            if (safeEnd == allDataPoints.size && allDataPoints.isNotEmpty()) {
                val lastPoint = allDataPoints.last()
                binding.textLatestValue.text = "Latest: ${lastPoint.capacitance} pF"
            }

            lineChart.invalidate()
            currentStartIndex = safeStart
            currentEndIndex = safeEnd

        } catch (e: Exception) {
            Log.e(TAG, "Error updating chart with range", e)
        }
    }

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

        binding.buttonScrollLeft.isEnabled = currentStartIndex > 0
        binding.buttonScrollRight.isEnabled = currentEndIndex < allDataPoints.size

        binding.buttonScrollLeft.text = "◀ ${currentStartIndex + 1}"
        binding.buttonScrollRight.text = "${currentEndIndex} ▶"

        val rangeText = "Showing: ${currentStartIndex + 1} - ${currentEndIndex} of ${allDataPoints.size}"
        binding.textRangeInfo.text = rangeText
    }

    // ==================== FILE LOADING FUNCTIONS ====================

    private fun loadAllDataFromFile(file: File): List<DataManager.CapacitanceDataPoint> {
        val points = mutableListOf<DataManager.CapacitanceDataPoint>()
        try {
            file.bufferedReader().use { reader ->
                var firstLine = true
                reader.forEachLine { line ->
                    if (firstLine) {
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

    private fun getSerialDataDirectory(): File {
        val baseDir = requireContext().getExternalFilesDir(null)
        val serialDataDir = File(baseDir, "SerialData")
        if (!serialDataDir.exists()) {
            serialDataDir.mkdirs()
        }
        return serialDataDir
    }

    private fun getAppDataFiles(): List<File> {
        val serialDataDir = getSerialDataDirectory()
        return serialDataDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".csv") || file.name.endsWith(".txt") || file.name.endsWith(".log"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

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

    private fun loadFileFromPath(filePath: String) {
        try {
            currentFilePath = filePath
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(requireContext(), "File does not exist: $filePath", Toast.LENGTH_SHORT).show()
                return
            }

            // Reset filtered data state
            showFilteredData = false
            currentSpikeIndices = emptyList()
            currentFilteredData = doubleArrayOf()

            val allPoints = loadAllDataFromFile(file)

            if (allPoints.isNotEmpty()) {
                allDataPoints.clear()
                allDataPoints.addAll(allPoints)
                allDataPoints.sortBy { it.timestamp }

                Log.d(TAG, "Loaded ${allDataPoints.size} total data points")

                binding.buttonDetectBlink.isEnabled = true

                val displayText = """
                    File: ${file.name}
                    Total Points: ${allDataPoints.size}
                    Size: ${formatFileSize(file.length())}
                """.trimIndent()
                binding.textFileInfo.text = displayText

                currentStartIndex = 0
                currentEndIndex = if (allDataPoints.size > DISPLAY_POINTS) {
                    DISPLAY_POINTS
                } else {
                    allDataPoints.size
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

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            size >= 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format("%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    private fun updateChart() {
        if (showFilteredData && currentFilteredData.isNotEmpty()) {
            updateChartWithFilteredData()
        } else {
            updateChartWithRange(currentStartIndex, currentEndIndex)
        }
    }

    private fun showEmptyState() {
        try {
            allDataPoints.clear()
            currentSpikeIndices = emptyList()
            currentData = doubleArrayOf()
            currentFilteredData = doubleArrayOf()
            showFilteredData = false

            val emptyData = LineData()
            lineChart.data = emptyData
            lineChart.invalidate()
            binding.textFileInfo.text = "No data loaded. Click 'Load File' to select a file."
            binding.textLatestValue.text = "Latest: --"
            binding.textRangeInfo.visibility = View.GONE
            binding.buttonScrollLeft.visibility = View.GONE
            binding.buttonScrollRight.visibility = View.GONE
            binding.buttonDetectBlink.isEnabled = false
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
            currentSpikeIndices = emptyList()
            currentData = doubleArrayOf()
            currentFilteredData = doubleArrayOf()
            showFilteredData = false
            dataManager?.clearPlotData()
            showEmptyState()
            binding.buttonDetectBlink.isEnabled = false
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