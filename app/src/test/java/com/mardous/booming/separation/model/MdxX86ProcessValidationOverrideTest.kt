package com.mardous.booming.separation.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxX86ProcessValidationOverrideTest {
    private val x86 = MdxRuntimePlatform(androidApi = 26, runtimeAbi = MdxRuntimeAbi.X86)

    @Test
    fun `validation gate admits only exact unsupported pinned artifacts`() {
        assertTrue(
            MdxX86ProcessValidationOverride.permitsCatalogQualification(
                modelId = MdxX86ProcessValidationOverride.MODEL_ID_9662,
                artifactSha256 = MdxX86ProcessValidationOverride.ARTIFACT_SHA256_9662,
                contractId = MdxX86ProcessValidationOverride.CONTRACT_ID_9662,
                platform = x86,
                originalStatus = MdxRuntimeSupportStatus.Unsupported,
                enabled = true,
            )
        )
        assertTrue(
            MdxX86ProcessValidationOverride.permitsCatalogQualification(
                modelId = MdxX86ProcessValidationOverride.MODEL_ID_KARA,
                artifactSha256 = MdxX86ProcessValidationOverride.ARTIFACT_SHA256_KARA,
                contractId = MdxX86ProcessValidationOverride.CONTRACT_ID_KARA,
                platform = x86,
                originalStatus = MdxRuntimeSupportStatus.Unsupported,
                enabled = true,
            )
        )
    }

    @Test
    fun `validation gate fails closed for build ABI identity and status mismatches`() {
        val request = {
            enabled: Boolean,
            platform: MdxRuntimePlatform,
            hash: String,
            status: MdxRuntimeSupportStatus,
            ->
            MdxX86ProcessValidationOverride.permitsCatalogQualification(
                modelId = MdxX86ProcessValidationOverride.MODEL_ID_9662,
                artifactSha256 = hash,
                contractId = MdxX86ProcessValidationOverride.CONTRACT_ID_9662,
                platform = platform,
                originalStatus = status,
                enabled = enabled,
            )
        }

        assertFalse(request(false, x86, MdxX86ProcessValidationOverride.ARTIFACT_SHA256_9662,
            MdxRuntimeSupportStatus.Unsupported))
        assertFalse(request(true, x86.copy(runtimeAbi = MdxRuntimeAbi.X86_64),
            MdxX86ProcessValidationOverride.ARTIFACT_SHA256_9662,
            MdxRuntimeSupportStatus.Unsupported))
        assertFalse(request(true, x86, "0".repeat(64), MdxRuntimeSupportStatus.Unsupported))
        assertFalse(request(true, x86, MdxX86ProcessValidationOverride.ARTIFACT_SHA256_9662,
            MdxRuntimeSupportStatus.KnownGood))
    }
}
