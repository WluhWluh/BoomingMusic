package com.mardous.booming.util.backup

import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupArchivePayload(
    val path: String,
    val kind: String,
    val schemaVersion: Int? = null,
    val optional: Boolean = true,
    val file: File,
)

class StagedBackupArchive internal constructor(
    val root: File,
    val manifest: BackupManifestV1?,
    val files: Map<String, File>,
) : Closeable {
    fun file(path: String): File? = files[path]

    override fun close() {
        root.deleteRecursively()
    }
}

object BackupArchiveCodec {
    const val MAX_ENTRY_COUNT = 10_000
    const val MAX_ENTRY_BYTES = 128L * 1024L * 1024L
    const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L

    fun createManifest(
        producerPackage: String,
        producerFlavor: String,
        applicationVersion: String,
        generatedAtUtc: String,
        payloads: List<BackupArchivePayload>,
    ): BackupManifestV1 = BackupManifestV1(
        formatVersion = BackupFormatV1.FORMAT_VERSION,
        commonSettingsSchema = BackupFormatV1.COMMON_SETTINGS_SCHEMA,
        sourceSeparationSettingsSchema = BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA,
        producerPackage = producerPackage,
        producerFlavor = producerFlavor,
        applicationVersion = applicationVersion,
        generatedAtUtc = generatedAtUtc,
        payloads = payloads.map { payload ->
            BackupPayloadDescriptor(
                path = BackupArchivePath.normalize(payload.path),
                kind = payload.kind,
                schemaVersion = payload.schemaVersion,
                byteSize = payload.file.length(),
                sha256 = payload.file.sha256(),
                optional = payload.optional,
            )
        },
    ).also(BackupContractValidator::validateManifest)

    fun write(
        output: OutputStream,
        comment: String,
        manifest: BackupManifestV1,
        payloads: List<BackupArchivePayload>,
    ) {
        val payloadsByPath = payloads.associateBy {
            BackupArchivePath.normalize(it.path)
        }
        require(payloadsByPath.size == payloads.size) {
            "Backup payload paths must be unique."
        }
        require(manifest.payloads.map { it.path }.toSet() == payloadsByPath.keys) {
            "Backup manifest and payload files do not match."
        }

        ZipOutputStream(output.buffered()).use { zip ->
            zip.setComment(comment)
            zip.writeEntry(
                BackupFormatV1.MANIFEST_PATH,
                BackupContractJson.json.encodeToString(manifest).encodeToByteArray(),
            )
            manifest.payloads.forEach { descriptor ->
                val payload = requireNotNull(payloadsByPath[descriptor.path])
                zip.putNextEntry(ZipEntry(descriptor.path))
                payload.file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun extract(input: InputStream, stagingRoot: File): StagedBackupArchive {
        require(!stagingRoot.exists()) { "Backup staging directory already exists." }
        check(stagingRoot.mkdirs()) { "Unable to create backup staging directory." }
        val files = linkedMapOf<String, File>()
        val seenPaths = mutableSetOf<String>()
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw BackupContractException("Backup contains too many ZIP entries")
                    }
                    val rawPath = if (entry.isDirectory) entry.name.trimEnd('/') else entry.name
                    val normalized = BackupArchivePath.normalize(rawPath)
                    if (!seenPaths.add(normalized)) {
                        throw BackupContractException(
                            "Backup contains duplicate ZIP path: $normalized",
                        )
                    }
                    val target = BackupArchivePath.resolve(stagingRoot, normalized)
                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw BackupContractException("Unable to create backup directory")
                        }
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            val copied = zip.copyLimitedTo(output, MAX_ENTRY_BYTES)
                            totalBytes += copied
                            if (totalBytes > MAX_ARCHIVE_BYTES) {
                                throw BackupContractException(
                                    "Expanded backup exceeds the size limit",
                                )
                            }
                        }
                        files[normalized] = target
                    }
                    zip.closeEntry()
                }
            }

            val manifestFile = files[BackupFormatV1.MANIFEST_PATH]
            val manifest = manifestFile?.let { file ->
                BackupContractJson.decodeManifest(file.readText())
                    .also(BackupContractValidator::validateManifest)
            }
            if (manifest != null) {
                validateDeclaredPayloads(manifest, files)
            }
            return StagedBackupArchive(stagingRoot, manifest, files)
        } catch (error: Throwable) {
            stagingRoot.deleteRecursively()
            throw error
        }
    }

    private fun validateDeclaredPayloads(
        manifest: BackupManifestV1,
        files: Map<String, File>,
    ) {
        val actualPayloadPaths = files.keys - BackupFormatV1.MANIFEST_PATH
        val declaredPaths = manifest.payloads.map { it.path }.toSet()
        if (actualPayloadPaths != declaredPaths) {
            throw BackupContractException("Backup ZIP entries do not match its manifest")
        }
        manifest.payloads.forEach { payload ->
            val file = files[payload.path]
                ?: throw BackupContractException("Backup payload is missing: ${payload.path}")
            if (file.length() != payload.byteSize) {
                throw BackupContractException(
                    "Backup payload size mismatch: ${payload.path}",
                )
            }
            if (file.sha256() != payload.sha256) {
                throw BackupContractException(
                    "Backup payload hash mismatch: ${payload.path}",
                )
            }
        }
    }

    private fun ZipOutputStream.writeEntry(path: String, contents: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(contents)
        closeEntry()
    }

    private fun InputStream.copyLimitedTo(output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) {
                throw BackupContractException("Backup ZIP entry exceeds the size limit")
            }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}

object BackupArchivePath {
    fun normalize(path: String): String {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) {
            throw BackupContractException("Backup path must be a non-empty relative path")
        }
        if ('\\' in path || ':' in path) {
            throw BackupContractException("Backup path must use portable ZIP separators")
        }
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw BackupContractException("Backup path contains an unsafe segment")
        }
        return segments.joinToString("/")
    }

    fun resolve(root: File, path: String): File {
        val normalized = normalize(path)
        val target = File(root, normalized.replace('/', File.separatorChar)).canonicalFile
        val canonicalRoot = root.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        if (target != canonicalRoot && !target.path.startsWith(rootPrefix)) {
            throw BackupContractException("Backup path escapes the staging directory")
        }
        return target
    }
}
