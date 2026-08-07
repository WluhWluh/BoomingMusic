package com.mardous.booming.separation.process.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.mardous.booming.R
import com.mardous.booming.separation.process.SourceSeparationForegroundControlAction
import com.mardous.booming.separation.process.SourceSeparationForegroundDeferredReason
import com.mardous.booming.separation.process.SourceSeparationForegroundExecutionDeferredException
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseLifecycle
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseOperationResult
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseTracker
import com.mardous.booming.separation.process.SourceSeparationForegroundPlatformPolicy
import com.mardous.booming.separation.process.SourceSeparationForegroundServiceDiagnostics
import com.mardous.booming.separation.process.SourceSeparationForegroundStartStage
import com.mardous.booming.separation.process.isSourceSeparationMediaProcessingForegroundServiceType
import com.mardous.booming.separation.process.toForegroundExecutionDeferredException
import com.mardous.booming.ui.screen.MainActivity

internal class SourceSeparationMediaProcessingForegroundController(
    private val service: Service,
    private val tracker: SourceSeparationForegroundLeaseTracker =
        SourceSeparationForegroundLeaseTracker(SystemClock::elapsedRealtimeNanos),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private var latestDeliveredStartId = 0
    private var pendingTimeout: Runnable? = null
    private var lastDeferredStart: DeferredForegroundStart? = null

    @Synchronized
    fun observeStartCommand(startId: Int) {
        if (startId > latestDeliveredStartId) latestDeliveredStartId = startId
    }

    @Synchronized
    fun start(
        request: SourceSeparationForegroundLeaseRequest,
        startId: Int,
    ): SourceSeparationForegroundLeaseOperationResult {
        val result = tracker.started(
            request = request,
            notificationId = NOTIFICATION_ID,
            platformPolicy = platformPolicy(),
        )
        observeStartCommand(startId)
        if (result == SourceSeparationForegroundLeaseOperationResult.AlreadyApplied) {
            if (tracker.diagnostics().activeLease == null) {
                stopStartedLifetime()
                lastDeferredStart
                    ?.takeIf { it.request == request }
                    ?.let { deferred ->
                        throw SourceSeparationForegroundExecutionDeferredException(
                            deferred.reason,
                        )
                    }
            }
            return result
        }
        lastDeferredStart = null
        try {
            createNotificationChannel()
            promote(buildNotification(request))
            schedulePendingTimeout(request)
        } catch (error: Throwable) {
            val deferred = error.toForegroundExecutionDeferredException(
                SourceSeparationForegroundStartStage.Promotion,
            )
            tracker.stop(
                request,
                "promotion-failed:${error::class.java.simpleName}",
            )
            if (deferred != null) {
                lastDeferredStart = DeferredForegroundStart(request, deferred.reason)
            }
            stopPlatformForeground()
            stopStartedLifetime()
            throw deferred ?: error
        }
        return result
    }

    @Synchronized
    fun attach(
        request: SourceSeparationForegroundLeaseRequest,
    ): SourceSeparationForegroundLeaseOperationResult {
        val result = tracker.attach(request)
        if (result == SourceSeparationForegroundLeaseOperationResult.Applied ||
            result == SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        ) {
            clearPendingTimeout()
        }
        return result
    }

    @Synchronized
    fun control(
        request: SourceSeparationForegroundLeaseRequest,
        commandId: String,
        action: SourceSeparationForegroundControlAction,
        startId: Int,
    ): SourceSeparationForegroundLeaseOperationResult {
        val result = tracker.control(request, commandId, action)
        observeStartCommand(startId)
        val active = tracker.diagnostics().activeLease
        if (result == SourceSeparationForegroundLeaseOperationResult.Applied &&
            active?.request == request &&
            active.lifecycle == SourceSeparationForegroundLeaseLifecycle.AwaitingRun
        ) {
            stop(request, "${action.name.lowercase()}-before-admission")
        } else if (active == null) {
            releaseUnownedStart(startId)
        }
        return result
    }

    @Synchronized
    fun stop(
        request: SourceSeparationForegroundLeaseRequest,
        reason: String,
    ): SourceSeparationForegroundLeaseOperationResult {
        val result = tracker.stop(request, reason)
        if (result == SourceSeparationForegroundLeaseOperationResult.Applied) {
            clearPendingTimeout()
            stopPlatformForeground()
            stopStartedLifetime()
        }
        return result
    }

    @Synchronized
    fun stopActive(reason: String) {
        val request = tracker.diagnostics().activeLease?.request ?: return
        stop(request, reason)
    }

    @Synchronized
    fun onTimeout(
        startId: Int,
        foregroundServiceType: Int,
    ): SourceSeparationForegroundLeaseRequest? {
        if (!isSourceSeparationMediaProcessingForegroundServiceType(foregroundServiceType)) {
            return null
        }
        val request = tracker.diagnostics().activeLease?.request
        if (request != null) {
            tracker.timedOut(request, startId, foregroundServiceType)
            lastDeferredStart = DeferredForegroundStart(
                request,
                SourceSeparationForegroundDeferredReason.TimedOut,
            )
        }
        clearPendingTimeout()
        stopPlatformForeground()
        stopStartedLifetime()
        return request
    }

    @Synchronized
    fun diagnostics(): SourceSeparationForegroundServiceDiagnostics = tracker.diagnostics()

    @Synchronized
    fun releaseUnownedStart(startId: Int) {
        observeStartCommand(startId)
        if (tracker.diagnostics().activeLease == null && startId > 0) {
            service.stopSelfResult(startId)
        }
    }

    private fun schedulePendingTimeout(request: SourceSeparationForegroundLeaseRequest) {
        clearPendingTimeout()
        val timeout = Runnable {
            synchronized(this) {
                val active = tracker.diagnostics().activeLease
                if (active?.request == request &&
                    active.lifecycle == SourceSeparationForegroundLeaseLifecycle.AwaitingRun
                ) {
                    stop(request, "admission-timeout")
                }
            }
        }
        pendingTimeout = timeout
        mainHandler.postDelayed(timeout, PENDING_ADMISSION_TIMEOUT_MS)
    }

    private fun clearPendingTimeout() {
        pendingTimeout?.let(mainHandler::removeCallbacks)
        pendingTimeout = null
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopPlatformForeground() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun stopStartedLifetime() {
        val startId = latestDeliveredStartId
        latestDeliveredStartId = 0
        if (startId > 0) {
            service.stopSelfResult(startId)
        } else {
            service.stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val manager = requireNotNull(service.getSystemService<NotificationManager>()) {
            "Notification manager is unavailable."
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.action_source_separation_settings),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = service.getString(R.string.source_separation_processing_windows)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(
        request: SourceSeparationForegroundLeaseRequest,
    ): Notification = NotificationCompat.Builder(service, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_music_playback)
        .setContentTitle(service.getString(R.string.source_separation_processing_windows))
        .setContentText(request.displayName)
        .setContentIntent(contentIntent())
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .addAction(
            R.drawable.ic_pause_24dp,
            service.getString(R.string.action_pause),
            controlIntent(request, SourceSeparationForegroundControlAction.Pause),
        )
        .addAction(
            R.drawable.ic_cancel_24dp,
            service.getString(R.string.action_cancel),
            controlIntent(request, SourceSeparationForegroundControlAction.Cancel),
        )
        .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        service,
        CONTENT_REQUEST_CODE,
        Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun controlIntent(
        request: SourceSeparationForegroundLeaseRequest,
        action: SourceSeparationForegroundControlAction,
    ): PendingIntent {
        val commandId = "notification-${action.name.lowercase()}-${request.leaseId}"
        val intent = SourceSeparationExecutionService.foregroundControlIntent(
            service,
            request,
            commandId,
            action,
        )
        return PendingIntent.getService(
            service,
            pendingIntentRequestCode(request, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingIntentRequestCode(
        request: SourceSeparationForegroundLeaseRequest,
        action: SourceSeparationForegroundControlAction,
    ): Int = (31 * request.leaseId.hashCode() + action.ordinal)
        .and(Int.MAX_VALUE)

    private fun platformPolicy() = if (Build.VERSION.SDK_INT >= 35) {
        SourceSeparationForegroundPlatformPolicy.TimedMediaProcessing
    } else {
        SourceSeparationForegroundPlatformPolicy.LegacyMediaProcessing
    }

    companion object {
        const val NOTIFICATION_ID = 21_331
        const val CHANNEL_ID = "source_separation_processing"
        private const val CONTENT_REQUEST_CODE = 21_330
        private const val PENDING_ADMISSION_TIMEOUT_MS = 15_000L
    }

    private data class DeferredForegroundStart(
        val request: SourceSeparationForegroundLeaseRequest,
        val reason: SourceSeparationForegroundDeferredReason,
    )
}
