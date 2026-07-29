# Phase 7 main-process product state

Date: 2026-07-29

## Scope

This checkpoint extends the real `MainActivity` main-process death scenario at
the first committed-segment boundary. It verifies three product-facing state
projections while the original independent CPU or bounded-GPU run is still
active:

- the media-processing notification remains correctly rendered;
- the running cache exposes a playable first window; and
- cache-management state exposes the exact active entry in its incomplete
  section.

It does not change source decoding, window boundaries, overlap, joining,
backend selection, or cache ownership.

## Frozen inputs

- Harness code commit: `2b9b16dc4d481a45d3231e032ae1072623fa3e0f`
- CPU input-envelope commit: `2b9b16dc4d481a45d3231e032ae1072623fa3e0f`
- GPU input-envelope commit: `6e0cc41d863a6e460a98bda1d215f0333539eea9`
  (documentation-only changes after the harness commit)
- App APK SHA-256:
  `f7911c7d2e1a9f07bbdffefc6a7815d03c66de5a0847065efc35f59ffef9a282`
- AndroidTest APK SHA-256:
  `f9e8bc3efb1e1a663a0380e923b8314450a1aae014d267589e7aad9a1f508e03`
- Runner: `phase7-runner-v57`
- Device: Galaxy S10 (`SM-G9730`), API 31, `arm64-v8a`
- Build fingerprint:
  `samsung/beyond1qltezc/beyond1q:12/SP1A.210812.016/G9730ZCU8HWE2:user/release-keys`
- Model: `uvr_mdxnet_3_9662@2`
- Backends: LiteRT CPU FP32 and bounded LiteRT GPU OpenCL FP32
- Fixture: `coast_town_full_wav`, 48 planned segments
- Death boundary: `after-first-committed-segment`

## Result

Both runs replaced the main PID while retaining the original independent
inference PID, process generation, run ID, and exact cache key. The replacement
product observer connected before the validation bridge used the coordinator.
Each run recorded exactly three observer transitions and no second start.

| Backend | Old/new main PID | Remote PID | Process generation | Segments before/final |
| --- | --- | ---: | ---: | --- |
| CPU FP32 | `27024` / `27450` | `27260` | `110004925397228` | `1/48` |
| Bounded GPU FP32 | `418` / `1388` | `781` | `13436371702996` | `1/48` |

Before death and after reattachment, notification `21331` remained on channel
`source_separation_processing`, category `progress`, ongoing, and
indeterminate. Its localized title, source filename, and localized Pause and
Cancel actions were unchanged.

While each run was still active, position zero returned `Ready` with a required
window count of one. Both stem files were readable. Cache-management state
contained the exact entry in its incomplete section with:

- state `Partial`;
- model `uvr_mdxnet_3_9662`;
- availability `InstalledExact`; and
- readiness `3/48` segments on CPU and `2/48` on bounded GPU.

Both original processes then completed all 48 segments. The CPU runtime record
was `LiteRtCpu`, FP32. The GPU run retained
`gpu-opencl-bounded-fp32-v1`, artifact `2.1.5-bss.2`, OpenCL FP32,
`kernelBatchSize=1`, and `commandQueueWindowSize=1`; its final runtime record
was `LiteRtGpu`. Neither run fell back.

## Evidence

Raw JSON remains ignored under
`build/phase7-validation/main-recreation-product-state-v1/`.

| Backend | Report SHA-256 | Input-envelope SHA-256 |
| --- | --- | --- |
| CPU | `9f528c709972a43f6e85368276c4a693bb0fd26a3f6b42676535ae767d15e7b2` | `b6120ef315008e5c20773beb6258443736a05efd96d380ee94da4c95cb44d4dc` |
| Bounded GPU | `292af01050ee72e04fe7d7049496b07c90388c9ce2f7d1962960a8ac78760f17` | `6dc18c86bd0c3c1adf1e79c2d44c38af5754c1c7c40399d72d77879dd4c58233` |

## Limits

This run validates the state consumed by the player and cache-management
surfaces, but it does not drive the visible Compose screen or its gestures.
It also does not claim active original-audio continuity across main-process
death. Visible cache actions and visible preset/model switching remain
separate checks. The retained app data contained another
incomplete cache entry; selection of the exact tested cache key prevented that
unrelated entry from satisfying the gate.
