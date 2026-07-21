package com.deepgaze.glasses

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A step noise corrector with highly selective detection to avoid false positives.
 * This version PRESERVES THE ORIGINAL DATA ORDER.
 */
class StepNoiseCorrector(
    private val window: Int = 30,
    private val requiredStable: Int = 50,  // Increased from 30 to 50 (needs more stable points)
    private val maxIterations: Int = 10,
    private val noiseThreshold: Double = 6.0,  // Increased from 4.0 to 6.0 (less sensitive)
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

    fun process(
        data: DoubleArray,
        timestamps: DoubleArray? = null
    ): ProcessedResult {
        this.originalData = data.copyOf()
        this.timestamps = timestamps?.copyOf() ?: DoubleArray(data.size) { it.toDouble() }

        var dataToProcess = data.copyOf()

        dataToProcess = correctStepNoise(dataToProcess)

        if (medianFilter) {
            println("Applying median filter (kernel=$medianKernel)...")
            dataToProcess = applyMedianFilter(dataToProcess, medianKernel)
        }

        if (filterNoise) {
            println("Applying Butterworth low-pass filter (cutoff=$cutoffFreq, order=$filterOrder)...")
            dataToProcess = butterLowpassFilter(dataToProcess, cutoffFreq, filterOrder)
        }

        correctedData = dataToProcess

        filteredData = if (medianFilter || filterNoise) dataToProcess.copyOf() else null

        println("Correcting step noise...")

        if (detrend) {
            println("Detrending corrected data using $detrendMethod method...")
            val (detrended, trendData) = detrendData(correctedData!!)
            detrendedData = detrended
            trend = trendData
        } else {
            detrendedData = null
            trend = null
        }

        val finalData = if (detrendedData != null) {
            detrendedData!!
        } else {
            correctedData!!
        }

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

    /**
     * Correct step noise with highly selective detection.
     */
    private fun correctStepNoise(data: DoubleArray): DoubleArray {
        val result = data.copyOf()
        stepPeriods.clear()

        var iteration = 0
        var foundStep = true

        while (foundStep && iteration < maxIterations) {
            foundStep = false

            val stepInfo = findMostProminentStep(result)

            if (stepInfo != null) {
                val (startIdx, endIdx, preMean, postMean) = stepInfo
                val stepAmplitude = postMean - preMean

                // ✅ STRICT REQUIREMENTS for step correction
                // 1. Amplitude must be large enough (at least 2.0)
                // 2. Duration must be long enough (at least 20 samples)
                // 3. Step must be at least 3x noisier than surrounding area
                val stepRegion = result.slice(startIdx until endIdx)
                val preStart = max(0, startIdx - requiredStable)
                val preRegion = result.slice(preStart until startIdx)
                val preStd = if (preRegion.isNotEmpty()) preRegion.std() else 1.0
                val stepStd = if (stepRegion.isNotEmpty()) stepRegion.std() else 1.0

                val isSignificant = abs(stepAmplitude) > 3.0 &&  // Large enough amplitude
                        (endIdx - startIdx) > 20 &&   // Long enough duration
                        stepStd > preStd * 3.0        // Noisier than surrounding

                if (isSignificant) {
                    // Apply correction
                    val correction = -stepAmplitude

                    // Fill the step region with pre-mean
                    for (i in startIdx until endIdx) {
                        result[i] = preMean
                    }

                    // Apply correction to post-step data
                    for (i in endIdx until result.size) {
                        result[i] = result[i] + correction
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

                    foundStep = true
                    iteration++
                    println("   Corrected step ${iteration} at indices $startIdx-$endIdx (amplitude: ${String.format("%.2f", stepAmplitude)})")
                } else {
                    println("   Skipping step at $startIdx-$endIdx (not significant enough)")
                    // Mark as processed to avoid rechecking
                    foundStep = false
                }
            }
        }

        return result
    }

    /**
     * Find the most prominent step with strict criteria.
     */
    private fun findMostProminentStep(data: DoubleArray): StepInfo? {
        if (data.size < requiredStable) return null  // Need enough data

        // Calculate rolling standard deviation
        val rollingStd = calculateRollingStd(data, window)
        val globalMedian = rollingStd.median()
        val threshold = globalMedian * noiseThreshold

        var bestStart = -1
        var bestEnd = -1
        var bestPreMean = 0.0
        var bestPostMean = 0.0
        var bestScore = 0.0

        var i = window / 2
        while (i < data.size - window / 2) {
            if (rollingStd[i] > threshold) {
                val start = i

                // Find where the noise ends - require more stable points
                var end = start
                var stableCount = 0
                for (j in start until min(data.size, start + lookAhead)) {
                    if (rollingStd[j] < threshold * 0.3) {  // More strict: 0.3 instead of 0.5
                        stableCount++
                        if (stableCount >= requiredStable) {
                            end = j - requiredStable + 1
                            break
                        }
                    } else {
                        stableCount = 0
                        end = j
                    }
                }

                // Require longer step region
                if (end > start + 30) {
                    // Calculate pre-step mean
                    val preStart = max(0, start - 30)
                    val preWindow = data.slice(preStart until start)
                    val preMean = if (preWindow.isNotEmpty()) preWindow.average() else data[start]

                    // Calculate post-step mean
                    val postEnd = min(data.size, end + 30)
                    val postWindow = data.slice(end until postEnd)
                    val postMean = if (postWindow.isNotEmpty()) postWindow.average() else data.last()

                    val stepAmplitude = abs(postMean - preMean)
                    val stepDuration = end - start

                    // Calculate noise levels
                    val stepWindow = data.slice(start until end)
                    val stepStd = if (stepWindow.isNotEmpty()) stepWindow.std() else 0.0
                    val preStd = if (preWindow.isNotEmpty()) preWindow.std() else 0.0

                    // ✅ STRICT SCORING CRITERIA
                    val isValidStep = stepAmplitude > 3.0 &&           // Large amplitude
                            stepDuration > 10 &&              // Long duration
                            stepStd > preStd * 3.0 &&        // Much noisier
                            stepAmplitude > preStd * 2.0    // Amplitude > 2x noise

                    if (isValidStep) {
                        // Score based on amplitude and duration
                        val score = stepAmplitude * stepDuration / (stepStd + 1.0)

                        if (score > bestScore) {
                            bestScore = score
                            bestStart = start
                            bestEnd = end
                            bestPreMean = preMean
                            bestPostMean = postMean
                        }
                    }
                }

                i = end + 1
            } else {
                i++
            }
        }

        return if (bestStart >= 0 && bestEnd > bestStart) {
            println("   Found valid step at $bestStart-$bestEnd (amplitude: ${String.format("%.2f", abs(bestPostMean - bestPreMean))})")
            StepInfo(bestStart, bestEnd, bestPreMean, bestPostMean)
        } else {
            null
        }
    }

    data class StepInfo(
        val start: Int,
        val end: Int,
        val preMean: Double,
        val postMean: Double
    )

    // ==================== HELPER FUNCTIONS ====================

    private fun butterLowpassFilter(data: DoubleArray, cutoff: Double, order: Int = 4): DoubleArray {
        val result = data.copyOf()
        val alpha = 1.0 / (1.0 + (1.0 / (2.0 * Math.PI * cutoff)))

        var previous = data[0]
        for (i in data.indices) {
            val filtered = alpha * data[i] + (1 - alpha) * previous
            result[i] = filtered
            previous = filtered
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
}