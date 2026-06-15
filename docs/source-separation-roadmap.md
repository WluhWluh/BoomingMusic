# Source Separation Experiment Roadmap

## Purpose

This document tracks the personal experimental branch for adding on-device two-stem source separation to Booming Music. The feature is intended for private use first and is not designed as an upstream-ready contribution at this stage.

The target experience is:

- The user starts playback normally.
- The expanded player exposes a source separation action.
- When separation is enabled, the app computes vocals and instrumental stems on device.
- A horizontal blend slider appears:
  - one end outputs vocals only,
  - the other end outputs instrumental only,
  - the center plays both stems at full level, approximating the original mix.
- Playback should not require waiting for the entire song to finish processing.
- If the current playback position is not ready, the UI should show a processing state and resume automatically when enough nearby audio is ready.
- Completed stems should be cached in app-private storage and reused on later plays.
- The user should be able to find songs with cached separated stems and delete those cached files.

## Reference Implementation

The starting technical reference is the separate local prototype at:

`C:\Users\User\Documents\MusicSourceSeparation`

Important reference components:

- `docs/model_contracts.md`
- `app/src/main/java/com/example/musicsourceseparation/model/MdxDspConfig.kt`
- `app/src/main/java/com/example/musicsourceseparation/model/MdxSpectrogram.kt`
- `app/src/main/java/com/example/musicsourceseparation/model/MdxRangeSeparator.kt`
- `app/src/main/java/com/example/musicsourceseparation/model/MdxRuntimeSettings.kt`
- `app/src/main/java/com/example/musicsourceseparation/audio/AudioPcmDecoder.kt`
- `app/src/main/java/com/example/musicsourceseparation/audio/WavFileWriter.kt`

The preferred first model is `UVR_MDXNET_9482.onnx` because real-device testing on a Samsung S25 showed good enough quality with roughly quarter-duration processing time for full-song output.

## Product Scope

### In Scope

- Android-only, fully on-device separation.
- Two stems only: vocals and instrumental.
- Personal builds may bundle or locally load the ONNX model.
- Integration with the existing Booming Music playback UI.
- Current-song separation from the player screen.
- Playback-position-prioritized chunk scheduling.
- Seeking while processing.
- Cache reuse for completed songs.
- Cache management UI for deletion.
- Background continuation when playback is ongoing and the system allows it.

### Out of Scope for the First Experimental Pass

- Upstream PR readiness.
- Cloud processing.
- Batch separation.
- Four-stem models.
- Model download marketplace or model management UX.
- Perfect gapless behavior across every codec/device combination.
- Android Auto-specific source separation controls.
- Public redistribution until model licensing is clarified.

## Key Technical Assumptions

- Booming Music uses Media3/ExoPlayer through `PlaybackService`.
- The current playback service already has a custom renderer factory and audio processors, but source separation should not be implemented as a simple `AudioProcessor` unless timestamp alignment is solved.
- The MDX pipeline requires 44.1 kHz stereo PCM and STFT/ISTFT conversion around ONNX inference.
- The first implementation can accept temporary WAV segment caches for correctness.
- Compressed stem storage should come after playback correctness, because MP3/AAC encoder delay and availability can complicate stem alignment.
- App-private storage is the safest first cache location.

## Proposed Architecture

### Core Modules

- `separation/model`
  - model variant metadata,
  - ONNX model loading,
  - runtime settings,
  - session lifecycle.
- `separation/dsp`
  - MDX DSP config,
  - STFT/ISTFT,
  - stem reconstruction,
  - sample conversion helpers.
- `separation/audio`
  - PCM decoding,
  - optional segment decoding,
  - WAV writing,
  - future compressed output encoding.
- `separation/cache`
  - cache key generation,
  - segment file layout,
  - completed stem file layout,
  - cache index persistence.
- `separation/worker`
  - task queue,
  - playback-position-prioritized scheduling,
  - cancellation,
  - progress state.
- `separation/playback`
  - separated playback mode,
  - stem blending,
  - readiness gating,
  - seek handling.
- `separation/ui`
  - player action,
  - blend slider,
  - processing indicators,
  - cache management entry points.

### Cache Identity

Cache entries should not be keyed by song ID alone. The first practical key should include:

- song ID,
- file path or media URI,
- file size,
- raw modified timestamp,
- duration,
- model variant,
- pipeline version.

This should prevent accidental reuse after a local file is replaced or the separation algorithm changes.

### Segment Model

Each song should be split into model-aligned playable segments. The scheduler should track:

- `Missing`
- `Queued`
- `Running`
- `Ready`
- `Failed`

The scheduler should prioritize:

1. segment at the current playback position,
2. next segment,
3. following near-future segments,
4. earlier missing segments,
5. remaining tail segments.

When the user seeks, the queue should reprioritize around the new playback position.

## Development Phases

### Phase 0: Branch and Planning

Status: in progress

Goals:

- Create a dedicated experimental branch.
- Add this roadmap.
- Keep all project documentation in English.

Done criteria:

- The branch exists.
- This document is committed.
- No runtime behavior changes are included in this phase.

### Phase 1: Import the Offline Separation Engine

Status: pending

Goals:

