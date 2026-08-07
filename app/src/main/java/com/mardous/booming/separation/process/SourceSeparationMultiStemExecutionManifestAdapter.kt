package com.mardous.booming.separation.process

import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest

/** Converts the durable cache snapshot into the only wire representation used by a remote host. */
internal fun SourceSeparationCacheManifest.toMultiStemExecutionPreparation():
    SourceSeparationMultiStemExecutionPreparation {
    require(contract.multiTensorContract != null) {
        "A multi-stem execution payload requires a multi-tensor cache contract."
    }
    val output = requireNotNull(output) {
        "A multi-stem execution payload requires a prepared cache output."
    }
    val plan = requireNotNull(segmentPlan) {
        "A multi-stem execution payload requires a segment plan."
    }
    val paths = output.stems.map { stem ->
        SourceSeparationMultiStemExecutionStemPath(
            stemId = stem.stemId,
            order = stem.order,
            path = stem.wavPath,
        )
    }
    return SourceSeparationMultiStemExecutionPreparation(
        stemPaths = paths,
        timingPath = output.timingPath,
        outputFrameCount = output.outputFrameCount,
        outputSampleRate = output.outputSampleRate,
        windowCount = output.windowCount,
        sourceAudioFingerprint = identity.source.audioFingerprint,
        segmentPlan = plan,
    )
}

internal fun SourceSeparationCacheManifest.toMultiStemExecutionCompletion():
    SourceSeparationMultiStemExecutionCompletion = SourceSeparationMultiStemExecutionCompletion(
        preparation = toMultiStemExecutionPreparation(),
        elapsedMs = requireNotNull(output).elapsedMs,
        runtimeRecord = requireNotNull(runtimeRecords.lastOrNull()) {
            "A completed multi-stem cache has no runtime record."
        },
    )
