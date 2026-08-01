package com.mardous.booming.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.separation.setup.LocalSeparationReadiness
import com.mardous.booming.separation.setup.LocalSeparationReadinessEvaluator
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupExecutor
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupItemResult
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupItemState
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupMode
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlan
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlanItem
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlanner
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupProgress
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupResult
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupStalePlanException
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupTerminalStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SourceSeparationQuickSetupViewModel(
    private val readinessEvaluator: LocalSeparationReadinessEvaluator,
    private val executor: SourceSeparationQuickSetupExecutor,
) : ViewModel() {
    private val cancelRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow(SourceSeparationQuickSetupUiState())
    val state = _state.asStateFlow()

    init {
        analyze()
    }

    fun analyze(mode: SourceSeparationQuickSetupMode? = null) {
        if (_state.value.phase == SourceSeparationQuickSetupPhase.Installing) return
        val requestedMode = mode ?: _state.value.mode
        _state.update {
            it.copy(
                phase = SourceSeparationQuickSetupPhase.Analyzing,
                errorMessage = null,
                result = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val readiness = readinessEvaluator.evaluate()
                val effectiveMode = mode ?: defaultMode(readiness)
                val plan = SourceSeparationQuickSetupPlanner.create(readiness, effectiveMode)
                readiness to plan
            }.onSuccess { (readiness, plan) ->
                val selected = plan.selectedItems.mapTo(mutableSetOf()) { it.itemId }
                _state.update {
                    it.copy(
                        phase = SourceSeparationQuickSetupPhase.Reviewing,
                        mode = plan.mode,
                        readiness = readiness,
                        plan = plan,
                        selectedItemIds = selected,
                        itemResults = emptyMap(),
                        progress = null,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        phase = SourceSeparationQuickSetupPhase.Finished,
                        errorMessage = error.message ?: "Could not analyze local separation resources.",
                    )
                }
            }
        }
    }

    fun setMode(mode: SourceSeparationQuickSetupMode) {
        analyze(mode)
    }

    fun setSelected(itemId: String, selected: Boolean) {
        _state.update { current ->
            val item = current.plan?.items?.singleOrNull { it.itemId == itemId }
                ?: return@update current
            if (item.requirement == com.mardous.booming.separation.setup.SourceSeparationQuickSetupRequirement.Required) {
                return@update current
            }
            val next = current.selectedItemIds.toMutableSet().apply {
                if (selected) add(itemId) else remove(itemId)
            }
            current.copy(selectedItemIds = next)
        }
    }

    fun installSelected() {
        val current = _state.value
        val plan = current.plan ?: return
        if (current.phase == SourceSeparationQuickSetupPhase.Installing) return
        if (plan.items.any { it.itemId in current.selectedItemIds && it.disabledReason != null }) return
        cancelRequested.set(false)
        _state.update {
            it.copy(
                phase = SourceSeparationQuickSetupPhase.Installing,
                itemResults = emptyMap(),
                progress = null,
                errorMessage = null,
                result = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                executor.execute(
                    plan = plan,
                    selectedItemIds = current.selectedItemIds,
                    shouldCancel = cancelRequested::get,
                    onProgress = ::publishProgress,
                )
            }.getOrElse { error ->
                SourceSeparationQuickSetupResult(
                    status = if (error is SourceSeparationQuickSetupStalePlanException) {
                        SourceSeparationQuickSetupTerminalStatus.Blocked
                    } else {
                        SourceSeparationQuickSetupTerminalStatus.Failed
                    },
                    plan = plan,
                    itemResults = emptyList(),
                    readiness = readinessEvaluator.evaluate(),
                    errorMessage = error.message ?: "Quick Setup failed.",
                )
            }
            _state.update {
                it.copy(
                    phase = SourceSeparationQuickSetupPhase.Finished,
                    readiness = result.readiness,
                    itemResults = result.itemResults.associateBy(SourceSeparationQuickSetupItemResult::itemId),
                    progress = null,
                    result = result,
                    errorMessage = result.errorMessage,
                )
            }
        }
    }

    fun cancel() {
        if (_state.value.phase == SourceSeparationQuickSetupPhase.Installing) {
            cancelRequested.set(true)
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun publishProgress(progress: SourceSeparationQuickSetupProgress) {
        _state.update {
            val resultState = when (progress.state) {
                SourceSeparationQuickSetupItemState.Running -> SourceSeparationQuickSetupItemState.Running
                else -> progress.state
            }
            it.copy(
                progress = progress,
                itemResults = it.itemResults + (
                    progress.itemId to SourceSeparationQuickSetupItemResult(
                        itemId = progress.itemId,
                        state = resultState,
                    )
                ),
            )
        }
    }

    private fun defaultMode(readiness: LocalSeparationReadiness): SourceSeparationQuickSetupMode =
        if (readiness.activeModelReference == null) {
            SourceSeparationQuickSetupMode.BootstrapRecommended
        } else {
            SourceSeparationQuickSetupMode.RepairCurrent
        }
}

internal enum class SourceSeparationQuickSetupPhase {
    Analyzing,
    Reviewing,
    Installing,
    Finished,
}

internal data class SourceSeparationQuickSetupUiState(
    val phase: SourceSeparationQuickSetupPhase = SourceSeparationQuickSetupPhase.Analyzing,
    val mode: SourceSeparationQuickSetupMode = SourceSeparationQuickSetupMode.BootstrapRecommended,
    val readiness: LocalSeparationReadiness? = null,
    val plan: SourceSeparationQuickSetupPlan? = null,
    val selectedItemIds: Set<String> = emptySet(),
    val itemResults: Map<String, SourceSeparationQuickSetupItemResult> = emptyMap(),
    val progress: SourceSeparationQuickSetupProgress? = null,
    val result: SourceSeparationQuickSetupResult? = null,
    val errorMessage: String? = null,
) {
    fun itemState(item: SourceSeparationQuickSetupPlanItem): SourceSeparationQuickSetupItemState =
        itemResults[item.itemId]?.state ?: item.state
}
