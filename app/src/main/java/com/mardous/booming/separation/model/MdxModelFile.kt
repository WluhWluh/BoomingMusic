package com.mardous.booming.separation.model

import android.content.Context
import java.io.File
import java.security.MessageDigest

object MdxModelFile {
    fun get(context: Context, variant: MdxModelVariant): File {
        return resolve(context, variant).file
    }

    fun resolve(context: Context, variant: MdxModelVariant): MdxModelArtifact {
        val state = SourceSeparationModelRepository(context).modelState(variant)
        val available = state as? SourceSeparationModelState.Available
            ?: throw SourceSeparationModelUnavailableException(variant)
        val file = File(available.path)
        val actualSha256 = available.actualSha256 ?: file.sha256Hex()
        return MdxModelArtifact(
            file = file,
            byteSize = file.length(),
            sha256 = actualSha256,
        )
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
