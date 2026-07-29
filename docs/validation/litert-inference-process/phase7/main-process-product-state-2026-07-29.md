# Phase 7 main-process product state

Date: 2026-07-29

## Scope

This checkpoint extends the real `MainActivity` main-process death scenario at
the first committed-segment boundary. It verifies three product-facing state
projections while the original independent CPU run is still active:

- the media-processing notification remains correctly rendered;
- the running cache exposes a playable first window; and
- cache-management state exposes the exact active entry in its incomplete
  section.

It does not change source decoding, window boundaries, overlap, joining,
backend selection, or cache ownership.

## Frozen inputs

- App commit: `2b9b16dc4d481a45d3231e032ae1072623fa3e0f`
- App APK SHA-256:
  `f7911c7d2e1a9f07bbdffefc6a7815d03c66de5a0847065efc35f59ffef9a282`
- AndroidTest APK SHA-256:
  `f9e8bc3efb1e1a663a0380e923b8314450a1aae014d267589e7aad9a1f508e03`
- Runner: `phase7-runner-v57`
- Device: Galaxy S10 (`SM-G9730`), API 31, `arm64-v8a`
- Build fingerprint:
  `samsung/beyond1qltezc/beyond1q:12/SP1A.210812.016/G9730ZCU8HWE2:user/release-keys`
- Model: `uvr_mdxnet_3_9662@2`, LiteRT CPU FP32
- Fixture: `coast_town_full_wav`, 48 planned segments
- Death boundary: `after-first-committed-segment`

## Result

The old main PID `27024` exited and the real `MainActivity` started in PID
`27450`. The independent inference process retained PID `27260`, process
generation `110004925397228`, the original run ID, and the exact cache key.
The replacement product observer connected before the validation bridge used
the coordinator. There were exactly three observer transitions and no second
start.

Before death and after reattachment, notification `21331` remained on channel
`source_separation_processing`, category `progress`, ongoing, and
indeterminate. Its localized title, source filename, and localized Pause and
Cancel actions were unchanged.

While the run was still active, position zero returned `Ready` with a required
window count of one. Both stem files were readable. Cache-management state
contained the exact entry in its incomplete section with:

- state `Partial`;
- model `uvr_mdxnet_3_9662`;
- availability `InstalledExact`; and
- readiness `3/48` segments.

The original process then completed all 48 segments. The final runtime record
was `LiteRtCpu`, FP32, with no fallback.

## Evidence

Raw JSON remains ignored under
`build/phase7-validation/main-recreation-product-state-v1/`.

- Report SHA-256:
  `9f528c709972a43f6e85368276c4a693bb0fd26a3f6b42676535ae767d15e7b2`
- Input-envelope SHA-256:
  `b6120ef315008e5c20773beb6258443736a05efd96d380ee94da4c95cb44d4dc`

## Limits

This run validates the state consumed by the player and cache-management
surfaces, but it does not drive the visible Compose screen or its gestures.
It also does not claim active original-audio continuity across main-process
death. Bounded-GPU repetition, visible cache actions, and visible preset/model
switching remain separate checks. The retained app data contained another
incomplete cache entry; selection of the exact tested cache key prevented that
unrelated entry from satisfying the gate.
