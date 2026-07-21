package com.mardous.booming.separation.model.contract

import android.content.Context
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object SourceSeparationModelMetadata {
    const val CATALOG_ASSET_PATH = "source-separation/model-catalog-v1.json"
    const val CATALOG_SOURCE_REPOSITORY = "https://github.com/WluhWluh/bss-tflite"
    const val CATALOG_SOURCE_REVISION = "a5ef10e96f8082ad2cb884a9d5b47a7eb28f6fe3"
    const val CATALOG_SHA256 =
        "a246f08675534e2b49044b25d85bed4a0179196c08646a2ab3fcf511834d735b"

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
