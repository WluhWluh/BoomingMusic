package com.mardous.booming.separation.cache.v2

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SourceSeparationCacheLocatorIndex(
    val locatorIndexSchemaVersion: Int = SCHEMA_VERSION,
    val records: List<SourceSeparationCacheLocatorRecord>,
) {
    init {
        require(locatorIndexSchemaVersion == SCHEMA_VERSION) {
            "Unsupported cache locator index schema: $locatorIndexSchemaVersion"
        }
        require(records.map { it.locatorKey }.distinct().size == records.size) {
            "Cache locator index contains duplicate records."
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class SourceSeparationCacheLocatorRecord(
    val locatorKey: String,
    val songId: Long,
    val mediaUri: String,
    val filePath: String,
    val cacheKeys: List<String>,
) {
    init {
        require(locatorKey == keyFor(songId, mediaUri, filePath)) {
            "Cache locator record key is invalid."
        }
        require(cacheKeys.isNotEmpty()) { "Cache locator record has no entries." }
        require(cacheKeys.distinct().size == cacheKeys.size) {
            "Cache locator record contains duplicate entry keys."
        }
        require(cacheKeys.all(CACHE_KEY_PATTERN::matches)) {
            "Cache locator record contains an invalid entry key."
        }
    }

    companion object {
        fun keyFor(locator: SourceSeparationCacheSongLocator): String {
            return keyFor(locator.songId, locator.mediaUri, locator.filePath)
        }

        fun keyFor(songId: Long, mediaUri: String, filePath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf("booming-ss-cache-locator-v1", songId.toString(), mediaUri, filePath)
                .forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    digest.update(byteArrayOf(
                        (bytes.size ushr 24).toByte(),
                        (bytes.size ushr 16).toByte(),
                        (bytes.size ushr 8).toByte(),
                        bytes.size.toByte(),
                    ))
                    digest.update(bytes)
                }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        private val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
