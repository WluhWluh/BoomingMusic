package com.mardous.booming.separation.audio

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

object Pcm16StereoFlacEncoder {
    fun encodeWavToFlac(
        wavFile: File,
        flacFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        stereoMode: Pcm16StereoFlacStereoMode = Pcm16StereoFlacStereoMode.INDEPENDENT,
        writeFrameIndex: Boolean = true,
        shouldCancel: () -> Boolean = { false },
    ): Pcm16StereoFlacEncodeResult {
        require(expectedSampleRate > 0) { "Expected sample rate must be positive." }
        require(expectedFrameCount >= 0) { "Expected frame count must not be negative." }

        val wavInfo = readPcm16StereoWavInfo(wavFile)
        require(wavInfo.sampleRate == expectedSampleRate) {
            "WAV sample rate does not match expected output rate."
        }
        require(wavInfo.frameCount == expectedFrameCount) {
            "WAV frame count does not match expected output length."
        }

        val pcmMd5 = wavFile.pcmMd5(wavInfo, shouldCancel)
        val targetDir = flacFile.parentFile
        targetDir?.mkdirs()
        val tempFile = File(targetDir ?: File("."), "${flacFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        var frameIndex: Pcm16StereoFlacFrameIndex? = null
        try {
            tempFile.outputStream().buffered().use { output ->
                output.write(FLAC_MAGIC)
                output.writeStreamInfo(
                    sampleRate = wavInfo.sampleRate,
                    frameCount = wavInfo.frameCount,
                    pcmMd5 = pcmMd5,
                )
                wavFile.inputStream().buffered().use { input ->
                    skipFully(input, wavInfo.dataOffset)
                    frameIndex = encodeFrames(
                        input = input,
                        output = output,
                        frameCount = wavInfo.frameCount,
                        sampleRate = wavInfo.sampleRate,
                        pcmMd5Hex = pcmMd5.toHexString(),
                        firstFrameByteOffset = FLAC_MAGIC.size + STREAMINFO_METADATA_HEADER.size + STREAMINFO_LENGTH,
                        stereoMode = stereoMode,
                        shouldCancel = shouldCancel,
                    )
                }
            }
            throwIfCanceled(shouldCancel)

            if (flacFile.exists() && !flacFile.delete()) {
                error("Could not replace FLAC output: ${flacFile.absolutePath}")
            }
            if (!tempFile.renameTo(flacFile)) {
                tempFile.copyTo(flacFile, overwrite = true)
                tempFile.delete()
            }
            val finalIndex = frameIndex?.copy(flacBytes = flacFile.length())
            if (writeFrameIndex && finalIndex != null) {
                writeFrameIndexFile(finalIndex, frameIndexFileFor(flacFile))
            }

            return Pcm16StereoFlacEncodeResult(
                sampleRate = wavInfo.sampleRate,
                channelCount = CHANNEL_COUNT_STEREO,
                bitsPerSample = BITS_PER_SAMPLE,
                frameCount = wavInfo.frameCount,
                pcmMd5Hex = pcmMd5.toHexString(),
                outputBytes = flacFile.length(),
                frameIndexEntries = finalIndex?.frames?.size ?: 0,
                frameIndexPath = frameIndexFileFor(flacFile).takeIf { it.isFile }?.absolutePath,
                stereoMode = stereoMode,
                channelAssignmentSummary = finalIndex?.channelAssignmentSummary().orEmpty(),
            )
        } catch (error: Throwable) {
            tempFile.delete()
            frameIndexFileFor(flacFile).delete()
            throw error
        }
    }

    fun verifyFlacFile(
        flacFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        expectedPcmMd5Hex: String? = null,
        shouldCancel: () -> Boolean = { false },
    ): Pcm16StereoFlacVerifyResult {
        require(flacFile.isFile) { "FLAC file does not exist: ${flacFile.absolutePath}" }
        flacFile.inputStream().buffered().use { input ->
            require(input.readAscii(FLAC_MAGIC.size) == "fLaC") { "Missing FLAC stream marker." }
            val streamInfo = readStreamInfo(input)
            require(streamInfo.sampleRate == expectedSampleRate) {
                "FLAC sample rate does not match expected output rate."
            }
            require(streamInfo.channelCount == CHANNEL_COUNT_STEREO) {
                "Only stereo FLAC output is supported."
            }
            require(streamInfo.bitsPerSample == BITS_PER_SAMPLE) {
                "Only 16-bit FLAC output is supported."
            }
            require(streamInfo.totalSamples == expectedFrameCount.toLong()) {
                "FLAC frame count does not match expected output length."
            }

            val digest = MessageDigest.getInstance("MD5")
            val left = IntArray(MAX_BLOCK_SIZE)
            val right = IntArray(MAX_BLOCK_SIZE)
            var decodedFrames = 0L
            while (decodedFrames < streamInfo.totalSamples) {
                throwIfCanceled(shouldCancel)
                val blockFrames = readAndDecodeFrame(
                    input = input,
                    left = left,
                    right = right,
                )
                require(decodedFrames + blockFrames <= streamInfo.totalSamples) {
                    "FLAC frame output exceeds STREAMINFO total samples."
                }
                digest.updateInterleavedPcm16(left, right, blockFrames)
                decodedFrames += blockFrames
            }

            val decodedMd5Hex = digest.digest().toHexString()
            require(decodedMd5Hex == streamInfo.pcmMd5Hex) {
                "Decoded FLAC PCM does not match STREAMINFO MD5."
            }
            if (expectedPcmMd5Hex != null) {
                require(decodedMd5Hex == expectedPcmMd5Hex) {
                    "Decoded FLAC PCM does not match expected PCM MD5."
                }
            }
            return Pcm16StereoFlacVerifyResult(
                sampleRate = streamInfo.sampleRate,
                channelCount = streamInfo.channelCount,
                bitsPerSample = streamInfo.bitsPerSample,
                frameCount = decodedFrames.toInt(),
                pcmMd5Hex = decodedMd5Hex,
                fileBytes = flacFile.length(),
            )
        }
    }

    fun decodeFlacFile(flacFile: File): Pcm16StereoFlacDecodeResult {
        require(flacFile.isFile) { "FLAC file does not exist: ${flacFile.absolutePath}" }
        flacFile.inputStream().buffered().use { input ->
            require(input.readAscii(FLAC_MAGIC.size) == "fLaC") { "Missing FLAC stream marker." }
            val streamInfo = readStreamInfo(input)
            require(streamInfo.channelCount == CHANNEL_COUNT_STEREO) {
                "Only stereo FLAC output is supported."
            }
            require(streamInfo.bitsPerSample == BITS_PER_SAMPLE) {
                "Only 16-bit FLAC output is supported."
            }
            val pcmBytes = streamInfo.totalSamples * BYTES_PER_FRAME
            require(pcmBytes <= Int.MAX_VALUE) {
                "Decoded FLAC PCM is too large to keep in memory."
            }

            val digest = MessageDigest.getInstance("MD5")
            val pcm = ByteArray(pcmBytes.toInt())
            val left = IntArray(MAX_BLOCK_SIZE)
            val right = IntArray(MAX_BLOCK_SIZE)
            var decodedFrames = 0L
            var pcmOffset = 0
            while (decodedFrames < streamInfo.totalSamples) {
                val blockFrames = readAndDecodeFrame(
                    input = input,
                    left = left,
                    right = right,
                )
                require(decodedFrames + blockFrames <= streamInfo.totalSamples) {
                    "FLAC frame output exceeds STREAMINFO total samples."
                }
                writeInterleavedPcm16(
                    left = left,
                    right = right,
                    frameCount = blockFrames,
                    output = pcm,
                    outputOffset = pcmOffset,
                )
                digest.update(pcm, pcmOffset, blockFrames * BYTES_PER_FRAME)
                pcmOffset += blockFrames * BYTES_PER_FRAME
                decodedFrames += blockFrames
            }

            val decodedMd5Hex = digest.digest().toHexString()
            require(decodedMd5Hex == streamInfo.pcmMd5Hex) {
                "Decoded FLAC PCM does not match STREAMINFO MD5."
            }
            return Pcm16StereoFlacDecodeResult(
                sampleRate = streamInfo.sampleRate,
                channelCount = streamInfo.channelCount,
                bitsPerSample = streamInfo.bitsPerSample,
                frameCount = decodedFrames.toInt(),
                pcm16 = pcm,
                pcmMd5Hex = decodedMd5Hex,
            )
        }
    }

    fun decodeFlacFileToPcmFile(
        flacFile: File,
        pcmFile: File,
        shouldCancel: () -> Boolean = { false },
    ): Pcm16StereoFlacDecodeToFileResult {
        require(flacFile.isFile) { "FLAC file does not exist: ${flacFile.absolutePath}" }
        val targetDir = pcmFile.parentFile
        targetDir?.mkdirs()
        val tempFile = File(targetDir ?: File("."), "${pcmFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        return try {
            flacFile.inputStream().buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    require(input.readAscii(FLAC_MAGIC.size) == "fLaC") { "Missing FLAC stream marker." }
                    val streamInfo = readStreamInfo(input)
                    require(streamInfo.channelCount == CHANNEL_COUNT_STEREO) {
                        "Only stereo FLAC output is supported."
                    }
                    require(streamInfo.bitsPerSample == BITS_PER_SAMPLE) {
                        "Only 16-bit FLAC output is supported."
                    }

                    val digest = MessageDigest.getInstance("MD5")
                    val pcm = ByteArray(MAX_BLOCK_SIZE * BYTES_PER_FRAME)
                    val left = IntArray(MAX_BLOCK_SIZE)
                    val right = IntArray(MAX_BLOCK_SIZE)
                    var decodedFrames = 0L
                    while (decodedFrames < streamInfo.totalSamples) {
                        if (shouldCancel()) {
                            throw CancellationException("FLAC decode canceled.")
                        }
                        val blockFrames = readAndDecodeFrame(
                            input = input,
                            left = left,
                            right = right,
                        )
                        require(decodedFrames + blockFrames <= streamInfo.totalSamples) {
                            "FLAC frame output exceeds STREAMINFO total samples."
                        }
                        val blockBytes = blockFrames * BYTES_PER_FRAME
                        writeInterleavedPcm16(
                            left = left,
                            right = right,
                            frameCount = blockFrames,
                            output = pcm,
                            outputOffset = 0,
                        )
                        output.write(pcm, 0, blockBytes)
                        digest.update(pcm, 0, blockBytes)
                        decodedFrames += blockFrames
                    }

                    val decodedMd5Hex = digest.digest().toHexString()
                    require(decodedMd5Hex == streamInfo.pcmMd5Hex) {
                        "Decoded FLAC PCM does not match STREAMINFO MD5."
                    }
                    output.flush()
                    if (pcmFile.exists()) {
                        pcmFile.delete()
                    }
                    require(tempFile.renameTo(pcmFile)) {
                        "Could not replace PCM output: ${pcmFile.absolutePath}"
                    }
                    Pcm16StereoFlacDecodeToFileResult(
                        sampleRate = streamInfo.sampleRate,
                        channelCount = streamInfo.channelCount,
                        bitsPerSample = streamInfo.bitsPerSample,
                        frameCount = decodedFrames.toInt(),
                        pcmMd5Hex = decodedMd5Hex,
                        fileBytes = pcmFile.length(),
                    )
                }
            }
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    fun frameIndexFileFor(flacFile: File): File {
        return File(flacFile.absolutePath + FRAME_INDEX_EXTENSION)
    }

    fun openIndexedPcmReader(
        flacFile: File,
        traceSink: ((String) -> Unit)? = null,
    ): Pcm16StereoFlacPcmReader? {
        val indexFile = frameIndexFileFor(flacFile)
        if (!indexFile.isFile) {
            traceSink?.invoke("indexedOpen skipped reason=missingIndex file=${flacFile.name}")
            return null
        }
        return runCatching {
            val index = readFrameIndexFile(indexFile)
            traceSink?.invoke(
                "indexedOpen validate file=${flacFile.name} flacBytes=${flacFile.length()} " +
                        "indexBytes=${index.flacBytes} indexFrames=${index.frames.size} " +
                        "frameCount=${index.frameCount} sampleRate=${index.sampleRate}"
            )
            require(index.flacBytes == flacFile.length()) {
                "FLAC index byte length does not match file length."
            }
            val streamInfo = readStreamInfo(flacFile)
            require(streamInfo.sampleRate == index.sampleRate) {
                "FLAC index sample rate does not match STREAMINFO."
            }
            require(streamInfo.channelCount == index.channelCount) {
                "FLAC index channel count does not match STREAMINFO."
            }
            require(streamInfo.bitsPerSample == index.bitsPerSample) {
                "FLAC index bit depth does not match STREAMINFO."
            }
            require(streamInfo.totalSamples == index.frameCount.toLong()) {
                "FLAC index frame count does not match STREAMINFO."
            }
            require(streamInfo.pcmMd5Hex == index.pcmMd5Hex) {
                "FLAC index PCM MD5 does not match STREAMINFO."
            }
            IndexedFlacPcmReader(
                flacFile = flacFile,
                index = index,
                traceSink = traceSink,
            )
        }.getOrElse { error ->
            traceSink?.invoke(
                "indexedOpen failed file=${flacFile.name} error=${error.message ?: error::class.java.name}"
            )
            null
        }
    }

    private fun encodeFrames(
        input: InputStream,
        output: OutputStream,
        frameCount: Int,
        sampleRate: Int,
        pcmMd5Hex: String,
        firstFrameByteOffset: Int,
        stereoMode: Pcm16StereoFlacStereoMode,
        shouldCancel: () -> Boolean,
    ): Pcm16StereoFlacFrameIndex {
        val pcmBuffer = ByteArray(MAX_BLOCK_SIZE * BYTES_PER_FRAME)
        val left = IntArray(MAX_BLOCK_SIZE)
        val right = IntArray(MAX_BLOCK_SIZE)
        val scratchA = IntArray(MAX_BLOCK_SIZE)
        val scratchB = IntArray(MAX_BLOCK_SIZE)
        val frameIndexEntries = ArrayList<Pcm16StereoFlacFrameIndexEntry>()
        var remainingFrames = frameCount
        var frameNumber = 0L
        var pcmStartFrame = 0L
        var outputByteOffset = firstFrameByteOffset.toLong()

        while (remainingFrames > 0) {
            throwIfCanceled(shouldCancel)
            val blockFrames = min(MAX_BLOCK_SIZE, remainingFrames)
            val blockBytes = blockFrames * BYTES_PER_FRAME
            input.readFully(pcmBuffer, blockBytes)
            for (frame in 0 until blockFrames) {
                val offset = frame * BYTES_PER_FRAME
                left[frame] = pcmBuffer.readLittleEndianShort(offset)
                right[frame] = pcmBuffer.readLittleEndianShort(offset + BYTES_PER_SAMPLE)
            }
            val encodedFrame = encodeFrame(
                frameNumber = frameNumber,
                blockFrames = blockFrames,
                left = left,
                right = right,
                scratchA = scratchA,
                scratchB = scratchB,
                stereoMode = stereoMode,
            )
            output.write(encodedFrame.bytes)
            frameIndexEntries += Pcm16StereoFlacFrameIndexEntry(
                frameNumber = frameNumber,
                startPcmFrame = pcmStartFrame,
                pcmFrameCount = blockFrames,
                byteOffset = outputByteOffset,
                byteCount = encodedFrame.bytes.size,
                channelAssignment = encodedFrame.channelAssignment,
            )
            frameNumber += 1L
            pcmStartFrame += blockFrames
            outputByteOffset += encodedFrame.bytes.size
            remainingFrames -= blockFrames
        }
        return Pcm16StereoFlacFrameIndex(
            sampleRate = sampleRate,
            channelCount = CHANNEL_COUNT_STEREO,
            bitsPerSample = BITS_PER_SAMPLE,
            frameCount = frameCount,
            maxBlockSize = MAX_BLOCK_SIZE,
            flacBytes = outputByteOffset,
            pcmMd5Hex = pcmMd5Hex,
            frames = frameIndexEntries,
        )
    }

    private fun encodeFrame(
        frameNumber: Long,
        blockFrames: Int,
        left: IntArray,
        right: IntArray,
        scratchA: IntArray,
        scratchB: IntArray,
        stereoMode: Pcm16StereoFlacStereoMode,
    ): EncodedFlacFrame {
        val independentFrame = encodeFrameWithAssignment(
            frameNumber = frameNumber,
            blockFrames = blockFrames,
            channelAssignment = CHANNEL_ASSIGNMENT_STEREO,
            first = left,
            second = right,
        )
        if (stereoMode != Pcm16StereoFlacStereoMode.ADAPTIVE_STEREO_DECORRELATION) {
            return independentFrame
        }

        var bestFrame = independentFrame
        prepareLeftSide(left, right, scratchA, scratchB, blockFrames)
        bestFrame = bestFrame.chooseSmaller(
            other = encodeFrameWithAssignment(
                frameNumber = frameNumber,
                blockFrames = blockFrames,
                channelAssignment = CHANNEL_ASSIGNMENT_LEFT_SIDE,
                first = scratchA,
                second = scratchB,
            ),
        )
        prepareRightSide(left, right, scratchA, scratchB, blockFrames)
        bestFrame = bestFrame.chooseSmaller(
            other = encodeFrameWithAssignment(
                frameNumber = frameNumber,
                blockFrames = blockFrames,
                channelAssignment = CHANNEL_ASSIGNMENT_RIGHT_SIDE,
                first = scratchA,
                second = scratchB,
            ),
        )
        prepareMidSide(left, right, scratchA, scratchB, blockFrames)
        return bestFrame.chooseSmaller(
            other = encodeFrameWithAssignment(
                frameNumber = frameNumber,
                blockFrames = blockFrames,
                channelAssignment = CHANNEL_ASSIGNMENT_MID_SIDE,
                first = scratchA,
                second = scratchB,
            ),
        )
    }

    private fun EncodedFlacFrame.chooseSmaller(other: EncodedFlacFrame): EncodedFlacFrame {
        return if (other.bytes.size < bytes.size) other else this
    }

    private fun encodeFrameWithAssignment(
        frameNumber: Long,
        blockFrames: Int,
        channelAssignment: Int,
        first: IntArray,
        second: IntArray,
    ): EncodedFlacFrame {
        val frame = ByteArrayOutputStream(blockFrames * BYTES_PER_FRAME)
        val header = ByteArrayOutputStream()
        val blockSizeCode = if (blockFrames == MAX_BLOCK_SIZE) {
            BLOCK_SIZE_CODE_4096
        } else {
            BLOCK_SIZE_CODE_16_BIT
        }
        header.writeUInt16(0xFFF8)
        header.write((blockSizeCode shl 4) or SAMPLE_RATE_FROM_STREAMINFO)
        header.write((channelAssignment shl 4) or (SAMPLE_SIZE_16_BIT shl 1))
        header.writeFlacUtf8UInt(frameNumber)
        if (blockSizeCode == BLOCK_SIZE_CODE_16_BIT) {
            header.writeUInt16(blockFrames - 1)
        }
        val headerBytes = header.toByteArray()
        frame.write(headerBytes)
        frame.write(crc8(headerBytes))

        val bitWriter = FlacBitWriter(frame)
        val firstBitsPerSample = bitsPerSampleForChannel(
            channelAssignment = channelAssignment,
            channelIndex = 0,
        )
        val secondBitsPerSample = bitsPerSampleForChannel(
            channelAssignment = channelAssignment,
            channelIndex = 1,
        )
        bitWriter.writeBestSubframe(first, blockFrames, firstBitsPerSample)
        bitWriter.writeBestSubframe(second, blockFrames, secondBitsPerSample)
        bitWriter.alignToByte()

        val frameWithoutCrc = frame.toByteArray()
        frame.writeUInt16(crc16(frameWithoutCrc))
        return EncodedFlacFrame(
            bytes = frame.toByteArray(),
            channelAssignment = channelAssignment,
        )
    }

    private fun prepareLeftSide(
        left: IntArray,
        right: IntArray,
        first: IntArray,
        second: IntArray,
        count: Int,
    ) {
        for (index in 0 until count) {
            first[index] = left[index]
            second[index] = left[index] - right[index]
        }
    }

    private fun prepareRightSide(
        left: IntArray,
        right: IntArray,
        first: IntArray,
        second: IntArray,
        count: Int,
    ) {
        for (index in 0 until count) {
            first[index] = left[index] - right[index]
            second[index] = right[index]
        }
    }

    private fun prepareMidSide(
        left: IntArray,
        right: IntArray,
        first: IntArray,
        second: IntArray,
        count: Int,
    ) {
        for (index in 0 until count) {
            val side = left[index] - right[index]
            first[index] = (left[index] + right[index]) shr 1
            second[index] = side
        }
    }

    private fun FlacBitWriter.writeBestSubframe(
        samples: IntArray,
        count: Int,
        bitsPerSample: Int,
    ) {
        val fixedSubframe = bestFixedSubframe(samples, count, bitsPerSample)
        val verbatimBits = VERBATIM_HEADER_BITS + count * bitsPerSample
        if (fixedSubframe != null && fixedSubframe.estimatedBits < verbatimBits) {
            writeBits(((FIXED_SUBFRAME_TYPE_BASE + fixedSubframe.order) shl 1).toLong(), SUBFRAME_HEADER_BITS)
            for (index in 0 until fixedSubframe.order) {
                writeSignedSample(samples[index], bitsPerSample)
            }
            writeBits(RESIDUAL_CODING_METHOD_RICE.toLong(), RESIDUAL_CODING_METHOD_BITS)
            writeBits(RESIDUAL_PARTITION_ORDER_ZERO.toLong(), RESIDUAL_PARTITION_ORDER_BITS)
            writeBits(fixedSubframe.riceParameter.toLong(), RICE_PARAMETER_BITS)
            for (index in 0 until fixedSubframe.residualCount) {
                writeRiceSigned(fixedSubframe.residuals[index], fixedSubframe.riceParameter)
            }
        } else {
            writeBits((VERBATIM_SUBFRAME_TYPE shl 1).toLong(), SUBFRAME_HEADER_BITS)
            for (index in 0 until count) {
                writeSignedSample(samples[index], bitsPerSample)
            }
        }
    }

    private fun readStreamInfo(input: InputStream): FlacStreamInfo {
        var streamInfo: FlacStreamInfo? = null
        var lastBlock = false
        while (!lastBlock) {
            val header = input.readRequiredByte()
            lastBlock = (header and 0x80) != 0
            val type = header and 0x7F
            val length = input.readUInt24()
            val data = input.readFullyToByteArray(length)
            if (type == STREAMINFO_METADATA_BLOCK_TYPE) {
                require(length == STREAMINFO_LENGTH) { "Invalid FLAC STREAMINFO length." }
                val packed = data.readBigEndianLong(10)
                streamInfo = FlacStreamInfo(
                    sampleRate = ((packed ushr STREAMINFO_SAMPLE_RATE_SHIFT) and
                            STREAMINFO_SAMPLE_RATE_MASK).toInt(),
                    channelCount = (((packed ushr STREAMINFO_CHANNEL_SHIFT) and
                            STREAMINFO_CHANNEL_MASK) + 1L).toInt(),
                    bitsPerSample = (((packed ushr STREAMINFO_BITS_PER_SAMPLE_SHIFT) and
                            STREAMINFO_BITS_PER_SAMPLE_MASK) + 1L).toInt(),
                    totalSamples = packed and STREAMINFO_TOTAL_SAMPLES_MASK,
                    pcmMd5Hex = data.copyOfRange(STREAMINFO_MD5_OFFSET, STREAMINFO_LENGTH).toHexString(),
                )
            }
        }
        return streamInfo ?: error("FLAC stream has no STREAMINFO metadata block.")
    }

    private fun readStreamInfo(flacFile: File): FlacStreamInfo {
        flacFile.inputStream().buffered().use { input ->
            require(input.readAscii(FLAC_MAGIC.size) == "fLaC") { "Missing FLAC stream marker." }
            return readStreamInfo(input)
        }
    }

    private fun readAndDecodeFrame(
        input: InputStream,
        left: IntArray,
        right: IntArray,
    ): Int {
        val first = input.readRequiredByte()
        val second = input.readRequiredByte()
        require(first == 0xFF && second == 0xF8) { "Unsupported FLAC frame sync/header." }
        val blockAndRate = input.readRequiredByte()
        val channelsAndSampleSize = input.readRequiredByte()
        val blockSizeCode = blockAndRate ushr 4
        val sampleRateCode = blockAndRate and 0x0F
        val channelAssignment = channelsAndSampleSize ushr 4
        val sampleSizeCode = (channelsAndSampleSize ushr 1) and 0x07
        val reserved = channelsAndSampleSize and 0x01

        require(sampleRateCode == SAMPLE_RATE_FROM_STREAMINFO) {
            "Only FLAC frames using STREAMINFO sample rate are supported."
        }
        require(
            channelAssignment == CHANNEL_ASSIGNMENT_STEREO ||
                    channelAssignment == CHANNEL_ASSIGNMENT_LEFT_SIDE ||
                    channelAssignment == CHANNEL_ASSIGNMENT_RIGHT_SIDE ||
                    channelAssignment == CHANNEL_ASSIGNMENT_MID_SIDE
        ) {
            "Only independent stereo and stereo decorrelation FLAC frames are supported."
        }
        require(sampleSizeCode == SAMPLE_SIZE_16_BIT) {
            "Only 16-bit FLAC frames are supported."
        }
        require(reserved == 0) { "Invalid FLAC frame reserved bit." }

        readFlacUtf8UInt(input)
        val blockFrames = when (blockSizeCode) {
            BLOCK_SIZE_CODE_4096 -> MAX_BLOCK_SIZE
            BLOCK_SIZE_CODE_16_BIT -> input.readUInt16() + 1
            else -> error("Unsupported FLAC block size code: $blockSizeCode")
        }
        input.readRequiredByte()

        val bitReader = FlacBitReader(input)
        bitReader.readSubframe(
            samples = left,
            blockFrames = blockFrames,
            bitsPerSample = bitsPerSampleForChannel(
                channelAssignment = channelAssignment,
                channelIndex = 0,
            ),
        )
        bitReader.readSubframe(
            samples = right,
            blockFrames = blockFrames,
            bitsPerSample = bitsPerSampleForChannel(
                channelAssignment = channelAssignment,
                channelIndex = 1,
            ),
        )
        bitReader.alignToByte()
        input.readUInt16()
        restoreStereoChannels(
            channelAssignment = channelAssignment,
            left = left,
            right = right,
            frameCount = blockFrames,
        )
        return blockFrames
    }

    private fun restoreStereoChannels(
        channelAssignment: Int,
        left: IntArray,
        right: IntArray,
        frameCount: Int,
    ) {
        when (channelAssignment) {
            CHANNEL_ASSIGNMENT_STEREO -> Unit
            CHANNEL_ASSIGNMENT_LEFT_SIDE -> {
                for (index in 0 until frameCount) {
                    right[index] = left[index] - right[index]
                }
            }
            CHANNEL_ASSIGNMENT_RIGHT_SIDE -> {
                for (index in 0 until frameCount) {
                    left[index] += right[index]
                }
            }
            CHANNEL_ASSIGNMENT_MID_SIDE -> {
                for (index in 0 until frameCount) {
                    val mid = left[index]
                    val side = right[index]
                    val reconstructedLeft = (mid shl 1) or (side and 1)
                    left[index] = (reconstructedLeft + side) shr 1
                    right[index] = (reconstructedLeft - side) shr 1
                }
            }
            else -> error("Unsupported FLAC channel assignment: $channelAssignment")
        }
    }

    private fun FlacBitReader.readSubframe(
        samples: IntArray,
        blockFrames: Int,
        bitsPerSample: Int,
    ) {
        val header = readBits(SUBFRAME_HEADER_BITS).toInt()
        require((header and 1) == 0) { "Wasted bits-per-sample are not supported." }
        when (val type = (header ushr 1) and 0x3F) {
            VERBATIM_SUBFRAME_TYPE -> {
                for (index in 0 until blockFrames) {
                    samples[index] = readSignedSample(bitsPerSample)
                }
            }
            in FIXED_SUBFRAME_TYPE_BASE..(FIXED_SUBFRAME_TYPE_BASE + MAX_FIXED_PREDICTOR_ORDER) -> {
                val order = type - FIXED_SUBFRAME_TYPE_BASE
                for (index in 0 until order) {
                    samples[index] = readSignedSample(bitsPerSample)
                }
                val residualCodingMethod = readBits(RESIDUAL_CODING_METHOD_BITS).toInt()
                val partitionOrder = readBits(RESIDUAL_PARTITION_ORDER_BITS).toInt()
                require(residualCodingMethod == RESIDUAL_CODING_METHOD_RICE) {
                    "Only FLAC Rice residuals are supported."
                }
                require(partitionOrder == RESIDUAL_PARTITION_ORDER_ZERO) {
                    "Only FLAC partition order zero is supported."
                }
                val riceParameter = readBits(RICE_PARAMETER_BITS).toInt()
                require(riceParameter <= MAX_RICE_PARAMETER) {
                    "Unsupported FLAC escaped Rice residual parameter."
                }
                for (index in order until blockFrames) {
                    val residual = readRiceSigned(riceParameter)
                    samples[index] = fixedPrediction(samples, index, order) + residual
                }
            }
            else -> error("Unsupported FLAC subframe type: $type")
        }
    }

    private fun bestFixedSubframe(
        samples: IntArray,
        count: Int,
        bitsPerSample: Int,
    ): FixedSubframe? {
        if (count <= 0) return null
        val maxOrder = min(MAX_FIXED_PREDICTOR_ORDER, count - 1)
        var best: FixedSubframe? = null
        for (order in 0..maxOrder) {
            var warmupSamplesFit = true
            for (index in 0 until order) {
                if (!samples[index].fitsSignedBits(bitsPerSample)) {
                    warmupSamplesFit = false
                    break
                }
            }
            if (!warmupSamplesFit) continue
            val residualCount = count - order
            val residuals = IntArray(residualCount)
            for (index in order until count) {
                residuals[index - order] = samples[index] - fixedPrediction(samples, index, order)
            }
            val rice = bestRiceParameter(residuals, residualCount) ?: continue
            val estimatedBits = FIXED_SUBFRAME_HEADER_BITS +
                    order * bitsPerSample +
                    RESIDUAL_HEADER_BITS +
                    residualCount.estimateRiceBits(residuals, rice.parameter)
            if (best == null || estimatedBits < best.estimatedBits) {
                best = FixedSubframe(
                    order = order,
                    riceParameter = rice.parameter,
                    residuals = residuals,
                    residualCount = residualCount,
                    estimatedBits = estimatedBits,
                )
            }
        }
        return best
    }

    private fun bitsPerSampleForChannel(
        channelAssignment: Int,
        channelIndex: Int,
    ): Int {
        return when (channelAssignment) {
            CHANNEL_ASSIGNMENT_LEFT_SIDE -> if (channelIndex == 1) BITS_PER_SAMPLE + 1 else BITS_PER_SAMPLE
            CHANNEL_ASSIGNMENT_RIGHT_SIDE -> if (channelIndex == 0) BITS_PER_SAMPLE + 1 else BITS_PER_SAMPLE
            CHANNEL_ASSIGNMENT_MID_SIDE -> if (channelIndex == 1) BITS_PER_SAMPLE + 1 else BITS_PER_SAMPLE
            else -> BITS_PER_SAMPLE
        }
    }

    private fun bestRiceParameter(
        residuals: IntArray,
        count: Int,
    ): RiceChoice? {
        var bestParameter = 0
        var bestBits = Long.MAX_VALUE
        for (parameter in 0..MAX_RICE_PARAMETER) {
            val bits = count.estimateRiceBits(residuals, parameter)
            if (bits < bestBits) {
                bestBits = bits
                bestParameter = parameter
            }
        }
        return RiceChoice(bestParameter, bestBits)
    }

    private fun Int.estimateRiceBits(
        residuals: IntArray,
        parameter: Int,
    ): Long {
        var bits = 0L
        for (index in 0 until this) {
            val unsigned = residuals[index].toRiceUnsigned()
            bits += (unsigned ushr parameter) + 1L + parameter
        }
        return bits
    }

    private fun fixedPrediction(samples: IntArray, index: Int, order: Int): Int {
        return when (order) {
            0 -> 0
            1 -> samples[index - 1]
            2 -> 2 * samples[index - 1] - samples[index - 2]
            3 -> 3 * samples[index - 1] - 3 * samples[index - 2] + samples[index - 3]
            4 -> 4 * samples[index - 1] - 6 * samples[index - 2] +
                    4 * samples[index - 3] - samples[index - 4]
            else -> error("Unsupported fixed predictor order: $order")
        }
    }

    private fun OutputStream.writeStreamInfo(
        sampleRate: Int,
        frameCount: Int,
        pcmMd5: ByteArray,
    ) {
        val maxBlockSize = min(MAX_BLOCK_SIZE, frameCount).coerceAtLeast(1)
        val finalBlockSize = (frameCount % MAX_BLOCK_SIZE).takeIf { it > 0 }
        val minBlockSize = min(finalBlockSize ?: maxBlockSize, maxBlockSize)
        write(STREAMINFO_METADATA_HEADER)
        val streamInfo = ByteArrayOutputStream(STREAMINFO_LENGTH)
        streamInfo.writeUInt16(minBlockSize)
        streamInfo.writeUInt16(maxBlockSize)
        streamInfo.writeUInt24(0)
        streamInfo.writeUInt24(0)
        FlacBitWriter(streamInfo).apply {
            writeBits(sampleRate.toLong(), STREAMINFO_SAMPLE_RATE_BITS)
            writeBits((CHANNEL_COUNT_STEREO - 1).toLong(), STREAMINFO_CHANNEL_BITS)
            writeBits((BITS_PER_SAMPLE - 1).toLong(), STREAMINFO_BITS_PER_SAMPLE_BITS)
            writeBits(frameCount.toLong(), STREAMINFO_TOTAL_SAMPLES_BITS)
            alignToByte()
        }
        streamInfo.write(pcmMd5)
        val bytes = streamInfo.toByteArray()
        require(bytes.size == STREAMINFO_LENGTH) { "Invalid FLAC STREAMINFO length." }
        write(bytes)
    }

    private fun readPcm16StereoWavInfo(wavFile: File): Pcm16StereoWavInfo {
        require(wavFile.isFile) { "WAV file does not exist: ${wavFile.absolutePath}" }
        RandomAccessFile(wavFile, "r").use { input ->
            require(input.readAscii(4) == "RIFF") { "WAV file has no RIFF header." }
            input.readLittleEndianUInt()
            require(input.readAscii(4) == "WAVE") { "WAV file has no WAVE header." }

            var sampleRate: Int? = null
            var channelCount: Int? = null
            var bitsPerSample: Int? = null
            var audioFormat: Int? = null
            var dataOffset: Long? = null
            var dataSize: Long? = null

            while (input.filePointer + CHUNK_HEADER_BYTES <= input.length()) {
                val chunkId = input.readAscii(4)
                val chunkSize = input.readLittleEndianUInt()
                val chunkDataStart = input.filePointer
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= PCM_FMT_CHUNK_MIN_BYTES) {
                            "WAV fmt chunk is too small."
                        }
                        audioFormat = input.readLittleEndianUShort()
                        channelCount = input.readLittleEndianUShort()
                        sampleRate = input.readLittleEndianUInt().toInt()
                        input.readLittleEndianUInt()
                        input.readLittleEndianUShort()
                        bitsPerSample = input.readLittleEndianUShort()
                    }
                    "data" -> {
                        dataOffset = chunkDataStart
                        dataSize = chunkSize
                    }
                }
                val paddedChunkSize = chunkSize + (chunkSize and 1L)
                input.seek(chunkDataStart + paddedChunkSize)
            }

