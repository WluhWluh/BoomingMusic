package com.mardous.booming.debug

import com.mardous.booming.separation.SourceSeparationExecutionSelectionResolver
import com.mardous.booming.separation.setup.LocalSeparationReadiness
import com.mardous.booming.separation.setup.LocalSeparationReadinessEvaluator
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupExecutor
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupMode
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlan
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlanner
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupResult
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get

internal class SourceSeparationDebugQuickSetupController(
    private val operations: SourceSeparationDebugOperationRegistry,
) {
    private val readinessEvaluator: LocalSeparationReadinessEvaluator
        get() = get(LocalSeparationReadinessEvaluator::class.java)
    private val executor: SourceSeparationQuickSetupExecutor
        get() = get(SourceSeparationQuickSetupExecutor::class.java)
    private val selectionResolver: SourceSeparationExecutionSelectionResolver
        get() = get(SourceSeparationExecutionSelectionResolver::class.java)

    fun plan(mode: SourceSeparationQuickSetupMode, verify: Boolean): JSONObject {
        val readiness = readinessEvaluator.evaluate(verifyPayloadHashes = verify)
        val plan = SourceSeparationQuickSetupPlanner.create(readiness, mode)
        return snapshot(readiness, plan)
    }

    fun submit(mode: SourceSeparationQuickSetupMode, verify: Boolean) =
        operations.submit("setup.execute", mode.name) {
            stage("analyze")
            val before = selectionResolver.current()
            val readiness = readinessEvaluator.evaluate(verifyPayloadHashes = verify)
            val plan = SourceSeparationQuickSetupPlanner.create(readiness, mode)
            stage("execute")
            val result = executor.execute(
                plan = plan,
                shouldCancel = {
                    runCatching { ensureActive() }.isFailure
                },
                onProgress = { update ->
                    progress(
                        downloadedBytes = update.downloadedBytes,
                        totalBytes = update.totalBytes,
                        stage = update.itemId,
                        message = update.state.name,
                    )
                },
            )
            ensureActive()
            JSONObject()
                .put("before", before.toJson())
                .put("plan", plan.toJson())
                .put("result", result.toJson())
                .put("after", selectionResolver.current().toJson())
        }

    private fun snapshot(
        readiness: LocalSeparationReadiness,
        plan: SourceSeparationQuickSetupPlan,
    ) = JSONObject()
        .put("selection", selectionResolver.current().toJson())
        .put("readiness", readiness.toJson())
        .put("plan", plan.toJson())
}

private fun com.mardous.booming.separation.SourceSeparationExecutionSelectionSnapshot.toJson() =
    JSONObject()
        .put("family", family?.name)
        .put("modelId", modelId)
        .put("generation", generation)
        .put("resolved", isResolved)
        .put("artifactSha256", identity?.artifactSha256)
        .put("contractId", identity?.contractId)

private fun LocalSeparationReadiness.toJson() = JSONObject()
    .put("schemaVersion", schemaVersion)
    .put("fingerprint", fingerprint)
    .put("state", state.name)
    .put("runnable", isRunnable)
    .put("activeFamily", activeSelection?.family?.name)
    .put("activeModelId", activeSelection?.modelId)
    .put("gpuEnabled", gpuEnabled)
    .put("blockers", JSONArray(blockers.map { issue -> issue.code.name }))
    .put("degradations", JSONArray(degradations.map { issue -> issue.code.name }))
    .put(
        "runnablePaths",
        JSONArray().also { array ->
            runnablePaths.forEach { path ->
                array.put(
                    JSONObject()
                        .put("family", path.family.name)
                        .put("backend", path.backend)
                        .put("modelId", path.modelId)
                        .put("profileId", path.profileId),
                )
            }
        },
    )

private fun SourceSeparationQuickSetupPlan.toJson() = JSONObject()
    .put("schemaVersion", schemaVersion)
    .put("planId", planId)
    .put("mode", mode.name)
    .put("proposedFamily", proposedSelection?.family?.name)
    .put("proposedModelId", proposedSelection?.modelId)
    .put("proposedGpuEnabled", proposedGpuEnabled)
    .put(
        "items",
        JSONArray().also { array ->
            items.forEach { item ->
                array.put(
                    JSONObject()
                        .put("itemId", item.itemId)
                        .put("action", item.action.name)
                        .put("requirement", item.requirement.name)
                        .put("selected", item.selected)
                        .put("modelFamily", item.modelFamily?.name)
                        .put("modelId", item.modelId)
                        .put("downloadBytes", item.expectedDownloadBytes),
                )
            }
        },
    )

private fun SourceSeparationQuickSetupResult.toJson() = JSONObject()
    .put("status", status.name)
    .put("errorMessage", errorMessage)
    .put("readinessState", readiness.state.name)
    .put("runnable", readiness.isRunnable)
    .put("activeFamily", readiness.activeSelection?.family?.name)
    .put("activeModelId", readiness.activeSelection?.modelId)
    .put(
        "items",
        JSONArray().also { array ->
            itemResults.forEach { item ->
                array.put(
                    JSONObject()
                        .put("itemId", item.itemId)
                        .put("state", item.state.name)
                        .put("errorMessage", item.errorMessage),
                )
            }
        },
    )

internal fun parseQuickSetupMode(value: String?): SourceSeparationQuickSetupMode = when (
    value?.trim()?.lowercase()
) {
    null, "", "restore", "restore_recommended" ->
        SourceSeparationQuickSetupMode.RestoreRecommended
    "repair", "repair_current" -> SourceSeparationQuickSetupMode.RepairCurrent
    "bootstrap", "bootstrap_recommended" -> SourceSeparationQuickSetupMode.BootstrapRecommended
    else -> throw IllegalArgumentException(
        "mode must be restore_recommended, repair_current, or bootstrap_recommended.",
    )
}
