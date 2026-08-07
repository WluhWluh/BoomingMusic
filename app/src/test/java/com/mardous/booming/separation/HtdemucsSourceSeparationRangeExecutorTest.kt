package com.mardous.booming.separation

import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
import com.mardous.booming.separation.model.HtdemucsTrackInferenceSession
import com.mardous.booming.separation.model.HtdemucsTrackSource
import com.mardous.booming.separation.model.HtdemucsWindowStemSet
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtdemucsSourceSeparationRangeExecutorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `product executor maps release session output to generic cache contracts`() {
        val source = FakeSource()
        val session = FakeSession()
        val executor = HtdemucsSourceSeparationRangeExecutor(
            sourceFactory = HtdemucsProductSourceFactory {
                HtdemucsPreparedTrackSource(source, diagnostics(), FINGERPRINT)
            },
            sessionFactory = HtdemucsProductSessionFactory { session },
        )
        val root = temporary.newFolder("executor")
        var preparedStemIds = emptyList<String>()

        val result = executor.separate(
            request(root).copy(
                onPrepared = { preparation ->
                    preparedStemIds = preparation.stemFiles.map { it.stemId.value }
                },
            ),
        )

        assertEquals(STEMS, preparedStemIds)
        assertEquals(STEMS, result.completion.stemFiles.map { it.stemId.value })
        assertEquals("htdemucs-cpu-fp32-v1", result.completion.runtimeRecord.runtimeProfileId)
        assertEquals("LiteRtCpu", result.completion.runtimeRecord.backend)
        assertEquals(FINGERPRINT, result.completion.sourceAudioFingerprint)
        assertTrue(session.closed)
    }

    @Test
    fun `source fingerprint mismatch rejects before creating a model session`() {
        var sessionCreated = false
        val executor = HtdemucsSourceSeparationRangeExecutor(
            sourceFactory = HtdemucsProductSourceFactory {
                HtdemucsPreparedTrackSource(FakeSource(), diagnostics(), "encoded-samples-v1:${"b".repeat(64)}")
            },
            sessionFactory = HtdemucsProductSessionFactory {
                sessionCreated = true
                FakeSession()
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            executor.separate(request(temporary.newFolder("mismatch")))
        }
        assertTrue(!sessionCreated)
    }

    private fun request(root: java.io.File) = HtdemucsSourceSeparationRangeRequest(
        sourceUri = "content://media/42",
        displayName = "Song",
        installedModel = SourceSeparationInstalledMultiStemModel(
            modelId = "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            displayName = "HTDemucs",
            modelFile = root.resolve("model.tflite"),
            sidecarFile = root.resolve("model.tflite.json"),
            modelByteSize = 1L,
            modelSha256 = "c".repeat(64),
            contractId = "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0@1",
            pipelineId = "booming-ss-htdemucs-neural-core",
            installedAtEpochMs = 0L,
        ),
        workDirectory = root.resolve("work"),
        segmentsDirectory = root.resolve("segments"),
        expectedSourceAudioFingerprint = FINGERPRINT,
        windowDecodeEnabled = true,
    )

    private fun diagnostics() = MdxSourceDecodeDiagnostics(
        mode = MdxSourceDecodeMode.Window,
        profile = "test",
        mimeType = "audio/flac",
        sampleRate = 44_100,
        channelCount = 2,
        sourceFrameCount = TRACK_FRAMES,
        outputFrameCount = TRACK_FRAMES,
        fallbackReason = null,
    )

    private class FakeSource : HtdemucsTrackSource {
        override val frameCount = TRACK_FRAMES

        override fun readPlanarStereo(
            startFrame: Int,
            frameCount: Int,
            shouldCancel: () -> Boolean,
        ): FloatArray = FloatArray(frameCount * 2) { index ->
            val local = index % frameCount
            val sourceFrame = startFrame + local
            if (sourceFrame in 0 until this.frameCount) {
                ((sourceFrame * 13L + index / frameCount) % 127L).toFloat() / 127f
            } else {
                0f
            }
        }
    }

    private class FakeSession : HtdemucsTrackInferenceSession {
        override val orderedStemIds = STEMS
        var closed = false

        override fun runNormalizedWindow(
            normalizedPlanarStereo: FloatArray,
            shouldCancel: () -> Boolean,
        ) = HtdemucsWindowStemSet(
            orderedStemIds = orderedStemIds,
            planarSamples = FloatArray(
                STEMS.size * 2 * HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            ),
            samplesPerStem = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        )

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val TRACK_FRAMES = 4_096
        const val FINGERPRINT =
            "encoded-samples-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val STEMS = listOf("drums", "bass", "other", "vocals")
    }
}
