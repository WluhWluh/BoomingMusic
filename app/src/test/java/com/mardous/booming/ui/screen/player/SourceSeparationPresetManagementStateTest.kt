package com.mardous.booming.ui.screen.player

import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.contract.ContractArtifact
import com.mardous.booming.separation.model.contract.ContractArtifactFormat
import com.mardous.booming.separation.model.contract.ContractDsp
import com.mardous.booming.separation.model.contract.ContractDtype
import com.mardous.booming.separation.model.contract.ContractTensor
import com.mardous.booming.separation.model.contract.ContractTensorLayout
import com.mardous.booming.separation.model.contract.ContractResidualRule
import com.mardous.booming.separation.model.contract.ContractStem
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.ContractWindow
import com.mardous.booming.separation.model.contract.PipelineCompatibility
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelCategory
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.SourceSeparationModelPresentationCatalog
import com.mardous.booming.separation.model.contract.StemContract
import com.mardous.booming.separation.model.contract.TensorContract
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationPresetManagementStateTest {
    @Test
    fun `candidate entries group by reviewed category and preserve unknown entries`() {
        val general = item(
            supportLevel = CatalogSupportLevel.Experimental,
            activationPolicy = CatalogActivationPolicy.SelectableExperimental,
        ).copy(
            modelId = "uvr_mdxnet_kara",
            presentation = SourceSeparationModelPresentationCatalog.require("uvr_mdxnet_kara"),
        )
        val target = item(
            supportLevel = CatalogSupportLevel.DownloadOnly,
            activationPolicy = CatalogActivationPolicy.DownloadOnlyResourceGated,
        ).copy(
            modelId = "kuielab_a_bass",
            presentation = SourceSeparationModelPresentationCatalog.require("kuielab_a_bass"),
        )
        val unknown = item(
            supportLevel = CatalogSupportLevel.Experimental,
            activationPolicy = CatalogActivationPolicy.SelectableExperimental,
        ).copy(modelId = "future-model")

        val groups = groupPresetManagementEntries(listOf(general, target, unknown))

        assertEquals(
            listOf(
                SourceSeparationModelCategory.Karaoke,
                SourceSeparationModelCategory.TargetStem,
                null,
            ),
            groups.map { it.category },
        )
        assertEquals(listOf("uvr_mdxnet_kara"), groups[0].entries.map { it.modelId })
        assertEquals(listOf("kuielab_a_bass"), groups[1].entries.map { it.modelId })
        assertEquals(listOf("future-model"), groups[2].entries.map { it.modelId })
    }

    @Test
    fun `download-only entries never offer a use action`() {
        val item = item(
            supportLevel = CatalogSupportLevel.DownloadOnly,
            activationPolicy = CatalogActivationPolicy.DownloadOnlyResourceGated,
        )

        assertFalse(item.offersUseForValidation)
        assertFalse(item.canUseForValidation)
        assertTrue(item.canDelete)
    }

    @Test
    fun `recommended and experimental entries expose independently gated use actions`() {
        val recommended = item(
            supportLevel = CatalogSupportLevel.Recommended,
            activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
            canUseForValidation = true,
        )
        val experimental = item(
            supportLevel = CatalogSupportLevel.Experimental,
            activationPolicy = CatalogActivationPolicy.SelectableExperimental,
            canUseForValidation = true,
        )

        assertTrue(recommended.offersUseForValidation)
        assertTrue(experimental.offersUseForValidation)
    }

    @Test
    fun `active installed model cannot offer deletion`() {
        val item = item(
            supportLevel = CatalogSupportLevel.Recommended,
            activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
            active = true,
        )

        assertFalse(item.canDelete)
    }

    @Test
    fun `imported active or busy models cannot be deleted`() {
        val active = importedItem(active = true)
        val deleting = importedItem(transferState = SourceSeparationPresetTransferState.Deleting)

        assertFalse(active.canDelete)
        assertFalse(deleting.canDelete)
    }

    @Test
    fun `only inactive non-default custom profile revisions can be deleted`() {
        val active = SourceSeparationCustomProfileRevisionUiState(
            profileId = "active",
            displayName = "Active",
            modelId = "model",
            active = true,
            defaultBinding = false,
        )
        val original = active.copy(
            profileId = "original",
            displayName = "Original",
            active = false,
            defaultBinding = true,
        )
        val orphan = active.copy(
            profileId = "orphan",
            displayName = "Orphan",
            active = false,
            defaultBinding = false,
        )

        assertFalse(active.canDelete)
        assertFalse(original.canDelete)
        assertTrue(orphan.canDelete)
    }

    @Test
    fun `missing restored official model is exposed without an active selection`() {
        val reference = activeReference()

        val target = requireNotNull(
            resolveRestoredModelTarget(
                reference = reference,
                entries = listOf(
                    item(
                        supportLevel = CatalogSupportLevel.Recommended,
                        activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
                        installed = false,
                    ),
                ),
                importedEntries = emptyList(),
                activeReference = null,
            ),
        )

        assertEquals("Model", target.displayName)
        assertEquals("a".repeat(12), target.shortSha256)
        assertFalse(target.exactModelInstalled)
        assertFalse(target.currentModelRetained)
    }

    @Test
    fun `installed restored official model remains a pending inactive target`() {
        val target = requireNotNull(
            resolveRestoredModelTarget(
                reference = activeReference(),
                entries = listOf(
                    item(
                        supportLevel = CatalogSupportLevel.Recommended,
                        activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
                    ),
                ),
                importedEntries = emptyList(),
                activeReference = null,
            ),
        )

        assertTrue(target.exactModelInstalled)
        assertFalse(target.currentModelRetained)
    }

    @Test
    fun `different active model is retained beside restored target`() {
        val target = requireNotNull(
            resolveRestoredModelTarget(
                reference = activeReference(),
                entries = listOf(
                    item(
                        supportLevel = CatalogSupportLevel.Recommended,
                        activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
                        installed = false,
                    ),
                ),
                importedEntries = emptyList(),
                activeReference = activeReference(
                    modelId = "current_model",
                    sha256 = "b".repeat(64),
                ),
            ),
        )

        assertTrue(target.currentModelRetained)
        assertFalse(target.exactModelInstalled)
    }

    @Test
    fun `missing current model is not reported as retained`() {
        val state = SourceSeparationActivePresetState.Reference(
            reference = activeReference(),
            installedModel = null,
        )

        assertEquals(null, usableActiveModelReference(state))
    }

    @Test
    fun `installed current model is eligible to be retained`() {
        val installed = item(
            supportLevel = CatalogSupportLevel.Recommended,
            activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
        ).installed
        val state = SourceSeparationActivePresetState.Reference(
            reference = activeReference(),
            installedModel = installed,
        )

        assertEquals(activeReference(), usableActiveModelReference(state))
    }

    @Test
    fun `restored custom profile names a missing model without marking it installed`() {
        val profile = customProfile()
        val target = requireNotNull(
            resolveRestoredModelTarget(
                reference = SourceSeparationActiveModelReference(
                    modelId = profile.modelId,
                    artifactSha256 = profile.artifact.sha256,
                    contractSchemaVersion = profile.profileSchemaVersion,
                    profileId = profile.profileId,
                ),
                entries = emptyList(),
                importedEntries = emptyList(),
                customProfiles = listOf(profile),
                activeReference = null,
            ),
        )

        assertEquals("Restored Custom Model", target.displayName)
        assertFalse(target.exactModelInstalled)
    }

    @Test
    fun `custom profile export round trips without model or cache data`() {
        val profile = customProfile()
        val export = profile.toPortableExport()

        assertTrue(export.fileName.startsWith("${profile.artifact.fileName}."))
        assertTrue(export.fileName.endsWith(".profile.json"))
        assertEquals(profile, SourceSeparationModelMetadata.decodeCustomProfile(export.contents))
        assertFalse(export.contents.contains("content://"))
        assertFalse(export.contents.contains("playback-settings.json"))
    }

    private fun item(
        supportLevel: CatalogSupportLevel,
        activationPolicy: CatalogActivationPolicy,
        canUseForValidation: Boolean = false,
        active: Boolean = false,
        installed: Boolean = true,
    ) = SourceSeparationPresetManagementItem(
        modelId = "model",
        displayName = "Model",
        supportLevel = supportLevel,
        activationPolicy = activationPolicy,
        releaseMaturity = CatalogReleaseMaturity.Candidate,
        isDefault = false,
        byteSize = 1L,
        sha256 = "a".repeat(64),
        releaseTag = "v1",
        installed = SourceSeparationInstalledPreset(
            modelId = "model",
            displayName = "Model",
            file = File("model.tflite"),
            byteSize = 1L,
            sha256 = "a".repeat(64),
            origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
            bindingKind = SourceSeparationPresetBindingKind.Official,
            contractId = null,
            sidecarContract = null,
            customProfile = null,
            installedAtEpochMs = 1L,
        ).takeIf { installed },
        active = active,
        canUseForValidation = canUseForValidation,
        useBlockReason = null,
        transferState = null,
        operationKey = "catalog:model",
    )

    private fun activeReference(
        modelId: String = "model",
        sha256: String = "a".repeat(64),
    ) = SourceSeparationActiveModelReference(
        modelId = modelId,
        artifactSha256 = sha256,
        contractSchemaVersion = 2,
    )

    private fun customProfile() = SourceSeparationCustomModelProfile(
        profileSchemaVersion = 2,
        profileId = "restored-profile",
        modelId = "restored_custom",
        displayName = "Restored Custom Model",
        artifact = ContractArtifact(
            fileName = "restored-custom.tflite",
            byteSize = 1L,
            sha256 = "c".repeat(64),
            format = ContractArtifactFormat.TfliteFlatbuffer,
        ),
        tensorContract = TensorContract(
            input = ContractTensor(
                name = "input",
                dtype = ContractDtype.Float32,
                layout = ContractTensorLayout.Nhwc,
                shape = listOf(1, 4, 2, 2),
            ),
            output = ContractTensor(
                name = "output",
                dtype = ContractDtype.Float32,
                layout = ContractTensorLayout.Nhwc,
                shape = listOf(1, 4, 2, 2),
            ),
            batchSize = 1,
            complexChannelCount = 4,
        ),
        dsp = ContractDsp(
            sampleRate = 44_100,
            channelCount = 2,
            nFft = 6_144,
            hopLength = 1_024,
            dimF = 2,
            dimTPower = 1,
            modelTimeFrames = 2,
            window = ContractWindow.PeriodicHann,
            modelOutputScale = 1.0,
        ),
        stemContract = StemContract(
            modelOutput = ContractStem(ContractStemSemantic.Vocals, "Vocals"),
            residual = ContractStem(ContractStemSemantic.Instrumental, "Instrumental"),
            residualRule = ContractResidualRule.MixtureMinusScaledModelOutput,
        ),
        pipelineCompatibility = PipelineCompatibility(
            pipelineId = "mdx-v1",
            minimumVersion = 1,
            maximumVersion = 1,
        ),
        qualityUnverified = true,
    )

    private fun importedItem(
        active: Boolean = false,
        transferState: SourceSeparationPresetTransferState? = null,
    ) = SourceSeparationImportedModelManagementItem(
        modelId = "imported_model",
        displayName = "Imported Model",
        sha256 = "b".repeat(64),
        byteSize = 1L,
        bindingKind = SourceSeparationPresetBindingKind.CustomProfile,
        qualityUnverified = true,
        installed = SourceSeparationInstalledPreset(
            modelId = "imported_model",
            displayName = "Imported Model",
            file = File("imported-model.tflite"),
            byteSize = 1L,
            sha256 = "b".repeat(64),
            origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
            bindingKind = SourceSeparationPresetBindingKind.CustomProfile,
            contractId = null,
            sidecarContract = null,
            customProfile = null,
            installedAtEpochMs = 1L,
        ),
        active = active,
        canUseForValidation = true,
        useBlockReason = null,
        transferState = transferState,
        operationKey = "imported:${"b".repeat(64)}",
    )
}
