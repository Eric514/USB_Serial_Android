package com.deepgaze.glasses

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
                    // Get arguments with safe defaults
                    patientId = args.getString("patientId", "") ?: ""
                    patientName = args.getString("patientName", "") ?: ""
                    val mode = args.getString("mode", "") ?: ""
                    val filePath = args.getString("filePath", "") ?: ""

                    Log.d(TAG, "Arguments received - mode: $mode, patientId: $patientId, filePath: $filePath")

                    // Handle the mode properly - using when expression for cleaner code
                    when {
                        mode == "realtime" -> {
                            dataManager?.let { dm ->
                                dataPoints = dm.getPlotDataPoints(maxDisplayPoints).toMutableList()
                                if (dataPoints.isNotEmpty()) {
                                    Toast.makeText(requireContext(), "Loaded ${dataPoints.size} data points", Toast.LENGTH_SHORT).show()
                                } else {
                                    Log.d(TAG, "No data points in DataManager yet")
                                }
                            }
                        }
                        filePath.isNotEmpty() -> {
                            dataManager?.let { dm ->
                                val file = File(filePath)
                                if (file.exists()) {
                                    dm.loadPlotDataFromFile(file)
                                    dataPoints = dm.getPlotDataPoints(maxDisplayPoints).toMutableList()
                                    Log.d(TAG, "Loaded ${dataPoints.size} data points from file: $filePath")
                                } else {
                                    Log.w(TAG, "File does not exist: $filePath")
                                }
                            }
                        }
                        else -> {
                            Log.d(TAG, "No mode specified or file path provided")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing arguments", e)
                }
            }

            setupChart()
            setupButtons()

            if (dataPoints.isNotEmpty()) {
                startRealTimeMode()
            } else {
                showEmptyState()
                binding.textFileInfo.text = "Click 'Start' to begin real-time plotting"
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

            binding.buttonLoadFile.setOnClickListener {
                try {
                    loadDataFile()
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading file", e)
                    Toast.makeText(requireContext(), "Error loading file: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun loadDataFile() {
        try {
            val externalFilesDir = requireContext().getExternalFilesDir(null)
            if (externalFilesDir != null && externalFilesDir.exists()) {
                val files = externalFilesDir.listFiles { file ->
                    file.name.endsWith(".csv") && file.name.startsWith("serial_data_")
                }

                if (files != null && files.isNotEmpty()) {
                    val latestFile = files.maxByOrNull { it.lastModified() }
                    if (latestFile != null) {
                        dataManager?.let { dm ->
                            dm.loadPlotDataFromFile(latestFile)
                            dataPoints = dm.getPlotDataPoints(maxDisplayPoints).toMutableList()
                            val displayText = "Loaded: ${latestFile.name}\nPoints: ${dataPoints.size}"
                            binding.textFileInfo.text = displayText
                            updateChart()
                            Toast.makeText(requireContext(), "Loaded ${dataPoints.size} data points", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "No data files found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "External storage not available", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading file", e)
            Toast.makeText(requireContext(), "Error loading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

                // Build patient info string with proper if-else
                val patientInfo = if (patientName.isNotEmpty()) {
                    " | Patient: $patientName"
                } else {
                    ""
                }
                binding.textFileInfo.text = "Points: ${dataPoints.size}$patientInfo"
            }

            // Update statistics from DataManager
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
            binding.textFileInfo.text = "No data loaded. Click 'Start' for real-time."
            binding.textLatestValue.text = "Latest: --"
            binding.textStats.text = "Min: -- | Max: -- | Avg: -- | Median: -- | Count: 0"
        } catch (e: Exception) {
            Log.e(TAG, "Error showing empty state", e)
        }
    }

    private fun clearData() {
        try {
            dataPoints.clear()
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

