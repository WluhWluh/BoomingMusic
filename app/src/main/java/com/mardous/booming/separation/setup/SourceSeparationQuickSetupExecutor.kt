package com.mardous.booming.separation.setup

import android.content.SharedPreferences
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import com.mardous.booming.util.writeSourceSeparationGpuEnabled
import java.util.concurrent.CancellationException

internal enum class SourceSeparationQuickSetupTerminalStatus {
    Completed,
    PartiallyCompleted,
    Blocked,
    Canceled,
    Failed,
}

internal data class SourceSeparationQuickSetupItemResult(
    val itemId: String,
    val state: SourceSeparationQuickSetupItemState,
    val errorMessage: String? = null,
)

internal data class SourceSeparationQuickSetupProgress(
    val itemId: String,
    val itemIndex: Int,
    val itemCount: Int,
    val state: SourceSeparationQuickSetupItemState,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

internal data class SourceSeparationQuickSetupResult(
    val status: SourceSeparationQuickSetupTerminalStatus,
    val plan: SourceSeparationQuickSetupPlan,
    val itemResults: List<SourceSeparationQuickSetupItemResult>,
    val readiness: LocalSeparationReadiness,
    val errorMessage: String? = null,
)

internal class SourceSeparationQuickSetupStalePlanException : IllegalStateException(
    "The local separation setup plan is stale and must be reviewed again.",
)

internal data class SourceSeparationQuickSetupSelectionSnapshot(
    val activeModel: SourceSeparationActiveModelReference?,
    val pendingModel: SourceSeparationActiveModelReference?,
    val gpuEnabled: Boolean,
)

internal interface SourceSeparationQuickSetupOperations {
    fun executeItem(
        item: SourceSeparationQuickSetupPlanItem,
        shouldCancel: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    )

    fun selectionSnapshot(): SourceSeparationQuickSetupSelectionSnapshot

    fun commitSelection(
        activeModel: SourceSeparationActiveModelReference?,
        gpuEnabled: Boolean?,
    )

    fun restoreSelection(snapshot: SourceSeparationQuickSetupSelectionSnapshot)
}

internal class SourceSeparationQuickSetupExecutor internal constructor(
    private val operations: SourceSeparationQuickSetupOperations,
    private val readinessEvaluator: () -> LocalSeparationReadiness,
) {
    constructor(
        runtimeStore: SourceSeparationRuntimeStore,
        gpuRuntimeStore: SourceSeparationGpuRuntimeStore,
        presetRepository: SourceSeparationPresetRepository,
        modelInstaller: SourceSeparationQuickSetupModelInstaller,
        preferences: SharedPreferences,
        readinessEvaluator: () -> LocalSeparationReadiness,
        platformProvider: () -> MdxRuntimePlatform = {
            AndroidMdxRuntimePlatformProvider.current()
        },
    ) : this(
        operations = DefaultSourceSeparationQuickSetupOperations(
            runtimeStore = runtimeStore,
            gpuRuntimeStore = gpuRuntimeStore,
            presetRepository = presetRepository,
            modelInstaller = modelInstaller,
            preferences = preferences,
            platformProvider = platformProvider,
        ),
        readinessEvaluator = readinessEvaluator,
    )

    fun execute(
        plan: SourceSeparationQuickSetupPlan,
        selectedItemIds: Set<String> = plan.selectedItems.mapTo(mutableSetOf()) {
            it.itemId
        },
        shouldCancel: () -> Boolean = { false },
        onProgress: (SourceSeparationQuickSetupProgress) -> Unit = {},
    ): SourceSeparationQuickSetupResult {
        val initialReadiness = readinessEvaluator()
        if (initialReadiness.fingerprint != plan.inputReadinessFingerprint) {
            throw SourceSeparationQuickSetupStalePlanException()
        }
        validateSelection(plan, selectedItemIds)
        val initialSelection = operations.selectionSnapshot()
        val resultItems = mutableListOf<SourceSeparationQuickSetupItemResult>()
        val selectedItems = scheduleSelectedItems(plan, selectedItemIds)
        var optionalFailureMessage: String? = null
        try {
            selectedItems.forEachIndexed { index, item ->
                if (shouldCancel()) {
                    markRemainingCanceled(
                        selectedItems = selectedItems,
                        firstUnprocessedIndex = index,
                        results = resultItems,
                        onProgress = onProgress,
                    )
                    return finish(
                        plan = plan,
                        resultItems = resultItems,
                        status = SourceSeparationQuickSetupTerminalStatus.Canceled,
                        readiness = readinessEvaluator(),
                    )
                }
                onProgress(
                    SourceSeparationQuickSetupProgress(
                        itemId = item.itemId,
                        itemIndex = index,
                        itemCount = selectedItems.size,
                        state = SourceSeparationQuickSetupItemState.Running,
                    ),
                )
                try {
                    operations.executeItem(
                        item = item,
                        shouldCancel = shouldCancel,
                        onProgress = { downloadedBytes, totalBytes ->
                            onProgress(
                                SourceSeparationQuickSetupProgress(
                                    itemId = item.itemId,
                                    itemIndex = index,
                                    itemCount = selectedItems.size,
                                    state = SourceSeparationQuickSetupItemState.Running,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                        },
                    )
                    resultItems += SourceSeparationQuickSetupItemResult(
                        itemId = item.itemId,
                        state = SourceSeparationQuickSetupItemState.Succeeded,
                    )
                    onProgress(
                        SourceSeparationQuickSetupProgress(
                            itemId = item.itemId,
                            itemIndex = index,
                            itemCount = selectedItems.size,
                            state = SourceSeparationQuickSetupItemState.Succeeded,
                        ),
                    )
                } catch (error: CancellationException) {
                    resultItems += SourceSeparationQuickSetupItemResult(
                        itemId = item.itemId,
                        state = SourceSeparationQuickSetupItemState.Skipped,
                        errorMessage = "Canceled before this item completed.",
                    )
                    markRemainingCanceled(
                        selectedItems = selectedItems,
                        firstUnprocessedIndex = index + 1,
                        results = resultItems,
                        onProgress = onProgress,
                    )
                    return finish(
                        plan = plan,
                        resultItems = resultItems,
                        status = SourceSeparationQuickSetupTerminalStatus.Canceled,
                        readiness = readinessEvaluator(),
                    )
                } catch (error: Throwable) {
                    val message = error.message ?: "Setup item failed."
                    resultItems += SourceSeparationQuickSetupItemResult(
                        itemId = item.itemId,
                        state = SourceSeparationQuickSetupItemState.Failed,
                        errorMessage = message,
                    )
                    onProgress(
                        SourceSeparationQuickSetupProgress(
                            itemId = item.itemId,
                            itemIndex = index,
                            itemCount = selectedItems.size,
                            state = SourceSeparationQuickSetupItemState.Failed,
                        ),
                    )
                    if (item.requirement == SourceSeparationQuickSetupRequirement.Required) {
                        markRemainingSkipped(
                            selectedItems = selectedItems,
                            firstUnprocessedIndex = index + 1,
                            results = resultItems,
                            onProgress = onProgress,
                        )
                        return finish(
                            plan = plan,
                            resultItems = resultItems,
                            status = SourceSeparationQuickSetupTerminalStatus.Blocked,
                            readiness = readinessEvaluator(),
                            errorMessage = message,
                        )
                    }
                    optionalFailureMessage = optionalFailureMessage ?: message
                }
            }
        } catch (error: CancellationException) {
            return finish(
                plan = plan,
                resultItems = resultItems,
                status = SourceSeparationQuickSetupTerminalStatus.Canceled,
                readiness = readinessEvaluator(),
            )
        }

        if (shouldCancel()) {
            return finish(
                plan = plan,
                resultItems = resultItems,
                status = SourceSeparationQuickSetupTerminalStatus.Canceled,
                readiness = readinessEvaluator(),
            )
        }

        val selectionBeforeCommit = operations.selectionSnapshot()
        if (selectionBeforeCommit != initialSelection) {
            return finish(
                plan = plan,
                resultItems = resultItems,
                status = SourceSeparationQuickSetupTerminalStatus.Failed,
                readiness = readinessEvaluator(),
                errorMessage = "The active model or backend preference changed during setup.",
            )
        }

        val preserveRunnableSelection = optionalFailureMessage != null &&
            initialReadiness.isRunnable
        val proposedGpuEnabled = proposedGpuEnabled(plan, selectedItemIds, resultItems)
        var selectionCommitted = false
        if (!preserveRunnableSelection) {
            try {
                operations.commitSelection(
                    activeModel = plan.proposedActiveModel,
                    gpuEnabled = proposedGpuEnabled,
                )
                selectionCommitted = true
            } catch (error: Throwable) {
                operations.restoreSelection(initialSelection)
                return finish(
                    plan = plan,
                    resultItems = resultItems,
                    status = SourceSeparationQuickSetupTerminalStatus.Failed,
                    readiness = readinessEvaluator(),
                    errorMessage = error.message ?: "The setup selection could not be committed.",
                )
            }
        }

        val committedReadiness = readinessEvaluator()
        if (!committedReadiness.isRunnable) {
            if (selectionCommitted) operations.restoreSelection(initialSelection)
            val restoredReadiness = readinessEvaluator()
            return finish(
                plan = plan,
                resultItems = resultItems,
                status = SourceSeparationQuickSetupTerminalStatus.Blocked,
                readiness = restoredReadiness,
                errorMessage = committedReadiness.blockers.joinToString(" ") { it.detail }
                    .ifBlank { "The installed resources did not produce a runnable path." },
            )
        }

        return finish(
            plan = plan,
            resultItems = resultItems,
            status = if (optionalFailureMessage != null) {
                SourceSeparationQuickSetupTerminalStatus.PartiallyCompleted
            } else {
                SourceSeparationQuickSetupTerminalStatus.Completed
            },
            readiness = committedReadiness,
            errorMessage = optionalFailureMessage,
        )
    }

    private fun proposedGpuEnabled(
        plan: SourceSeparationQuickSetupPlan,
        selectedItemIds: Set<String>,
        results: List<SourceSeparationQuickSetupItemResult>,
    ): Boolean? {
        if (plan.proposedGpuEnabled == null) return null
        val gpuItem = plan.items.singleOrNull { item ->
            item.action == SourceSeparationQuickSetupAction.InstallGpuRuntime ||
                item.action == SourceSeparationQuickSetupAction.RepairGpuRuntime ||
                item.action == SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime ||
                item.action == SourceSeparationQuickSetupAction.ConfigureGpuRuntime
        }
        val gpuSucceeded = gpuItem == null || results.any { result ->
            result.itemId == gpuItem.itemId &&
                result.state == SourceSeparationQuickSetupItemState.Succeeded
        }
        return plan.proposedGpuEnabled &&
            (gpuItem == null || gpuItem.itemId in selectedItemIds && gpuSucceeded)
    }

    private fun scheduleSelectedItems(
        plan: SourceSeparationQuickSetupPlan,
        selectedItemIds: Set<String>,
    ): List<SourceSeparationQuickSetupPlanItem> {
        val originalOrder = plan.items.withIndex().associate { it.value.itemId to it.index }
        val remaining = plan.items.filter { it.itemId in selectedItemIds }.toMutableList()
        val scheduled = mutableListOf<SourceSeparationQuickSetupPlanItem>()
        val scheduledIds = mutableSetOf<String>()
        while (remaining.isNotEmpty()) {
            val next = remaining
                .filter { item -> item.dependencyIds.all(scheduledIds::contains) }
                .minWithOrNull(
                    compareBy<SourceSeparationQuickSetupPlanItem>(
                        { requirementPriority(it.requirement) },
                        { originalOrder.getValue(it.itemId) },
                    ),
                ) ?: error("The setup plan contains a dependency cycle.")
            remaining.remove(next)
            scheduled += next
            scheduledIds += next.itemId
        }
        return scheduled
    }

    private fun requirementPriority(requirement: SourceSeparationQuickSetupRequirement): Int =
        when (requirement) {
            SourceSeparationQuickSetupRequirement.Required -> 0
            SourceSeparationQuickSetupRequirement.Recommended -> 1
            SourceSeparationQuickSetupRequirement.Optional -> 2
        }

    private fun validateSelection(
        plan: SourceSeparationQuickSetupPlan,
        selectedItemIds: Set<String>,
    ) {
        val knownIds = plan.items.mapTo(mutableSetOf()) { it.itemId }
        require(selectedItemIds.all(knownIds::contains)) {
            "The setup selection contains an unknown item."
        }
        require(plan.requiredItems.all { it.itemId in selectedItemIds }) {
            "Every required setup item must remain selected."
        }
        plan.items.forEach { item ->
            if (item.itemId !in selectedItemIds) return@forEach
            require(item.dependencyIds.all(selectedItemIds::contains)) {
                "The setup selection is missing a dependency for ${item.itemId}."
            }
        }
    }

    private fun markRemainingCanceled(
        selectedItems: List<SourceSeparationQuickSetupPlanItem>,
        firstUnprocessedIndex: Int,
        results: MutableList<SourceSeparationQuickSetupItemResult>,
        onProgress: (SourceSeparationQuickSetupProgress) -> Unit,
    ) {
        selectedItems.drop(firstUnprocessedIndex).forEachIndexed { offset, item ->
            results += SourceSeparationQuickSetupItemResult(
                itemId = item.itemId,
                state = SourceSeparationQuickSetupItemState.Skipped,
                errorMessage = "Canceled before this item started.",
            )
            onProgress(
                SourceSeparationQuickSetupProgress(
                    itemId = item.itemId,
                    itemIndex = firstUnprocessedIndex + offset,
                    itemCount = selectedItems.size,
                    state = SourceSeparationQuickSetupItemState.Skipped,
                ),
            )
        }
    }

    private fun markRemainingSkipped(
        selectedItems: List<SourceSeparationQuickSetupPlanItem>,
        firstUnprocessedIndex: Int,
        results: MutableList<SourceSeparationQuickSetupItemResult>,
        onProgress: (SourceSeparationQuickSetupProgress) -> Unit,
    ) {
        selectedItems.drop(firstUnprocessedIndex).forEachIndexed { offset, item ->
            results += SourceSeparationQuickSetupItemResult(
                itemId = item.itemId,
                state = SourceSeparationQuickSetupItemState.Skipped,
                errorMessage = "Skipped after a required setup item failed.",
            )
            onProgress(
                SourceSeparationQuickSetupProgress(
                    itemId = item.itemId,
                    itemIndex = firstUnprocessedIndex + offset,
                    itemCount = selectedItems.size,
                    state = SourceSeparationQuickSetupItemState.Skipped,
                ),
            )
        }
    }

    private fun finish(
        plan: SourceSeparationQuickSetupPlan,
        resultItems: List<SourceSeparationQuickSetupItemResult>,
        status: SourceSeparationQuickSetupTerminalStatus,
        readiness: LocalSeparationReadiness = readinessEvaluator(),
        errorMessage: String? = null,
    ) = SourceSeparationQuickSetupResult(
        status = status,
        plan = plan,
        itemResults = resultItems,
        readiness = readiness,
        errorMessage = errorMessage,
    )
}

private class DefaultSourceSeparationQuickSetupOperations(
    private val runtimeStore: SourceSeparationRuntimeStore,
    private val gpuRuntimeStore: SourceSeparationGpuRuntimeStore,
    private val presetRepository: SourceSeparationPresetRepository,
    private val modelInstaller: SourceSeparationQuickSetupModelInstaller,
    private val preferences: SharedPreferences,
    private val platformProvider: () -> MdxRuntimePlatform,
) : SourceSeparationQuickSetupOperations {
    override fun executeItem(
        item: SourceSeparationQuickSetupPlanItem,
        shouldCancel: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        when (item.action) {
            SourceSeparationQuickSetupAction.InstallRuntime -> {
                val result = runtimeStore.install(requireNotNull(item.componentId), onProgress)
                require(result.state == SourceSeparationRuntimeState.Installed) {
                    result.reason ?: "The CPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.RepairRuntime -> {
                val result = runtimeStore.repair(requireNotNull(item.componentId), onProgress)
                require(result.state == SourceSeparationRuntimeState.Installed) {
                    result.reason ?: "The repaired CPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.ActivatePendingRuntime -> {
                val result = runtimeStore.activatePending(requireNotNull(item.componentId))
                require(result.state == SourceSeparationRuntimeState.Installed) {
                    result.reason ?: "The pending CPU runtime could not be activated."
                }
            }

            SourceSeparationQuickSetupAction.InstallGpuRuntime -> {
                val result = gpuRuntimeStore.install(requireNotNull(item.componentId), onProgress)
                require(result.state == SourceSeparationGpuRuntimeState.Installed) {
                    result.reason ?: "The GPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.RepairGpuRuntime -> {
                val result = gpuRuntimeStore.repair(requireNotNull(item.componentId), onProgress)
                require(result.state == SourceSeparationGpuRuntimeState.Installed) {
                    result.reason ?: "The repaired GPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime -> {
                val result = gpuRuntimeStore.activatePending(requireNotNull(item.componentId))
                require(result.state == SourceSeparationGpuRuntimeState.Installed) {
                    result.reason ?: "The pending GPU runtime could not be activated."
                }
            }

            SourceSeparationQuickSetupAction.ConfigureGpuRuntime -> Unit

            SourceSeparationQuickSetupAction.InstallAndSelectModel -> {
                val modelId = requireNotNull(item.modelId)
                val expected = presetRepository.officialPreset(modelId)
                presetRepository.installedModels().singleOrNull {
                    it.sha256.equals(expected.sha256, ignoreCase = true)
                } ?: modelInstaller.install(
                    modelId = modelId,
                    onProgress = onProgress,
                    shouldCancel = shouldCancel,
                )
                if (shouldCancel()) throw CancellationException("Model installation canceled.")
            }

            SourceSeparationQuickSetupAction.Validate -> Unit

            SourceSeparationQuickSetupAction.Configure,
            SourceSeparationQuickSetupAction.RecycleProcess,
            SourceSeparationQuickSetupAction.OpenRuntimeManagement,
            SourceSeparationQuickSetupAction.OpenModelManagement,
            -> throw IllegalStateException("This setup item is not an install operation.")
        }
    }

    override fun selectionSnapshot(): SourceSeparationQuickSetupSelectionSnapshot =
        SourceSeparationQuickSetupSelectionSnapshot(
            activeModel = (presetRepository.activeModel() as?
                SourceSeparationActivePresetState.Reference)?.reference,
            pendingModel = presetRepository.pendingActiveModel(),
            gpuEnabled = preferences.readSourceSeparationGpuEnabled(),
        )

    override fun commitSelection(
        activeModel: SourceSeparationActiveModelReference?,
        gpuEnabled: Boolean?,
    ) {
        val current = (presetRepository.activeModel() as?
            SourceSeparationActivePresetState.Reference)?.reference
        if (activeModel != null && activeModel != current) {
            if (activeModel.profileId == null) {
                presetRepository.activate(
                    sha256 = activeModel.artifactSha256,
                    platform = platformProvider(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = false,
                )
            } else {
                presetRepository.activateCustomProfile(
                    sha256 = activeModel.artifactSha256,
                    profileId = activeModel.profileId,
                    platform = platformProvider(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                )
            }
        }
        gpuEnabled?.let(preferences::writeSourceSeparationGpuEnabled)
    }

    override fun restoreSelection(snapshot: SourceSeparationQuickSetupSelectionSnapshot) {
        presetRepository.restoreSetupSelection(
            activeReference = snapshot.activeModel,
            pendingReference = snapshot.pendingModel,
        )
        preferences.writeSourceSeparationGpuEnabled(snapshot.gpuEnabled)
    }
}
