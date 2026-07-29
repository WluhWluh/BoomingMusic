# Phase 7 remote-death cache invalidation

Status: S25 passed in both backend-policy directions and S10 passed from CPU
to bounded GPU; the abandoned cache and its frozen policy are discarded, and
only an explicit start creates fresh work

Product and test-harness revision:

- `76d5bc9fd0ee26bbcd9faac01647cc342f969241`
- S10 extension: `2eed0c0954c7ff5044fbc7c6e01022573246ccb6`

Device-test runner: `phase7-runner-v55`

Run-journal schema: 6

## Scope

This is a focused extension of the
[remote-process death matrix](remote-process-death-2026-07-28.md). Each run
starts an independent manual full-song separation, waits for segment 0 to be
committed, externally terminates the exact inference-process incarnation, and
observes at least 30 seconds without automatic relaunch. The old journal and
entry digest must remain unchanged during that interval, and the old lock,
foreground service, notification, and wake lock must be released.

Instead of resuming the partial cache, the product runtime facade then deletes
the exact cache entry. The test requires the manifest, journal, committed
segment, and old admitted backend policy to disappear together. It changes the
persistent `tryGpu` setting to the opposite value and performs one explicit
start through the production foreground-worker coordinator.

A debug-only native-invocation barrier freezes the new process before its first
window can be committed. At that point the new journal must:

- start with sequence 1 `Admitted`;
- contain zero committed segments and no `PreviousOwnerDied` transition;
- use a new PID, process start tick, process generation, and run ID; and
- use the current `tryGpu` value rather than the deleted journal's value.

The test then requests Pause and releases the barrier. One window may finish
before the normal safe-window Pause boundary is observed. Terminal validation
requires durable `Paused`, released cache and processing ownership, and no
processing service, notification, or inference wake lock.

This test exercises the production cache repository, runtime facade,
foreground-worker coordinator, independent service, and Pause cleanup. A
separate S10 Compose instrumentation transaction also exercises the visible
cache-management Delete action through its production ViewModel; see
[main-process product state](main-process-product-state-2026-07-29.md).

## Frozen inputs

- Devices: Samsung S25 (`SM-S9310`), API 35, and Samsung S10 (`SM-G9730`),
  API 31; both `arm64-v8a`
- Model: `uvr_mdxnet_3_9662@2`
- Model SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Fixture: `coast_town_full_mp3`
- Fixture SHA-256:
  `f66be47fc846459f8ac92543b38dab2019765b556cff98539339bbb44cabcae3`
- App APK SHA-256:
  `e488eb122f5b27a501a71f170ae92f021901bd6524367a5088024e126b9b0225`
- Test APK SHA-256:
  `0fb82b819e8b052cbe3352caa42901139f90719e102fbf9bb7d90eb834efb0a9`
- Catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- GPU profile: `gpu-opencl-bounded-fp32-v1`, LiteRT
  `2.1.5-bss.2`, OpenCL FP32, `kernelBatchSize=1`,
  `commandQueueWindowSize=1`

## Results

| Old admitted backend | Setting after clear | Fresh admitted backend | No-relaunch observation | Fresh sequence / segments | Post-Pause state | Result |
| --- | --- | --- | ---: | --- | --- | --- |
| bounded GPU FP32 | `tryGpu=false` | CPU FP32 | 30,017 ms | 1 / 0 | `Paused` | Pass |
| CPU FP32 | `tryGpu=true` | bounded GPU FP32 | 36,978 ms | 1 / 0 | `Paused` | Pass |
| S10 CPU FP32 | `tryGpu=true` | bounded GPU FP32 | 30,428 ms | 1 / 0 | `Paused` | Pass |

The S25 rows used `adb-run-as-kill-9`; the S10 row used
`adb-am-crash-pid`. All three retained an unchanged sequence-6 old journal and
entry digest during the no-relaunch interval and recorded zero automatic
relaunches or unexpected remote-process samples. The old lock was available
after 3 ms, 5 ms, and 12 ms respectively.

The fresh journals had no previous-owner transition or fallback latch. The GPU
row attested the exact bounded N=1 runtime; the CPU row carried no GPU runtime
identity. Both fresh tasks reached the requested safe Pause after one window
and ended without processing foreground resources.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/remote-death-cache-clear/<device>/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-remote-death-cache-clear-v1` | `183073a4e6c1c85623af72c3ee7d29a13c85730cbc7abe94a67197f08993f3da` | `b3c853a210e56c5c9e353c6391e0bc801af3a6700bc2ccb679eacf0804cf0f24` |
| `phase7-s25-remote-death-cache-clear-cpu-v1` | `b9d0ff021adc941990f9afe477f5670c93101ab44efe9ae201cc88cdf462712e` | `590e1a8d4bd124bae25387cd35cc468b0c776d69a62ea5214544314353bd29a3` |
| `phase7-s10-remote-death-cache-clear-cpu-v1` | `08487ab2b662b185880704c4c3671306841848c058b82327db7d26bb06621710` | `2993b69d5fb98542a2b98e7afe1898ab69959ef18becfe82952249537da8da1c` |

## Remaining scope

- Exercise the visible current-song deletion action and navigation from a
  recreated `MainActivity`; the cache-management Delete action itself has
  passed through the production ViewModel on S10.
- Verify app/runtime mismatch and an already-latched GPU-to-CPU fallback at
  explicit-retry time.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this work.
