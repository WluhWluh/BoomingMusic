package com.mardous.booming.ui.screen.player

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheFormat
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetImportCoordinator
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
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

    @Test
    fun livePresetDownloadAndActivationUseProductionViewModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("livePresetFlow").equals("true", ignoreCase = true))
        val modelId = arguments.getString("livePresetModelId")
            ?.takeIf(String::isNotBlank)
            ?: "uvr_mdxnet_kara"
        val context = instrumentation.targetContext
        val repository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        val original = repository.activeModel() as? SourceSeparationActivePresetState.Reference
            ?: error("The live preset flow requires an active model to restore.")
        val viewModel = SourceSeparationPresetManagementViewModel(
            contentResolver = context.contentResolver,
            repository = repository,
            downloader = get(SourceSeparationPresetDownloader::class.java),
            importCoordinator = get(SourceSeparationPresetImportCoordinator::class.java),
        )

        try {
            compose.setContent {
                val state by viewModel.state.collectAsState()
                MaterialTheme {
                    SourceSeparationPresetManagementSheet(
                        state = state,
                        onDownload = viewModel::download,
                        onCancelDownload = viewModel::cancelDownload,
                        onUse = viewModel::requestUse,
                        onDelete = viewModel::delete,
                        onConfirmExperimental = viewModel::confirmExperimentalUse,
                        onDismissExperimental = viewModel::dismissExperimentalUse,
                        onClearError = viewModel::clearError,
                        onClearRestoredModelTarget = viewModel::clearRestoredModelTarget,
                        onRefresh = viewModel::refresh,
                        onImportModel = {},
                        onImportSidecar = {},
                        onStartManualProfile = viewModel::startManualProfile,
                        onSaveManualProfile = viewModel::saveManualProfile,
                        onCancelManualProfile = viewModel::cancelManualProfile,
                        onRetryImport = viewModel::retryImport,
                        onDiscardImport = viewModel::discardImport,
                        onDismissImportSuccess = viewModel::dismissImportSuccess,
                        onUseImported = viewModel::requestUseImported,
                        onDeleteImported = viewModel::deleteImported,
                        onShowCatalogDetails = viewModel::showCatalogDetails,
                        onShowImportedDetails = viewModel::showImportedDetails,
                        onDismissModelDetails = viewModel::dismissModelDetails,
                        onEditCustomProfile = viewModel::editCustomProfile,
                        onSaveCustomProfileRevision = viewModel::saveCustomProfileRevision,
                        onCancelCustomProfileEdit = viewModel::cancelCustomProfileEdit,
                        onUseCustomProfile = viewModel::useCustomProfile,
                        onExportCustomProfile = {},
                        onDeleteCustomProfile = viewModel::deleteCustomProfile,
                    )
                }
            }

            if (viewModel.state.value.entries.single { it.modelId == modelId }.installed == null) {
                compose.onNodeWithTag("source-separation-preset-download:$modelId")
                    .performScrollTo()
                    .assertIsEnabled()
                    .performClick()
                compose.waitUntil(timeoutMillis = LIVE_PRESET_TIMEOUT_MS) {
                    viewModel.state.value.entries.single { it.modelId == modelId }
                        .let { item -> item.installed != null && item.transferState == null }
                }
            }

            compose.onNodeWithTag("source-separation-preset-use:$modelId")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(timeoutMillis = LIVE_PRESET_TIMEOUT_MS) {
                viewModel.state.value.confirmationModelId == modelId
            }
            compose.onNodeWithTag("source-separation-preset-confirm-experimental")
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(timeoutMillis = LIVE_PRESET_TIMEOUT_MS) {
                viewModel.state.value.entries.single { it.modelId == modelId }.active
            }

            val active = repository.activeModel() as SourceSeparationActivePresetState.Reference
            assertEquals(modelId, active.reference.modelId)
            assertTrue(repository.installedModels().any { it.modelId == modelId })
        } finally {
            repository.activate(
                sha256 = original.reference.artifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
        }
    }

    @Test
    fun liveCacheDeleteUsesProductionViewModel() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveCacheDeleteFlow").equals("true", ignoreCase = true))
        val cacheKey = arguments.getString("liveCacheKey")
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: error("The live cache-delete flow requires a lowercase cache key.")
        val runtime = get<SourceSeparationRuntimeFacade>(SourceSeparationRuntimeFacade::class.java)
        val worker = get<SourceSeparationForegroundWorkerCoordinator>(
            SourceSeparationForegroundWorkerCoordinator::class.java,
        )
        val idleDeadline = SystemClock.elapsedRealtime() + LIVE_CACHE_TIMEOUT_MS
        while (worker.isWorkerActive() && SystemClock.elapsedRealtime() < idleDeadline) {
            SystemClock.sleep(100L)
        }
        check(!worker.isWorkerActive()) { "The live cache-delete flow requires an idle worker." }
        check(runtime.entries().any { it.cacheKey == cacheKey }) {
            "The requested live cache entry is not present."
        }
        val viewModel = SourceSeparationModelAwareCacheManagementViewModel(runtime)

        compose.setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                SourceSeparationModelAwareCacheManagementPage(
                    state = state,
                    autoCleanupEnabled = true,
                    partialLimit = 3,
                    completedLimit = 2,
                    onBack = {},
                    onRefresh = viewModel::refresh,
                    onDeleteAll = viewModel::deleteAll,
                    onDelete = viewModel::delete,
                    onPlay = {},
                    onDismissFailure = viewModel::clearFailure,
                    onAutoCleanupChange = {},
                    onPartialLimitChange = {},
                    onCompletedLimitChange = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = LIVE_CACHE_TIMEOUT_MS) {
            val state = viewModel.state.value
            !state.loading && state.items.any { it.cacheKey == cacheKey }
        }
        compose.onNodeWithTag("source-separation-cache-delete:$cacheKey")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = LIVE_CACHE_TIMEOUT_MS) {
            val state = viewModel.state.value
            !state.loading &&
                cacheKey !in state.deletingCacheKeys &&
                state.items.none { it.cacheKey == cacheKey }
        }

        assertTrue(runtime.entries().none { it.cacheKey == cacheKey })
        assertTrue(!worker.isWorkerActive())
    }

    private companion object {
        const val LIVE_PRESET_TIMEOUT_MS = 10L * 60L * 1_000L
        const val LIVE_CACHE_TIMEOUT_MS = 60_000L
    }
}
