package com.mardous.booming.debug

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class SourceSeparationDebugOperationRegistry(
    private val maxRetainedOperations: Int = DEFAULT_MAX_RETAINED_OPERATIONS,
) {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = linkedMapOf<String, MutableOperation>()

    init {
        require(maxRetainedOperations > 0) { "Operation history limit must be positive." }
    }

    fun submit(
        kind: String,
        target: String? = null,
        block: suspend SourceSeparationDebugOperationContext.() -> JSONObject,
    ): SourceSeparationDebugOperationSnapshot = submitSerialized(kind, target) {
        block().toString()
    }

    internal fun submitSerializedForTest(
        kind: String,
        target: String? = null,
        block: suspend SourceSeparationDebugOperationContext.() -> String,
    ): SourceSeparationDebugOperationSnapshot = submitSerialized(kind, target, block)

    private fun submitSerialized(
        kind: String,
        target: String?,
        block: suspend SourceSeparationDebugOperationContext.() -> String,
    ): SourceSeparationDebugOperationSnapshot {
        require(kind.isNotBlank()) { "Operation kind is empty." }
        val operation = synchronized(lock) {
            operations.values.firstOrNull { existing ->
                existing.status.isActive && existing.kind == kind && existing.target == target
            }?.let { existing ->
                throw IllegalStateException(
                    "Operation ${existing.id} is already active for $kind${target?.let { ":$it" }.orEmpty()}.",
                )
            }
            MutableOperation(
                id = "op-${System.currentTimeMillis()}-${sequence.incrementAndGet()}",
                kind = kind,
                target = target,
                createdAtEpochMs = System.currentTimeMillis(),
            ).also { created ->
                operations[created.id] = created
                trimHistoryLocked()
            }
        }
        val context = SourceSeparationDebugOperationContext(operation, lock)
        operation.job = scope.launch {
            synchronized(lock) {
                if (operation.cancelRequested) {
                    operation.completedAtEpochMs = System.currentTimeMillis()
                    operation.cancelAction = null
                    trimHistoryLocked()
                    return@launch
                }
                operation.status = SourceSeparationDebugOperationStatus.Running
                operation.startedAtEpochMs = System.currentTimeMillis()
            }
            try {
                val result = context.block()
                coroutineContext.ensureActive()
                synchronized(lock) {
                    if (!operation.cancelRequested) {
                        operation.status = SourceSeparationDebugOperationStatus.Succeeded
                        operation.resultJson = result
                        operation.message = null
                    }
                }
            } catch (error: CancellationException) {
                synchronized(lock) {
                    operation.status = SourceSeparationDebugOperationStatus.Canceled
                    operation.message = error.message ?: "Operation canceled."
                }
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (operation.cancelRequested) {
                        operation.status = SourceSeparationDebugOperationStatus.Canceled
                        operation.message = "Operation canceled."
                    } else {
                        operation.status = SourceSeparationDebugOperationStatus.Failed
                        operation.message = error.message ?: error::class.java.simpleName
                        operation.errorType = error::class.java.name
                    }
                }
            } finally {
                synchronized(lock) {
                    operation.completedAtEpochMs = System.currentTimeMillis()
                    operation.cancelAction = null
                    trimHistoryLocked()
                }
            }
        }
        return snapshot(operation.id)
    }

    fun snapshot(id: String): SourceSeparationDebugOperationSnapshot = synchronized(lock) {
        operations[id]?.snapshot() ?: throw IllegalArgumentException("Unknown operation '$id'.")
    }

    fun snapshots(): List<SourceSeparationDebugOperationSnapshot> = synchronized(lock) {
        operations.values.toList().asReversed().map(MutableOperation::snapshot)
    }

    fun cancel(id: String): SourceSeparationDebugOperationSnapshot {
        val operation = synchronized(lock) {
            operations[id] ?: throw IllegalArgumentException("Unknown operation '$id'.")
        }
        val cancelAction = synchronized(lock) {
            if (!operation.status.isActive) return operation.snapshot()
            operation.cancelRequested = true
            operation.status = SourceSeparationDebugOperationStatus.Canceled
            operation.message = "Cancellation requested."
            operation.completedAtEpochMs = System.currentTimeMillis()
            operation.cancelAction
        }
        runCatching { cancelAction?.invoke() }
        operation.job?.cancel(CancellationException("Canceled through ADB debug control."))
        return synchronized(lock) { operation.snapshot() }
    }

    fun close() {
        val cancelActions = synchronized(lock) {
            operations.values.filter { it.status.isActive }.forEach { operation ->
                operation.cancelRequested = true
                operation.status = SourceSeparationDebugOperationStatus.Canceled
                operation.message = "Debug control provider stopped."
                operation.completedAtEpochMs = System.currentTimeMillis()
            }
            operations.values.mapNotNull { operation ->
                operation.cancelAction.also { operation.cancelAction = null }
            }
        }
        cancelActions.forEach { action -> runCatching(action) }
        scope.cancel()
    }

    private fun trimHistoryLocked() {
        while (operations.size > maxRetainedOperations) {
            val removable = operations.entries.firstOrNull { !it.value.status.isActive } ?: return
            operations.remove(removable.key)
        }
    }

    private companion object {
        const val DEFAULT_MAX_RETAINED_OPERATIONS = 64
    }
}

