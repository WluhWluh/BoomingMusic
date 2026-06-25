package com.mardous.booming.separation.model

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

interface MdxOrtSessionProvider {
    fun acquire(
        modelFile: File,
        runtimeSettings: MdxRuntimeSettings,
        modelVariant: MdxModelVariant,
    ): MdxOrtSessionLease
}

class MdxOrtSessionLease(
    val session: OrtSession,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    override fun close() {
        closeAction()
    }
}

object DefaultMdxOrtSessionProvider : MdxOrtSessionProvider {
    override fun acquire(
        modelFile: File,
        runtimeSettings: MdxRuntimeSettings,
        modelVariant: MdxModelVariant,
    ): MdxOrtSessionLease {
        val session = createMdxOrtSession(modelFile, runtimeSettings, modelVariant)
        return MdxOrtSessionLease(session) {
            session.close()
        }
    }
}

class ReusableMdxOrtSessionProvider : MdxOrtSessionProvider, AutoCloseable {
    private var cachedSession: OrtSession? = null
    private var cachedModelPath: String? = null
    private var cachedModelLength: Long = -1L
    private var cachedModelModifiedAt: Long = -1L
    private var cachedRuntimeSettings: MdxRuntimeSettings? = null
    private var cachedModelVariant: MdxModelVariant? = null

    @Synchronized
    override fun acquire(
        modelFile: File,
        runtimeSettings: MdxRuntimeSettings,
        modelVariant: MdxModelVariant,
    ): MdxOrtSessionLease {
        val modelPath = modelFile.absolutePath
        val modelLength = modelFile.length()
        val modelModifiedAt = modelFile.lastModified()
        val session = cachedSession
            ?.takeIf {
                cachedModelPath == modelPath &&
                        cachedModelLength == modelLength &&
                        cachedModelModifiedAt == modelModifiedAt &&
                        cachedRuntimeSettings == runtimeSettings &&
                        cachedModelVariant == modelVariant
            }
            ?: run {
                closeLocked()
                createMdxOrtSession(modelFile, runtimeSettings, modelVariant).also { newSession ->
                    cachedSession = newSession
                    cachedModelPath = modelPath
                    cachedModelLength = modelLength
                    cachedModelModifiedAt = modelModifiedAt
                    cachedRuntimeSettings = runtimeSettings
                    cachedModelVariant = modelVariant
                }
            }
        return MdxOrtSessionLease(session) {
            // The reusable provider owns the session and keeps it warm for the next job.
        }
    }

    @Synchronized
    override fun close() {
        closeLocked()
    }

    private fun closeLocked() {
        cachedSession?.close()
        cachedSession = null
        cachedModelPath = null
        cachedModelLength = -1L
        cachedModelModifiedAt = -1L
        cachedRuntimeSettings = null
        cachedModelVariant = null
    }
}

private fun createMdxOrtSession(
    modelFile: File,
    runtimeSettings: MdxRuntimeSettings,
    modelVariant: MdxModelVariant,
): OrtSession {
    return runtimeSettings.createSessionOptions().use { options ->
        try {
            OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
        } catch (error: Exception) {
            throw SourceSeparationModelLoadException(modelVariant, error)
        }
    }
}
