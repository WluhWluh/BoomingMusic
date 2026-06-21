# Source Separation Experiment Roadmap

## Purpose

This document tracks the personal experimental branch for adding on-device two-stem source separation to Booming Music. The feature is intended for private use first and is not designed as an upstream-ready contribution at this stage.

The target experience is:

- The user starts playback normally.
- The expanded player exposes a source separation settings entry.
- The entry opens a source separation settings sheet; its icon reflects the active separated playback state.
- When separation is enabled, the app computes vocals and instrumental stems on device.
- The settings sheet exposes a horizontal blend slider:
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
- The first implementation can accept temporary WAV or raw PCM segment caches for correctness.
- Segment output should be built from each MDX window's trimmed stable region, then joined on exact sample boundaries.
- Playback should not use crossfades as the primary segment-joining strategy. Very short anti-click fades may be considered only if measured or audible boundary artifacts remain.
- Completed stem storage should default to FLAC because it preserves sample-accurate alignment while saving space compared with WAV.
- Opus can be evaluated later as an optional small-size mode. MP3 should not be a first target because Android does not provide a reliable platform MP3 encoder and external encoders add size, delay, and licensing complexity.
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
  - future FLAC output encoding,
  - optional Opus output experiments.
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
  - player settings entry,
  - settings bottom sheet,
  - blend slider,
  - processing indicators,
  - current-song progress and cache controls,
  - cache management entry points.

### Cache Identity

Cache entries should not be invalidated by ordinary metadata edits. Booming Music can edit tags such as artist, album, lyrics, and cover art; those changes may rewrite the source file and update file size or modification time even when the decoded audio content is unchanged.

The cache model should therefore separate song location from audio identity.

Song locator fields help find and display the cache entry:

- song ID,
- file path,
- media URI,
- title,
- artist,
- album.

These fields may change without forcing recomputation.

Audio identity fields decide whether separated stems remain valid:

- audio fingerprint,
- decoded frame count,
- decoded sample rate,
- decoded channel layout,
- model variant,
- pipeline version.

The current implementation computes `audioFingerprint` from the selected encoded audio track samples through `MediaExtractor`, plus decoder-relevant track fields such as MIME type, sample rate, channel count, duration, encoder delay, and encoder padding. This skips container metadata such as cover art, lyrics, and tags, so metadata-only rewrites should not invalidate separated caches. A decoded PCM hash remains a possible fallback strategy if a platform extractor proves unreliable for a specific format.

File size and raw modified timestamp should be stored as diagnostic fields in the manifest, but they should not be used as mandatory cache invalidation inputs.

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

Segments are a sample-accurate cache format, not independent user-facing songs. Each segment should record:

- global start frame,
- global end frame,
- model input window frame range,
- stable output frame range,
- sample rate,
- channel count,
- stem file paths or byte ranges.

The MDX model already uses overlap and trim internally. For each model window, only the stable center region should be written into the segment timeline. Adjacent stable regions should be concatenated by exact frame index. They should not be crossfaded by default.

### Playback Change and Partial Cache Strategy

If the user changes songs while the previous song is still being separated, the app should preserve completed partial work but immediately prioritize the newly playing song.

Default behavior:

- Keep completed stable segments for the previous song.
- Do not delete partial cache merely because playback moved away.
- Cancel or drop queued segments for the previous song.
- Let an already running model window finish only if safe cancellation is not available yet.
- Move the new current song's playback-position segment and next segment to the front of the queue.
- Pause old-song processing by default after the active song changes.
- Resume old-song processing only as low-priority idle work after active playback needs are satisfied, if thermal and battery conditions allow it.

Future segment manifests should support at least:

- `Partial` song-level cache state,
- per-segment `Missing`, `Queued`, `Running`, `Ready`, and `Failed`,
- completed frame ranges,
- `lastActiveAtEpochMs`,
- priority reason such as `CurrentPlayback`, `NextPlayback`, `NearFuture`, or `IdleBackfill`.

This keeps playback responsive and avoids spending full CPU on songs the user has stopped listening to, while still preserving useful work for later reuse.

### Separated Playback Settings and Icon State

Separated playback should be controlled from a source separation settings sheet instead of by directly cycling a player button. The player-screen source separation button is an entry point and state indicator:

- tapping it always opens the source separation settings sheet,
- it does not directly change the separated playback mode,
- its icon is derived from the current settings.

The user-facing behavior is still equivalent to three playback states:

- `Off`: the default state. Playback always uses the original source audio file, even if completed separated stems already exist. Stem playback and mixing are not activated. The entry uses the outline blend icon.
- `Global`: playback uses completed separated stems when they are available. The blend slider reads and writes one app-level global blend value. Per-song memory is ignored in this state, so changing the blend affects every completed-cache song played while global blending is active.
- `PerSong`: playback uses completed separated stems when they are available. The blend slider reads and writes a per-song blend value. The app-level global blend is ignored in this state. If the current song has no stored per-song blend yet, playback should default to the neutral center blend where both stems are fully present.

The durable settings should be modeled as two simple concepts:

- app-level separated playback enabled flag,
- app-level per-song blend memory enabled flag.

The three icon states can then be derived from those concepts:

- disabled playback -> `Off`,
- enabled playback without per-song memory -> `Global`,
- enabled playback with per-song memory -> `PerSong`.

Blend values should be persisted separately from these mode settings:

- app-level global blend value,
- per-song blend value stored with the separated cache entry or a cache-owned playback settings sidecar.

Per-song blend memory should follow the valid separated cache identity instead of volatile library metadata. Metadata-only edits should not erase the per-song blend if the completed stems still match the decoded audio identity. Deleting a song's separated cache should delete its per-song blend memory as well.

On song transitions:

- `Off` mode keeps playback on the original media item.
- `Global` mode automatically uses completed stems with the global blend when a completed cache exists.
- `PerSong` mode automatically uses completed stems with that song's saved blend, or the neutral center blend if no saved value exists.
- If no completed cache exists in `Global` or `PerSong` mode, playback should fall back to the original source without changing the selected mode.

The source separation entry should use icon shape, not disabled alpha or brightness, to communicate state:

- `ic_stem_blend_outline_24dp` for `Off`,
- `ic_stem_blend_24dp` for `Global`,
- `ic_stem_blend_per_song_24dp` for `PerSong`,
- no toast is shown when the settings change,
- all three modes use the normal tint/background treatment of their surrounding player controls.

### Source Separation Settings Sheet Strategy

The source separation UI should move toward a dedicated bottom sheet that matches the existing Sound Settings sheet style. The preferred structure is:

- a `BottomSheetDialogFragment`,
- Compose content wrapped in `BoomingMusicTheme`,
- `BottomSheetDialogSurface`,
- `LazyColumn`,
- `TitledCard`,
- labeled switches matching Sound Settings rows,
- a centered Material slider matching the Sound Settings balance control.

