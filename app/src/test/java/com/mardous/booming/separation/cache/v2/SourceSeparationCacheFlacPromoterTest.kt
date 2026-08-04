package com.mardous.booming.separation.cache.v2

import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeTimingReport
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationCacheFlacPromoterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `promotion publishes both flac files and indexes before wav cleanup`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        val promoter = fixture.promoter(nowEpochMs = { 20L })

        val promoted = promoter.promote(completed.cacheKey)
            as SourceSeparationCacheFlacPromotionResult.Completed

        assertTrue(promoted.manifest.output?.stems?.all { it.promotionValidated } == true)
        assertEquals(20L, promoted.manifest.lastAccessedAtEpochMs)
        assertEquals(
            SourceSeparationCacheValidationResult.Valid,
            fixture.store.validateCompletedEntry(promoted.manifest),
        )
        val playback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))
        assertEquals("stem-00.flac", playback.vocalsFile.name)
        assertEquals("stem-01.flac", playback.instrumentalFile.name)
        assertEquals(
            listOf("completed/stem-00.flac.idx", "completed/stem-01.flac.idx"),
            requireNotNull(promoted.manifest.output).stems.map { it.promotedIndexPath },
        )
        assertFalse(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        playback.close()
        assertTrue(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        assertFalse(fixture.store.resolveEntryPath(completed.cacheKey, "completed/stem-00.wav").exists())
        assertEquals(
            SourceSeparationCacheValidationResult.Valid,
            fixture.store.validateCompletedEntry(
                requireNotNull(fixture.store.readManifest(completed.cacheKey)),
            ),
        )
    }

    @Test
    fun `real promotion publishes indexes readable by the playback decoder`() {
        val expectedPcm = listOf(
            testPcm16Stereo(frameCount = TEST_FRAME_COUNT, seed = 17),
            testPcm16Stereo(frameCount = TEST_FRAME_COUNT, seed = 53),
        )
        val fixture = fixture()
        val completed = fixture.completedManifest(expectedPcm)

        val promoted = fixture.realPromoter().promote(completed.cacheKey)
            as SourceSeparationCacheFlacPromotionResult.Completed

        requireNotNull(promoted.manifest.output).stems.forEach { stem ->
            val flac = fixture.store.resolveEntryPath(
                completed.cacheKey,
                requireNotNull(stem.promotedPath),
            )
            val index = Pcm16StereoFlacEncoder.frameIndexFileFor(flac)
            assertEquals(
                fixture.store.relativeEntryPath(completed.cacheKey, index),
                stem.promotedIndexPath,
            )
            assertTrue(index.isFile)

            val trace = mutableListOf<String>()
            val reader = requireNotNull(
                Pcm16StereoFlacEncoder.openIndexedPcmReader(flac, trace::add),
            )
            reader.use {
                assertEquals(TEST_FRAME_COUNT, reader.frameCount)

                val expected = expectedPcm[stem.order]
                val actual = ByteArray(expected.size)
                assertEquals(actual.size, reader.read(actual, actual.size))
                assertArrayEquals(expected, actual)

                val seekByte = (FLAC_BLOCK_FRAME_COUNT - 3) * PCM16_STEREO_BYTES_PER_FRAME
                val seekResult = ByteArray(8 * PCM16_STEREO_BYTES_PER_FRAME)
                reader.seekToPcmByte(seekByte.toLong())
                assertEquals(seekResult.size, reader.read(seekResult, seekResult.size))
                assertArrayEquals(
                    expected.copyOfRange(seekByte, seekByte + seekResult.size),
                    seekResult,
                )
            }
            assertTrue(trace.any { it.startsWith("indexedOpen success") })
            assertFalse(trace.any { "missingIndex" in it })
        }

        val playback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))
        val mixTrace = mutableListOf<String>()
        val processor = SourceSeparationMixAudioProcessor().apply {
            debugTraceSink = mixTrace::add
        }
        try {
            processor.enable(
                vocalsFile = playback.vocalsFile,
                instrumentalFile = playback.instrumentalFile,
                positionMs = 0L,
            )
            awaitCondition { mixTrace.count { "indexedOpen success" in it } == 2 }
        } finally {
            processor.disable()
            playback.close()
        }
        assertEquals(2, mixTrace.count { "indexedOpen success" in it })
        assertFalse(mixTrace.any { "fallbackWholeFileDecode" in it })
    }

    @Test
    fun `indexed playback rejects a frame crc failure`() {
        val expectedPcm = testPcm16Stereo(frameCount = TEST_FRAME_COUNT, seed = 17)
        val fixture = fixture()
        val completed = fixture.completedManifest(listOf(expectedPcm, expectedPcm))
        fixture.realPromoter().promote(completed.cacheKey)
        val flac = fixture.store.resolveEntryPath(
            completed.cacheKey,
            requireNotNull(fixture.store.readManifest(completed.cacheKey))
                .output!!.stems.first().promotedPath!!,
        )
        val index = Pcm16StereoFlacEncoder.frameIndexFileFor(flac)
        val firstFrameOffset = index.readLines()
            .first { line -> line.startsWith("0,") }
            .split(',')[3]
            .toLong()
        RandomAccessFile(flac, "rw").use { file ->
            file.seek(firstFrameOffset + 8L)
            file.write(file.read().xor(0x01))
        }

        val reader = requireNotNull(Pcm16StereoFlacEncoder.openIndexedPcmReader(flac))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                reader.read(ByteArray(FLAC_BLOCK_FRAME_COUNT * PCM16_STEREO_BYTES_PER_FRAME), 1)
            }
        } finally {
            reader.close()
        }
    }

    @Test
    fun `promotion publishes new playback while existing wav playback remains leased`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        val wavPlayback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))

        val promoted = fixture.promoter().promote(completed.cacheKey)
            as SourceSeparationCacheFlacPromotionResult.Completed
        assertEquals("stem-00.wav", wavPlayback.vocalsFile.name)
        assertTrue(wavPlayback.vocalsFile.isFile)

        val flacPlayback = requireNotNull(
            fixture.repository.openCompletedCache(promoted.manifest.cacheKey),
        )
        assertEquals("stem-00.flac", flacPlayback.vocalsFile.name)
        assertTrue(flacPlayback.vocalsFile.isFile)
        assertFalse(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))

        flacPlayback.close()
        assertFalse(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        wavPlayback.close()
        assertTrue(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        assertFalse(wavPlayback.vocalsFile.exists())
        assertTrue(flacPlayback.vocalsFile.isFile)
    }

    @Test
    fun `failed promotion leaves the completed wav manifest authoritative`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        var encoded = 0
        val promoter = fixture.promoter { wav, flac, _, _, _ ->
            encoded += 1
            if (encoded == 2) error("injected encoder failure")
            flac.writeText(wav.readText())
            val index = Pcm16StereoFlacEncoder.frameIndexFileFor(flac).apply {
                writeText("index")
            }
            SourceSeparationCacheEncodedFlac(flac, index)
        }

        assertThrows(IllegalStateException::class.java) {
            promoter.promote(completed.cacheKey)
        }

        val unchanged = requireNotNull(fixture.store.readManifest(completed.cacheKey))
        assertTrue(unchanged.output?.stems?.none { it.promotionValidated } == true)
        assertEquals(SourceSeparationCacheValidationResult.Valid, fixture.store.validateCompletedEntry(unchanged))
        assertFalse(
            fixture.store.resolveEntryPath(
                completed.cacheKey,
                SourceSeparationCacheFlacPromoter.PROMOTION_STAGING_DIRECTORY,
            ).exists(),
        )
        assertFalse(fixture.repository.isLeased(completed.cacheKey))
    }

    @Test
    fun `canceled promotion releases promotion lease and remains retryable`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        var shouldCancel = false
        val promoter = fixture.promoter { wav, flac, _, _, _ ->
            flac.writeText(wav.readText())
            val index = Pcm16StereoFlacEncoder.frameIndexFileFor(flac).apply {
                writeText("index")
            }
            shouldCancel = true
            SourceSeparationCacheEncodedFlac(flac, index)
        }

        assertThrows(CancellationException::class.java) {
            promoter.promote(completed.cacheKey) { shouldCancel }
        }

        assertFalse(fixture.repository.isLeased(completed.cacheKey))
        assertTrue(
            fixture.promoter().promote(completed.cacheKey) is
                SourceSeparationCacheFlacPromotionResult.Completed,
        )
    }

    private fun fixture(): PromotionFixture {
        val store = SourceSeparationCacheStore(
            SourceSeparationCacheRoot(
                temporary.newFolder().absoluteFile,
                SourceSeparationCacheRootLocation.InternalCache,
            ),
            nowEpochMs = { 10L },
        )
        val repository = SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationCacheModelAvailabilityProvider {
                SourceSeparationCacheModelAvailability.InstalledExact
            },
            nowEpochMs = { 10L },
        )
        return PromotionFixture(
            store = store,
            repository = repository,
            coordinator = SourceSeparationCacheRunCoordinator(store, repository) { 10L },
        )
    }

    private data class PromotionFixture(
        val store: SourceSeparationCacheStore,
        val repository: SourceSeparationModelAwareCacheRepository,
        val coordinator: SourceSeparationCacheRunCoordinator,
    ) {
        fun promoter(
            nowEpochMs: () -> Long = { 10L },
            encoder: SourceSeparationCacheFlacEncoder = SourceSeparationCacheFlacEncoder {
                    wav, flac, _, _, _ ->
                flac.writeText("flac:${wav.readText()}")
                val index = Pcm16StereoFlacEncoder.frameIndexFileFor(flac).apply {
                    writeText("index")
                }
                SourceSeparationCacheEncodedFlac(flac, index)
            },
        ) = SourceSeparationCacheFlacPromoter(
            store = store,
            repository = repository,
            encoder = encoder,
            nowEpochMs = nowEpochMs,
        )

        fun realPromoter(
            nowEpochMs: () -> Long = { 10L },
        ) = SourceSeparationCacheFlacPromoter(
            store = store,
            repository = repository,
            nowEpochMs = nowEpochMs,
        )

        fun completedManifest(
            stemPcm16: List<ByteArray>? = null,
        ): SourceSeparationCacheManifest {
            require(
                stemPcm16 == null ||
                    (stemPcm16.size == 2 &&
                        stemPcm16.all {
                            it.size == TEST_FRAME_COUNT * PCM16_STEREO_BYTES_PER_FRAME
                        }),
            ) {
                "Real FLAC fixtures must contain two complete PCM16 stereo stems."
            }
            val contract = SourceSeparationCacheContractSnapshot.fromOfficial(
                catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" },
            )
            val request = SourceSeparationCacheRunRequest(
                identity = contract.identity(sourceIdentity()),
                contract = contract,
                song = SourceSeparationCacheSongLocator(
                    songId = 42L,
                    mediaUri = "content://media/42",
                    filePath = "/music/song.flac",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                ),
                sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(1_024L, 50L, 2_000L),
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                backgroundPolicy =
                    SourceSeparationExecutionRunClass.PlaybackDemandWindow.backgroundPolicy,
                tryGpu = false,
                gpuRuntimeIdentity = null,
                gpuFallbackLatch = null,
            )
            val run = (coordinator.begin(request) as SourceSeparationCacheRunStart.Ready).run
            val vocals = File(run.workDirectory, "vocals.wav").apply {
                stemPcm16?.get(0)?.let { writePcm16StereoWav(it) } ?: writeText("vocals")
            }
            val instrumental = File(run.workDirectory, "instrumental.wav").apply {
                stemPcm16?.get(1)?.let { writePcm16StereoWav(it) } ?: writeText("instrumental")
            }
            val timing = File(run.workDirectory, "timing.txt").apply { writeText("timing") }
            val plan = SourceSeparationSegmentPlan.build(
                rangeStartFrame = 0,
                rangeEndFrame = 88_200,
                sampleRate = 44_100,
                generationSize = 44_100,
                trim = 1_024,
                chunkSize = 46_148,
                defaultState = SourceSeparationSegmentState.Ready,
            )
            plan.segments.forEach { segment ->
                segment.stems.map { it.path }.forEach { path ->
                    store.resolveEntryPath(run.identity.cacheKey, path).apply {
                        parentFile?.mkdirs()
                        writeText(path)
                    }
                }
            }
            val preparation = MdxRangePreparation(
                vocals,
                instrumental,
                timing,
                0L,
                2_000L,
                88_200,
                2,
                run.identity.source.audioFingerprint,
                88_200,
                44_100,
                2,
                44_100,
                plan,
            )
            coordinator.updatePreparation(run, preparation)
            val executionProfile = catalog.contracts
                .single { it.modelId == "uvr_mdxnet_3_9662" }
                .toMdxExecutionProfile(catalog.runtimeQualifications)
            val runtime = MdxRuntimeDiagnostics(
                "fake-litert",
                MdxInferenceBackend.LiteRtCpu,
                4,
                "test",
            )
            val decode = MdxSourceDecodeDiagnostics(
                MdxSourceDecodeMode.Window,
                "test",
                "audio/flac",
                44_100,
                2,
                88_200,
                88_200,
                null,
            )
            val timingReport = MdxRangeTimingReport(
                2.0,
                2,
                1_000L,
                MdxRuntimeSettings(),
                runtime,
                executionProfile,
                decode,
                emptyMap(),
            )
            return coordinator.complete(
                run,
                MdxRangeSeparationResult(
                    vocals,
                    instrumental,
                    timing,
                    0L,
                    2_000L,
                    88_200,
                    2,
                    1_000L,
                    run.identity.source.audioFingerprint,
                    88_200,
                    44_100,
                    2,
                    44_100,
                    plan,
                    timingReport,
                    MdxRuntimeSettings(),
                    runtime,
                    executionProfile,
                    decode,
                ),
            )
        }

        private fun sourceIdentity() = SourceSeparationCacheSourceIdentity(
            "encoded-samples-v1:${"a".repeat(64)}",
            100L,
            1_024L,
            "audio/flac",
            44_100,
            2,
            2_000_000L,
        )
    }

    companion object {
        private const val TEST_FRAME_COUNT = 88_200
        private const val FLAC_BLOCK_FRAME_COUNT = 4_096
        private const val PCM16_STEREO_BYTES_PER_FRAME = 4

        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val resource = requireNotNull(
                SourceSeparationCacheFlacPromoterTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH),
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}

private fun File.writePcm16StereoWav(pcm16: ByteArray) {
    WavFileWriter(
        file = this,
        sampleRate = 44_100,
        channelCount = 2,
    ).use { writer ->
        writer.writePcm16(pcm16)
    }
}

private fun testPcm16Stereo(frameCount: Int, seed: Int): ByteArray {
    val output = ByteArray(frameCount * 4)
    var offset = 0
    repeat(frameCount) { frame ->
        val left = (frame * 251 + seed * 97).toShort().toInt()
        val right = (frame * 131 + seed * 193).toShort().toInt()
        output[offset++] = left.toByte()
        output[offset++] = (left ushr 8).toByte()
        output[offset++] = right.toByte()
        output[offset++] = (right ushr 8).toByte()
    }
    return output
}

private fun awaitCondition(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(2L)
    }
    assertTrue("Timed out waiting for asynchronous playback setup.", condition())
}
