# Phase 7 Explicit Retry After Model Loss

Date: 2026-07-29

## Scope

This checkpoint covers loss and exact restoration of the active TFLite weight
after an unexpected independent inference-process death. It verifies the
typed missing-model boundary, absence of automatic work after restoration,
and the second explicit start required to resume the abandoned cache.

It does not change source decoding, window boundaries, overlap, joining,
model contracts, or backend selection.

## Frozen Inputs

- App commit: `2aded542d21d156e2f3c9faea629c1ec378a7340`
- App APK SHA-256:
  `5e9e96ea6e437ad5b4fb4ec5904e74dee6edd91a0a4f69880aaddcb5bed08237`
- AndroidTest APK SHA-256:
  `f9e8bc3efb1e1a663a0380e923b8314450a1aae014d267589e7aad9a1f508e03`
- Model: `uvr_mdxnet_3_9662@2`, artifact SHA-256
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Source fixture: `coast_town_full_mp3`, SHA-256
  `f66be47fc846459f8ac92543b38dab2019765b556cff98539339bbb44cabcae3`
- Device: Galaxy S10, API 31, `arm64-v8a`
- Admitted runtime: `2.1.5-bss.2`, bounded OpenCL FP32, `N=1`

The raw report remains under the ignored Phase 7 build directory. Its SHA-256
is `2818f0f804d6b80a99f84741d1538fa3da3ee08e12dd843998ef3ebd39cafc7b`.
The companion input envelope SHA-256 is
`6d3bb6ae934423c20608a47290d875697ac3fed0a5f515edc6436b35d2fc8775`.

## Results

The full external-death scenario passed:

- the first committed segment and journal sequence 6 survived remote death;
- no automatic retry or remote-process relaunch occurred during a 30.027
  second observation window;
- moving the exact active weight out of its installed location produced
  `ModelNotInstalled`, a stale cache entry, and the localized missing-model
  failure on explicit retry;
- the missing retry and five later playback position updates created no
  inference-process PID and did not mutate the abandoned journal;
- restoring the identical weight changed cache model availability back to
  `InstalledExact`, but five more position updates still did not resume work;
- a second explicit start created a distinct process and execution generation,
  preserved the original committed segment, and appended exactly one previous
  owner-death transition;
- the persisted GPU preference had been changed to false, but the resumed run
  correctly preserved the admitted `tryGpu=true` policy and exact bounded
  OpenCL FP32 `N=1` runtime identity; and
- after reaching the native-invocation barrier, Pause left a durable partial
  cache and released the cache lock, processing service, notification, wake
  lock, and remote process.

The cleanup path restored the model at its canonical installed path with its
original SHA-256 and left no file in the debug backup directory.

## Limits

This row covers S10 and an originally GPU-admitted run. It does not replace a
CPU or S25 repetition if a broader release matrix later requires one. The
weight was moved by a debug-only harness after remote death; ordinary product
model deletion remains blocked while that model is active.

An already-persisted GPU-to-CPU fallback latch and a real two-APK application
update remain separate open explicit-retry cases.
