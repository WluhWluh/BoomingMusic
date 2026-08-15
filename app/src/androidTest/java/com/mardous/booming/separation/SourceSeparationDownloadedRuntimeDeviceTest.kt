package com.mardous.booming.separation

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryCapabilities
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryOperation
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryPayload
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeCatalogLoader
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeCatalogLoader
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeLayout
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSeparationDownloadedRuntimeDeviceTest {
    @Test
    fun stagedBundlesInstallThroughProductionStoresAndBootstrap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val cpuArgument = arguments.getString(ARG_STAGED_CPU_RUNTIME_ZIP)
        val gpuArgument = arguments.getString(ARG_STAGED_GPU_RUNTIME_ZIP)
        assumeTrue(
            "Pass both -e $ARG_STAGED_CPU_RUNTIME_ZIP <path> and " +
                "-e $ARG_STAGED_GPU_RUNTIME_ZIP <path> to run the staged Store gate.",
            !cpuArgument.isNullOrBlank() || !gpuArgument.isNullOrBlank(),
        )
        require(!cpuArgument.isNullOrBlank() && !gpuArgument.isNullOrBlank()) {
            "Both staged runtime bundle arguments are required."
        }

        val context = instrumentation.targetContext
        val cpuZip = requireStagedFile(context, cpuArgument)
        val gpuZip = requireStagedFile(context, gpuArgument)
        require(cpuZip != gpuZip) { "CPU and GPU staging paths must be distinct." }
        val processAbi = currentProcessAbi()
        val cpuCatalog = SourceSeparationRuntimeCatalogLoader.load(context)
        val gpuCatalog = SourceSeparationGpuRuntimeCatalogLoader.load(context)
        val cpuEntry = requireNotNull(cpuCatalog.entryForAbi(processAbi)) {
            "No CPU runtime catalog entry exists for $processAbi."
        }
        val gpuEntry = requireNotNull(gpuCatalog.entries.singleOrNull { it.abi == processAbi }) {
            "No bounded GPU runtime catalog entry exists for $processAbi."
        }
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, cpuEntry.runtimeArtifactVersion)
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, gpuEntry.runtimeArtifactVersion)

        val runtimeRoot = SourceSeparationRuntimeLayout.runtimeRoot(context)
        if (runtimeRoot.exists()) {
            assertTrue("Unable to reset the product runtime root.", runtimeRoot.deleteRecursively())
        }
        assertFalse(runtimeRoot.exists())

        val provider = StagedRuntimeDeliveryProvider(
            mapOf(
                cpuEntry.componentId to cpuZip,
                gpuEntry.componentId to gpuZip,
            ),
        )
        val cpuStore = SourceSeparationRuntimeStore(
            root = runtimeRoot,
            catalog = cpuCatalog,
            provider = provider,
            androidApi = Build.VERSION.SDK_INT,
        )
        val gpuStore = SourceSeparationGpuRuntimeStore(
            root = runtimeRoot,
            catalog = gpuCatalog,
            provider = provider,
            androidApi = Build.VERSION.SDK_INT,
        )

        assertEquals(
            SourceSeparationRuntimeState.Missing,
            cpuStore.inventory(cpuEntry.componentId).state,
        )
        assertEquals(
            SourceSeparationGpuRuntimeState.Missing,
            gpuStore.inventory(gpuEntry.componentId).state,
        )

        val installedCpu = cpuStore.install(cpuEntry.componentId)
        assertEquals(SourceSeparationRuntimeState.Installed, installedCpu.state)
        val installedCpuIdentity = requireNotNull(installedCpu.installation).identity
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, installedCpuIdentity.runtimeArtifactVersion)
        assertEquals(cpuEntry.producerReleaseVersion, installedCpuIdentity.releaseVersion)
        assertEquals(cpuEntry.abi, installedCpuIdentity.abi)
        assertTrue(
            installedCpuIdentity.librarySha256.equals(
                cpuEntry.innerLibrary.sha256,
                ignoreCase = true,
            ),
        )
        assertTrue(
            installedCpuIdentity.jniLibrarySha256.equals(
                cpuEntry.innerJniLibrary.sha256,
                ignoreCase = true,
            ),
        )

        val installedGpu = gpuStore.install(gpuEntry.componentId)
        assertEquals(SourceSeparationGpuRuntimeState.Installed, installedGpu.state)
        val installedGpuIdentity = requireNotNull(installedGpu.installation).identity
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, installedGpuIdentity.runtimeArtifactVersion)
        assertEquals(EXPECTED_GPU_PROFILE_ID, installedGpuIdentity.profileId)
        assertEquals(
            listOf(cpuEntry.componentId, gpuEntry.componentId),
            provider.acquiredArtifactIds,
        )

        val loadedCpu = SourceSeparationRuntimeBootstrap.ensureLoaded(context)
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, loadedCpu.identity.runtimeArtifactVersion)
        assertEquals(cpuEntry.producerReleaseVersion, loadedCpu.identity.releaseVersion)
        assertEquals(cpuEntry.abi, loadedCpu.identity.abi)
        assertTrue(
            loadedCpu.identity.librarySha256.equals(cpuEntry.innerLibrary.sha256, ignoreCase = true),
        )
        assertTrue(
            loadedCpu.identity.jniLibrarySha256.equals(
                cpuEntry.innerJniLibrary.sha256,
                ignoreCase = true,
            ),
        )

        SourceSeparationGpuRuntimeBootstrap.ensureLoaded(context)
        val capability = SourceSeparationGpuRuntimeBootstrap.capability()
        assertTrue(capability.detail, capability.available)
        assertEquals(1, capability.schemaVersion)
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, capability.artifactVersion)
        assertEquals(EXPECTED_GPU_PROFILE_ID, capability.profileId)
        assertEquals(1, capability.kernelBatchSize)
        assertEquals(1, capability.commandQueueWindowSize)
        assertTrue(SourceSeparationGpuRuntimeBootstrap.isLoaded())
        val loadedGpu = requireNotNull(SourceSeparationGpuRuntimeBootstrap.installation())
        assertEquals(EXPECTED_RUNTIME_ARTIFACT_VERSION, loadedGpu.identity.runtimeArtifactVersion)
        assertEquals(EXPECTED_GPU_PROFILE_ID, loadedGpu.identity.profileId)
    }

    @Test
    fun cpuRuntimeLoadsFromDownloadedAbsolutePath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installation = SourceSeparationRuntimeBootstrap.ensureLoaded(context)

        assertEquals("cpu-core", installation.manifest.component)
        assertEquals(installation.manifest.abi, installation.identity.abi)
        assertTrue(installation.libraryFile.isFile)
        assertTrue(installation.jniLibraryFile.isFile)
        assertEquals(
            listOf("libLiteRt.so", "liblitert_jni.so"),
            installation.libraryFiles.keys.toList(),
        )
        assertTrue(installation.manifestFile.isFile)
    }

    @Test
    fun gpuRuntimeReportsTheExpectedInstalledState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SourceSeparationRuntimeBootstrap.ensureLoaded(context)
        SourceSeparationGpuRuntimeBootstrap.ensureLoaded(context)

        val expectedAvailable = InstrumentationRegistry.getArguments()
            .getString(ARG_EXPECTED_GPU_AVAILABLE)
            ?.toBooleanStrictOrNull()
            ?: false
        val observation = SourceSeparationGpuRuntimeBootstrap.capability()

        assertEquals(expectedAvailable, observation.available)
        if (expectedAvailable) {
            assertEquals("gpu-opencl-bounded-fp32-v1", observation.profileId)
            assertEquals(1, observation.kernelBatchSize)
            assertEquals(1, observation.commandQueueWindowSize)
            assertTrue(SourceSeparationGpuRuntimeBootstrap.isLoaded())
        }
    }

    private fun requireStagedFile(context: Context, argument: String): File {
        val filesRoot = context.filesDir.canonicalFile
        val argumentFile = File(argument)
        val stagedFile = if (argumentFile.isAbsolute) {
            argumentFile.canonicalFile
        } else {
            File(filesRoot, argument).canonicalFile
        }
        val privatePrefix = filesRoot.path.trimEnd(File.separatorChar) + File.separator
        require(stagedFile.path.startsWith(privatePrefix)) {
            "Staged runtime bundles must be inside the target app files directory."
        }
        require(stagedFile.isFile && stagedFile.length() > 0L) {
            "Staged runtime bundle is missing or empty: ${stagedFile.path}"
        }
        return stagedFile
    }

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }
        return abis.firstOrNull().orEmpty().ifBlank {
            error("Android did not report an ABI for the source-separation process.")
        }
    }

    private class StagedRuntimeDeliveryProvider(
        private val filesByArtifactId: Map<String, File>,
    ) : RuntimeDeliveryProvider {
        val acquiredArtifactIds = mutableListOf<String>()

        override val providerId: String = "github"
        override val capabilities = SourceSeparationDeliveryCapabilities(
            operations = setOf(SourceSeparationDeliveryOperation.Acquire),
            supportsPlatformManagedPayloads = false,
        )

        override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
            reference.providerId == providerId && reference.artifactId in filesByArtifactId

        override fun acquire(
            reference: SourceSeparationDeliveryReference,
        ): SourceSeparationDeliveryPayload {
            require(supports(reference)) {
                "No staged runtime bundle exists for ${reference.artifactId}."
            }
            val file = requireNotNull(filesByArtifactId[reference.artifactId])
            acquiredArtifactIds += reference.artifactId
            return object : SourceSeparationDeliveryPayload {
                override val reference = reference
                override val byteSize: Long = file.length()

                override fun openStream(): InputStream = file.inputStream()

                override fun close() = Unit
            }
        }
    }

    private companion object {
        const val ARG_EXPECTED_GPU_AVAILABLE = "expectedGpuAvailable"
        const val ARG_STAGED_CPU_RUNTIME_ZIP = "stagedCpuRuntimeZip"
        const val ARG_STAGED_GPU_RUNTIME_ZIP = "stagedGpuRuntimeZip"
        const val EXPECTED_RUNTIME_ARTIFACT_VERSION = "2.2.0-bss.2"
        const val EXPECTED_GPU_PROFILE_ID = "gpu-opencl-bounded-fp32-v1"
    }
}
