package com.mardous.booming.separation.model.contract

import kotlin.math.log10
import kotlin.math.sqrt

data class SourceSeparationMultiTensorFloatMetrics(
    val elementCount: Int,
    val finite: Boolean,
    val maxAbsoluteError: Double,
    val rootMeanSquareError: Double,
    val signalToNoiseDb: Double,
    val cosineSimilarity: Double,
    val referenceRms: Double,
)

data class SourceSeparationMultiTensorStemMetrics(
    val stemIndex: Int,
    val metrics: SourceSeparationMultiTensorFloatMetrics,
    val lowEnergy: Boolean,
    val passes: Boolean,
)

data class SourceSeparationMultiTensorPcm16Metrics(
    val frameCountMatches: Boolean,
    val expectedBytes: Int,
    val actualBytes: Int,
    val maximumSampleDelta: Int,
    val maximumAbsoluteError: Double,
    val passes: Boolean,
)

/** Frozen Phase 6 policy for multi-stem model qualification. */
data class SourceSeparationMultiTensorQualityThresholds(
    val strictHostMaximumAbsoluteError: Double = 1e-6,
    val strictHostMaximumRmsError: Double = 1e-7,
    val tensorMinimumReferenceRms: Double = 1e-3,
    val tensorMinimumSnrDb: Double = 60.0,
    val tensorMinimumCosineSimilarity: Double = 0.9999,
    val tensorMaximumAbsoluteError: Double = 2.5e-4,
    val tensorLowEnergyMaximumRmsError: Double = 2e-5,
    val pcm16MaximumSampleDelta: Int = 1,
    val pcm16MaximumAbsoluteError: Double = 1.0 / 32768.0,
) {
    init {
        require(strictHostMaximumAbsoluteError >= 0.0)
        require(strictHostMaximumRmsError >= 0.0)
        require(tensorMinimumReferenceRms > 0.0)
        require(tensorMinimumSnrDb > 0.0)
        require(tensorMinimumCosineSimilarity in 0.0..1.0)
        require(tensorMaximumAbsoluteError >= 0.0)
        require(tensorLowEnergyMaximumRmsError >= 0.0)
        require(pcm16MaximumSampleDelta >= 0)
        require(pcm16MaximumAbsoluteError > 0.0)
    }

    companion object {
        val FrozenV1 = SourceSeparationMultiTensorQualityThresholds(
            tensorMinimumReferenceRms = 1e-4,
        )
        val FrozenV2 = SourceSeparationMultiTensorQualityThresholds(
            tensorMinimumReferenceRms = 1e-3,
        )
    }
}

object SourceSeparationMultiTensorQualityGate {
    fun compareFloat(
        expected: FloatArray,
        actual: FloatArray,
    ): SourceSeparationMultiTensorFloatMetrics {
        require(expected.size == actual.size) {
            "Float fixture sizes differ: ${expected.size} versus ${actual.size}."
        }
        var finite = true
        var maxAbsoluteError = 0.0
        var errorSquareSum = 0.0
        var signalSquareSum = 0.0
        var candidateSquareSum = 0.0
        var dotProduct = 0.0
        expected.indices.forEach { index ->
            val reference = expected[index].toDouble()
            val candidate = actual[index].toDouble()
            finite = finite && reference.isFinite() && candidate.isFinite()
            val error = candidate - reference
            maxAbsoluteError = maxOf(maxAbsoluteError, kotlin.math.abs(error))
            errorSquareSum += error * error
            signalSquareSum += reference * reference
            candidateSquareSum += candidate * candidate
            dotProduct += reference * candidate
        }
        val count = expected.size.toDouble()
        val errorRms = sqrt(errorSquareSum / count)
        val referenceRms = sqrt(signalSquareSum / count)
        return SourceSeparationMultiTensorFloatMetrics(
            elementCount = expected.size,
            finite = finite,
            maxAbsoluteError = maxAbsoluteError,
            rootMeanSquareError = errorRms,
            signalToNoiseDb = 20.0 * log10(
                maxOf(referenceRms, MIN_RMS) / maxOf(errorRms, MIN_RMS),
            ),
            cosineSimilarity = dotProduct /
                maxOf(sqrt(signalSquareSum * candidateSquareSum), MIN_RMS),
            referenceRms = referenceRms,
        )
    }