internal class SourceSeparationDebugOperationContext(
    private val operation: MutableOperation,
    private val lock: Any,
) {
    fun progress(
        downloadedBytes: Long,
        totalBytes: Long,
        stage: String? = null,
        message: String? = null,
    ) {
        require(downloadedBytes >= 0L) { "Downloaded byte count is negative." }
        require(totalBytes >= 0L) { "Total byte count is negative." }
        ensureActive()
        synchronized(lock) {
            operation.downloadedBytes = downloadedBytes
            operation.totalBytes = totalBytes
            operation.stage = stage
            operation.message = message
        }
    }

    fun stage(stage: String, message: String? = null) {
        ensureActive()
        synchronized(lock) {
            operation.stage = stage
            operation.message = message
        }
    }

    fun onCancel(action: () -> Unit) {
        val invokeImmediately = synchronized(lock) {
            if (operation.cancelRequested) true else {
                operation.cancelAction = action
                false
            }
        }
        if (invokeImmediately) action()
    }

    fun ensureActive() {
        if (synchronized(lock) { operation.cancelRequested } || Thread.currentThread().isInterrupted) {
            throw CancellationException("Operation canceled.")
        }
    }
}

internal data class SourceSeparationDebugOperationSnapshot(
    val id: String,
    val kind: String,
    val target: String?,
    val status: SourceSeparationDebugOperationStatus,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long?,
    val completedAtEpochMs: Long?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val stage: String?,
    val message: String?,
    val errorType: String?,
    val resultJson: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("target", target)
        .put("status", status.wireName)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("completedAtEpochMs", completedAtEpochMs)
        .put("downloadedBytes", downloadedBytes)
        .put("totalBytes", totalBytes)
        .put("stage", stage)
        .put("message", message)
        .put("errorType", errorType)
        .put("result", resultJson?.let(::JSONObject))
}

internal fun List<SourceSeparationDebugOperationSnapshot>.toJson(): JSONArray =
    JSONArray().also { array -> forEach { array.put(it.toJson()) } }

internal enum class SourceSeparationDebugOperationStatus(val wireName: String) {
    Queued("queued"),
    Running("running"),
    Succeeded("succeeded"),
    Failed("failed"),
    Canceled("canceled"),
    ;

    val isActive: Boolean
        get() = this == Queued || this == Running
}

internal class MutableOperation(
    val id: String,
    val kind: String,
    val target: String?,
    val createdAtEpochMs: Long,
) {
    var status = SourceSeparationDebugOperationStatus.Queued
    var startedAtEpochMs: Long? = null
    var completedAtEpochMs: Long? = null
    var downloadedBytes: Long = 0L
    var totalBytes: Long = 0L
    var stage: String? = null
    var message: String? = null
    var errorType: String? = null
    var resultJson: String? = null
    var cancelRequested = false
    var cancelAction: (() -> Unit)? = null
    var job: Job? = null

    fun snapshot() = SourceSeparationDebugOperationSnapshot(
        id = id,
        kind = kind,
        target = target,
        status = status,
        createdAtEpochMs = createdAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        stage = stage,
        message = message,
        errorType = errorType,
        resultJson = resultJson,
    )
}
