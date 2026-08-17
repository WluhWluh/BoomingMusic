package com.mardous.booming.separation.process

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class SourceSeparationProcessDiagnostics(
    val processGeneration: Long,
    val processName: String,
    val pid: Int,
    val processStartTicks: Long,
    val capturedAtElapsedRealtimeNanos: Long,
    val activeRunId: String? = null,
    val memory: SourceSeparationProcessMemoryDiagnostics,
    val mappedNativeLibraries: List<String> = emptyList(),
    val session: SourceSeparationProcessSessionDiagnostics =
        SourceSeparationProcessSessionDiagnostics.empty(),
    val validationOverride: SourceSeparationProcessValidationOverrideDiagnostics? = null,
    val foregroundService: SourceSeparationForegroundServiceDiagnostics =
        SourceSeparationForegroundServiceDiagnostics(),
    val processingWakeLock: SourceSeparationProcessingWakeLockDiagnostics =
        SourceSeparationProcessingWakeLockDiagnostics(),
) {
    init {
        require(processGeneration > 0L) { "Process diagnostic generation is invalid." }
        require(processName.isNotBlank()) { "Process diagnostic name is empty." }
        require(pid > 0) { "Process diagnostic PID is invalid." }
        require(processStartTicks > 0L) { "Process diagnostic start identity is invalid." }
        require(capturedAtElapsedRealtimeNanos > 0L) {
            "Process diagnostic capture time is invalid."
        }
        require(mappedNativeLibraries.all { library ->
            library.isNotBlank() && '/' !in library && '\\' !in library
        }) {
            "Process diagnostic native-library identity is invalid."
        }
    }
}

@Serializable
internal data class SourceSeparationProcessMemoryDiagnostics(
    val vmSizeBytes: Long? = null,
    val vmPeakBytes: Long? = null,
    val vmRssBytes: Long? = null,
    val vmDataBytes: Long? = null,
    val pssBytes: Long,
    val nativePssBytes: Long,
    val threadCount: Int,
    val mappedRegionCount: Int,
    val smapsSource: SourceSeparationSmapsSource,
    val anonHugePagesBytes: Long? = null,
    val largestFreeAddressGapBytes: Long? = null,
    val ussBytes: Long = 0L,
    val javaPssBytes: Long = 0L,
    val graphicsPssBytes: Long = 0L,
    val javaHeapAllocatedBytes: Long = 0L,
    val nativeHeapAllocatedBytes: Long = 0L,
    val runtimeMaxMemoryBytes: Long = 0L,
    val processCpuTimeMs: Long = 0L,
    val oomScoreAdj: Int? = null,
    val artRuntime: SourceSeparationArtRuntimeDiagnostics =
        SourceSeparationArtRuntimeDiagnostics(),
) {
    init {
        require(
            pssBytes >= 0L && nativePssBytes >= 0L && ussBytes >= 0L &&
                javaPssBytes >= 0L && graphicsPssBytes >= 0L &&
                javaHeapAllocatedBytes >= 0L && nativeHeapAllocatedBytes >= 0L &&
                runtimeMaxMemoryBytes >= 0L && processCpuTimeMs >= 0L,
        ) {
            "Process diagnostic PSS is invalid."
        }
        require(threadCount >= 0 && mappedRegionCount >= 0) {
            "Process diagnostic count is invalid."
        }
    }
}

