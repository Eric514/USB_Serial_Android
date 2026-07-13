package com.deepgaze.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.deepgaze.glasses.databinding.FragmentSerialBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.*
import java.util.concurrent.Executors
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import kotlin.collections.ArrayList

class SerialFragment : Fragment() {
    private var _binding: FragmentSerialBinding? = null
    private val binding get() = _binding!!

    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    private var deviceList = mutableListOf<UsbSerialDriver>()
    private var selectedDriver: UsbSerialDriver? = null

    private var isConnected = false
    private var isReceiving = false
    private var dataCounter = 0
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val dataBuffer = StringBuilder()
    private var isProcessingQueue = false
    private var lastDataTime = 0L
    private val TIMEOUT_MS = 100L
    private val MIN_MESSAGE_LENGTH = 2
    private val MAX_BUFFER_SIZE = 1024 * 1024

    // Graph related variables - OPTIMIZED
    private lateinit var lineChart: LineChart
    private lateinit var lineDataSet: LineDataSet
    private val graphDataPoints = mutableListOf<Entry>()
    private val MAX_GRAPH_POINTS = 100
    private var showGraph = false
    private var isGraphUpdating = false
    private var graphUpdateCounter = 0
    private var pendingGraphUpdate = false

    // Cache for graph data to avoid repeated object creation
    private val entryCache = mutableListOf<Entry>()
    private val valueCache = mutableListOf<Float>()

    // Message delimiters
    private val MESSAGE_DELIMITERS = listOf("\n", "\r\n", "\r", ";", "\t")
    private var customDelimiter: String? = null

    // Patient data
    private var patientId = ""
    private var patientName = ""
    private var gender = ""
    private var age = ""
    private var patientDate = ""
    private var patientTime = ""
    private var notes = ""

    // Serial configuration defaults
    private var selectedBaudRate = 921600
    private var selectedDataBits = 8
    private var selectedStopBits = UsbSerialPort.STOPBITS_1
    private var selectedParity = UsbSerialPort.PARITY_NONE

    private lateinit var ACTION_USB_PERMISSION: String
    private val TAG = "SerialFragment"

    // Data Save to Data Manager
    private lateinit var dataManager: DataManager
    private var isSavingEnabled = true
    private var currentDataBuffer = StringBuilder()
    private var lastSaveTime = 0L
    private val SAVE_INTERVAL_MS = 5000L

    // ==================== DECLARE ALL FUNCTIONS FIRST ====================

