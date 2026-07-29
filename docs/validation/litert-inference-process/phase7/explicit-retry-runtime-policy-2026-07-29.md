# Phase 7 Explicit-Retry Runtime Policy

Date: 2026-07-29

## Scope

This checkpoint covers the artifact-identity boundary after an unexpected
independent inference-process death. It verifies that an explicit retry cannot
resume a GPU-admitted journal through a retired bounded-runtime identity, and
that ordinary playback position updates cannot turn the rejection into an
implicit retry.

It does not change source decoding, window boundaries, overlap, joining,
model contracts, or backend selection.

## Frozen Inputs

- App commit: `139606ff0793cb6b12bec07a03fb6e151184c0fb`
- App APK SHA-256:
  `baace891825c22da53cf396b2c0999fe2c748548536863977215ff5dd356057b`
- AndroidTest APK SHA-256:
  `f9e8bc3efb1e1a663a0380e923b8314450a1aae014d267589e7aad9a1f508e03`
- Model: `uvr_mdxnet_3_9662@2`, artifact SHA-256
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Source fixture: `coast_town_full_mp3`, SHA-256
  `f66be47fc846459f8ac92543b38dab2019765b556cff98539339bbb44cabcae3`
- Device: Galaxy S10, API 31, `arm64-v8a`
- Admitted runtime: `2.1.5-bss.2`, bounded OpenCL FP32, `N=1`
- Retired test identity: `2.1.5-bss.2-retired`

The raw report remains under the ignored Phase 7 build directory. Its SHA-256
is `bfe6d6433132b44c8ff409ca18e3ae3f44a8a30a5272b25fe284f7825a5b9bfd`.
The companion input envelope SHA-256 is
`253ff81895b786fa7aa6a95a218f035a49aea0118f53fb0497c0514225ff6045`.

## Results

The full external-death scenario passed:

- the first committed segment and journal sequence 6 survived remote death;
- no automatic retry or remote-process relaunch occurred during a 30.418
  second observation window;
- explicit retry rejected the retired GPU artifact identity before starting a
  replacement inference process;
- five later playback position updates left the worker inactive and did not
  make another runtime call;
- the journal remained unchanged during rejection, then the harness restored
  its exact original contents for subsequent tests;
- the admitted `tryGpu=true` policy remained frozen even though the persisted
  preference was temporarily changed to false;
- the failure message used the localized TFLite model-load text; and
- the cache lock, processing service, notification, inference wake lock, and
  remote process were all absent at the terminal boundary.

The complete S10 `SourceSeparationForegroundWorkerRecoveryTest` also passed.
Its third case proves in isolation that repeated position updates remain
terminal after runtime mismatch, while a second explicit
`startCurrentSong()` invokes runtime resolution again.

## Limits

This checkpoint edits the admitted journal to carry a retired artifact
identity. It is deterministic coverage of runtime-policy admission, not a real
two-APK application update. A later test must install an older APK, create the
durable run, replace it with a newer APK through `adb install -r`, and retry
without clearing app data.

Model loss and an already-latched GPU-to-CPU fallback remain separate open
explicit-retry cases. The initial run used the production bounded GPU profile,
but this checkpoint did not inject or qualify a production GPU failure.
