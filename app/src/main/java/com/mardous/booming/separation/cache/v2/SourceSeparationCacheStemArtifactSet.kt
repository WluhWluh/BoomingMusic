package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegment
import com.mardous.booming.separation.cache.SourceSeparationSegmentState

internal fun SourceSeparationSegment.hasCompleteReadyArtifactSet(
    store: SourceSeparationCacheStore,
    cacheKey: String,
    playbackFrame: Int? = null,
): Boolean {
    val playable = state.isPlaybackReady ||
        (state == SourceSeparationSegmentState.Provisional &&
            playbackFrame != null &&
            playableFromFrame != null &&
            playbackFrame >= playableFromFrame)
    return playable && stems.all { stem ->
        store.resolveEntryPath(cacheKey, stem.path).isFile
    }
}

internal fun SourceSeparationSegment.captureCommittedArtifactSet(
    store: SourceSeparationCacheStore,
    cacheKey: String,
): SourceSeparationCacheCommittedSegment = SourceSeparationCacheCommittedSegment(
    segmentIndex = index,
    stems = stems.map { stem ->
        SourceSeparationCacheCommittedStem(
            stemId = stem.stemId,
            order = stem.order,
            path = stem.path,
            integrity = store.fileIntegrity(store.resolveEntryPath(cacheKey, stem.path)),
        )
    },
)

internal fun SourceSeparationCacheCommittedSegment.matches(
    segment: SourceSeparationSegment,
): Boolean = segmentIndex == segment.index &&
    stems.map { it.stemId to it.path } == segment.stems.map { it.stemId to it.path }

internal fun SourceSeparationCacheCommittedSegment.hasValidArtifactSet(
    store: SourceSeparationCacheStore,
    cacheKey: String,
): Boolean = stems.all { stem ->
    store.validateIntegrity(
        cacheKey = cacheKey,
        relativePath = stem.path,
        expected = stem.integrity,
    )
}

internal fun SourceSeparationSegment.deleteArtifactSet(
    store: SourceSeparationCacheStore,
    cacheKey: String,
) {
    stems.forEach { stem -> store.deleteRelativePath(cacheKey, stem.path) }
}