Near-term work should avoid building the final full UI before the segment cache and live playback state model exists. The first UI step should be a small settings-entry skeleton:

- rename the current `source_separation_blend_mode_button` concept to a source separation settings entry such as `action_source_separation_settings` or `source_separation_settings_button`,
- make the entry open the sheet instead of cycling playback mode,
- keep the three icon states as passive reflections of the real settings,
- expose only controls that already have reliable backing state,
- keep long-running progress behavior unchanged until the sheet has a stable progress model.

The final sheet should include:

- master switch for separated playback,
- switch for remembering blend per song,
- vocals/instrumental blend slider,
- current song separation status,
- current segment and near-future segment readiness once segment processing exists,
- pause or resume controls only after pause can preserve partial work,
- cancel or stop controls for discardable full-song jobs,
- delete-current-song-cache action,
- links or entry points to broader cache management.

The existing overflow actions should be removed only after the sheet can replace them safely:

- `Separate vocals`,
- `Separated playback`.

The settings sheet should be the primary progress surface. Legacy source-separation Snackbar messages, including preparation, window progress, completion, cancellation, and failure, may be retained only behind an explicit advanced debug setting.

True pause should not be exposed until partial segment manifests can preserve completed work. The current full-song WAV job can be canceled, but cancellation deletes temporary work; labeling that action as "Pause" would be misleading.

### Cover Lyrics Quick Blend Control

The lyrics-on-cover surface can host a compact source-separation quick control above the existing full-lyrics button. This should not replace the full source separation settings sheet; it is a fast path for the most common listening adjustment while lyrics are visible.

The implementation should live in the shared Compose cover lyrics layer, not in each player style layout:

- `CoverLyricsScreen` already owns the full-lyrics circular button and is reused by the player styles that support cover lyrics.
- The quick control should be stacked with the full-lyrics button in one bottom-end overlay column.
- Player styles that do not support cover lyrics, such as FullCover and Peek, do not need this control in the first pass.

The closed state should be a circular `FilledIconButton` matching the existing full-lyrics button style:

- icon: `ic_stem_blend_outline_24dp`,
- size and bottom/end margin: match the full-lyrics button,
- click behavior: enable separated playback using the current active blend, then morph or swap into the expanded vertical blend control.

The expanded state should be a dedicated custom Compose control rather than a rotated Material `Slider`:

- width: the same as the circular full-lyrics button,
- height: three times that width,
- shape: two separated vertical capsule segments with a small center gap at the neutral blend point,
- the center cut ends should use a small corner radius so the split track still feels polished,
- no Material slider thumb and no current-position handle,
- the neutral 50% blend state should show no active fill,
- progress fill should start at the center gap and extend toward the vocals end or instrumental end depending on the active blend,
- the inactive track should match Material Slider inactive color behavior by using the same base color at low alpha,
- top and bottom endpoint icons should currently reuse `ic_person_24dp` and `ic_speaker_24dp`,
- colors should follow the active `PlayerTheme`; the first visual prototype uses `onSurface` as the base track color for both filled and inactive segments.

Reason for a custom control:

- The settings sheet's Stem blend slider uses a horizontal Material3 `Slider` plus `SliderDefaults.CenteredTrack`.
- The quick control needs a vertical track, no thumb, custom tap zones, custom drag snapping, and overlaid endpoint icons.
- Rotating the existing slider would make pointer mapping, thumb removal, semantics, and layout harder than a small purpose-built Canvas/pointer-input control.

Current implementation status:

- The cover lyrics overlay now uses a bottom-end column that stacks the source-separation quick control above the full-lyrics button.
- The quick control switches visually with `sourceSeparationBlendModeFlow`: `Off` shows a circular outline stem-blend button, and enabled modes show the expanded vertical preview.
- The expanded control is 40dp wide and 120dp tall, split into two 58dp track segments with a 4dp neutral center gap.
- The outer ends are capsule-rounded; the center cut ends use 2dp corner radii.
- The closed-to-expanded transition is implemented as a custom morph on the source-separation quick control only; other circular player buttons still use their original Material button implementation.
- The control now renders the actual blend value: the neutral 50% state has no active fill, vocals-only fills upward from the center gap, and instrumental-only fills downward from the center gap.
- The closed state click enables separated playback through `PlayerViewModel.setSourceSeparationPlaybackEnabled`.
- The expanded state supports drag-to-blend with a small midpoint snap region and three-zone tap handling: top sets vocals-only, middle turns separated playback off without changing the stored blend, and bottom sets instrumental-only.
- Drag rendering uses a local in-control blend value for immediate visual feedback. Audio preview updates are throttled through a lightweight ViewModel path, while drag finish and tap endpoints still use the formal persisted blend path.
- The lyrics bottom avoidance is already content-padding based and changes with the quick-control visual height, so the lyrics viewport is not shortened.

State synchronization should use the same source of truth as the settings sheet:

- collect `sourceSeparationBlendModeFlow` to decide whether the quick control is closed or expanded,
- collect `sourceSeparationPlaybackStateFlow.blend` for the current displayed blend value,
- call `setSourceSeparationPlaybackEnabled(true, blend)` when the closed button is pressed,
- call `setSourceSeparationBlend(value)` during drag updates and at drag finish,
- call `setSourceSeparationPlaybackEnabled(false)` when the center tap zone is pressed,
- do not create an independent quick-control blend setting.

Interaction rules:

- Dragging inside the expanded vertical control continuously maps pointer Y to blend.
- The top maps to the vocals-only end and the bottom maps to the instrumental-only end, matching the horizontal sheet labels.
- A small snap region around the vertical midpoint should snap the value to the neutral center blend.
- Tapping is handled differently from dragging:
  - top third: set blend to vocals-only,
  - middle third: turn separated playback off without changing the stored blend,
  - bottom third: set blend to instrumental-only.
- The control should distinguish tap vs drag with touch slop so a slight finger movement does not accidentally invoke the three-zone tap behavior.

Lyrics bottom spacing should remain content-padding based:

- Do not shrink the lyrics viewport by adding outer bottom padding to `LyricsSurface`.
- Instead, increase only the scrollable lyrics content's bottom padding so the last line can scroll above the overlay.
- The extra bottom padding should be dynamic:
  - closed quick button + full-lyrics button: enough for two stacked circular buttons plus spacing and margin,
  - expanded quick blend control + full-lyrics button: enough for the vertical control, full-lyrics button, spacing, and margin.
- The same padding override should apply to both plain lyrics and synced lyrics.

Suggested first implementation steps:

