package com.mardous.booming.separation

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRoot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRootLocation
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCompletion
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailabilityProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRuntimeRecord
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.contract.StemId
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtdemucsSourceSeparationEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `engine publishes completed release model through ordered cache coordinator`() {
        val fixture = fixture()
        val result = fixture.engine().separate(fixture.input, fixture.model, fixture.preflight)

        assertTrue(result is HtdemucsSourceSeparationEngineResult.Completed)
        val manifest = (result as HtdemucsSourceSeparationEngineResult.Completed).manifest
        assertEquals(listOf("drums", "bass", "other", "vocals"), manifest.output!!.stems.map { it.stemId.value })
    }

    @Test
    fun `active model supersession pauses and records the specific lifecycle reason`() {
        val fixture = fixture(throwOnSeparate = SourceSeparationPausedException(
            pauseReason = SourceSeparationPauseReason.ActiveModelSuperseded,
        ))

        val error = assertThrows(SourceSeparationPausedException::class.java) {
            fixture.engine().separate(fixture.input, fixture.model, fixture.preflight)
        }

        assertEquals(SourceSeparationPauseReason.ActiveModelSuperseded, error.pauseReason)
        val journal = requireNotNull(fixture.store.readRunJournal(fixture.identity.cacheKey))
        assertEquals(SourceSeparationCacheRunJournalLifecycle.Paused, journal.lifecycle)
        assertEquals(
            SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
            journal.transitions.last().type,
        )
    }

    @Test
    fun `user cancellation is not recorded as a pause`() {
        val fixture = fixture(throwOnSeparate = CancellationException("test cancel"))

        assertThrows(CancellationException::class.java) {
            fixture.engine().separate(fixture.input, fixture.model, fixture.preflight)
        }

        val journal = requireNotNull(fixture.store.readRunJournal(fixture.identity.cacheKey))
        assertEquals(SourceSeparationCacheRunJournalLifecycle.Canceled, journal.lifecycle)
        assertEquals(SourceSeparationCacheRunTransitionType.UserCanceled, journal.transitions.last().type)
    }

    @Test
    fun `paused partial cache is retained until the next run atomically restarts it`() {
        val fixture = fixture()
        fixture.throwAfterPreparation = SourceSeparationPausedException()
        assertThrows(SourceSeparationPausedException::class.java) {
            fixture.engine().separate(fixture.input, fixture.model, fixture.preflight)
        }
        val paused = requireNotNull(fixture.store.readManifest(fixture.identity.cacheKey))
        assertEquals(SourceSeparationSegmentState.Ready, paused.segmentPlan!!.segments.single().state)
        val oldSegment = fixture.store.resolveEntryPath(
            fixture.identity.cacheKey,
            paused.segmentPlan!!.segments.single().stems.first().path,
        )
        oldSegment.writeText("stale-partial")
        fixture.throwAfterPreparation = null
        val observed = mutableListOf<SourceSeparationSegmentState>()

        val result = fixture.engine().separate(
            fixture.input,
            fixture.model,
            fixture.preflight,
            onSegmentStateChanged = { _, state -> observed += state },
        )

        assertTrue(result is HtdemucsSourceSeparationEngineResult.Completed)
        assertTrue(observed.none { it == SourceSeparationSegmentState.Queued })
        assertEquals(SourceSeparationSegmentState.Ready, observed.last())
        assertEquals("drums", oldSegment.readText())
    }

    private fun fixture(throwOnSeparate: Throwable? = null): Fixture {
        val root = temporary.newFolder("cache")
        val store = SourceSeparationCacheStore(
            SourceSeparationCacheRoot(root, SourceSeparationCacheRootLocation.InternalCache),
        )
        val repository = SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationCacheModelAvailabilityProvider {
                SourceSeparationCacheModelAvailability.InstalledExact
            },
        )
        val coordinator = SourceSeparationCacheRunCoordinator(store, repository)
        val model = installedModel(root)
        val input = SourceSeparationModelAwareSongInput(
            sourceUri = "content://media/42",
            displayName = "Song",
            song = SourceSeparationCacheSongLocator(42L, "content://media/42", "/music/song.flac", "Song", "Artist", "Album"),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(1_024L, 1L, 7_800L),
        )
        val source = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 1_024L,
            encodedByteCount = 1_024L,
            mimeType = "audio/flac",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 7_800_000L,
        )
        val executable = model.sidecarFile.bufferedReader().use {
            SourceSeparationMultiTensorExecutableContractLoader.load(it.readText())
        }
        val identity = com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
            .fromMultiTensor(executable).identity(source, HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID)
        return Fixture(
            store, repository, coordinator, model, input,
            SourceSeparationCacheSourcePreflight(source, 0L), identity,
            throwOnSeparate,
        )
    }

    private fun installedModel(root: File): SourceSeparationInstalledMultiStemModel {
        val serialized = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "source-separation/research-contracts/htdemucs-4s-official-base-fp32.json",
        )).bufferedReader().use { it.readText() }
        val executable = SourceSeparationMultiTensorExecutableContractLoader.load(serialized)
        val sidecar = File(root, "${executable.artifact.fileName}.json").apply {
            writeText(serialized)
        }
        val model = File(root, executable.artifact.fileName).apply {
            parentFile!!.mkdirs()
            RandomAccessFile(this, "rw").use { it.setLength(executable.artifact.byteSize) }
        }
        return SourceSeparationInstalledMultiStemModel(
            modelId = executable.modelContract.modelId,
            displayName = executable.modelContract.displayName,
            modelFile = model,
            sidecarFile = sidecar,
            modelByteSize = executable.artifact.byteSize,
            modelSha256 = executable.artifact.sha256,
            contractId = executable.modelContract.contractId,
            pipelineId = executable.modelContract.pipelineContract.pipelineId,
            installedAtEpochMs = 0L,
        )
    }

    private class Fixture(
        val store: SourceSeparationCacheStore,
        val repository: SourceSeparationModelAwareCacheRepository,
        val coordinator: SourceSeparationCacheRunCoordinator,
        val model: SourceSeparationInstalledMultiStemModel,
        val input: SourceSeparationModelAwareSongInput,
        val preflight: SourceSeparationCacheSourcePreflight,
        val identity: com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity,
        private var throwOnSeparate: Throwable?,
    ) {
        var throwAfterPreparation: Throwable? = null

        fun engine() = HtdemucsSourceSeparationEngine(
            coordinator = coordinator,
            rangeExecutor = HtdemucsSourceSeparationRangeExecutorContract { request ->
                throwOnSeparate?.let { throw it }
                val stemIds = listOf("drums", "bass", "other", "vocals")
                val stemFiles = stemIds.mapIndexed { index, id ->
                    com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunStemFile(
                        StemId(id),
                        File(request.workDirectory, "stem-%02d.wav".format(index)).apply { writeText(id) },
                    )
                }
                val plan = SourceSeparationSegmentPlan.build(
                    0, 343_980, 44_100, 343_980, 0, 343_980,
                    stemIds.map(::StemId),
                    SourceSeparationSegmentState.Ready,
                )
                plan.segments.single().stems.forEach { stem ->
                    File(request.segmentsDirectory.parentFile, stem.path).apply {
                        parentFile!!.mkdirs()
                        writeText(stem.stemId.value)
                    }
                }
                val preparation = com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunPreparation(
                    stemFiles, null, 343_980, 44_100, 1, request.expectedSourceAudioFingerprint, plan,
                )
                request.onPrepared(preparation)
                plan.segments.single().index.let { index ->
                    request.onSegmentStateChanged(index, SourceSeparationSegmentState.Ready)
                }
                throwAfterPreparation?.let { throw it }
                HtdemucsSourceSeparationRangeResult(
                    SourceSeparationCacheRunCompletion(
                        stemFiles, null, 44_100, 343_980, 1, 1L,
                        request.expectedSourceAudioFingerprint, plan,
                        SourceSeparationCacheRuntimeRecord("LiteRtCpu", HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID, "fp32", 1L),
                    ),
                    MdxSourceDecodeDiagnostics(MdxSourceDecodeMode.Window, "test", "audio/flac", 44_100, 2, 343_980, 343_980, null),
                )
            },
        )
    }
}
