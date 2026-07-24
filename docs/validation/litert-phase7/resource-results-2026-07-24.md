# Phase 7 Resource, Thread, and Thermal Results

These are local pre-promotion results for the 9662 FP32 contract
`uvr_mdxnet_3_9662@2` and LiteRT 2.1.5. They close the exploratory Phase 7C
CPU, thread, GPU, and cancellation measurements. They do not replace the final
matrix from the frozen promotion commit.

## Frozen Inputs

- Model artifact SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Bundled catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- Full-track WAV SHA-256:
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`
- Full-track duration: 273.699 seconds, 12,070,130 output frames
- Runtime/report inputs: LiteRT 2.1.5, runner v11, thresholds v2, fixtures v2
- App APK SHA-256: arm64
  `499b3c1f193a9a6988254185245d99fb1cf9db13e3f374335740758ce69eeaa1`,
  arm32 `5ffae498b66fc8a424e3fbd39c04a35ef1727587717aa2a9fe1944d74ec96489`,
  x86_64 `fc9ad0450753ec5e1e1c54c4377e881d7f0eddad03a4f856cbe066f1bedd7ff0`
- Resource-matrix AndroidTest APK SHA-256:
  `e48238f3937ac23a3ce33a43cdeb8e91b01303a03ba905354b82766e8a6f9e4c`
- Cancellation AndroidTest APK SHA-256:
  `3948c04f242d3c1dae3b5fa362b74cabe7acebed1d21b5b0618d85170760e5a7`

Each resource row used one cold session followed by three warm sessions. The
model remained installed, but every timing run used a fresh cache identity.
The runner sampled process memory, process CPU time, and thermal status every
two seconds. Emulator thermal and timing values are diagnostic only.

The resource runs span several runner-only commits while retaining identical
APK hashes. Commit `ad766348` subsequently made `-SkipBuild` read a checked
build-identity marker instead of the current Git `HEAD`. The final promotion
matrix must use that behavior so its source revision and binary hashes cannot
drift apart.

## Default CPU Matrix

The production default resolved to four XNNPACK threads on every target.

| Target | Full song (s) | First ready (s) | Process CPU (s) | PSS delta (MiB) | Peak Java / native / graphics (MiB) | Peak thermal |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| Galaxy S25 arm64 | 98.4-109.9 | 4.47-4.83 | 321.9-359.0 | 742.9-747.9 | 96.1-102.5 / 639.0-642.5 / 0 | 2 |
| Galaxy S10 arm64 | 180.5-187.0 | 8.66-9.06 | 581.7-613.0 | 718.3-742.1 | 74.2-85.4 / 641.7-642.5 / 0 | 0 |
| Galaxy S10 arm32 | 234.0-238.0 | 10.46-12.04 | 717.5-724.1 | 563.0-577.5 | 79.2-85.0 / 489.5-490.2 / 0 | 0 |
| API 37 x86_64 AVD | 168.0-179.8 | 12.12-13.87 | 393.9-414.0 | 869.4-876.3 | 55.0-57.2 / 796.9-797.2 / 0 | 0 |

All 16 runs completed the exact worker/cache flow. The S25 reached Android
thermal status 2 during two runs. Neither S10 run class reported throttling.
The x86_64 row is useful for regression coverage but does not qualify physical
device performance or thermal behavior.

The first x86_64 attempts repeatedly used `adb install -r` and eventually hit
an API 37 emulator `failed to complete startup` ANR before instrumentation
entered LiteRT. This was not a native inference crash. The runner now verifies
the installed app and test APK hashes and skips redundant installation. Four
consecutive runs then passed without the startup ANR.

## Auto GPU Matrix

All eight Auto runs selected `LiteRtGpu`; none fell back to CPU.

| Target | Full song (s) | First ready (s) | Process CPU (s) | PSS delta (MiB) | Peak Java / native / graphics (MiB) | Peak thermal |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| Galaxy S25 arm64 | 36.5-36.7 | 3.61-3.63 | 28.7-29.2 | 497.8-505.6 | 95.7-101.3 / 105.3-108.5 / 282.2 | 0 |
| Galaxy S10 arm64 | 165.6-211.6 | 11.56-13.10 | 89.9-148.2 | 427.0-440.5 | 76.0-80.4 / 120.9-123.3 / 265.8 | 0 |

GPU is decisively faster on S25 and reduces total PSS by about 240 MiB. S10
GPU wall time varies enough to overlap, beat, or trail CPU, but its PSS remains
about 280-315 MiB lower and its process CPU time is substantially lower. Keep
GPU-first Auto with the existing one-way CPU fallback. Do not add a user-facing
GPU-only or CPU-only preference.

## CPU Thread Matrix

Ranges below use warm sessions. The thread override is validation-only and
does not create an app setting.

| Target | Threads | Full song (s) | Process CPU (s) | PSS delta (MiB) |
| --- | ---: | ---: | ---: | ---: |
| Galaxy S25 arm64 | 2 | 136.7-137.3 | 262.4-262.9 | 744.2-745.9 |
| Galaxy S25 arm64 | 3 | 102.0-106.2 | 268.9-279.5 | 740.3-742.5 |
| Galaxy S25 arm64 | 4 | 104.1-109.9 | 341.3-359.0 | 742.9-747.9 |
| Galaxy S10 arm64 | 2 | 235.9-239.1 | 460.8-468.9 | 716.7-732.5 |
| Galaxy S10 arm64 | 3 | 201.9-205.3 | 529.2-536.1 | 722.0-729.4 |
| Galaxy S10 arm64 | 4 | 185.0-187.0 | 598.5-613.0 | 718.3-729.4 |

Three threads match four-thread wall time on S25 while using less process CPU,
but are about 8-10 percent slower on S10. Two threads are about 25 percent
slower on both devices. Retain the existing four-thread default because S10 is
the lower-bound physical device and no material PSS reduction accompanied the
slower settings. Do not add a thread-count preference.

## Cancellation

The isolated lifecycle scenario waits for one ready window, calls `cancel()`,
then measures until the worker becomes inactive. These rows use the production
default of four CPU threads and the 30,000 ms v2 limit.

| Target | Latency (ms) | Cache state | Sessions | Result |
| --- | ---: | --- | ---: | --- |
| Galaxy S25 arm64 | 1 | `Incomplete` | 1 | passed |
| Galaxy S10 arm64 | 5 | `Incomplete` | 1 | passed |
| Galaxy S10 arm32 | 3 | `Incomplete` | 1 | passed |
| API 37 x86_64 AVD | 9 | `Incomplete` | 1 | passed |

The measured calls landed between native invocations, so these values establish
the coordinator's prompt cancellation path. Existing direct-engine tests still
cover cancellation requested during an invocation, where LiteRT must return
before the output can be discarded.

## HQ4 No-Allocation Gate

The current HQ4 artifact is 59,057,268 bytes with SHA-256
`5f091562bd0297ff2223015a219d12cc1136a9d54df975680ff4eb239629c742`.
Earlier window probes measured about 0.76-1.3 GiB of PSS, above the 256 MiB
target and 384 MiB hard limit. Phase 7 therefore verifies rejection before
model or native allocation instead of repeating a known-disqualified run.

| Target | Catalog result | Allocator calls | LiteRT maps | Model exists | PSS change (KiB) |
| --- | --- | ---: | ---: | --- | ---: |
| Galaxy S25 arm64 | rejected | 0 | 0 | no | +84 |
| Galaxy S10 arm64 | rejected | 0 | 0 | no | +26 |
| Galaxy S10 arm32 | unsupported | 0 | 0 | no | +54 |
| API 37 x86_64 AVD | rejected | 0 | 0 | no | -50 |
| API 26 x86 AVD | unsupported | 0 | 0 | no | +80 |

All five reports use app commit `4c680f23`, exact per-ABI app APK hashes, and
AndroidTest APK SHA-256
`aa725b78ce9b4452fdbaa0216b57b7bcb431173b2fb714bde10816b8efa97966`.
The reports and checksums are in [`hq4-preflight`](hq4-preflight/). HQ4 remains
download-only. Any artifact, contract, runtime, or resource-policy change
reopens the S10 hard gate before activation can be reconsidered.

## Supplemental x86 Runtime Faults

`test_verify_litert_native.py` now runs in Android CI, the API 26 x86 smoke
job, and release builds. Its four cases are:

| Input | Expected terminal result | Result |
| --- | --- | --- |
| pinned x86 ELF | exact size, SHA-256, exports, and dependencies accepted | passed |
| missing file | local `library is missing` build failure | passed |
| same-size tampered ELF | local SHA-256 build failure | passed |
| ELF32 with ARM machine ID | local `expected Intel 80386` build failure | passed |

The verifier checks ELF class and machine before the pinned hash, so a wrong
architecture is reported distinctly from tampering. These are pre-package
terminal failures: they cannot start ORT, select another model, or allocate a
second inference session. Existing production-graph tests separately prevent
normal runtime routes from constructing the ORT oracle.

## Dual-Runtime Size Baseline

The full machine-readable inventory is
[`dual-runtime-inventory-2026-07-24.json`](dual-runtime-inventory-2026-07-24.json).
Every installed row matched the local split by SHA-256 and `primaryCpuAbi`.
Models, app data, and separation caches are excluded.

| ABI | APK (MiB) | ZIP payload (MiB) | Native (MiB) | LiteRT (MiB) | ORT (MiB) | Installed code path (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| arm64-v8a | 77.00 | 127.83 | 36.53 | 7.68 | 26.25 | 77.10 |
| armeabi-v7a | 64.56 | 115.39 | 24.09 | 3.34 | 18.68 | 64.60 |
| x86_64 | 85.11 | 135.94 | 44.64 | 10.18 | 31.76 | 85.11 |
| x86 | 81.79 | 132.63 | 41.33 | 7.14 | 31.63 | 81.80 |
| universal | 187.19 | 237.90 | 146.60 | 28.34 | 108.32 | not installed |

This is intentionally the Phase 7 dual-runtime baseline. ORT remains packaged
only as the unreachable regression oracle. Phase 8 must remove it, rebuild the
same inventory, and apply the separate 10 MiB target / 16 MiB hard LiteRT
runtime-increment gate; this table is not post-removal size acceptance.

## Phase 7C Decisions

- Retain the four-thread CPU formula and GPU-first Auto policy.
- Treat x86_64 resource numbers as diagnostics, not device qualification.
- Keep HQ4 download-only under the existing 256 MiB target and 384 MiB hard
  PSS gate; the current artifact is rejected without allocation on every ABI.
- Reject missing, altered, or wrong-architecture supplemental x86 runtimes in
  the build supply chain before an APK or inference session can be produced.
- Use the checked dual-runtime inventory as the Phase 8 pre-removal baseline.
- Rerun the final promotion rows from one frozen commit after listening, UI,
  KARA, catalog, and remaining lifecycle decisions are complete.
