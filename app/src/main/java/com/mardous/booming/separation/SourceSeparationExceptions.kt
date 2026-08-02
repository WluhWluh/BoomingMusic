package com.mardous.booming.separation

open class SourceSeparationPausedException(
    message: String = "Source separation paused.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
