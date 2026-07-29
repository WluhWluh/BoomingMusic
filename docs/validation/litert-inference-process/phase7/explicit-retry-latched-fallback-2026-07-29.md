# Phase 7 Explicit Retry With a Latched CPU Fallback

Date: 2026-07-29

## Scope

This checkpoint covers explicit retry after an admitted bounded-GPU run has a
durable GPU-to-CPU fallback latch. It verifies that the replacement process
preserves the original GPU admission identity while creating a direct CPU
session, without another GPU attempt.

It does not change source decoding, window boundaries, overlap, joining,
model contracts, or backend selection.

## Frozen Inputs

- App commit: `bcc07731b614ba4e999a460ac46c908c4819f4a2`
- App APK SHA-256:
  `a6a36f549f6dfffe91f90d6e96ca8a90022c96dc5fb0c4e57298cf6d55bc5057`
- AndroidTest APK SHA-256:
  `f9e8bc3efb1e1a663a0380e923b8314450a1aae014d267589e7aad9a1f508e03`
- Model: `uvr_mdxnet_3_9662@2`, artifact SHA-256
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Source fixture: `coast_town_full_mp3`, SHA-256
  `f66be47fc846459f8ac92543b38dab2019765b556cff98539339bbb44cabcae3`
- Device: Galaxy S10, API 31, `arm64-v8a`
- Admitted runtime: `2.1.5-bss.2`, bounded OpenCL FP32, `N=1`

The raw report remains under the ignored Phase 7 build directory. Its SHA-256
is `b9f02a18838efffbf6b57abf4f7cd4039f532384d5e830d526c6f2eb14775940`.
The companion input envelope SHA-256 is
`e1dd667d3445c81407abda97c176be4229ee2e7e0ea7d6cd052a6a9420f69e23`.

## Results

The full external-death scenario passed:

- the first committed segment and journal sequence 6 survived remote death;
- no automatic retry or remote-process relaunch occurred during a 30.021
  second observation window;
- the original native-invocation barrier recorded a real
  `LiteRT 2.1.5 Auto / LiteRtGpu` session with no fallback;
- the harness appended one valid `GpuFallbackLatched` transition at sequence
  7 without changing the original admitted GPU identity;
- the persisted user preference was changed to `tryGpu=false`, but explicit
  retry correctly preserved the admitted `tryGpu=true`, bounded OpenCL FP32
  `N=1` identity, and fallback latch;
- the replacement process and execution generation were distinct, retained
  the first segment, and appended exactly one previous-owner-death transition;
- its native-invocation barrier recorded `LiteRT 2.1.5 / LiteRtCpu` directly,
  with no fallback stage or reason. An erroneous Auto setup followed by a new
  fallback would instead report the Auto runtime and a fallback stage; and
- Pause left a durable two-segment partial cache and released the cache lock,
  processing service, notification, wake lock, and remote process.

The complete `SourceSeparationModelAwareEngineTest` also passed. Its host-level
case independently verifies that an abandoned latched request emits a CPU
backend policy while retaining both the admitted GPU identity and latch.

## Limits

The latch transition is injected into the durable journal after process death.
This deliberately tests restart policy without relying on the older automatic
GPU fault-injection profile, which is not the production bounded OpenCL
profile. It does not qualify the production GPU failure that originally
creates a latch; that remains part of the final bounded-runtime fallback
matrix.

This row covers S10 only. A real two-APK application update remains the final
open Phase 7B explicit-retry boundary.
