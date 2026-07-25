package com.mardous.booming.separation.cache.v2

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SourceSeparationCacheStore(
    private val root: SourceSeparationCacheRoot,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val fileHasher: SourceSeparationCacheFileHasher =
        SourceSeparationCacheFileHasher { file -> sha256(file) },
    private val json: Json = DEFAULT_JSON,
    private val entryLockManager: SourceSeparationCacheEntryLockManager =
        SourceSeparationCacheEntryLockManager(root),
) {
    init {
        require(root.directory.isAbsolute) { "Cache root must be absolute." }
        ensureLayout()
    }

    fun root(): SourceSeparationCacheRoot = root

    fun entryLocks(): SourceSeparationCacheEntryLockManager = entryLockManager

    fun ensureLayout() {
        require(root.directory.exists() || root.directory.mkdirs()) {
            "Unable to create source-separation cache root."
        }
        require(root.directory.isDirectory) { "Source-separation cache root is not a directory." }
        require(File(root.directory, ENTRIES_DIR_NAME).exists() ||
            File(root.directory, ENTRIES_DIR_NAME).mkdirs()
        ) { "Unable to create source-separation entries directory." }
        require(File(root.directory, STAGING_DIR_NAME).exists() ||
            File(root.directory, STAGING_DIR_NAME).mkdirs()
        ) { "Unable to create source-separation staging directory." }
    }

    fun entryDirectory(cacheKey: String): File {
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Cache key is invalid." }
        return File(File(root.directory, ENTRIES_DIR_NAME), cacheKey)
    }

    fun entriesDirectory(): File = File(root.directory, ENTRIES_DIR_NAME)

    fun beginStaging(runId: String = UUID.randomUUID().toString()): SourceSeparationCacheStaging {
        require(RUN_ID_PATTERN.matches(runId)) { "Cache staging run ID is invalid." }
        ensureLayout()
        val directory = File(File(root.directory, STAGING_DIR_NAME), runId)
        require(!directory.exists()) { "Cache staging run already exists." }
        require(directory.mkdirs()) { "Unable to create cache staging directory." }
        return SourceSeparationCacheStaging(runId = runId, directory = directory)
    }

    fun promoteStaging(
        staging: SourceSeparationCacheStaging,
        manifest: SourceSeparationCacheManifest,
    ): File {
        validateStagingDirectory(staging)
        writeManifestFile(staging.directory, manifest)
        val target = entryDirectory(manifest.cacheKey)
        require(!target.exists()) { "Cache entry already exists: ${manifest.cacheKey}" }
        moveDirectoryAtomically(staging.directory, target)
        refreshLocatorIndexBestEffort()
        return target
    }

    fun writeManifest(manifest: SourceSeparationCacheManifest): File {
        val directory = entryDirectory(manifest.cacheKey)
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create cache entry directory."
        }
        writeManifestFile(directory, manifest)
        refreshLocatorIndexBestEffort()
        return directory
    }

    fun readManifest(cacheKey: String): SourceSeparationCacheManifest? {
        val directory = entryDirectory(cacheKey)
        return readManifestFromDirectory(directory, cacheKey)
    }

    fun writeRunJournal(journal: SourceSeparationCacheRunJournal) {
        writeJsonFile(
            directory = entryDirectory(journal.request.cacheKey),
            targetName = RUN_JOURNAL_FILE_NAME,
            serializer = SourceSeparationCacheRunJournal.serializer(),
            value = journal,
        )
    }

    fun readRunJournal(cacheKey: String): SourceSeparationCacheRunJournal? {
        val directory = entryDirectory(cacheKey)
        val file = File(directory, RUN_JOURNAL_FILE_NAME)
        if (!file.isFile || !file.isWithin(directory)) return null
        return runCatching {
            json.decodeFromString(
                SourceSeparationCacheRunJournal.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull()?.takeIf { journal -> journal.request.cacheKey == cacheKey }
    }

    fun listManifests(): List<SourceSeparationCacheManifest> {
        ensureLayout()
        return File(root.directory, ENTRIES_DIR_NAME)
            .listFiles()
            ?.filter { it.isDirectory && CACHE_KEY_PATTERN.matches(it.name) }
            ?.mapNotNull { readManifestFromDirectory(it, it.name) }
            ?.sortedByDescending(SourceSeparationCacheManifest::lastAccessedAtEpochMs)
            .orEmpty()
    }

    fun touchManifest(manifest: SourceSeparationCacheManifest): SourceSeparationCacheManifest? {
        val current = readManifest(manifest.cacheKey) ?: return null
        val now = nowEpochMs()
        val updated = current.copy(
            updatedAtEpochMs = maxOf(current.updatedAtEpochMs, now),
            lastAccessedAtEpochMs = maxOf(current.lastAccessedAtEpochMs, now),
        )
        writeManifestFile(entryDirectory(current.cacheKey), updated)
        return updated
    }

    fun writePlaybackSettings(
        manifest: SourceSeparationCacheManifest,
        settings: SourceSeparationCachePlaybackSettings,
    ) {
        require(settings.matches(manifest)) {
            "Playback settings do not match the cache manifest."
        }
        val directory = entryDirectory(manifest.cacheKey)
        require(readManifest(manifest.cacheKey) != null) {
            "Cannot write playback settings for an unreadable cache entry."
        }
        writeJsonFile(
            directory = directory,
            targetName = PLAYBACK_SETTINGS_FILE_NAME,
            serializer = SourceSeparationCachePlaybackSettings.serializer(),
            value = settings,
        )
    }

    fun readPlaybackSettings(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationCachePlaybackSettings? {
        val directory = entryDirectory(manifest.cacheKey)
        val file = File(directory, PLAYBACK_SETTINGS_FILE_NAME)
        if (!file.isFile || !file.isWithin(directory)) return null
        return runCatching {
            json.decodeFromString(
                SourceSeparationCachePlaybackSettings.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull()?.takeIf { it.matches(manifest) }
    }

    fun writeHydrationMarker(
        manifest: SourceSeparationCacheManifest,
        marker: SourceSeparationCacheHydrationMarker,
    ) {
        require(marker.matches(manifest)) {
            "Hydration marker does not match its completed cache entry."
        }
        val markerFile = resolveEntryPath(manifest.cacheKey, HYDRATION_MARKER_FILE_NAME)
        writeJsonFile(
            directory = requireNotNull(markerFile.parentFile),
            targetName = markerFile.name,
            serializer = SourceSeparationCacheHydrationMarker.serializer(),
            value = marker,
        )
    }

    fun readHydrationMarker(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationCacheHydrationMarker? {
        val directory = entryDirectory(manifest.cacheKey)
        val file = resolveRelativePath(directory, HYDRATION_MARKER_FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(
                SourceSeparationCacheHydrationMarker.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull()?.takeIf { marker ->
            marker.matches(manifest) && marker.stems.all { stem ->
                validateFile(
                    file = resolveRelativePath(directory, stem.pcmPath),
                    expected = stem.integrity,
                    verifyHash = true,
                )
            }
        }
    }

    fun deleteEntry(cacheKey: String): Boolean {
        val directory = entryDirectory(cacheKey)
        val deleted = !directory.exists() || directory.deleteRecursively()
        if (deleted) refreshLocatorIndexBestEffort()
        return deleted
    }

    fun validateCompletedEntry(
        manifest: SourceSeparationCacheManifest,
        verifyHashes: Boolean = true,
    ): SourceSeparationCacheValidationResult {
        if (manifest.state != SourceSeparationCacheManifestState.Completed) {
            return SourceSeparationCacheValidationResult.Invalid("entry-not-completed")
        }
        val output = manifest.output
            ?: return SourceSeparationCacheValidationResult.Invalid("output-missing")
        val directory = entryDirectory(manifest.cacheKey)
        output.stems.forEach { stem ->
            if (stem.promotionValidated) {
                val promotedPath = stem.promotedPath
                    ?: return SourceSeparationCacheValidationResult.Invalid("promoted-path-missing")
                val promotedIntegrity = stem.promotedIntegrity
                    ?: return SourceSeparationCacheValidationResult.Invalid(
                        "promoted-integrity-missing"
                    )
                if (!validateFile(
                        file = resolveRelativePath(directory, promotedPath),
                        expected = promotedIntegrity,
                        verifyHash = verifyHashes,
                    )
                ) {
                    return SourceSeparationCacheValidationResult.Invalid("promoted-invalid")
                }
                val promotedIndexPath = stem.promotedIndexPath
                    ?: return SourceSeparationCacheValidationResult.Invalid(
                        "promoted-index-path-missing"
                    )
                val promotedIndexIntegrity = stem.promotedIndexIntegrity
                    ?: return SourceSeparationCacheValidationResult.Invalid(
                        "promoted-index-integrity-missing"
                    )
                if (!validateFile(
                        file = resolveRelativePath(directory, promotedIndexPath),
                        expected = promotedIndexIntegrity,
                        verifyHash = verifyHashes,
                    )
                ) {
                    return SourceSeparationCacheValidationResult.Invalid("promoted-index-invalid")
                }
            } else {
                val wavIntegrity = stem.wavIntegrity
                    ?: return SourceSeparationCacheValidationResult.Invalid("wav-integrity-missing")
                if (!validateFile(
                        file = resolveRelativePath(directory, stem.wavPath),
                        expected = wavIntegrity,
                        verifyHash = verifyHashes,
                    )
                ) {
                    return SourceSeparationCacheValidationResult.Invalid("wav-invalid")
                }
            }
        }
        output.timingPath?.let { path ->
            if (!resolveRelativePath(directory, path).isFile) {
                return SourceSeparationCacheValidationResult.Invalid("timing-missing")
            }
        }
        return SourceSeparationCacheValidationResult.Valid
    }

    fun resolveRelativePath(entryDirectory: File, relativePath: String): File {
        SourceSeparationCacheRelativePath.requireValid(relativePath)
        val canonicalEntry = entryDirectory.canonicalFile
        var component = entryDirectory
        relativePath.split('/').forEach { segment ->
            component = File(component, segment)
            require(!Files.isSymbolicLink(component.toPath())) {
                "Cache path must not traverse a symbolic link."
            }
        }
        val resolved = File(entryDirectory, relativePath.replace('/', File.separatorChar)).canonicalFile
        require(resolved.isWithin(canonicalEntry)) {
            "Cache path escapes its entry directory."
        }
        return resolved
    }

    fun resolveEntryPath(cacheKey: String, relativePath: String): File {
        return resolveRelativePath(entryDirectory(cacheKey), relativePath)
    }

    fun relativeEntryPath(cacheKey: String, file: File): String {
        val entry = entryDirectory(cacheKey).canonicalFile
        val target = file.canonicalFile
        require(target.isWithin(entry)) { "Cache file is outside its entry directory." }
        val relative = entry.toPath().relativize(target.toPath()).toString()
            .replace(File.separatorChar, '/')
        SourceSeparationCacheRelativePath.requireValid(relative)
        return relative
    }

    fun copyIntoEntryAtomically(
        cacheKey: String,
        source: File,
        relativePath: String,
    ): SourceSeparationCacheFileIntegrity {
        require(source.isFile) { "Cache copy source is missing." }
        val target = resolveEntryPath(cacheKey, relativePath)
        val targetDirectory = requireNotNull(target.parentFile) {
            "Cache output has no parent directory."
        }
        require(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "Unable to create cache output directory."
        }
        val temporary = File.createTempFile("${target.name}.", ".tmp", targetDirectory)
        try {
            source.copyTo(temporary, overwrite = true)
            syncFile(temporary)
            val integrity = fileIntegrity(temporary)
            replaceFile(temporary, target)
            return integrity
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun fileIntegrity(file: File): SourceSeparationCacheFileIntegrity {
        require(file.isFile) { "Cache integrity file is missing." }
        return SourceSeparationCacheFileIntegrity(
            byteSize = file.length(),
            sha256 = fileHasher.sha256(file),
        )
    }

    fun validateIntegrity(
        cacheKey: String,
        relativePath: String,
        expected: SourceSeparationCacheFileIntegrity,
        verifyHash: Boolean = true,
    ): Boolean = validateFile(
        file = resolveEntryPath(cacheKey, relativePath),
        expected = expected,
        verifyHash = verifyHash,
    )

    fun deleteRelativePath(cacheKey: String, relativePath: String): Boolean {
        val target = resolveEntryPath(cacheKey, relativePath)
        return !target.exists() || if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
    }

    fun entrySize(cacheKey: String): Long = entryDirectory(cacheKey).directorySize()

    fun candidateManifests(
        locator: SourceSeparationCacheSongLocator,
    ): List<SourceSeparationCacheManifest> {
        val index = readLocatorIndex() ?: rebuildLocatorIndex()
        val record = index.records.singleOrNull {
            it.locatorKey == SourceSeparationCacheLocatorRecord.keyFor(locator)
        } ?: return emptyList()
        return record.cacheKeys.mapNotNull(::readManifest)
    }

    fun matchingManifests(
        locator: SourceSeparationCacheSongLocator,
        source: SourceSeparationCacheSourceIdentity,
    ): List<SourceSeparationCacheManifest> {
        return candidateManifests(locator).filter { it.identity.source == source }
    }

    fun rebuildLocatorIndex(): SourceSeparationCacheLocatorIndex {
        val records = listManifests()
            .groupBy { manifest -> SourceSeparationCacheLocatorRecord.keyFor(manifest.song) }
            .map { (locatorKey, manifests) ->
                val first = manifests.first().song
                SourceSeparationCacheLocatorRecord(
                    locatorKey = locatorKey,
                    songId = first.songId,
                    mediaUri = first.mediaUri,
                    filePath = first.filePath,
                    cacheKeys = manifests.map(SourceSeparationCacheManifest::cacheKey).distinct(),
                )
            }
            .sortedBy(SourceSeparationCacheLocatorRecord::locatorKey)
        val index = SourceSeparationCacheLocatorIndex(records = records)
        writeJsonFile(
            directory = root.directory,
            targetName = LOCATOR_INDEX_FILE_NAME,
            serializer = SourceSeparationCacheLocatorIndex.serializer(),
            value = index,
        )
        return index
    }

    fun recover(): SourceSeparationCacheRecoveryResult {
        ensureLayout()
        val stagingDirectory = File(root.directory, STAGING_DIR_NAME)
        val entriesDirectory = File(root.directory, ENTRIES_DIR_NAME)
        var removedTemporaryFiles = removeTopLevelTemporaryFiles(root.directory) +
            removeTemporaryFiles(stagingDirectory)
        val removedStagingRuns = stagingDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.count { it.deleteRecursively() }
            ?: 0
        var removedInvalidEntries = 0
        var removedDerivedArtifacts = 0
        var skippedLockedEntries = 0
        entriesDirectory.listFiles()
            ?.filter(File::isDirectory)
            .orEmpty()
            .forEach { directory ->
                if (!CACHE_KEY_PATTERN.matches(directory.name)) {
                    if (directory.deleteRecursively()) removedInvalidEntries += 1
                    return@forEach
                }
                val kernelLease = entryLockManager.tryAcquire(
                    cacheKey = directory.name,
                    owner = SourceSeparationCacheLockOwner(
                        SourceSeparationCacheLockPurpose.Recovery,
                    ),
                )
                if (kernelLease == null) {
                    skippedLockedEntries += 1
                    return@forEach
                }
                kernelLease.use { lease ->
                    lease.bindEntryDirectory(directory)
                    removedTemporaryFiles += removeTemporaryFiles(directory)
                    val manifest = readManifestFromDirectory(directory, directory.name)
                    if (manifest == null) {
                        if (directory.deleteRecursively()) removedInvalidEntries += 1
                    } else {
                        removedDerivedArtifacts += recoverDerivedArtifacts(manifest)
                    }
                }
            }
        rebuildLocatorIndex()
        return SourceSeparationCacheRecoveryResult(
            removedTemporaryFiles = removedTemporaryFiles,
            removedStagingRuns = removedStagingRuns,
            removedInvalidEntries = removedInvalidEntries,
            removedDerivedArtifacts = removedDerivedArtifacts,
            skippedLockedEntries = skippedLockedEntries,
        )
    }

    private fun recoverDerivedArtifacts(manifest: SourceSeparationCacheManifest): Int {
        var removed = 0
        listOf(
            SourceSeparationCacheFlacPromoter.PROMOTION_STAGING_DIRECTORY,
            SourceSeparationCacheHydrator.HYDRATION_STAGING_DIRECTORY,
        ).forEach { path ->
            val directory = resolveEntryPath(manifest.cacheKey, path)
            if (directory.exists() && deleteRelativePath(manifest.cacheKey, path)) {
                removed += 1
            }
        }

        val hydrationDirectory = resolveEntryPath(
            manifest.cacheKey,
            SourceSeparationCacheHydrator.HYDRATION_OUTPUT_DIRECTORY,
        )
        if (hydrationDirectory.exists() && readHydrationMarker(manifest) == null &&
            deleteRelativePath(
                manifest.cacheKey,
                SourceSeparationCacheHydrator.HYDRATION_OUTPUT_DIRECTORY,
            )
        ) {
            removed += 1
        }

        val referencedPromotedPaths = manifest.output?.stems.orEmpty()
            .flatMap { stem -> listOfNotNull(stem.promotedPath, stem.promotedIndexPath) }
            .toSet()
        val completedDirectory = resolveEntryPath(manifest.cacheKey, "completed")
        completedDirectory.listFiles()
            ?.filter { file ->
                file.isFile &&
                    (file.name.endsWith(".flac") || file.name.endsWith(".flac.frames")) &&
                    relativeEntryPath(manifest.cacheKey, file) !in referencedPromotedPaths
            }
            ?.forEach { file ->
                if (file.delete()) removed += 1
            }
        return removed
    }

    private fun readLocatorIndex(): SourceSeparationCacheLocatorIndex? {
        val file = File(root.directory, LOCATOR_INDEX_FILE_NAME)
        if (!file.isFile || !file.isWithin(root.directory)) return null
        val index = runCatching {
            json.decodeFromString(
                SourceSeparationCacheLocatorIndex.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        val manifests = listManifests()
        val expectedKeys = manifests.map(SourceSeparationCacheManifest::cacheKey).toSet()
        return index.takeIf { candidate ->
            candidate.records.flatMap(SourceSeparationCacheLocatorRecord::cacheKeys).toSet() ==
                expectedKeys &&
            candidate.records.all { record ->
                record.cacheKeys.all { key -> readManifest(key) != null }
            }
        }
    }

    private fun readManifestFromDirectory(
        directory: File,
        expectedCacheKey: String,
    ): SourceSeparationCacheManifest? {
        if (!directory.isDirectory || directory.name != expectedCacheKey) return null
        val file = File(directory, MANIFEST_FILE_NAME)
        if (!file.isFile || !file.isWithin(directory)) return null
        return runCatching {
            json.decodeFromString(
                SourceSeparationCacheManifest.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull()?.takeIf { manifest ->
            manifest.cacheKey == expectedCacheKey && manifest.identity.cacheKey == expectedCacheKey
        }
    }

    private fun writeManifestFile(
        directory: File,
        manifest: SourceSeparationCacheManifest,
    ) {
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create cache manifest directory."
        }
        require(directory.isWithin(root.directory)) {
            "Cache manifest directory escapes the selected root."
        }
        require(directory.name == manifest.cacheKey || directory.name.matches(RUN_ID_PATTERN)) {
            "Cache manifest directory does not match its state directory."
        }
        writeJsonFile(
            directory = directory,
            targetName = MANIFEST_FILE_NAME,
            serializer = SourceSeparationCacheManifest.serializer(),
            value = manifest,
        )
    }

    private fun <T> writeJsonFile(
        directory: File,
        targetName: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create cache JSON directory."
        }
        val target = File(directory, targetName)
        require(target.parentFile?.canonicalFile == directory.canonicalFile) {
            "Cache JSON target escapes its directory."
        }
        val temporary = File.createTempFile("$targetName.", ".tmp", directory)
        try {
            val bytes = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(0L)
                output.write(bytes)
                output.fd.sync()
            }
            replaceFile(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun validateStagingDirectory(staging: SourceSeparationCacheStaging) {
        require(staging.directory.isDirectory) { "Cache staging directory is missing." }
        require(staging.directory.name == staging.runId) { "Cache staging run ID is inconsistent." }
        require(staging.directory.isWithin(File(root.directory, STAGING_DIR_NAME))) {
            "Cache staging directory escapes the staging root."
        }
    }

    private fun moveDirectoryAtomically(source: File, target: File) {
        require(!target.exists()) { "Cache target directory already exists." }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun replaceFile(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        target.parentFile?.let(::syncDirectoryBestEffort)
    }

    private fun syncFile(file: File) {
        RandomAccessFile(file, "rw").use { output -> output.fd.sync() }
    }

    private fun syncDirectoryBestEffort(directory: File) {
        runCatching {
            java.nio.channels.FileChannel.open(
                directory.toPath(),
                java.nio.file.StandardOpenOption.READ,
            ).use { channel -> channel.force(true) }
        }
    }

    private fun validateFile(
        file: File,
        expected: SourceSeparationCacheFileIntegrity,
        verifyHash: Boolean,
    ): Boolean {
        return runCatching {
            file.isFile &&
                file.length() == expected.byteSize &&
                (!verifyHash || fileHasher.sha256(file).equals(expected.sha256, ignoreCase = true))
        }.getOrDefault(false)
    }

    private fun refreshLocatorIndexBestEffort() {
        runCatching { rebuildLocatorIndex() }
    }

    private fun removeTemporaryFiles(
        vararg directories: File,
    ): Int {
        var removed = 0
        directories.asSequence()
            .filter(File::exists)
            .flatMap { directory ->
                if (directory.isDirectory) directory.walkTopDown() else sequenceOf(directory)
            }
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .toList()
            .forEach { file ->
                if (file.delete()) removed += 1
            }
        return removed
    }

    private fun removeTopLevelTemporaryFiles(directory: File): Int {
        return directory.listFiles()
            ?.filter { file -> file.isFile && file.name.endsWith(".tmp") }
            ?.count(File::delete)
            ?: 0
    }

    private fun File.isWithin(parent: File): Boolean {
        return runCatching {
            val canonicalParent = parent.canonicalFile
            var current: File? = canonicalFile
            while (current != null) {
                if (current == canonicalParent) return@runCatching true
                current = current.parentFile
            }
            false
        }.getOrDefault(false)
    }

    private fun File.directorySize(): Long {
        if (!isDirectory) return 0L
        return walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    companion object {
        const val ENTRIES_DIR_NAME = "entries"
        const val STAGING_DIR_NAME = "staging"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val RUN_JOURNAL_FILE_NAME = "run-journal.json"
        const val PLAYBACK_SETTINGS_FILE_NAME = "playback-settings.json"
        const val HYDRATION_MARKER_FILE_NAME = "hydration/v1/marker.json"
        const val LOCATOR_INDEX_FILE_NAME = "locator-index.json"

        private val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
        private val RUN_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val DEFAULT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_HASH_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        private const val DEFAULT_HASH_BUFFER_SIZE = 256 * 1024
    }
}

fun interface SourceSeparationCacheFileHasher {
    fun sha256(file: File): String
}

data class SourceSeparationCacheStaging(
    val runId: String,
    val directory: File,
)

sealed class SourceSeparationCacheValidationResult {
    data object Valid : SourceSeparationCacheValidationResult()
    data class Invalid(val reason: String) : SourceSeparationCacheValidationResult()
}

data class SourceSeparationCacheRecoveryResult(
    val removedTemporaryFiles: Int,
    val removedStagingRuns: Int,
    val removedInvalidEntries: Int,
    val removedDerivedArtifacts: Int = 0,
    val skippedLockedEntries: Int = 0,
)
