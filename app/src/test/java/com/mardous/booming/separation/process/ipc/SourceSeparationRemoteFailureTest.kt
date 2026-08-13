package com.mardous.booming.separation.process.ipc

import android.os.DeadObjectException
import android.os.RemoteException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationRemoteFailureTest {
    @Test
    fun `binder transport failures map to one recoverable host error`() {
        val deadObject = DeadObjectException()
        val direct = deadObject.asSourceSeparationRemoteHostDied()
        val nestedCause = RemoteException("disconnected")
        val nested = IllegalStateException("outer", nestedCause)
            .asSourceSeparationRemoteHostDied()

        assertTrue(direct is SourceSeparationRemoteHostDiedException)
        assertSame(deadObject, direct.cause)
        assertTrue(nested is SourceSeparationRemoteHostDiedException)
        assertSame(nestedCause, nested.cause?.cause)
    }

    @Test
    fun `business and protocol failures keep their original type`() {
        val business = IllegalArgumentException("invalid contract")

        assertSame(business, business.asSourceSeparationRemoteHostDied())
    }

    @Test
    fun `already mapped host failure is stable`() {
        val mapped = SourceSeparationRemoteHostDiedException(DeadObjectException())

        assertSame(mapped, mapped.asSourceSeparationRemoteHostDied())
    }
}