@Serializable
internal data class SourceSeparationArtRuntimeDiagnostics(
    val gcCount: Long? = null,
    val gcTimeMs: Long? = null,
    val bytesAllocated: Long? = null,
    val bytesFreed: Long? = null,
    val blockingGcCount: Long? = null,
    val blockingGcTimeMs: Long? = null,
) {
    init {
        require(
            listOf(
                gcCount,
                gcTimeMs,
                bytesAllocated,
                bytesFreed,
                blockingGcCount,
                blockingGcTimeMs,
            ).all { value -> value == null || value >= 0L },
        ) { "ART runtime diagnostics contain a negative counter." }
    }

    companion object {
        fun fromRuntimeStats(stats: Map<String, String>) =
            SourceSeparationArtRuntimeDiagnostics(
                gcCount = stats.longValue(ART_GC_COUNT),
                gcTimeMs = stats.longValue(ART_GC_TIME),
                bytesAllocated = stats.longValue(ART_BYTES_ALLOCATED),
                bytesFreed = stats.longValue(ART_BYTES_FREED),
                blockingGcCount = stats.longValue(ART_BLOCKING_GC_COUNT),
                blockingGcTimeMs = stats.longValue(ART_BLOCKING_GC_TIME),
            )

        private fun Map<String, String>.longValue(key: String): Long? =
            get(key)?.trim()?.toLongOrNull()?.takeIf { value -> value >= 0L }

        private const val ART_GC_COUNT = "art.gc.gc-count"
        private const val ART_GC_TIME = "art.gc.gc-time"
        private const val ART_BYTES_ALLOCATED = "art.gc.bytes-allocated"
        private const val ART_BYTES_FREED = "art.gc.bytes-freed"
        private const val ART_BLOCKING_GC_COUNT = "art.gc.blocking-gc-count"
        private const val ART_BLOCKING_GC_TIME = "art.gc.blocking-gc-time"
    }
}

@Serializable
internal enum class SourceSeparationSmapsSource {
    Rollup,
    Full,
    Unavailable,
}

@Serializable
internal enum class SourceSeparationProcessSessionState {
    Empty,
    Creating,
    Resident,
    Poisoned,
    Recycling,
}

@Serializable
internal data class SourceSeparationProcessSessionDiagnostics(
    val state: SourceSeparationProcessSessionState,
    val backendPolicy: SourceSeparationExecutionBackendPolicy? = null,
    val sessionId: String? = null,
    val sessionKey: String? = null,
    val nativeSessionCreationCount: Int = 0,
    val activeLeaseCount: Int = 0,
    val invocationCount: Long = 0L,
    val poisoned: Boolean = false,
    val poisonReason: String? = null,
    val recycleReason: String? = null,
    val recycleToken: String? = null,
) {
    init {
        require(nativeSessionCreationCount >= 0 && activeLeaseCount >= 0 && invocationCount >= 0L) {
            "Process session diagnostic count is invalid."
        }
        require(poisoned == (state == SourceSeparationProcessSessionState.Poisoned) ||
            state == SourceSeparationProcessSessionState.Recycling
        ) {
            "Process session poison state is inconsistent."
        }
    }

    companion object {
        fun empty() = SourceSeparationProcessSessionDiagnostics(
            state = SourceSeparationProcessSessionState.Empty,
        )
    }
}

internal fun SourceSeparationProcessSessionDiagnostics.requiresRecycleFor(
    requestedIdentity: SourceSeparationExecutionSessionIdentity,
): Boolean {
    if (backendPolicy != null && backendPolicy != requestedIdentity.backendPolicy) return true
    return when (state) {
        SourceSeparationProcessSessionState.Resident ->
            sessionKey != requestedIdentity.diagnosticKey
        SourceSeparationProcessSessionState.Poisoned,
        SourceSeparationProcessSessionState.Recycling,
        -> true
        SourceSeparationProcessSessionState.Empty,
        SourceSeparationProcessSessionState.Creating,
        -> false
    }
}