- Add ONNX Runtime Android and JTransforms dependencies.
- Add the MDX model/DSP classes from the prototype with Booming package names.
- Add local model loading for personal builds.
- Add a small internal API that can separate one selected song into vocals and instrumental WAV files in app-private storage.

Done criteria:

- The app builds with the new dependencies.
- A debug-only or hidden entry point can separate the current song to WAV files.
- Output stems sound correct on at least one known test song.
- The existing playback path still works when separation is unused.

### Phase 2: Cache Index and File Layout

Status: pending

Goals:

- Define app-private cache directories.
- Add a persistent cache index.
- Record model variant, source fingerprint, output files, state, created time, and total size.
- Add safe cleanup for failed or canceled runs.

Done criteria:

- Completed output can be discovered after app restart.
- Replaced source files do not accidentally reuse stale stems.
- Failed partial output does not appear as completed cache.

### Phase 3: Current-Song Manual Separation

Status: pending

Goals:

- Add a player-screen action to start separation for the current song.
- Show progress without changing playback output yet.
- Allow cancellation.
- Keep work off the main thread.

Done criteria:

- Starting separation from the player screen works.
- Progress is visible.
- Cancellation leaves no broken completed cache entry.
- Playback remains usable during processing.

### Phase 4: Basic Completed-Stem Playback Mode

Status: pending

Goals:

- For songs with completed cached stems, enable separated playback mode.
- Add the vocals/instrumental blend slider.
- Mix the two completed stems while preserving player controls, seek, pause, resume, and notification behavior as much as possible.

Done criteria:

- Completed stems can be played through the app.
- The blend slider works during playback.
- Seeking remains accurate enough for daily use.
- Returning to normal playback is possible.

### Phase 5: Segment-Based Processing

Status: pending

Goals:

- Replace full-song-only processing with segment state tracking.
- Write ready segments independently.
- Make current position and next segment the highest-priority work.
- Support reprioritization after seek.

Done criteria:

- The app can process a song incrementally.
- Segment readiness is persisted.
- Seeking to an unready section reprioritizes that section.
- Already computed segments are reused.

### Phase 6: Play While Processing

Status: pending

Goals:

- Start separated playback once the current segment and the next segment are ready.
- Pause or visually gate separated output when the requested position is not ready.
- Automatically resume when enough data is available.
- Avoid disruptive playback jumps when segment boundaries become ready.

Done criteria:

- The user does not need to wait for whole-song completion.
- If the playback head outruns available separated audio, the UI clearly shows processing.
- Playback resumes automatically when ready.
- User seeking during processing remains responsive.

### Phase 7: Background and Thermal Behavior

Status: pending

Goals:

- Decide whether separation runs inside the existing playback service or a dedicated foreground service.
- Keep processing alive while playback is ongoing and the screen is off when possible.
- Add throttling or pause behavior for thermal/battery stress if needed.
- Avoid competing too aggressively with ExoPlayer's audio thread.

Done criteria:

- Screen-off playback plus separation is stable on the Samsung S25.
- The app does not stutter during inference.
- Long-running work has an appropriate notification or foreground-service strategy.

### Phase 8: Compression and Final Cache Files

Status: pending

Goals:

- Evaluate MP3, AAC/M4A, or another compressed cache format.
- Verify stem alignment after encoding and decoding.
- Keep WAV caches while processing, then atomically promote compressed outputs after full-song completion.
- Delete temporary WAV files after successful compression.

Done criteria:

- Completed songs use compressed cache files.
- Compression does not introduce audible stem desync.
- Temporary WAV files are cleaned up safely.
- Playback can still use partial WAV segments while a song is not fully complete.

### Phase 9: Cache Management UX

Status: pending

Goals:

- Add a way to list songs with separated caches.
- Show cache size and model/pipeline information.
- Allow deleting a single song's separated cache.
- Consider an optional "delete all separated tracks" action.

Done criteria:

- The user can find all cached separated songs.
- Deleting cache does not delete original music.
- Storage usage is visible enough for personal maintenance.

### Phase 10: Polish and Hardening

Status: pending

Goals:

- Improve error messages.
- Handle unsupported source codecs.
- Handle low storage.
- Handle model missing/corrupt cases.
- Add logging around scheduler decisions.
- Add focused tests for DSP math, cache keys, and scheduler priority.

Done criteria:

- The feature is reliable enough for daily personal use.
- Common failure states are understandable.
- The experiment can be paused and resumed without losing track of what works.

## Major Risks

- Model licensing is unclear for redistribution.
- ONNX Runtime and bundled models will significantly increase APK size.
- MP3 encoding may require native libraries and licensing review.
- Compressed stems may drift because of encoder delay or padding.
- Two independent players are likely to drift and should be avoided unless the Media3 custom-source path proves too expensive.
- Running inference while playing audio may cause thermal throttling or playback stutter.
- The prototype currently decodes full songs into memory; a production-like path may need segment or streaming decode.
- Accurate segment boundary playback will be the hardest part of the project.

## First Implementation Preference

The first useful milestone should be deliberately modest:

1. Import the proven offline engine.
2. Separate the current song to app-private WAV files.
3. Add cache indexing.
4. Play completed cached stems with a blend slider.
5. Only then attempt play-while-processing.

This keeps the project useful at each stage and avoids mixing the most fragile playback work with the initial model integration.

