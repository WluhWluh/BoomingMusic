package com.mardous.booming.ui.screen.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationAutomaticPruneCoordinatorTest {

    @Test
    fun `worker coordinator is the only automatic prune owner`() {
        val coordinator = source("SourceSeparationForegroundWorkerCoordinator.kt").readText()
        val player = source("PlayerViewModel.kt").readText()
        val cacheManagement = source(
            "SourceSeparationModelAwareCacheManagementViewModel.kt",
        ).readText()

        assertEquals(1, Regex("sourceSeparationRuntime\\.prune\\(").findAll(coordinator).count())
        assertTrue(coordinator.contains("Channel<Unit>(Channel.CONFLATED)"))
        assertTrue(coordinator.contains("fun requestAutomaticPrune()"))
        assertFalse(player.contains("sourceSeparationRuntime.prune("))
        assertFalse(cacheManagement.contains("runtime.prune("))
    }

    private fun source(name: String): File = File(sourceRoot, name).also { file ->
        require(file.isFile) { "Missing production source: $name" }
    }

    private val sourceRoot: File
        get() {
            val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
            val mainRoot = listOf(
                File(workingDirectory, "src/main/java"),
                File(workingDirectory, "app/src/main/java"),
            ).firstOrNull(File::isDirectory)
                ?: error("Cannot locate the app production source root from $workingDirectory")
            return File(mainRoot, "com/mardous/booming/ui/screen/player")
        }
}
