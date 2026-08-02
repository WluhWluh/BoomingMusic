package com.mardous.booming.separation.runtime

import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.ResumableRuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryCapabilities
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryOperation
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryPayload
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import com.mardous.booming.separation.delivery.SourceSeparationResumableDeliveryPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationRuntimeStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `install verifies and atomically publishes CPU runtime`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val store = fixture.store()

        val installed = store.install(fixture.entry.componentId)

        assertEquals(SourceSeparationRuntimeState.Installed, installed.state)
        assertEquals(
            fixture.entry.innerLibrary.byteSize + fixture.manifestBytes.size,
            installed.installedBytes,
        )
        val current = SourceSeparationRuntimeLayout.cpuCurrentDirectory(
            temporary.root,
            fixture.entry.abi,
        )
        assertTrue(File(current, "libLiteRt.so").isFile)
        assertTrue(File(current, "manifest.json").isFile)
        assertTrue(File(current, "install.json").isFile)
        assertTrue(current.listFiles().orEmpty().all { !it.canWrite() })
        assertFalse(File(current, "payload.zip").exists())
        assertEquals(installed, store.inventory(fixture.entry.componentId))
    }

    @Test
    fun `repeated install is idempotent`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val store = fixture.store()

        val first = store.install(fixture.entry.componentId)
        val second = store.install(fixture.entry.componentId)

        assertEquals(first.state, second.state)
        assertEquals(first.installedBytes, second.installedBytes)
        assertEquals(3, temporary.root.resolve("cpu/${fixture.entry.abi}/current").listFiles()!!.size)
    }

    @Test
    fun `ZIP hash mismatch leaves no installed runtime`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val tampered = fixture.zipBytes.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        }
        val store = fixture.store(tampered)

        val error = runCatching { store.install(fixture.entry.componentId) }.exceptionOrNull()

        assertTrue(error is SourceSeparationRuntimeInstallException)
        assertFalse(
            SourceSeparationRuntimeLayout.cpuCurrentDirectory(temporary.root, fixture.entry.abi)
                .exists(),
        )
        assertTrue(temporary.root.resolve(".staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `ZIP traversal path is rejected`() {
        val fixture = RuntimeFixture.create(temporary.root, zipEntryName = "../libLiteRt.so")
        val store = fixture.store()

        val error = runCatching { store.install(fixture.entry.componentId) }.exceptionOrNull()

        assertTrue(error is SourceSeparationRuntimeInstallException)
        assertFalse(
            SourceSeparationRuntimeLayout.cpuCurrentDirectory(temporary.root, fixture.entry.abi)
                .exists(),
        )
    }

    @Test
    fun `low disk preflight rejects before acquiring payload`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val provider = fixture.provider()
        val store = SourceSeparationRuntimeStore(
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
    fun `lease turns removal into pending deletion`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val store = fixture.store()
        store.install(fixture.entry.componentId)
        val lease = requireNotNull(
            SourceSeparationRuntimeProcessLease.tryAcquire(temporary.root, fixture.entry.abi),
        )

        val pending = store.remove(fixture.entry.componentId)

        assertEquals(SourceSeparationRuntimeState.PendingDeletion, pending.state)
        assertTrue(
            SourceSeparationRuntimeLayout.cpuCurrentDirectory(temporary.root, fixture.entry.abi)
                .exists(),
        )
        lease.close()

        val removed = store.remove(fixture.entry.componentId)
        assertEquals(SourceSeparationRuntimeState.Missing, removed.state)
    }

    @Test
    fun `resumable provider continues a known partial ZIP`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val partialBytes = fixture.zipBytes.size / 2
        val partialDirectory = temporary.root.resolve(
            ".staging/${fixture.entry.componentId}",
        ).apply { mkdirs() }
        partialDirectory.resolve("payload.zip.part").writeBytes(
            fixture.zipBytes.copyOf(partialBytes),
        )
        val provider = ResumableTestRuntimeProvider(fixture.zipBytes)
        val store = SourceSeparationRuntimeStore(
            root = temporary.root,
            catalog = fixture.catalog,
            provider = provider,
            androidApi = 35,
            usableSpace = { Long.MAX_VALUE },
        )

        val installed = store.install(fixture.entry.componentId)

        assertEquals(SourceSeparationRuntimeState.Installed, installed.state)
        assertEquals(partialBytes.toLong(), provider.resumedFrom)
    }

    @Test
    fun `failed replacement preserves the installed runtime and retry publishes replacement`() {
        val initial = RuntimeFixture.create(temporary.root)
        val initialStore = initial.store()
        initialStore.install(initial.entry.componentId)

        val replacement = RuntimeFixture.create(
            root = temporary.root,
            library = byteArrayOf(1, 2, 3),
            runtimeVersion = "test-runtime-v2",
            releaseVersion = "test-release-v2",
        )
        val failingProvider = FailingRuntimeProvider(replacement.zipBytes, failAfterBytes = 32)
        val replacementStore = replacement.store(failingProvider)

        val error = runCatching {
            replacementStore.install(replacement.entry.componentId)
        }.exceptionOrNull()

        assertTrue(error is SourceSeparationRuntimeInstallException)
        assertEquals(
            SourceSeparationRuntimeState.Installed,
            initialStore.inventory(initial.entry.componentId).state,
        )
        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            File(
                SourceSeparationRuntimeLayout.cpuCurrentDirectory(
                    temporary.root,
                    initial.entry.abi,
                ),
                SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME,
            ).readBytes(),
        )

        val installed = replacement.store().install(replacement.entry.componentId)

        assertEquals(SourceSeparationRuntimeState.Installed, installed.state)
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            File(
                SourceSeparationRuntimeLayout.cpuCurrentDirectory(
                    temporary.root,
                    replacement.entry.abi,
                ),
                SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME,
            ).readBytes(),
        )
        assertTrue(temporary.root.resolve(".staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `concurrent stores serialize installation through the shared file lock`() {
        val fixture = RuntimeFixture.create(temporary.root)
        val firstProvider = BlockingRuntimeProvider(fixture.zipBytes)
        val firstStore = fixture.store(firstProvider)
        val secondStore = fixture.store()
        var firstResult: SourceSeparationRuntimeInventoryItem? = null
        var secondResult: SourceSeparationRuntimeInventoryItem? = null

        val firstThread = thread(start = true) {
            firstResult = firstStore.install(fixture.entry.componentId)
        }
        assertTrue(firstProvider.acquired.await(5, TimeUnit.SECONDS))
        val secondThread = thread(start = true) {
            secondResult = secondStore.install(fixture.entry.componentId)
        }

        Thread.sleep(50)
        assertTrue(secondThread.isAlive)
        firstProvider.release.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertTrue(!firstThread.isAlive)
        assertTrue(!secondThread.isAlive)
        assertEquals(SourceSeparationRuntimeState.Installed, firstResult?.state)
        assertEquals(SourceSeparationRuntimeState.Installed, secondResult?.state)
        assertEquals(
            SourceSeparationRuntimeState.Installed,
            fixture.store().inventory(fixture.entry.componentId).state,
        )
    }

    private class RuntimeFixture private constructor(
        private val root: File,
        val entry: SourceSeparationRuntimeCatalogEntry,
        val catalog: SourceSeparationRuntimeCatalog,
        val zipBytes: ByteArray,
        val manifestBytes: ByteArray,
    ) {
        fun provider(bytes: ByteArray = zipBytes) = TestRuntimeProvider(bytes)

        fun store(bytes: ByteArray = zipBytes) = SourceSeparationRuntimeStore(
            root = root,
            catalog = catalog,
            provider = provider(bytes),
            androidApi = 35,
            usableSpace = { Long.MAX_VALUE },
        )

        fun store(provider: RuntimeDeliveryProvider) = SourceSeparationRuntimeStore(
            root = root,
            catalog = catalog,
            provider = provider,
            androidApi = 35,
            usableSpace = { Long.MAX_VALUE },
        )

        companion object {
            fun create(
                root: File,
                zipEntryName: String = "libLiteRt.so",
                library: ByteArray = byteArrayOf(7, 8, 9),
                runtimeVersion: String = "test-runtime",
                releaseVersion: String = "test-release",
            ): RuntimeFixture {
                val libraryHash = library.sha256()
                val manifest = """
                    {
                      "schemaVersion": 1,
                      "contractSchemaVersion": "bss-litert-downloadable-runtime-v2",
                      "component": "cpu-core",
                      "abi": "x86_64",
                      "androidMinApi": 26,
                      "baseLiteRtVersion": "2.1.5",
                      "capabilities": ["cpu"],
                      "runtimeArtifactVersion": "$runtimeVersion",
                      "releaseVersion": "$releaseVersion",
                      "files": [{
                        "path": "libLiteRt.so",
                        "byteSize": 3,
                        "sha256": "$libraryHash",
                        "elf": {
                          "class": "ELF64",
                          "machine": "EM_X86_64",
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
                val zip = zipBytes(
                    manifest = manifest,
                    library = library,
                    libraryEntryName = zipEntryName,
                )
                val entry = SourceSeparationRuntimeCatalogEntry(
                    componentId = "test-cpu-core-x86_64",
                    componentType = "cpu-core",
                    producerReleaseTag = "test-release-tag",
                    producerReleaseVersion = releaseVersion,
                    runtimeArtifactVersion = runtimeVersion,
                    baseLiteRtVersion = "2.1.5",
                    abi = "x86_64",
                    androidMinApi = 26,
                    maturity = "recommended",
                    capabilityId = "cpu",
                    dependencies = emptyList(),
                    delivery = SourceSeparationRuntimeDelivery(
                        providerId = "github",
                        artifactId = "test-cpu-core-x86_64",
                        locator = "https://github.com/test/repo/releases/download/test/test.zip",
                        expectedByteSize = zip.size.toLong(),
                        expectedSha256 = zip.sha256(),
                    ),
                    innerManifestSha256 = manifest.sha256(),
                    innerLibrary = SourceSeparationRuntimeLibrary(
                        path = "libLiteRt.so",
                        byteSize = library.size.toLong(),
                        sha256 = libraryHash,
                        elfClass = "ELF64",
                        machine = "EM_X86_64",
                        soname = "libLiteRt.so",
                    ),
                    licenseAssets = listOf("LICENSE-LiteRT.txt"),
                )
                val catalog = SourceSeparationRuntimeCatalog(
                    schemaVersion = 1,
                    catalogId = SourceSeparationRuntimeCatalogMetadata.CATALOG_ID,
                    producerContractSchemaVersion = SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION,
                    producerContractSha256 = "b".repeat(64),
                    entries = listOf(entry),
                )
                SourceSeparationRuntimeCatalogLoader.validate(catalog)
                return RuntimeFixture(root, entry, catalog, zip, manifest)
            }

            private fun zipBytes(
                manifest: ByteArray,
                library: ByteArray,
                libraryEntryName: String,
            ): ByteArray = ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(libraryEntryName))
                    zip.write(library)
                    zip.closeEntry()
                }
                buffer.toByteArray()
            }
        }
    }

    private class TestRuntimeProvider(
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

    private class ResumableTestRuntimeProvider(
        private val bytes: ByteArray,
    ) : ResumableRuntimeDeliveryProvider {
        var resumedFrom: Long? = null

        override val providerId: String = "github"
        override val capabilities = SourceSeparationDeliveryCapabilities(
            operations = setOf(SourceSeparationDeliveryOperation.Acquire),
            supportsPlatformManagedPayloads = false,
        )

        override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
            reference.providerId == providerId

        override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload =
            error("The resumable test provider should not restart a full transfer.")

        override fun acquireResumable(
            reference: SourceSeparationDeliveryReference,
            existingBytes: Long,
        ): SourceSeparationResumableDeliveryPayload {
            resumedFrom = existingBytes
            return object : SourceSeparationResumableDeliveryPayload {
                override val reference = reference
                override val resumedOffset = existingBytes
                override val totalByteSize = bytes.size.toLong()
                override fun openStream(): InputStream = ByteArrayInputStream(
                    bytes.copyOfRange(existingBytes.toInt(), bytes.size),
                )
                override fun close() = Unit
            }
        }
    }

    private class FailingRuntimeProvider(
        private val bytes: ByteArray,
        private val failAfterBytes: Int,
    ) : RuntimeDeliveryProvider {
        override val providerId: String = "github"
        override val capabilities = SourceSeparationDeliveryCapabilities(
            operations = setOf(SourceSeparationDeliveryOperation.Acquire),
            supportsPlatformManagedPayloads = false,
        )

        override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
            reference.providerId == providerId

        override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload =
            object : SourceSeparationDeliveryPayload {
                override val reference = reference
                override val byteSize: Long = bytes.size.toLong()

                override fun openStream(): InputStream = object : InputStream() {
                    private val delegate = ByteArrayInputStream(bytes)
                    private var delivered = 0

                    override fun read(): Int {
                        if (delivered >= failAfterBytes) {
                            throw IOException("Injected delivery cancellation")
                        }
                        val value = delegate.read()
                        if (value >= 0) delivered++
                        return value
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (delivered >= failAfterBytes) {
                            throw IOException("Injected delivery cancellation")
                        }
                        val count = delegate.read(
                            buffer,
                            offset,
                            min(length, failAfterBytes - delivered),
                        )
                        if (count > 0) delivered += count
                        return count
                    }

                    override fun close() = delegate.close()
                }

                override fun close() = Unit
            }
    }

    private class BlockingRuntimeProvider(
        private val bytes: ByteArray,
    ) : RuntimeDeliveryProvider {
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)

        override val providerId: String = "github"
        override val capabilities = SourceSeparationDeliveryCapabilities(
            operations = setOf(SourceSeparationDeliveryOperation.Acquire),
            supportsPlatformManagedPayloads = false,
        )

        override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
            reference.providerId == providerId

        override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload {
            acquired.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release the installer" }
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