            val resolvedSampleRate = sampleRate ?: error("WAV file has no fmt chunk.")
            val resolvedChannelCount = channelCount ?: error("WAV file has no channel count.")
            val resolvedBitsPerSample = bitsPerSample ?: error("WAV file has no bit depth.")
            val resolvedAudioFormat = audioFormat ?: error("WAV file has no audio format.")
            val resolvedDataOffset = dataOffset ?: error("WAV file has no data chunk.")
            val resolvedDataSize = dataSize ?: error("WAV file has no data size.")

            require(resolvedAudioFormat == WAV_FORMAT_PCM) { "Only PCM WAV input is supported." }
            require(resolvedChannelCount == CHANNEL_COUNT_STEREO) { "Only stereo WAV input is supported." }
            require(resolvedBitsPerSample == BITS_PER_SAMPLE) { "Only 16-bit WAV input is supported." }
            require(resolvedDataSize % BYTES_PER_FRAME == 0L) {
                "WAV data size does not align to stereo 16-bit frames."
            }
            require(resolvedDataSize <= Int.MAX_VALUE.toLong() * BYTES_PER_FRAME) {
                "WAV input is too large."
            }
            return Pcm16StereoWavInfo(
                dataOffset = resolvedDataOffset,
                dataSize = resolvedDataSize,
                sampleRate = resolvedSampleRate,
                frameCount = (resolvedDataSize / BYTES_PER_FRAME).toInt(),
            )
        }
    }

    private fun File.pcmMd5(
        info: Pcm16StereoWavInfo,
        shouldCancel: () -> Boolean,
    ): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(PCM_MD5_BUFFER_BYTES)
        inputStream().buffered().use { input ->
            skipFully(input, info.dataOffset)
            var remaining = info.dataSize
            while (remaining > 0L) {
                throwIfCanceled(shouldCancel)
                val count = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (count < 0) error("Unexpected end of WAV PCM data.")
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return digest.digest()
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw CancellationException("FLAC encode canceled.")
        }
    }

    private fun InputStream.readFully(buffer: ByteArray, byteCount: Int) {
        var offset = 0
        while (offset < byteCount) {
            val count = read(buffer, offset, byteCount - offset)
            if (count < 0) error("Unexpected end of WAV PCM data.")
            offset += count
        }
    }

    private fun InputStream.readAscii(length: Int): String {
        return String(readFullyToByteArray(length), Charsets.US_ASCII)
    }

    private fun InputStream.readFullyToByteArray(byteCount: Int): ByteArray {
        val bytes = ByteArray(byteCount)
        readFully(bytes, byteCount)
        return bytes
    }

    private fun InputStream.readRequiredByte(): Int {
        val value = read()
        require(value >= 0) { "Unexpected end of FLAC stream." }
        return value and 0xFF
    }

    private fun InputStream.readUInt16(): Int {
        val high = readRequiredByte()
        val low = readRequiredByte()
        return (high shl 8) or low
    }

    private fun InputStream.readUInt24(): Int {
        val b0 = readRequiredByte()
        val b1 = readRequiredByte()
        val b2 = readRequiredByte()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun skipFully(
        input: InputStream,
        byteCount: Long,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining -= 1L
            } else {
                error("Unexpected end of file while skipping.")
            }
        }
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLittleEndianUShort(): Int {
        val low = read()
        val high = read()
        require(low >= 0 && high >= 0) { "Unexpected end of file." }
        return (low and 0xFF) or ((high and 0xFF) shl 8)
    }

    private fun RandomAccessFile.readLittleEndianUInt(): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) {
            "Unexpected end of file."
        }
        return (b0.toLong() and 0xFF) or
                ((b1.toLong() and 0xFF) shl 8) or
                ((b2.toLong() and 0xFF) shl 16) or
                ((b3.toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.readLittleEndianShort(offset: Int): Int {
        val low = this[offset].toInt() and 0xFF
        val high = this[offset + 1].toInt()
        return ((high shl 8) or low).toShort().toInt()
    }

    private fun ByteArray.readBigEndianLong(offset: Int): Long {
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (this[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeUInt24(value: Int) {
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeFlacUtf8UInt(value: Long) {
        require(value >= 0L) { "FLAC UTF-8 integer must not be negative." }
        val byteCount = when {
            value < (1L shl 7) -> 1
            value < (1L shl 11) -> 2
            value < (1L shl 16) -> 3
            value < (1L shl 21) -> 4
            value < (1L shl 26) -> 5
            value < (1L shl 31) -> 6
            else -> 7
        }
        if (byteCount == 1) {
            write(value.toInt())
            return
        }

        var remaining = value
        val bytes = ByteArray(byteCount)
        for (index in byteCount - 1 downTo 1) {
            bytes[index] = (0x80 or (remaining.toInt() and 0x3F)).toByte()
            remaining = remaining ushr 6
        }
        val prefix = when (byteCount) {
            2 -> 0xC0
            3 -> 0xE0
            4 -> 0xF0
            5 -> 0xF8
            6 -> 0xFC
            else -> 0xFE
        }
        bytes[0] = (prefix or remaining.toInt()).toByte()
        write(bytes)
    }

    private fun FlacBitWriter.writeSignedSample(
        sample: Int,
        bitsPerSample: Int,
    ) {
        require(bitsPerSample in 1..Int.SIZE_BITS) { "Invalid FLAC sample bit depth." }
        require(sample.fitsSignedBits(bitsPerSample)) {
            "FLAC sample does not fit declared bit depth."
        }
        val mask = if (bitsPerSample == Long.SIZE_BITS) {
            -1L
        } else {
            (1L shl bitsPerSample) - 1L
        }
        writeBits(sample.toLong() and mask, bitsPerSample)
    }

    private fun FlacBitWriter.writeRiceSigned(value: Int, parameter: Int) {
        val unsigned = value.toRiceUnsigned()
        val quotient = unsigned ushr parameter
        writeUnaryUnsigned(quotient)
        if (parameter > 0) {
            writeBits(unsigned and ((1L shl parameter) - 1L), parameter)
        }
    }

    private fun FlacBitReader.readSignedSample(bitsPerSample: Int): Int {
        require(bitsPerSample in 1..Int.SIZE_BITS) { "Invalid FLAC sample bit depth." }
        val unsigned = readBits(bitsPerSample)
        val signBit = 1L shl (bitsPerSample - 1)
        val signed = if ((unsigned and signBit) != 0L) {
            unsigned - (1L shl bitsPerSample)
        } else {
            unsigned
        }
        return signed.toInt()
    }

    private fun FlacBitReader.readRiceSigned(parameter: Int): Int {
        val quotient = readUnaryUnsigned()
        val remainder = if (parameter > 0) readBits(parameter) else 0L
        val unsigned = (quotient shl parameter) or remainder
        return if ((unsigned and 1L) == 0L) {
            (unsigned ushr 1).toInt()
        } else {
            -((unsigned ushr 1).toInt()) - 1
        }
    }

    private fun readFlacUtf8UInt(input: InputStream): Long {
        val first = input.readRequiredByte()
        if ((first and 0x80) == 0) return first.toLong()
        val byteCount = when {
            (first and 0xE0) == 0xC0 -> 2
            (first and 0xF0) == 0xE0 -> 3
            (first and 0xF8) == 0xF0 -> 4
            (first and 0xFC) == 0xF8 -> 5
            (first and 0xFE) == 0xFC -> 6
            first == 0xFE -> 7
            else -> error("Invalid FLAC UTF-8 integer.")
        }
        var value = (first and ((1 shl (7 - byteCount)) - 1)).toLong()
        repeat(byteCount - 1) {
            val next = input.readRequiredByte()
            require((next and 0xC0) == 0x80) { "Invalid FLAC UTF-8 continuation byte." }
            value = (value shl 6) or (next and 0x3F).toLong()
        }
        return value
    }

    private fun MessageDigest.updateInterleavedPcm16(
        left: IntArray,
        right: IntArray,
        frameCount: Int,
    ) {
        val buffer = ByteArray(frameCount * BYTES_PER_FRAME)
        var offset = 0
        for (index in 0 until frameCount) {
            val leftSample = left[index]
            val rightSample = right[index]
            buffer[offset++] = (leftSample and 0xFF).toByte()
            buffer[offset++] = ((leftSample ushr 8) and 0xFF).toByte()
            buffer[offset++] = (rightSample and 0xFF).toByte()
            buffer[offset++] = ((rightSample ushr 8) and 0xFF).toByte()
        }
        update(buffer)
    }

    private fun writeInterleavedPcm16(
        left: IntArray,
        right: IntArray,
        frameCount: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        var offset = outputOffset
        for (index in 0 until frameCount) {
            val leftSample = left[index]
            val rightSample = right[index]
            output[offset++] = (leftSample and 0xFF).toByte()
            output[offset++] = ((leftSample ushr 8) and 0xFF).toByte()
            output[offset++] = (rightSample and 0xFF).toByte()
            output[offset++] = ((rightSample ushr 8) and 0xFF).toByte()
        }
    }

    private fun writeFrameIndexFile(
        index: Pcm16StereoFlacFrameIndex,
        indexFile: File,
    ) {
        val parent = indexFile.parentFile
        parent?.mkdirs()
        val tempFile = File(parent ?: File("."), "${indexFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val text = buildString {
            appendLine(FRAME_INDEX_MAGIC)
            appendLine("sampleRate=${index.sampleRate}")
            appendLine("channelCount=${index.channelCount}")
            appendLine("bitsPerSample=${index.bitsPerSample}")
            appendLine("frameCount=${index.frameCount}")
            appendLine("maxBlockSize=${index.maxBlockSize}")
            appendLine("flacBytes=${index.flacBytes}")
            appendLine("pcmMd5Hex=${index.pcmMd5Hex}")
            appendLine("frames=${index.frames.size}")
            appendLine("frameNumber,startPcmFrame,pcmFrameCount,byteOffset,byteCount,channelAssignment")
            index.frames.forEach { frame ->
                append(frame.frameNumber)
                append(',')
                append(frame.startPcmFrame)
                append(',')
                append(frame.pcmFrameCount)
                append(',')
                append(frame.byteOffset)
                append(',')
                append(frame.byteCount)
                append(',')
                append(frame.channelAssignment.channelAssignmentName())
                appendLine()
            }
        }
        try {
            tempFile.writeText(text, Charsets.UTF_8)
            if (indexFile.exists() && !indexFile.delete()) {
                error("Could not replace FLAC frame index: ${indexFile.absolutePath}")
            }
            if (!tempFile.renameTo(indexFile)) {
                tempFile.copyTo(indexFile, overwrite = true)
                tempFile.delete()
            }
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    private fun Pcm16StereoFlacFrameIndex.channelAssignmentSummary(): String {
        return frames
            .groupingBy { frame -> frame.channelAssignment.channelAssignmentName() }
            .eachCount()
            .entries
            .sortedBy { entry -> entry.key }
            .joinToString(";") { entry -> "${entry.key}=${entry.value}" }
    }

    private fun readFrameIndexFile(indexFile: File): Pcm16StereoFlacFrameIndex {
        val lines = indexFile.readLines(Charsets.UTF_8)
        require(lines.firstOrNull() == FRAME_INDEX_MAGIC) { "Unsupported FLAC frame index." }
        var cursor = 1
        fun readValue(key: String): String {
            require(cursor < lines.size) { "Missing FLAC frame index field: $key" }
            val line = lines[cursor++]
            val prefix = "$key="
            require(line.startsWith(prefix)) { "Unexpected FLAC frame index field: $line" }
            return line.removePrefix(prefix)
        }
        val sampleRate = readValue("sampleRate").toInt()
        val channelCount = readValue("channelCount").toInt()
        val bitsPerSample = readValue("bitsPerSample").toInt()
        val frameCount = readValue("frameCount").toInt()
        val maxBlockSize = readValue("maxBlockSize").toInt()
        val flacBytes = readValue("flacBytes").toLong()
        val pcmMd5Hex = readValue("pcmMd5Hex")
        val frameEntryCount = readValue("frames").toInt()
        require(cursor < lines.size) {
            "Missing FLAC frame index table header."
        }
        val tableHeader = lines[cursor++]
        require(
            tableHeader == "frameNumber,startPcmFrame,pcmFrameCount,byteOffset,byteCount" ||
                    tableHeader == "frameNumber,startPcmFrame,pcmFrameCount,byteOffset,byteCount,channelAssignment"
        ) {
            "Missing FLAC frame index table header."
        }
        val frames = ArrayList<Pcm16StereoFlacFrameIndexEntry>(frameEntryCount)
        while (cursor < lines.size) {
            val line = lines[cursor++]
            if (line.isBlank()) continue
            val parts = line.split(',')
            require(parts.size == 5 || parts.size == 6) { "Invalid FLAC frame index row: $line" }
            frames += Pcm16StereoFlacFrameIndexEntry(
                frameNumber = parts[0].toLong(),
                startPcmFrame = parts[1].toLong(),
                pcmFrameCount = parts[2].toInt(),
                byteOffset = parts[3].toLong(),
                byteCount = parts[4].toInt(),
                channelAssignment = parts.getOrNull(5)?.channelAssignmentValue()
                    ?: CHANNEL_ASSIGNMENT_STEREO,
            )
        }
        require(frames.size == frameEntryCount) { "FLAC frame index row count mismatch." }
        require(sampleRate > 0) { "Invalid FLAC frame index sample rate." }
        require(channelCount == CHANNEL_COUNT_STEREO) { "Only stereo FLAC indexes are supported." }
        require(bitsPerSample == BITS_PER_SAMPLE) { "Only 16-bit FLAC indexes are supported." }
        require(frameCount >= 0) { "Invalid FLAC frame index frame count." }
        require(maxBlockSize == MAX_BLOCK_SIZE) { "Unsupported FLAC frame index block size." }
        validateFrameIndexEntries(frames, frameCount)
        return Pcm16StereoFlacFrameIndex(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            frameCount = frameCount,
            maxBlockSize = maxBlockSize,
            flacBytes = flacBytes,
            pcmMd5Hex = pcmMd5Hex,
            frames = frames,
        )
    }

    private fun validateFrameIndexEntries(
        frames: List<Pcm16StereoFlacFrameIndexEntry>,
        totalFrameCount: Int,
    ) {
        var expectedFrameNumber = 0L
        var expectedPcmFrame = 0L
        frames.forEach { frame ->
            require(frame.frameNumber == expectedFrameNumber) { "Unexpected FLAC frame number." }
            require(frame.startPcmFrame == expectedPcmFrame) { "Unexpected FLAC PCM frame start." }
            require(frame.pcmFrameCount > 0) { "Invalid FLAC frame sample count." }
            require(frame.pcmFrameCount <= MAX_BLOCK_SIZE) { "FLAC frame exceeds max block size." }
            require(frame.byteOffset >= FLAC_MAGIC.size + STREAMINFO_METADATA_HEADER.size + STREAMINFO_LENGTH) {
                "Invalid FLAC frame byte offset."
            }
            require(frame.byteCount > 0) { "Invalid FLAC frame byte count." }
            require(
                frame.channelAssignment == CHANNEL_ASSIGNMENT_STEREO ||
                        frame.channelAssignment == CHANNEL_ASSIGNMENT_LEFT_SIDE ||
                        frame.channelAssignment == CHANNEL_ASSIGNMENT_RIGHT_SIDE ||
                        frame.channelAssignment == CHANNEL_ASSIGNMENT_MID_SIDE
            ) {
                "Unsupported FLAC frame channel assignment."
            }
            expectedFrameNumber += 1L
            expectedPcmFrame += frame.pcmFrameCount
        }
        require(expectedPcmFrame == totalFrameCount.toLong()) {
            "FLAC frame index does not cover the full stream."
        }
    }

    private class IndexedFlacPcmReader(
        flacFile: File,
        private val index: Pcm16StereoFlacFrameIndex,
        private val traceSink: ((String) -> Unit)?,
    ) : Pcm16StereoFlacPcmReader {
        private val input = RandomAccessFile(flacFile, "r")
        private val inputStream = RandomAccessFileInputStream(input)
        private val prefetchInput = RandomAccessFile(flacFile, "r")
        private val prefetchInputStream = RandomAccessFileInputStream(prefetchInput)
        private val prefetchExecutor = Executors.newSingleThreadExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "BoomingFlacPrefetch").apply {
                    isDaemon = true
                    priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
                }
            }
        )
        private val prefetchLock = Any()
        private val left = IntArray(MAX_BLOCK_SIZE)
        private val right = IntArray(MAX_BLOCK_SIZE)
        private val prefetchLeft = IntArray(MAX_BLOCK_SIZE)
        private val prefetchRight = IntArray(MAX_BLOCK_SIZE)
        private var pcmBlock = ByteArray(MAX_BLOCK_SIZE * BYTES_PER_FRAME)
        private var currentFrameIndex = -1
        private var currentBlockBytes = 0
        private var pcmBytePosition = 0L
        private var prefetchedBlock: DecodedPcmBlock? = null
        private var prefetchTask: Future<DecodedPcmBlock?>? = null
        private var prefetchTaskFrameIndex = -1
        @Volatile
        private var closed = false

        override val frameCount: Int = index.frameCount
        override val sampleRate: Int = index.sampleRate

        init {
            traceSink?.invoke(
                "indexedOpen success sampleRate=${index.sampleRate} frames=${index.frameCount} " +
                        "indexFrames=${index.frames.size}"
            )
        }

        override fun read(buffer: ByteArray, byteCount: Int): Int {
            if (pcmBytePosition >= totalPcmBytes()) return -1
            var copied = 0
            val shouldTraceReadTiming = shouldTraceIndexedReaderTiming()
            val startedAtNs = if (shouldTraceReadTiming) {
                System.nanoTime()
            } else {
                0L
            }
            val startBytePosition = pcmBytePosition
            while (copied < byteCount && pcmBytePosition < totalPcmBytes()) {
                val frameIndex = frameIndexForPcmByte(pcmBytePosition) ?: break
                ensureFrameDecoded(frameIndex)
                val entry = index.frames[frameIndex]
                val blockStartByte = entry.startPcmFrame * BYTES_PER_FRAME
                val blockOffset = (pcmBytePosition - blockStartByte).toInt()
                val streamBytesRemaining = (totalPcmBytes() - pcmBytePosition)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                val available = min(
                    min(currentBlockBytes - blockOffset, byteCount - copied),
                    streamBytesRemaining,
                )
                if (available <= 0) break
                pcmBlock.copyInto(
                    destination = buffer,
                    destinationOffset = copied,
                    startIndex = blockOffset,
                    endIndex = blockOffset + available,
                )
                copied += available
                pcmBytePosition += available
            }
            traceReadIfNeeded(
                requestedBytes = byteCount,
                copiedBytes = copied,
                startBytePosition = startBytePosition,
                elapsedNs = if (shouldTraceReadTiming) {
                    System.nanoTime() - startedAtNs
                } else {
                    0L
                },
            )
            return if (copied > 0) copied else -1
        }

        override fun seekToPcmByte(bytePosition: Long) {
            pcmBytePosition = bytePosition
                .coerceAtLeast(0L)
                .coerceAtMost(totalPcmBytes())
            currentFrameIndex = -1
            synchronized(prefetchLock) {
                prefetchedBlock = null
                prefetchTask?.cancel(true)
                prefetchTask = null
                prefetchTaskFrameIndex = -1
            }
            frameIndexForPcmByte(pcmBytePosition)?.let(::schedulePrefetch)
            traceSink?.invoke(
                "indexedSeek byte=$pcmBytePosition frame=${pcmBytePosition / BYTES_PER_FRAME} " +
                        "index=${frameIndexForPcmByte(pcmBytePosition)}"
            )
        }

        override fun close() {
            closed = true
            synchronized(prefetchLock) {
                prefetchTask?.cancel(true)
                prefetchTask = null
                prefetchedBlock = null
            }
            prefetchExecutor.shutdownNow()
            input.close()
            prefetchInput.close()
        }

        private fun ensureFrameDecoded(frameIndex: Int) {
            if (currentFrameIndex == frameIndex) return
            val shouldTraceFrameTiming = shouldTraceIndexedFrameDecodeTiming()
            val startedAtNs = if (shouldTraceFrameTiming) {
                System.nanoTime()
            } else {
                0L
            }
            val prefetched = takePrefetchedFrame(frameIndex)
            if (prefetched != null) {
                pcmBlock = prefetched.pcm
                currentFrameIndex = prefetched.frameIndex
                currentBlockBytes = prefetched.byteCount
                traceFrameDecodeIfNeeded(
                    frameIndex = frameIndex,
                    byteOffset = index.frames[frameIndex].byteOffset,
                    elapsedNs = if (shouldTraceFrameTiming) {
                        System.nanoTime() - startedAtNs
                    } else {
                        0L
                    },
                    prefetched = true,
                )
                schedulePrefetch(frameIndex + 1)
                return
            }

            currentBlockBytes = decodeFrameInto(
                frameIndex = frameIndex,
                input = input,
                inputStream = inputStream,
                left = left,
                right = right,
                output = pcmBlock,
            )
            currentFrameIndex = frameIndex
            traceFrameDecodeIfNeeded(
                frameIndex = frameIndex,
                byteOffset = index.frames[frameIndex].byteOffset,
                elapsedNs = if (shouldTraceFrameTiming) {
                    System.nanoTime() - startedAtNs
                } else {
                    0L
                },
                prefetched = false,
            )
            schedulePrefetch(frameIndex + 1)
        }

        private fun decodeFrameInto(
            frameIndex: Int,
            input: RandomAccessFile,
            inputStream: RandomAccessFileInputStream,
            left: IntArray,
            right: IntArray,
            output: ByteArray,
        ): Int {
            val entry = index.frames[frameIndex]
            input.seek(entry.byteOffset)
            val blockFrames = readAndDecodeFrame(
                input = inputStream,
                left = left,
                right = right,
            )
            require(blockFrames == entry.pcmFrameCount) {
                "Decoded FLAC frame size does not match index."
            }
            require(input.filePointer == entry.byteOffset + entry.byteCount) {
                "Decoded FLAC frame byte span does not match index."
            }
            writeInterleavedPcm16(
                left = left,
                right = right,
                frameCount = blockFrames,
                output = output,
                outputOffset = 0,
            )
            return blockFrames * BYTES_PER_FRAME
        }

        private fun takePrefetchedFrame(frameIndex: Int): DecodedPcmBlock? {
            synchronized(prefetchLock) {
                harvestPrefetchLocked()
                val block = prefetchedBlock
                if (block?.frameIndex == frameIndex) {
                    prefetchedBlock = null
                    return block
                }
                if (block != null && block.frameIndex < frameIndex) {
                    prefetchedBlock = null
                }
                if (prefetchTaskFrameIndex <= frameIndex) {
                    prefetchTask?.cancel(true)
                    prefetchTask = null
                    prefetchTaskFrameIndex = -1
                }
                return null
            }
        }

        private fun schedulePrefetch(frameIndex: Int) {
            if (closed || frameIndex !in index.frames.indices) return
            synchronized(prefetchLock) {
                harvestPrefetchLocked()
                if (closed ||
                    currentFrameIndex == frameIndex ||
                    prefetchedBlock?.frameIndex == frameIndex ||
                    (prefetchTaskFrameIndex == frameIndex && prefetchTask != null)
                ) {
                    return
                }
                if (prefetchTask != null) return
                prefetchTaskFrameIndex = frameIndex
                prefetchTask = prefetchExecutor.submit<DecodedPcmBlock?> {
                    if (closed) return@submit null
                    val block = ByteArray(MAX_BLOCK_SIZE * BYTES_PER_FRAME)
                    val byteCount = decodeFrameInto(
                        frameIndex = frameIndex,
                        input = prefetchInput,
                        inputStream = prefetchInputStream,
                        left = prefetchLeft,
                        right = prefetchRight,
                        output = block,
                    )
                    if (closed) {
                        null
                    } else {
                        DecodedPcmBlock(
                            frameIndex = frameIndex,
                            byteCount = byteCount,
                            pcm = block,
                        )
                    }
                }
            }
        }

        private fun harvestPrefetchLocked() {
            val task = prefetchTask ?: return
            if (!task.isDone) return
            runCatching {
                task.get()
            }.onSuccess { block ->
                if (block != null && block.frameIndex > currentFrameIndex) {
                    prefetchedBlock = block
                }
            }.onFailure { error ->
                if (shouldTraceIndexedFrameDecodeTiming()) {
                    traceSink?.invoke(
                        "indexedPrefetch failed frame=$prefetchTaskFrameIndex " +
                                "error=${error.message ?: error::class.java.name}"
                    )
                }
            }
            prefetchTask = null
            prefetchTaskFrameIndex = -1
        }

        private fun traceFrameDecodeIfNeeded(
            frameIndex: Int,
            byteOffset: Long,
            elapsedNs: Long,
            prefetched: Boolean,
        ) {
            if (!shouldTraceIndexedFrameDecodeTiming()) return
            traceSink?.invoke(
                "indexedDecode frame=$frameIndex byteOffset=$byteOffset " +
                        "decodeMs=${elapsedNs / NANOS_PER_MILLISECOND.toFloat()} prefetched=$prefetched"
            )
        }

        private fun traceReadIfNeeded(
            requestedBytes: Int,
            copiedBytes: Int,
            startBytePosition: Long,
            elapsedNs: Long,
        ) {
            if (!shouldTraceIndexedReaderTiming()) return
            val readSeq = ++debugReadSeq
            if (readSeq <= TRACE_INDEXED_INITIAL_READ_COUNT ||
                readSeq % TRACE_INDEXED_READ_INTERVAL == 0L ||
                copiedBytes <= 0
            ) {
                traceSink?.invoke(
                    "indexedRead seq=$readSeq requested=$requestedBytes copied=$copiedBytes " +
                            "startByte=$startBytePosition endByte=$pcmBytePosition " +
                            "readMs=${elapsedNs / NANOS_PER_MILLISECOND.toFloat()}"
                )
            }
        }

        private fun shouldTraceIndexedFrameDecodeTiming(): Boolean {
            return TRACE_INDEXED_FRAME_DECODE_TIMING && traceSink != null
        }

        private fun shouldTraceIndexedReaderTiming(): Boolean {
            return TRACE_INDEXED_READER_TIMING && traceSink != null
        }

        private fun frameIndexForPcmByte(bytePosition: Long): Int? {
            val targetFrame = bytePosition / BYTES_PER_FRAME
            if (targetFrame >= index.frameCount) return null
            var low = 0
            var high = index.frames.lastIndex
            while (low <= high) {
                val mid = (low + high) ushr 1
                val frame = index.frames[mid]
                val start = frame.startPcmFrame
                val end = start + frame.pcmFrameCount
                when {
                    targetFrame < start -> high = mid - 1
                    targetFrame >= end -> low = mid + 1
                    else -> return mid
                }
            }
            return null
        }

        private fun totalPcmBytes(): Long {
            return index.frameCount.toLong() * BYTES_PER_FRAME
        }

        private data class DecodedPcmBlock(
            val frameIndex: Int,
            val byteCount: Int,
            val pcm: ByteArray,
        )

        private var debugReadSeq = 0L
    }

    private class RandomAccessFileInputStream(
        private val input: RandomAccessFile,
    ) : InputStream() {
        override fun read(): Int {
            return input.read()
        }

        override fun read(
            buffer: ByteArray,
            byteOffset: Int,
            byteCount: Int,
        ): Int {
            return input.read(buffer, byteOffset, byteCount)
        }
    }

    private fun Int.toRiceUnsigned(): Long {
        val longValue = toLong()
        return if (longValue >= 0L) {
            longValue shl 1
        } else {
            ((-longValue) shl 1) - 1L
        }
    }

    private fun Int.fitsSignedBits(bits: Int): Boolean {
        val minValue = -(1L shl (bits - 1))
        val maxValue = (1L shl (bits - 1)) - 1L
        return toLong() in minValue..maxValue
    }

    private fun Int.channelAssignmentName(): String {
        return when (this) {
            CHANNEL_ASSIGNMENT_STEREO -> "independent"
            CHANNEL_ASSIGNMENT_LEFT_SIDE -> "left_side"
            CHANNEL_ASSIGNMENT_RIGHT_SIDE -> "right_side"
            CHANNEL_ASSIGNMENT_MID_SIDE -> "mid_side"
            else -> "unknown_$this"
        }
    }

    private fun String.channelAssignmentValue(): Int {
        return when (this) {
            "independent" -> CHANNEL_ASSIGNMENT_STEREO
            "left_side" -> CHANNEL_ASSIGNMENT_LEFT_SIDE
            "right_side" -> CHANNEL_ASSIGNMENT_RIGHT_SIDE
            "mid_side" -> CHANNEL_ASSIGNMENT_MID_SIDE
            else -> toInt()
        }
    }

    private fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(Byte.SIZE_BITS) {
                crc = if ((crc and 0x80) != 0) {
                    (crc shl 1) xor 0x07
                } else {
                    crc shl 1
                } and 0xFF
            }
        }
        return crc
    }

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(Byte.SIZE_BITS) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x8005
                } else {
                    crc shl 1
                } and 0xFFFF
            }
        }
        return crc
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private const val MAX_BLOCK_SIZE = 4096
    private const val CHANNEL_COUNT_STEREO = 2
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_FRAME = CHANNEL_COUNT_STEREO * BYTES_PER_SAMPLE
    private const val WAV_FORMAT_PCM = 1
    private const val PCM_FMT_CHUNK_MIN_BYTES = 16L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val PCM_MD5_BUFFER_BYTES = 256 * 1024
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private const val TRACE_INDEXED_FRAME_DECODE_TIMING = true
    private const val TRACE_INDEXED_READER_TIMING = true
    private const val TRACE_INDEXED_INITIAL_READ_COUNT = 40L
    private const val TRACE_INDEXED_READ_INTERVAL = 200L

    private const val BLOCK_SIZE_CODE_16_BIT = 7
    private const val BLOCK_SIZE_CODE_4096 = 12
    private const val SAMPLE_RATE_FROM_STREAMINFO = 0
    private const val CHANNEL_ASSIGNMENT_STEREO = 1
    private const val CHANNEL_ASSIGNMENT_LEFT_SIDE = 8
    private const val CHANNEL_ASSIGNMENT_RIGHT_SIDE = 9
    private const val CHANNEL_ASSIGNMENT_MID_SIDE = 10
    private const val SAMPLE_SIZE_16_BIT = 4

    private const val SUBFRAME_HEADER_BITS = 8
    private const val VERBATIM_HEADER_BITS = 8
    private const val FIXED_SUBFRAME_HEADER_BITS = 8
    private const val VERBATIM_SUBFRAME_TYPE = 1
    private const val FIXED_SUBFRAME_TYPE_BASE = 8
    private const val MAX_FIXED_PREDICTOR_ORDER = 4

    private const val RESIDUAL_CODING_METHOD_RICE = 0
    private const val RESIDUAL_CODING_METHOD_BITS = 2
    private const val RESIDUAL_PARTITION_ORDER_ZERO = 0
    private const val RESIDUAL_PARTITION_ORDER_BITS = 4
    private const val RICE_PARAMETER_BITS = 4
    private const val RESIDUAL_HEADER_BITS = RESIDUAL_CODING_METHOD_BITS +
            RESIDUAL_PARTITION_ORDER_BITS +
            RICE_PARAMETER_BITS
    private const val MAX_RICE_PARAMETER = 14

    private const val STREAMINFO_LENGTH = 34
    private const val STREAMINFO_SAMPLE_RATE_BITS = 20
    private const val STREAMINFO_CHANNEL_BITS = 3
    private const val STREAMINFO_BITS_PER_SAMPLE_BITS = 5
    private const val STREAMINFO_TOTAL_SAMPLES_BITS = 36
    private const val STREAMINFO_METADATA_BLOCK_TYPE = 0
    private const val STREAMINFO_SAMPLE_RATE_SHIFT = 44
    private const val STREAMINFO_SAMPLE_RATE_MASK = 0xFFFFFL
    private const val STREAMINFO_CHANNEL_SHIFT = 41
    private const val STREAMINFO_CHANNEL_MASK = 0x7L
    private const val STREAMINFO_BITS_PER_SAMPLE_SHIFT = 36
    private const val STREAMINFO_BITS_PER_SAMPLE_MASK = 0x1FL
    private const val STREAMINFO_TOTAL_SAMPLES_MASK = 0xFFFFFFFFFL
    private const val STREAMINFO_MD5_OFFSET = 18
    private const val FRAME_INDEX_MAGIC = "BoomingPcm16StereoFlacIndexV1"
    private const val FRAME_INDEX_EXTENSION = ".idx"

    private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    private val STREAMINFO_METADATA_HEADER = byteArrayOf(0x80.toByte(), 0x00, 0x00, STREAMINFO_LENGTH.toByte())
}

