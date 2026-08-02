package com.mardous.booming.separation.setup

import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationQuickSetupExecutorTest {
    @Test
    fun `required work is dependency scheduled ahead of optional accelerators`() {
        val oldModel = reference('a')
        val targetModel = reference('b')
        var readiness = readiness("input", runnable = false)
        val operations = FakeOperations(selection(oldModel, gpuEnabled = false)).apply {
            afterCommit = { readiness = readiness("committed", runnable = true) }
        }
        val result = executor(operations) { readiness }.execute(plan(targetModel))

        assertEquals(listOf("cpu", "model", "gpu"), operations.executedItemIds)
        assertEquals(SourceSeparationQuickSetupTerminalStatus.Completed, result.status)
        assertEquals(targetModel, operations.selection.activeModel)
        assertTrue(operations.selection.gpuEnabled)
        assertEquals(1, operations.commitCount)
    }

    @Test
    fun `optional GPU failure still completes a missing required path`() {
        val targetModel = reference('b')
        var readiness = readiness("input", runnable = false)
        val operations = FakeOperations(selection(null, gpuEnabled = true)).apply {
            failures["gpu"] = IllegalStateException("GPU download failed")
            afterCommit = { readiness = readiness("cpu-ready", runnable = true) }
        }
        val result = executor(operations) { readiness }.execute(plan(targetModel))

        assertEquals(listOf("cpu", "model", "gpu"), operations.executedItemIds)
        assertEquals(SourceSeparationQuickSetupTerminalStatus.PartiallyCompleted, result.status)
        assertEquals(targetModel, operations.selection.activeModel)
        assertFalse(operations.selection.gpuEnabled)
        assertEquals(SourceSeparationQuickSetupItemState.Failed,
            result.itemResults.single { it.itemId == "gpu" }.state)
    }

    @Test
    fun `optional failure preserves an existing runnable selection`() {
        val oldModel = reference('a')
        val operations = FakeOperations(selection(oldModel, gpuEnabled = false)).apply {
            failures["gpu"] = IllegalStateException("GPU unavailable")
        }
        val result = executor(operations) {
            readiness("input", runnable = true, activeModel = oldModel)
        }.execute(plan(reference('b')))

        assertEquals(SourceSeparationQuickSetupTerminalStatus.PartiallyCompleted, result.status)
        assertEquals(oldModel, operations.selection.activeModel)
        assertFalse(operations.selection.gpuEnabled)
        assertEquals(0, operations.commitCount)
    }

    @Test
    fun `required failure blocks without changing selection`() {
        val oldSelection = selection(reference('a'), gpuEnabled = false)
        val operations = FakeOperations(oldSelection).apply {
            failures["model"] = IllegalStateException("bad model hash")
        }
        val result = executor(operations) {
            readiness("input", runnable = true, activeModel = oldSelection.activeModel)
        }.execute(plan(reference('b')))

        assertEquals(SourceSeparationQuickSetupTerminalStatus.Blocked, result.status)
        assertEquals(listOf("cpu", "model"), operations.executedItemIds)
        assertEquals(oldSelection, operations.selection)
        assertEquals(0, operations.commitCount)
        assertEquals(SourceSeparationQuickSetupItemState.Skipped,
            result.itemResults.single { it.itemId == "gpu" }.state)
    }

    @Test
    fun `cancellation at an item boundary leaves installed work but not selection changes`() {
        val oldSelection = selection(reference('a'), gpuEnabled = false)
        val operations = FakeOperations(oldSelection)
        var canceled = false
        operations.afterExecute = { item -> if (item.itemId == "cpu") canceled = true }
        val result = executor(operations) {
            readiness("input", runnable = true, activeModel = oldSelection.activeModel)
        }.execute(
            plan = plan(reference('b')),
            shouldCancel = { canceled },
        )

        assertEquals(SourceSeparationQuickSetupTerminalStatus.Canceled, result.status)
        assertEquals(listOf("cpu"), operations.executedItemIds)
        assertEquals(oldSelection, operations.selection)
        assertEquals(0, operations.commitCount)
    }

    @Test
    fun `failed final readiness rolls back model and backend preferences`() {
        val oldSelection = selection(reference('a'), gpuEnabled = false)
        val operations = FakeOperations(oldSelection)
        val result = executor(operations) {
            readiness("input", runnable = false)
        }.execute(plan(reference('b')))

        assertEquals(SourceSeparationQuickSetupTerminalStatus.Blocked, result.status)
        assertEquals(oldSelection, operations.selection)
        assertEquals(1, operations.commitCount)
        assertEquals(1, operations.restoreCount)
    }

    @Test
    fun `concurrent selection change invalidates the final commit`() {
        val oldSelection = selection(reference('a'), gpuEnabled = false)
        val externalSelection = selection(reference('c'), gpuEnabled = true)
        val operations = FakeOperations(oldSelection).apply {
            afterExecute = { item ->
                if (item.itemId == "gpu") selection = externalSelection
            }
        }
        val result = executor(operations) {
            readiness("input", runnable = true, activeModel = oldSelection.activeModel)
        }.execute(plan(reference('b')))

        assertEquals(SourceSeparationQuickSetupTerminalStatus.Failed, result.status)
        assertEquals(externalSelection, operations.selection)
        assertEquals(0, operations.commitCount)
    }

    @Test
    fun `stale plan is rejected before executing delivery operations`() {
        val operations = FakeOperations(selection(reference('a'), gpuEnabled = false))
        val stalePlan = plan(reference('b')).copy(inputReadinessFingerprint = "old")

        assertThrows(SourceSeparationQuickSetupStalePlanException::class.java) {
            executor(operations) { readiness("new", runnable = true) }.execute(stalePlan)
        }
        assertTrue(operations.executedItemIds.isEmpty())
        assertEquals(0, operations.commitCount)
    }

    @Test
    fun `no-work plan completes without changing an already ready selection`() {
        val oldSelection = selection(reference('a'), gpuEnabled = false)
        val operations = FakeOperations(oldSelection)
        val plan = plan(reference('a')).copy(
            items = emptyList(),
            proposedActiveModel = null,
            proposedGpuEnabled = null,
        )
        val result = executor(operations) {
            readiness("input", runnable = true, activeModel = oldSelection.activeModel)
        }.execute(plan)

        assertEquals(SourceSeparationQuickSetupTerminalStatus.Completed, result.status)
        assertEquals(oldSelection, operations.selection)
    }

    private fun executor(
        operations: FakeOperations,
        readiness: () -> LocalSeparationReadiness,
    ) = SourceSeparationQuickSetupExecutor(operations, readiness)

    private fun plan(targetModel: SourceSeparationActiveModelReference) =
        SourceSeparationQuickSetupPlan(
            schemaVersion = LocalSeparationReadinessContract.PLAN_SCHEMA_VERSION,
            planId = "plan",
            mode = SourceSeparationQuickSetupMode.BootstrapRecommended,
            inputReadinessFingerprint = "input",
            catalogRevision = "catalog",
            items = listOf(
                item(
                    id = "cpu",
                    action = SourceSeparationQuickSetupAction.InstallRuntime,
                    requirement = SourceSeparationQuickSetupRequirement.Required,
                ),
                item(
                    id = "gpu",
                    action = SourceSeparationQuickSetupAction.InstallGpuRuntime,
                    requirement = SourceSeparationQuickSetupRequirement.Recommended,
                    dependencies = listOf("cpu"),
                ),
                item(
                    id = "model",
                    action = SourceSeparationQuickSetupAction.InstallAndSelectModel,
                    requirement = SourceSeparationQuickSetupRequirement.Required,
                    dependencies = listOf("cpu"),
                ),
            ),
            proposedActiveModel = targetModel,
            proposedGpuEnabled = true,
        )

    private fun item(
        id: String,
        action: SourceSeparationQuickSetupAction,
        requirement: SourceSeparationQuickSetupRequirement,
        dependencies: List<String> = emptyList(),
    ) = SourceSeparationQuickSetupPlanItem(
        itemId = id,
        action = action,
        requirement = requirement,
        selected = true,
        title = id,
        reason = "test",
        dependencyIds = dependencies,
        componentId = id.takeIf { action != SourceSeparationQuickSetupAction.InstallAndSelectModel },
        modelId = "uvr_mdxnet_3_9662".takeIf {
            action == SourceSeparationQuickSetupAction.InstallAndSelectModel
        },
    )

    private fun readiness(
        fingerprint: String,
        runnable: Boolean,
        activeModel: SourceSeparationActiveModelReference? = null,
    ) = LocalSeparationReadiness(
        schemaVersion = LocalSeparationReadinessContract.SCHEMA_VERSION,
        fingerprint = fingerprint,
        state = if (runnable) LocalSeparationReadinessState.Ready else
            LocalSeparationReadinessState.NeedsSetup,
        platform = null,
        catalogRevision = "catalog",
        cpuRuntime = null,
        activeModel = null,
        recommendedModel = null,
        activeModelReference = activeModel,
        runnablePaths = if (runnable) {
            listOf(LocalSeparationRunnablePath("cpu", "model", "runtime", "profile"))
        } else {
            emptyList()
        },
        blockers = emptyList(),
        degradations = emptyList(),
        repairCandidates = emptyList(),
    )

    private fun reference(character: Char) = SourceSeparationActiveModelReference(
        modelId = "model_$character",
        artifactSha256 = character.toString().repeat(64),
        contractSchemaVersion = 2,
    )

    private fun selection(
        activeModel: SourceSeparationActiveModelReference?,
        gpuEnabled: Boolean,
    ) = SourceSeparationQuickSetupSelectionSnapshot(
        activeModel = activeModel,
        pendingModel = null,
        gpuEnabled = gpuEnabled,
    )

    private class FakeOperations(
        var selection: SourceSeparationQuickSetupSelectionSnapshot,
    ) : SourceSeparationQuickSetupOperations {
        val executedItemIds = mutableListOf<String>()
        val failures = mutableMapOf<String, Throwable>()
        var commitCount = 0
        var restoreCount = 0
        var afterExecute: (SourceSeparationQuickSetupPlanItem) -> Unit = {}
        var afterCommit: () -> Unit = {}

        override fun executeItem(
            item: SourceSeparationQuickSetupPlanItem,
            shouldCancel: () -> Boolean,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ) {
            executedItemIds += item.itemId
            onProgress(1L, 1L)
            failures[item.itemId]?.let { throw it }
            afterExecute(item)
        }

        override fun selectionSnapshot(): SourceSeparationQuickSetupSelectionSnapshot = selection

        override fun commitSelection(
            activeModel: SourceSeparationActiveModelReference?,
            gpuEnabled: Boolean?,
        ) {
            commitCount++
            selection = selection.copy(
                activeModel = activeModel ?: selection.activeModel,
                gpuEnabled = gpuEnabled ?: selection.gpuEnabled,
            )
            afterCommit()
        }

        override fun restoreSelection(snapshot: SourceSeparationQuickSetupSelectionSnapshot) {
            restoreCount++
            selection = snapshot
        }
    }
}
