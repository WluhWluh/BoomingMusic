package com.mardous.booming.separation.delivery

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

internal class DeterministicRuntimeDeliveryProvider(
    override val providerId: String = "test-runtime",
    private val artifacts: Map<String, ByteArray>,
    operations: Set<SourceSeparationDeliveryOperation> = setOf(
        SourceSeparationDeliveryOperation.Acquire,
    ),
    supportsPlatformManagedPayloads: Boolean = false,
    private val acquireFailure: Throwable? = null,
) : RuntimeDeliveryProvider {
    override val capabilities = SourceSeparationDeliveryCapabilities(
        operations = operations,
        supportsPlatformManagedPayloads = supportsPlatformManagedPayloads,
    )
    val acquireCount = AtomicInteger()

    override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
        reference.providerId == providerId && reference.artifactId in artifacts

    override fun acquire(
        reference: SourceSeparationDeliveryReference,
    ): SourceSeparationDeliveryPayload {
        require(supports(reference)) { "The deterministic runtime artifact is unavailable." }
        acquireCount.incrementAndGet()
        acquireFailure?.let { throw it }
        return ByteArrayDeliveryPayload(reference, requireNotNull(artifacts[reference.artifactId]))
    }
}

internal class DeterministicModelDeliveryProvider(
    override val providerId: String = "test-model",
    private val artifacts: Map<String, ByteArray>,
    operations: Set<SourceSeparationDeliveryOperation> = setOf(
        SourceSeparationDeliveryOperation.Acquire,
    ),
    supportsPlatformManagedPayloads: Boolean = false,
    private val acquireFailure: Throwable? = null,
) : ModelDeliveryProvider {
    override val capabilities = SourceSeparationDeliveryCapabilities(
        operations = operations,
        supportsPlatformManagedPayloads = supportsPlatformManagedPayloads,
    )
    val acquireCount = AtomicInteger()

    override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
        reference.providerId == providerId && reference.artifactId in artifacts

    override fun acquire(
        reference: SourceSeparationDeliveryReference,
    ): SourceSeparationDeliveryPayload {
        require(supports(reference)) { "The deterministic model artifact is unavailable." }
        acquireCount.incrementAndGet()
        acquireFailure?.let { throw it }
        return ByteArrayDeliveryPayload(reference, requireNotNull(artifacts[reference.artifactId]))
    }
}

private class ByteArrayDeliveryPayload(
    override val reference: SourceSeparationDeliveryReference,
    private val bytes: ByteArray,
) : SourceSeparationDeliveryPayload {
    override val byteSize: Long = bytes.size.toLong()

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() = Unit
}
