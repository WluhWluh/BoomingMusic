package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class SourceSeparationInstalledMultiStemCpuSessionFactoryTest {
    @Test
    fun `rejects a changed installed artifact before creating LiteRT resources`() {
        val root = Files.createTempDirectory("bss-multistem-session").toFile()
        val model = root.resolve("model.tflite").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sidecar = root.resolve("model.tflite.json").apply { writeText("{}") }
        try {
            val installed = SourceSeparationInstalledMultiStemModel(
                modelId = "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
                displayName = "HTDemucs",
                modelFile = model,
                sidecarFile = sidecar,
                modelByteSize = 4L,
                modelSha256 = "a".repeat(64),
                contractId = "contract@1",
                pipelineId = "booming-ss-htdemucs-neural-core",
                installedAtEpochMs = 0L,
            )

            assertThrows(IllegalArgumentException::class.java) {
                SourceSeparationInstalledMultiStemCpuSessionFactory().create(installed)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
