package com.mardous.booming.separation.process.ipc

import android.os.DeadObjectException
import android.os.RemoteException

/** One recoverable product error for either remote execution service. */
internal class SourceSeparationRemoteHostDiedException(
    cause: Throwable?,
) : IllegalStateException("Source-separation remote host died.", cause)

internal fun Throwable.asSourceSeparationRemoteHostDied(): Throwable {
    if (this is SourceSeparationRemoteHostDiedException) return this
    return if (isSourceSeparationRemoteTransportFailure()) {
        SourceSeparationRemoteHostDiedException(this)
    } else {
        this
    }
}

private fun Throwable.isSourceSeparationRemoteTransportFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is DeadObjectException || current is RemoteException) return true
        current = current.cause.takeUnless { it === current }
    }
    return false
}