1. Keep the current content-padding-based lyrics-bottom-avoidance experiment and parameterize the extra bottom padding by overlay height.
2. Replace the single full-lyrics button overlay with a bottom-end column that contains a placeholder quick source-separation button above the full-lyrics button.
3. Wire the closed button to `PlayerViewModel` separated-playback state and verify it opens/closes in sync with the settings sheet.
4. Add the custom vertical blend control with Canvas drawing and pointer-input handling.
5. Add center snapping for drag only.
6. Add top/middle/bottom tap zones.
7. Add endpoint stem icons after the final icon assets are drawn.
8. Verify on supported player styles and with both plain and synced lyrics.

Open polish decisions:

- Whether the closed-to-expanded transition should be an `AnimatedContent` size transform or a simple state swap for the first prototype.
- Exact middle snap threshold; start conservatively around a few percent of the track height and tune by device testing.
- Whether the quick control should show per-song/global mode distinction visually or remain a simple separated-playback quick blend regardless of memory mode.

### Boundary and Finalization Strategy

There are three separate boundary concerns:

- Model window reconstruction: handled by STFT/ISTFT plus MDX stable-region trim.
- Playback segment continuity: handled by reading the sample-accurate segment timeline in order.
- Final cache creation: handled by concatenating completed stable regions in global frame order.

The final whole-song stems should be generated from the same stable segment data used for playback. They should not re-run model inference and should not include playback-only fades. After all segments are complete, the app should atomically promote the full-track stems to the completed-cache format.

Default completed-cache format:

- `vocals.flac`
- `instrumental.flac`

FLAC is preferred because it is lossless and avoids encoder-delay alignment problems. A manifest should store the exact frame count so decoded FLAC output can be checked against the segment timeline.

Optional future small-cache format:

- Opus, after encode/decode alignment tests pass on target devices.

MP3 is not planned for the first implementation.

## Development Phases

### Phase 0: Branch and Planning

Status: completed

Goals:

- Create a dedicated experimental branch.
- Add this roadmap.
- Keep all project documentation in English.

Done criteria:

- The branch exists.
- This document is committed.
- No runtime behavior changes are included in this phase.

### Phase 1: Import the Offline Separation Engine

Status: completed

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

Implementation notes:

- Added ONNX Runtime Android and JTransforms dependencies.
- Added optional local model asset packaging from `models/uvr-mdx`.
- Added `models/` to `.gitignore` so local ONNX weights stay out of Git.
- Copied the locally verified `UVR_MDXNET_9482.onnx` into the ignored model directory for personal debug builds.
- Added the offline engine under `com.mardous.booming.separation`.
- Added PCM decoding, sample-rate conversion, WAV writing, MDX STFT/ISTFT, ONNX session setup, and range separation.
- Added `SourceSeparationEngine`, a Koin-registered internal entry point that can separate a `Song` into vocals and instrumental WAV files under the app-private external music directory.
- Added a hidden MediaSession command, `Playback.SEPARATE_CURRENT_SONG_OFFLINE`, that separates the currently playing song and returns output file paths in the command result bundle.
- Verified `:app:assembleNormalDebug` succeeds.
- Verified the x86_64 debug APK contains `assets/UVR_MDXNET_9482.onnx` and ONNX Runtime native libraries.

Current limitations:

- The initial developer-only custom command is still available, but Phase 3 adds the first player-screen manual trigger.
- Cancellation is now cooperative and can stop decoding, resampling, or processing between model windows, but it still cannot interrupt an active ONNX inference call instantly.
- The offline path still decodes the whole source into memory before processing.
- Output is WAV only.

### Phase 2: Cache Index and File Layout

Status: completed

Goals:

- Define app-private cache directories.
- Add a persistent cache index.
- Record song locator fields separately from audio identity fields.
- Record audio fingerprint, model variant, pipeline version, output files, state, created time, and total cache size.
- Store file size and raw modified timestamp as diagnostic metadata only.
- Add safe cleanup for failed or canceled runs.

Done criteria:

- Completed output can be discovered after app restart.
- Metadata-only edits such as artist, album, lyrics, or cover changes do not force recomputation when decoded audio is unchanged.
- Replaced or edited audio content does not accidentally reuse stale stems.
- Failed partial output does not appear as completed cache.

Implementation notes:

- Added a file-based cache index under the app-private external music directory.
- Added one cache entry directory per song/model/pipeline tuple under `source-separation/entries`.
- Added `manifest.json` with explicit `Running`, `Completed`, `Canceled`, and `Failed` states.
- Split cache metadata into song locator fields, audio identity fields, diagnostics, output paths, and error information.
- Added a decoded PCM SHA-256 audio fingerprint to the separation result and cache manifest.
- Stored file size and raw modified timestamp only as diagnostics, not cache invalidation inputs.
- Added work and completed directories so successful runs are promoted from temporary output to stable cache files.
- Added unsuccessful-run cleanup so temporary work files are removed and failed or canceled manifests do not appear as completed output.
- Added cache listing, completed-manifest lookup, and delete helpers for later UI work.
- Verified `:app:assembleNormalDebug` succeeds after cache integration.

Current limitations:

- Phase 2 cache entries are full-song WAV outputs only.
- Segment-level readiness and partial playback cache files are still deferred to Phase 5.
- Completed-cache FLAC promotion is still deferred to Phase 8.
- The first audio fingerprint is decoded PCM SHA-256 computed during full-song separation; a faster encoded audio-track hash can be evaluated later.

### Phase 3: Current-Song Manual Separation

Status: completed

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

Implementation notes:

- Added a "Separate vocals" action to the expanded player's overflow menu.
- Added `SourceSeparationUiState` to `PlayerViewModel` for idle, running, completed, canceled, and failed states.
- Runs source separation on `Dispatchers.IO` without changing the active playback output.
- Shows an indefinite player-screen Snackbar while separation is running, including completed-window progress once known.
- Shows pre-window stage details such as decoding, resampling, model preparation, ONNX session creation, and output setup.
- Added a Snackbar cancel action that requests cooperative cancellation and cleans temporary work output.
- Added a `Canceled` cache manifest state so user cancellation is distinct from processing failure and never appears as completed cache.
- Added cancellation checks to decode, resample, and MDX processing between model windows.
- Writes `timing.txt` next to completed WAV stems with decode, resample, model-file, session setup, STFT, ONNX inference, ISTFT, PCM conversion, and WAV write timings.
- Verified `:app:assembleNormalDebug` succeeds.

Current limitations:

- This phase still processes the whole song as a single offline job.
- Canceling during an active ONNX window waits until that window returns.
- Progress is diagnostic-stage-oriented until window processing begins.
- Separated stems are not used for playback until Phase 4.

### Phase 4: Basic Completed-Stem Playback Mode

Status: completed

Goals:

- For songs with completed cached stems, enable separated playback mode.
- Add the vocals/instrumental blend slider.
- Mix the two completed stems while preserving player controls, seek, pause, resume, and notification behavior as much as possible.

Done criteria:

