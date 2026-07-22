package com.mardous.booming.ui.screen.player

import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationPresetManagementStateTest {
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
            activationPolicy = CatalogActivationPolicy.SelectableExperimentalCpuOnly,
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

    private fun item(
        supportLevel: CatalogSupportLevel,
        activationPolicy: CatalogActivationPolicy,
        canUseForValidation: Boolean = false,
        active: Boolean = false,
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
        ),
        active = active,
        canUseForValidation = canUseForValidation,
        useBlockReason = null,
        transferState = null,
        operationKey = "catalog:model",
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
