package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationCacheRelativePath

class SourceSeparationCacheEntryLeaseRegistry {
    private val states = mutableMapOf<String, LeaseState>()
    private var nextReaderId = 0L

    @Synchronized
    fun tryAcquireRead(cacheKey: String): SourceSeparationCacheEntryLease? =
        tryAcquireRead(cacheKey, ReadProtection.WholeEntry)

    @Synchronized
    fun tryAcquireArtifactRead(
        cacheKey: String,
        protectedPaths: Set<String>,
    ): SourceSeparationCacheEntryLease? {
        require(protectedPaths.isNotEmpty()) { "A cache artifact read lease protects no paths." }
        protectedPaths.forEach(SourceSeparationCacheRelativePath::requireValid)
        return tryAcquireRead(
            cacheKey = cacheKey,
            protection = ReadProtection.Paths(protectedPaths.toSet()),
        )
    }

    private fun tryAcquireRead(
        cacheKey: String,
        protection: ReadProtection,
    ): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive) return null
        val readerId = ++nextReaderId
        current.readers[readerId] = protection
        return SourceSeparationCacheEntryLease(
            cacheKey = cacheKey,
            mode = SourceSeparationCacheLeaseMode.Read,
            readerId = readerId,
            release = ::release,
        )
    }

    @Synchronized
    fun tryAcquireRunWrite(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive || current.runWriterActive || current.promotionActive) {
            return null
        }
        current.runWriterActive = true
        return mutationLease(cacheKey, SourceSeparationCacheLeaseMode.RunWrite)
    }

    @Synchronized
    fun tryAcquirePromotion(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive || current.runWriterActive || current.promotionActive) {
            return null
        }
        current.promotionActive = true
        return mutationLease(cacheKey, SourceSeparationCacheLeaseMode.Promotion)
    }

    @Synchronized
    fun tryAcquireCleanup(
        cacheKey: String,
        cleanupPaths: Set<String>,
    ): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        cleanupPaths.forEach(SourceSeparationCacheRelativePath::requireValid)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive ||
            current.runWriterActive ||
            current.promotionActive ||
            current.readers.values.any { it.blocks(cleanupPaths) }
        ) {
            return null
        }
        current.exclusiveActive = true
        return mutationLease(cacheKey, SourceSeparationCacheLeaseMode.Exclusive)
    }

    @Synchronized
    fun tryAcquireExclusive(cacheKey: String): SourceSeparationCacheEntryLease? {
        requireCacheKey(cacheKey)
        val current = states.getOrPut(cacheKey, ::LeaseState)
        if (current.exclusiveActive ||
            current.runWriterActive ||
            current.promotionActive ||
            current.readers.isNotEmpty()
        ) {
            return null
        }
        current.exclusiveActive = true
        return mutationLease(cacheKey, SourceSeparationCacheLeaseMode.Exclusive)
    }

    @Synchronized
    fun isLeased(cacheKey: String): Boolean {
        requireCacheKey(cacheKey)
        return states[cacheKey]?.let {
            it.exclusiveActive ||
                it.runWriterActive ||
                it.promotionActive ||
                it.readers.isNotEmpty()
        } == true
    }

    @Synchronized
    fun snapshot(): Map<String, SourceSeparationCacheLeaseSnapshot> {
        return states.mapValues { (_, state) ->
            SourceSeparationCacheLeaseSnapshot(
                readerCount = state.readers.size,
                runWriterActive = state.runWriterActive,
                promotionActive = state.promotionActive,
                exclusiveActive = state.exclusiveActive,
            )
        }
    }

    private fun mutationLease(
        cacheKey: String,
        mode: SourceSeparationCacheLeaseMode,
    ) = SourceSeparationCacheEntryLease(
        cacheKey = cacheKey,
        mode = mode,
        readerId = null,
        release = ::release,
    )

    @Synchronized
    private fun release(
        cacheKey: String,
        mode: SourceSeparationCacheLeaseMode,
        readerId: Long?,
    ) {
        val state = states[cacheKey] ?: return
        when (mode) {
            SourceSeparationCacheLeaseMode.Read -> {
                val id = requireNotNull(readerId) { "Cache read lease has no reader ID." }
                check(state.readers.containsKey(id)) { "Cache read lease count underflow." }
                state.readers.remove(id)
            }
            SourceSeparationCacheLeaseMode.RunWrite -> {
                check(state.runWriterActive) { "Cache run-writer lease was not active." }
                state.runWriterActive = false
            }
            SourceSeparationCacheLeaseMode.Promotion -> {
                check(state.promotionActive) { "Cache promotion lease was not active." }
                state.promotionActive = false
            }
            SourceSeparationCacheLeaseMode.Exclusive -> {
                check(state.exclusiveActive) { "Cache exclusive lease was not active." }
                state.exclusiveActive = false
            }
        }
        if (state.readers.isEmpty() &&
            !state.runWriterActive &&
            !state.promotionActive &&
            !state.exclusiveActive
        ) {
            states.remove(cacheKey)
        }
    }

    private fun requireCacheKey(cacheKey: String) {
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Cache lease key is invalid." }
    }

    private data class LeaseState(
        val readers: MutableMap<Long, ReadProtection> = mutableMapOf(),
        var runWriterActive: Boolean = false,
        var promotionActive: Boolean = false,
        var exclusiveActive: Boolean = false,
    )

    private sealed interface ReadProtection {
        fun blocks(cleanupPaths: Set<String>): Boolean

        data object WholeEntry : ReadProtection {
            override fun blocks(cleanupPaths: Set<String>): Boolean = true
        }

        data class Paths(val paths: Set<String>) : ReadProtection {
            override fun blocks(cleanupPaths: Set<String>): Boolean = paths.any { protectedPath ->
                cleanupPaths.any { cleanupPath -> pathsOverlap(protectedPath, cleanupPath) }
            }
        }

        companion object {
            private fun pathsOverlap(first: String, second: String): Boolean =
                first == second ||
                    first.startsWith("$second/") ||
                    second.startsWith("$first/")
        }
    }

    private companion object {
        val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

class SourceSeparationCacheEntryLease internal constructor(
    val cacheKey: String,
    val mode: SourceSeparationCacheLeaseMode,
    private val readerId: Long?,
    private val release: (String, SourceSeparationCacheLeaseMode, Long?) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var kernelLease: SourceSeparationCacheEntryKernelLease? = null

    internal fun attachKernelLease(lease: SourceSeparationCacheEntryKernelLease) {
        synchronized(this) {
            check(!closed && kernelLease == null) { "Cache lease cannot attach a kernel lock." }
            require(lease.cacheKey == cacheKey) { "Cache kernel lock key is inconsistent." }
            kernelLease = lease
        }
    }

    fun bindEntryDirectory(directory: java.io.File) {
        synchronized(this) {
            check(!closed) { "Cache lease is closed." }
            requireNotNull(kernelLease) { "Cache mutation lease has no kernel lock." }
                .bindEntryDirectory(directory)
        }
    }

    fun requireCacheAvailable() {
        synchronized(this) {
            check(!closed) { "Cache lease is closed." }
            kernelLease?.requireAvailable()
        }
    }

    override fun close() {
        val kernel = synchronized(this) {
            if (closed) return
            closed = true
            kernelLease.also { kernelLease = null }
        }
        kernel?.close()
        release(cacheKey, mode, readerId)
    }
}

enum class SourceSeparationCacheLeaseMode {
    Read,
    RunWrite,
    Promotion,
    Exclusive,
}

data class SourceSeparationCacheLeaseSnapshot(
    val readerCount: Int,
    val runWriterActive: Boolean,
    val promotionActive: Boolean,
    val exclusiveActive: Boolean,
)