- Completed stems can be played through the app.
- The blend slider works during playback.
- Seeking remains accurate enough for daily use.
- Returning to normal playback is possible.

Implementation notes:

- Added a completed-cache lookup path for the playback service.
- Added a `SourceSeparationMixAudioProcessor` that lets ExoPlayer play the completed instrumental WAV while synchronously mixing the completed vocals WAV from the same playback position.
- Added MediaSession commands for enabling or disabling separated playback and for changing the vocals/instrumental blend.
- Added player ViewModel state for separated playback mode.
- Added an experimental player-menu control panel with a blend slider and enable/original actions.
- Preserved normal player controls, pause/resume, seeking, and notification behavior by keeping playback inside the existing Media3 player.
- Prevented the temporary instrumental stem media item from being saved into the persistent playback queue.
- Verified `:app:assembleNormalDebug` succeeds.

Current limitations:

- Phase 4 only works after a full-song WAV cache already exists.
- The blend slider is currently exposed in a menu dialog rather than embedded persistently into every player style.
- Separated playback is currently a simple enabled/disabled state; it still needs the planned `Off`, `Global`, and `PerSong` mode split.
- Song transitions to completed caches still need to apply the selected blend mode and the correct global or per-song blend memory automatically.
- The mixer assumes the completed WAV stems are 16-bit stereo and sample-aligned with the instrumental stem used by ExoPlayer.
- This still uses completed WAV files; FLAC promotion remains deferred to Phase 8.
- Play-while-processing and segment readiness remain deferred to Phase 5 and Phase 6.

Planned follow-up before Phase 5:

- Replace the direct-cycling blend-mode entry with a source separation settings entry.
- Add a minimal Sound Settings-style source separation bottom sheet.
- Model separated playback settings as a master enabled flag plus a per-song-memory flag.
- Persist the global blend value and per-song blend values separately from those flags. Completed in the initial persistence pass.
- Reapply the correct settings and blend after song changes, seeks, service recreation, and app restart.
- Keep the existing direct progress Snackbar until the sheet has reliable progress state.

### Phase 4.5: Source Separation Settings Entry Skeleton

Status: in progress

Goals:

- Rename the player source separation entry so it no longer implies direct blend-mode cycling.
- Make the entry open a Sound Settings-style bottom sheet.
- Keep the entry icon as a passive state indicator.
- Add a minimal sheet around the existing completed-stem playback capabilities.
- Avoid adding fake pause/resume controls before partial segment preservation exists.

Done criteria:

- The source separation entry opens the sheet in every player style that exposes the button. Completed.
- The entry no longer directly cycles modes. Completed.
- The sheet can toggle separated playback, toggle per-song blend memory, and adjust the active blend value for completed caches. Completed for the current prototype control surface.
- The icon state updates from the durable settings. Completed for app-level separated playback and per-song-memory flags.
- App-level separated playback enabled, per-song memory enabled, and global blend are persisted in shared preferences.
- Per-song blend is persisted in a cache-owned `playback-settings.json` sidecar keyed to the separated cache's encoded-audio fingerprint.
- Per-song blend also has a best-effort temporary shared-preferences store for songs whose separated cache is not prepared yet. This lets a user adjust and restore the blend while separation is still running or paused.
- Saved settings are reapplied after song changes, service reconnection, and app restart.
- Song transitions in per-song blend mode defer new separated playback sessions until the target song's blend is loaded and sent by the UI layer, preventing a brief output with the previous song's blend.
- The old separated playback dialog is no longer needed for normal use. Completed.
- The app still builds and completed-cache playback still works. Completed for `:app:assembleNormalDebug`.

Implementation notes:

- Renamed the player entry from a blend-mode button to a source separation settings entry.
- Added a Sound Settings-style `SourceSeparationSettingsFragment` bottom sheet.
- Routed player-style source separation buttons and menu items to the settings sheet.
- Removed the direct click-to-cycle behavior from player controls.
- Removed the old separated playback dialog surface.
- Split the control state into durable app-level flags instead of deriving the per-song-memory switch only from the three-state icon mode.
- Added a cache-owned playback settings sidecar for per-song blend memory. The sidecar is ignored if its fingerprint does not match the current manifest audio fingerprint.
- Added a temporary per-song blend store keyed by a hash of the current library song id, URI, and file path. When the separation manifest receives a real encoded-audio fingerprint, the temporary value is migrated to the cache-owned sidecar and removed.
- Added a playback-service gate so global blend can auto-sync on song transitions, while per-song blend waits for the target song's explicit blend command before creating a new separated playback session.

Current limitations:

- The per-song blend sidecar currently follows the existing cache entry lookup, which is still rooted by MediaStore song id. Cross-song-id cache reuse after file moves or MediaStore rescans remains deferred to the future audio-fingerprint cache index.
- The temporary per-song blend store is intentionally best-effort and only exists to bridge the period before a fingerprint-backed cache sidecar is available.
- The long-running separation Snackbar remains until the sheet owns a reliable progress model.

### Phase 5A: Window Decode Experiment

Status: completed for the current production whitelist; maintenance only

Goals:

- Verify whether Android `MediaExtractor`/`MediaCodec` can reliably decode only the source range needed by one MDX context window.
- Compare a locally decoded window against the same window cut from the current full-song decode path.
- Measure full-song decode time versus local-window decode time on real devices.
- Use the current playback position to choose a representative MDX segment window.
- Keep the experiment isolated from normal separation and playback behavior.

Done criteria:

- The app can generate a diagnostic report for the current song and playback position.
- The report includes requested frame/time ranges, full decode timing, window decode timing, frame counts, direct PCM difference, and best small-offset PCM difference.
- The experiment can be run on several common local formats before the production pipeline depends on it.
- Existing full-song separation and play-while-processing behavior remain unchanged.

Implementation notes:

- The current full-song path still decodes the whole source into memory before segment processing begins.
- `startMs` and `endMs` in `MdxRangeSeparator` are currently applied only after full decode and resample, so they do not reduce initial wait time yet.
- A successful window decoder experiment should lead to a segment-first pipeline where each MDX context window is decoded, resampled, processed, and written independently.
- Audio identity should move away from full decoded PCM SHA-256 before production local-window processing. A hash of encoded audio samples from `MediaExtractor`, excluding container metadata, is the preferred follow-up candidate.

Historical limitations:

- MediaCodec seek behavior can vary by codec/container, so one successful format is not enough to promote the approach.
- Exact PCM equality may not hold near seek boundaries; the report should evaluate small alignment offsets rather than relying only on byte equality.

Latest diagnostic direction:

- Run several probes per song from one report: the current playback position, the beginning, common mid-song points, half duration, and near the end.
- Decode the full song only once as the reference, then compare each local window against the corresponding reference slice.
- Compare both request-aligned placement and first-decoder-output-timestamp placement.
- Treat a stable non-zero best offset as a warning that the container or codec needs explicit timestamp-delay compensation before production use.
- Pay special attention to AAC/M4A, where decoder priming or timestamp behavior may shift local-window PCM even when MP3, FLAC, and WAV are aligned.

