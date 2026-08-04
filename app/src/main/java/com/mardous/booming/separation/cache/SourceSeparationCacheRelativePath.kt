package com.mardous.booming.separation.cache

object SourceSeparationCacheRelativePath {
    fun requireValid(path: String) {
        require(path.isNotBlank()) { "Cache path is empty." }
        require(!path.startsWith('/') && !path.startsWith('\\')) {
            "Cache path must be relative."
        }
        require(!WINDOWS_DRIVE_PATH.matches(path)) { "Cache path must not use a drive root." }
        require('\\' !in path) { "Cache path must use forward slashes." }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Cache path contains an unsafe segment."
        }
        require(parts.none { ':' in it || '\u0000' in it }) {
            "Cache path contains an unsafe character."
        }
    }

    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
}
