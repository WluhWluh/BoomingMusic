package com.mardous.booming.separation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSeparationDownloadedRuntimeDeviceTest {
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

    private companion object {
        const val ARG_EXPECTED_GPU_AVAILABLE = "expectedGpuAvailable"
    }
}
