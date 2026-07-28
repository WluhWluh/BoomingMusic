package com.mardous.booming.separation.process.ipc

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.mardous.booming.separation.process.SourceSeparationProcParser
import java.io.File

internal class SourceSeparationDeadForegroundResourceCleaner(
    private val processStartTicks: (Int) -> Long?,
    private val stopService: () -> Unit,
    private val cancelNotification: () -> Unit,
) {
    fun cleanupIfProcessDied(
        foregroundPolicy: SourceSeparationRemoteForegroundPolicy,
        pid: Int?,
        expectedProcessStartTicks: Long?,
    ): Boolean {
        if (foregroundPolicy != SourceSeparationRemoteForegroundPolicy.ManualFullSong ||
            pid == null ||
            expectedProcessStartTicks == null ||
            processStartTicks(pid) == expectedProcessStartTicks
        ) {
            return false
        }
        runCatching(stopService)
        runCatching(cancelNotification)
        return true
    }

    companion object {
        fun create(context: Context): SourceSeparationDeadForegroundResourceCleaner {
            val applicationContext = context.applicationContext
            return SourceSeparationDeadForegroundResourceCleaner(
                processStartTicks = { pid ->
                    runCatching {
                        SourceSeparationProcParser.parseProcessStartTicks(
                            File("/proc/$pid/stat").readText(),
                        )
                    }.getOrNull()
                },
                stopService = {
                    applicationContext.stopService(
                        Intent(
                            applicationContext,
                            SourceSeparationExecutionService::class.java,
                        )
                    )
                },
                cancelNotification = {
                    applicationContext.getSystemService(NotificationManager::class.java)
                        ?.cancel(SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID)
                },
            )
        }
    }
}
