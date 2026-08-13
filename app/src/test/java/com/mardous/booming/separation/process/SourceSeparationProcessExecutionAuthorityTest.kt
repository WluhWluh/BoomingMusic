package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationModelFamily
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationProcessExecutionAuthorityTest {
    @Test
    fun `only one family can own the process at a time`() {
        val authority = SourceSeparationProcessExecutionAuthority()
        val mdx = owner(SourceSeparationModelFamily.Mdx, "mdx-run")
        val demucs = owner(SourceSeparationModelFamily.Htdemucs, "demucs-run")
        val lease = authority.acquire(mdx)

        val error = assertThrows(SourceSeparationProcessExecutionBusyException::class.java) {
            authority.acquire(demucs)
        }

        assertEquals(mdx, error.activeOwner)
        assertEquals(mdx, authority.snapshot())
        lease.close()
        assertNull(authority.snapshot())
        authority.acquire(demucs).close()
    }

    @Test
    fun `a stale lease cannot release a newer owner`() {
        val authority = SourceSeparationProcessExecutionAuthority()
        val first = authority.acquire(owner(SourceSeparationModelFamily.Mdx, "first"))
        first.close()
        val secondOwner = owner(SourceSeparationModelFamily.Htdemucs, "second")
        val second = authority.acquire(secondOwner)

        first.close()

        assertEquals(secondOwner, authority.snapshot())
        second.close()
        assertNull(authority.snapshot())
    }

    @Test
    fun `concurrent acquisition has exactly one winner`() {
        val authority = SourceSeparationProcessExecutionAuthority()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val firstLease = AtomicReference<SourceSeparationProcessExecutionLease?>()
        val secondLease = AtomicReference<SourceSeparationProcessExecutionLease?>()
        val first = executor.submit<Boolean> {
            start.await()
            runCatching {
                authority.acquire(owner(SourceSeparationModelFamily.Mdx, "a"))
                    .also(firstLease::set)
                true
            }.getOrDefault(false)
        }
        val second = executor.submit<Boolean> {
            start.await()
            runCatching {
                authority.acquire(owner(SourceSeparationModelFamily.Htdemucs, "b"))
                    .also(secondLease::set)
                true
            }.getOrDefault(false)
        }
        start.countDown()
        val outcomes = listOf(first.get(5L, TimeUnit.SECONDS), second.get(5L, TimeUnit.SECONDS))
        assertEquals(1, outcomes.count { it })
        assertTrue(authority.snapshot() != null)
        firstLease.get()?.close()
        secondLease.get()?.close()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS))
        assertNull(authority.snapshot())
    }

    @Test
    fun `cleanup cannot release ownership before execution finishes`() {
        val authority = SourceSeparationProcessExecutionAuthority()
        val activeOwner = owner(SourceSeparationModelFamily.Htdemucs, "scheduled-run")
        val lifetime = SourceSeparationProcessExecutionLifetime(authority.acquire(activeOwner))

        lifetime.close()

        assertEquals(activeOwner, authority.snapshot())
        assertThrows(SourceSeparationProcessExecutionBusyException::class.java) {
            authority.acquire(owner(SourceSeparationModelFamily.Mdx, "replacement"))
        }

        lifetime.markExecutionFinished()
        assertNull(authority.snapshot())
        authority.acquire(owner(SourceSeparationModelFamily.Mdx, "replacement")).close()
    }

    @Test
    fun `rejected run releases ownership without an execution`() {
        val authority = SourceSeparationProcessExecutionAuthority()
        val lifetime = SourceSeparationProcessExecutionLifetime(
            authority.acquire(owner(SourceSeparationModelFamily.Mdx, "rejected-run")),
        )

        lifetime.rejectBeforeExecution()

        assertTrue(lifetime.executionFinished())
        assertNull(authority.snapshot())
    }

    private fun owner(
        family: SourceSeparationModelFamily,
        runId: String,
    ) = SourceSeparationProcessExecutionOwner(
        family = family,
        runId = runId,
        processGeneration = 1L,
    )
}
