package com.mardous.booming.separation.setup

import android.content.SharedPreferences
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
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

internal class SourceSeparationQuickSetupExecutor(
    private val runtimeStore: SourceSeparationRuntimeStore,
    private val gpuRuntimeStore: SourceSeparationGpuRuntimeStore,
    private val presetRepository: SourceSeparationPresetRepository,
    private val modelInstaller: SourceSeparationQuickSetupModelInstaller,
    private val preferences: SharedPreferences,
    private val readinessEvaluator: () -> LocalSeparationReadiness,
    private val platformProvider: () -> MdxRuntimePlatform = {
        AndroidMdxRuntimePlatformProvider.current()
    },
) {
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
        val resultItems = mutableListOf<SourceSeparationQuickSetupItemResult>()
        val selectedItems = plan.items.filter { it.itemId in selectedItemIds }
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
                    executeItem(item, index, selectedItems.size, shouldCancel, onProgress)
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
                    )
                } catch (error: Throwable) {
                    val message = error.message ?: "Setup item failed."
                    resultItems += SourceSeparationQuickSetupItemResult(
                        itemId = item.itemId,
                        state = SourceSeparationQuickSetupItemState.Failed,
                        errorMessage = message,
                    )
                    val status = if (
                        item.requirement == SourceSeparationQuickSetupRequirement.Required
                    ) {
                        SourceSeparationQuickSetupTerminalStatus.Blocked
                    } else {
                        SourceSeparationQuickSetupTerminalStatus.PartiallyCompleted
                    }
                    markRemainingSkipped(
                        selectedItems = selectedItems,
                        firstUnprocessedIndex = index + 1,
                        results = resultItems,
                        onProgress = onProgress,
                    )
                    return finish(
                        plan = plan,
                        resultItems = resultItems,
                        status = status,
                        errorMessage = message,
                    )
                }
            }
        } catch (error: CancellationException) {
            return finish(
                plan = plan,
                resultItems = resultItems,
                status = SourceSeparationQuickSetupTerminalStatus.Canceled,
            )
        }
        val finalReadiness = readinessEvaluator()
        if (!finalReadiness.isRunnable) {
            return finish(
                plan = plan,
                resultItems = resultItems,
                status = SourceSeparationQuickSetupTerminalStatus.Blocked,
                readiness = finalReadiness,
                errorMessage = finalReadiness.blockers.joinToString(" ") { it.detail }
                    .ifBlank { "The installed resources did not produce a runnable path." },
            )
        }
        commitGpuPreference(plan, selectedItemIds)
        val committedReadiness = readinessEvaluator()
        return finish(
            plan = plan,
            resultItems = resultItems,
            status = if (resultItems.any { it.state == SourceSeparationQuickSetupItemState.Failed }) {
                SourceSeparationQuickSetupTerminalStatus.PartiallyCompleted
            } else {
                SourceSeparationQuickSetupTerminalStatus.Completed
            },
            readiness = committedReadiness,
        )
    }

    private fun executeItem(
        item: SourceSeparationQuickSetupPlanItem,
        itemIndex: Int,
        itemCount: Int,
        shouldCancel: () -> Boolean,
        onProgress: (SourceSeparationQuickSetupProgress) -> Unit,
    ) {
        when (item.action) {
            SourceSeparationQuickSetupAction.InstallRuntime -> {
                val componentId = requireNotNull(item.componentId)
                val result = runtimeStore.install(componentId) { downloaded, total ->
                    onProgress(
                        SourceSeparationQuickSetupProgress(
                            itemId = item.itemId,
                            itemIndex = itemIndex,
                            itemCount = itemCount,
                            state = SourceSeparationQuickSetupItemState.Running,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                }
                require(result.state == SourceSeparationRuntimeState.Installed) {
                    result.reason ?: "The CPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.RepairRuntime -> {
                val componentId = requireNotNull(item.componentId)
                val result = runtimeStore.repair(componentId) { downloaded, total ->
                    onProgress(
                        SourceSeparationQuickSetupProgress(
                            itemId = item.itemId,
                            itemIndex = itemIndex,
                            itemCount = itemCount,
                            state = SourceSeparationQuickSetupItemState.Running,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                }
                require(result.state == SourceSeparationRuntimeState.Installed) {
                    result.reason ?: "The repaired CPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.ActivatePendingRuntime -> {
                val componentId = requireNotNull(item.componentId)
                val result = runtimeStore.activatePending(componentId)
                require(result.state == SourceSeparationRuntimeState.Installed) {
                    result.reason ?: "The pending CPU runtime could not be activated."
                }
            }

            SourceSeparationQuickSetupAction.InstallGpuRuntime -> {
                val componentId = requireNotNull(item.componentId)
                val result = gpuRuntimeStore.install(componentId) { downloaded, total ->
                    onProgress(
                        SourceSeparationQuickSetupProgress(
                            itemId = item.itemId,
                            itemIndex = itemIndex,
                            itemCount = itemCount,
                            state = SourceSeparationQuickSetupItemState.Running,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                }
                require(result.state == SourceSeparationGpuRuntimeState.Installed) {
                    result.reason ?: "The GPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.RepairGpuRuntime -> {
                val componentId = requireNotNull(item.componentId)
                val result = gpuRuntimeStore.repair(componentId) { downloaded, total ->
                    onProgress(
                        SourceSeparationQuickSetupProgress(
                            itemId = item.itemId,
                            itemIndex = itemIndex,
                            itemCount = itemCount,
                            state = SourceSeparationQuickSetupItemState.Running,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                }
                require(result.state == SourceSeparationGpuRuntimeState.Installed) {
                    result.reason ?: "The repaired GPU runtime is waiting for activation."
                }
            }

            SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime -> {
                val componentId = requireNotNull(item.componentId)
                val result = gpuRuntimeStore.activatePending(componentId)
                require(result.state == SourceSeparationGpuRuntimeState.Installed) {
                    result.reason ?: "The pending GPU runtime could not be activated."
                }
            }

            SourceSeparationQuickSetupAction.ConfigureGpuRuntime -> Unit

            SourceSeparationQuickSetupAction.InstallModel -> {
                val modelId = requireNotNull(item.modelId)
                modelInstaller.install(
                    modelId = modelId,
                    onProgress = { downloadedBytes, totalBytes ->
                        onProgress(
                            SourceSeparationQuickSetupProgress(
                                itemId = item.itemId,
                                itemIndex = itemIndex,
                                itemCount = itemCount,
                                state = SourceSeparationQuickSetupItemState.Running,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    },
                    shouldCancel = shouldCancel,
                )
            }

            SourceSeparationQuickSetupAction.SelectModel -> {
                if (shouldCancel()) throw CancellationException("Selection canceled.")
                val modelId = requireNotNull(item.modelId)
                val expected = presetRepository.officialPreset(modelId)
                val installed = presetRepository.installedModels().singleOrNull {
                    it.sha256.equals(expected.sha256, ignoreCase = true)
                } ?: throw IllegalStateException("The selected model was not installed.")
                presetRepository.activate(
                    sha256 = installed.sha256,
                    platform = platformProvider(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                )
            }

            SourceSeparationQuickSetupAction.Validate -> {
                require(readinessEvaluator().isRunnable) {
                    "The local separation path failed final validation."
                }
            }

            SourceSeparationQuickSetupAction.Configure,
            SourceSeparationQuickSetupAction.RecycleProcess,
            SourceSeparationQuickSetupAction.OpenRuntimeManagement,
            SourceSeparationQuickSetupAction.OpenModelManagement,
            -> throw IllegalStateException("This setup item requires user action.")
        }
    }

    private fun commitGpuPreference(
        plan: SourceSeparationQuickSetupPlan,
        selectedItemIds: Set<String>,
    ) {
        if (plan.proposedGpuEnabled == null) return
        val gpuItem = plan.items.singleOrNull { item ->
            item.action == SourceSeparationQuickSetupAction.InstallGpuRuntime ||
                item.action == SourceSeparationQuickSetupAction.RepairGpuRuntime ||
                item.action == SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime ||
                item.action == SourceSeparationQuickSetupAction.ConfigureGpuRuntime
        }
        val enabled = plan.proposedGpuEnabled && gpuItem?.itemId in selectedItemIds
        preferences.writeSourceSeparationGpuEnabled(enabled)
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
            val itemIndex = plan.items.indexOf(item)
            require(item.dependencyIds.all { dependencyId ->
                plan.items.indexOfFirst { it.itemId == dependencyId } < itemIndex
            }) {
                "The setup plan dependency order is invalid for ${item.itemId}."
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
        markRemainingCanceled(selectedItems, firstUnprocessedIndex, results, onProgress)
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
