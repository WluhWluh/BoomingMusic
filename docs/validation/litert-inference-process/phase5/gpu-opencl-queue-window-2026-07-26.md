# Phase 5 OpenCL queue-window experiment

Status: effective on S25; materially helpful but incomplete on S10

Release decision: keep as a diagnostic prototype; do not ship the
binary-patched runtime

## Scope

This experiment tests the MACE-style bounded OpenCL queue proposed after the
S25 GPU UI attribution. It changes no source decode, MP3 fallback, DSP,
overlap, join, cache, playback, or model contract.

MACE's pinned
[`WaitForQueueExecution`](https://github.com/XiaoMi/mace/blob/0fc55a548ef41b37fd15fd8944de5155eb09b3c1/mace/runtimes/opencl/core/opencl_helper.cc)
documents that a long command queue can harm UI responsiveness and waits for a
boundary event after a configured command count. The LiteRT prototype applies
the same principle in two steps:

1. redirect the unused Kotlin
   `numStepsOfCommandBufferPreparations` setter to native
   `kernel_batch_size`, causing LiteRT to enqueue ordinary NDRange kernels in
   bounded batches instead of one graph command buffer;
2. interpose `clEnqueueNDRangeKernel`, request an event at each queue-window
   boundary, and wait for that event only while `CompiledModel.run()` is
   active.

Delegate compilation and autotuning remain outside the wait gate. `N=0` uses
the same patched AAR and shim in pass-through mode. Values `N=1,4,8,16,32`
bound the number of inference kernels submitted before a completion wait.

## Artifact

The tested coordinate was
`com.google.ai.edge.litert:litert:2.1.5-bss.oclq4`.
The reproducible builder is
`bss-litert-android@e061de6e5a4170b30ad249f19d8e3b4315989356`.

| Component | SHA-256 |
| --- | --- |
| Tested AAR | `2f71791bac5268f170954d7b673e95aabfab492292cf715690d8691c106eddf7` |
| Patched `libLiteRt.so` | `ae2b996fde27021b070e88b56eebc9626a5261feb72f09791bdac38b2f09abd2` |
| Patched accelerator | `cef6e4dc72485d84c18e673e205064b4a5390bb9ed8b9f61a5361a3f279e6a8c` |
| `libOCLQ.so` | `0bc56359e8d8e0a787558ea1d94099a8b33149a8607032859f31bd2ded9a619d` |

The build script initially preserved ZIP entry timestamps, so rebuilding
byte-identical contents changed the outer AAR hash. The final v2 build
manifest normalizes entries to epoch `315532800`; three consecutive builds
produced AAR SHA-256
`06bf391df0e8b74c5a80ed5d272d847e4147493d17478afe426f30839c81276b`.
All three native component hashes in that reproducible AAR exactly match the
tested APK. The archive normalization itself was not treated as a new device
test.

The runtime patch is strict: it requires the official runtime SHA-256 and one
exact 28-byte setter, then rewrites two arm64 store instructions. Eight
instruction bytes are replaced and four file bytes differ. The app gate is
debug-only and loads the arm64 shim only in an `aarch64` process.

## Method

- App base commit: `eacd5ee0b880657b0f49bca3cbbd51643053db74`
- Hardened experiment integration: `a577c32b`
- App APK:
  `c88825dc953e524679d9b1aa15b221f4cc25d35f3e344c2b734827c9cc2f8be3`
- AndroidTest APK:
  `e281cf508889768c8ef7015231c8759931aa7dcfff43be3384d7f846c84cfc6e`
- Model: exact 9662 FP32,
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Devices: Galaxy S10 / API 31 and Galaxy S25 / API 35
- GPU profile: explicit OpenCL FP32, in-process, single-use
- Tensor sweep: two invocations at each queue window
- Whole-song sweep: 273.699-second Coast Town WAV through the production
  Phase 7 Worker
- UI sweep: four warm-up swipes, reset `gfxinfo`, then twenty alternating
  500 ms swipes while inference remains active
- Final attribution: identical 70-second Perfetto config for pass-through and
  `N=1`

The tested APK was built from the base commit plus the dirty experiment
bridge. Post-test hardening only prevents the bridge from loading for CPU and
non-`aarch64` sessions; it does not alter the tested arm64 GPU path.

## Tensor sweep

Every row produced SNR `93.832758 dB` versus the frozen ORT output, maximum
absolute error `0.0000668764`, and zero same-session reuse error.

| Device | N | Setup | First | Reused | Boundary waits | Mean wait | Maximum wait |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S25 | 0 | 814 ms | 426 ms | 354 ms | 0 | 0 | 0 |
| S25 | 1 | 823 ms | 472 ms | 376 ms | 254 | 2.183 ms | 28.205 ms |
| S25 | 4 | 823 ms | 452 ms | 365 ms | 63 | 8.222 ms | 38.534 ms |
| S25 | 8 | 818 ms | 447 ms | 364 ms | 31 | 14.318 ms | 69.804 ms |
| S25 | 16 | 831 ms | 440 ms | 355 ms | 15 | 23.217 ms | 104.417 ms |
| S25 | 32 | 824 ms | 440 ms | 350 ms | 7 | 37.494 ms | 120.042 ms |
| S10 | 0, warm repeat | 1,074 ms | 2,489 ms | 2,444 ms | 0 | 0 | 0 |
| S10 | 1 | 1,080 ms | 2,612 ms | 2,558 ms | 254 | 19.260 ms | 372.430 ms |
| S10 | 4 | 1,068 ms | 2,451 ms | 2,445 ms | 63 | 74.180 ms | 480.790 ms |
| S10 | 8 | 1,087 ms | 2,482 ms | 2,448 ms | 31 | 122.820 ms | 683.560 ms |
| S10 | 16 | 1,126 ms | 2,465 ms | 2,433 ms | 15 | 181.990 ms | 902.770 ms |
| S10 | 32 | 1,110 ms | 2,520 ms | 2,474 ms | 7 | 314.900 ms | 1,052.970 ms |

`N=1` adds approximately 5-6% to one isolated reused invocation. On S10, a
single kernel can still occupy the GPU for several hundred milliseconds, so
no queue-window value can enforce one display-frame latency there.

## Foreground result

Perfetto uses actual app surface frames inside the marked swipe interval.
Every frame over 20 ms overlaps an app `GPU completion` wait in all four
samples.

| Device | N | Interval | FPS | Frames >20 ms | Frames >200 ms | P99 | Maximum frame | Maximum GPU wait |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S25 | 0 | 15.696 s | 61.481 | 81 | 78 | 244.139 ms | 249.658 ms | 246.958 ms |
| S25 | 1 | 14.831 s | 116.786 | 85 | 0 | 26.593 ms | 35.121 ms | 30.373 ms |
| S10 | 0 | 23.079 s | 22.315 | 353 | 14 | 1,188.152 ms | 1,364.312 ms | 1,251.186 ms |
| S10 | 1 | 15.635 s | 36.968 | 263 | 1 | 48.400 ms | 200.973 ms | 411.613 ms |

On S25, `N=1` removes every frame above 50 ms and restores nearly the full
120 Hz presentation rate. On S10 it raises presentation rate by 66%, removes
13 of 14 frames above 200 ms, and cuts the maximum frame by 85%. S10 remains
visibly imperfect: 263 frames still exceed 20 ms and one reaches 201 ms.

The S10 Android 12 Perfetto build does not expose the `android.log` data
source. Its `N=1` interval was aligned with retained epoch log markers and the
trace REALTIME/BOOTTIME snapshot. The `N=0` interval used host UTC plus the
measured 136 ms ADB log latency and was cross-checked against the first and
last input-event clusters. Neither S10 trace reports ftrace overrun, buffer
overwrite, or packet loss. Old producers emitted 41 and 306 negative-timestamp
packets, but app FrameTimeline, GPU-completion waits, and all swipe clusters
remain present.

## Whole-song result

Two no-Perfetto pairs were run in opposite order on each phone. Android
thermal status remained 0.

| Device | N | Full-song samples | First-ready samples | Peak PSS samples |
| --- | ---: | --- | --- | --- |
| S25 | 0 | 31.049 s, 40.255 s | 3.839 s, 4.390 s | 868.8 MiB, 871.2 MiB |
| S25 | 1 | 33.844 s, 33.387 s | 3.876 s, 4.069 s | 824.4 MiB, 812.3 MiB |
| S10 | 0 | 181.340 s, 212.275 s | 13.636 s, 14.368 s | 659.7 MiB, 677.0 MiB |
| S10 | 1 | 175.753 s, 192.241 s | 12.557 s, 13.682 s | 675.8 MiB, 679.8 MiB |

The isolated tensor cost is measurable, but the complete pipeline shows no
systematic `N=1` throughput regression outside normal device variance.
Perfetto itself disproportionately slows the 6,223 event waits and is not used
as a production throughput sample.

All 11 completed whole-song runs across `N=0,1,4,8` produced exactly
12,070,130 finite frames, valid joins, and identical WAV and promoted FLAC
hashes for both stems. Queue bounding therefore changes scheduling, not
numerical output or cache identity.

## Decision

- `N=1` is the only useful tested window. Larger values progressively restore
  long queue ownership; no value below 1 exists to split S10's long kernel.
- The MACE mechanism is validated on both Samsung generations. It nearly
  solves S25 foreground contention and substantially reduces S10 stalls.
- It does not yet make foreground GPU universally UI-safe. Keep CPU while
  visible as the lowest-risk fallback for devices that fail an eventual
  responsiveness qualification.
- Do not ship this binary patch. A production implementation should use an
  upstream API or a pinned source-built LiteRT surface, then repeat output,
  throughput, thermal, memory, and UI gates across Adreno, Mali, and at least
  one non-Samsung device.
- If source-level `N=1` remains acceptable, test a policy that selects bounded
  GPU while the UI is visible and normal command-buffer GPU while backgrounded.
  Session recreation cost and transition behavior must be measured.

## Evidence

The compact record
[`gpu-opencl-queue-window-v1.json`](gpu-opencl-queue-window-v1.json) contains
the run IDs, ignored-artifact hashes, exact metrics, output hashes, device
fingerprints, and trace-boundary notes. Raw reports, logs, `gfxinfo`, and traces
remain under ignored `build/perfetto-gpu-ui-s25/` and
`build/opencl-queue-experiment/`.

Perfetto Trace Processor SHA-256:
`100334b6091596fbc97f872556849a5747bf47a7f7190c485ba8cea8d2409c7b`.
