package com.mardous.booming.separation.model.litert

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdxLiteRtBoundedGpuCapabilityDeviceTest {
    @Test
    fun arm64WithoutGpuComponentReportsUnavailableCapability() {
        val platform = AndroidMdxRuntimePlatformProvider.current()
        assumeTrue(platform.runtimeAbi == MdxRuntimeAbi.Arm64V8a)

        val capability = MdxLiteRtNativeBoundedGpuCapabilityProvider.query()
        val decision = MdxLiteRtBoundedGpuContract.evaluate(capability)

        assertFalse(decision.detail, decision.isExact)
        assertFalse(capability.available)
        assertEquals("uninstalled", capability.artifactVersion)
        assertEquals("uninstalled", capability.profileId)
        assertEquals(0, capability.kernelBatchSize)
        assertEquals(0, capability.commandQueueWindowSize)
    }

    @Test
    fun cpuOnlyAbiRejectsGpuBeforeCapabilityLookup() {
        val platform = AndroidMdxRuntimePlatformProvider.current()
        assumeFalse(platform.runtimeAbi == MdxRuntimeAbi.Arm64V8a)
        var capabilityQueries = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = context.assets.open(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            .use { SourceSeparationModelMetadata.decodeBundledCatalog(it.readBytes()) }
        val profile = catalog.contracts
            .single { it.modelId == "uvr_mdxnet_3_9662" }
            .toMdxExecutionProfile(catalog.runtimeQualifications)
        val artifact = MdxModelArtifact(
            file = File(context.cacheDir, profile.expectedFileName),
            byteSize = requireNotNull(profile.expectedByteSize),
            sha256 = requireNotNull(profile.expectedSha256),
        )
        val eligibility = AndroidMdxLiteRtGpuEligibilityProvider(
            context = context,
            boundedCapabilityProvider = {
                capabilityQueries += 1
                error("A CPU-only ABI must not query the bounded GPU capability.")
            },
        ).evaluate(artifact, profile, platform)

        assertFalse(eligibility.detail, eligibility.isEligible)
        assertEquals(MdxLiteRtGpuEligibilityReason.CpuOnlyAbi, eligibility.reason)
        assertEquals(0, capabilityQueries)
    }
}
