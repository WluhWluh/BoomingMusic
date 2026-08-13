package com.mardous.booming.separation

import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSeparationPerformanceStatsTest {
    private val stats = SourceSeparationPerformanceStats(MemorySharedPreferences())

    @Test
    fun `window timing is isolated by family model profile and backend`() {
        val mdxCpu = scope(SourceSeparationModelFamily.Mdx, "mdx-a", "profile-a", "LiteRtCpu")
        val mdxGpu = scope(SourceSeparationModelFamily.Mdx, "mdx-a", "profile-a", "LiteRtGpu")
        val mdxOtherProfile = scope(
            SourceSeparationModelFamily.Mdx,
            "mdx-a",
            "profile-b",
            "LiteRtCpu",
        )
        val htdemucsCpu = scope(
            SourceSeparationModelFamily.Htdemucs,
            "htdemucs-a",
            "profile-a",
            "LiteRtCpu",
        )

        assertEquals(DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS, stats.averageWindowMs(mdxCpu))
        assertEquals(1_000L, stats.recordWindowElapsed(mdxCpu, 1_000L))
        assertEquals(4_000L, stats.recordWindowElapsed(mdxGpu, 4_000L))
        assertEquals(1_000L, stats.averageWindowMs(mdxCpu))
        assertEquals(4_000L, stats.averageWindowMs(mdxGpu))
        assertEquals(
            DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS,
            stats.averageWindowMs(mdxOtherProfile),
        )
        assertEquals(
            DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS,
            stats.averageWindowMs(htdemucsCpu),
        )

        assertEquals(2_000L, stats.recordWindowElapsed(mdxCpu, 3_000L))
        assertEquals(4_000L, stats.averageWindowMs(mdxGpu))
    }

    @Test
    fun `scope storage identity includes every timing dimension`() {
        val base = scope(SourceSeparationModelFamily.Mdx, "model", "profile", "LiteRtCpu")
        assertNotEquals(base.storageSuffix, base.copy(family = SourceSeparationModelFamily.Htdemucs).storageSuffix)
        assertNotEquals(base.storageSuffix, base.copy(modelId = "other").storageSuffix)
        assertNotEquals(base.storageSuffix, base.copy(profileId = "other").storageSuffix)
        assertNotEquals(base.storageSuffix, base.copy(backend = "LiteRtGpu").storageSuffix)
    }

    private fun scope(
        family: SourceSeparationModelFamily,
        modelId: String,
        profileId: String,
        backend: String,
    ) = SourceSeparationPerformanceScope(family, modelId, profileId, backend)
}
