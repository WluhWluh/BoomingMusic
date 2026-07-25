package com.mardous.booming.separation.cache.v2

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationCacheEntryLockManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `independent managers cannot mutate the same exact entry`() {
        val root = root()
        val firstManager = SourceSeparationCacheEntryLockManager(root, nowEpochMs = { 10L })
        val secondManager = SourceSeparationCacheEntryLockManager(root, nowEpochMs = { 20L })
        val first = requireNotNull(firstManager.tryAcquire(KEY_A, owner("first")))

        assertNull(secondManager.tryAcquire(KEY_A, owner("second")))
        assertNotNull(secondManager.tryAcquire(KEY_B, owner("other"))?.also { it.close() })

        first.close()
        assertNotNull(secondManager.tryAcquire(KEY_A, owner("second"))?.also { it.close() })
    }

    @Test
    fun `deleting a bound entry becomes typed cache loss`() {
        val root = root()
        val store = SourceSeparationCacheStore(root)
        val lease = requireNotNull(store.entryLocks().tryAcquire(KEY_A, owner("run")))
        val entry = store.entryDirectory(KEY_A)
        lease.bindEntryDirectory(entry)

        entry.deleteRecursively()

        assertThrows(SourceSeparationCacheLostException::class.java) {
            lease.requireAvailable()
        }
        lease.close()
    }

    @Test
    fun `deleting the lock path becomes typed cache loss`() {
        val root = root()
        val manager = SourceSeparationCacheEntryLockManager(root)
        val lease = requireNotNull(manager.tryAcquire(KEY_A, owner("run")))
        val lockDirectory = root.directory.resolve(
            SourceSeparationCacheEntryLockManager.LOCKS_DIRECTORY_NAME,
        )

        lockDirectory.deleteRecursively()

        assertThrows(SourceSeparationCacheLostException::class.java) {
            lease.requireAvailable()
        }
        lease.close()
    }

    @Test
    fun `startup recovery skips a remotely locked entry`() {
        val root = root()
        val writerStore = SourceSeparationCacheStore(root)
        val entry = writerStore.entryDirectory(KEY_A).apply { mkdirs() }
        val temporary = entry.resolve("segment.tmp").apply { writeText("active") }
        val lease = requireNotNull(writerStore.entryLocks().tryAcquire(KEY_A, owner("run")))
        lease.bindEntryDirectory(entry)

        val result = SourceSeparationCacheStore(root).recover()

        assertEquals(1, result.skippedLockedEntries)
        assertEquals(0, result.removedInvalidEntries)
        assertTrue(temporary.isFile)
        lease.close()
    }

    private fun root() = SourceSeparationCacheRoot(
        directory = temporaryFolder.newFolder(),
        location = SourceSeparationCacheRootLocation.InternalCache,
    )

    private fun owner(runId: String) = SourceSeparationCacheLockOwner(
        purpose = SourceSeparationCacheLockPurpose.Run,
        runId = runId,
        processGeneration = 1L,
        pid = 100,
    )

    private companion object {
        val KEY_A = "a".repeat(64)
        val KEY_B = "b".repeat(64)
    }
}