@Serializable
internal data class SourceSeparationProcessValidationOverrideDiagnostics(
    val modelId: String,
    val artifactSha256: String,
    val contractId: String,
    val originalStatus: String,
    val originalReason: String,
    val originalEvidence: String,
    val effectiveStatus: String,
    val effectiveReason: String,
) {
    init {
        require(modelId.isNotBlank() && contractId.isNotBlank()) {
            "Process validation override identity is incomplete."
        }
        require(SHA256_PATTERN.matches(artifactSha256)) {
            "Process validation override artifact hash is invalid."
        }
        require(originalStatus == "Unsupported") {
            "Process validation override must preserve an Unsupported source record."
        }
        require(effectiveStatus == "KnownGood") {
            "Process validation override must be explicitly validation-only KnownGood."
        }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

internal object SourceSeparationProcessDiagnosticsCollector {
    fun capture(
        processGeneration: Long,
        processName: String,
        activeRunId: String? = null,
        session: SourceSeparationProcessSessionDiagnostics =
            SourceSeparationProcessSessionDiagnostics.empty(),
        validationOverride: SourceSeparationProcessValidationOverrideDiagnostics? = null,
        foregroundService: SourceSeparationForegroundServiceDiagnostics =
            SourceSeparationForegroundServiceDiagnostics(),
        processingWakeLock: SourceSeparationProcessingWakeLockDiagnostics =
            SourceSeparationProcessingWakeLockDiagnostics(),
        procRoot: File = File("/proc/self"),
    ): SourceSeparationProcessDiagnostics {
        val status = readText(File(procRoot, "status"))
        val maps = readLines(File(procRoot, "maps"))
        val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val smaps = readSmaps(procRoot)
        val runtime = Runtime.getRuntime()
        val artRuntime = runCatching {
            SourceSeparationArtRuntimeDiagnostics.fromRuntimeStats(Debug.getRuntimeStats())
        }.getOrDefault(SourceSeparationArtRuntimeDiagnostics())
        val processStartTicks = SourceSeparationProcParser.parseProcessStartTicks(
            requireNotNull(readText(File(procRoot, "stat"))) {
                "Unable to read the inference process start identity."
            },
        ) ?: error("Unable to parse the inference process start identity.")
        return SourceSeparationProcessDiagnostics(
            processGeneration = processGeneration,
            processName = processName,
            pid = Process.myPid(),
            processStartTicks = processStartTicks,
            capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L),
            activeRunId = activeRunId,
            memory = SourceSeparationProcessMemoryDiagnostics(
                vmSizeBytes = SourceSeparationProcParser.parseStatusKilobytes(status, "VmSize"),
                vmPeakBytes = SourceSeparationProcParser.parseStatusKilobytes(status, "VmPeak"),
                vmRssBytes = SourceSeparationProcParser.parseStatusKilobytes(status, "VmRSS"),
                vmDataBytes = SourceSeparationProcParser.parseStatusKilobytes(status, "VmData"),
                pssBytes = memoryInfo.totalPss.toLong() * KIBIBYTE,
                nativePssBytes = memoryInfo.nativePss.toLong() * KIBIBYTE,
                threadCount = SourceSeparationProcParser.parseStatusCount(status, "Threads")
                    ?: File(procRoot, "task").list()?.size
                    ?: 0,
                mappedRegionCount = maps.size,
                smapsSource = smaps.source,
                anonHugePagesBytes = smaps.anonHugePagesBytes,
                largestFreeAddressGapBytes =
                    SourceSeparationProcParser.largestMappedAddressGapBytes(maps),
                ussBytes = (memoryInfo.totalPrivateDirty.toLong() +
                    memoryInfo.totalPrivateClean.toLong()) * KIBIBYTE,
                javaPssBytes = memoryInfo.summaryBytes("summary.java-heap", memoryInfo.dalvikPss),
                graphicsPssBytes = memoryInfo.summaryBytes("summary.graphics", 0),
                javaHeapAllocatedBytes = (runtime.totalMemory() - runtime.freeMemory())
                    .coerceAtLeast(0L),
                nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L),
                runtimeMaxMemoryBytes = runtime.maxMemory().coerceAtLeast(0L),
                processCpuTimeMs = Process.getElapsedCpuTime().coerceAtLeast(0L),
                oomScoreAdj = readText(File(procRoot, "oom_score_adj"))?.trim()?.toIntOrNull(),
                artRuntime = artRuntime,
            ),
            mappedNativeLibraries = SourceSeparationProcParser.mappedNativeLibraryNames(maps),
            session = session,
            validationOverride = validationOverride,
            foregroundService = foregroundService,
            processingWakeLock = processingWakeLock,
        )
    }

    private fun readSmaps(procRoot: File): SmapsDiagnostics {
        readText(File(procRoot, "smaps_rollup"))?.let { text ->
            return SmapsDiagnostics(
                source = SourceSeparationSmapsSource.Rollup,
                anonHugePagesBytes = SourceSeparationProcParser.sumKilobyteFields(
                    text,
                    "AnonHugePages",
                ),
            )
        }
        readText(File(procRoot, "smaps"))?.let { text ->
            return SmapsDiagnostics(
                source = SourceSeparationSmapsSource.Full,
                anonHugePagesBytes = SourceSeparationProcParser.sumKilobyteFields(
                    text,
                    "AnonHugePages",
                ),
            )
        }
        return SmapsDiagnostics(SourceSeparationSmapsSource.Unavailable, null)
    }

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun readLines(file: File): List<String> =
        runCatching { file.readLines() }.getOrDefault(emptyList())

    private fun Debug.MemoryInfo.summaryBytes(key: String, fallbackKiB: Int): Long =
        (memoryStats[key]?.toLongOrNull() ?: fallbackKiB.toLong()) * KIBIBYTE

    private data class SmapsDiagnostics(
        val source: SourceSeparationSmapsSource,
        val anonHugePagesBytes: Long?,
    )

    private const val KIBIBYTE = 1_024L
}

