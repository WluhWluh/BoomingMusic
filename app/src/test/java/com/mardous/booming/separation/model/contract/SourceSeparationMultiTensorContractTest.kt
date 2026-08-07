package com.mardous.booming.separation.model.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceSeparationMultiTensorContractTest {
    @Test
    fun `loads a static six stem two branch contract`() {
        val contract = SourceSeparationMultiTensorContractLoader.load(
            Json.encodeToString(sixStemContract()),
        )

        assertEquals(2, contract.tensorContract.inputs.size)
        assertEquals(2, contract.tensorContract.outputs.size)
        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            contract.stemContract.stems.map { it.stemId },
        )
    }

    @Test
    fun `rejects non static tensor axes and dimensions`() {
        val contract = sixStemContract().copy(
            tensorContract = sixStemContract().tensorContract.copy(
                inputs = listOf(
                    sixStemContract().tensorContract.inputs.first().copy(shape = listOf(1, 2, -1)),
                    sixStemContract().tensorContract.inputs[1],
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorContractValidator.validate(contract)
        }
    }

    @Test
    fun `rejects a branch with a different stem order`() {
        val contract = sixStemContract().copy(
            tensorContract = sixStemContract().tensorContract.copy(
                outputBindings = listOf(
                    sixStemContract().tensorContract.outputBindings.first(),
                    sixStemContract().tensorContract.outputBindings[1].copy(
                        stemIds = listOf("drums", "bass", "other", "guitar", "vocals", "piano"),
                    ),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorContractValidator.validate(contract)
        }
    }

    @Test
    fun `rejects a stem dimension that does not match the stem set`() {
        val contract = sixStemContract().copy(
            tensorContract = sixStemContract().tensorContract.copy(
                outputs = listOf(
                    sixStemContract().tensorContract.outputs.first().copy(
                        shape = listOf(1, 5, 4, 2048, 336),
                    ),
                    sixStemContract().tensorContract.outputs[1],
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorContractValidator.validate(contract)
        }
    }

    @Test
    fun `rejects an output that is not bound`() {
        val baseline = sixStemContract()
        val contract = baseline.copy(
            tensorContract = baseline.tensorContract.copy(
                outputBindings = baseline.tensorContract.outputBindings.take(1),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorContractValidator.validate(contract)
        }
    }

    @Test
    fun `rejects a binding whose tensor index differs from its name`() {
        val baseline = sixStemContract()
        val contract = baseline.copy(
            tensorContract = baseline.tensorContract.copy(
                outputBindings = listOf(
                    baseline.tensorContract.outputBindings.first().copy(tensorIndex = 1),
                    baseline.tensorContract.outputBindings[1],
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorContractValidator.validate(contract)
        }
    }

    private fun sixStemContract() = SourceSeparationMultiTensorContract(
        contractSchemaVersion = 1,
        contractKind = "bss-static-multitensor-v1",
        contractId = "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0@1",
        modelId = "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
        displayName = "HTDemucs 6-stem canonical FP32 neural core",
        tensorContract = MultiTensorContract(
            inputs = listOf(
                MultiTensorDescriptor(
                    index = 0,
                    name = "args_0",
                    dtype = MultiTensorDtype.Float32,
                    shape = listOf(1, 2, 343980),
                    axes = listOf("batch", "channel", "sample"),
                ),
                MultiTensorDescriptor(
                    index = 1,
                    name = "args_1",
                    dtype = MultiTensorDtype.Float32,
                    shape = listOf(1, 4, 2048, 336),
                    axes = listOf("batch", "feature", "frequency", "frame"),
                ),
            ),
            outputs = listOf(
                MultiTensorDescriptor(
                    index = 0,
                    name = "output_0",
                    dtype = MultiTensorDtype.Float32,
                    shape = listOf(1, 6, 4, 2048, 336),
                    axes = listOf("batch", "stem", "feature", "frequency", "frame"),
                ),
                MultiTensorDescriptor(
                    index = 1,
                    name = "output_1",
                    dtype = MultiTensorDtype.Float32,
                    shape = listOf(1, 6, 2, 343980),
                    axes = listOf("batch", "stem", "channel", "sample"),
                ),
            ),
            outputBindings = listOf(
                MultiTensorOutputBinding(
                    tensorIndex = 0,
                    tensorName = "output_0",
                    stemAxis = 1,
                    packing = MultiTensorOutputPacking.StemAxis,
                    stemIds = listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
                ),
                MultiTensorOutputBinding(
                    tensorIndex = 1,
                    tensorName = "output_1",
                    stemAxis = 1,
                    packing = MultiTensorOutputPacking.StemAxis,
                    stemIds = listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
                ),
            ),
        ),
        stemContract = MultiTensorStemContract(
            stems = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
                .mapIndexed { index, id ->
                    MultiTensorStemDescriptor(
                        stemId = id,
                        semanticId = id,
                        canonicalLabel = id.replaceFirstChar(Char::uppercase),
                        order = index,
                    )
                },
        ),
        pipelineContract = MultiTensorPipelineContract(
            pipelineId = "booming-ss-htdemucs-neural-core",
            pipelineVersion = 1,
            sampleRate = 44100,
            channelCount = 2,
            windowSamples = 343980,
            fftSize = 4096,
            hopLength = 1024,
            renderMode = MultiTensorRenderMode.NeuralCoreWaveformFrequencyOla,
        ),
    )
}
