package com.mardous.booming.separation.lifecycle

import com.mardous.booming.separation.lifecycle.SourceSeparationLifecycleTestFixtures.MODEL_A
import com.mardous.booming.separation.lifecycle.SourceSeparationLifecycleTestFixtures.MODEL_B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationLifecycleIdentityTest {
    @Test
    fun `same song under A and B has distinct active cache and run identity`() {
        val referenceA = SourceSeparationLifecycleTestFixtures.activeReference(MODEL_A)
        val referenceB = SourceSeparationLifecycleTestFixtures.activeReference(MODEL_B)
        val source = SourceSeparationLifecycleTestFixtures.source()
        val identityA = SourceSeparationLifecycleTestFixtures.identity(MODEL_A, source)
        val identityB = SourceSeparationLifecycleTestFixtures.identity(MODEL_B, source)
        val runA = SourceSeparationLifecycleTestFixtures.journal(MODEL_A, runId = "run-a")
        val runB = SourceSeparationLifecycleTestFixtures.journal(MODEL_B, runId = "run-b")

        assertNotEquals(referenceA, referenceB)
        assertEquals(identityA.source, identityB.source)
        assertNotEquals(identityA.cacheKey, identityB.cacheKey)
        assertEquals(runA.request.song.songId, runB.request.song.songId)
        assertNotEquals(runA.request.cacheKey, runB.request.cacheKey)
        assertNotEquals(runA.request.runId, runB.request.runId)
    }

    @Test
    fun `lifecycle trace uses bounded identities and stable field names`() {
        val trace = SourceSeparationLifecycleTrace.format(
            event = "worker.stop",
            selectionGeneration = 4L,
            cacheKey = "a".repeat(64),
            requestGeneration = 9L,
            runId = "run-1234567890-extra",
            stopReason = SourceSeparationLifecycleStopReason.ActiveModelSuperseded,
        )

        assertEquals(
            "event=worker.stop selectionGeneration=4 cache=${"a".repeat(12)} " +
                "requestGeneration=9 run=run-12345678 stop=active-model-superseded",
            trace,
        )
        assertTrue("/music/" !in trace)
    }
}