data class Pcm16StereoFlacEncodeResult(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val pcmMd5Hex: String,
    val outputBytes: Long,
    val frameIndexEntries: Int,
    val frameIndexPath: String?,
    val stereoMode: Pcm16StereoFlacStereoMode,
    val channelAssignmentSummary: String,
)

enum class Pcm16StereoFlacStereoMode {
    INDEPENDENT,
    ADAPTIVE_STEREO_DECORRELATION,
}

data class Pcm16StereoFlacVerifyResult(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val pcmMd5Hex: String,
    val fileBytes: Long,
)

data class Pcm16StereoFlacDecodeResult(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val pcm16: ByteArray,
    val pcmMd5Hex: String,
)

data class Pcm16StereoFlacDecodeToFileResult(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val pcmMd5Hex: String,
    val fileBytes: Long,
)

interface Pcm16StereoFlacPcmReader : Closeable {
    val sampleRate: Int
    val frameCount: Int

    fun read(buffer: ByteArray, byteCount: Int): Int
    fun seekToPcmByte(bytePosition: Long)
}

private data class FlacStreamInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val pcmMd5Hex: String,
)

private data class Pcm16StereoWavInfo(
    val dataOffset: Long,
    val dataSize: Long,
    val sampleRate: Int,
    val frameCount: Int,
)

