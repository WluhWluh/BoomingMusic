package com.mardous.booming.separation

import com.mardous.booming.separation.cache.v2.SelectingSourceSeparationCacheRootProvider
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationStorageOwnershipTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `generated cache root is independent from durable runtime and model roots`() {
        val files = temporary.root.resolve("files")
        val noBackup = temporary.root.resolve("no-backup")
        val externalCache = temporary.root.resolve("external-cache")
        val cacheRoot = SelectingSourceSeparationCacheRootProvider(
            externalCacheDirectory = { externalCache },
            internalCacheDirectory = { temporary.root.resolve("cache") },
        ).resolveRoot().directory
        val runtimeRoot = File(noBackup, "source-separation/runtimes")
        val modelRoot = File(files, SourceSeparationPresetRepository.MODEL_ROOT_DIRECTORY)

        assertTrue(cacheRoot.toPath().startsWith(externalCache.toPath()))
        assertFalse(cacheRoot.toPath().startsWith(runtimeRoot.toPath()))
        assertFalse(cacheRoot.toPath().startsWith(modelRoot.toPath()))
        assertFalse(runtimeRoot.toPath().startsWith(cacheRoot.toPath()))
        assertFalse(modelRoot.toPath().startsWith(cacheRoot.toPath()))
        assertTrue(runtimeRoot.toPath().toString().endsWith("source-separation${File.separator}runtimes"))
        assertTrue(modelRoot.toPath().toString().endsWith("litert-models-v1"))
    }
}
