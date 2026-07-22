package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationCacheStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `root provider prefers external cache and falls back when unavailable`() {
        val external = temporary.newFolder("external")
        val internal = temporary.newFolder("internal")
        val preferred = SelectingSourceSeparationCacheRootProvider(
            externalCacheDirectory = { external },
            internalCacheDirectory = { internal },
        ).resolveRoot()

        assertEquals(SourceSeparationCacheRootLocation.ExternalCache, preferred.location)
        assertEquals(File(external, "source-separation"), preferred.directory)

        preferred.directory.deleteRecursively()
        assertTrue(preferred.directory.createNewFile())
        val fallback = SelectingSourceSeparationCacheRootProvider(
            externalCacheDirectory = { external },
            internalCacheDirectory = { internal },
        ).resolveRoot()

        assertEquals(SourceSeparationCacheRootLocation.InternalCache, fallback.location)
        assertEquals(File(internal, "source-separation"), fallback.directory)
    }

    @Test
    fun `manifest survives store recreation and root deletion is recoverable`() {
        val root = cacheRoot()
        val first = SourceSeparationCacheStore(root)
        val manifest = completedManifest(first)
        first.writeManifest(manifest)

        assertEquals(manifest, SourceSeparationCacheStore(root).readManifest(manifest.cacheKey))

        root.directory.deleteRecursively()
        val recreated = SourceSeparationCacheStore(root)
        assertTrue(recreated.root().directory.isDirectory)
        assertTrue(
            File(root.directory, SourceSeparationCacheStore.ENTRIES_DIR_NAME).isDirectory
        )
        assertNull(recreated.readManifest(manifest.cacheKey))
    }

    @Test
    fun `staging promotion publishes one final entry atomically`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store, createFiles = false)
        val staging = store.beginStaging("run-1")
        writeCompletedFiles(staging.directory, manifest)

        val entryDirectory = store.promoteStaging(staging, manifest)

        assertFalse(staging.directory.exists())
        assertEquals(manifest.cacheKey, entryDirectory.name)
        assertEquals(manifest, store.readManifest(manifest.cacheKey))
        assertThrows(IllegalArgumentException::class.java) {
            store.promoteStaging(store.beginStaging("run-2"), manifest)
        }
    }

    @Test
    fun `completed output validation detects alteration and missing files`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)

        assertEquals(
            SourceSeparationCacheValidationResult.Valid,
            store.validateCompletedEntry(manifest),
        )

        store.resolveEntryPath(manifest.cacheKey, "completed/vocals.wav")
            .appendText("changed")
        assertEquals(
            SourceSeparationCacheValidationResult.Invalid("wav-invalid"),
            store.validateCompletedEntry(manifest),
        )

        store.resolveEntryPath(manifest.cacheKey, "completed/instrumental.wav").delete()
        assertEquals(
            SourceSeparationCacheValidationResult.Invalid("wav-invalid"),
            store.validateCompletedEntry(manifest),
        )
    }

    @Test
    fun `playback settings require exact manifest identity`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)
        val settings = SourceSeparationCachePlaybackSettings(
            cacheKey = manifest.cacheKey,
            audioFingerprint = manifest.identity.source.audioFingerprint,
            blend = 0.75f,
            updatedAtEpochMs = 5L,
        )

        store.writePlaybackSettings(manifest, settings)
        assertEquals(settings, store.readPlaybackSettings(manifest))

        assertThrows(IllegalArgumentException::class.java) {
            store.writePlaybackSettings(
                manifest,
                settings.copy(cacheKey = "0".repeat(64)),
            )
        }
    }

    @Test
    fun `locator index is rebuildable and exact source matching rejects replacement`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)
        val indexFile = File(store.root().directory, SourceSeparationCacheStore.LOCATOR_INDEX_FILE_NAME)

        assertEquals(listOf(manifest), store.candidateManifests(manifest.song))
        assertEquals(
            listOf(manifest),
            store.matchingManifests(manifest.song, manifest.identity.source),
        )
        indexFile.writeText("not-json")
        assertEquals(listOf(manifest), store.candidateManifests(manifest.song))

        val replacedSource = manifest.identity.source.copy(
            audioFingerprint = "encoded-samples-v1:${"d".repeat(64)}",
        )
        assertTrue(store.matchingManifests(manifest.song, replacedSource).isEmpty())
    }

    @Test
    fun `recovery removes temp staging and unreadable entries then rebuilds index`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val valid = completedManifest(store)
        store.writeManifest(valid)
        val tempFile = File(store.entryDirectory(valid.cacheKey), "manifest.json.orphan.tmp")
        tempFile.writeText("orphan")
        store.beginStaging("abandoned").directory.resolve("partial.wav").writeText("partial")
        val invalidEntry = store.entryDirectory("0".repeat(64)).apply { mkdirs() }
        File(invalidEntry, SourceSeparationCacheStore.MANIFEST_FILE_NAME).writeText("not-json")

        val result = store.recover()

        assertEquals(1, result.removedTemporaryFiles)
        assertEquals(1, result.removedStagingRuns)
        assertEquals(1, result.removedInvalidEntries)
        assertNotNull(store.readManifest(valid.cacheKey))
        assertFalse(tempFile.exists())
        assertFalse(invalidEntry.exists())
        assertEquals(listOf(valid), store.candidateManifests(valid.song))
    }

    @Test
    fun `manifest reader rejects historical schema and wrong directory key`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)
        val manifestFile = File(
            store.entryDirectory(manifest.cacheKey),
            SourceSeparationCacheStore.MANIFEST_FILE_NAME,
        )
        manifestFile.writeText(
            manifestFile.readText().replace(
                "\"manifestSchemaVersion\":2",
                "\"manifestSchemaVersion\":1",
            )
        )

        assertNull(store.readManifest(manifest.cacheKey))

        val wrongDirectory = store.entryDirectory("0".repeat(64)).apply { mkdirs() }
        manifestFile.copyTo(
            File(wrongDirectory, SourceSeparationCacheStore.MANIFEST_FILE_NAME),
            overwrite = true,
        )
        assertNull(store.readManifest("0".repeat(64)))
    }

    @Test
    fun `resolved paths cannot follow a symlink outside the entry`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)
        val outside = temporary.newFolder("outside")
        File(outside, "outside.wav").writeText("outside")
        val link = File(store.entryDirectory(manifest.cacheKey), "linked")
        val linked = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        if (!linked) return

        assertThrows(IllegalArgumentException::class.java) {
            store.resolveEntryPath(manifest.cacheKey, "linked/outside.wav")
        }
    }

    private fun cacheRoot(): SourceSeparationCacheRoot {
        return SourceSeparationCacheRoot(
            directory = temporary.newFolder().absoluteFile,
            location = SourceSeparationCacheRootLocation.InternalCache,
        )
    }

    private fun completedManifest(
        store: SourceSeparationCacheStore,
        createFiles: Boolean = true,
    ): SourceSeparationCacheManifest {
        val snapshot = SourceSeparationCacheContractSnapshot.fromOfficial(contract)
        val source = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 100L,
            encodedByteCount = 1_024L,
            mimeType = "audio/flac",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 1_000_000L,
        )
        val identity = snapshot.identity(source)
        val entryDirectory = store.entryDirectory(identity.cacheKey)
        val fileContents = mapOf(
            "completed/vocals.wav" to "vocals",
            "completed/instrumental.wav" to "instrumental",
            "completed/timing.txt" to "timing",
        )
        if (createFiles) {
            entryDirectory.mkdirs()
            fileContents.forEach { (path, content) ->
                File(entryDirectory, path).apply {
                    parentFile?.mkdirs()
                    writeText(content)
                }
            }
        }
        fun integrity(path: String): SourceSeparationCacheFileIntegrity {
            val content = requireNotNull(fileContents[path])
            return SourceSeparationCacheFileIntegrity(
                byteSize = content.toByteArray().size.toLong(),
                sha256 = sha256(content.toByteArray()),
            )
        }
        val stems = listOf(
            SourceSeparationCacheRenderedStem(
                semantic = ContractStemSemantic.Vocals,
                displayLabel = "Vocals",
                wavPath = "completed/vocals.wav",
                channelCount = 2,
                sampleRate = 44_100,
                frameCount = 44_100,
                wavIntegrity = integrity("completed/vocals.wav"),
            ),
            SourceSeparationCacheRenderedStem(
                semantic = ContractStemSemantic.Instrumental,
                displayLabel = "Instrumental",
                wavPath = "completed/instrumental.wav",
                channelCount = 2,
                sampleRate = 44_100,
                frameCount = 44_100,
                wavIntegrity = integrity("completed/instrumental.wav"),
            ),
        )
        return SourceSeparationCacheManifest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = snapshot,
            state = SourceSeparationCacheManifestState.Completed,
            song = SourceSeparationCacheSongLocator(
                songId = 42L,
                mediaUri = "content://media/42",
                filePath = "/music/song.flac",
                title = "Song",
                artist = "Artist",
                album = "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = 1_024L,
                rawDateModified = 50L,
                durationMs = 1_000L,
            ),
            output = SourceSeparationCacheOutput(
                stems = stems,
                timingPath = "completed/timing.txt",
                outputSampleRate = 44_100,
                outputFrameCount = 44_100,
                windowCount = 1,
                elapsedMs = 100L,
                totalBytes = fileContents.values.sumOf { it.toByteArray().size }.toLong(),
            ),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
    }

    private fun writeCompletedFiles(
        directory: File,
        manifest: SourceSeparationCacheManifest,
    ) {
        manifest.output!!.stems.forEach { stem ->
            File(directory, stem.wavPath).apply {
                parentFile?.mkdirs()
                writeText(if (stem.semantic == ContractStemSemantic.Vocals) "vocals" else "instrumental")
            }
        }
        manifest.output.timingPath?.let { path ->
            File(directory, path).apply {
                parentFile?.mkdirs()
                writeText("timing")
            }
        }
    }

    companion object {
        private lateinit var contract: SourceSeparationModelContract

        @JvmStatic
        @BeforeClass
        fun loadContract() {
            val resource = requireNotNull(
                SourceSeparationCacheStoreTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            )
            val catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
            contract = catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }
        }

        private fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
        }
    }
}
