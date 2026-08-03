package com.mardous.booming.separation

import kotlinx.serialization.Serializable

open class SourceSeparationPausedException(
    message: String = "Source separation paused.",
    cause: Throwable? = null,
    val pauseReason: SourceSeparationPauseReason = SourceSeparationPauseReason.Standard,
) : RuntimeException(message, cause)

@Serializable
enum class SourceSeparationPauseReason {
    Standard,
    ActiveModelSuperseded,
}