Initial conclusions from the first two experiment rounds:

- FLAC is already close to ideal for window decoding across multiple positions.
- MP3 can look good near the start but shows position-dependent seek offsets later in the song.
- AAC/M4A can require codec-specific compensation and does not share one universal offset behavior.
- OGG and WAV exposed a second issue: local-window resampling from an arbitrary cut does not always match full-song resampling followed by slicing.
- The local window idea is still promising because it is much faster than full decode, but the production path needs a better alignment model before it can replace the current whole-song decode.

Latest conclusions from rounds 5 and 6:

- Non-MP3 formats are now good candidates for a strict production fallback path: the local-window strategy looks reliable when the chosen placement rule is stable.
- MP3 is the remaining format that needs dedicated attention. Its raw frame-deficit signal appears file-specific but often stable enough to justify a short calibration probe.
- The next step should treat MP3 as a file-level anchor calibration problem keyed by audio fingerprint, not as a generic "one rule fits every format" decode problem.
- Brute-force best-offset search remains diagnostic only. Production should use a deterministic profile or fall back to the current full-song path.

Planned MP3 calibration round:

- Compare the MP3 raw frame-deficit placement offset against the search-verified best offset across several songs and positions.
- Check whether one short probe can produce a file-level correction that stays stable across the rest of the song.
- Cache the correction by audio fingerprint only when the spread is tight enough to trust.
- Keep the existing non-MP3 findings as a fallback-oriented reference instead of trying to force one universal rule across all codecs.

Round 8 follow-up findings and Round 9 experiment plan:

- MP3 beginning-segment calibration is close but not yet production-safe by itself. One tested MP3 stayed within 1 source frame, while another stayed within 4 source frames but still had 1-3 frame residual errors on holdout probes.
- The holdout rows suggest that the search-verified MP3 placement often lands on a 384-source-frame grid after applying file-level calibration. Round 9 therefore adds a diagnostic `MP3 quantized calibrated` candidate that applies the beginning correction and then rounds placement up to the next 384-frame boundary.
- The quantized MP3 candidate is diagnostic only. It must prove bit-perfect zero-offset holdout behavior across more than the two current MP3 files before it can become a production profile.
- 22.05 kHz AAC remains the weakest non-MP3 sample. Some probes are bit-perfect, while others show large direct mismatches that look related to AAC priming, low sample-rate frame math, and local-window tail truncation.
- Round 9 adds low-sample-rate AAC tail-extension probes. For `audio/mp4a-latm` at 24 kHz or lower, the experiment decodes one encoder-delay/AAC-frame of extra tail audio and then evaluates timestamp and metadata-delay song-timeline placement against the original requested window.
- If the AAC tail-extension candidate improves only a subset of probes, the production path should keep 22.05 kHz AAC on full-song decode fallback until a deterministic profile is proven.

FFmpeg batch and MP3 adaptive findings:

- A debug-only batch runner now tests a directory of decoder samples on-device and writes per-file reports plus a CSV summary. This should remain debug-only unless the experiment harness is deliberately productized later.
- The S25 batch over `test/decode_cases/luv_in_b_ffmpeg` completed 33 of 43 files. The 10 failures were platform decoder support limits, mainly ALAC, unusual WAV, and unsupported WMA variants, rather than window-placement algorithm failures.
- WAV and Ogg Vorbis are the strongest production candidates. The preroll song-timeline strategy was bit-perfect at zero offset for every supported batch sample in those families.
- MP3 needs a format-specific path. The MP3-only adaptive batch completed all 5 supported MP3 cases: 22.05 kHz and 44.1 kHz files were strong, while 48 kHz and 8 kHz still showed residual mismatch.
- 44.1 kHz MP3 should enter the first production prototype with short file-level calibration and quantized calibrated placement. Other MP3 sample rates should keep the full-song fallback until more evidence closes the residual offset problem.
- AAC/M4A, Opus, non-44.1 kHz FLAC, WMA, and unsupported platform-decoder cases should remain on the full-song fallback for now. Some samples are promising, but the batch results are not uniform enough for a safe first production enablement.
- Window decode is the right low-latency direction, but it should be a selective optimization with per-format gates, per-file validation where needed, and automatic fallback to full-song decode. It should not replace the full-song decoder universally.

First production window-decode prototype:

- Enable window decode only for WAV, Ogg Vorbis, 44.1 kHz MP3, and 44.1 kHz FLAC.
- Use preroll song-timeline placement for WAV and Ogg Vorbis.
- Use MP3 file-level calibration plus quantized calibrated placement for 44.1 kHz MP3.
- Use first-output timestamp placement for 44.1 kHz FLAC.
- If metadata, calibration, or local decode validation fails, silently fall back to the current full-song decode path.
- Keep the output contract identical to the current segment writer: stable intervals must land on exact song-timeline frames, and completed WAV stems remain the source of truth for playback.
- Keep the debug batch runner available for regression tests before expanding the whitelist to more codecs or sample rates.

First production prototype validation:

- WAV, Ogg Vorbis, 44.1 kHz MP3, and 44.1 kHz FLAC now use the selective window-decode path in normal separation.
- The currently enabled and tested window-decode profiles are:
  - WAV files reported as `audio/raw` with a `.wav` file name, using preroll song-timeline placement.
  - Ogg Vorbis sources reported as `audio/vorbis`, using preroll song-timeline placement.
  - 44.1 kHz MP3 sources with gapless metadata, using quantized placement.
  - 44.1 kHz MP3 sources without gapless metadata, using the no-gapless calibration profile.
  - 44.1 kHz FLAC sources, using first-output timestamp placement.
- Real-device testing confirmed that the initial decode wait for the enabled profiles is no longer perceptible before model-window progress starts.
- Output vocals and instrumental WAV files remained correct for tested WAV, Ogg Vorbis, 44.1 kHz MP3, and 44.1 kHz FLAC sources.
- Unsupported or not-yet-whitelisted formats still fall back to the full-song decode path and were verified to keep working.
- The current MP3 path remains experimental and is intentionally limited to 44.1 kHz sources, including the no-gapless calibrated profile.
- Separation timing reports and the source separation settings sheet should expose the active decode mode, window profile, source MIME/sample-rate/channel metadata, and fallback reason so format routing can be checked during normal use.
- Whitelisted window-decode inputs now run a lightweight first-window preflight before committing to the window path. If the preflight fails, separation falls back to the full-song decode path and exposes the preflight failure as the fallback reason.
- Completed cache manifests now use an encoded-audio-sample fingerprint instead of the temporary window-profile identity or decoded PCM hash. The fingerprint is derived from the selected audio track's encoded samples and decoder-relevant track fields, so cover art, lyrics, and normal tag edits should not invalidate separated caches.

