package com.mardous.booming.debug

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

internal object SourceSeparationDebugProtocol {
    const val VERSION = 1
    const val RESULT_OK = "ok"
    const val RESULT_CODE = "code"
    const val RESULT_MESSAGE = "message"
    const val RESULT_JSON = "json"
    const val RESULT_OPERATION_ID = "operation_id"

    val commands = linkedMapOf(
        "help" to emptyList(),
        "state" to emptyList(),
        "playback.play" to emptyList(),
        "playback.pause" to emptyList(),
        "playback.toggle" to emptyList(),
        "playback.stop" to emptyList(),
        "playback.next" to emptyList(),
        "playback.previous" to emptyList(),
        "playback.seek" to listOf("position_ms:long"),
        "playback.seek_percent" to listOf("percent:float"),
        "playback.song" to listOf(
            "song_id:long|path:string|query:string",
            "first:boolean=false",
            "play:boolean=true",
            "position_ms:long?",
        ),
        "playback.queue" to emptyList(),
        "separation.output" to listOf(
            "enabled:boolean",
            "auto_sync:boolean=true",
            "expect_processing:boolean=<enabled>",
            "blend:float?",
        ),
        "separation.sync" to listOf(
            "allow_new_session:boolean=true",
            "expect_processing:boolean=true",
            "prefer_completed:boolean=false",
        ),
        "separation.blend" to listOf("blend:float", "persist:boolean=true"),
        "separation.start" to emptyList(),
        "separation.resume" to emptyList(),
        "separation.pause" to emptyList(),
        "separation.cancel" to emptyList(),
        "separation.marker" to listOf("marker:string"),
        "separation.samples" to emptyList(),
        "separation.samples.clear" to emptyList(),
        "ui.launch" to emptyList(),
    )

    fun success(
        data: JSONObject = JSONObject(),
        message: String? = null,
        operationId: String? = null,
    ): Bundle = response(
        ok = true,
        code = "ok",
        message = message,
        data = data,
        operationId = operationId,
    )

    fun failure(code: String, message: String, data: JSONObject = JSONObject()): Bundle = response(
        ok = false,
        code = code,
        message = message,
        data = data,
        operationId = null,
    )

    fun help(): Bundle = success(
        JSONObject()
            .put("protocolVersion", VERSION)
            .put(
                "commands",
                JSONArray().apply {
                    commands.forEach { (name, arguments) ->
                        put(
                            JSONObject()
                                .put("name", name)
                                .put("arguments", JSONArray(arguments)),
                        )
                    }
                },
            )
            .put("authoritySuffix", ".debug-control")
            .put("argumentTransport", "content call extras; --arg is accepted as fallback"),
    )

    private fun response(
        ok: Boolean,
        code: String,
        message: String?,
        data: JSONObject,
        operationId: String?,
    ): Bundle {
        val envelope = JSONObject()
            .put("protocolVersion", VERSION)
            .put("ok", ok)
            .put("code", code)
            .put("data", data)
        if (message != null) envelope.put("message", message)
        if (operationId != null) envelope.put("operationId", operationId)
        return Bundle().apply {
            putBoolean(RESULT_OK, ok)
            putString(RESULT_CODE, code)
            message?.let { putString(RESULT_MESSAGE, it) }
            putString(RESULT_JSON, envelope.toString())
            operationId?.let { putString(RESULT_OPERATION_ID, it) }
        }
    }
}
