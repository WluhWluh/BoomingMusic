package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.StemId
import com.mardous.booming.separation.model.contract.StemProduction
import com.mardous.booming.separation.model.contract.StemSemanticId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationStemArtifactSetTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `two four six and eight stem segment sets commit validate and delete atomically`() {
        for (stemCount in STEM_GEOMETRIES) {
            val store = store("cache-$stemCount")
            val cacheKey = stemCount.toString(16).repeat(64)
            val stemIds = stemIds(stemCount)
            val plan = segmentPlan(stemIds)
            val segment = plan.segments.first()

            assertEquals(stemIds, plan.stemIds)
            assertEquals(stemIds, segment.stems.map { it.stemId })
            assertEquals(
                (0 until stemCount).map { "segments/00000/stem-%02d.wav".format(it) },
                segment.stems.map { it.path },
            )
            assertEquals(
                plan,
                json.decodeFromString<SourceSeparationSegmentPlan>(json.encodeToString(plan)),
            )

            segment.stems.forEach { stem ->
                store.resolveEntryPath(cacheKey, stem.path).apply {
                    parentFile?.mkdirs()
                    writeText("${stem.stemId}:${stem.order}")
                }
            }
            assertTrue(segment.hasCompleteReadyArtifactSet(store, cacheKey))

            val committed = segment.captureCommittedArtifactSet(store, cacheKey)
            assertEquals(stemIds, committed.stems.map { it.stemId })
            assertTrue(committed.matches(segment))
            assertTrue(committed.hasValidArtifactSet(store, cacheKey))
            assertEquals(
                committed,
                json.decodeFromString<SourceSeparationCacheCommittedSegment>(
                    json.encodeToString(committed),
                ),
            )

            store.resolveEntryPath(cacheKey, segment.stems.last().path).delete()
            assertFalse(segment.hasCompleteReadyArtifactSet(store, cacheKey))
            assertFalse(committed.hasValidArtifactSet(store, cacheKey))
            assertThrows(IllegalArgumentException::class.java) {
                segment.captureCommittedArtifactSet(store, cacheKey)
            }

            segment.deleteArtifactSet(store, cacheKey)
            assertTrue(segment.stems.none { store.resolveEntryPath(cacheKey, it.path).exists() })
        }
    }

    @Test
    fun `multistem replacement remains unavailable until the complete set is restored`() {
        for (stemCount in listOf(4, 6, 8)) {
            val store = store("replacement-$stemCount")
            val cacheKey = (stemCount + 1).toString(16).repeat(64)
            val segment = segmentPlan(stemIds(stemCount)).segments.first()
            segment.stems.forEach { stem ->
                store.resolveEntryPath(cacheKey, stem.path).apply {
                    parentFile?.mkdirs()
                    writeText("old:${stem.stemId}:${stem.order}")
                }
            }
            val oldCommit = segment.captureCommittedArtifactSet(store, cacheKey)

            segment.deleteArtifactSet(store, cacheKey)
            segment.stems.dropLast(1).forEach { stem ->
                store.resolveEntryPath(cacheKey, stem.path).apply {
                    parentFile?.mkdirs()
                    writeText("new:${stem.stemId}:${stem.order}")
                }
            }
            assertFalse(segment.hasCompleteReadyArtifactSet(store, cacheKey))
            assertFalse(oldCommit.hasValidArtifactSet(store, cacheKey))
            assertThrows(IllegalArgumentException::class.java) {
                segment.captureCommittedArtifactSet(store, cacheKey)
            }

            val finalStem = segment.stems.last()
            store.resolveEntryPath(cacheKey, finalStem.path).writeText(
                "new:${finalStem.stemId}:${finalStem.order}",
            )
            assertTrue(segment.hasCompleteReadyArtifactSet(store, cacheKey))
            val replacementCommit = segment.captureCommittedArtifactSet(store, cacheKey)
            assertTrue(replacementCommit.hasValidArtifactSet(store, cacheKey))
            assertFalse(oldCommit == replacementCommit)
        }
    }

    @Test
    fun `segment plan rejects an incomplete or reordered stem set`() {
        val plan = segmentPlan(stemIds(4))
        val first = plan.segments.first()

        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(
                segments = listOf(first.copy(stems = first.stems.dropLast(1))) +
                    plan.segments.drop(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.copy(stems = first.stems.reversed())
        }
    }

    @Test
    fun `rendered output rejects aliased paths and inconsistent audio geometry`() {
        val stems = stemIds(4).mapIndexed { order, stemId ->
            SourceSeparationCacheRenderedStem(
                stemId = stemId,
                semanticId = StemSemanticId(stemId.value),
                canonicalLabel = "Stem $order",
                order = order,
                production = StemProduction.PipelineNative(order),
                wavPath = "completed/stem-%02d.wav".format(order),
                channelCount = 2,
                sampleRate = 44_100,
                frameCount = 100,
            )
        }
        val output = SourceSeparationCacheOutput(
            stems = stems,
            outputSampleRate = 44_100,
            outputFrameCount = 100,
            windowCount = 1,
            elapsedMs = 1L,
            totalBytes = 0L,
        )

        assertEquals(4, output.stems.size)
        assertThrows(IllegalArgumentException::class.java) {
            output.copy(stems = stems.mapIndexed { index, stem ->
                if (index == 3) stem.copy(wavPath = stems.first().wavPath) else stem
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            output.copy(stems = stems.mapIndexed { index, stem ->
                if (index == 3) stem.copy(frameCount = 99) else stem
            })
        }
    }

    private fun segmentPlan(stemIds: List<StemId>): SourceSeparationSegmentPlan =
        SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = 88_200,
            sampleRate = 44_100,
            generationSize = 44_100,
            trim = 1_024,
            chunkSize = 46_148,
            stemIds = stemIds,
            defaultState = SourceSeparationSegmentState.Ready,
        )

    private fun stemIds(count: Int): List<StemId> =
        (0 until count).map { index -> StemId("stem_$index") }

    private fun integrity(seed: Int) = SourceSeparationCacheFileIntegrity(
        byteSize = seed.toLong() + 1L,
        sha256 = seed.toString(16).padStart(64, '0'),
    )

    private fun store(name: String): SourceSeparationCacheStore =
        SourceSeparationCacheStore(
            SourceSeparationCacheRoot(
                directory = temporary.newFolder(name),
                location = SourceSeparationCacheRootLocation.InternalCache,
            )
        )

    private companion object {
        val STEM_GEOMETRIES = listOf(2, 4, 6, 8)
    }
}