private data class Pcm16StereoFlacFrameIndex(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Int,
    val maxBlockSize: Int,
    val flacBytes: Long,
    val pcmMd5Hex: String,
    val frames: List<Pcm16StereoFlacFrameIndexEntry>,
)

private data class Pcm16StereoFlacFrameIndexEntry(
    val frameNumber: Long,
    val startPcmFrame: Long,
    val pcmFrameCount: Int,
    val byteOffset: Long,
    val byteCount: Int,
    val channelAssignment: Int,
)

private data class EncodedFlacFrame(
    val bytes: ByteArray,
    val channelAssignment: Int,
)

private data class FixedSubframe(
    val order: Int,
    val riceParameter: Int,
    val residuals: IntArray,
    val residualCount: Int,
    val estimatedBits: Long,
)

private data class RiceChoice(
    val parameter: Int,
    val estimatedBits: Long,
)

private class FlacBitWriter(
    private val output: ByteArrayOutputStream,
) {
    private var currentByte = 0
    private var bitCount = 0

    fun writeBits(value: Long, bitCount: Int) {
        require(bitCount >= 0) { "Bit count must not be negative." }
        for (shift in bitCount - 1 downTo 0) {
            writeBit(((value ushr shift) and 1L).toInt())
        }
    }

    fun writeUnaryUnsigned(value: Long) {
        require(value >= 0L) { "Unary value must not be negative." }
        var remaining = value
        while (remaining > 0L) {
            writeBit(0)
            remaining -= 1L
        }
        writeBit(1)
    }

    fun alignToByte() {
        if (bitCount > 0) {
            writeBits(0, Byte.SIZE_BITS - bitCount)
        }
    }

    private fun writeBit(bit: Int) {
        currentByte = (currentByte shl 1) or (bit and 1)
        bitCount += 1
        if (bitCount == Byte.SIZE_BITS) {
            output.write(currentByte)
            currentByte = 0
            bitCount = 0
        }
    }
}

private class FlacBitReader(
    private val input: InputStream,
) {
    private var currentByte = 0
    private var remainingBits = 0

    fun readBits(bitCount: Int): Long {
        require(bitCount >= 0) { "Bit count must not be negative." }
        var value = 0L
        repeat(bitCount) {
            value = (value shl 1) or readBit().toLong()
        }
        return value
    }

    fun readUnaryUnsigned(): Long {
        var value = 0L
        while (readBit() == 0) {
            value += 1L
        }
        return value
    }

    fun alignToByte() {
        remainingBits = 0
    }

    private fun readBit(): Int {
        if (remainingBits == 0) {
            currentByte = input.read()
            require(currentByte >= 0) { "Unexpected end of FLAC bitstream." }
            remainingBits = Byte.SIZE_BITS
        }
        remainingBits -= 1
        return (currentByte ushr remainingBits) and 1
    }
}
