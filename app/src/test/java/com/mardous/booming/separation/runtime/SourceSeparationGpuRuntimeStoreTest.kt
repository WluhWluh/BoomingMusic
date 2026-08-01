package com.mardous.booming.separation.runtime

import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryCapabilities
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryOperation
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryPayload
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationGpuRuntimeStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `install verifies dependency and publishes both GPU libraries`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)

        val installed = fixture.store().install(fixture.entry.componentId)

        assertEquals(SourceSeparationGpuRuntimeState.Installed, installed.state)
        val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(
            temporary.root,
            fixture.entry.abi,
        )
        assertTrue(File(current, "libLiteRtClGlAccelerator.so").isFile)
        assertTrue(File(current, "libBssOcl.so").isFile)
        assertTrue(File(current, "manifest.json").isFile)
        assertTrue(File(current, "install.json").isFile)
        assertEquals(installed, fixture.store().inventory(fixture.entry.componentId))
    }

    @Test
    fun `mismatched CPU dependency is rejected before download`() {
        val fixture = GpuRuntimeFixture.create(temporary.root, requiredCpuHash = "f".repeat(64))
        val provider = fixture.provider()

        val error = runCatching { fixture.store(provider).install(fixture.entry.componentId) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, provider.acquireCount)
        assertFalse(
            SourceSeparationRuntimeLayout.gpuCurrentDirectory(temporary.root, fixture.entry.abi)
                .exists(),
        )
    }

    @Test
    fun `low disk preflight rejects before acquiring GPU payload`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val provider = fixture.provider()
        val store = SourceSeparationGpuRuntimeStore(
            root = temporary.root,
            catalog = fixture.catalog,
            provider = provider,
            androidApi = 35,
            usableSpace = { 0L },
        )

        val error = runCatching { store.install(fixture.entry.componentId) }.exceptionOrNull()

        assertTrue(error is SourceSeparationRuntimeInsufficientStorageException)
        assertEquals(0, provider.acquireCount)
    }

    @Test
    fun `ZIP hash mismatch leaves no installed GPU runtime`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val tampered = fixture.zipBytes.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        }
        val store = fixture.store(provider = fixture.provider(tampered))

        val error = runCatching { store.install(fixture.entry.componentId) }.exceptionOrNull()

        assertTrue(error is SourceSeparationRuntimeInstallException)
        assertFalse(
            SourceSeparationRuntimeLayout.gpuCurrentDirectory(temporary.root, fixture.entry.abi)
                .exists(),
        )
        assertTrue(
            temporary.root.resolve("gpu/.staging").listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun `inventory removes unknown GPU staging directories`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val orphan = temporary.root.resolve("gpu/.staging/orphan-component").apply { mkdirs() }
        orphan.resolve("payload.zip.part").writeBytes(byteArrayOf(1, 2, 3))

        fixture.store().inventory()

        assertFalse(orphan.exists())
    }

    @Test
    fun `mismatched CPU component identity is rejected before download`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val provider = fixture.provider()
        val recordFile = SourceSeparationRuntimeLayout.cpuCurrentDirectory(
            temporary.root,
            fixture.entry.abi,
        ).resolve("install.json")
        recordFile.writeText(
            Json.encodeToString(
                SourceSeparationRuntimeInstallRecord(
                    schemaVersion = 1,
                    componentId = "different-cpu-component",
                    componentType = SourceSeparationRuntimeLayout.CPU_COMPONENT,
                    producerReleaseTag = "test-cpu-release-tag",
                    producerReleaseVersion = "test-cpu-release",
                    runtimeArtifactVersion = "test-cpu-runtime",
                    abi = fixture.entry.abi,
                    innerManifestSha256 = "a".repeat(64),
                    librarySha256 = fixture.cpuLibrary.sha256(),
                    installedAtEpochMs = 1L,
                    lastValidatedAtEpochMs = 1L,
                ),
            ),
        )

        val error = runCatching { fixture.store(provider).install(fixture.entry.componentId) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, provider.acquireCount)
    }

    @Test
    fun `lease turns replacement into pending activation`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val store = fixture.store()
        store.install(fixture.entry.componentId)
        val replacement = GpuRuntimeFixture.create(
            temporary.root,
            runtimeVersion = "test-runtime-v2",
            releaseVersion = "test-release-v2",
        )
        val lease = requireNotNull(
            SourceSeparationRuntimeProcessLease.tryAcquire(temporary.root, fixture.entry.abi),
        )

        val pending = replacement.store().install(replacement.entry.componentId)

        assertEquals(SourceSeparationGpuRuntimeState.PendingActivation, pending.state)
        assertTrue(
            SourceSeparationRuntimeLayout.gpuCurrentDirectory(temporary.root, fixture.entry.abi)
                .isDirectory,
        )
        lease.close()
        val activated = replacement.store().activatePending(replacement.entry.componentId)
        assertEquals(SourceSeparationGpuRuntimeState.Installed, activated.state)
    }

    @Test
    fun `corrupt installed GPU library is reported invalid`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val store = fixture.store()
        store.install(fixture.entry.componentId)
        val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(
            temporary.root,
            fixture.entry.abi,
        )
        File(current, "libBssOcl.so").appendBytes(byteArrayOf(1))

        val inventory = store.inventory(fixture.entry.componentId)

        assertEquals(SourceSeparationGpuRuntimeState.Invalid, inventory.state)
    }

    @Test
    fun `mismatched GPU install identity is reported invalid`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val store = fixture.store()
        store.install(fixture.entry.componentId)
        val recordFile = SourceSeparationRuntimeLayout.gpuCurrentDirectory(
            temporary.root,
            fixture.entry.abi,
        ).resolve("install.json")
        recordFile.writeText(recordFile.readText().replace(fixture.entry.componentId, "different-gpu-component"))

        val inventory = store.inventory(fixture.entry.componentId)

        assertEquals(SourceSeparationGpuRuntimeState.Invalid, inventory.state)
    }

    @Test
    fun `lease turns removal into pending deletion`() {
        val fixture = GpuRuntimeFixture.create(temporary.root)
        val store = fixture.store()
        store.install(fixture.entry.componentId)
        val lease = requireNotNull(
            SourceSeparationRuntimeProcessLease.tryAcquire(temporary.root, fixture.entry.abi),
        )

        val pending = store.remove(fixture.entry.componentId)

        assertEquals(SourceSeparationGpuRuntimeState.PendingDeletion, pending.state)
        lease.close()
        assertEquals(
            SourceSeparationGpuRuntimeState.Missing,
            store.remove(fixture.entry.componentId).state,
        )
    }

    private class GpuRuntimeFixture private constructor(
        private val root: File,
        val entry: SourceSeparationGpuRuntimeCatalogEntry,
        val catalog: SourceSeparationGpuRuntimeCatalog,
        val zipBytes: ByteArray,
        val cpuLibrary: ByteArray,
        private val cpuManifest: ByteArray,
    ) {
        fun provider(bytes: ByteArray = zipBytes) = TestGpuRuntimeProvider(bytes)

        fun store(provider: TestGpuRuntimeProvider = provider()) = SourceSeparationGpuRuntimeStore(
            root = root,
            catalog = catalog,
            provider = provider,
            androidApi = 35,
            usableSpace = { Long.MAX_VALUE },
        )

        init {
            val cpuDirectory = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, entry.abi)
                .apply { mkdirs() }
            File(cpuDirectory, SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME).writeBytes(cpuLibrary)
            File(cpuDirectory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME).writeBytes(cpuManifest)
            File(cpuDirectory, "install.json").writeText(
                Json.encodeToString(
                    SourceSeparationRuntimeInstallRecord(
                        schemaVersion = 1,
                        componentId = entry.requiredCpuComponentId,
                        componentType = SourceSeparationRuntimeLayout.CPU_COMPONENT,
                        producerReleaseTag = "test-cpu-release-tag",
                        producerReleaseVersion = "test-cpu-release",
                        runtimeArtifactVersion = "test-cpu-runtime",
                        abi = entry.abi,
                        innerManifestSha256 = cpuManifest.sha256(),
                        librarySha256 = cpuLibrary.sha256(),
                        installedAtEpochMs = 1L,
                        lastValidatedAtEpochMs = 1L,
                    ),
                ),
            )
        }

        companion object {
            fun create(
                root: File,
                requiredCpuHash: String? = null,
                runtimeVersion: String = "test-runtime",
                releaseVersion: String = "test-release",
            ): GpuRuntimeFixture {
                val cpuLibrary = byteArrayOf(1, 2, 3, 4)
                val cpuHash = cpuLibrary.sha256()
                val requiredHash = requiredCpuHash ?: cpuHash
                val accelerator = byteArrayOf(8, 7, 6)
                val shim = byteArrayOf(5, 4, 3)
                val acceleratorHash = accelerator.sha256()
                val shimHash = shim.sha256()
                val componentId = "test-bounded-gpu-arm64-v8a"
                val cpuComponentId = "test-cpu-core-arm64-v8a"
                val manifest = """
                    {
                      "schemaVersion": 1,
                      "contractSchemaVersion": "bss-litert-downloadable-runtime-v2",
                      "component": "bounded-gpu",
                      "abi": "arm64-v8a",
                      "androidMinApi": 26,
                      "baseLiteRtVersion": "2.1.5",
                      "capabilities": ["gpu-opencl-bounded-fp32"],
                      "runtimeArtifactVersion": "$runtimeVersion",
                      "releaseVersion": "$releaseVersion",
                      "files": [
                        {
                          "path": "libLiteRtClGlAccelerator.so",
                          "byteSize": 3,
                          "sha256": "$acceleratorHash",
                          "elf": {
                            "class": "ELF64",
                            "machine": "EM_AARCH64",
                            "needed": ["libc.so"],
                            "soname": "libLiteRtClGlAccelerator.so"
                          },
                          "runtimeLoads": ["libBssOcl.so"]
                        },
                        {
                          "path": "libBssOcl.so",
                          "byteSize": 3,
                          "sha256": "$shimHash",
                          "elf": {
                            "class": "ELF64",
                            "machine": "EM_AARCH64",
                            "needed": ["libc.so"],
                            "soname": "libBssOcl.so"
                          }
                        }
                      ],
                      "profile": {
                        "schemaVersion": 1,
                        "profileId": "gpu-opencl-bounded-fp32-v1",
                        "precision": "FP32",
                        "backend": "OpenCL",
                        "kernelBatchSize": 1,
                        "commandQueueWindowSize": 1
                      },
                      "requiredCore": {
                        "abi": "arm64-v8a",
                        "librarySha256": "$requiredHash"
                      },
                      "sourceAar": {
                        "fileName": "test-api.aar",
                        "sha256": "${"a".repeat(64)}"
                      }
                    }
                """.trimIndent().encodeToByteArray()
                val zip = zipBytes(manifest, accelerator, shim)
                val entry = SourceSeparationGpuRuntimeCatalogEntry(
                    componentId = componentId,
                    componentType = "bounded-gpu",
                    producerReleaseTag = "test-release-tag",
                    producerReleaseVersion = releaseVersion,
                    runtimeArtifactVersion = runtimeVersion,
                    baseLiteRtVersion = "2.1.5",
                    abi = "arm64-v8a",
                    androidMinApi = 26,
                    maturity = "recommended",
                    capabilityId = "gpu-opencl-bounded-fp32",
                    dependencies = listOf(cpuComponentId),
                    requiredCpuComponentId = cpuComponentId,
                    requiredCpuLibrarySha256 = requiredHash,
                    capability = SourceSeparationGpuRuntimeCapability(
                        schemaVersion = 1,
                        profileId = "gpu-opencl-bounded-fp32-v1",
                        precision = "FP32",
                        backend = "OpenCL",
                        kernelBatchSize = 1,
                        commandQueueWindowSize = 1,
                    ),
                    delivery = SourceSeparationGpuRuntimeDelivery(
                        providerId = "github",
                        artifactId = componentId,
                        locator = "https://github.com/test/repo/releases/download/test/test.zip",
                        expectedByteSize = zip.size.toLong(),
                        expectedSha256 = zip.sha256(),
                    ),
                    innerManifestSha256 = manifest.sha256(),
                    files = listOf(
                        SourceSeparationGpuRuntimeLibrary(
                            path = "libLiteRtClGlAccelerator.so",
                            byteSize = accelerator.size.toLong(),
                            sha256 = acceleratorHash,
                            elfClass = "ELF64",
                            machine = "EM_AARCH64",
                            soname = "libLiteRtClGlAccelerator.so",
                            runtimeLoads = listOf("libBssOcl.so"),
                        ),
                        SourceSeparationGpuRuntimeLibrary(
                            path = "libBssOcl.so",
                            byteSize = shim.size.toLong(),
                            sha256 = shimHash,
                            elfClass = "ELF64",
                            machine = "EM_AARCH64",
                            soname = "libBssOcl.so",
                        ),
                    ),
                    licenseAssets = listOf("LICENSE-LiteRT.txt"),
                )
                val cpuManifest = """
                    {
                      "schemaVersion": 1,
                      "contractSchemaVersion": "bss-litert-downloadable-runtime-v2",
                      "component": "cpu-core",
                      "abi": "arm64-v8a",
                      "androidMinApi": 26,
                      "baseLiteRtVersion": "2.1.5",
                      "capabilities": ["cpu"],
                      "runtimeArtifactVersion": "test-cpu-runtime",
                      "releaseVersion": "test-cpu-release",
                      "files": [{
                        "path": "libLiteRt.so",
                        "byteSize": 4,
                        "sha256": "$cpuHash",
                        "elf": {
                          "class": "ELF64",
                          "machine": "EM_AARCH64",
                          "needed": ["libc.so"],
                          "soname": "libLiteRt.so"
                        }
                      }],
                      "sourceAar": {
                        "fileName": "test-api.aar",
                        "sha256": "${"a".repeat(64)}"
                      }
                    }
                """.trimIndent().encodeToByteArray()
                val catalog = SourceSeparationGpuRuntimeCatalog(
                    schemaVersion = 1,
                    catalogId = SourceSeparationGpuRuntimeCatalogMetadata.CATALOG_ID,
                    producerContractSchemaVersion = SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION,
                    producerContractSha256 = "b".repeat(64),
                    entries = listOf(entry),
                )
                SourceSeparationGpuRuntimeCatalogLoader.validate(catalog)
                return GpuRuntimeFixture(root, entry, catalog, zip, cpuLibrary, cpuManifest)
            }

            private fun zipBytes(
                manifest: ByteArray,
                accelerator: ByteArray,
                shim: ByteArray,
            ): ByteArray = ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("libLiteRtClGlAccelerator.so"))
                    zip.write(accelerator)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("libBssOcl.so"))
                    zip.write(shim)
                    zip.closeEntry()
                }
                buffer.toByteArray()
            }
        }
    }

    private class TestGpuRuntimeProvider(
        private val bytes: ByteArray,
    ) : RuntimeDeliveryProvider {
        var acquireCount: Int = 0

        override val providerId: String = "github"
        override val capabilities = SourceSeparationDeliveryCapabilities(
            operations = setOf(SourceSeparationDeliveryOperation.Acquire),
            supportsPlatformManagedPayloads = false,
        )

        override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
            reference.providerId == providerId

        override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload {
            acquireCount++
            return object : SourceSeparationDeliveryPayload {
                override val reference = reference
                override val byteSize: Long = bytes.size.toLong()
                override fun openStream(): InputStream = ByteArrayInputStream(bytes)
                override fun close() = Unit
            }
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
