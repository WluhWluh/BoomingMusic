package com.mardous.booming.separation.model.contract

import kotlinx.serialization.json.Json

object SourceSeparationMultiTensorContractLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun load(serialized: String): SourceSeparationMultiTensorContract {
        val contract = json.decodeFromString<SourceSeparationMultiTensorContract>(serialized)
        SourceSeparationMultiTensorContractValidator.validate(contract)
        return contract
    }
}

object SourceSeparationMultiTensorContractValidator {
    private const val SCHEMA_VERSION = 1
    private const val CONTRACT_KIND = "bss-static-multitensor-v1"
    private val idPattern = Regex("[a-z0-9][a-z0-9_.-]*")
    private val axisPattern = Regex("[a-z][a-z0-9_-]*")

    fun validate(contract: SourceSeparationMultiTensorContract) {
        require(contract.contractSchemaVersion == SCHEMA_VERSION) {
            "Unsupported multi-tensor contract schema: ${contract.contractSchemaVersion}"
        }
        require(contract.contractKind == CONTRACT_KIND) {
            "Unsupported multi-tensor contract kind: ${contract.contractKind}"
        }
        require(contract.contractId.isNotBlank()) { "Multi-tensor contract ID is empty" }
        require(idPattern.matches(contract.modelId)) { "Invalid multi-tensor model ID" }
        require(contract.displayName.isNotBlank()) { "Multi-tensor display name is empty" }

        validateStems(contract.stemContract)
        validateTensorDescriptors(contract.tensorContract)
        validateBindings(contract.tensorContract, contract.stemContract)
        validatePipeline(contract.pipelineContract)
    }

    private fun validateStems(stemContract: MultiTensorStemContract) {
        require(stemContract.stems.isNotEmpty()) { "Multi-tensor stem list is empty" }
        require(stemContract.stems.size <= MAX_PLAYABLE_STEMS) {
            "Multi-tensor stem count exceeds product limit"
        }
        val ids = stemContract.stems.map { stem ->
            require(idPattern.matches(stem.stemId)) { "Invalid stem ID: ${stem.stemId}" }
            require(stem.semanticId.isNotBlank()) { "Stem semantic ID is empty" }
            require(stem.canonicalLabel.isNotBlank()) { "Stem canonical label is empty" }
            stem.stemId
        }
        require(ids.toSet().size == ids.size) { "Multi-tensor stem IDs are duplicated" }
        require(stemContract.stems.map { it.order } == ids.indices.toList()) {
            "Multi-tensor stem order must be contiguous and stable"
        }
    }

    private fun validateTensorDescriptors(tensorContract: MultiTensorContract) {
        require(tensorContract.inputs.isNotEmpty()) { "Multi-tensor input list is empty" }
        require(tensorContract.outputs.isNotEmpty()) { "Multi-tensor output list is empty" }
        validateDescriptorNames(tensorContract.inputs, "input")
        validateDescriptorNames(tensorContract.outputs, "output")
    }

    private fun validateDescriptorNames(
        descriptors: List<MultiTensorDescriptor>,
        role: String,
    ) {
        val names = descriptors.map { descriptor ->
            require(descriptor.name.isNotBlank()) { "Multi-tensor $role name is empty" }
            require(descriptor.shape.isNotEmpty()) {
                "Multi-tensor $role ${descriptor.name} has no shape"
            }
            require(descriptor.shape.all { it > 0 }) {
                "Multi-tensor $role ${descriptor.name} is not static"
            }
            require(descriptor.axes.size == descriptor.shape.size) {
                "Multi-tensor $role ${descriptor.name} axes do not match rank"
            }
            require(descriptor.axes.all(axisPattern::matches)) {
                "Multi-tensor $role ${descriptor.name} has invalid axes"
            }
            require(descriptor.axes.toSet().size == descriptor.axes.size) {
                "Multi-tensor $role ${descriptor.name} axes are duplicated"
            }
            require(descriptor.axes.firstOrNull() == "batch" && descriptor.shape.first() == 1) {
                "Multi-tensor $role ${descriptor.name} must use static batch size 1"
            }
            descriptor.name
        }
        require(names.toSet().size == names.size) { "Multi-tensor $role names are duplicated" }
        require(descriptors.map { it.index } == descriptors.indices.toList()) {
            "Multi-tensor $role indexes must be contiguous and ordered"
        }
    }

    private fun validateBindings(
        tensorContract: MultiTensorContract,
        stemContract: MultiTensorStemContract,
    ) {
        val outputNames = tensorContract.outputs.map { it.name }.toSet()
        val stemIds = stemContract.stems.map { it.stemId }
        require(tensorContract.outputBindings.isNotEmpty()) { "Output bindings are empty" }
        require(tensorContract.outputBindings.map { it.tensorName }.toSet().size ==
            tensorContract.outputBindings.size
        ) { "Output tensors have ambiguous bindings" }
        require(tensorContract.outputBindings.map { it.tensorName }.toSet() == outputNames) {
            "Every output tensor must have exactly one binding"
        }
        val branchOrders = tensorContract.outputBindings.map { binding ->
            require(binding.tensorName in outputNames) {
                "Output binding references missing tensor ${binding.tensorName}"
            }
            val tensor = tensorContract.outputs.single { it.name == binding.tensorName }
            require(binding.tensorIndex == tensor.index) {
                "Output binding index differs from ${binding.tensorName}"
            }
            require(binding.packing == MultiTensorOutputPacking.StemAxis) {
                "Unsupported output packing for ${binding.tensorName}"
            }
            require(binding.stemAxis in tensor.shape.indices) {
                "Stem axis is outside ${binding.tensorName} rank"
            }
            require(binding.stemIds == stemIds) {
                "Output stem order differs from the contract stem order"
            }
            require(tensor.axes[binding.stemAxis] == "stem") {
                "Output stem axis is not labelled stem for ${binding.tensorName}"
            }
            require(tensor.shape[binding.stemAxis] == stemIds.size) {
                "Output stem dimension differs from declared stem count"
            }
            binding.stemIds
        }
        require(branchOrders.distinct().size == 1) {
            "Neural-core output branches disagree on stem order"
        }
    }

    private fun validatePipeline(pipeline: MultiTensorPipelineContract) {
        require(pipeline.pipelineId.isNotBlank()) { "Multi-tensor pipeline ID is empty" }
        require(pipeline.pipelineVersion > 0) { "Multi-tensor pipeline version is invalid" }
        require(pipeline.sampleRate > 0 && pipeline.channelCount == 2) {
            "Multi-tensor pipeline audio geometry is invalid"
        }
        require(pipeline.windowSamples > 0) { "Multi-tensor window size is invalid" }
        require(pipeline.fftSize > 0 && pipeline.fftSize % 2 == 0) {
            "Multi-tensor FFT size is invalid"
        }
        require(pipeline.hopLength > 0 && pipeline.hopLength <= pipeline.fftSize) {
            "Multi-tensor hop length is invalid"
        }
    }

    private const val MAX_PLAYABLE_STEMS = 8
}
