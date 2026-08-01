package com.mardous.booming.separation.setup

import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationQuickSetupPlannerTest {
    @Test
    fun `recommended GPU is optional and never becomes a model dependency`() {
        val plan = SourceSeparationQuickSetupPlanner.create(
            readiness(
                activeModel = null,
                activeReference = null,
                gpuRuntime = gpuRuntime(SourceSeparationGpuRuntimeState.Missing),
                repairCandidates = listOf(
                    LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.InstallCpuRuntime,
                        reason = "Install CPU runtime.",
                        required = true,
                        componentId = "cpu-arm64",
                    ),
                    LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.InstallGpuRuntime,
                        reason = "Install GPU runtime.",
                        required = false,
                        componentId = "gpu-arm64",
                    ),
                    LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.InstallRecommendedModel,
                        reason = "Install model.",
                        required = true,
                        modelId = "recommended-model",
                    ),
                ),
            ),
            SourceSeparationQuickSetupMode.BootstrapRecommended,
        )

        assertEquals(
            listOf(
                SourceSeparationQuickSetupAction.InstallRuntime,
                SourceSeparationQuickSetupAction.InstallGpuRuntime,
                SourceSeparationQuickSetupAction.InstallModel,
                SourceSeparationQuickSetupAction.SelectModel,
            ),
            plan.items.map(SourceSeparationQuickSetupPlanItem::action),
        )
        assertTrue(plan.items[1].selected)
        assertEquals(20L, plan.items[0].expectedInstalledBytes)
        assertEquals(20L, plan.items[1].expectedInstalledBytes)
        assertEquals(listOf("runtime:cpu-arm64"), plan.items[1].dependencyIds)
        assertEquals(listOf("runtime:cpu-arm64"), plan.items[2].dependencyIds)
        assertEquals(
            listOf("runtime:cpu-arm64", "model:recommended-model"),
            plan.items[3].dependencyIds,
        )
        assertTrue(plan.proposedGpuEnabled == true)
    }

    @Test
    fun `installed recommended GPU is offered as a preference reset`() {
        val currentReference = SourceSeparationActiveModelReference(
            modelId = "current-model",
            artifactSha256 = "b".repeat(64),
            contractSchemaVersion = 2,
        )
        val plan = SourceSeparationQuickSetupPlanner.create(
            readiness(
                activeModel = model(
                    modelId = currentReference.modelId,
                    sha256 = currentReference.artifactSha256,
                    installed = true,
                    active = true,
                ),
                activeReference = currentReference,
                gpuRuntime = gpuRuntime(SourceSeparationGpuRuntimeState.Installed),
                gpuEnabled = false,
                repairCandidates = listOf(
                    LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.ConfigureGpuRuntime,
                        reason = "Enable GPU.",
                        required = false,
                        componentId = "gpu-arm64",
                    ),
                ),
            ),
            SourceSeparationQuickSetupMode.RepairCurrent,
        )

        assertEquals(
            listOf(SourceSeparationQuickSetupAction.ConfigureGpuRuntime),
            plan.items.map(SourceSeparationQuickSetupPlanItem::action),
        )
        assertTrue(plan.proposedGpuEnabled == true)
    }

    @Test
    fun `bootstrap orders CPU runtime model installation and final selection`() {
        val readiness = readiness(
            activeModel = null,
            activeReference = null,
            runtimeState = SourceSeparationRuntimeState.Missing,
            repairCandidates = listOf(
                LocalSeparationRepairCandidate(
                    kind = LocalSeparationRepairCandidateKind.InstallCpuRuntime,
                    reason = "Install CPU runtime.",
                    required = true,
                    componentId = "cpu-arm64",
                ),
                LocalSeparationRepairCandidate(
                    kind = LocalSeparationRepairCandidateKind.InstallRecommendedModel,
                    reason = "Install recommended model.",
                    required = true,
                    modelId = "recommended-model",
                ),
            ),
        )

        val plan = SourceSeparationQuickSetupPlanner.create(
            readiness = readiness,
            mode = SourceSeparationQuickSetupMode.BootstrapRecommended,
        )

        assertEquals(
            listOf(
                SourceSeparationQuickSetupAction.InstallRuntime,
                SourceSeparationQuickSetupAction.InstallModel,
                SourceSeparationQuickSetupAction.SelectModel,
            ),
            plan.items.map(SourceSeparationQuickSetupPlanItem::action),
        )
        assertEquals(
            listOf("runtime:cpu-arm64"),
            plan.items[1].dependencyIds,
        )
        assertEquals(
            listOf("runtime:cpu-arm64", "model:recommended-model"),
            plan.items[2].dependencyIds,
        )
        assertEquals("recommended-model", plan.proposedActiveModel?.modelId)
    }

    @Test
    fun `repair current keeps a valid selected model`() {
        val reference = SourceSeparationActiveModelReference(
            modelId = "current-model",
            artifactSha256 = "b".repeat(64),
            contractSchemaVersion = 2,
        )
        val readiness = readiness(
            activeModel = model(
                modelId = "current-model",
                sha256 = reference.artifactSha256,
                installed = true,
                active = true,
            ),
            activeReference = reference,
            runtimeState = SourceSeparationRuntimeState.Invalid,
            repairCandidates = listOf(
                LocalSeparationRepairCandidate(
                    kind = LocalSeparationRepairCandidateKind.RepairCpuRuntime,
                    reason = "Repair CPU runtime.",
                    required = true,
                    componentId = "cpu-arm64",
                ),
            ),
        )

        val plan = SourceSeparationQuickSetupPlanner.create(
            readiness = readiness,
            mode = SourceSeparationQuickSetupMode.RepairCurrent,
        )

        assertEquals(1, plan.items.size)
        assertEquals(SourceSeparationQuickSetupAction.RepairRuntime, plan.items.single().action)
        assertEquals(reference, plan.proposedActiveModel)
    }

    @Test
    fun `restore recommended selects an already installed recommendation`() {
        val current = SourceSeparationActiveModelReference(
            modelId = "old-model",
            artifactSha256 = "a".repeat(64),
            contractSchemaVersion = 2,
        )
        val recommended = model(
            modelId = "recommended-model",
            sha256 = "b".repeat(64),
            installed = true,
            active = false,
        )
        val plan = SourceSeparationQuickSetupPlanner.create(
            readiness = readiness(
                activeModel = model(
                    modelId = "old-model",
                    sha256 = current.artifactSha256,
                    installed = true,
                    active = true,
                ),
                activeReference = current,
                recommendedModel = recommended,
                runtimeState = SourceSeparationRuntimeState.Installed,
            ),
            mode = SourceSeparationQuickSetupMode.RestoreRecommended,
        )

        assertEquals(listOf(SourceSeparationQuickSetupAction.SelectModel), plan.items.map {
            it.action
        })
        assertEquals("recommended-model", plan.proposedActiveModel?.modelId)
    }

    @Test
    fun `unsupported readiness creates an unselected management handoff`() {
        val readiness = readiness(
            state = LocalSeparationReadinessState.Unsupported,
            runtime = null,
            runtimeState = null,
            blockers = listOf(
                LocalSeparationIssue(
                    LocalSeparationBlockerCode.UnsupportedProcessAbi,
                    "Unsupported ABI.",
                ),
            ),
        )

        val plan = SourceSeparationQuickSetupPlanner.create(
            readiness = readiness,
            mode = SourceSeparationQuickSetupMode.BootstrapRecommended,
        )

        assertEquals(SourceSeparationQuickSetupAction.OpenRuntimeManagement, plan.items.single().action)
        assertFalse(plan.items.single().selected)
        assertTrue(plan.items.single().disabledReason != null)
    }

    @Test
    fun `same readiness produces the same plan identity`() {
        val readiness = readiness()
        val first = SourceSeparationQuickSetupPlanner.create(
            readiness,
            SourceSeparationQuickSetupMode.BootstrapRecommended,
        )
        val second = SourceSeparationQuickSetupPlanner.create(
            readiness,
            SourceSeparationQuickSetupMode.BootstrapRecommended,
        )

        assertEquals(first.planId, second.planId)
    }

    private fun readiness(
        state: LocalSeparationReadinessState = LocalSeparationReadinessState.NeedsSetup,
        runtime: LocalSeparationRuntimeSnapshot? = runtime(),
        runtimeState: SourceSeparationRuntimeState? = SourceSeparationRuntimeState.Missing,
        activeModel: LocalSeparationModelSnapshot? = null,
        activeReference: SourceSeparationActiveModelReference? = null,
        recommendedModel: LocalSeparationModelSnapshot = model(
            modelId = "recommended-model",
            sha256 = "c".repeat(64),
            installed = false,
            active = false,
        ),
        blockers: List<LocalSeparationIssue> = emptyList(),
        repairCandidates: List<LocalSeparationRepairCandidate> = emptyList(),
        gpuRuntime: LocalSeparationGpuRuntimeSnapshot? = null,
        gpuEnabled: Boolean = true,
    ) = LocalSeparationReadiness(
        schemaVersion = LocalSeparationReadinessContract.SCHEMA_VERSION,
        fingerprint = "f".repeat(64),
        state = state,
        platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
        catalogRevision = "catalog:2",
        cpuRuntime = runtime?.copy(state = runtimeState ?: runtime.state),
        activeModel = activeModel,
        recommendedModel = recommendedModel,
        activeModelReference = activeReference,
        runnablePaths = emptyList(),
        blockers = blockers,
        degradations = emptyList(),
        repairCandidates = repairCandidates,
        gpuRuntime = gpuRuntime,
        gpuEnabled = gpuEnabled,
    )

    private fun runtime(
        state: SourceSeparationRuntimeState = SourceSeparationRuntimeState.Missing,
    ) = LocalSeparationRuntimeSnapshot(
        componentId = "cpu-arm64",
        abi = "arm64-v8a",
        androidMinApi = 26,
        runtimeArtifactVersion = "runtime-1",
        producerReleaseVersion = "release-1",
        downloadBytes = 10L,
        installedBytes = 20L,
        state = state,
        reason = null,
        delivery = SourceSeparationDeliveryReference(
            providerId = "github",
            artifactId = "cpu-arm64",
            locator = "https://github.com/test/repo/releases/download/v1/cpu.zip",
            expectedSha256 = "d".repeat(64),
            expectedByteSize = 10L,
        ),
        isRunnable = state == SourceSeparationRuntimeState.Installed,
    )

    private fun model(
        modelId: String,
        sha256: String,
        installed: Boolean,
        active: Boolean,
    ) = LocalSeparationModelSnapshot(
        modelId = modelId,
        displayName = modelId,
        artifactSha256 = sha256,
        contractSchemaVersion = 2,
        byteSize = 30L,
        releaseTag = "model-release",
        installed = installed,
        active = active,
        official = true,
    )

    private fun gpuRuntime(state: SourceSeparationGpuRuntimeState) =
        LocalSeparationGpuRuntimeSnapshot(
            componentId = "gpu-arm64",
            abi = "arm64-v8a",
            androidMinApi = 26,
            runtimeArtifactVersion = "gpu-runtime-1",
            producerReleaseVersion = "gpu-release-1",
            downloadBytes = 10L,
            installedBytes = 20L,
            state = state,
            reason = null,
            delivery = SourceSeparationDeliveryReference(
                providerId = "github",
                artifactId = "gpu-arm64",
                locator = "https://github.com/test/repo/releases/download/v1/gpu.zip",
                expectedSha256 = "e".repeat(64),
                expectedByteSize = 10L,
            ),
            isRunnable = state == SourceSeparationGpuRuntimeState.Installed,
            maturity = "recommended",
            profileId = "gpu-opencl-bounded-fp32-v1",
        )
}