MP3 no-gapless-metadata experiment preparation:

- Some local 44.1 kHz MP3 files report neither encoder delay nor encoder padding metadata, so the current production window-decode whitelist intentionally falls back to full-song decode for them.
- Before changing the production route, add a debug-only batch mode for `audio/mpeg` 44.1 kHz files with missing delay/padding metadata.
- The experiment reuses the existing local-window probe data and evaluates `frameDeficit + short file-level calibration + 384-source-frame quantization` without trusting metadata correction.
- Per-file reports now include an `MP3 no-gapless-metadata calibration gate` with applicability, correction spread, prediction error, holdout bit-perfect count, and a pass/promising/borderline/fallback decision.
- The debug batch CSV now includes the same gate fields so a directory of user-provided MP3 samples can be scanned quickly after running on the S25.

MP3 no-gapless-metadata experiment result:

- The first S25 sample set at `test/mp3_no_gapless_metadata` covered 7 local 44.1 kHz MP3 files with both encoder delay and encoder padding metadata unavailable.
- All 7 files passed the new gate: `mp3NoGaplessApplicable=true`, `mp3NoGaplessDecision=pass`, correction `0 frames`, correction spread `0 frames`, worst prediction error `1 frame`, and quantized calibrated holdouts `7/7` bit-perfect at zero offset.
- Plain calibrated direct placement was not sufficient on the holdouts. Every file showed a stable residual `1 frame` best offset before quantization.
- The 384-source-frame quantized calibrated placement removed that residual on every holdout in this sample set.
- This supports a conservative production prototype for no-gapless-metadata 44.1 kHz MP3, but only behind a lightweight per-file calibration gate keyed by the encoded-audio-sample fingerprint. Files that fail calibration or cannot be calibrated should still fall back to full-song decode.

MP3 no-gapless-metadata production prototype:

- 44.1 kHz MP3 files with both encoder delay and encoder padding metadata missing now have a separate experimental window-decode profile instead of being rejected at routing time.
- Before using that profile, the app computes the encoded-audio-sample fingerprint and checks a small per-file calibration cache under the app cache directory.
- On a cache miss, the gate decodes several short local probe windows from the same file and records their raw frame deficits and quantized placement offsets for diagnostics.
- Passing calibration is cached by encoded-audio-sample fingerprint. The current production gate no longer requires cross-seek PCM overlap to match sample-for-sample because real-device testing showed that check rejects the same files that passed the full-reference experiment; MP3 decoder state at local seek boundaries is not a valid hard gate for stable-region correctness.
- Failure still throws from decoder/preflight errors and falls back to the full-song decode path.
- This production gate is intentionally weaker than the debug full-reference experiment because it does not decode the whole song for comparison. The debug experiment remains the reference test for absolute bit-perfect validation; the production gate now acts as a low-cost routing/cache diagnostic plus a decode viability check.

44.1 kHz FLAC production prototype:

- The earlier S25 batch showed that full-length 44.1 kHz FLAC failed the default song-timeline candidate but passed the first-output timestamp placement candidate across all tested probes.
- A conservative 44.1 kHz FLAC window-decode profile now uses normal local decode plus first-output timestamp placement.
- Normal separation testing confirmed that 44.1 kHz FLAC produces correct vocals and instrumental output with the window-decode path.
- Non-44.1 kHz FLAC remains on full-song fallback. The 48 kHz/24-bit batch sample still showed source-rate residual alignment issues that should be handled in a separate experiment before production enablement.

Next hardening steps:

- Consider mid-run fallback cleanup for the rarer case where preflight succeeds but a later window decode fails.
- Add a cache-index lookup by audio fingerprint if cross-song-id reuse becomes necessary after MediaStore rescans or file moves.
- Keep 48 kHz MP3, low-rate MP3, AAC/M4A, Opus, non-44.1 kHz FLAC, WMA, and platform-unsupported cases on full-song fallback until deterministic placement profiles are proven.
- Device-to-device and run-to-run timing comparisons should account for thermal throttling. A later retest showed that apparent slowdown after enabling window decode was also present in a pre-window-decode build after extended S25 testing, so the current timing concern is treated as thermal/load-related rather than a window-decode regression.
- The main development focus now moves back to Phase 5B/6 scheduling and live playback behavior instead of expanding the window-decode whitelist.

### Phase 5B: Segment-Based Processing

Status: in progress

Goals:

- Replace the current full-song decode-and-process path after Phase 5A proves local window decoding is reliable enough.
- Replace full-song-only processing with segment state tracking.
- Write ready segments as sample-accurate stable regions.
- Make current position and next segment the highest-priority work.
- Support reprioritization after seek.
- Preserve partial cache when the user changes songs.
- Pause or downgrade old-song work when playback moves to a different song.
- Avoid default crossfades between model output segments.
- Define real pause/resume semantics around preserved segment manifests instead of full-song temporary WAV cancellation.

Done criteria:

- The app can process a song incrementally.
- Segment readiness is persisted.
- Seeking to an unready section reprioritizes that section.
- Already computed segments are reused.
- Changing songs keeps completed segments but does not let old-song work block the new current song.
- Adjacent ready segments join on exact frame boundaries.
- Pausing does not delete completed segment work.

Implementation notes:

- Added `SourceSeparationSegmentPlan` to describe sample-accurate stable regions for a song range.
- Added `SourceSeparationSegmentScheduler` to rank segments by playback position, near-future need, and idle backfill.
- Added segment-relative stem file layout metadata to the cache model.
- The full-song offline separation path now also writes segment WAV outputs under the entry's `segments/` directory so the segment layout is real and inspectable before live playback uses it.
- Manifest output now records the generated segment plan alongside the completed full-song cache.
- Added segment snapshots that derive real availability from the manifest state plus exact segment stem WAV validation, so stale or partial files are not treated as playable.
- Added a cache helper for updating individual segment states, preparing for queued/running/failed partial processing.
- The running separation path now writes the segment plan to the manifest before model-window processing starts.
- Each model window updates its manifest segment state from `Queued` to `Running` to `Ready` as work progresses.
- Running stem WAV files are preallocated to the full output duration, so completed windows can be written into a song-aligned timeline while future regions remain silent.
- The separator can now choose the next unprocessed segment from `SourceSeparationSegmentScheduler` after each completed model window. This lets the active playback position and next segment move to the front of the current song's work when the user seeks.
- Whole-song work WAVs support frame-addressed writes for preallocated files, so out-of-order segment processing does not corrupt the final song timeline.
- Completed runs copy final WAV files into `completed/` and mark the full-duration `work/` WAV timeline for deferred cleanup.

Current limitations:

- Reprioritization is currently scoped to the active song's in-flight separation run. It does not yet provide a global multi-song work queue.
- A model window that is already inside ONNX inference still runs to completion before the new playback-head priority can take effect.

Next scheduler refinement steps:

1. Expose scheduler decisions in the source separation sheet: playback segment, processing segment, priority reason, current/next readiness, and ready segment count.
2. Trigger playback readiness sync immediately after seeks so the processing gate and scheduler target update together.
3. Add light debounce or segment-change gating so continuous scrubbing does not churn scheduler intent more often than useful.
4. Add active-song ownership to processing: after song changes, preserve completed segments for the old song but pause or downgrade its remaining work once the current ONNX window finishes.
5. Move from the current single active task to a real background queue only after the foreground current-song path stays stable.

Active-song pause prototype:

- The current player-scoped separation task now watches the active song. When playback changes to another song, the old task is asked to pause at the next safe boundary.
- The active ONNX inference window is not interrupted. After that window is written and marked ready, the task stops without marking the cache as canceled or failed.
- The cache entry remains in `Running` state and keeps its `work/` full-duration WAV files, segment WAV files, and manifest segment readiness.
- Starting separation for that song again reuses the existing `Running` manifest, verifies segment files on disk, preserves ready segment states, and skips ready segments.
- Manual testing confirmed that changing songs pauses the old song after the current window, and returning to that song resumes from the preserved partial segment cache.
- This is still a player-scoped single active task, not a background multi-song queue. A future worker layer can resume old songs as low-priority idle work after current-song needs are satisfied.

Partial-cache restart recovery prototype:

- A new separation run can now resume from a previous `Running` manifest after app restart or process death.
- Recovery only preserves segments that were already marked `Ready` in the manifest and whose vocals/instrumental segment WAV files exactly match the expected 44-byte PCM16 WAV layout and data size.
- Ready segments are only skipped when the full-duration running work WAV files are also present and valid, because those files are the live playback and final-promotion timeline.
- Stale `Running`, `Queued`, `Failed`, or incomplete segments are normalized back to `Queued` and processed again.
- If the full-duration work WAVs are missing or invalid, all segments are queued again so old segment files cannot make playback trust an empty or mismatched stem timeline.

Completed temporary-cache cleanup prototype:

- Completed manifests now record pending cleanup entries for the `work/` and `segments/` directories after final WAVs are promoted to `completed/`.
- The playback service owns the actual deletion decision because it knows which stem files are still referenced by the active mixer session.
- Pending temporary cleanup is attempted on service startup, after source-separation playback sessions are cleared, after service-side offline separation completes, and when the UI asks the service to clean after a ViewModel-side separation run finishes.
- Cleanup skips any `work/` directory whose files are still referenced by the active source-separation playback session, so live running-cache playback can finish its transition before storage is reclaimed.
- Completed `segments/` directories are deleted as soon as the cleanup pass can safely verify that they are inside the cache entry directory, because completed playback uses the promoted full-track stems.
- If the current playback session is still using the running `work/` stem timeline after final promotion, a manual seek upgrades that session to the promoted `completed/` full-track WAV files and then retries temporary cleanup. This keeps uninterrupted playback stable while still giving the user an immediate cleanup trigger.
- The source separation sheet now reports idle cache state explicitly: not started, partial cache present, completed with temporary artifacts pending cleanup, or completed and cleaned.
- Manual device testing confirmed that active playback remains stable after full-song completion, manual seek can switch playback to the promoted full-track files, and both `work/` and `segments/` temporary directories can be reclaimed without breaking the current session.

### Phase 6: Play While Processing

Status: in progress

Goals:

- Start separated playback once the current segment and the next segment are ready.
- Pause or visually gate separated output when the requested position is not ready.
- Automatically resume when enough data is available.
- Switch scheduler priority immediately when the current song changes.
- Read ready stems from the sample-accurate segment timeline.
- Avoid disruptive playback jumps when segment boundaries become ready.
- Add only a minimal anti-click fade if direct sample-boundary joins are audibly imperfect.
- Feed current-song readiness and progress into the source separation settings sheet.

Done criteria:

- The user does not need to wait for whole-song completion.
- If the playback head outruns available separated audio, the UI clearly shows processing.
- Playback resumes automatically when ready.
- User seeking during processing remains responsive.
- User song changes reprioritize the active song without deleting useful partial cache from the old song.
- Segment transitions are not perceptibly worse than the completed full-song output.
- Detailed processing progress is available in the settings sheet without relying on the old indefinite Snackbar.

Implementation notes:

- Added a playable-cache lookup that can return a `Running` cache only when the current playback segment and the following segment are already marked `Ready`.
- The playback service now tries to use the running full-duration stem WAV timeline, guarded by segment readiness, before falling back to the original source.
- Added a MediaSession sync command so the UI can request a non-disruptive retry after each separation progress update.
- If the user has enabled separated playback before the current ready window is available, playback now enters a processing gate, pauses, and retries automatically as windows complete.
- Verified `:app:assembleNormalDebug` succeeds after the first play-while-processing experiment.
- Seeks during running separated playback now re-check current and next segment readiness. If the target window is not ready, playback restores the original media item, pauses, reports a processing state, and automatically switches back to separated playback when the window becomes ready.
- Starting a separation while separated playback is requested now uses the same processing gate: playback pauses while the initial playable window is unavailable instead of continuing with the original audio.
- Completed separated playback uses the instrumental stem WAV as the ExoPlayer media item and mixes the vocals stem from the same completed stem timeline.
- Running separated playback keeps the original source as the ExoPlayer clock input while the mixer reads both work-in-progress stem WAVs directly. This avoids ExoPlayer pre-buffering unwritten zero-filled ranges from the instrumental work WAV while still suppressing original-source leakage.
- Separated playback transitions pause output while replacing media items, seeking, preparing, and realigning the stem processor, then restore playback only after the new timeline is ready.
- The stem mixer seeks by the output stem sample rate stored in the manifest instead of by the current ExoPlayer input format, preventing stale-format or source-format seeks from offsetting vocals and instrumental stems.

Current limitations:

- A newly selected segment priority takes effect between model windows. Seeking to an unready position can now move that position's segment and its next segment ahead of remaining backfill work, but it cannot interrupt an active ONNX inference call.
- The settings sheet still uses the old coarse progress text instead of a dedicated current/next segment readiness model.

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

Status: in progress

Goals:

- Promote completed segment timelines to full-track FLAC stems by default.
- Store exact frame counts and codec metadata in the cache manifest.
- Verify decoded FLAC frame count and alignment before deleting temporary segment data.
- Keep WAV or raw PCM segment caches while processing, then atomically promote completed FLAC outputs after full-song completion.
- Evaluate Opus later as an optional small-size mode only after encode/decode alignment tests pass.
- Do not target MP3 for the first implementation.

Done criteria:

