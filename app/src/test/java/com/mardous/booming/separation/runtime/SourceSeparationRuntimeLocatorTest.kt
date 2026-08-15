package com.mardous.booming.separation.runtime

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.rules.TemporaryFolder

class SourceSeparationRuntimeLocatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `missing active runtime is typed`() {
        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            locator().resolve()
        }

        assertEquals(SourceSeparationRuntimeFailureReason.MissingRuntime, error.reason)
    }

    @Test
    fun `wrong ABI is rejected before reading the native library`() {
        val directory = writeRuntime(
            abi = "x86_64",
            directoryAbi = "arm64-v8a",
        )

        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            SourceSeparationRuntimeLocator(
                root = temporary.root,
                processAbi = "arm64-v8a",
                androidApi = 35,
            ).resolve()
        }

        assertTrue(directory.isDirectory)
        assertEquals(SourceSeparationRuntimeFailureReason.WrongAbi, error.reason)
    }

    @Test
    fun `unsupported API is rejected from the manifest`() {
        writeRuntime(androidMinApi = 35)

        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            SourceSeparationRuntimeLocator(
                root = temporary.root,
                processAbi = "arm64-v8a",
                androidApi = 34,
            ).resolve()
        }

        assertEquals(SourceSeparationRuntimeFailureReason.UnsupportedApi, error.reason)
    }

    @Test
    fun `corrupt native payload is rejected by hash`() {
        val directory = writeRuntime()
        File(directory, SourceSeparationRuntimeLayout.CORE_LIBRARY_FILE_NAME)
            .appendBytes(byteArrayOf(9))

        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            locator().resolve()
        }

        assertEquals(SourceSeparationRuntimeFailureReason.CorruptPayload, error.reason)
    }

    @Test
    fun `corrupt JNI payload is rejected by hash`() {
        val directory = writeRuntime()
        File(directory, SourceSeparationRuntimeLayout.JNI_LIBRARY_FILE_NAME)
            .appendBytes(byteArrayOf(9))

        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            locator().resolve()
        }

        assertEquals(SourceSeparationRuntimeFailureReason.CorruptPayload, error.reason)
    }

    @Test
    fun `missing JNI payload is rejected before loading`() {
        val directory = writeRuntime()
        assertTrue(File(directory, SourceSeparationRuntimeLayout.JNI_LIBRARY_FILE_NAME).delete())

        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            locator().resolve()
        }

        assertEquals(SourceSeparationRuntimeFailureReason.MissingRuntime, error.reason)
    }

    @Test
    fun `trusted loading skips payload hash but keeps manifest and size checks`() {
        val directory = writeRuntime()
        val library = File(directory, SourceSeparationRuntimeLayout.CORE_LIBRARY_FILE_NAME)
        library.writeBytes(byteArrayOf(4, 3, 2, 1))

        val verifiedError = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            locator().resolve()
        }
        val trusted = locator().resolve(verifyPayloadHash = false)

        assertEquals(SourceSeparationRuntimeFailureReason.CorruptPayload, verifiedError.reason)
        assertEquals(library.canonicalFile, trusted.libraryFile)
        assertTrue(trusted.jniLibraryFile.isFile)
    }

    @Test
    fun `valid runtime resolves immutable identity`() {
        val directory = writeRuntime()

        val installation = locator().resolve()

        assertEquals(directory.canonicalFile, installation.directory)
        assertEquals(
            SourceSeparationRuntimeLayout.CORE_LIBRARY_FILE_NAME,
            installation.libraryFile.name,
        )
        assertEquals(
            SourceSeparationRuntimeLayout.JNI_LIBRARY_FILE_NAME,
            installation.jniLibraryFile.name,
        )
        assertEquals("2.2.0-bss.2", installation.identity.runtimeArtifactVersion)
        assertEquals("2.2.0-bss.2-exp.1", installation.identity.releaseVersion)
        assertEquals("arm64-v8a", installation.identity.abi)
    }

    @Test
    fun `malformed manifest is typed as an invalid contract`() {
        val directory = SourceSeparationRuntimeLayout.cpuCurrentDirectory(
            temporary.root,
            "arm64-v8a",
        ).apply { mkdirs() }
        File(directory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME).writeText("{}")

        val error = assertThrows(SourceSeparationRuntimeLoadException::class.java) {
            locator().resolve()
        }

        assertEquals(SourceSeparationRuntimeFailureReason.InvalidContract, error.reason)
    }

    private fun locator() = SourceSeparationRuntimeLocator(
        root = temporary.root,
        processAbi = "arm64-v8a",
        androidApi = 35,
    )

    private fun writeRuntime(
        abi: String = "arm64-v8a",
        directoryAbi: String = abi,
        androidMinApi: Int = 26,
    ): File {
        val directory = SourceSeparationRuntimeLayout.cpuCurrentDirectory(
            temporary.root,
            directoryAbi,
        ).apply { mkdirs() }
        val coreBytes = byteArrayOf(1, 2, 3, 4)
        val jniBytes = byteArrayOf(5, 6, 7, 8)
        val coreSha256 = coreBytes.sha256()
        val jniSha256 = jniBytes.sha256()
        File(directory, SourceSeparationRuntimeLayout.CORE_LIBRARY_FILE_NAME).writeBytes(coreBytes)
        File(directory, SourceSeparationRuntimeLayout.JNI_LIBRARY_FILE_NAME).writeBytes(jniBytes)
        File(directory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME).writeText(
            """
            {
              "schemaVersion": 2,
              "contractSchemaVersion": "bss-litert-downloadable-runtime-v3",
              "component": "cpu-core",
              "abi": "$abi",
              "androidMinApi": $androidMinApi,
              "baseLiteRtVersion": "2.2.0",
              "capabilities": ["cpu"],
              "runtimeArtifactVersion": "2.2.0-bss.2",
              "releaseVersion": "2.2.0-bss.2-exp.1",
              "loadOrder": ["libLiteRt.so", "liblitert_jni.so"],
              "files": [
                {
                  "role": "runtime",
                  "path": "libLiteRt.so",
                  "byteSize": 4,
                  "sha256": "$coreSha256",
                  "elf": {
                    "class": "ELF64",
                    "machine": "EM_AARCH64",
                    "needed": ["libc.so"],
                    "soname": "libLiteRt.so",
                    "loadAlignment": 16384
                  }
                },
                {
                  "role": "jni",
                  "path": "liblitert_jni.so",
                  "byteSize": 4,
                  "sha256": "$jniSha256",
                  "elf": {
                    "class": "ELF64",
                    "machine": "EM_AARCH64",
                    "needed": ["libdl.so", "libc.so"],
                    "soname": "liblitert_jni.so",
                    "loadAlignment": 16384
                  },
                  "runtimeLoads": ["libLiteRt.so"]
                }
              ],
              "sourceAar": {
                "fileName": "litert-android-2.2.0-bss.2.aar",
                "sha256": "${"a".repeat(64)}"
              }
            }
            """.trimIndent(),
        )
        return directory
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
}
