package com.mardous.booming.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoomingDatabasePolicyTest {
    @Test
    fun `unreleased app data has no historical Room migration graph`() {
        val database = mainSource("com/mardous/booming/core/BoomingDatabase.kt").readText()
        val module = mainSource("com/mardous/booming/MainModule.kt").readText()

        assertFalse(database.contains("androidx.room.migration.Migration"))
        assertFalse(database.contains("MIGRATION_"))
        assertFalse(module.contains(".addMigrations("))
        assertTrue(
            module.contains(".fallbackToDestructiveMigration(dropAllTables = true)")
        )
    }

    private fun mainSource(relativePath: String): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val root = listOf(
            File(workingDirectory, "src/main/java"),
            File(workingDirectory, "app/src/main/java"),
        ).firstOrNull(File::isDirectory)
            ?: error("Cannot locate the app production source root from $workingDirectory")
        return File(root, relativePath).also { source ->
            require(source.isFile) { "Missing production source: $relativePath" }
        }
    }
}
