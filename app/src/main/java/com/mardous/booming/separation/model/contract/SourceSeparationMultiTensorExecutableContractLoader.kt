package com.mardous.booming.separation.model.contract

import java.net.URI
import kotlinx.serialization.json.Json

object SourceSeparationMultiTensorExecutableContractLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun load(serialized: String): SourceSeparationMultiTensorExecutableContract {
        val contract = json.decodeFromString<SourceSeparationMultiTensorExecutableContract>(serialized)
        SourceSeparationMultiTensorExecutableContractValidator.validate(contract)
        return contract
    }
}

object SourceSeparationMultiTensorExecutableContractValidator {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val revisionPattern = Regex("^[0-9a-f]{40}$")
    private val rolePattern = Regex("^[a-z][a-z0-9-]*$")

    fun validate(contract: SourceSeparationMultiTensorExecutableContract) {
        require(contract.executableSchemaVersion == SCHEMA_VERSION) {
            "Unsupported executable multi-tensor schema: ${contract.executableSchemaVersion}"
        }
        require(contract.executableKind == CONTRACT_KIND) {
            "Unsupported executable multi-tensor kind: ${contract.executableKind}"
        }
        SourceSeparationMultiTensorContractValidator.validate(contract.modelContract)
        require(contract.allowedBackends == listOf(MultiTensorExecutableBackend.Cpu)) {
            "The frozen HTDemucs candidate batch is CPU-only"
        }
        validatePinnedFile(
            MultiTensorPinnedFile(
                role = "runtime-artifact",
                fileName = contract.artifact.fileName,
                byteSize = contract.artifact.byteSize,
                sha256 = contract.artifact.sha256,
            ),
        )
        require(contract.artifact.fileName.endsWith(".tflite")) {
            "Executable artifact must be a TFLite file"
        }
        validateFlatBuffer(contract)
        validateProvenance(contract.provenance, contract.modelContract.modelId)
        validateConversion(contract.conversion)
        validateFixtures(contract)
        validateNotices(contract.notices, contract.modelContract.modelId)
    }

    private fun validateFlatBuffer(contract: SourceSeparationMultiTensorExecutableContract) {
        val identity = contract.flatBuffer
        require(identity.schemaVersion == 3 && identity.customOperatorCount == 0) {
            "Executable FlatBuffer must use TFLite schema 3 without custom operators"
        }
        require(identity.minimumRuntimeVersion.isNotBlank() && identity.signatureKey.isNotBlank()) {
            "Executable FlatBuffer metadata is incomplete"
        }
        validateBindings(identity.inputs, contract.modelContract.tensorContract.inputs, "input")
        validateBindings(identity.outputs, contract.modelContract.tensorContract.outputs, "output")
        val tensorIndexes = (identity.inputs + identity.outputs).map { it.tensorIndex }
        require(tensorIndexes.toSet().size == tensorIndexes.size) {
            "FlatBuffer signature tensor indexes are duplicated"
        }
    }

    private fun validateBindings(
        bindings: List<MultiTensorFlatBufferBinding>,
        descriptors: List<MultiTensorDescriptor>,
        role: String,
    ) {
        require(bindings.size == descriptors.size) { "FlatBuffer $role count differs from contract" }
        bindings.zip(descriptors).forEach { (binding, descriptor) ->
            require(
                binding.index == descriptor.index &&
                    binding.logicalName == descriptor.name &&
                    binding.tensorName.isNotBlank() && binding.tensorIndex >= 0 &&
                    binding.dtype == descriptor.dtype && binding.shape == descriptor.shape &&
                    binding.axes == descriptor.axes,
            ) { "FlatBuffer $role ${descriptor.name} differs from the static tensor contract" }
        }
    }

    private fun validateProvenance(
        provenance: MultiTensorExecutableProvenance,
        modelId: String,
    ) {
        require(revisionPattern.matches(provenance.loaderRevision)) { "Invalid loader revision" }
        require(revisionPattern.matches(provenance.neuralCoreReferenceRevision)) {
            "Invalid neural-core reference revision"
        }
        provenance.sources.forEach { source ->
            validatePinnedFile(source)
            require(!source.repository.isNullOrBlank() && source.revision != null) {
                "Executable model sources must pin a repository revision"
            }
        }
        validatePinnedFile(provenance.exportScript)
        validatePinnedFile(provenance.requirementsLock)
        val roles = provenance.sources.map { it.role }
        require(roles.toSet().size == roles.size) { "Provenance source roles are duplicated" }
        require(roles.containsAll(listOf("source-weight", "model-metadata", "bag-manifest"))) {
            "Executable provenance is missing a canonical model source"
        }
        if (modelId.contains("guitar_ft")) {
            require(roles.containsAll(listOf("fine-tune-checkpoint", "model-card"))) {
                "Guitar-ft provenance is missing its checkpoint or model card"
            }
        }
    }

