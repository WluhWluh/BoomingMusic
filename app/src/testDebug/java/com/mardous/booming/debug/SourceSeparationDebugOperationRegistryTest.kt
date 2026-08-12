package com.mardous.booming.debug

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationDebugOperationRegistryTest {
    @Test
    fun `operation records success and failure`() {
        val registry = SourceSeparationDebugOperationRegistry()
        try {
            val success = registry.submitSerializedForTest("success") { "{}" }
            val failure = registry.submitSerializedForTest("failure") {
                error("expected failure")
            }

            assertEquals(
                SourceSeparationDebugOperationStatus.Succeeded,
                awaitTerminal(registry, success.id).status,
            )
            val failed = awaitTerminal(registry, failure.id)
            assertEquals(SourceSeparationDebugOperationStatus.Failed, failed.status)
            assertEquals("expected failure", failed.message)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `duplicate active operation is rejected`() {
        val registry = SourceSeparationDebugOperationRegistry()
        val release = CountDownLatch(1)
        try {
            val active = registry.submitSerializedForTest("download", "model") {
                check(release.await(5, TimeUnit.SECONDS))
                "{}"
            }
            awaitStatus(registry, active.id, SourceSeparationDebugOperationStatus.Running)

            assertThrows(IllegalStateException::class.java) {
                registry.submitSerializedForTest("download", "model") { "{}" }
            }
        } finally {
            release.countDown()
            registry.close()
        }
    }

    @Test
    fun `cancellation invokes hook and remains canceled`() {
        val registry = SourceSeparationDebugOperationRegistry()
        val entered = CountDownLatch(1)
        val cancelHookCalled = AtomicBoolean(false)
        try {
            val active = registry.submitSerializedForTest("download", "runtime") {
                onCancel { cancelHookCalled.set(true) }
                entered.countDown()
                while (true) {
                    ensureActive()
                    Thread.sleep(5)
                }
                @Suppress("UNREACHABLE_CODE")
                "{}"
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val canceled = registry.cancel(active.id)

            assertEquals(SourceSeparationDebugOperationStatus.Canceled, canceled.status)
            assertTrue(cancelHookCalled.get())
            assertEquals(
                SourceSeparationDebugOperationStatus.Canceled,
                awaitTerminal(registry, active.id).status,
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun `completed operations trim overflow created while all were active`() {
        val registry = SourceSeparationDebugOperationRegistry(maxRetainedOperations = 2)
        val release = CountDownLatch(1)
        try {
            repeat(3) { index ->
                registry.submitSerializedForTest("task-$index") {
                    check(release.await(5, TimeUnit.SECONDS))
                    "{}"
                }
            }
            assertEquals(3, registry.snapshots().size)

            release.countDown()
            awaitCondition { registry.snapshots().none { it.status.isActive } }

            assertEquals(2, registry.snapshots().size)
        } finally {
            release.countDown()
            registry.close()
        }
    }

    private fun awaitTerminal(
        registry: SourceSeparationDebugOperationRegistry,
        id: String,
    ): SourceSeparationDebugOperationSnapshot {
        awaitCondition { !registry.snapshot(id).status.isActive }
        return registry.snapshot(id)
    }

    private fun awaitStatus(
        registry: SourceSeparationDebugOperationRegistry,
        id: String,
        expected: SourceSeparationDebugOperationStatus,
    ) {
        awaitCondition { registry.snapshot(id).status == expected }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for debug operation state." }
            Thread.sleep(5)
        }
    }
}
