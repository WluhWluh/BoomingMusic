package com.mardous.booming.separation

import kotlin.math.abs

internal object SourceSeparationBlendDemand {
    const val CENTER_BLEND = 0.5f

    fun isCentered(blend: Float): Boolean =
        abs(blend.coerceIn(0f, 1f) - CENTER_BLEND) < CENTER_EPSILON

    fun requiresSeparatedOutput(blend: Float): Boolean = !isCentered(blend)

    private const val CENTER_EPSILON = 0.0001f
}
