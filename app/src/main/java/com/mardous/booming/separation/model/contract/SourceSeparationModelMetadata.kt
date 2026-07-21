package com.mardous.booming.separation.model.contract

import android.content.Context
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object SourceSeparationModelMetadata {
    const val CATALOG_ASSET_PATH = "source-separation/model-catalog-v1.json"
    const val CATALOG_SOURCE_REPOSITORY = "https://github.com/WluhWluh/bss-tflite"
    const val CATALOG_SOURCE_REVISION = "73e6b25c51dd57da62e3eb30ba93d4a1fe8d5ea6"
    const val CATALOG_SHA256 =
        "a1cb77832cdb28864dd4388ec8b66ca16e25903dde8752c568eaffadb1e45ff5"

    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    fun decodeContract(value: String): SourceSeparationModelContract =
        json.decodeFromString(value)

    fun decodeCustomProfile(value: String): SourceSeparationCustomModelProfile =
        json.decodeFromString(value)

    fun decodeCatalog(value: String): SourceSeparationModelCatalog =
        json.decodeFromString(value)

    fun decodeBundledCatalog(bytes: ByteArray): SourceSeparationModelCatalog {
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        if (actualSha256 != CATALOG_SHA256) {
            throw SourceSeparationModelContractException(
                "Bundled model catalog SHA-256 does not match its pinned source"
            )
        }
        return SourceSeparationModelContractValidator.validateCatalog(
            decodeCatalog(bytes.toString(Charsets.UTF_8))
        )
    }

    fun loadBundledCatalog(context: Context): SourceSeparationModelCatalog {
        val bytes = context.assets.open(CATALOG_ASSET_PATH).use { input ->
            input.readBytes()
        }
        return decodeBundledCatalog(bytes)
    }
}
