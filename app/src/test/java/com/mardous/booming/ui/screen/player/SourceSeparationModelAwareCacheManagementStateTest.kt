package com.mardous.booming.ui.screen.player

import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationModelAwareCacheManagementStateTest {

    @Test
    fun `same song model entries remain independent management items`() {
        val state = SourceSeparationModelAwareCacheManagementUiState(
            items = listOf(
                entry(cacheKeySeed = 'a', modelId = "uvr_mdxnet_3_9662", sizeBytes = 10L),
                entry(cacheKeySeed = 'b', modelId = "uvr_mdxnet_kara", sizeBytes = 20L),
            ),
        )

        assertEquals(2, state.items.size)
        assertEquals(2, state.items.map(SourceSeparationModelAwareCacheEntry::cacheKey).distinct().size)
        assertEquals(setOf(42L), state.items.map(SourceSeparationModelAwareCacheEntry::songId).toSet())
        assertEquals(30L, state.totalSizeBytes)
    }

    @Test
    fun `only validated completed state enters completed section`() {
        val completed = entry(
            cacheKeySeed = 'a',
            modelId = "uvr_mdxnet_3_9662",
            state = SourceSeparationModelAwareCacheEntryState.Completed,
        )
        val otherStates = SourceSeparationModelAwareCacheEntryState.entries
            .filterNot { it == SourceSeparationModelAwareCacheEntryState.Completed }
            .mapIndexed { index, state ->
                entry(
                    cacheKeySeed = ('b'.code + index).toChar(),
                    modelId = "model-$index",
                    state = state,
                )
            }
        val state = SourceSeparationModelAwareCacheManagementUiState(
            items = listOf(completed) + otherStates,
        )

        assertEquals(listOf(completed.cacheKey), state.completedItems.map { it.cacheKey })
        assertEquals(otherStates.map { it.cacheKey }, state.incompleteItems.map { it.cacheKey })
    }

    @Test
    fun `flac promotion state distinguishes model caches for the same song`() {
        val runningKey = "a".repeat(64)
        val queuedKey = "b".repeat(64)
        val state = SourceSeparationFlacPromotionUiState(
            runningCacheKey = runningKey,
            queuedCacheKeys = setOf(queuedKey),
        )

        assertEquals(true, state.isRunning(runningKey))
        assertEquals(false, state.isRunning(queuedKey))
        assertEquals(true, state.isQueued(queuedKey))
        assertEquals(false, state.isQueued(runningKey))
        assertEquals(false, state.isActive("c".repeat(64)))
    }

    @Test
    fun `only an exact installed inactive cache model can be selected`() {
        val installed = entry(cacheKeySeed = 'a', modelId = "model-a")
        val activeState = SourceSeparationModelAwareCacheManagementUiState(
            activeModelId = installed.modelId,
            activeArtifactSha256 = installed.artifactSha256,
            activeProfileId = null,
        )

        assertFalse(installed.canUseModel(activeState))
        assertTrue(
            installed.copy(artifactSha256 = "b".repeat(64)).canUseModel(activeState),
        )
        assertFalse(
            installed.copy(
                modelAvailability = SourceSeparationCacheModelAvailability.ModelNotInstalled,
            ).canUseModel(SourceSeparationModelAwareCacheManagementUiState()),
        )
    }

    @Test
    fun `custom profile selection compares the exact profile revision`() {
        val installed = entry(cacheKeySeed = 'a', modelId = "custom-model")
        val activeState = SourceSeparationModelAwareCacheManagementUiState(
            activeModelId = installed.modelId,
            activeArtifactSha256 = installed.artifactSha256,
            activeProfileId = installed.profileRevisionId,
        )

        assertFalse(installed.canUseModel(activeState))
        assertTrue(
            installed.copy(profileRevisionId = "other-profile").canUseModel(activeState),
        )
    }

    private fun entry(
        cacheKeySeed: Char,
        modelId: String,
        sizeBytes: Long = 1L,
        state: SourceSeparationModelAwareCacheEntryState =
            SourceSeparationModelAwareCacheEntryState.Completed,
    ) = SourceSeparationModelAwareCacheEntry(
        cacheKey = cacheKeySeed.toString().repeat(64),
        songId = 42L,
        title = "Song",
        artist = "Artist",
        album = "Album",
        modelId = modelId,
        displayName = modelId,
        artifactSha256 = cacheKeySeed.toString().repeat(64),
        contractId = "$modelId-contract",
        profileRevisionId = "$modelId-profile",
        renderProfileId = "mdx-fp32-render-v1",
        state = state,
        modelAvailability = SourceSeparationCacheModelAvailability.InstalledExact,
        readySegments = null,
        totalSegments = null,
        format = SourceSeparationModelAwareCacheFormat.Wav,
        sizeBytes = sizeBytes,
        updatedAtEpochMs = 1L,
        lastAccessedAtEpochMs = 2L,
    )
}