internal object SourceSeparationProcParser {
    fun parseProcessStartTicks(stat: String): Long? {
        val commandEnd = stat.lastIndexOf(')')
        if (commandEnd < 0 || commandEnd + 2 >= stat.length) return null
        val fieldsFromState = stat.substring(commandEnd + 2)
            .trim()
            .split(WHITESPACE)
        return fieldsFromState.getOrNull(PROCESS_START_TICKS_OFFSET_FROM_STATE)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun parseStatusKilobytes(status: String?, field: String): Long? {
        val value = findFieldValue(status, field)?.substringBefore(' ')?.toLongOrNull()
            ?: return null
        return runCatching { Math.multiplyExact(value, KIBIBYTE) }.getOrNull()
    }

    fun parseStatusCount(status: String?, field: String): Int? =
        findFieldValue(status, field)?.substringBefore(' ')?.toIntOrNull()

    fun sumKilobyteFields(text: String, field: String): Long? {
        var found = false
        var total = 0L
        text.lineSequence().forEach { line ->
            if (line.substringBefore(':') != field) return@forEach
            val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                ?: return@forEach
            found = true
            total = runCatching { Math.addExact(total, Math.multiplyExact(value, KIBIBYTE)) }
                .getOrDefault(Long.MAX_VALUE)
        }
        return total.takeIf { found }
    }

    fun largestMappedAddressGapBytes(maps: List<String>): Long? {
        val ranges = maps.mapNotNull { line ->
            val range = line.substringBefore(' ')
            val separator = range.indexOf('-')
            if (separator <= 0 || separator == range.lastIndex) return@mapNotNull null
            val start = range.substring(0, separator).toULongOrNull(16) ?: return@mapNotNull null
            val end = range.substring(separator + 1).toULongOrNull(16) ?: return@mapNotNull null
            if (end <= start) null else start to end
        }.sortedBy { it.first }
        if (ranges.size < 2) return null
        var previousEnd = ranges.first().second
        var largest = 0UL
        ranges.drop(1).forEach { (start, end) ->
            if (start > previousEnd) largest = maxOf(largest, start - previousEnd)
            previousEnd = maxOf(previousEnd, end)
        }
        return largest.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
    }

    fun mappedNativeLibraryNames(maps: List<String>): List<String> = maps
        .asSequence()
        .mapNotNull { line ->
            line.trim()
                .split(WHITESPACE, limit = MAPS_FIELD_COUNT)
                .getOrNull(MAPS_PATH_INDEX)
        }
        .map { path -> path.removeSuffix(" (deleted)").substringAfterLast('/') }
        .filter { name -> name.endsWith(".so") || ".so." in name }
        .distinct()
        .sorted()
        .toList()

    private fun findFieldValue(status: String?, field: String): String? = status
        ?.lineSequence()
        ?.firstOrNull { it.substringBefore(':') == field }
        ?.substringAfter(':')
        ?.trim()

    private val WHITESPACE = Regex("\\s+")
    private const val MAPS_FIELD_COUNT = 6
    private const val MAPS_PATH_INDEX = 5
    private const val PROCESS_START_TICKS_OFFSET_FROM_STATE = 19
    private const val KIBIBYTE = 1_024L
}
