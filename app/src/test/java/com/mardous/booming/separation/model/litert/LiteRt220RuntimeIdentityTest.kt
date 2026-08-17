package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.runtime.SourceSeparationCpuRuntimeInstallation
import com.mardous.booming.separation.runtime.SourceSeparationCpuRuntimeManifest
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeElfManifest
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeFileManifest
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeIdentity
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeLayout
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeSourceAar
import java.io.File
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRt220RuntimeIdentityTest {
    @Test
    fun `exact released arm64 identity is accepted`() {
        val installation = installation()

        assertSame(installation, LiteRt220RuntimeIdentity.requireExact(installation))
    }

    @Test
    fun `native ABI bridge rejects a different core binary`() {
        val installation = installation(coreSha256 = "0".repeat(64))

        assertThrows(IllegalArgumentException::class.java) {
            LiteRt220RuntimeIdentity.requireExact(installation)
        }
    }

    @Test
    fun `native ABI bridge rejects a floating patch version`() {
        val installation = installation(runtimeArtifactVersion = "2.2.1-bss.1")

        assertThrows(IllegalArgumentException::class.java) {
            LiteRt220RuntimeIdentity.requireExact(installation)
        }
    }

    @Test
    fun `only pure x86 uses the ABI fallback without explicit flags`() {
        assertTrue(shouldUseJvmMdxCpuPipeline("x86", null))
        assertFalse(shouldUseJvmMdxCpuPipeline("x86_64", null))
        assertFalse(shouldUseJvmMdxCpuPipeline("armeabi-v7a", null))
        assertFalse(shouldUseJvmMdxCpuPipeline("arm64-v8a", null))
        assertTrue(shouldUseJvmMdxCpuPipeline("arm64-v8a", 0))
    }

    private fun installation(
        runtimeArtifactVersion: String = LiteRt220RuntimeIdentity.ARTIFACT_VERSION,
        coreSha256: String = ARM64_CORE_SHA256,
    ): SourceSeparationCpuRuntimeInstallation {
        val files = listOf(
            runtimeFile("runtime", SourceSeparationRuntimeLayout.CORE_LIBRARY_FILE_NAME, coreSha256),
            runtimeFile("jni", SourceSeparationRuntimeLayout.JNI_LIBRARY_FILE_NAME, ARM64_JNI_SHA256),
        )
        val manifest = SourceSeparationCpuRuntimeManifest(
            schemaVersion = 2,
            contractSchemaVersion = SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION,
            component = SourceSeparationRuntimeLayout.CPU_COMPONENT,
            abi = "arm64-v8a",
            androidMinApi = 26,
            baseLiteRtVersion = LiteRt220RuntimeIdentity.BASE_VERSION,
            capabilities = listOf("cpu"),
            runtimeArtifactVersion = runtimeArtifactVersion,
            releaseVersion = LiteRt220RuntimeIdentity.RELEASE_VERSION,
            loadOrder = SourceSeparationRuntimeLayout.CPU_LIBRARY_LOAD_ORDER,
            files = files,
            sourceAar = SourceSeparationRuntimeSourceAar(
                fileName = LiteRt220RuntimeIdentity.SOURCE_AAR_FILE_NAME,
                sha256 = LiteRt220RuntimeIdentity.SOURCE_AAR_SHA256,
            ),
        )
        return SourceSeparationCpuRuntimeInstallation(
            directory = File("runtime"),
            manifestFile = File("runtime/manifest.json"),
            libraryFiles = emptyMap(),
            manifest = manifest,
            identity = SourceSeparationRuntimeIdentity(
                contractSchemaVersion = SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION,
                runtimeArtifactVersion = runtimeArtifactVersion,
                releaseVersion = LiteRt220RuntimeIdentity.RELEASE_VERSION,
                abi = "arm64-v8a",
                librarySha256 = coreSha256,
                jniLibrarySha256 = ARM64_JNI_SHA256,
            ),
        )
    }

    private fun runtimeFile(role: String, path: String, sha256: String) =
        SourceSeparationRuntimeFileManifest(
            role = role,
            path = path,
            byteSize = 1L,
            sha256 = sha256,
            elf = SourceSeparationRuntimeElfManifest(
                elfClass = "ELF64",
                machine = "EM_AARCH64",
                needed = emptyList(),
                soname = path,
                loadAlignment = 16_384L,
            ),
        )

    private companion object {
        const val ARM64_CORE_SHA256 =
            "97355a36cb8ac7628cf407773291e98da79f3ef184cc43cb0e57dedf5f0c0637"
        const val ARM64_JNI_SHA256 =
            "708b7a2bcdef55b698878ae237971fbd31d9a6bfcfe6ac81dc62c880ad6b4e8a"
    }
}
