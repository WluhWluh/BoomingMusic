package com.mardous.booming.separation.model.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationReleaseCatalogTest {
    @Test
    fun `the three published Demucs entries resolve to exact paired Release assets`() {
        val catalog = SourceSeparationReleaseCatalogValidator.validate(
            SourceSeparationModelMetadata.json.decodeFromString(catalogJson()),
        )

        assertEquals(3, catalog.entries.size)
        assertEquals(
            setOf(
                "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
                "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
                "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
            ),
            catalog.entries.map { it.modelId }.toSet(),
        )

        catalog.entries.forEach { entry ->
            val pair = SourceSeparationReleaseCatalogValidator.resolveMultistem(
                catalog,
                entry.modelId,
            )
            assertEquals(entry.artifact.fileName, pair.artifact.artifactId)
            assertEquals("${entry.artifact.fileName}.json", pair.sidecar.artifactId)
            assertTrue(pair.artifact.locator.contains("/releases/download/v0.2.0-experimental.1/"))
            assertEquals(pair.artifact.locator.substringBeforeLast('/'), pair.sidecar.locator.substringBeforeLast('/'))
        }
    }

    @Test
    fun `catalog rejects sidecar that is not the exact model filename`() {
        val invalid = catalogJson().replace(
            "htdemucs_4s.core.canonical_7p8s.fp32.tflite.json",
            "wrong-sidecar.json",
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationReleaseCatalogValidator.validate(
                SourceSeparationModelMetadata.json.decodeFromString(invalid),
            )
        }
    }

    @Test
    fun `catalog rejects a non GitHub Release URL`() {
        val invalid = catalogJson().replace(
            "https://github.com/WluhWluh/bss-tflite/releases/download/v0.2.0-experimental.1/htdemucs_4s.core.canonical_7p8s.fp32.tflite",
            "https://example.com/htdemucs_4s.core.canonical_7p8s.fp32.tflite",
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationReleaseCatalogValidator.validate(
                SourceSeparationModelMetadata.json.decodeFromString(invalid),
            )
        }
    }

    @Test
    fun `installed model matches only its exact Release identity`() {
        val catalog = SourceSeparationReleaseCatalogValidator.validate(
            SourceSeparationModelMetadata.json.decodeFromString(catalogJson()),
        )
        val entry = catalog.entries.single {
            it.modelId == "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0"
        }
        val installed = SourceSeparationInstalledMultiStemModel(
            modelId = entry.modelId,
            displayName = entry.displayName,
            modelFile = File(entry.artifact.fileName),
            sidecarFile = File(entry.contract.fileName),
            modelByteSize = entry.artifact.byteSize,
            modelSha256 = entry.artifact.sha256,
            contractId = entry.contract.contractId,
            pipelineId = entry.pipelineId,
            installedAtEpochMs = 1L,
        )

        assertTrue(installed.matchesReleaseEntry(entry))
        assertFalse(installed.copy(modelSha256 = "0".repeat(64)).matchesReleaseEntry(entry))
        assertFalse(installed.copy(contractId = "stale@1").matchesReleaseEntry(entry))
        assertFalse(installed.copy(modelByteSize = entry.artifact.byteSize - 1L)
            .matchesReleaseEntry(entry))
        assertFalse(installed.copy(sidecarFile = File("stale.tflite.json"))
            .matchesReleaseEntry(entry))
    }

    private fun catalogJson(): String = """
        {
          "catalogId": "booming-ss-model-catalog-v3",
          "catalogSchemaVersion": 3,
          "releaseTag": "v0.2.0-experimental.1",
          "entries": [
            ${entry("htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0", "htdemucs_4s.core.canonical_7p8s.fp32.tflite", "9855718072ee819bacacdb6b670bd6257feca172bf27ac1d72dff994cdbeed81", 178042000, "6ba70a422abd44a56b4ab7834a83801466fa9adad031c6b8cb5f983794f6fe5c", 10690, true)},
            ${entry("htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0", "htdemucs_6s.core.canonical_7p8s.fp32.tflite", "8b19e919dd17c6a93d862ca9b1158ed72f09feb4c52745819346369506ba4ed7", 117624880, "c9c288a4f95a82a5a1bbed7c5ccd09c46fb49a1a6851585a5b605fbe5cf1fcd6", 11060, false)},
            ${entry("htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0", "htdemucs_6s_guitar_ft.core.canonical_7p8s.fp32.tflite", "ab632a5a024033d557eabb716f8829230532e8e5b4cd7ba146812a301f89b9a5", 117729544, "fa18936f53f8277ad4ac22a9e4e2e4556dd354f1957ab37f330358f187d11865", 13003, false)}
          ]
        }
    """.trimIndent()

    private fun entry(
        modelId: String,
        fileName: String,
        artifactSha256: String,
        artifactByteSize: Long,
        sidecarSha256: String,
        sidecarByteSize: Long,
        isDefault: Boolean,
    ): String = """
        {
          "activationPolicy": "selectable-experimental",
          "allowedBackends": ["cpu"],
          "artifact": {
            "byteSize": $artifactByteSize,
            "fileName": "$fileName",
            "sha256": "$artifactSha256",
            "url": "https://github.com/WluhWluh/bss-tflite/releases/download/v0.2.0-experimental.1/$fileName"
          },
          "artifactFamily": "htdemucs-multistem",
          "contract": {
            "byteSize": $sidecarByteSize,
            "contractId": "${modelId}@1",
            "fileName": "$fileName.json",
            "schemaId": "multitensor-v1",
            "sha256": "$sidecarSha256",
            "url": "https://github.com/WluhWluh/bss-tflite/releases/download/v0.2.0-experimental.1/$fileName.json"
          },
          "displayName": "$modelId",
          "isDefault": $isDefault,
          "modelId": "$modelId",
          "pipelineId": "booming-ss-htdemucs-neural-core",
          "supportLevel": "experimental",
          "validation": {
            "canonicalDeviceGate": "passed-s25-phase6-v2",
            "fullSong": "pending",
            "lifecycle": "pending",
            "listening": "pending"
          }
        }
    """.trimIndent()
}
