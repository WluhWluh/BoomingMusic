package com.mardous.booming.separation.cache.v2

import android.content.Context
import java.io.File

fun interface SourceSeparationCacheRootProvider {
    fun resolveRoot(): SourceSeparationCacheRoot
}

data class SourceSeparationCacheRoot(
    val directory: File,
    val location: SourceSeparationCacheRootLocation,
)

enum class SourceSeparationCacheRootLocation {
    ExternalCache,
    InternalCache,
}

class AndroidSourceSeparationCacheRootProvider(
    private val context: Context,
) : SourceSeparationCacheRootProvider {
    override fun resolveRoot(): SourceSeparationCacheRoot {
        return SelectingSourceSeparationCacheRootProvider(
            externalCacheDirectory = { context.externalCacheDir },
            internalCacheDirectory = { context.cacheDir },
        ).resolveRoot()
    }
}

class SelectingSourceSeparationCacheRootProvider(
    private val externalCacheDirectory: () -> File?,
    private val internalCacheDirectory: () -> File,
) : SourceSeparationCacheRootProvider {
    override fun resolveRoot(): SourceSeparationCacheRoot {
        val external = externalCacheDirectory()
            ?.let { File(it, ROOT_DIRECTORY_NAME) }
            ?.takeIf(::ensureWritableDirectory)
        if (external != null) {
            return SourceSeparationCacheRoot(
                directory = external,
                location = SourceSeparationCacheRootLocation.ExternalCache,
            )
        }

        val internal = File(internalCacheDirectory(), ROOT_DIRECTORY_NAME)
        check(ensureWritableDirectory(internal)) {
            "No writable source-separation cache directory is available."
        }
        return SourceSeparationCacheRoot(
            directory = internal,
            location = SourceSeparationCacheRootLocation.InternalCache,
        )
    }

    private fun ensureWritableDirectory(directory: File): Boolean {
        if (directory.isFile) return false
        if (!directory.isDirectory && !directory.mkdirs()) return false
        return directory.isDirectory && directory.canWrite()
    }

    companion object {
        const val ROOT_DIRECTORY_NAME = "source-separation"
    }
}