- Completed songs use FLAC cache files by default.
- FLAC output preserves the exact expected frame count.
- Compression does not introduce audible or measurable stem desync.
- Temporary segment files are cleaned up safely after promotion.
- Playback can still use partial segment caches while a song is not fully complete.

Implementation recommendation:

- Start with FLAC promotion for completed full-track stems only. Keep running `work/` and `segments/` as PCM WAV during processing so partial playback and restart recovery stay unchanged.
- Add manifest output metadata that records codec, path, expected frame count, sample rate, channel count, and whether the promoted files have passed a local decode validation.
- Promote atomically: encode `completed/vocals.flac` and `completed/instrumental.flac` beside the existing WAV files, decode-probe both FLAC files for frame count/alignment, update the manifest only after validation, then let the temporary cleanup path delete superseded WAV process artifacts.
- Keep a WAV fallback path for promotion failures or devices without reliable FLAC encoder/decoder behavior.

Initial implementation notes:

- The first FLAC promotion pass uses a project-local encoder that only supports the app's own completed 16-bit stereo PCM stem WAV files.
- The encoder writes standard FLAC with fixed predictors plus Rice residual coding, falling back to verbatim subframes when that is smaller for a block. No external encoder dependency is used.
- Promotion is non-critical: if encoding or decode validation fails, the completed cache remains WAV-backed and separation still succeeds.
- The manifest records validated promoted FLAC paths. Completed separated playback now prefers validated `instrumental.flac` as the ExoPlayer source and validated `vocals.flac` as the mixer stem.
- Automatic FLAC promotion is now an advanced source-separation setting. It defaults on for new separation tasks, is captured when the task starts, and can be disabled so completed WAV stems remain WAV-backed.
- Current-song manual FLAC promotion is available from the settings sheet only when a completed cache still has both WAV stems and no validated FLAC promotion.
- S25 debug testing showed that Android `MediaExtractor` does not recognize the generated raw FLAC files as audio tracks, so platform decode validation is not a suitable promotion gate. The project-local verifier is used instead and checks STREAMINFO, frame count, and decoded PCM MD5.
- Separate S25 ExoPlayer testing confirmed that `instrumental.flac` can be used directly as the completed-cache media item: tested files reached `READY`, advanced playback position for the probe window, and produced no playback errors.
- Completed-cache playback with both stems retained only as FLAC was manually tested on several songs. Blend playback sounded correct and remained stable.
- After successful promotion, both `completed/vocals.wav` and `completed/instrumental.wav` are superseded and can be deleted. The temporary cleanup path also removes legacy completed WAV stems when a manifest has validated promoted FLAC paths.
- The committed first validation pass decoded `vocals.flac` into PCM memory when the mixer session opened. That proved the dual-FLAC playback route but was not suitable as the final reader.
- The current hardening pass writes a small sidecar frame index (`*.flac.idx`) during project-local FLAC promotion. The mixer can use this index to seek to the containing FLAC frame and decode only bounded 4096-frame blocks on demand.
- Existing promoted FLAC caches without a sidecar index remain playable through the old whole-file decode fallback, so this hardening does not invalidate already-tested caches.
- Playback-gate traces keep indexed FLAC open, fallback, and seek events visible. Per-frame decode timing remains behind a local debug constant because it is useful for targeted profiling but too noisy for normal playback testing.
- A debug-only stereo decorrelation experiment compared the current independent-stereo encoder against adaptive independent/left-side/right-side/mid-side frame selection on S25. On three real completed-cache song pairs, vocals saved about 8.3% total, instrumentals saved about 2.5% total, and the combined stem set saved about 5.2%. All decorrelated outputs passed local PCM MD5 verification, but adaptive encoding was substantially slower because each block must encode multiple candidates. Keep production promotion on independent stereo for now unless the extra post-processing time becomes acceptable.

Next FLAC hardening steps:

- If smaller completed caches become more important than promotion time, consider enabling adaptive stereo decorrelation only as an optional/background promotion mode, not on the critical playback path.

### Phase 9: Cache Management and Full Settings UX

Status: in progress

Goals:

- Finish the source separation settings sheet as the main control surface.
- Remove the old overflow actions once the sheet fully replaces them.
- Keep source-separation Snackbar messages optional now that progress is represented in the sheet.
- Add a way to list songs with separated caches.
- Show cache size and model/pipeline information.
- Allow deleting a single song's separated cache.
- Allow deleting the current song's separated cache from the settings sheet.
- Add a compact cover-lyrics quick control for enabling separated playback and adjusting the active stem blend without opening the full settings sheet.
- Consider an optional "delete all separated tracks" action.

Done criteria:

- The source separation sheet contains the master switch, per-song memory switch, blend slider, current song progress, pause/resume or stop controls, current-song cache deletion, manual FLAC promotion, and advanced diagnostic settings.
- The cover-lyrics quick control appears above the full-lyrics button on player styles that support cover lyrics, stays synchronized with the settings sheet, and adjusts separated playback/blend without introducing stem desync or source leakage.
- The old `Separate vocals` and `Separated playback` overflow actions have been removed.
- Detailed processing progress no longer depends on an indefinite Snackbar, and all source-separation Snackbar messages are hidden by default behind an advanced setting.
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
- MP3 encoding would require native libraries or third-party encoders and is not planned for the first implementation.
- Lossy compressed stems may drift because of encoder delay or padding; FLAC avoids this and should be the default completed-cache format.
- Two independent players are likely to drift and should be avoided unless the Media3 custom-source path proves too expensive.
- Running inference while playing audio may cause thermal throttling or playback stutter.
- The prototype currently decodes full songs into memory; a production-like path may need segment or streaming decode.
- Accurate segment boundary playback will be the hardest part of the project.
- Crossfading segment boundaries by default could hide alignment bugs while also changing the separated audio. The preferred path is sample-accurate stable-region concatenation.

## Current Implementation Order

The early milestone ordering was deliberately modest:

1. Import the proven offline engine.
2. Separate the current song to app-private WAV files.
3. Add cache indexing.
4. Play completed cached stems with a blend slider.
5. Add only a minimal source separation settings entry skeleton.
6. Build segment-based caching and resumable processing semantics.
7. Only then attempt play-while-processing.
8. Finish the full settings sheet and remove the older temporary menu/Snackbar surfaces.

The first seven items now exist in prototype form. The current preferred order is:

1. Implement the cover-lyrics quick blend control as the next UX step, using content-padding-based lyric avoidance and a custom vertical Canvas/pointer-input control.
2. Feed current/next segment readiness and scheduler priority into the source separation sheet.
3. Finish cache management and remove the older temporary menu/Snackbar surfaces.
4. Revisit background and thermal behavior after the foreground quick-control and settings-sheet UX are stable.
5. Expand window-decode profiles only after the core live playback path remains stable in daily use.
