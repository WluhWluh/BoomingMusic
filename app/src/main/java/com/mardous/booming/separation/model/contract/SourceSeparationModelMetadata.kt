package com.mardous.booming.separation.model.contract

import android.content.Context
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object SourceSeparationModelMetadata {
    const val CATALOG_ASSET_PATH = "source-separation/model-catalog-v2.json"
    const val CATALOG_SOURCE_REPOSITORY = "https://github.com/WluhWluh/bss-tflite"
    const val CATALOG_SOURCE_REVISION = "710c6ae4aa1fe34eaf314697db63a65874127395"
    const val CATALOG_SHA256 =
        "9025533f7fce2add2482846d04b3adc1fecfd28fd95f30d2efe59f9d87eec40a"

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
