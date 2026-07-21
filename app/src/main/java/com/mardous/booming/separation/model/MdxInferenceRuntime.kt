package com.mardous.booming.separation.model

import java.io.File
import java.util.Locale

enum class MdxModelFormat {
    Onnx,
    Tflite,
}

enum class MdxTensorLayout {
    Nchw,
    Nhwc,
}

data class MdxTensorSpec(
    val name: String?,
    val shape: List<Int>,
    val layout: MdxTensorLayout,
) {
    val elementCount: Int = shape.fold(1) { total, dimension ->
        Math.multiplyExact(total, dimension)
    }

    init {
        require(shape.isNotEmpty() && shape.all { it > 0 }) {
            "Tensor shape must contain only positive dimensions."
        }
    }
}

data class MdxExecutionProfile(
    val profileId: String,
    val displayName: String,
    val outputTag: String,
    val modelFormat: MdxModelFormat,
    val inputTensor: MdxTensorSpec,
    val outputTensor: MdxTensorSpec,
    val dspConfig: MdxDspConfig,
    val modelOutputScale: Float,
    val modelOutputStem: MdxStem,
    val pipelineId: String,
    val pipelineVersion: Int,
    val expectedFileName: String,
    val expectedByteSize: Long? = null,
    val expectedSha256: String? = null,
    val legacyModelVariant: MdxModelVariant? = null,
) {
    val sessionIdentity: String = buildString {
        append(profileId)
        append('|').append(pipelineId)
        append('|').append(pipelineVersion)
        append('|').append(inputTensor.shape.joinToString("x"))
        append('|').append(inputTensor.layout)
        append('|').append(outputTensor.shape.joinToString("x"))
        append('|').append(outputTensor.layout)
    }

    init {
        require(profileId.isNotBlank()) { "Execution profile ID is empty." }
        require(displayName.isNotBlank()) { "Execution profile display name is empty." }
        require(outputTag.isNotBlank()) { "Execution profile output tag is empty." }
        require(modelOutputScale.isFinite() && modelOutputScale > 0f) {
            "Model output scale must be finite and positive."
        }
        require(pipelineId.isNotBlank() && pipelineVersion > 0) {
            "Execution profile pipeline identity is invalid."
        }
        require(expectedFileName.isNotBlank()) { "Expected model file name is empty." }
        require(inputTensor.elementCount == dspConfig.tensorElementCount) {
            "Input tensor does not match the DSP configuration."
        }
        require(outputTensor.elementCount == dspConfig.tensorElementCount) {
            "Output tensor does not match the DSP configuration."
        }
        expectedByteSize?.let { require(it > 0L) { "Expected model size must be positive." } }
        expectedSha256?.let(::requireSha256)
    }

    fun validateArtifact(artifact: MdxModelArtifact) {
        require(artifact.file.name == expectedFileName) {
            "Model file name does not match the execution profile."
        }
        expectedByteSize?.let { expected ->
            require(artifact.byteSize == expected) {
                "Model file size does not match the execution profile."
            }
        }
        expectedSha256?.let { expected ->
            require(artifact.sha256.equals(expected, ignoreCase = true)) {
                "Model SHA-256 does not match the execution profile."
            }
        }
    }

    companion object {
        fun legacy(
            variant: MdxModelVariant,
            dspConfig: MdxDspConfig = MdxDspConfig(),
        ): MdxExecutionProfile {
            val shape = listOf(
                1,
                MdxDspConfig.STEM_COMPLEX_CHANNELS,
                dspConfig.dimF,
                dspConfig.dimT,
            )
            return MdxExecutionProfile(
                profileId = "legacy_${variant.name.lowercase(Locale.US)}",
                displayName = variant.displayName,
                outputTag = variant.outputTag,
                modelFormat = MdxModelFormat.Onnx,
                inputTensor = MdxTensorSpec(null, shape, MdxTensorLayout.Nchw),
                outputTensor = MdxTensorSpec(null, shape, MdxTensorLayout.Nchw),
                dspConfig = dspConfig,
                modelOutputScale = 1f,
                modelOutputStem = variant.modelOutputStem,
                pipelineId = "booming-ss-legacy-mdx-onnx",
                pipelineVersion = 1,
                expectedFileName = variant.fileName,
                legacyModelVariant = variant,
            )
        }

        private fun requireSha256(value: String) {
            require(SHA256_PATTERN.matches(value)) { "Expected model SHA-256 is invalid." }
        }

        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

data class MdxModelArtifact(
    val file: File,
    val byteSize: Long,
    val sha256: String,
) {
    init {
        require(byteSize > 0L) { "Model artifact size must be positive." }
        require(Regex("^[0-9a-fA-F]{64}$").matches(sha256)) {
            "Model artifact SHA-256 is invalid."
        }
    }
}

enum class MdxInferenceBackend {
    OrtCpu,
    LiteRtCpu,
    LiteRtGpu,
}

data class MdxRuntimeDiagnostics(
    val runtimeName: String,
    val backend: MdxInferenceBackend,
    val cpuThreads: Int?,
    val detail: String,
) {
    fun toDisplayText(): String = buildString {
        append("Runtime: ").append(runtimeName)
        append(", backend=").append(backend.name)
        cpuThreads?.let { append(", threads=").append(it) }
        if (detail.isNotBlank()) append(", ").append(detail)
    }
}

interface MdxInferenceSession : AutoCloseable {
    val diagnostics: MdxRuntimeDiagnostics

    fun run(inputNchw: FloatArray): FloatArray
}

interface MdxInferenceSessionFactory {
    val factoryId: String
    val backend: MdxInferenceBackend

    fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession
}

interface MdxInferenceSessionProvider {
    fun acquire(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSessionLease
}

class MdxInferenceSessionLease internal constructor(
    val session: MdxInferenceSession,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeAction()
    }
}

class SingleUseMdxInferenceSessionProvider(
    private val factory: MdxInferenceSessionFactory,
) : MdxInferenceSessionProvider {
    override fun acquire(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSessionLease {
        profile.validateArtifact(artifact)
        val session = factory.create(artifact, profile, runtimeSettings)
        return MdxInferenceSessionLease(session, session::close)
    }
}

class ReusableMdxInferenceSessionProvider(
    private val factory: MdxInferenceSessionFactory = MdxOrtInferenceSessionFactory,
) : MdxInferenceSessionProvider, AutoCloseable {
    private var cachedSession: MdxInferenceSession? = null
    private var cachedKey: SessionKey? = null
    private var activeLeaseCount = 0
    private var closeWhenReleased = false

    @Synchronized
    override fun acquire(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSessionLease {
        check(!closeWhenReleased) { "Inference session provider is closing." }
        profile.validateArtifact(artifact)
        val key = SessionKey(
            factoryId = factory.factoryId,
            artifactSha256 = artifact.sha256.lowercase(Locale.US),
            profileIdentity = profile.sessionIdentity,
            runtimeSettings = runtimeSettings,
        )
        val session = cachedSession
            ?.takeIf { cachedKey == key }
            ?: run {
                check(activeLeaseCount == 0) {
                    "Cannot replace an inference session while it is leased."
                }
                closeCachedLocked()
                factory.create(artifact, profile, runtimeSettings).also { created ->
                    cachedSession = created
                    cachedKey = key
                }
            }
        activeLeaseCount += 1
        return MdxInferenceSessionLease(session, ::releaseLease)
    }

    @Synchronized
    override fun close() {
        if (activeLeaseCount > 0) {
            closeWhenReleased = true
        } else {
            closeCachedLocked()
        }
    }

    @Synchronized
    private fun releaseLease() {
        check(activeLeaseCount > 0) { "Inference session lease count underflow." }
        activeLeaseCount -= 1
        if (activeLeaseCount == 0 && closeWhenReleased) {
            closeCachedLocked()
        }
    }

    private fun closeCachedLocked() {
        cachedSession?.close()
        cachedSession = null
        cachedKey = null
        closeWhenReleased = false
    }

    private data class SessionKey(
        val factoryId: String,
        val artifactSha256: String,
        val profileIdentity: String,
        val runtimeSettings: MdxRuntimeSettings,
    )
}

object DefaultMdxInferenceSessionProvider : MdxInferenceSessionProvider {
    private val delegate = SingleUseMdxInferenceSessionProvider(MdxOrtInferenceSessionFactory)

    override fun acquire(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSessionLease = delegate.acquire(artifact, profile, runtimeSettings)
}
