package com.mardous.booming.separation.cache.v2

class SourceSeparationCacheEntryLeaseRegistry {
    private val states = mutableMapOf<String, LeaseState>()

    @Synchronized
    fun tryAcquireRead(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive) {
            return null
        }
        current.readerCount += 1
        return SourceSeparationCacheEntryLease(
            cacheKey = cacheKey,
            mode = SourceSeparationCacheLeaseMode.Read,
            release = ::release,
        )
    }

    @Synchronized
    fun tryAcquireRunWrite(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive || current.runWriterActive) {
            return null
        }
        current.runWriterActive = true
        return SourceSeparationCacheEntryLease(
            cacheKey = cacheKey,
            mode = SourceSeparationCacheLeaseMode.RunWrite,
            release = ::release,
        )
    }

    @Synchronized
    fun tryAcquireExclusive(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive || current.runWriterActive || current.readerCount > 0) {
            return null
        }
        current.exclusiveActive = true
        return SourceSeparationCacheEntryLease(
            cacheKey = cacheKey,
            mode = SourceSeparationCacheLeaseMode.Exclusive,
            release = ::release,
        )
    }

    @Synchronized
    fun isLeased(cacheKey: String): Boolean {
        requireCacheKey(cacheKey)
        return states[cacheKey]?.let {
            it.exclusiveActive || it.runWriterActive || it.readerCount > 0
        } == true
    }

    @Synchronized
    fun snapshot(): Map<String, SourceSeparationCacheLeaseSnapshot> {
        return states.mapValues { (_, state) ->
            SourceSeparationCacheLeaseSnapshot(
                readerCount = state.readerCount,
                runWriterActive = state.runWriterActive,
                exclusiveActive = state.exclusiveActive,
            )
        }
    }

    @Synchronized
    private fun release(cacheKey: String, mode: SourceSeparationCacheLeaseMode) {
        val state = states[cacheKey] ?: return
        when (mode) {
            SourceSeparationCacheLeaseMode.Read -> {
                check(state.readerCount > 0) { "Cache read lease count underflow." }
                state.readerCount -= 1
            }
            SourceSeparationCacheLeaseMode.RunWrite -> {
                check(state.runWriterActive) { "Cache run-writer lease was not active." }
                state.runWriterActive = false
            }
            SourceSeparationCacheLeaseMode.Exclusive -> {
                check(state.exclusiveActive) { "Cache exclusive lease was not active." }
                state.exclusiveActive = false
            }
        }
        if (state.readerCount == 0 && !state.runWriterActive && !state.exclusiveActive) {
            states.remove(cacheKey)
        }
    }

    private fun requireCacheKey(cacheKey: String) {
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Cache lease key is invalid." }
    }

    private data class LeaseState(
        var readerCount: Int = 0,
        var runWriterActive: Boolean = false,
        var exclusiveActive: Boolean = false,
    )

    private companion object {
        val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

class SourceSeparationCacheEntryLease internal constructor(
    val cacheKey: String,
    val mode: SourceSeparationCacheLeaseMode,
    private val release: (String, SourceSeparationCacheLeaseMode) -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        release(cacheKey, mode)
    }
}

enum class SourceSeparationCacheLeaseMode {
    Read,
    RunWrite,
    Exclusive,
}

data class SourceSeparationCacheLeaseSnapshot(
    val readerCount: Int,
    val runWriterActive: Boolean,
    val exclusiveActive: Boolean,
)
