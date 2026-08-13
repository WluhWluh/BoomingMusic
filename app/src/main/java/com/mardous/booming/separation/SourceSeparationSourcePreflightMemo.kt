package com.mardous.booming.separation

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class SourceSeparationSourcePreflightMemoSnapshot(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val entryCount: Int,
)

internal class SourceSeparationSourcePreflightMemo(
    private val maxEntries: Int = MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "The source-preflight memo capacity must be positive." }
    }

    private val lock = Any()
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L
    private val inFlight = mutableMapOf<
        SourceSeparationSourcePreflightMemoKey,
        SourceSeparationSourcePreflightInFlight,
    >()
    private val entries = object : LinkedHashMap<
        SourceSeparationSourcePreflightMemoKey,
        SourceSeparationCacheSourcePreflight,
    >(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<
                SourceSeparationSourcePreflightMemoKey,
                SourceSeparationCacheSourcePreflight,
            >?,
        ): Boolean {
            val remove = size > maxEntries
            if (remove) evictions += 1L
            return remove
        }
    }

    fun resolve(
        song: Song,
        input: SourceSeparationModelAwareSongInput,
        resolver: SourceSeparationModelAwarePreflightResolver,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheSourcePreflight {
        throwIfCanceled(shouldCancel)
        val key = SourceSeparationSourcePreflightMemoKey(
            sourceUri = input.sourceUri,
            filePath = song.data,
            fileSize = song.size,
            rawDateModified = song.rawDateModified,
            durationMs = song.duration,
        )
        val acquisition = synchronized(lock) {
            entries[key]?.let { cached ->
                hits += 1L
                return cached.copy(elapsedMs = 0L)
            }
            inFlight[key]?.let { existing ->
                hits += 1L
                SourceSeparationSourcePreflightAcquisition(existing, ownsResolution = false)
            } ?: run {
                misses += 1L
                SourceSeparationSourcePreflightInFlight().also { created ->
                    inFlight[key] = created
                }.let { created ->
                    SourceSeparationSourcePreflightAcquisition(created, ownsResolution = true)
                }
            }
        }
        val flight = acquisition.flight
        if (!acquisition.ownsResolution) {
            while (!flight.completed.await(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                throwIfCanceled(shouldCancel)
            }
            throwIfCanceled(shouldCancel)
            flight.error?.let { error ->
                if (error is CancellationException) {
                    return resolve(song, input, resolver, shouldCancel)
                }
                throw error
            }
            return requireNotNull(flight.value).copy(elapsedMs = 0L)
        }

        try {
            val resolved = resolver.resolve(input.sourceUri, shouldCancel)
            throwIfCanceled(shouldCancel)
            synchronized(lock) {
                entries[key] = resolved
                inFlight.remove(key, flight)
            }
            flight.value = resolved
            return resolved
        } catch (error: Throwable) {
            flight.error = error
            synchronized(lock) {
                inFlight.remove(key, flight)
            }
            throw error
        } finally {
            flight.completed.countDown()
        }
    }

    fun snapshot(): SourceSeparationSourcePreflightMemoSnapshot = synchronized(lock) {
        SourceSeparationSourcePreflightMemoSnapshot(
            hits = hits,
            misses = misses,
            evictions = evictions,
            entryCount = entries.size,
        )
    }

    private companion object {
        const val MAX_ENTRIES = 4
        const val WAIT_POLL_MS = 25L

        fun throwIfCanceled(shouldCancel: () -> Boolean) {
            if (shouldCancel()) {
                throw CancellationException("Source audio identity resolution canceled.")
            }
        }
    }
}

private class SourceSeparationSourcePreflightInFlight {
    val completed = CountDownLatch(1)

    @Volatile
    var value: SourceSeparationCacheSourcePreflight? = null

    @Volatile
    var error: Throwable? = null
}

private data class SourceSeparationSourcePreflightAcquisition(
    val flight: SourceSeparationSourcePreflightInFlight,
    val ownsResolution: Boolean,
)

private data class SourceSeparationSourcePreflightMemoKey(
    val sourceUri: String,
    val filePath: String,
    val fileSize: Long,
    val rawDateModified: Long,
    val durationMs: Long,
)