    private fun processIncomingData(data: ByteArray) {
        try {
            val text = String(data, Charsets.UTF_8)
            Log.d(TAG, "Raw data received: $text")

            dataBuffer.append(text)

            if (dataBuffer.length > MAX_BUFFER_SIZE) {
                Log.w(TAG, "Buffer size exceeded, clearing")
                dataBuffer.clear()
                return
            }

            extractMessages()

            if (dataBuffer.isNotEmpty()) {
                Log.d(TAG, "Buffer has ${dataBuffer.length} chars waiting for complete message")
                scheduleBufferTimeout()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing data", e)
        }
    }

    private fun extractMessages() {
        if (dataBuffer.isEmpty()) return

        for (delimiter in MESSAGE_DELIMITERS) {
            var index: Int

            while (dataBuffer.indexOf(delimiter).also { index = it } != -1) {
                val endPos = index + delimiter.length
                val message = dataBuffer.substring(0, endPos).trim()
                dataBuffer.delete(0, endPos)

                if (message.isNotEmpty() && message.length >= MIN_MESSAGE_LENGTH) {
                    Log.d(TAG, "Complete message extracted: $message")
                    handleCompleteMessage(message)
                } else if (message.isNotEmpty()) {
                    Log.d(TAG, "Message too short, ignoring: $message")
                }
            }
        }
    }

    private fun handleCompleteMessage(message: String) {
        val cleanMessage = message.trim()
        displayData(cleanMessage)
        saveSerialData(cleanMessage)
        dataCounter++
    }

    private fun scheduleBufferTimeout() {
        handler.removeCallbacks(bufferTimeoutRunnable)
        handler.postDelayed(bufferTimeoutRunnable, TIMEOUT_MS)
    }

    private val bufferTimeoutRunnable = Runnable {
        if (dataBuffer.isNotEmpty()) {
            Log.d(TAG, "Buffer timeout - processing remaining data: ${dataBuffer.length} chars")
            val remainingData = dataBuffer.toString().trim()
            dataBuffer.clear()

            if (remainingData.isNotEmpty() && remainingData.length >= MIN_MESSAGE_LENGTH) {
                if (customDelimiter != null) {
                    val parts = remainingData.split(customDelimiter!!)
                    parts.forEach { part ->
                        if (part.isNotEmpty()) {
                            handleCompleteMessage(part)
                        }
                    }
                } else {
                    handleCompleteMessage(remainingData)
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        handler.post {
            try {
                binding.textStatus.text = "Status: $text"
            } catch (e: Exception) {
                Log.e(TAG, "updateStatus error", e)
            }
        }
    }

    private fun displayPatientInfo() {
        if (patientId.isNotEmpty()) {
            val info = """
                👤 Patient: $patientName (ID: $patientId)
                📊 Gender: ${if(gender.isEmpty()) "N/A" else gender} | Age: ${if(age.isEmpty()) "N/A" else age}
                📅 $patientDate | 🕐 $patientTime
            """.trimIndent()
            binding.textPatientInfo.text = info
            binding.textPatientInfo.visibility = View.VISIBLE
        }
    }

    private fun getPatientDataSummary(): String {
        return """
            ════════════════════════════════════════
            👤 PATIENT INFORMATION
            ════════════════════════════════════════
            Patient ID: ${if(patientId.isEmpty()) "N/A" else patientId}
            Patient Name: ${if(patientName.isEmpty()) "N/A" else patientName}
            Gender: ${if(gender.isEmpty()) "N/A" else gender}
            Age: ${if(age.isEmpty()) "N/A" else age}
            Date: ${if(patientDate.isEmpty()) "N/A" else patientDate}
            Time: ${if(patientTime.isEmpty()) "N/A" else patientTime}
            Notes: ${if(notes.isEmpty()) "N/A" else notes}
            ════════════════════════════════════════
        """.trimIndent()
    }

    private fun setupSpinners() {
        // Device Spinner
        binding.spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < deviceList.size) {
                    selectedDriver = deviceList[position]
                    Log.d(TAG, "Device selected: ${selectedDriver?.device?.deviceName}")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Baud Rate Spinner
        val baudRates = arrayOf("9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600")
        val baudAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, baudRates)
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBaudRate.adapter = baudAdapter
        binding.spinnerBaudRate.setSelection(7)
        binding.spinnerBaudRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBaudRate = baudRates[position].toInt()
                if (isConnected) {
                    updateSerialParameters()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Data Bits Spinner
        val dataBits = arrayOf("5", "6", "7", "8")
        val dataBitsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, dataBits)
        dataBitsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDataBits.adapter = dataBitsAdapter
        binding.spinnerDataBits.setSelection(3)
        binding.spinnerDataBits.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDataBits = dataBits[position].toInt()
                if (isConnected) {
                    updateSerialParameters()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Stop Bits Spinner
        val stopBits = arrayOf("1", "1.5", "2")
        val stopBitsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, stopBits)
        stopBitsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStopBits.adapter = stopBitsAdapter
        binding.spinnerStopBits.setSelection(0)
        binding.spinnerStopBits.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedStopBits = when (position) {
                    0 -> UsbSerialPort.STOPBITS_1
                    1 -> UsbSerialPort.STOPBITS_1_5
                    else -> UsbSerialPort.STOPBITS_2
                }
                if (isConnected) {
                    updateSerialParameters()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Parity Spinner
        val parity = arrayOf("None", "Odd", "Even", "Mark", "Space")
        val parityAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, parity)
        parityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerParity.adapter = parityAdapter
        binding.spinnerParity.setSelection(0)
        binding.spinnerParity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedParity = when (position) {
                    0 -> UsbSerialPort.PARITY_NONE
                    1 -> UsbSerialPort.PARITY_ODD
                    2 -> UsbSerialPort.PARITY_EVEN
                    3 -> UsbSerialPort.PARITY_MARK
                    else -> UsbSerialPort.PARITY_SPACE
                }
                if (isConnected) {
                    updateSerialParameters()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        try {
            binding.buttonConnect.setOnClickListener { toggleConnection() }
            binding.buttonDisconnect.setOnClickListener { disconnect() }
            binding.buttonRefresh.setOnClickListener { refreshDeviceList() }
            binding.buttonBack.setOnClickListener {
                findNavController().navigateUp()
            }
            binding.buttonPlot.setOnClickListener {
                navigateToPlot()
            }
            binding.buttonStorageManager.setOnClickListener {
                findNavController().navigate(R.id.action_serialFragment_to_folderViewerFragment)
            }

            // Toggle view button
            binding.buttonToggleView.setOnClickListener {
                toggleView()
            }

            Log.d(TAG, "Buttons setup done")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up buttons", e)
        }
    }

    private fun setupChart() {
        try {
            lineChart = binding.lineChart

            // Performance optimizations for the chart
            lineChart.apply {
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                setBackgroundColor(Color.WHITE)

                // Performance improvements
                setDrawGridBackground(false)
                setDrawBorders(false)
                setMaxHighlightDistance(0f) // Disable highlighting for performance
                setScaleYEnabled(true)
                setScaleXEnabled(true)
                setHardwareAccelerationEnabled(false)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = Color.BLACK
                    setDrawGridLines(true)
                    gridColor = Color.LTGRAY
                    setDrawLabels(true)
                    granularity = 1f
                    labelCount = 5
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
                }

                axisRight.isEnabled = false

                legend.apply {
                    isEnabled = true
                    textColor = Color.BLACK
                }
            }

            // Create dataset once and reuse it
            lineDataSet = LineDataSet(emptyList(), "Capacitance (pF)").apply {
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
            }

            val lineData = LineData(lineDataSet)
            lineChart.data = lineData

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up chart", e)
        }
    }

    private fun toggleView() {
        showGraph = !showGraph

        if (showGraph) {
            // Show graph, hide text display
            binding.scrollView.visibility = View.GONE
            binding.graphContainer.visibility = View.VISIBLE
            binding.buttonToggleView.text = "Switch to Data"

            // Initialize chart if needed
            if (!this::lineChart.isInitialized) {
                setupChart()
            }

            // Update graph with latest data
            updateGraph()

            Log.d(TAG, "Graph view enabled - updating with ${graphDataPoints.size} points")

        } else {
            // Show text display, hide graph
            binding.graphContainer.visibility = View.GONE
            binding.scrollView.visibility = View.VISIBLE
            binding.buttonToggleView.text = "Switch to Graph"

            Log.d(TAG, "Graph view disabled")
        }
    }

    private fun updateGraph() {
        // ONLY update if graph is visible
        if (!showGraph) {
            pendingGraphUpdate = false
            return
        }

        try {
            if (!this::lineChart.isInitialized || !this::lineDataSet.isInitialized) return
            if (isGraphUpdating) return
            if (graphDataPoints.isEmpty()) {
                // Clear the graph
                val emptyData = LineData(lineDataSet)
                lineChart.data = emptyData
                lineChart.invalidate()
                return
            }

            isGraphUpdating = true
            pendingGraphUpdate = false

            try {
                // Reuse the dataset instead of creating new one
                lineDataSet.values = graphDataPoints

                // Notify the chart of changes
                lineDataSet.notifyDataSetChanged()

                // Create new data object with the updated dataset
                val lineData = LineData(lineDataSet)
                lineChart.data = lineData

                // Only animate if not too frequent
                if (graphUpdateCounter % 5 == 0) {
                    lineChart.animateX(100)
                }

                lineChart.invalidate()
                graphUpdateCounter++

            } finally {
                isGraphUpdating = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating graph", e)
            isGraphUpdating = false
        }
    }

    // Schedule a graph update if graph is visible
    private fun scheduleGraphUpdate() {
        if (showGraph && !isGraphUpdating) {
            // If there's a pending update, just update now
            if (pendingGraphUpdate) {
                updateGraph()
            } else {
                // Schedule update for next frame
                pendingGraphUpdate = true
                handler.postDelayed({
                    if (pendingGraphUpdate && showGraph) {
                        updateGraph()
                    }
                }, 50) // Small delay to batch updates
            }
        }
    }

    private fun refreshDeviceList() {
        try {
            Log.d(TAG, "refreshDeviceList started")
            deviceList.clear()

            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isNotEmpty()) {
                deviceList.addAll(drivers)
                val deviceNames = drivers.map { driver ->
                    val device = driver.device
                    "VID:${device.vendorId} PID:${device.productId}"
                }

                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, deviceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerDevice.adapter = adapter

                if (deviceNames.isNotEmpty()) {
                    binding.spinnerDevice.setSelection(0)
                    selectedDriver = deviceList[0]
                    updateStatus("Device found: ${deviceNames[0]}")
                    binding.buttonConnect.isEnabled = true
                }
                Log.d(TAG, "Device list refreshed: ${deviceList.size} devices found")
            } else {
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("No devices found"))
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerDevice.adapter = adapter
                updateStatus("No USB devices found")
                binding.buttonConnect.isEnabled = false
                Log.d(TAG, "No devices found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in refreshDeviceList", e)
            updateStatus("Error: ${e.message}")
            binding.buttonConnect.isEnabled = false
        }
    }

    private fun toggleConnection() {
        Log.d(TAG, "toggleConnection: isConnected=$isConnected, isReceiving=$isReceiving")
        if (!isConnected) {
            connect()
        } else {
            if (isReceiving) {
                stopReceiving()
            } else {
                startReceiving()
            }
        }
    }

    private fun connect() {
        Log.d(TAG, "connect started")
        try {
            val driver = selectedDriver ?: run {
                updateStatus("No device selected")
                Toast.makeText(requireContext(), "Please select a device", Toast.LENGTH_SHORT).show()
                return
            }

            val device = driver.device

            if (usbManager?.hasPermission(device) == true) {
                openDevice(device)
            } else {
                val permissionIntent = PendingIntent.getBroadcast(
                    requireContext(),
                    0,
                    Intent(ACTION_USB_PERMISSION),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0
                )
                usbManager?.requestPermission(device, permissionIntent)
                updateStatus("Requesting permission...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            updateStatus("Connect error: ${e.message}")
            Toast.makeText(requireContext(), "Connect failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDevice(device: UsbDevice) {
        Log.d(TAG, "openDevice started")
        try {
            val driver = selectedDriver ?: return
            val connection = usbManager?.openDevice(device)
            if (connection == null) {
                updateStatus("Failed to open device")
                Toast.makeText(requireContext(), "Failed to open device", Toast.LENGTH_SHORT).show()
                return
            }

            serialPort = driver.ports[0]
            serialPort?.open(connection)
            serialPort?.setParameters(
                selectedBaudRate,
                selectedDataBits,
                selectedStopBits,
                selectedParity
            )

            isConnected = true
            updateStatus("Connected at $selectedBaudRate baud")
            binding.buttonConnect.text = "Start Receiving"

            enableSpinners(false)

            val configInfo = """
                ✅ Connected to: ${device.deviceName}
                📊 VID: ${device.vendorId}, PID: ${device.productId}
                ⚡ Baud Rate: $selectedBaudRate
                📊 Data Bits: $selectedDataBits
                ⏹ Stop Bits: ${getStopBitsText(selectedStopBits)}
                🔄 Parity: ${getParityText(selectedParity)}
                ──────────────────────────────
                ⏳ Waiting for data...
            """.trimIndent()

            val patientInfo = getPatientDataSummary()
            binding.textDataDisplay.text = "$patientInfo\n\n$configInfo"

            Toast.makeText(requireContext(), "✅ Connected to USB device", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Device opened successfully")

        } catch (e: Exception) {
            Log.e(TAG, "openDevice error", e)
            updateStatus("Open error: ${e.message}")
            Toast.makeText(requireContext(), "Open failed: ${e.message}", Toast.LENGTH_SHORT).show()
            disconnect()
        }
    }

    private fun startReceiving() {
        Log.d(TAG, "startReceiving")
        if (!isConnected || serialPort == null) {
            updateStatus("Not connected")
            Toast.makeText(requireContext(), "Not connected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            serialIoManager = SerialInputOutputManager(serialPort, serialListener)
            executor = Executors.newSingleThreadExecutor()
            executor?.submit { serialIoManager?.start() }

            isReceiving = true
            dataCounter = 0
            binding.buttonConnect.text = "Stop Receiving"
            updateStatus("Receiving data...")

            // Clear graph data when starting
            graphDataPoints.clear()
            valueCache.clear()
            pendingGraphUpdate = false

            Log.d(TAG, "Receiving started")

        } catch (e: Exception) {
            Log.e(TAG, "startReceiving error", e)
            updateStatus("Start error: ${e.message}")
            Toast.makeText(requireContext(), "Start failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopReceiving() {
        Log.d(TAG, "stopReceiving")
        isReceiving = false
        serialIoManager?.stop()
        serialIoManager = null
        executor?.shutdownNow()
        executor = null
        binding.buttonConnect.text = "Start Receiving"
        updateStatus("Stopped receiving")
    }

    private fun disconnect() {
        Log.d(TAG, "disconnect")
        stopReceiving()
        try {
            serialPort?.close()
        } catch (_: Exception) {}
        serialPort = null
        isConnected = false
        binding.buttonConnect.text = "Connect"
        binding.textDataDisplay.text = "Disconnected"
        updateStatus("Disconnected")
        enableSpinners(true)

        // Clear graph data
        graphDataPoints.clear()
        valueCache.clear()
        pendingGraphUpdate = false

        if (this::lineChart.isInitialized && this::lineDataSet.isInitialized) {
            lineDataSet.values = emptyList()
            lineChart.invalidate()
        }
    }

    private fun updateSerialParameters() {
        try {
            serialPort?.setParameters(
                selectedBaudRate,
                selectedDataBits,
                selectedStopBits,
                selectedParity
            )
            updateStatus("Parameters updated: $selectedBaudRate baud")

            val timestamp = dateFormat.format(Date())
            val currentText = binding.textDataDisplay.text.toString()
            binding.textDataDisplay.text = """
                $currentText
                ──────────────────────────────
                ⚙️ [$timestamp] Parameters updated:
                Baud: $selectedBaudRate, Data: $selectedDataBits,
                Stop: ${getStopBitsText(selectedStopBits)}, Parity: ${getParityText(selectedParity)}
            """.trimIndent()

            Toast.makeText(requireContext(), "Parameters updated", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Parameters updated: $selectedBaudRate baud, $selectedDataBits data bits")

        } catch (e: Exception) {
            Log.e(TAG, "Parameter update error", e)
            updateStatus("Parameter update failed: ${e.message}")
            Toast.makeText(requireContext(), "Parameter update failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToPlot() {
        try {
            val bundle = Bundle().apply {
                putString("patientId", patientId)
                putString("patientName", patientName)
                putString("mode", "realtime")
            }
            findNavController().navigate(R.id.action_serial_to_plot, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to plot", e)
            Toast.makeText(requireContext(), "Error opening plot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Add data to graph - only stores data, doesn't update UI
    private fun addDataToGraph(value: Float) {
        val newEntry = Entry(graphDataPoints.size.toFloat(), value)
        graphDataPoints.add(newEntry)

        // Keep only last MAX_GRAPH_POINTS
        if (graphDataPoints.size > MAX_GRAPH_POINTS) {
            val removeCount = graphDataPoints.size - MAX_GRAPH_POINTS
            for (i in 0 until removeCount) {
                graphDataPoints.removeAt(0)
            }
            // Renumber entries to maintain correct indices
            renumberEntries()
        }

        // Schedule graph update ONLY if graph is visible
        if (showGraph) {
            scheduleGraphUpdate()
        }
    }

    private fun renumberEntries() {
        for (i in graphDataPoints.indices) {
            graphDataPoints[i].x = i.toFloat()
        }
    }

    private fun displayData(data: String) {
        val timestamp = dateFormat.format(Date())
        val formattedData = "$timestamp $data"
        val currentText = binding.textDataDisplay.text.toString()
        val newText = if (currentText == "Disconnected" || currentText == "Ready" ||
            currentText.startsWith("Connected") || currentText.startsWith("Waiting")) {
            formattedData
        } else {
            val lines = currentText.split("\n")
            val limited = if (lines.size >= 200) lines.drop(lines.size - 199) else lines
            limited.joinToString("\n") + "\n$formattedData"
        }
        binding.textDataDisplay.text = newText
        dataCounter++

        // Extract numeric value for graph - OPTIMIZED
        try {
            val parts = data.split(" ")
            if (parts.isNotEmpty()) {
                val value = parts[0].toFloatOrNull()
                if (value != null && value > 0) {
                    // Cache the value
                    valueCache.add(value)

                    // Batch process cached values
                    if (valueCache.size >= 3 || dataCounter % 3 == 0) {
                        for (cachedValue in valueCache) {
                            addDataToGraph(cachedValue)
                        }
                        valueCache.clear()
                    }
                }
            }
        } catch (e: Exception) {
            // Not a numeric value, skip
        }

        // Save to DataManager
        saveSerialData(data)
        saveToFile(data)

        binding.textStats.text = "Packets: $dataCounter"
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveSerialData(data: String) {
        if (!isSavingEnabled) return
        try {
            val timestamp = dateFormat.format(Date())
            val trueData = data.split(" ")[0]
            val record = SerialDataRecord(
                Time = timestamp,
                Capacitance = trueData.toInt(),
            )

            dataManager.saveRecord(record)

            binding.textStats.text = "Packets: $dataCounter | Saved: ${dataManager.getRecordCount()}"

        } catch (e: Exception) {
            Log.e(TAG, "Error saving data", e)
        }
    }

    private fun saveToFile(data: String) {
        try {
            val baseDir = requireContext().getExternalFilesDir(null)
            val serialDataDir = File(baseDir, "SerialData")

            if (!serialDataDir.exists()) {
                serialDataDir.mkdirs()
                Log.d(TAG, "Created SerialData directory: ${serialDataDir.absolutePath}")
            }

            val fileName = "serial_data_${patientId}_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.csv"
            val file = File(serialDataDir, fileName)
            val trueData = data.split(" ")[0]

            FileWriter(file, true).use { writer ->
                val mili_timestamp = System.currentTimeMillis()
                writer.write("$mili_timestamp $trueData\n")
                writer.flush()
            }

            Log.d(TAG, "Data saved to: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving to file", e)
        }
    }

    private fun enableSpinners(enabled: Boolean) {
        binding.spinnerBaudRate.isEnabled = enabled
        binding.spinnerDataBits.isEnabled = enabled
        binding.spinnerStopBits.isEnabled = enabled
        binding.spinnerParity.isEnabled = enabled
        binding.spinnerDevice.isEnabled = enabled
        binding.buttonRefresh.isEnabled = enabled
    }

    private fun getStopBitsText(stopBits: Int): String {
        return when (stopBits) {
            UsbSerialPort.STOPBITS_1 -> "1"
            UsbSerialPort.STOPBITS_1_5 -> "1.5"
            UsbSerialPort.STOPBITS_2 -> "2"
            else -> "Unknown"
        }
    }

    private fun getParityText(parity: Int): String {
        return when (parity) {
            UsbSerialPort.PARITY_NONE -> "None"
            UsbSerialPort.PARITY_ODD -> "Odd"
            UsbSerialPort.PARITY_EVEN -> "Even"
            UsbSerialPort.PARITY_MARK -> "Mark"
            UsbSerialPort.PARITY_SPACE -> "Space"
            else -> "Unknown"
        }
    }

    // ==================== NOW DEFINE THE RECEIVERS ====================

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Permission receiver called")
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    Log.d(TAG, "Permission granted")
                    openDevice(device)
                } else {
                    Log.e(TAG, "Permission denied")
                    updateStatus("Permission denied")
                    Toast.makeText(context, "USB permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val serialListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            handler.post { processIncomingData(data) }
        }
        override fun onRunError(e: Exception?) {
            handler.post {
                updateStatus("Error: ${e?.message}")
                disconnect()
            }
        }
    }

    // ==================== LIFECYCLE METHODS ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Initialize DataManager
        dataManager = DataManager(requireContext())

        // Get patient data from arguments
        arguments?.let {
            patientId = it.getString("patientId", "")
            patientName = it.getString("patientName", "")
            gender = it.getString("gender", "")
            age = it.getString("age", "")
            patientDate = it.getString("date", "")
            patientTime = it.getString("time", "")
            notes = it.getString("notes", "")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Log.d(TAG, "onCreateView started")
        try {
            _binding = FragmentSerialBinding.inflate(inflater, container, false)
            Log.d(TAG, "Binding created successfully")
            return binding.root
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating layout", e)
            return View(requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated started")

        try {
            ACTION_USB_PERMISSION = "${requireContext().packageName}.USB_PERMISSION"
            usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            requireContext().registerReceiver(usbPermissionReceiver, filter)
            Log.d(TAG, "Receiver registered")

            // Setup chart
            setupChart()

            // Display patient info
            displayPatientInfo()

            setupSpinners()
            setupButtons()
            refreshDeviceList()
            Log.d(TAG, "onViewCreated completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
            updateStatus("Error: ${e.message}")
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        disconnect()
        try {
            requireContext().unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
        _binding = null
    }
}