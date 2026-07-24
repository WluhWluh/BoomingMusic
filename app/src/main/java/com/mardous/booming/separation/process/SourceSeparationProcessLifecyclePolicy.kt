package com.mardous.booming.separation.process

/** Frozen Phase 3 experiment bounds. These are not user-facing production policy. */
internal object SourceSeparationProcessLifecyclePolicy {
    const val NO_CLIENT_WARM_RETENTION_MS = 5L * 60L * 1_000L
    const val PAUSED_WARM_RETENTION_MS = 5L * 60L * 1_000L
    const val RECYCLE_TIMEOUT_MS = 10_000L
    const val RECYCLE_ACKNOWLEDGEMENT_GRACE_MS = 150L
    const val MINIMUM_LARGEST_FREE_ADDRESS_GAP_BYTES = 128L * 1_024L * 1_024L
}
