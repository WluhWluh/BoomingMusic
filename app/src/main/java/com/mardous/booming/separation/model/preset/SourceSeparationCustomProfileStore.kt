package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

interface SourceSeparationCustomProfileStore {
    fun profiles(): List<SourceSeparationCustomModelProfile>
    fun profile(profileId: String): SourceSeparationCustomModelProfile?
    fun write(profile: SourceSeparationCustomModelProfile)
    fun merge(profiles: List<SourceSeparationCustomModelProfile>)
    fun delete(profileId: String): Boolean
}

internal class FileSourceSeparationCustomProfileStore(
    private val rootDirectory: File,
) : SourceSeparationCustomProfileStore {
    private val lock = Any()

    override fun profiles(): List<SourceSeparationCustomModelProfile> = synchronized(lock) {
        recoverInterruptedMerge()
        rootDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(PROFILE_SUFFIX) }
            ?.mapNotNull(::readProfile)
            ?.sortedBy(SourceSeparationCustomModelProfile::profileId)
            ?.toList()
            .orEmpty()
    }

    override fun profile(profileId: String): SourceSeparationCustomModelProfile? =
        profiles().singleOrNull { it.profileId == profileId }

    override fun write(profile: SourceSeparationCustomModelProfile): Unit = synchronized(lock) {
        merge(listOf(profile))
    }

    override fun merge(profiles: List<SourceSeparationCustomModelProfile>): Unit = synchronized(lock) {
        recoverInterruptedMerge()
        val existing = this.profiles()
        val combined = mergeProfiles(existing, profiles)
        if (combined == existing) return

        val parent = rootDirectory.parentFile
            ?: throw SourceSeparationPresetProfileException("Custom profile root has no parent.")
        parent.mkdirs()
        val staged = stagedDirectory(parent)
        val previous = previousDirectory(parent)
        staged.deleteRecursively()
        previous.deleteRecursively()
        if (!staged.mkdirs()) {
            throw SourceSeparationPresetProfileException("Unable to stage custom profiles.")
        }
        try {
            combined.forEach { profile ->
                profileFile(staged, profile.artifact.sha256).writeText(
                    SourceSeparationModelMetadata.json.encodeToString(profile),
                )
            }
            if (rootDirectory.exists() && !rootDirectory.renameTo(previous)) {
                throw SourceSeparationPresetProfileException(
                    "Unable to preserve existing custom profiles.",
                )
            }
            if (!staged.renameTo(rootDirectory)) {
                if (previous.exists()) previous.renameTo(rootDirectory)
                throw SourceSeparationPresetProfileException("Unable to install custom profiles.")
            }
            previous.deleteRecursively()
        } catch (error: Throwable) {
            staged.deleteRecursively()
            if (!rootDirectory.exists() && previous.exists()) {
                previous.renameTo(rootDirectory)
            }
            throw error
        }
        Unit
    }

    override fun delete(profileId: String): Boolean = synchronized(lock) {
        val profile = profile(profileId) ?: return false
        val file = profileFile(profile.artifact.sha256)
        if (!file.delete()) {
            throw SourceSeparationPresetProfileException(
                "Unable to delete custom model profile.",
            )
        }
        true
    }

    private fun readProfile(file: File): SourceSeparationCustomModelProfile? = try {
        SourceSeparationModelMetadata.decodeCustomProfile(file.readText()).also { profile ->
            SourceSeparationModelContractValidator.validateCustomProfile(profile)
            if (file.name != "${profile.artifact.sha256}$PROFILE_SUFFIX") return null
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun profileFile(sha256: String): File = profileFile(rootDirectory, sha256)

    private fun profileFile(root: File, sha256: String): File =
        File(root, "${sha256.lowercase()}$PROFILE_SUFFIX")

    private fun recoverInterruptedMerge() {
        val parent = rootDirectory.parentFile ?: return
        val staged = stagedDirectory(parent)
        val previous = previousDirectory(parent)
        if (!rootDirectory.exists() && previous.exists() && !previous.renameTo(rootDirectory)) {
            throw SourceSeparationPresetProfileException(
                "Unable to recover existing custom profiles.",
            )
        }
        staged.deleteRecursively()
        if (rootDirectory.exists()) previous.deleteRecursively()
    }

    private fun stagedDirectory(parent: File) =
        File(parent, "${rootDirectory.name}.staging")

    private fun previousDirectory(parent: File) =
        File(parent, "${rootDirectory.name}.previous")

    private fun mergeProfiles(
        existing: List<SourceSeparationCustomModelProfile>,
        incoming: List<SourceSeparationCustomModelProfile>,
    ): List<SourceSeparationCustomModelProfile> {
        incoming.forEach(SourceSeparationModelContractValidator::validateCustomProfile)
        val combined = existing + incoming
        val profileIdConflicts = combined.groupBy { it.profileId }.values
            .any { matches -> matches.distinct().size > 1 }
        val artifactConflicts = combined.groupBy { it.artifact.sha256.lowercase() }.values
            .any { matches -> matches.distinct().size > 1 }
        if (profileIdConflicts || artifactConflicts) {
            throw SourceSeparationPresetProfileException(
                "Custom profiles must have unique IDs and model hashes.",
            )
        }
        return combined.distinct().sortedBy(SourceSeparationCustomModelProfile::profileId)
    }

    private companion object {
        const val PROFILE_SUFFIX = ".profile.json"
    }
}

class SourceSeparationPresetProfileException(message: String) : IllegalStateException(message)
