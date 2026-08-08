package com.mardous.booming.separation.process

import android.os.Process
import android.os.SystemClock

internal fun createSourceSeparationProcessGeneration(): Long =
    (SystemClock.elapsedRealtimeNanos() xor (Process.myPid().toLong() shl 32))
        .and(Long.MAX_VALUE)
        .coerceAtLeast(1L)