    private fun validateConversion(conversion: MultiTensorConversionIdentity) {
        validatePinnedFile(conversion.exportReport)
        require(REQUIRED_TOOLS.all { !conversion.toolVersions[it].isNullOrBlank() }) {
            "Executable conversion tool versions are incomplete"
        }
        require(
            conversion.inputKind == "project-owned-pytorch-neural-core" &&
                conversion.onnxRole == "not-generated" && conversion.strictExport &&
                conversion.deterministicPositionalEmbedding &&
                !conversion.lightweightConversion && !conversion.runtimeConstantFolding &&
                !conversion.enableX64,
        ) { "Executable conversion recipe differs from the frozen canonical recipe" }
    }

    private fun validateFixtures(contract: SourceSeparationMultiTensorExecutableContract) {
        val fixtures = contract.fixtures
        require(fixtures.map { it.role }.toSet() == MultiTensorFixtureRole.entries.toSet() &&
            fixtures.size == MultiTensorFixtureRole.entries.size
        ) { "Executable contract must bind every canonical fixture exactly once" }
        val inputs = contract.modelContract.tensorContract.inputs
        val outputs = contract.modelContract.tensorContract.outputs
        val stemCount = contract.modelContract.stemContract.stems.size
        val expectedShapes = mapOf(
            MultiTensorFixtureRole.WaveformInput to inputs[0].shape,
            MultiTensorFixtureRole.SpectrumInput to inputs[1].shape,
            MultiTensorFixtureRole.FrequencyGolden to outputs[0].shape,
            MultiTensorFixtureRole.WaveformGolden to outputs[1].shape,
            MultiTensorFixtureRole.FrequencyWaveformGolden to outputs[1].shape,
            MultiTensorFixtureRole.CombinedGolden to outputs[1].shape,
            MultiTensorFixtureRole.OlaMixInput to listOf(1, 2, OLA_TRACK_SAMPLES),
            MultiTensorFixtureRole.OlaCombinedGolden to
                listOf(1, stemCount, 2, OLA_TRACK_SAMPLES),
        )
        fixtures.forEach { fixture ->
            require(fixture.fileName.endsWith(".f32le.raw")) { "Invalid fixture file name" }
            require(sha256Pattern.matches(fixture.sha256)) { "Invalid fixture SHA-256" }
            require(fixture.shape == expectedShapes.getValue(fixture.role)) {
                "Fixture ${fixture.role} shape differs from the executable contract"
            }
            require(fixture.byteSize == elementCount(fixture.shape) * Float.SIZE_BYTES) {
                "Fixture ${fixture.role} byte size differs from its shape"
            }
        }
    }

    private fun validateNotices(notices: List<MultiTensorLicenseNotice>, modelId: String) {
        require(notices.isNotEmpty() && notices.map { it.noticeId }.toSet().size == notices.size) {
            "Executable license notices are empty or duplicated"
        }
        notices.forEach { notice ->
            require(rolePattern.matches(notice.noticeId) && notice.subject.isNotBlank() &&
                notice.licenseId.isNotBlank() && notice.statement.isNotBlank()
            ) { "Executable license notice is incomplete" }
            requireHttps(notice.sourceUrl)
            notice.sourceRevision?.let { require(revisionPattern.matches(it)) }
        }
        if (modelId.contains("guitar_ft")) {
            val byId = notices.associateBy { it.noticeId }
            require(byId["author-model"]?.licenseId == "Apache-2.0" &&
                byId["author-model"]?.sourceRevision == GUITAR_FT_REVISION
            ) { "Guitar-ft author license declaration is not frozen" }
            require(byId["base-model"]?.licenseId == "MIT") {
                "Guitar-ft base-model attribution is missing"
            }
            require(byId["training-data"]?.licenseId == "CC-BY-NC-SA-4.0") {
                "Guitar-ft MoisesDB disclosure is missing"
            }
            require(byId["conversion"]?.statement?.contains("FP32 LiteRT FlatBuffer") == true) {
                "Guitar-ft conversion notice is missing"
            }
        }
    }

    private fun validatePinnedFile(file: MultiTensorPinnedFile) {
        require(rolePattern.matches(file.role) && file.fileName.isNotBlank() && file.byteSize > 0L) {
            "Pinned file identity is incomplete"
        }
        require(sha256Pattern.matches(file.sha256)) { "Pinned file SHA-256 is invalid" }
        require((file.repository == null) == (file.revision == null)) {
            "Pinned repository and revision must be supplied together"
        }
        file.revision?.let { require(revisionPattern.matches(it)) { "Pinned revision is invalid" } }
    }

    private fun requireHttps(value: String) {
        val uri = URI(value)
        require(uri.isAbsolute && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "License notice source must be an HTTPS URL"
        }
    }

    private fun elementCount(shape: List<Int>): Long = shape.fold(1L, Math::multiplyExact)

    private const val SCHEMA_VERSION = 1
    private const val CONTRACT_KIND = "bss-static-multitensor-executable-v1"
    private const val OLA_TRACK_SAMPLES = 515_970
    private const val GUITAR_FT_REVISION = "163ec83135ee06e6f10cb8cd94d2ecef8f3f34ad"
    private val REQUIRED_TOOLS = setOf(
        "python",
        "torch",
        "numpy",
        "safetensors",
        "litert-torch",
        "ai-edge-litert",
    )
}
