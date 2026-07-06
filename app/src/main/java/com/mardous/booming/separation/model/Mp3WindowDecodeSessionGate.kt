package com.mardous.booming.separation.model

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object Mp3WindowDecodeSessionGate {
    const val fallbackReason: String =
        "MP3 window decoding is disabled for this app session after an overlap mismatch."

    private const val TAG = "Mp3WindowDecodeGate"

    private val disabled = AtomicBoolean(false)

    val isDisabled: Boolean
        get() = disabled.get()

    fun disable(reason: String) {
        if (disabled.compareAndSet(false, true)) {
            Log.w(TAG, "$fallbackReason Reason: $reason")
        }
    }
}
