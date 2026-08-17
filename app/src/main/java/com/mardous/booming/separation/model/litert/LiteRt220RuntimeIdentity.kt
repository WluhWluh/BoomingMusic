package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.runtime.SourceSeparationCpuRuntimeInstallation
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeLayout

internal object LiteRt220RuntimeIdentity {
    const val BASE_VERSION = "2.2.0"
    const val ARTIFACT_VERSION = "2.2.0-bss.2"
    const val RELEASE_VERSION = "2.2.0-bss.2-exp.1"
    const val SOURCE_AAR_FILE_NAME = "litert-android-2.2.0-bss.2.aar"
    const val SOURCE_AAR_SHA256 =
        "35b55a0ef9a6d28e56271a9bc3b6b6cc8a84b16732b17b34b2a6b51ee7be3124"

    private val librariesByAbi = mapOf(
        "arm64-v8a" to LibraryPair(
            coreSha256 = "97355a36cb8ac7628cf407773291e98da79f3ef184cc43cb0e57dedf5f0c0637",
            jniSha256 = "708b7a2bcdef55b698878ae237971fbd31d9a6bfcfe6ac81dc62c880ad6b4e8a",
        ),
        "armeabi-v7a" to LibraryPair(
            coreSha256 = "5860fcecc1cef9bfb69a33b799465eba058102aa4d80e05320bc9be7a687e075",
            jniSha256 = "6bb45f8c3fa7a97d65a5d44b0800244da7a09a2b17cb8348880fc43c2e2b6c7a",
        ),
        "x86_64" to LibraryPair(
            coreSha256 = "38a71966ac2ccd76c2782d5d96e317f5e6d3322ae9f8e3dfff6e3327e284c591",
            jniSha256 = "a05652e65e71ddb5b1f38c569a9e3722b87efeb76d2036266154a429bab9c657",
        ),
        "x86" to LibraryPair(
            coreSha256 = "83132f9eb2fbbc0858a2d96c45bc5cb39c54922c9f7f5aed26bb5563ce2cb21c",
            jniSha256 = "570452100ba34041b95b066310cbc8db7a14a14d66dc51742727bbe35afc8699",
        ),
    )

    fun requireExact(
        installation: SourceSeparationCpuRuntimeInstallation,
    ): SourceSeparationCpuRuntimeInstallation {
        val manifest = installation.manifest
        val identity = installation.identity
        val expectedLibraries = requireNotNull(librariesByAbi[identity.abi]) {
            "LiteRT $ARTIFACT_VERSION has no pinned binary identity for ${identity.abi}."
        }
        require(identity.contractSchemaVersion == SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION &&
            manifest.baseLiteRtVersion == BASE_VERSION &&
            identity.runtimeArtifactVersion == ARTIFACT_VERSION &&
            identity.releaseVersion == RELEASE_VERSION &&
            manifest.sourceAar.fileName == SOURCE_AAR_FILE_NAME &&
            manifest.sourceAar.sha256.equals(SOURCE_AAR_SHA256, ignoreCase = true) &&
            identity.librarySha256.equals(expectedLibraries.coreSha256, ignoreCase = true) &&
            identity.jniLibrarySha256.equals(expectedLibraries.jniSha256, ignoreCase = true)
        ) {
            "The loaded LiteRT runtime does not match the exact $ARTIFACT_VERSION native ABI identity."
        }
        return installation
    }

    private data class LibraryPair(
        val coreSha256: String,
        val jniSha256: String,
    )
}

internal fun shouldUseJvmMdxCpuPipeline(
    runtimeAbi: String,
    xnnPackFlags: Int?,
): Boolean = runtimeAbi == "x86" || xnnPackFlags != null
