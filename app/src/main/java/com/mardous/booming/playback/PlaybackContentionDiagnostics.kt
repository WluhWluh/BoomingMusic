package com.mardous.booming.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.io.File

@UnstableApi
internal object PlaybackContentionDiagnostics : AnalyticsListener {
    private val lock = Any()
    private var generation = 0L
    private var attachedPlayer: ExoPlayer? = null
    private var playerThreadId: Int? = null
    private var audioUnderrunCount = 0L
    private var audioUnderrunElapsedSinceLastFeedTotalMs = 0L
    private var maximumAudioUnderrunElapsedSinceLastFeedMs = 0L
    private var maximumAudioUnderrunBufferSizeMs: Long? = null

    fun attach(player: ExoPlayer, threadId: Int) {
        require(threadId > 0) { "Playback diagnostics thread ID is invalid." }
        val previous = synchronized(lock) {
            val old = attachedPlayer
            generation += 1L
            attachedPlayer = player
            playerThreadId = threadId
            audioUnderrunCount = 0L
            audioUnderrunElapsedSinceLastFeedTotalMs = 0L
            maximumAudioUnderrunElapsedSinceLastFeedMs = 0L
            maximumAudioUnderrunBufferSizeMs = null
            old
        }
        previous?.takeUnless { it === player }?.removeAnalyticsListener(this)
        player.addAnalyticsListener(this)
    }

    fun detach(player: ExoPlayer) {
        player.removeAnalyticsListener(this)
        synchronized(lock) {
            if (attachedPlayer === player) {
                attachedPlayer = null
                playerThreadId = null
            }
        }
    }

