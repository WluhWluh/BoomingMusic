package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toStemSet
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

        store.resolveEntryPath(manifest.cacheKey, "completed/stem-00.wav")
            .appendText("changed")
        assertEquals(
            SourceSeparationCacheValidationResult.Invalid("wav-invalid"),
            store.validateCompletedEntry(manifest),
        )

        store.resolveEntryPath(manifest.cacheKey, "completed/stem-01.wav").delete()
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
            playbackSettingsSchemaVersion = SourceSeparationCachePlaybackSettings.SCHEMA_VERSION,
            cacheKey = manifest.cacheKey,
            audioFingerprint = manifest.identity.source.audioFingerprint,
            stemGains = manifest.output!!.stems.associate { stem ->
                stem.stemId.value to 0.75f
            },
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

        val settingsFile = store.resolveEntryPath(
            manifest.cacheKey,
            SourceSeparationCacheStore.PLAYBACK_SETTINGS_FILE_NAME,
        )
        settingsFile.writeText(
            """{"playbackSettingsSchemaVersion":3,"cacheKey":"${manifest.cacheKey}","audioFingerprint":"${manifest.identity.source.audioFingerprint}","blend":0.75,"updatedAtEpochMs":5}"""
        )
        assertNull(store.readPlaybackSettings(manifest))

        store.writePlaybackSettings(manifest, settings)
        val currentJson = settingsFile.readText()
        settingsFile.writeText(currentJson.replaceFirst("{", "{\"obsolete\":true,"))
        assertNull(store.readPlaybackSettings(manifest))

        settingsFile.writeText(
            currentJson.replace(
                "\"playbackSettingsSchemaVersion\":${SourceSeparationCachePlaybackSettings.SCHEMA_VERSION},",
                "",
            )
        )
        assertNull(store.readPlaybackSettings(manifest))
    }

    @Test
    fun `manifest writes do not create a global locator index`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)

        assertEquals(listOf(manifest), store.listManifests())
        assertFalse(File(store.root().directory, "locator-index.json").exists())
    }

    @Test
    fun `recovery removes temp staging and unreadable entries`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val valid = completedManifest(store)
        store.writeManifest(valid)
        val tempFile = File(store.entryDirectory(valid.cacheKey), "manifest.json.orphan.tmp")
        tempFile.writeText("orphan")
        store.beginStaging("abandoned").directory.resolve("partial.wav").writeText("partial")
        val invalidEntry = store.entryDirectory("0".repeat(64)).apply { mkdirs() }
        File(invalidEntry, SourceSeparationCacheStore.MANIFEST_FILE_NAME).writeText("not-json")
        store.resolveEntryPath(
            valid.cacheKey,
            SourceSeparationCacheFlacPromoter.PROMOTION_STAGING_DIRECTORY,
        ).apply { mkdirs() }.resolve("partial.flac").writeText("partial")
        store.resolveEntryPath(valid.cacheKey, "completed/orphan.flac").writeText("orphan")
        store.resolveEntryPath(valid.cacheKey, "completed/orphan.flac.idx").writeText("orphan")
        store.resolveEntryPath(valid.cacheKey, "completed/orphan.wav").writeText("orphan")
        store.resolveEntryPath(valid.cacheKey, "completed/orphan-timing.txt").writeText("orphan")

        val result = store.recover()

        assertEquals(1, result.removedTemporaryFiles)
        assertEquals(1, result.removedStagingRuns)
        assertEquals(1, result.removedInvalidEntries)
        assertEquals(5, result.removedDerivedArtifacts)
        assertNotNull(store.readManifest(valid.cacheKey))
        assertFalse(tempFile.exists())
        assertFalse(invalidEntry.exists())
        assertEquals(listOf(valid), store.listManifests())
    }

    @Test
    fun `recovery pauses an orphaned running journal`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val partial = completedManifest(store).copy(
            state = SourceSeparationCacheManifestState.Partial,
        )
        store.writeManifest(partial)
        val running = runningJournal(partial).observerConnected(
            observerId = "main-process",
            observerProcessName = "test",
            nowEpochMs = partial.updatedAtEpochMs + 1L,
        )
        store.writeRunJournal(running)

        val result = store.recover()

        val reconciled = requireNotNull(store.readRunJournal(partial.cacheKey))
        assertEquals(1, result.reconciledOrphanedRuns)
        assertEquals(SourceSeparationCacheRunJournalLifecycle.Paused, reconciled.lifecycle)
        assertEquals(
            SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
            reconciled.transitions.last().type,
        )
        assertTrue(reconciled.transitions.any {
            it.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
        })
    }

    @Test
    fun `forced terminal recovery records cancellation after owner death`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val partial = completedManifest(store).copy(
            state = SourceSeparationCacheManifestState.Partial,
            output = null,
        )
        store.writeManifest(partial)
        store.writeRunJournal(runningJournal(partial))
        val error = SourceSeparationCacheError(
            type = "java.util.concurrent.CancellationException",
            message = "forced cancellation",
        )

        val recovered = store.recoverForcedTerminalRun(
            cacheKey = partial.cacheKey,
            runId = "orphaned-run",
            processGeneration = 1L,
            terminalTransition = SourceSeparationCacheRunTransitionType.UserCanceled,
            terminalLifecycle = SourceSeparationCacheRunJournalLifecycle.Canceled,
            terminalError = error,
        )

        assertEquals(SourceSeparationCacheRunJournalLifecycle.Canceled, recovered?.lifecycle)
        assertEquals(SourceSeparationCacheRunTransitionType.UserCanceled,
            recovered?.transitions?.last()?.type)
        assertTrue(recovered?.transitions?.any {
            it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
        } == true)
        assertEquals(error, store.readManifest(partial.cacheKey)?.error)
    }

    @Test
    fun `forced terminal recovery rejects a stale generation`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val partial = completedManifest(store).copy(
            state = SourceSeparationCacheManifestState.Partial,
            output = null,
        )
        store.writeManifest(partial)
        store.writeRunJournal(runningJournal(partial))

        assertNull(store.recoverForcedTerminalRun(
            cacheKey = partial.cacheKey,
            runId = "orphaned-run",
            processGeneration = 2L,
            terminalTransition = SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
            terminalLifecycle = SourceSeparationCacheRunJournalLifecycle.Paused,
            terminalError = null,
        ))
        assertEquals(
            SourceSeparationCacheRunJournalLifecycle.Running,
            store.readRunJournal(partial.cacheKey)?.lifecycle,
        )
    }

    @Test
    fun `recovery removes segment artifacts absent from the committed journal`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val base = completedManifest(store)
        val plan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = 44_100,
            sampleRate = 44_100,
            generationSize = 44_100,
            trim = 1_024,
            chunkSize = 46_148,
            stemIds = base.contract.expectedStemSet().stems.map { it.stemId },
            defaultState = SourceSeparationSegmentState.Running,
        )
        val partial = base.copy(
            state = SourceSeparationCacheManifestState.Partial,
            segmentPlan = plan,
        )
        store.writeManifest(partial)
        store.writeRunJournal(runningJournal(partial))
        val segmentFiles = plan.segments.flatMap { segment ->
            segment.stems.map { stem ->
                store.resolveEntryPath(partial.cacheKey, stem.path).apply {
                    parentFile?.mkdirs()
                    writeText("uncommitted")
                }
            }
        }

        val result = store.recover()

        assertEquals(segmentFiles.size, result.removedDerivedArtifacts)
        assertTrue(segmentFiles.none(File::exists))
        assertEquals(
            SourceSeparationCacheRunJournalLifecycle.Paused,
            store.readRunJournal(partial.cacheKey)?.lifecycle,
        )
        assertTrue(
            store.readManifest(partial.cacheKey)?.segmentPlan?.segments?.all {
                it.state == SourceSeparationSegmentState.Queued
            } == true
        )
    }

    @Test
    fun `recovery removes a partial workspace that has no resumable metadata`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val partial = completedManifest(store, createFiles = false).copy(
            state = SourceSeparationCacheManifestState.Partial,
            output = null,
            segmentPlan = null,
        )
        store.writeManifest(partial)
        store.writeRunJournal(runningJournal(partial))
        val orphanWork = store.resolveEntryPath(partial.cacheKey, "work/orphan.wav").apply {
            parentFile?.mkdirs()
            writeText("work")
        }
        val orphanSegment = store.resolveEntryPath(
            partial.cacheKey,
            "segments/00000/stem-00.wav",
        ).apply {
            parentFile?.mkdirs()
            writeText("segment")
        }

        val result = store.recover()

        assertEquals(2, result.removedDerivedArtifacts)
        assertFalse(orphanWork.exists())
        assertFalse(orphanSegment.exists())
        assertFalse(store.resolveEntryPath(partial.cacheKey, "work").exists())
        assertFalse(store.resolveEntryPath(partial.cacheKey, "segments").exists())
    }

    @Test
    fun `manifest reader rejects noncurrent incomplete and extended schemas`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)
        val manifestFile = File(
            store.entryDirectory(manifest.cacheKey),
            SourceSeparationCacheStore.MANIFEST_FILE_NAME,
        )
        val currentJson = manifestFile.readText()
        manifestFile.writeText(
            currentJson.replace(
                "\"manifestSchemaVersion\":4",
                "\"manifestSchemaVersion\":1",
            )
        )

        assertNull(store.readManifest(manifest.cacheKey))

        manifestFile.writeText(
            currentJson.replace(
                "\"manifestSchemaVersion\":${SourceSeparationCacheManifest.SCHEMA_VERSION},",
                "",
            )
        )
        assertNull(store.readManifest(manifest.cacheKey))

        manifestFile.writeText(
            currentJson.replace(
                "\"cacheIdentitySchemaVersion\":${SourceSeparationCacheIdentity.SCHEMA_VERSION},",
                "",
            )
        )
        assertNull(store.readManifest(manifest.cacheKey))

        manifestFile.writeText(currentJson.replaceFirst("{", "{\"obsolete\":true,"))
        assertNull(store.readManifest(manifest.cacheKey))

        val wrongDirectory = store.entryDirectory("0".repeat(64)).apply { mkdirs() }
        manifestFile.writeText(currentJson)
        manifestFile.copyTo(
            File(wrongDirectory, SourceSeparationCacheStore.MANIFEST_FILE_NAME),
            overwrite = true,
        )
        assertNull(store.readManifest("0".repeat(64)))
    }

    @Test
    fun `run journal reader rejects missing schema and unknown fields`() {
        val store = SourceSeparationCacheStore(cacheRoot())
        val manifest = completedManifest(store)
        store.writeManifest(manifest)
        val journal = runningJournal(manifest)
        store.writeRunJournal(journal)
        val journalFile = store.resolveEntryPath(
            manifest.cacheKey,
            SourceSeparationCacheStore.RUN_JOURNAL_FILE_NAME,
        )
        val currentJson = journalFile.readText()

        journalFile.writeText(
            currentJson.replace(
                "\"journalSchemaVersion\":${SourceSeparationCacheRunJournal.SCHEMA_VERSION},",
                "",
            )
        )
        assertNull(store.readRunJournal(manifest.cacheKey))

        journalFile.writeText(currentJson.replaceFirst("{", "{\"obsolete\":true,"))
        assertNull(store.readRunJournal(manifest.cacheKey))
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
            "completed/stem-00.wav" to "vocals",
            "completed/stem-01.wav" to "instrumental",
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
            snapshot.renderedStemFor(
                semantic = ContractStemSemantic.Vocals,
                path = "completed/stem-00.wav",
                integrity = integrity("completed/stem-00.wav"),
                frameCount = 44_100,
            ),
            snapshot.renderedStemFor(
                semantic = ContractStemSemantic.Instrumental,
                path = "completed/stem-01.wav",
                integrity = integrity("completed/stem-01.wav"),
                frameCount = 44_100,
            ),
        )
        return SourceSeparationCacheManifest(
            manifestSchemaVersion = SourceSeparationCacheManifest.SCHEMA_VERSION,
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

    private fun runningJournal(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationCacheRunJournal = SourceSeparationCacheRunJournal.admitted(
        SourceSeparationCacheRunJournalRequest(
            cacheKey = manifest.cacheKey,
            identity = manifest.identity,
            contract = manifest.contract,
            song = manifest.song,
            sourceDiagnostics = manifest.sourceDiagnostics,
            runId = "orphaned-run",
            processGeneration = 1L,
            ownerPid = 1234,
            runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
            backgroundPolicy = SourceSeparationExecutionRunClass.PlaybackDemandWindow
                .backgroundPolicy,
            tryGpu = false,
            gpuRuntimeIdentity = null,
            gpuFallbackLatch = null,
            admittedAtEpochMs = manifest.createdAtEpochMs,
        )
    )

    private fun writeCompletedFiles(
        directory: File,
        manifest: SourceSeparationCacheManifest,
    ) {
        manifest.output!!.stems.forEach { stem ->
            File(directory, stem.wavPath).apply {
                parentFile?.mkdirs()
                writeText(if (stem.semanticId.value == "vocals") "vocals" else "instrumental")
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
