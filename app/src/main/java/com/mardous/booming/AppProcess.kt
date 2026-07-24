package com.mardous.booming

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

internal data class AppProcessIdentity(
    val processName: String,
    val kind: AppProcessKind,
) {
    val isSourceSeparationProcess: Boolean
        get() = kind == AppProcessKind.SourceSeparation
}

internal enum class AppProcessKind {
    Main,
    SourceSeparation,
    Other,
}

internal object AppProcessResolver {
    const val SOURCE_SEPARATION_PROCESS_SUFFIX = ":source_separation"

    fun resolve(context: Context): AppProcessIdentity {
        val processName = currentProcessName(context)
        return classify(context.packageName, processName)
    }

    fun classify(
        packageName: String,
        processName: String,
    ): AppProcessIdentity {
        require(packageName.isNotBlank()) { "Application package name is empty." }
        require(processName.isNotBlank()) { "Application process name is empty." }
        val kind = when (processName) {
            packageName -> AppProcessKind.Main
            packageName + SOURCE_SEPARATION_PROCESS_SUFFIX ->
                AppProcessKind.SourceSeparation
            else -> AppProcessKind.Other
        }
        return AppProcessIdentity(processName, kind)
    }

    private fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()?.takeIf(String::isNotBlank)?.let { return it }
        }
        val pid = Process.myPid()
        val manager = context.getSystemService(ActivityManager::class.java)
        manager?.runningAppProcesses
            ?.singleOrNull { it.pid == pid }
            ?.processName
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val procName = runCatching {
            File("/proc/self/cmdline").inputStream().buffered().use { input ->
                buildString {
                    while (true) {
                        val value = input.read()
                        if (value <= 0) break
                        append(value.toChar())
                    }
                }
            }
        }.getOrNull()
        return requireNotNull(procName?.takeIf(String::isNotBlank)) {
            "Unable to resolve the current application process."
        }
    }
}
