package com.mardous.booming.separation

import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationMultiStemContractMemoTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `parsed contract is reused until the sidecar stamp changes`() {
        val memo = SourceSeparationMultiStemContractMemo()
        val installed = installedModel(validContractText())

        val first = memo.resolve(installed)
        val second = memo.resolve(installed)
        installed.sidecarFile.appendText("\n")
        val third = memo.resolve(installed)

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(
            SourceSeparationMultiStemContractMemoSnapshot(
                hits = 1L,
                misses = 2L,
                entryCount = 2,
            ),
            memo.snapshot(),
        )
    }

    @Test
    fun `invalid sidecar is retried after replacement`() {
        val memo = SourceSeparationMultiStemContractMemo()
        val installed = installedModel("not json")

        assertThrows(IllegalArgumentException::class.java) {
            memo.resolve(installed)
        }
        installed.sidecarFile.writeText(validContractText())

        assertEquals(installed.modelId, memo.resolve(installed).modelContract.modelId)
        assertEquals(2L, memo.snapshot().misses)
        assertEquals(1, memo.snapshot().entryCount)
    }

    private fun installedModel(sidecarText: String): SourceSeparationInstalledMultiStemModel {
        val model = temporary.newFile("model.tflite").apply { writeText("model") }
        val sidecar = temporary.newFile("model.tflite.json").apply { writeText(sidecarText) }
        return SourceSeparationInstalledMultiStemModel(
            modelId = "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            displayName = "Official HTDemucs 4-stem Base FP32",
            modelFile = model,
            sidecarFile = sidecar,
            modelByteSize = model.length(),
            modelSha256 = "a".repeat(64),
            contractId = "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0@1",
            pipelineId = "booming-ss-htdemucs-neural-core",
            installedAtEpochMs = 1L,
        )
    }

    private fun validContractText(): String {
        val resource = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "source-separation/research-contracts/htdemucs-4s-official-base-fp32.json",
            ),
        )
        return resource.bufferedReader().use { it.readText() }
    }
}
