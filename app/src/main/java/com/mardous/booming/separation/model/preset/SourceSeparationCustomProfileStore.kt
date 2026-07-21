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
    fun delete(profileId: String): Boolean
}

internal class FileSourceSeparationCustomProfileStore(
    private val rootDirectory: File,
) : SourceSeparationCustomProfileStore {
    private val lock = Any()

    override fun profiles(): List<SourceSeparationCustomModelProfile> = synchronized(lock) {
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

    override fun write(profile: SourceSeparationCustomModelProfile) = synchronized(lock) {
        SourceSeparationModelContractValidator.validateCustomProfile(profile)
        val conflicts = profiles().filter { existing ->
            existing.profileId == profile.profileId ||
                existing.artifact.sha256 == profile.artifact.sha256
        }
        if (conflicts.any { it.profileId != profile.profileId || it != profile }) {
            throw SourceSeparationPresetProfileException(
                "Custom profiles must have unique IDs and model hashes.",
            )
        }

        rootDirectory.mkdirs()
        val target = profileFile(profile.artifact.sha256)
        val temporary = File(rootDirectory, "${target.name}.tmp")
        temporary.writeText(SourceSeparationModelMetadata.json.encodeToString(profile))
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw SourceSeparationPresetProfileException(
                "Unable to replace custom model profile.",
            )
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
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

    private fun profileFile(sha256: String): File =
        File(rootDirectory, "${sha256.lowercase()}$PROFILE_SUFFIX")

    private companion object {
        const val PROFILE_SUFFIX = ".profile.json"
    }
}

class SourceSeparationPresetProfileException(message: String) : IllegalStateException(message)
