package com.deepgaze.glasses

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A class to detect and correct noisy step periods in time series data.
 * Modified to work with DoubleArrays instead of DataFrames.
 */
class StepNoiseCorrector(
    private val window: Int = 30,
    private val requiredStable: Int = 30,
    private val maxIterations: Int = 20,
    private val noiseThreshold: Double = 3.0,
    private val lookAhead: Int = 500,
    private val filterNoise: Boolean = false,
    private val cutoffFreq: Double = 0.1,
    private val filterOrder: Int = 4,
    private val medianFilter: Boolean = false,
    private val medianKernel: Int = 5,
    private val detrend: Boolean = false,
    private val detrendMethod: String = "linear"
) {
    data class StepPeriod(
        val start: Int,
        val end: Int,
        val preMean: Double,
        val postMean: Double,
        val stepAmplitude: Double,
        val duration: Int
    )

    data class ProcessedResult(
        val originalData: DoubleArray,
        val timestamps: DoubleArray,
        val filteredData: DoubleArray?,
        val correctedData: DoubleArray,
        val detrendedData: DoubleArray?,
        val trend: DoubleArray?,
        val finalData: DoubleArray,
        val stepPeriods: List<StepPeriod>,
        val stats: Statistics
    )

    data class Statistics(
        val numSteps: Int,
        val originalMean: Double,
        val originalStd: Double,
        val correctedMean: Double,
        val correctedStd: Double,
        val improvement: Double,
        val filteredMean: Double? = null,
        val filteredStd: Double? = null,
        val detrendedMean: Double? = null,
        val detrendedStd: Double? = null
    )

    private var stepPeriods = mutableListOf<StepPeriod>()
    private var originalData: DoubleArray? = null
    private var correctedData: DoubleArray? = null
    private var filteredData: DoubleArray? = null
    private var detrendedData: DoubleArray? = null
    private var trend: DoubleArray? = null
    private var timestamps: DoubleArray? = null

    /**
     * Proper Butterworth low-pass filter implementation.
     */
    private fun butterLowpassFilter(data: DoubleArray, cutoff: Double, order: Int = 4): DoubleArray {
        val nyquist = 0.5
        val normalizedCutoff = min(cutoff / nyquist, 0.99)

        val analogCoeffs = designAnalogButterworth(order)
        val (b, a) = bilinearTransform(analogCoeffs, normalizedCutoff)

        return filtfilt(data, b, a)
    }

    private fun designAnalogButterworth(order: Int): Pair<DoubleArray, DoubleArray> {
        val poles = Array(order) { k ->
            val angle = PI / 2 + (2 * k + 1) * PI / (2 * order)
            org.apache.commons.math3.complex.Complex(cos(angle), sin(angle))
        }

        val denomRe = DoubleArray(order + 1)
        denomRe[0] = 1.0

        for (pole in poles) {
            val newDenom = DoubleArray(order + 1)
            for (i in 0..order) {
                if (denomRe[i] != 0.0) {
                    if (i + 1 <= order) {
                        newDenom[i + 1] += denomRe[i]
                    }
                    newDenom[i] += denomRe[i] * (-pole.real)
                }
            }
            System.arraycopy(newDenom, 0, denomRe, 0, order + 1)
        }

        val num = DoubleArray(order + 1)
        num[0] = 1.0

        return Pair(num, denomRe)
    }

    private fun bilinearTransform(
        analogCoeffs: Pair<DoubleArray, DoubleArray>,
        cutoff: Double
    ): Pair<DoubleArray, DoubleArray> {
        val (b, a) = analogCoeffs
        val order = a.size - 1

        val T = 1.0
        val wc = 2.0 / T * tan(PI * cutoff / 2.0)

        val bDigital = DoubleArray(order + 1)
        val aDigital = DoubleArray(order + 1)

        if (order == 1) {
            val c = wc / (wc + 2.0 / T)
            bDigital[0] = c
            bDigital[1] = c
            aDigital[0] = 1.0
            aDigital[1] = -(1.0 - 2.0 / T / (wc + 2.0 / T))
        } else {
            var bTotal = doubleArrayOf(1.0)
            var aTotal = doubleArrayOf(1.0)

            val sections = order / 2
            for (k in 0 until sections) {
                val angle = PI / 2 + (2 * k + 1) * PI / (2 * order)
                val poleReal = wc * cos(angle)
                val poleImag = wc * sin(angle)

                val wc2 = wc * wc
                val denominator = wc2 + 2.0 * wc / T + 4.0 / (T * T)

                val bSection = DoubleArray(3)
                val aSection = DoubleArray(3)

                val k0 = wc2 / denominator
                val k1 = 2.0 * k0
                bSection[0] = k0
                bSection[1] = k1
                bSection[2] = k0

                val k2 = (2.0 * wc2 - 8.0 / (T * T)) / denominator
                val k3 = (wc2 - 2.0 * wc / T + 4.0 / (T * T)) / denominator
                aSection[0] = 1.0
                aSection[1] = k2
                aSection[2] = k3

                bTotal = convolve(bTotal, bSection)
                aTotal = convolve(aTotal, aSection)
            }

            for (i in 0..min(order, bTotal.size - 1)) {
                bDigital[i] = bTotal[i]
            }
            for (i in 0..min(order, aTotal.size - 1)) {
                aDigital[i] = aTotal[i]
            }
        }

        val a0 = aDigital[0]
        for (i in bDigital.indices) {
            bDigital[i] = bDigital[i] / a0
        }
        for (i in aDigital.indices) {
            aDigital[i] = aDigital[i] / a0
        }

        return Pair(bDigital, aDigital)
    }

    private fun convolve(x: DoubleArray, y: DoubleArray): DoubleArray {
        val result = DoubleArray(x.size + y.size - 1)
        for (i in x.indices) {
            for (j in y.indices) {
                result[i + j] += x[i] * y[j]
            }
        }
        return result
    }

    private fun filtfilt(data: DoubleArray, b: DoubleArray, a: DoubleArray): DoubleArray {
        val forward = filter(data, b, a)
        val reversed = forward.reversedArray()
        val backward = filter(reversed, b, a)
        return backward.reversedArray()
    }

    private fun filter(data: DoubleArray, b: DoubleArray, a: DoubleArray): DoubleArray {
        val result = DoubleArray(data.size)
        val order = b.size - 1

        for (i in data.indices) {
            var sum = 0.0
            for (k in 0..min(i, order)) {
                sum += b[k] * data[i - k]
            }
            for (k in 1..min(i, order)) {
                sum -= a[k] * result[i - k]
            }
            result[i] = sum / a[0]
        }

        return result
    }

    private fun applyMedianFilter(data: DoubleArray, kernelSize: Int = 5): DoubleArray {
        val result = DoubleArray(data.size)
        val halfKernel = kernelSize / 2

        for (i in data.indices) {
            val window = mutableListOf<Double>()
            for (j in max(0, i - halfKernel)..min(data.size - 1, i + halfKernel)) {
                window.add(data[j])
            }
            window.sort()
            result[i] = window[window.size / 2]
        }
        return result
    }

    private fun detrendData(data: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = data.size.toDouble()
        val x = DoubleArray(data.size) { it.toDouble() }

        return when (detrendMethod) {
            "linear" -> {
                val meanX = x.average()
                val meanY = data.average()

                var covXY = 0.0
                for (i in x.indices) {
                    covXY += (x[i] - meanX) * (data[i] - meanY)
                }

                var varX = 0.0
                for (i in x.indices) {
                    varX += (x[i] - meanX) * (x[i] - meanX)
                }

                val slope = covXY / varX
                val intercept = meanY - slope * meanX
                val trend = DoubleArray(data.size) { i -> slope * i + intercept }
                val detrended = DoubleArray(data.size) { i -> data[i] - trend[i] }

                println("  Linear detrending applied (slope=$slope)")
                Pair(detrended, trend)
            }
            "polynomial" -> {
                var sumX = 0.0
                var sumX2 = 0.0
                var sumX3 = 0.0
                var sumX4 = 0.0
                var sumY = 0.0
                var sumXY = 0.0
                var sumX2Y = 0.0

                for (i in x.indices) {
                    val xi = x[i]
                    val xi2 = xi * xi
                    val xi3 = xi2 * xi
                    val xi4 = xi2 * xi2
                    val yi = data[i]

                    sumX += xi
                    sumX2 += xi2
                    sumX3 += xi3
                    sumX4 += xi4
                    sumY += yi
                    sumXY += xi * yi
                    sumX2Y += xi2 * yi
                }

                val a = arrayOf(
                    doubleArrayOf(n, sumX, sumX2),
                    doubleArrayOf(sumX, sumX2, sumX3),
                    doubleArrayOf(sumX2, sumX3, sumX4)
                )
                val b = doubleArrayOf(sumY, sumXY, sumX2Y)
                val coeffs = solveLinearSystem(a, b)

                val trend = DoubleArray(data.size) { i ->
                    val xi = i.toDouble()
                    coeffs[0] + coeffs[1] * xi + coeffs[2] * xi * xi
                }
                val detrended = DoubleArray(data.size) { i -> data[i] - trend[i] }

                println("  Polynomial detrending applied (degree 2)")
                Pair(detrended, trend)
            }
            "smoothing" -> {
                val windowSize = 100
                val trend = DoubleArray(data.size)
                for (i in data.indices) {
                    var sum = 0.0
                    var count = 0
                    for (j in max(0, i - windowSize / 2)..min(data.size - 1, i + windowSize / 2)) {
                        sum += data[j]
                        count++
                    }
                    trend[i] = sum / count
                }
                val detrended = DoubleArray(data.size) { i -> data[i] - trend[i] }

                println("  Smoothing detrending applied (window=100)")
                Pair(detrended, trend)
            }
            else -> Pair(data, DoubleArray(data.size) { 0.0 })
        }
    }

    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 3
        val augmented = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }

        for (i in 0 until n) {
            var maxRow = i
            for (k in i + 1 until n) {
                if (abs(augmented[k][i]) > abs(augmented[maxRow][i])) {
                    maxRow = k
                }
            }
            augmented[i] = augmented[maxRow].also { augmented[maxRow] = augmented[i] }

            for (k in i + 1 until n) {
                val factor = augmented[k][i] / augmented[i][i]
                for (j in i until n + 1) {
                    augmented[k][j] -= factor * augmented[i][j]
                }
            }
        }

        val result = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until n) {
                sum += augmented[i][j] * result[j]
            }
            result[i] = (augmented[i][n] - sum) / augmented[i][i]
        }
        return result
    }

    private fun calculateRollingStd(data: DoubleArray, window: Int): DoubleArray {
        val result = DoubleArray(data.size)
        for (i in data.indices) {
            val start = max(0, i - window / 2)
            val end = min(data.size, i + window / 2 + 1)
            val slice = data.slice(start until end)
            result[i] = if (slice.size > 1) {
                slice.std()
            } else 0.0
        }
        return result
    }

    private fun findPeaks(data: List<Double>, prominence: Double): List<Int> {
        val peaks = mutableListOf<Int>()
        for (i in 1 until data.size - 1) {
            if (data[i] > data[i - 1] && data[i] > data[i + 1] && data[i] > prominence) {
                peaks.add(i)
            }
        }
        return peaks
    }

    private fun detectNoisyStep(data: DoubleArray, startIdx: Int): Pair<Int, Boolean> {
        val n = data.size

        val preStart = max(0, startIdx - 100)
        val preRegion = data.slice(preStart until startIdx)
        val preStd = if (preRegion.isNotEmpty()) preRegion.std()
        else data.slice(0 until 100).std()

        val rollingStd = calculateRollingStd(data, window)

        var endIdx = startIdx
        var stableCount = 0
        val maxLookAhead = min(n, startIdx + lookAhead)

        for (i in startIdx until maxLookAhead) {
            if (rollingStd[i] < preStd * 1.5) {
                stableCount++
                if (stableCount >= requiredStable) {
                    endIdx = i - requiredStable + 1
                    break
                }
            } else {
                stableCount = 0
                endIdx = i
            }
        }

        if (endIdx == startIdx) {
            endIdx = min(n - 1, startIdx + 200)
        }

        val stepRegion = data.slice(startIdx until endIdx)
        if (stepRegion.size > window) {
            val stepStd = stepRegion.std()
            val stepRange = stepRegion.maxOrNull()!! - stepRegion.minOrNull()!!
            val preRange = if (preRegion.isNotEmpty()) preRegion.maxOrNull()!! - preRegion.minOrNull()!! else 0.0

            val peaks = findPeaks(stepRegion, stepStd / 3)

            val isNoisy = stepStd > preStd * 2.5 &&
                    stepRange > preRange * 2 &&
                    peaks.size > 2

            if (isNoisy) {
                return Pair(endIdx, true)
            }
        }

        return Pair(startIdx, false)
    }

    private fun findNextStep(data: DoubleArray): Pair<Int?, Int?> {
        val n = data.size
        val rollingStd = calculateRollingStd(data, window)

        val globalNoise = rollingStd.median()
        val stdThreshold = globalNoise * noiseThreshold

        for (i in 0 until n - window) {
            if (rollingStd[i] > stdThreshold) {
                val (endIdx, isNoisy) = detectNoisyStep(data, i)
                if (isNoisy && endIdx - i > window) {
                    return Pair(i, endIdx)
                }
            }
        }
        return Pair(null, null)
    }

    private fun correctStepNoise(data: DoubleArray): DoubleArray {
        val dataCorrected = data.copyOf()
        stepPeriods.clear()

        var iteration = 0
        while (iteration < maxIterations) {
            val (startIdx, endIdx) = findNextStep(dataCorrected)

            if (startIdx == null || endIdx == null) {
                break
            }

            val preStart = max(0, startIdx - 50)
            val preMean = dataCorrected.slice(preStart until startIdx).average()

            val postEnd = min(dataCorrected.size, endIdx + 50)
            val postMean = if (endIdx < dataCorrected.size - 1) {
                dataCorrected.slice(endIdx until postEnd).average()
            } else {
                dataCorrected.last()
            }
            val stepAmplitude = postMean - preMean

            for (i in startIdx until endIdx) {
                dataCorrected[i] = preMean
            }
            for (i in endIdx until dataCorrected.size) {
                dataCorrected[i] = dataCorrected[i] - stepAmplitude
            }

            stepPeriods.add(
                StepPeriod(
                    start = startIdx,
                    end = endIdx,
                    preMean = preMean,
                    postMean = postMean,
                    stepAmplitude = stepAmplitude,
                    duration = endIdx - startIdx
                )
            )

            iteration++
        }

        return dataCorrected
    }

    /**
     * Process the data using DoubleArray inputs.
     */
    fun process(
        data: DoubleArray,
        timestamps: DoubleArray? = null
    ): ProcessedResult {
        this.originalData = data.copyOf()
        this.timestamps = timestamps?.copyOf() ?: DoubleArray(data.size) { it.toDouble() }

        // Step 1: Apply filters if requested
        var dataToProcess = data.copyOf()

        // Median filter
        if (medianFilter) {
            println("Applying median filter (kernel=$medianKernel)...")
            dataToProcess = applyMedianFilter(dataToProcess, medianKernel)
        }

        // Low-pass filter
        if (filterNoise) {
            println("Applying Butterworth low-pass filter (cutoff=$cutoffFreq, order=$filterOrder)...")
            dataToProcess = butterLowpassFilter(dataToProcess, cutoffFreq, filterOrder)
        }

        filteredData = if (medianFilter || filterNoise) dataToProcess.copyOf() else null

        // Step 2: Correct step noise
        println("Correcting step noise...")
        correctedData = correctStepNoise(dataToProcess)

        // Step 3: Detrend the corrected data if requested
        if (detrend) {
            println("Detrending corrected data using $detrendMethod method...")
            val (detrended, trendData) = detrendData(correctedData!!)
            detrendedData = detrended
            trend = trendData
        } else {
            // If detrend is false, use corrected data as detrended data
            detrendedData = correctedData?.copyOf()
            trend = null
        }

        // Step 4: Final data - always use detrendedData if available, otherwise correctedData
        val finalData = if (detrendedData != null) {
            detrendedData!!
        } else if (correctedData != null) {
            correctedData!!
        } else {
            data.copyOf()
        }

        // Calculate statistics
        val stats = Statistics(
            numSteps = stepPeriods.size,
            originalMean = originalData!!.average(),
            originalStd = originalData!!.std(),
            correctedMean = correctedData!!.average(),
            correctedStd = correctedData!!.std(),
            improvement = originalData!!.average() - correctedData!!.average(),
            filteredMean = filteredData?.average(),
            filteredStd = filteredData?.let { it.std() },
            detrendedMean = detrendedData?.average(),
            detrendedStd = detrendedData?.let { it.std() }
        )

        // Print summary
        println()
        println("✅ Done! Processed ${data.size} points")
        println("   Steps detected: ${stepPeriods.size}")
        println("   Original mean: ${originalData!!.average()}")
        println("   Corrected mean: ${correctedData!!.average()}")
        if (detrendedData != null) {
            println("   Detrended mean: ${detrendedData!!.average()}")
            println("   Detrended std: ${detrendedData!!.std()}")
        }

        return ProcessedResult(
            originalData = originalData!!,
            timestamps = this.timestamps!!,
            filteredData = filteredData,
            correctedData = correctedData!!,
            detrendedData = detrendedData,
            trend = trend,
            finalData = finalData,
            stepPeriods = stepPeriods.toList(),
            stats = stats
        )
    }

    private fun DoubleArray.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }

    private fun List<Double>.std(): Double {
        if (size <= 1) return 0.0
        val mean = average()
        val variance = map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun DoubleArray.std(): Double {
        if (isEmpty()) return 0.0
        val mean = average()
        val variance = map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun findPeaks(data: DoubleArray, threshold: Double): List<Int> {
        if (data.size < 3) return emptyList()

        val peaks = mutableListOf<Int>()
        for (i in 1 until data.size - 1) {
            if (data[i] > data[i - 1] && data[i] > data[i + 1] && data[i] > threshold) {
                if (peaks.isEmpty() || i - peaks.last() >= 5) {
                    peaks.add(i)
                }
            }
        }
        return peaks
    }
}

/**
 * Main function demonstrating usage with DoubleArrays.
 */
fun main() {
    println("=".repeat(60))
    println("STEP NOISE CORRECTOR WITH DETRENDING")
    println("=".repeat(60))

    try {
        // Example: Read CSV and convert to DoubleArrays
        // Replace this with your actual data loading
        val sampleData = DoubleArray(1000) { i ->
            100.0 * (1 + 0.5 * sin(i / 20.0) + 0.2 * sin(i / 5.0)) +
                    (kotlin.random.Random.nextDouble() - 0.5) * 10.0
        }
        // Add some artificial step noise
        for (i in 200..250) {
            sampleData[i] += 50.0
        }
        for (i in 400..420) {
            sampleData[i] -= 30.0
        }
        for (i in 600..660) {
            sampleData[i] += 40.0
        }

        val timestamps = DoubleArray(sampleData.size) { it.toDouble() }

        // Process with various configurations
        val corrector = StepNoiseCorrector(
            detrend = true,
            detrendMethod = "smoothing",
            medianFilter = true,
            medianKernel = 5,
            filterNoise = true,
            cutoffFreq = 0.1
        )

        val result = corrector.process(sampleData, timestamps)

        println("\n" + "=".repeat(60))
        println("Processing Complete!")
        println("=".repeat(60))
        println("Original data size: ${result.originalData.size}")
        println("Final data size: ${result.finalData.size}")
        println("Steps detected: ${result.stepPeriods.size}")
        println("Original mean: ${result.stats.originalMean}")
        println("Corrected mean: ${result.stats.correctedMean}")
        if (result.stats.detrendedMean != null) {
            println("Detrended mean: ${result.stats.detrendedMean}")
        }

    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}