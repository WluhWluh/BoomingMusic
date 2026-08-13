package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SourceSeparationProcessExecutionBusyException

internal class SourceSeparationRemoteProcessBusyException(
    val remoteType: String?,
    message: String?,
) : IllegalStateException(
    message ?: "The remote inference process is finishing another model-family run.",
)

internal fun sourceSeparationRemoteBusyFailure(
    errorType: String?,
    message: String?,
    cacheKey: String,
): RuntimeException = if (
    errorType == SourceSeparationProcessExecutionBusyException::class.java.name
) {
    SourceSeparationRemoteProcessBusyException(errorType, message)
} else {
    SourceSeparationRemoteCacheBusyException(cacheKey)
}
