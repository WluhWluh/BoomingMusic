package com.mardous.booming.debug

import android.content.Context
import android.os.Build
import com.mardous.booming.BuildConfig
import com.mardous.booming.playback.Playback
import com.mardous.booming.separation.cache.SourceSeparationCacheDirectories
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteBinderDeathDiagnostics
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteConnectionDiagnostics
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get

internal class SourceSeparationDebugDiagnosticsExporter(
    private val context: Context,
    private val operations: SourceSeparationDebugOperationRegistry,
    private val mediaClient: () -> SourceSeparationDebugMediaClient,
    private val stateSnapshot: () -> JSONObject,
) {
    fun submit(): SourceSeparationDebugOperationSnapshot =
        operations.submit("diagnostics.export") {
            stage("flush_playback_trace")
            val flushResult = mediaClient().send(Playback.FLUSH_SOURCE_SEPARATION_DEBUG_TRACE)
            check(flushResult.resultCode == 0) {
                "PlaybackService rejected debug trace flush (${flushResult.resultCode})."
            }
            val playbackTraceFile = flushResult.extras
                .getString(Playback.EXTRA_DEBUG_TRACE_PATH)
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
            ensureActive()

            stage("capture_state")
            val state = stateSnapshot()
            val process = runCatching {
                get<BoundRemoteSourceSeparationExecutionHost>(
                    BoundRemoteSourceSeparationExecutionHost::class.java,
                ).connectionDiagnostics.toJson()
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    JSONObject().put("captureError", error.message ?: error::class.java.name)
                },
            )
            val manifest = manifest(state, process)
            ensureActive()

            stage("write_archive")
            val outputDirectory = File(
                requireNotNull(context.getExternalFilesDir(null)) {
                    "External app files directory is unavailable."
                },
                "source-separation-debug",
            ).apply { mkdirs() }
            check(outputDirectory.isDirectory && outputDirectory.canWrite()) {
                "Debug export directory is not writable."
            }
            val createdAtEpochMs = System.currentTimeMillis()
            val output = File(outputDirectory, "source-separation-$createdAtEpochMs.zip")
            val staging = File(outputDirectory, ".${output.name}.tmp")
            staging.delete()
            try {
                ZipOutputStream(staging.outputStream().buffered()).use { zip ->
                    zip.putText("manifest.json", manifest.toString(2))
                    zip.putText("state.json", state.toString(2))
                    zip.putText("inference-process.json", process.toString(2))
                    zip.putText(
                        "worker-window-samples.txt",
                        com.mardous.booming.ui.screen.player
                            .SourceSeparationForegroundWorkerDebugBridge.windowSamples(),
                    )
                    addIfPresent(
                        zip,
                        File(SourceSeparationCacheDirectories.diagnostics(context), "cache-diagnostics.jsonl"),
                        "cache-diagnostics.jsonl",
                    )
                    addIfPresent(
                        zip,
                        playbackTraceFile,
                        "playback-gate.log",
                    )
                }
                ensureActive()
                check(staging.renameTo(output)) { "Unable to publish diagnostics archive." }
                pruneOldExports(outputDirectory, output)
            } finally {
                staging.delete()
            }
            JSONObject()
                .put("path", output.absolutePath)
                .put("sizeBytes", output.length())
                .put("sha256", output.sha256())
                .put("createdAtEpochMs", createdAtEpochMs)
                .put("containsAudio", false)
                .put("containsModelOrRuntimePayloads", false)
        }

    private fun manifest(state: JSONObject, process: JSONObject): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("capturedAtEpochMs", System.currentTimeMillis())
        .put("packageName", context.packageName)
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put(
            "device",
            JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("product", Build.PRODUCT)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("fingerprint", Build.FINGERPRINT)
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())),
        )
        .put("currentMediaId", state.optJSONObject("playback")?.optString("mediaId"))
        .put("worker", state.optString("worker"))
        .put("inferenceConnectionState", process.optString("state"))
        .put("containsAudio", false)
        .put("containsModelOrRuntimePayloads", false)

    private fun addIfPresent(zip: ZipOutputStream, source: File?, entryName: String) {
        if (source?.isFile != true) return
        zip.putNextEntry(ZipEntry(entryName))
        source.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun ZipOutputStream.putText(entryName: String, text: String) {
        putNextEntry(ZipEntry(entryName))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun pruneOldExports(directory: File, keep: File) {
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("source-separation-") && file.extension == "zip"
        }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_EXPORTS)
            .filterNot { it == keep }
            .forEach(File::delete)
    }

    private companion object {
        const val MAX_RETAINED_EXPORTS = 5
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val DIAGNOSTICS_JSON = Json {
    prettyPrint = true
    explicitNulls = false
}

private fun SourceSeparationRemoteConnectionDiagnostics.toJson(): JSONObject = JSONObject()
    .put("state", state.name.lowercase())
    .put("processGeneration", processGeneration)
    .put("processName", processName)
    .put("pid", pid)
    .put("processStartTicks", processStartTicks)
    .put("idlePssBytes", idlePssBytes)
    .put("expectedBinderDeathCount", expectedBinderDeathCount)
    .put("unexpectedBinderDeathCount", unexpectedBinderDeathCount)
    .put("staleCallbackDropCount", staleCallbackDropCount)
    .put("bindToConnectedMs", bindToConnectedMs)
    .put("failure", failure)
    .put("lastBinderDeath", lastBinderDeath?.toJson())
    .put("latestProcessDiagnostics", latestProcessDiagnostics?.toJson())

private fun SourceSeparationRemoteBinderDeathDiagnostics.toJson(): JSONObject = JSONObject()
    .put("processGeneration", processGeneration)
    .put("pid", pid)
    .put("processStartTicks", processStartTicks)
    .put("expected", expected)
    .put("recycleToken", recycleToken)
    .put("error", error)

private fun SourceSeparationProcessDiagnostics.toJson(): JSONObject =
    JSONObject(DIAGNOSTICS_JSON.encodeToString(this))
