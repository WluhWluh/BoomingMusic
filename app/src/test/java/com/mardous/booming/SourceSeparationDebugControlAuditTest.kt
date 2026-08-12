package com.mardous.booming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationDebugControlAuditTest {
    @Test
    fun `ADB control provider is confined to the debug source set`() {
        val debugManifest = appFile("src/debug/AndroidManifest.xml").readText()
        val mainManifest = appFile("src/main/AndroidManifest.xml").readText()

        assertTrue(debugManifest.contains(".debug.SourceSeparationDebugControlProvider"))
        assertTrue(debugManifest.contains("${'$'}{applicationId}.debug-control"))
        assertTrue(debugManifest.contains("android:exported=\"true\""))
        assertFalse(mainManifest.contains("SourceSeparationDebugControlProvider"))
        assertFalse(mainManifest.contains(".debug-control"))
    }

    @Test
    fun `exported provider restricts calls to trusted debugging UIDs`() {
        val provider = appFile(
            "src/debug/java/com/mardous/booming/debug/SourceSeparationDebugControlProvider.kt",
        ).readText()

        assertTrue(provider.contains("Binder.getCallingUid()"))
        assertTrue(provider.contains("callingUid == ownUid"))
        assertTrue(provider.contains("callingUid == Process.SHELL_UID"))
        assertTrue(provider.contains("callingUid == 0"))
    }

    private fun appFile(relativePath: String): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val appRoot = if (File(workingDirectory, "src/main").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
        return File(appRoot, relativePath).also { file ->
            require(file.isFile) { "Missing app file: ${file.absolutePath}" }
        }
    }
}
