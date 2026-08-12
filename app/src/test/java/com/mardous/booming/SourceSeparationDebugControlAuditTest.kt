package com.mardous.booming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        assertTrue(provider.contains("callingUid != ownUid"))
        assertTrue(provider.contains("callingUid != Process.SHELL_UID"))
        assertTrue(provider.contains("callingUid != 0"))
        assertTrue(provider.contains("throw SecurityException"))
    }

    @Test
    fun `help advertises exactly the commands implemented by the provider`() {
        val protocol = appFile(
            "src/debug/java/com/mardous/booming/debug/SourceSeparationDebugProtocol.kt",
        ).readText()
        val provider = appFile(
            "src/debug/java/com/mardous/booming/debug/SourceSeparationDebugControlProvider.kt",
        ).readText()
        val commandTable = protocol.substringAfter("val commands = linkedMapOf(")
            .substringBefore("\n    )")
        val advertised = COMMAND_TABLE_ENTRY.findAll(commandTable)
            .map { match -> match.groupValues[1] }
            .toSet()
        val dispatch = provider.substringAfter("private fun execute(")
            .substringBefore("\n    private fun fullState")
        val implemented = COMMAND_DISPATCH_BRANCH.findAll(dispatch)
            .flatMap { branch ->
                COMMAND_LITERAL.findAll(branch.groupValues[1])
                    .map { match -> match.groupValues[1] }
            }
            .toSet()

        assertTrue("The debug command table is empty.", advertised.isNotEmpty())
        assertEquals(advertised, implemented)
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

    private companion object {
        val COMMAND_TABLE_ENTRY = Regex("(?m)^\\s*\"([a-z][a-z0-9_.]*)\"\\s+to\\s+")
        val COMMAND_DISPATCH_BRANCH = Regex(
            "(?m)^ {8}((?:\"[a-z][a-z0-9_.]*\"\\s*,?\\s*)+)->",
        )
        val COMMAND_LITERAL = Regex("\"([a-z][a-z0-9_.]*)\"")
    }
}
