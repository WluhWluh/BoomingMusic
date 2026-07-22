package com.mardous.booming.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.mardous.booming.separation.cache.SourceSeparationCacheDirectories
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.audio.Pcm16StereoFlacStereoMode
import com.mardous.booming.separation.audio.WavFileWriter
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class DebugFlacPromotionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outputTag = intent.getStringExtra(EXTRA_OUTPUT_TAG)
            ?.sanitizePathSegment()
            ?.ifBlank { null }
            ?: "flac-promotion-${System.currentTimeMillis()}"
        val runTag = "${outputTag}-${System.currentTimeMillis()}"
        val inputDirPath = intent.getStringExtra(EXTRA_INPUT_DIR)
        val maxFiles = intent.getIntExtra(EXTRA_MAX_FILES, DEFAULT_MAX_FILES)
            .coerceAtLeast(0)
        val includeSynthetic = intent.getBooleanExtra(EXTRA_INCLUDE_SYNTHETIC, true)

        thread(name = "DebugFlacPromotion") {
            try {
                runFlacPromotionTest(
                    outputTag = runTag,
                    inputDirPath = inputDirPath,
                    maxFiles = maxFiles,
                    includeSynthetic = includeSynthetic,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "FLAC promotion debug test failed.", error)
            } finally {
                finish()
            }
        }
    }

    private fun runFlacPromotionTest(
        outputTag: String,
        inputDirPath: String?,
        maxFiles: Int,
        includeSynthetic: Boolean,
    ) {
        val reportRoot = File(
            File(SourceSeparationCacheDirectories.debug(this), "flac-promotion"),
            outputTag,
        )
        val outputDir = File(reportRoot, "outputs")
        outputDir.mkdirs()

        val summaryFile = File(reportRoot, "flac-promotion-summary.csv")
        val logFile = File(reportRoot, "flac-promotion-log.txt")
        summaryFile.writeText(FlacPromotionRow.csvHeader() + "\n", Charsets.UTF_8)
        logFile.writeText(
            "FLAC promotion debug test\n" +
                    "Output: ${reportRoot.absolutePath}\n" +
                    "Input override: ${inputDirPath.orEmpty()}\n" +
                    "Max files: $maxFiles\n" +
                    "Include synthetic: $includeSynthetic\n\n",
            Charsets.UTF_8,
        )

        val cases = buildList {
            if (includeSynthetic) {
                add(createSyntheticCase(outputDir))
            }
            addAll(findCompletedStemCases(inputDirPath, maxFiles))
        }

        logFile.appendText("Cases: ${cases.size}\n\n", Charsets.UTF_8)
        Log.i(TAG, "Starting FLAC promotion debug test with ${cases.size} case(s).")

        cases.forEachIndexed { index, case ->
            val startedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "(${index + 1}/${cases.size}) Testing ${case.label}")
            logFile.appendText("START ${index + 1}/${cases.size}: ${case.label}\n", Charsets.UTF_8)
            val row = try {
                runCase(
                    case = case,
                    outputDir = outputDir,
                    elapsedMsProvider = { SystemClock.elapsedRealtime() - startedAtMs },
                )
            } catch (error: Throwable) {
                Log.e(TAG, "(${index + 1}/${cases.size}) Failed ${case.label}", error)
                FlacPromotionRow.failure(
                    case = case,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    error = error,
                )
            }
            summaryFile.appendText(row.toCsvLine() + "\n", Charsets.UTF_8)
            logFile.appendText(
                "END ${index + 1}/${cases.size}: ${case.label} ${row.status} " +
                        "${row.elapsedMs}ms ${row.errorMessage.orEmpty()}\n",
                Charsets.UTF_8,
            )
        }

        logFile.appendText("\nFinished.\n", Charsets.UTF_8)
        Log.i(TAG, "FLAC promotion debug test finished: ${reportRoot.absolutePath}")
    }

    private fun runCase(
        case: FlacPromotionCase,
        outputDir: File,
        elapsedMsProvider: () -> Long,
    ): FlacPromotionRow {
        val baseName = case.label.sanitizePathSegment()
        val flacFile = File(outputDir, "$baseName.independent.flac")
        val decorrelatedFlacFile = File(outputDir, "$baseName.decorrelated.flac")
        val wavMd5 = case.wavFile.pcmDataMd5Hex()
        val encodeStartedAtMs = SystemClock.elapsedRealtime()
        val encodeResult = Pcm16StereoFlacEncoder.encodeWavToFlac(
            wavFile = case.wavFile,
            flacFile = flacFile,
            expectedSampleRate = case.sampleRate,
            expectedFrameCount = case.frameCount,
            stereoMode = Pcm16StereoFlacStereoMode.INDEPENDENT,
        )
        val encodeMs = SystemClock.elapsedRealtime() - encodeStartedAtMs

        val decorrelatedEncodeStartedAtMs = SystemClock.elapsedRealtime()
        val decorrelatedEncodeResult = Pcm16StereoFlacEncoder.encodeWavToFlac(
            wavFile = case.wavFile,
            flacFile = decorrelatedFlacFile,
            expectedSampleRate = case.sampleRate,
            expectedFrameCount = case.frameCount,
            stereoMode = Pcm16StereoFlacStereoMode.ADAPTIVE_STEREO_DECORRELATION,
        )
        val decorrelatedEncodeMs = SystemClock.elapsedRealtime() - decorrelatedEncodeStartedAtMs

        val verifyStartedAtMs = SystemClock.elapsedRealtime()
        val verifyResult = Pcm16StereoFlacEncoder.verifyFlacFile(
            flacFile = flacFile,
            expectedSampleRate = case.sampleRate,
            expectedFrameCount = case.frameCount,
            expectedPcmMd5Hex = wavMd5,
        )
        val verifyMs = SystemClock.elapsedRealtime() - verifyStartedAtMs

        val decorrelatedVerifyStartedAtMs = SystemClock.elapsedRealtime()
        val decorrelatedVerifyResult = Pcm16StereoFlacEncoder.verifyFlacFile(
            flacFile = decorrelatedFlacFile,
            expectedSampleRate = case.sampleRate,
            expectedFrameCount = case.frameCount,
            expectedPcmMd5Hex = wavMd5,
        )
        val decorrelatedVerifyMs = SystemClock.elapsedRealtime() - decorrelatedVerifyStartedAtMs

        val platformDecodeStartedAtMs = SystemClock.elapsedRealtime()
        val platformDecodeResult = runCatching {
            val decoded = AudioPcmDecoder(this).decode(Uri.fromFile(flacFile))
            require(decoded.sampleRate == case.sampleRate) { "sampleRate=${decoded.sampleRate}" }
            require(decoded.channelCount == CHANNEL_COUNT_STEREO) { "channelCount=${decoded.channelCount}" }
            require(decoded.frameCount == case.frameCount) { "frameCount=${decoded.frameCount}" }
            require(decoded.pcm16.md5Hex() == wavMd5) { "pcmMd5=${decoded.pcm16.md5Hex()}" }
            "success"
        }.getOrElse { error ->
            "failure: ${error.message ?: error::class.java.name}"
        }
        val platformDecodeMs = SystemClock.elapsedRealtime() - platformDecodeStartedAtMs

        return FlacPromotionRow.success(
            case = case,
            elapsedMs = elapsedMsProvider(),
            encodeMs = encodeMs,
            verifyMs = verifyMs,
            platformDecodeMs = platformDecodeMs,
            platformDecodeResult = platformDecodeResult,
            encodeResultMd5 = encodeResult.pcmMd5Hex,
            frameIndexEntries = encodeResult.frameIndexEntries,
            frameIndexPath = encodeResult.frameIndexPath,
            verifiedMd5 = verifyResult.pcmMd5Hex,
            decorrelatedEncodeMs = decorrelatedEncodeMs,
            decorrelatedVerifyMs = decorrelatedVerifyMs,
            decorrelatedFlacFile = decorrelatedFlacFile,
            decorrelatedMd5 = decorrelatedVerifyResult.pcmMd5Hex,
            decorrelatedFrameIndexEntries = decorrelatedEncodeResult.frameIndexEntries,
            decorrelatedFrameIndexPath = decorrelatedEncodeResult.frameIndexPath,
            decorrelatedAssignmentSummary = decorrelatedEncodeResult.channelAssignmentSummary,
            flacFile = flacFile,
        )
    }

    private fun createSyntheticCase(outputDir: File): FlacPromotionCase {
        val wavFile = File(outputDir, "synthetic_44100_stereo.wav")
        val sampleRate = 44_100
        val frameCount = sampleRate
        WavFileWriter(
            file = wavFile,
            sampleRate = sampleRate,
            channelCount = CHANNEL_COUNT_STEREO,
        ).use { writer ->
            val buffer = ByteArray(frameCount * CHANNEL_COUNT_STEREO * Short.SIZE_BYTES)
            var offset = 0
            for (frame in 0 until frameCount) {
                val left = (sin(2.0 * PI * 440.0 * frame / sampleRate) * Short.MAX_VALUE * 0.35)
                    .roundToInt()
                val right = (sin(2.0 * PI * 660.0 * frame / sampleRate) * Short.MAX_VALUE * 0.35)
                    .roundToInt()
                buffer[offset++] = (left and 0xFF).toByte()
                buffer[offset++] = ((left ushr 8) and 0xFF).toByte()
                buffer[offset++] = (right and 0xFF).toByte()
                buffer[offset++] = ((right ushr 8) and 0xFF).toByte()
            }
            writer.writePcm16(buffer)
        }
        return FlacPromotionCase(
            label = "synthetic_44100_stereo",
            wavFile = wavFile,
            sampleRate = sampleRate,
            frameCount = frameCount,
        )
    }

    private fun findCompletedStemCases(
        inputDirPath: String?,
        maxFiles: Int,
    ): List<FlacPromotionCase> {
        if (maxFiles == 0) return emptyList()
        val root = inputDirPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: SourceSeparationCacheDirectories.legacyOnnxEntries(this)
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { file ->
                file.isFile &&
                        (file.extension.equals("wav", ignoreCase = true) ||
                                file.extension.equals("flac", ignoreCase = true))
            }
            .filter { file ->
                file.name.equals("vocals.wav", ignoreCase = true) ||
                        file.name.equals("instrumental.wav", ignoreCase = true) ||
                        file.name.equals("vocals.flac", ignoreCase = true) ||
                        file.name.equals("instrumental.flac", ignoreCase = true)
            }
            .mapNotNull { file ->
                runCatching {
                    val wavFile = file.asDebugWavInput(root)
                    val info = wavFile.readPcm16StereoWavInfo()
                    FlacPromotionCase(
                        label = file.relativeTo(root).invariantSeparatorsPath,
                        wavFile = wavFile,
                        sampleRate = info.sampleRate,
                        frameCount = info.frameCount,
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Skipping ${file.absolutePath}: ${error.message}")
                    null
                }
            }
            .sortedBy { it.label }
            .take(maxFiles)
            .toList()
    }

    private fun File.asDebugWavInput(root: File): File {
        if (extension.equals("wav", ignoreCase = true)) {
            return this
        }
        val decoded = Pcm16StereoFlacEncoder.decodeFlacFile(this)
        val outputDir = File(
            File(
                SourceSeparationCacheDirectories.debug(this@DebugFlacPromotionActivity),
                "flac-promotion/_decoded-inputs",
            ),
            relativeTo(root).invariantSeparatorsPath.sanitizePathSegment(),
        ).apply { mkdirs() }
        val wavFile = File(outputDir, nameWithoutExtension + ".wav")
        WavFileWriter(
            file = wavFile,
            sampleRate = decoded.sampleRate,
            channelCount = decoded.channelCount,
        ).use { writer ->
            writer.writePcm16(decoded.pcm16)
        }
        return wavFile
    }

    private fun File.readPcm16StereoWavInfo(): DebugWavInfo {
        inputStream().buffered().use { input ->
            val header = ByteArray(WAV_HEADER_BYTES)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) error("Unexpected end of WAV header.")
                offset += count
            }
            require(header.asAscii(0, 4) == "RIFF") { "Missing RIFF header." }
            require(header.asAscii(8, 4) == "WAVE") { "Missing WAVE header." }
            require(header.asAscii(12, 4) == "fmt ") { "Only simple app-generated WAV is supported." }
            require(header.littleEndianShort(20) == 1) { "Only PCM WAV is supported." }
            require(header.littleEndianShort(22) == CHANNEL_COUNT_STEREO) { "Only stereo WAV is supported." }
            require(header.littleEndianShort(34) == 16) { "Only 16-bit WAV is supported." }
            require(header.asAscii(36, 4) == "data") { "Only simple app-generated WAV is supported." }
            val sampleRate = header.littleEndianUInt(24).toInt()
            val dataSize = header.littleEndianUInt(40)
            require(dataSize % (CHANNEL_COUNT_STEREO * Short.SIZE_BYTES) == 0L) {
                "WAV data does not align to stereo PCM16 frames."
            }
            return DebugWavInfo(
                sampleRate = sampleRate,
                frameCount = (dataSize / (CHANNEL_COUNT_STEREO * Short.SIZE_BYTES)).toInt(),
            )
        }
    }

    private fun File.pcmDataMd5Hex(): String {
        inputStream().buffered().use { input ->
            val header = ByteArray(WAV_HEADER_BYTES)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) error("Unexpected end of WAV header.")
                offset += count
            }
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest().toHexString()
        }
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.littleEndianShort(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.littleEndianUInt(offset: Int): Long {
        return (this[offset].toLong() and 0xFF) or
                ((this[offset + 1].toLong() and 0xFF) shl 8) or
                ((this[offset + 2].toLong() and 0xFF) shl 16) or
                ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.md5Hex(): String {
        return MessageDigest.getInstance("MD5")
            .digest(this)
            .toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun String.sanitizePathSegment(): String {
        return replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    }

    private companion object {
        const val TAG = "DebugFlacPromotion"
        const val EXTRA_INPUT_DIR = "input_dir"
        const val EXTRA_OUTPUT_TAG = "output_tag"
        const val EXTRA_MAX_FILES = "max_files"
        const val EXTRA_INCLUDE_SYNTHETIC = "include_synthetic"
        const val DEFAULT_MAX_FILES = 12
        const val CHANNEL_COUNT_STEREO = 2
        const val WAV_HEADER_BYTES = 44
        const val HASH_BUFFER_BYTES = 256 * 1024
    }
}

private data class DebugWavInfo(
    val sampleRate: Int,
    val frameCount: Int,
)

private data class FlacPromotionCase(
    val label: String,
    val wavFile: File,
    val sampleRate: Int,
    val frameCount: Int,
)

private data class FlacPromotionRow(
    val label: String,
    val status: String,
    val wavPath: String,
    val wavBytes: Long,
    val sampleRate: Int,
    val frames: Int,
    val elapsedMs: Long,
    val encodeMs: Long?,
    val verifyMs: Long?,
    val platformDecodeMs: Long?,
    val platformDecodeResult: String?,
    val flacPath: String?,
    val flacBytes: Long?,
    val compressionRatio: Double?,
    val frameIndexEntries: Int?,
    val frameIndexPath: String?,
    val decorrelatedEncodeMs: Long?,
    val decorrelatedVerifyMs: Long?,
    val decorrelatedFlacPath: String?,
    val decorrelatedFlacBytes: Long?,
    val decorrelatedCompressionRatio: Double?,
    val decorrelatedSavingsRatio: Double?,
    val decorrelatedFrameIndexEntries: Int?,
    val decorrelatedFrameIndexPath: String?,
    val decorrelatedAssignmentSummary: String?,
    val decorrelatedPcmMd5: String?,
    val encodePcmMd5: String?,
    val verifiedPcmMd5: String?,
    val errorMessage: String?,
) {
    fun toCsvLine(): String {
        return listOf(
            label,
            status,
            wavPath,
            wavBytes,
            sampleRate,
            frames,
            elapsedMs,
            encodeMs,
            verifyMs,
            platformDecodeMs,
            platformDecodeResult,
            flacPath,
            flacBytes,
            compressionRatio,
            frameIndexEntries,
            frameIndexPath,
            decorrelatedEncodeMs,
            decorrelatedVerifyMs,
            decorrelatedFlacPath,
            decorrelatedFlacBytes,
            decorrelatedCompressionRatio,
            decorrelatedSavingsRatio,
            decorrelatedFrameIndexEntries,
            decorrelatedFrameIndexPath,
            decorrelatedAssignmentSummary,
            decorrelatedPcmMd5,
            encodePcmMd5,
            verifiedPcmMd5,
            errorMessage,
        ).joinToString(",") { it.toCsvCell() }
    }

    companion object {
        fun csvHeader(): String {
            return listOf(
                "label",
                "status",
                "wavPath",
                "wavBytes",
                "sampleRate",
                "frames",
                "elapsedMs",
                "encodeMs",
                "verifyMs",
                "platformDecodeMs",
                "platformDecodeResult",
                "flacPath",
                "flacBytes",
                "compressionRatio",
                "frameIndexEntries",
                "frameIndexPath",
                "decorrelatedEncodeMs",
                "decorrelatedVerifyMs",
                "decorrelatedFlacPath",
                "decorrelatedFlacBytes",
                "decorrelatedCompressionRatio",
                "decorrelatedSavingsRatio",
                "decorrelatedFrameIndexEntries",
                "decorrelatedFrameIndexPath",
                "decorrelatedAssignmentSummary",
                "decorrelatedPcmMd5",
                "encodePcmMd5",
                "verifiedPcmMd5",
                "errorMessage",
            ).joinToString(",")
        }

        fun success(
            case: FlacPromotionCase,
            elapsedMs: Long,
            encodeMs: Long,
            verifyMs: Long,
            platformDecodeMs: Long,
            platformDecodeResult: String,
            encodeResultMd5: String,
            frameIndexEntries: Int,
            frameIndexPath: String?,
            verifiedMd5: String,
            decorrelatedEncodeMs: Long,
            decorrelatedVerifyMs: Long,
            decorrelatedFlacFile: File,
            decorrelatedMd5: String,
            decorrelatedFrameIndexEntries: Int,
            decorrelatedFrameIndexPath: String?,
            decorrelatedAssignmentSummary: String,
            flacFile: File,
        ): FlacPromotionRow {
            val flacBytes = flacFile.length()
            val decorrelatedBytes = decorrelatedFlacFile.length()
            return FlacPromotionRow(
                label = case.label,
                status = "success",
                wavPath = case.wavFile.absolutePath,
                wavBytes = case.wavFile.length(),
                sampleRate = case.sampleRate,
                frames = case.frameCount,
                elapsedMs = elapsedMs,
                encodeMs = encodeMs,
                verifyMs = verifyMs,
                platformDecodeMs = platformDecodeMs,
                platformDecodeResult = platformDecodeResult,
                flacPath = flacFile.absolutePath,
                flacBytes = flacBytes,
                compressionRatio = flacBytes.toDouble() / case.wavFile.length().coerceAtLeast(1L),
                frameIndexEntries = frameIndexEntries,
                frameIndexPath = frameIndexPath,
                decorrelatedEncodeMs = decorrelatedEncodeMs,
                decorrelatedVerifyMs = decorrelatedVerifyMs,
                decorrelatedFlacPath = decorrelatedFlacFile.absolutePath,
                decorrelatedFlacBytes = decorrelatedBytes,
                decorrelatedCompressionRatio = decorrelatedBytes.toDouble() /
                        case.wavFile.length().coerceAtLeast(1L),
                decorrelatedSavingsRatio = (flacBytes - decorrelatedBytes).toDouble() /
                        flacBytes.coerceAtLeast(1L),
                decorrelatedFrameIndexEntries = decorrelatedFrameIndexEntries,
                decorrelatedFrameIndexPath = decorrelatedFrameIndexPath,
                decorrelatedAssignmentSummary = decorrelatedAssignmentSummary,
                decorrelatedPcmMd5 = decorrelatedMd5,
                encodePcmMd5 = encodeResultMd5,
                verifiedPcmMd5 = verifiedMd5,
                errorMessage = null,
            )
        }

        fun failure(
            case: FlacPromotionCase,
            elapsedMs: Long,
            error: Throwable,
        ): FlacPromotionRow {
            return FlacPromotionRow(
                label = case.label,
                status = "failure",
                wavPath = case.wavFile.absolutePath,
                wavBytes = case.wavFile.length(),
                sampleRate = case.sampleRate,
                frames = case.frameCount,
                elapsedMs = elapsedMs,
                encodeMs = null,
                verifyMs = null,
                platformDecodeMs = null,
                platformDecodeResult = null,
                flacPath = null,
                flacBytes = null,
                compressionRatio = null,
                frameIndexEntries = null,
                frameIndexPath = null,
                decorrelatedEncodeMs = null,
                decorrelatedVerifyMs = null,
                decorrelatedFlacPath = null,
                decorrelatedFlacBytes = null,
                decorrelatedCompressionRatio = null,
                decorrelatedSavingsRatio = null,
                decorrelatedFrameIndexEntries = null,
                decorrelatedFrameIndexPath = null,
                decorrelatedAssignmentSummary = null,
                decorrelatedPcmMd5 = null,
                encodePcmMd5 = null,
                verifiedPcmMd5 = null,
                errorMessage = error.stackTraceToString(),
            )
        }
    }
}

private fun Any?.toCsvCell(): String {
    val text = when (this) {
        null -> ""
        is Double -> String.format(Locale.US, "%.6f", this)
        else -> toString()
    }
    return "\"" + text.replace("\"", "\"\"") + "\""
}
