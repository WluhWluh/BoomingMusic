package com.mardous.booming.separation

import android.content.Context
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.SourceSeparationCacheDirectories
import java.io.File
import java.util.Locale

internal object SourceSeparationDiagnostics {
    private const val CACHE_DIAGNOSTICS_FILE_NAME = "cache-diagnostics.jsonl"
    private const val MAX_CACHE_DIAGNOSTIC_ENTRIES = 500

    private val lock = Any()

    fun recordCacheEvent(
        context: Context,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        synchronized(lock) {
            runCatching {
                val file = cacheDiagnosticsFile(context)
                file.parentFile?.mkdirs()
                file.appendText(
                    jsonLine(event, fields) + "\n",
                    Charsets.UTF_8,
                )
                trimToRecentEntries(file)
            }
        }
    }

    fun songFields(song: Song, prefix: String = "song"): Map<String, Any?> {
        return mapOf(
            "${prefix}Id" to song.id,
            "${prefix}Title" to song.title,
            "${prefix}Uri" to song.uri.toString(),
            "${prefix}Path" to song.data,
            "${prefix}Size" to song.size,
            "${prefix}DurationMs" to song.duration,
            "${prefix}DateModified" to song.rawDateModified,
        )
    }

    private fun cacheDiagnosticsFile(context: Context): File {
        return File(SourceSeparationCacheDirectories.diagnostics(context), CACHE_DIAGNOSTICS_FILE_NAME)
    }

    private fun trimToRecentEntries(file: File) {
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size <= MAX_CACHE_DIAGNOSTIC_ENTRIES) return
        file.writeText(
            lines.takeLast(MAX_CACHE_DIAGNOSTIC_ENTRIES).joinToString(separator = "\n") + "\n",
            Charsets.UTF_8,
        )
    }

    private fun jsonLine(event: String, fields: Map<String, Any?>): String {
        val values = linkedMapOf<String, Any?>(
            "timeMs" to System.currentTimeMillis(),
            "event" to event,
        )
        values.putAll(fields)
        return values.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { (key, value) ->
            "${key.toJsonString()}:${value.toJsonValue()}"
        }
    }

    private fun Any?.toJsonValue(): String {
        return when (this) {
            null -> "null"
            is Boolean -> toString()
            is Byte, is Short, is Int, is Long, is Float, is Double -> {
                val number = this as Number
                if (number is Float && !number.isFinite()) {
                    "null"
                } else if (number is Double && !number.isFinite()) {
                    "null"
                } else {
                    number.toString()
                }
            }
            is Iterable<*> -> joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
            ) { it.toJsonValue() }
            else -> toString().toJsonString()
        }
    }

    private fun String.toJsonString(): String {
        val builder = StringBuilder(length + 2)
        builder.append('"')
        forEach { char ->
            when (char) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        builder.append(
                            "\\u" + char.code.toString(16)
                                .padStart(4, '0')
                                .lowercase(Locale.US),
                        )
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