    fun comparePerStem(
        expected: FloatArray,
        actual: FloatArray,
        stemCount: Int,
        thresholds: SourceSeparationMultiTensorQualityThresholds =
            SourceSeparationMultiTensorQualityThresholds.FrozenV2,
    ): List<SourceSeparationMultiTensorStemMetrics> {
        require(stemCount > 0)
        require(expected.size % stemCount == 0 && actual.size == expected.size) {
            "Per-stem fixture sizes are not divisible by stem count."
        }
        val elementsPerStem = expected.size / stemCount
        return List(stemCount) { stem ->
            val start = stem * elementsPerStem
            val end = start + elementsPerStem
            val metrics = compareFloat(
                expected = expected.copyOfRange(start, end),
                actual = actual.copyOfRange(start, end),
            )
            val lowEnergy = metrics.referenceRms < thresholds.tensorMinimumReferenceRms
            val passes = metrics.finite &&
                metrics.maxAbsoluteError <= thresholds.tensorMaximumAbsoluteError &&
                if (lowEnergy) {
                    metrics.rootMeanSquareError <= thresholds.tensorLowEnergyMaximumRmsError
                } else {
                    metrics.signalToNoiseDb >= thresholds.tensorMinimumSnrDb &&
                        metrics.cosineSimilarity >= thresholds.tensorMinimumCosineSimilarity
                }
            SourceSeparationMultiTensorStemMetrics(stem, metrics, lowEnergy, passes)
        }
    }

    fun passesStrictHost(
        metrics: SourceSeparationMultiTensorFloatMetrics,
        thresholds: SourceSeparationMultiTensorQualityThresholds =
            SourceSeparationMultiTensorQualityThresholds.FrozenV2,
    ): Boolean = metrics.finite &&
        metrics.maxAbsoluteError <= thresholds.strictHostMaximumAbsoluteError &&
        metrics.rootMeanSquareError <= thresholds.strictHostMaximumRmsError

    fun comparePcm16(
        expected: ByteArray,
        actual: ByteArray,
        expectedFrameCount: Int,
        channelCount: Int,
        thresholds: SourceSeparationMultiTensorQualityThresholds =
            SourceSeparationMultiTensorQualityThresholds.FrozenV2,
    ): SourceSeparationMultiTensorPcm16Metrics {
        require(channelCount > 0)
        val frameBytes = Math.multiplyExact(channelCount, Short.SIZE_BYTES)
        val expectedBytes = Math.multiplyExact(expectedFrameCount, frameBytes)
        val comparableBytes = minOf(expected.size, actual.size)
        var maximumSampleDelta = 0
        var maximumAbsoluteError = 0.0
        var offset = 0
        while (offset + 1 < comparableBytes) {
            val expectedSample = readLittleEndianShort(expected, offset)
            val actualSample = readLittleEndianShort(actual, offset)
            val delta = kotlin.math.abs(actualSample - expectedSample)
            maximumSampleDelta = maxOf(maximumSampleDelta, delta)
            maximumAbsoluteError = maxOf(
                maximumAbsoluteError,
                delta.toDouble() / 32768.0,
            )
            offset += Short.SIZE_BYTES
        }
        val frameCountMatches = expected.size == expectedBytes && actual.size == expectedBytes
        val passes = frameCountMatches &&
            maximumSampleDelta <= thresholds.pcm16MaximumSampleDelta &&
            maximumAbsoluteError <= thresholds.pcm16MaximumAbsoluteError
        return SourceSeparationMultiTensorPcm16Metrics(
            frameCountMatches = frameCountMatches,
            expectedBytes = expectedBytes,
            actualBytes = actual.size,
            maximumSampleDelta = maximumSampleDelta,
            maximumAbsoluteError = maximumAbsoluteError,
            passes = passes,
        )
    }

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private const val MIN_RMS = 1e-30
}
