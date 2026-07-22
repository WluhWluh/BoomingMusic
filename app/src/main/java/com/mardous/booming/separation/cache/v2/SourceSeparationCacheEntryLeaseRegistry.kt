package com.mardous.booming.separation.cache.v2

class SourceSeparationCacheEntryLeaseRegistry {
    private val states = mutableMapOf<String, LeaseState>()

    @Synchronized
    fun tryAcquireRead(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.mutationActive) {
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
    fun tryAcquireMutation(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.mutationActive || current.readerCount > 0) {
            return null
        }
        current.mutationActive = true
        return SourceSeparationCacheEntryLease(
            cacheKey = cacheKey,
            mode = SourceSeparationCacheLeaseMode.Mutation,
            release = ::release,
        )
    }

    @Synchronized
    fun isLeased(cacheKey: String): Boolean {
        requireCacheKey(cacheKey)
        return states[cacheKey]?.let { it.mutationActive || it.readerCount > 0 } == true
    }

    @Synchronized
    fun snapshot(): Map<String, SourceSeparationCacheLeaseSnapshot> {
        return states.mapValues { (_, state) ->
            SourceSeparationCacheLeaseSnapshot(
                readerCount = state.readerCount,
                mutationActive = state.mutationActive,
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
            SourceSeparationCacheLeaseMode.Mutation -> {
                check(state.mutationActive) { "Cache mutation lease was not active." }
                state.mutationActive = false
            }
        }
        if (state.readerCount == 0 && !state.mutationActive) {
            states.remove(cacheKey)
        }
    }

    private fun requireCacheKey(cacheKey: String) {
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Cache lease key is invalid." }
    }

    private data class LeaseState(
        var readerCount: Int = 0,
        var mutationActive: Boolean = false,
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
    Mutation,
}

data class SourceSeparationCacheLeaseSnapshot(
    val readerCount: Int,
    val mutationActive: Boolean,
)
