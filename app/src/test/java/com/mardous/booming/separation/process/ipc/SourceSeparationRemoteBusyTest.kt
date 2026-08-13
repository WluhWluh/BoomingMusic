package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationModelFamily
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionBusyException
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionOwner
import com.mardous.booming.separation.process.SourceSeparationRemoteCacheBusyException as RemoteEnvironmentCacheBusyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationRemoteBusyTest {
    @Test
    fun `process ownership busy remains distinct from cache busy`() {
        val owner = SourceSeparationProcessExecutionOwner(
            family = SourceSeparationModelFamily.Htdemucs,
            runId = "demucs-run",
            processGeneration = 7L,
        )
        val processError = SourceSeparationProcessExecutionBusyException(owner)

        val processBusy = sourceSeparationRemoteBusyFailure(
            errorType = processError::class.java.name,
            message = processError.message,
            cacheKey = "new-cache",
        )
        val cacheBusy = sourceSeparationRemoteBusyFailure(
            errorType = RemoteEnvironmentCacheBusyException::class.java.name,
            message = "cache busy",
            cacheKey = "busy-cache",
        )

        assertTrue(processBusy is SourceSeparationRemoteProcessBusyException)
        assertEquals(processError.message, processBusy.message)
        assertTrue(cacheBusy is SourceSeparationRemoteCacheBusyException)
        assertEquals(
            "busy-cache",
            (cacheBusy as SourceSeparationRemoteCacheBusyException).cacheKey,
        )
    }
}
