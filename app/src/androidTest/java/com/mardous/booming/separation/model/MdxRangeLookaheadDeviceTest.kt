package com.mardous.booming.separation.model

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdxRangeLookaheadDeviceTest {
    @Test
    fun stagedLookaheadOverlapsAndRevalidatesPlaybackPriority() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mdx-range-lookahead-test").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val config = MdxDspConfig(
            sampleRate = 8_000,
            nFft = 32,
            hopLength = 32,
            dimF = 16,
            dimTPower = 3,
        )
        val inputFile = File(root, "input.wav")
        writeSegmentedInput(inputFile, config, segmentCount = SEGMENT_COUNT)
        val modelFile = File(root, "test-model.tflite").apply { writeBytes(byteArrayOf(1)) }
        val profile = profile(config, modelFile.name)
        val session = RecordingStagedSession(config)
        val playbackProviderCall = AtomicInteger()
        val runningSegments = Collections.synchronizedList(mutableListOf<Int>())

        val result = MdxRangeSeparator(context, config).separate(
            uri = Uri.fromFile(inputFile),
            outputDir = File(root, "output"),
            segmentOutputDir = File(root, "segments"),
            displayName = inputFile.name,
            playbackPositionMsProvider = {
                when (playbackProviderCall.getAndIncrement()) {
                    0 -> segmentStartMs(config, 0)
                    1 -> segmentStartMs(config, 1)
                    else -> segmentStartMs(config, 7)
                }
            },
            playbackReadyWindowCountProvider = { 2 },
            execution = MdxSeparationExecution(
                artifact = MdxModelArtifact(
                    file = modelFile,
                    byteSize = modelFile.length(),
                    sha256 = "a".repeat(64),
                ),
                profile = profile,
            ),
            sessionProvider = object : MdxInferenceSessionProvider {
                override fun acquire(
                    artifact: MdxModelArtifact,
                    profile: MdxExecutionProfile,
                    runtimeSettings: MdxRuntimeSettings,
                ) = MdxInferenceSessionLease(session, session::close)
            },
            onSegmentStateChanged = { index, state ->
                if (state == SourceSeparationSegmentState.Running) runningSegments += index
            },
        )

        assertEquals(SEGMENT_COUNT, result.windowCount)
        assertTrue(session.firstInvocationEntered.await(1, TimeUnit.SECONDS))
        assertTrue(session.firstInvocationSawLookahead.get())
        assertEquals(listOf(0, 7), runningSegments.take(2))
        assertEquals(0, markerSegment(session.invokedMarkers[0]))
        assertEquals(7, markerSegment(session.invokedMarkers[1]))
        assertTrue(session.discardedMarkers.any { markerSegment(it) == 1 })
        assertEquals(SEGMENT_COUNT, session.invokedMarkers.size)
        assertTrue(session.closed)
    }

    private class RecordingStagedSession(
        private val config: MdxDspConfig,
    ) : MdxInferenceSession, MdxWaveformInferenceSession {
        private val prepared = arrayOfNulls<Array<FloatArray>>(2)
        private val firstInvocation = AtomicInteger()
        val firstInvocationEntered = CountDownLatch(1)
        private val firstLookaheadPrepared = CountDownLatch(1)
        val firstInvocationSawLookahead = java.util.concurrent.atomic.AtomicBoolean(false)
        val invokedMarkers = Collections.synchronizedList(mutableListOf<Float>())
        val discardedMarkers = Collections.synchronizedList(mutableListOf<Float>())
        @Volatile
        var closed = false
            private set

        override val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "Range lookahead fake",
            backend = MdxInferenceBackend.LiteRtCpu,
            cpuThreads = 1,
            detail = "test",
        )
        override val waveformSlotCount = 2
        override val waveformDspImplementationId = "range-lookahead-fake"
        override val supportsStagedWaveformExecution = true

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray = error("Tensor inference is not expected.")

        override fun prepareWaveform(
            waveform: Array<FloatArray>,
            slot: Int,
            shouldCancel: () -> Boolean,
        ) {
            synchronized(this) {
                prepared[slot] = waveform.map(FloatArray::copyOf).toTypedArray()
            }
            if (slot == 1 && firstInvocationEntered.count == 0L) {
                firstLookaheadPrepared.countDown()
            }
        }

        override fun invokePreparedWaveform(slot: Int, shouldCancel: () -> Boolean) {
            val marker = synchronized(this) {
                checkNotNull(prepared[slot])[0][config.trim]
            }
            invokedMarkers += marker
            if (firstInvocation.getAndIncrement() == 0) {
                firstInvocationEntered.countDown()
                firstInvocationSawLookahead.set(
                    firstLookaheadPrepared.await(2, TimeUnit.SECONDS),
                )
                check(firstInvocationSawLookahead.get()) {
                    "The first invocation did not overlap slot 1 preparation."
                }
            }
        }

        override fun readPreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> = synchronized(this) {
            checkNotNull(prepared[slot]).map(FloatArray::copyOf).toTypedArray()
        }

        override fun discardPreparedWaveform(slot: Int) {
            synchronized(this) {
                prepared[slot]?.let { discardedMarkers += it[0][config.trim] }
                prepared[slot] = null
            }
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val SEGMENT_COUNT = 8

        fun profile(config: MdxDspConfig, modelFileName: String): MdxExecutionProfile {
            val shape = listOf(1, MdxDspConfig.STEM_COMPLEX_CHANNELS, config.dimF, config.dimT)
            return MdxExecutionProfile(
                profileId = "range-lookahead-test",
                displayName = "Range lookahead test",
                outputTag = "test",
                modelFormat = MdxModelFormat.Tflite,
                inputTensor = MdxTensorSpec(
                    name = "input",
                    shape = shape,
                    layout = MdxTensorLayout.Nchw,
                    dataType = MdxTensorDataType.Float32,
                ),
                outputTensor = MdxTensorSpec(
                    name = "output",
                    shape = shape,
                    layout = MdxTensorLayout.Nchw,
                    dataType = MdxTensorDataType.Float32,
                ),
                dspConfig = config,
                modelOutputScale = 1f,
                modelOutputStem = MdxStem.VOCALS,
                pipelineId = "range-lookahead-test",
                pipelineVersion = 1,
                expectedFileName = modelFileName,
                expectedByteSize = 1,
                expectedSha256 = "a".repeat(64),
            )
        }

        fun writeSegmentedInput(
            file: File,
            config: MdxDspConfig,
            segmentCount: Int,
        ) {
            val pcm = ByteArray(config.generationSize * segmentCount * 4)
            var offset = 0
            repeat(segmentCount) { segment ->
                val value = ((segment + 1) * 1_000).toShort().toInt()
                repeat(config.generationSize) {
                    repeat(MdxDspConfig.STEREO_CHANNELS) {
                        pcm[offset++] = (value and 0xff).toByte()
                        pcm[offset++] = ((value ushr 8) and 0xff).toByte()
                    }
                }
            }
            WavFileWriter(file, config.sampleRate, MdxDspConfig.STEREO_CHANNELS).use {
                it.writePcm16(pcm)
            }
        }

        fun segmentStartMs(config: MdxDspConfig, index: Int): Long =
            index.toLong() * config.generationSize * 1_000L / config.sampleRate

        fun markerSegment(marker: Float): Int =
            ((marker * 32_768f).roundToInt() / 1_000) - 1
    }
}