    fun snapshot(procRoot: File = File("/proc/self/task")):
            PlaybackContentionSnapshot {
        val counters = synchronized(lock) {
            CounterSnapshot(
                generation = generation,
                attached = attachedPlayer != null,
                playerThreadId = playerThreadId,
                audioUnderrunCount = audioUnderrunCount,
                audioUnderrunElapsedSinceLastFeedTotalMs =
                    audioUnderrunElapsedSinceLastFeedTotalMs,
                maximumAudioUnderrunElapsedSinceLastFeedMs =
                    maximumAudioUnderrunElapsedSinceLastFeedMs,
                maximumAudioUnderrunBufferSizeMs = maximumAudioUnderrunBufferSizeMs,
            )
        }
        val scheduler = counters.playerThreadId?.let { threadId ->
            PlaybackSchedulerDiagnosticsParser.read(procRoot, threadId)
        }
        return PlaybackContentionSnapshot(
            generation = counters.generation,
            available = counters.attached && scheduler != null,
            playerThreadId = counters.playerThreadId,
            audioUnderrunCount = counters.audioUnderrunCount,
            audioUnderrunElapsedSinceLastFeedTotalMs =
                counters.audioUnderrunElapsedSinceLastFeedTotalMs,
            maximumAudioUnderrunElapsedSinceLastFeedMs =
                counters.maximumAudioUnderrunElapsedSinceLastFeedMs,
            maximumAudioUnderrunBufferSizeMs = counters.maximumAudioUnderrunBufferSizeMs,
            scheduler = scheduler,
        )
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        synchronized(lock) {
            audioUnderrunCount += 1L
            audioUnderrunElapsedSinceLastFeedTotalMs = saturatingAdd(
                audioUnderrunElapsedSinceLastFeedTotalMs,
                elapsedSinceLastFeedMs.coerceAtLeast(0L),
            )
            maximumAudioUnderrunElapsedSinceLastFeedMs = maxOf(
                maximumAudioUnderrunElapsedSinceLastFeedMs,
                elapsedSinceLastFeedMs.coerceAtLeast(0L),
            )
            if (bufferSizeMs >= 0L) {
                maximumAudioUnderrunBufferSizeMs = maxOf(
                    maximumAudioUnderrunBufferSizeMs ?: 0L,
                    bufferSizeMs,
                )
            }
        }
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        runCatching { Math.addExact(first, second) }.getOrDefault(Long.MAX_VALUE)

    private data class CounterSnapshot(
        val generation: Long,
        val attached: Boolean,
        val playerThreadId: Int?,
        val audioUnderrunCount: Long,
        val audioUnderrunElapsedSinceLastFeedTotalMs: Long,
        val maximumAudioUnderrunElapsedSinceLastFeedMs: Long,
        val maximumAudioUnderrunBufferSizeMs: Long?,
    )
}

internal data class PlaybackContentionSnapshot(
    val generation: Long,
    val available: Boolean,
    val playerThreadId: Int?,
    val audioUnderrunCount: Long,
    val audioUnderrunElapsedSinceLastFeedTotalMs: Long,
    val maximumAudioUnderrunElapsedSinceLastFeedMs: Long,
    val maximumAudioUnderrunBufferSizeMs: Long?,
    val scheduler: PlaybackSchedulerSnapshot?,
) {
    fun deltaFrom(baseline: PlaybackContentionSnapshot): PlaybackContentionDelta {
        val sameSource = available && baseline.available && generation == baseline.generation &&
            playerThreadId == baseline.playerThreadId && scheduler != null &&
            baseline.scheduler != null
        if (!sameSource) return PlaybackContentionDelta.unavailable()
        val underrunCount = nonNegativeDelta(
            audioUnderrunCount,
            baseline.audioUnderrunCount,
        )
        return PlaybackContentionDelta(
            available = true,
            audioUnderrunCount = underrunCount,
            audioUnderrunElapsedSinceLastFeedTotalMs = nonNegativeDelta(
                audioUnderrunElapsedSinceLastFeedTotalMs,
                baseline.audioUnderrunElapsedSinceLastFeedTotalMs,
            ),
            maximumAudioUnderrunElapsedSinceLastFeedMs =
                maximumAudioUnderrunElapsedSinceLastFeedMs.takeIf { underrunCount > 0L } ?: 0L,
            maximumAudioUnderrunBufferSizeMs =
                maximumAudioUnderrunBufferSizeMs.takeIf { underrunCount > 0L },
            playerRunTimeNanos = nonNegativeDelta(
                scheduler.runTimeNanos,
                baseline.scheduler.runTimeNanos,
            ),
            playerRunQueueWaitNanos = nonNegativeDelta(
                scheduler.runQueueWaitNanos,
                baseline.scheduler.runQueueWaitNanos,
            ),
            playerTimesliceCount = nonNegativeDelta(
                scheduler.timesliceCount,
                baseline.scheduler.timesliceCount,
            ),
            playerVoluntaryContextSwitches = nonNegativeDelta(
                scheduler.voluntaryContextSwitches,
                baseline.scheduler.voluntaryContextSwitches,
            ),
            playerInvoluntaryContextSwitches = nonNegativeDelta(
                scheduler.involuntaryContextSwitches,
                baseline.scheduler.involuntaryContextSwitches,
            ),
        )
    }

    private fun nonNegativeDelta(current: Long, baseline: Long): Long =
        (current - baseline).coerceAtLeast(0L)
}

internal data class PlaybackContentionDelta(
    val available: Boolean,
    val audioUnderrunCount: Long,
    val audioUnderrunElapsedSinceLastFeedTotalMs: Long,
    val maximumAudioUnderrunElapsedSinceLastFeedMs: Long,
    val maximumAudioUnderrunBufferSizeMs: Long?,
    val playerRunTimeNanos: Long,
    val playerRunQueueWaitNanos: Long,
    val playerTimesliceCount: Long,
    val playerVoluntaryContextSwitches: Long,
    val playerInvoluntaryContextSwitches: Long,
) {
    companion object {
        fun unavailable() = PlaybackContentionDelta(
            available = false,
            audioUnderrunCount = 0L,
            audioUnderrunElapsedSinceLastFeedTotalMs = 0L,
            maximumAudioUnderrunElapsedSinceLastFeedMs = 0L,
            maximumAudioUnderrunBufferSizeMs = null,
            playerRunTimeNanos = 0L,
            playerRunQueueWaitNanos = 0L,
            playerTimesliceCount = 0L,
            playerVoluntaryContextSwitches = 0L,
            playerInvoluntaryContextSwitches = 0L,
        )
    }
}

internal data class PlaybackSchedulerSnapshot(
    val runTimeNanos: Long,
    val runQueueWaitNanos: Long,
    val timesliceCount: Long,
    val voluntaryContextSwitches: Long,
    val involuntaryContextSwitches: Long,
)

internal object PlaybackSchedulerDiagnosticsParser {
    fun read(procTaskRoot: File, threadId: Int): PlaybackSchedulerSnapshot? {
        val threadRoot = File(procTaskRoot, threadId.toString())
        val schedstat = runCatching { File(threadRoot, "schedstat").readText() }.getOrNull()
            ?.let(::parseSchedstat) ?: return null
        val switches = runCatching { File(threadRoot, "status").readText() }.getOrNull()
            ?.let(::parseContextSwitches) ?: return null
        return PlaybackSchedulerSnapshot(
            runTimeNanos = schedstat.first,
            runQueueWaitNanos = schedstat.second,
            timesliceCount = schedstat.third,
            voluntaryContextSwitches = switches.first,
            involuntaryContextSwitches = switches.second,
        )
    }

    fun parseSchedstat(value: String): Triple<Long, Long, Long>? {
        val fields = value.trim().split(WHITESPACE)
        if (fields.size < 3) return null
        val parsed = fields.take(3).map { it.toLongOrNull() ?: return null }
        if (parsed.any { it < 0L }) return null
        return Triple(parsed[0], parsed[1], parsed[2])
    }

    fun parseContextSwitches(value: String): Pair<Long, Long>? {
        val fields = value.lineSequence().mapNotNull { line ->
            val name = line.substringBefore(':')
            if (name != VOLUNTARY_SWITCHES && name != INVOLUNTARY_SWITCHES) return@mapNotNull null
            name to line.substringAfter(':').trim().toLongOrNull()
        }.toMap()
        val voluntary = fields[VOLUNTARY_SWITCHES] ?: return null
        val involuntary = fields[INVOLUNTARY_SWITCHES] ?: return null
        if (voluntary < 0L || involuntary < 0L) return null
        return voluntary to involuntary
    }

    private val WHITESPACE = Regex("\\s+")
    private const val VOLUNTARY_SWITCHES = "voluntary_ctxt_switches"
    private const val INVOLUNTARY_SWITCHES = "nonvoluntary_ctxt_switches"
}
