package com.mardous.booming.separation.setup

import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository

internal class SourceSeparationQuickSetupModelInstaller(
    private val repository: SourceSeparationPresetRepository,
    private val provider: ModelDeliveryProvider,
) {
    fun install(
        modelId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationInstalledPreset {
        val preset = repository.officialPreset(modelId)
        val reference = SourceSeparationDeliveryReference(
            providerId = provider.providerId,
            artifactId = preset.artifactId,
            locator = preset.downloadUrl,
            expectedSha256 = preset.sha256,
            expectedByteSize = preset.byteSize,
        )
        require(provider.supports(reference)) {
            "The configured model provider cannot acquire ${preset.modelId}."
        }
        provider.acquire(reference).use { payload ->
            require(payload.reference == reference) {
                "The model provider returned a different delivery reference."
            }
            require(payload.byteSize == null || payload.byteSize == preset.byteSize) {
                "The model provider returned an unexpected payload size."
            }
            onProgress(0L, preset.byteSize)
            return repository.installOfficial(
                modelId = preset.modelId,
                input = payload.openStream(),
                onProgress = { downloadedBytes ->
                    if (shouldCancel()) {
                        throw java.util.concurrent.CancellationException(
                            "Model installation canceled.",
                        )
                    }
                    onProgress(downloadedBytes, preset.byteSize)
                },
            )
        }
    }
}
