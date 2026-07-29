package com.mardous.booming.separation.cache.v2

import com.mardous.booming.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Debug-only process-death barriers used by the Phase 4 device matrix. */
internal object SourceSeparationCacheFaultInjection {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var initializedRoot: File? = null
    private val occurrences = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    fun initialize(root: File) {
        if (!BuildConfig.DEBUG) return
        initializedRoot = root.absoluteFile
    }

    fun arm(root: File, control: SourceSeparationCacheFaultControl) {
        check(BuildConfig.DEBUG) { "Cache fault injection is debug-only." }
        val directory = faultDirectory(root).apply {
            require(exists() || mkdirs()) { "Unable to create cache fault directory." }
        }
        listOf(HIT_FILE_NAME, RELEASE_FILE_NAME).forEach { File(directory, it).delete() }
        writeDurably(
            File(directory, CONTROL_FILE_NAME),
            json.encodeToString(control).toByteArray(Charsets.UTF_8),
        )
    }

    fun release(root: File, token: String) {
        check(BuildConfig.DEBUG) { "Cache fault injection is debug-only." }
        writeDurably(
            File(faultDirectory(root), RELEASE_FILE_NAME),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    fun clear(root: File) {
        if (!BuildConfig.DEBUG) return
        faultDirectory(root).deleteRecursively()
    }

    fun readHit(root: File): SourceSeparationCacheFaultHit? {
        if (!BuildConfig.DEBUG) return null
        val file = File(faultDirectory(root), HIT_FILE_NAME)
        return runCatching {
            if (!file.isFile) null else json.decodeFromString(
                SourceSeparationCacheFaultHit.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull()
    }

    fun reach(
        stage: SourceSeparationCacheFaultStage,
        root: File? = initializedRoot,
        runtime: SourceSeparationCacheFaultRuntimeDiagnostics? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val selectedRoot = root ?: return
        val directory = faultDirectory(selectedRoot)
        val controlFile = File(directory, CONTROL_FILE_NAME)
        val control = runCatching {
            if (!controlFile.isFile) null else json.decodeFromString(
                SourceSeparationCacheFaultControl.serializer(),
                controlFile.readText(Charsets.UTF_8),
            )
        }.getOrNull() ?: return
        if (control.stage != stage) return
        val occurrence = occurrences.getOrPut(control.token) {
            java.util.concurrent.atomic.AtomicInteger(0)
        }.incrementAndGet()
        if (occurrence != control.occurrence) return
        writeDurably(
            File(directory, HIT_FILE_NAME),
            json.encodeToString(
                SourceSeparationCacheFaultHit(
                    token = control.token,
                    stage = stage,
                    occurrence = occurrence,
                    pid = android.os.Process.myPid(),
                    reachedAtElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                    runtime = runtime,
                )
            ).toByteArray(Charsets.UTF_8),
        )
        if (control.action == SourceSeparationCacheFaultAction.Notify) return

        val deadline = android.os.SystemClock.elapsedRealtime() + control.timeoutMs
        val releaseFile = File(directory, RELEASE_FILE_NAME)
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val releasedToken = runCatching {
                releaseFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
            }.getOrNull()
            if (releasedToken == control.token || !controlFile.isFile) return
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw SourceSeparationCacheFaultTimeoutException(stage, control.timeoutMs)
    }

    private fun faultDirectory(root: File) = File(root, DIRECTORY_NAME)

    private fun writeDurably(target: File, bytes: ByteArray) {
        val directory = requireNotNull(target.parentFile)
        require(directory.isDirectory) { "Cache fault directory is unavailable." }
        val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
        try {
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(0L)
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private const val DIRECTORY_NAME = "phase4-fault-injection"
    private const val CONTROL_FILE_NAME = "control.json"
    private const val HIT_FILE_NAME = "hit.json"
    private const val RELEASE_FILE_NAME = "release"
    private const val POLL_INTERVAL_MS = 10L
}

@Serializable
internal data class SourceSeparationCacheFaultControl(
    val token: String,
    val stage: SourceSeparationCacheFaultStage,
    val action: SourceSeparationCacheFaultAction,
    val occurrence: Int = 1,
    val timeoutMs: Long = 60_000L,
) {
    init {
        require(token.matches(Regex("^[A-Za-z0-9._-]{1,120}$"))) {
            "Cache fault token is invalid."
        }
        require(timeoutMs in 1_000L..120_000L) { "Cache fault timeout is invalid." }
        require(occurrence > 0) { "Cache fault occurrence is invalid." }
    }
}

@Serializable
internal data class SourceSeparationCacheFaultHit(
    val token: String,
    val stage: SourceSeparationCacheFaultStage,
    val occurrence: Int,
    val pid: Int,
    val reachedAtElapsedRealtimeNanos: Long,
    val runtime: SourceSeparationCacheFaultRuntimeDiagnostics? = null,
)

@Serializable
internal data class SourceSeparationCacheFaultRuntimeDiagnostics(
    val runtimeName: String,
    val backend: String,
    val fallbackStage: String? = null,
    val fallbackReason: String? = null,
) {
    init {
        require(runtimeName.isNotBlank()) { "Fault runtime name is empty." }
        require(backend.isNotBlank()) { "Fault runtime backend is empty." }
        require(fallbackStage == null || fallbackStage.isNotBlank()) {
            "Fault runtime fallback stage is empty."
        }
        require(fallbackReason == null || fallbackReason.isNotBlank()) {
            "Fault runtime fallback reason is empty."
        }
    }
}

@Serializable
internal enum class SourceSeparationCacheFaultAction {
    Barrier,
    Notify,
}

@Serializable
internal enum class SourceSeparationCacheFaultStage {
    Decode,
    Dsp,
    NativeInvocation,
    OutputPublish,
    JournalCommit,
    TerminalCommit,
    FlacHandoff,
}

internal class SourceSeparationCacheFaultTimeoutException(
    stage: SourceSeparationCacheFaultStage,
    timeoutMs: Long,
) : IllegalStateException("Cache fault barrier $stage timed out after $timeoutMs ms.")
