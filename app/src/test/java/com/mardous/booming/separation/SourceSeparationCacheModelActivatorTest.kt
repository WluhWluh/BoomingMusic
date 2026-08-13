package com.mardous.booming.separation

import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceSeparationCacheModelActivatorTest {
    @Test
    fun `already active exact cache is a no-op`() {
        val entry = entry(SourceSeparationModelFamily.Htdemucs, 'a')
        var validationCount = 0
        var activationCount = 0
        var pauseCount = 0
        val activator = SourceSeparationCacheModelActivator(
            currentSelection = { selection(entry.executionIdentity, generation = 4L) },
            validateTarget = { validationCount++ },
            activateTarget = { activationCount++ },
            pauseForModelSupersession = { pauseCount++ },
        )

        val result = activator.activate(entry)

        assertEquals(4L, result.generation)
        assertEquals(0, validationCount)
        assertEquals(0, activationCount)
        assertEquals(0, pauseCount)
    }

    @Test
    fun `cross-family activation pauses once and commits exact identity`() {
        val oldEntry = entry(SourceSeparationModelFamily.Mdx, 'a')
        val target = entry(SourceSeparationModelFamily.Htdemucs, 'b')
        var current = selection(oldEntry.executionIdentity, generation = 2L)
        var validated: SourceSeparationModelAwareCacheEntry? = null
        var pauseCount = 0
        val activator = SourceSeparationCacheModelActivator(
            currentSelection = { current },
            validateTarget = { validated = it },
            activateTarget = {
                current = selection(it.executionIdentity, generation = 3L)
            },
            pauseForModelSupersession = { pauseCount++ },
        )

        val result = activator.activate(target)

        assertEquals(target, validated)
        assertEquals(1, pauseCount)
        assertEquals(3L, result.generation)
        assertEquals(true, result.matches(target.executionIdentity))
    }

    @Test
    fun `non-exact installed cache is rejected before pausing`() {
        val target = entry(SourceSeparationModelFamily.Mdx, 'a').copy(
            modelAvailability = SourceSeparationCacheModelAvailability.ContractMismatch,
        )
        var pauseCount = 0
        val activator = SourceSeparationCacheModelActivator(
            currentSelection = { emptySelection() },
            validateTarget = {},
            activateTarget = {},
            pauseForModelSupersession = { pauseCount++ },
        )

        assertThrows(IllegalArgumentException::class.java) { activator.activate(target) }
        assertEquals(0, pauseCount)
    }

    @Test
    fun `activation fails when committed execution identity differs from cache`() {
        val target = entry(SourceSeparationModelFamily.Mdx, 'a')
        var current = emptySelection()
        var pauseCount = 0
        val activator = SourceSeparationCacheModelActivator(
            currentSelection = { current },
            validateTarget = {},
            activateTarget = {
                current = selection(
                    it.executionIdentity.copy(contractFingerprint = "f".repeat(64)),
                    generation = 1L,
                )
            },
            pauseForModelSupersession = { pauseCount++ },
        )

        assertThrows(IllegalStateException::class.java) { activator.activate(target) }
        assertEquals(1, pauseCount)
    }

    private fun entry(
        family: SourceSeparationModelFamily,
        seed: Char,
    ): SourceSeparationModelAwareCacheEntry {
        val modelId = if (family == SourceSeparationModelFamily.Mdx) "mdx-$seed" else "htdemucs-$seed"
        return SourceSeparationModelAwareCacheEntry(
            cacheKey = seed.toString().repeat(64),
            songId = 1L,
            title = "Song",
            artist = "Artist",
            album = "Album",
            executionIdentity = SourceSeparationExecutionModelIdentity(
                family = family,
                modelId = modelId,
                artifactSha256 = seed.toString().repeat(64),
                contractId = "$modelId@1",
                contractSchemaVersion = 1,
                contractFingerprint = "c".repeat(64),
                profileRevisionId = "$modelId@1",
                pipelineId = "pipeline-$modelId",
                pipelineVersion = 1,
                renderProfileId = "render-$modelId",
            ),
            displayName = modelId,
            state = SourceSeparationModelAwareCacheEntryState.Completed,
            modelAvailability = SourceSeparationCacheModelAvailability.InstalledExact,
            readySegments = null,
            totalSegments = null,
            format = SourceSeparationModelAwareCacheFormat.Wav,
            sizeBytes = 1L,
            updatedAtEpochMs = 1L,
            lastAccessedAtEpochMs = 1L,
        )
    }

    private fun selection(
        identity: SourceSeparationExecutionModelIdentity,
        generation: Long,
    ) = SourceSeparationExecutionSelectionSnapshot(
        family = identity.family,
        modelId = identity.modelId,
        identity = identity,
        generation = generation,
    )

    private fun emptySelection() = SourceSeparationExecutionSelectionSnapshot(
        family = null,
        modelId = null,
        identity = null,
        generation = 0L,
    )
}
