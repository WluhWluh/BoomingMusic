package com.mardous.booming.ui.screen.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.R
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheFormat
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SourceSeparationManagementScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun partialCacheRendersExactIdentityAndRoutesDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheKey = "a".repeat(64)
        val deletedCacheKey = AtomicReference<String?>()
        val item = SourceSeparationModelAwareCacheEntry(
            cacheKey = cacheKey,
            songId = 42L,
            title = "Recovery song",
            artist = "Recovery artist",
            album = "Recovery album",
            modelId = "uvr_mdxnet_3_9662",
            displayName = "UVR MDXNET 3 9662",
            artifactSha256 = "b".repeat(64),
            contractId = "uvr_mdxnet_3_9662@2",
            profileRevisionId = "uvr_mdxnet_3_9662@2",
            renderProfileId = "mdx-fp32-render-v1",
            state = SourceSeparationModelAwareCacheEntryState.Partial,
            modelAvailability = SourceSeparationCacheModelAvailability.InstalledExact,
            readySegments = 3,
            totalSegments = 48,
            format = SourceSeparationModelAwareCacheFormat.Wav,
            sizeBytes = 1_024L,
            updatedAtEpochMs = 1L,
            lastAccessedAtEpochMs = 2L,
        )

        compose.setContent {
            MaterialTheme {
                SourceSeparationModelAwareCacheManagementPage(
                    state = SourceSeparationModelAwareCacheManagementUiState(
                        items = listOf(item),
                    ),
                    autoCleanupEnabled = true,
                    partialLimit = 3,
                    completedLimit = 2,
                    onBack = {},
                    onRefresh = {},
                    onDeleteAll = {},
                    onDelete = deletedCacheKey::set,
                    onPlay = {},
                    onDismissFailure = {},
                    onAutoCleanupChange = {},
                    onPartialLimitChange = {},
                    onCompletedLimitChange = {},
                )
            }
        }

        compose.onNodeWithTag("source-separation-cache-entry:$cacheKey")
            .assertIsDisplayed()
        compose.onNodeWithText("Recovery song").assertIsDisplayed()
        compose.onNodeWithText("UVR MDXNET 3 9662").assertIsDisplayed()
        compose.onNodeWithTag("source-separation-cache-toggle:$cacheKey").performClick()
        compose.onNodeWithText(
            context.getString(R.string.source_separation_cache_state_partial, 3, 48),
        ).assertIsDisplayed()
        compose.onNodeWithText("uvr_mdxnet_3_9662").assertIsDisplayed()
        compose.onNodeWithTag("source-separation-cache-delete:$cacheKey").performClick()
        compose.runOnIdle { assertEquals(cacheKey, deletedCacheKey.get()) }
    }

    @Test
    fun installedPresetRoutesExplicitUseAction() {
        val modelId = "uvr_mdxnet_kara"
        val selectedModelId = AtomicReference<String?>()
        val sha256 = "c".repeat(64)
        val installed = SourceSeparationInstalledPreset(
            modelId = modelId,
            displayName = "UVR MDXNET KARA",
            file = File("/test/$modelId.tflite"),
            byteSize = 1_024L,
            sha256 = sha256,
            origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
            bindingKind = SourceSeparationPresetBindingKind.Official,
            contractId = "$modelId@2",
            sidecarContract = null,
            customProfile = null,
            installedAtEpochMs = 1L,
        )
        val item = SourceSeparationPresetManagementItem(
            modelId = modelId,
            displayName = installed.displayName,
            supportLevel = CatalogSupportLevel.Experimental,
            activationPolicy = CatalogActivationPolicy.SelectableExperimentalCpuOnly,
            releaseMaturity = CatalogReleaseMaturity.Candidate,
            isDefault = false,
            byteSize = installed.byteSize,
            sha256 = sha256,
            releaseTag = "v0.1.0-candidates.1",
            installed = installed,
            active = false,
            canUseForValidation = true,
            useBlockReason = null,
            transferState = null,
            operationKey = "catalog:$modelId",
        )

        compose.setContent {
            MaterialTheme {
                SourceSeparationPresetManagementSheet(
                    state = SourceSeparationPresetManagementUiState(entries = listOf(item)),
                    onDownload = {},
                    onCancelDownload = {},
                    onUse = selectedModelId::set,
                    onDelete = {},
                    onConfirmExperimental = {},
                    onDismissExperimental = {},
                    onClearError = {},
                    onClearRestoredModelTarget = {},
                    onRefresh = {},
                    onImportModel = {},
                    onImportSidecar = {},
                    onStartManualProfile = {},
                    onSaveManualProfile = {},
                    onCancelManualProfile = {},
                    onRetryImport = {},
                    onDiscardImport = {},
                    onDismissImportSuccess = {},
                    onUseImported = {},
                    onDeleteImported = {},
                    onShowCatalogDetails = {},
                    onShowImportedDetails = { _, _ -> },
                    onDismissModelDetails = {},
                    onEditCustomProfile = { _, _ -> },
                    onSaveCustomProfileRevision = {},
                    onCancelCustomProfileEdit = {},
                    onUseCustomProfile = { _, _ -> },
                    onExportCustomProfile = {},
                    onDeleteCustomProfile = {},
                )
            }
        }

        compose.onNodeWithTag("source-separation-preset:$modelId")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("source-separation-preset-use:$modelId")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(modelId, selectedModelId.get()) }
    }
}
