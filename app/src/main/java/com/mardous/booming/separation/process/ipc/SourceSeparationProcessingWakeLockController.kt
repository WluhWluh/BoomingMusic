package com.mardous.booming.separation.process.ipc

import android.app.Service
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockOperationResult
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockTracker

internal class SourceSeparationProcessingWakeLockController(
    private val service: Service,
    private val tracker: SourceSeparationProcessingWakeLockTracker =
        SourceSeparationProcessingWakeLockTracker(SystemClock::elapsedRealtimeNanos),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onLeaseLost: (SourceSeparationForegroundLeaseRequest, String) -> Unit,
) {
    private var platformWakeLock: PowerManager.WakeLock? = null
    private var renewal: Runnable? = null
    private var orphanReleaseRetry: Runnable? = null

    @Synchronized
    fun acquire(
        request: SourceSeparationForegroundLeaseRequest,
    ): SourceSeparationProcessingWakeLockOperationResult {
        if (tracker.diagnostics(platformHeld()).activeLease != null) {
            val result = tracker.acquire(request, wakeLockTag(), HOLD_TIMEOUT_MS)
            require(platformWakeLock?.isHeld == true) {
                "The processing wake-lock lease is active without a platform lock."
            }
            return result
        }
        require(!platformHeld()) {
            "A prior platform processing wake lock has not been released."
        }
        clearOrphanReleaseRetry()
        platformWakeLock = null
        val result = tracker.acquire(request, wakeLockTag(), HOLD_TIMEOUT_MS)
        require(result == SourceSeparationProcessingWakeLockOperationResult.Applied) {
            "A released processing wake-lock lease cannot be reacquired."
        }
        try {
            val manager = requireNotNull(service.getSystemService<PowerManager>()) {
                "Power manager is unavailable."
            }
            val wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                wakeLockTag(),
            ).apply {
                setReferenceCounted(false)
                acquire(HOLD_TIMEOUT_MS)
            }
            require(wakeLock.isHeld) { "The processing wake lock was not acquired." }
            platformWakeLock = wakeLock
            scheduleRenewal(request)
        } catch (error: Throwable) {
            tracker.release(request, "acquire-failed:${error::class.java.simpleName}")
            releasePlatformLock()
            throw error
        }
        return result
    }

    @Synchronized
    fun release(
        request: SourceSeparationForegroundLeaseRequest,
        reason: String,
    ): SourceSeparationProcessingWakeLockOperationResult {
        val result = tracker.release(request, reason)
        if (result == SourceSeparationProcessingWakeLockOperationResult.Applied ||
            result == SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied
        ) {
            clearRenewal()
            releasePlatformLock()
        }
        return result
    }

    @Synchronized
    fun releaseActive(reason: String) {
        val request = tracker.diagnostics(platformHeld()).activeLease?.request
        if (request != null) {
            release(request, reason)
        } else {
            clearRenewal()
            releasePlatformLock()
        }
    }

    @Synchronized
    fun diagnostics(): SourceSeparationProcessingWakeLockDiagnostics =
        tracker.diagnostics(platformHeld())

    private fun scheduleRenewal(request: SourceSeparationForegroundLeaseRequest) {
        clearRenewal()
        val task = Runnable { renew(request) }
        renewal = task
        mainHandler.postDelayed(task, RENEW_INTERVAL_MS)
    }

    private fun renew(request: SourceSeparationForegroundLeaseRequest) {
        var lostReason: String? = null
        synchronized(this) {
            val active = tracker.diagnostics(platformHeld()).activeLease
            if (active?.request != request) return
            val wakeLock = platformWakeLock
            if (wakeLock?.isHeld != true) {
                lostReason = "platform-lock-expired-before-renewal"
            } else {
                try {
                    wakeLock.release()
                    wakeLock.acquire(HOLD_TIMEOUT_MS)
                    require(wakeLock.isHeld) {
                        "The processing wake lock was not reacquired."
                    }
                    tracker.renew(request, HOLD_TIMEOUT_MS)
                    scheduleRenewal(request)
                } catch (error: Throwable) {
                    lostReason = "renewal-failed:${error::class.java.simpleName}"
                }
            }
            lostReason?.let { reason ->
                tracker.release(request, reason)
                clearRenewal()
                releasePlatformLock()
            }
        }
        lostReason?.let { onLeaseLost(request, it) }
    }

    private fun clearRenewal() {
        renewal?.let(mainHandler::removeCallbacks)
        renewal = null
    }

    private fun releasePlatformLock() {
        val wakeLock = platformWakeLock ?: return
        if (wakeLock.isHeld) {
            runCatching(wakeLock::release)
        }
        if (wakeLock.isHeld) {
            scheduleOrphanReleaseRetry(wakeLock)
        } else {
            clearOrphanReleaseRetry()
            platformWakeLock = null
        }
    }

    private fun scheduleOrphanReleaseRetry(wakeLock: PowerManager.WakeLock) {
        clearOrphanReleaseRetry()
        val retry = Runnable {
            synchronized(this) {
                if (platformWakeLock !== wakeLock) return@synchronized
                if (wakeLock.isHeld) runCatching(wakeLock::release)
                if (wakeLock.isHeld) {
                    scheduleOrphanReleaseRetry(wakeLock)
                } else {
                    platformWakeLock = null
                    orphanReleaseRetry = null
                }
            }
        }
        orphanReleaseRetry = retry
        mainHandler.postDelayed(retry, ORPHAN_RELEASE_RETRY_MS)
    }

    private fun clearOrphanReleaseRetry() {
        orphanReleaseRetry?.let(mainHandler::removeCallbacks)
        orphanReleaseRetry = null
    }

    private fun platformHeld(): Boolean = platformWakeLock?.isHeld == true

    private fun wakeLockTag() = "${service.packageName}:SourceSeparationInference"

    companion object {
        const val HOLD_TIMEOUT_MS = 10L * 60L * 1_000L
        const val RENEW_INTERVAL_MS = 5L * 60L * 1_000L
        private const val ORPHAN_RELEASE_RETRY_MS = 1_000L
    }
}
