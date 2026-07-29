# Phase 7 remote-process death and explicit retry

Status: the zero-automatic-retry policy passed on S10 and S25 for CPU and
bounded GPU after the first committed segment

Product and test-harness revision:

- `c8f055c067d2dae57752aa24290b143652e8fa3c`

Device-test runner: `phase7-runner-v54`

Run-journal schema: 6

## Selected policy

`SourceSeparationInferenceService` uses `START_NOT_STICKY`. Unexpected loss of
the inference process has an automatic retry budget of zero. The main process
reports a stable localized failure, but it does not rewrite the remote owner's
nonterminal journal, start another process, or allocate another native session.
The partial cache and its durable `Running` journal remain available for an
explicit user retry.

An explicit retry is a new process and execution generation, not an Android
service restart. It must reacquire the exact cache-entry lock, prove that the
old process incarnation is dead, adopt the durable cache state, and append
`PreviousOwnerDied`, the old observer's
`ObserverDisconnected(reason=owner-process-died)`, and the new `Admitted`
transition. The new owner resumes the backend policy frozen by the original
admission. A later change to the persistent `tryGpu` setting affects only a
different newly admitted run.

The confirmed-dead-process cleanup path removes an orphaned manual full-song
processing service and notification. It is gated by the old PID and process
start ticks and does not run while that process incarnation may still be
alive. Pause, Cancel, force-stop, foreground-service timeout, model/cache
invalidation, and protocol incompatibility remain separate terminal or
re-admission paths; none may trigger an automatic retry.

## Scope

Each run started an independent manual full-song separation and waited for
segment 0 to be committed. The runner then terminated the exact inference
process from outside the app while leaving the main process alive. It sampled
process state for at least 30 seconds and required:

- no automatic inference-process relaunch;
- no journal sequence, journal hash, or cache-entry hash change;
- the main process to remain alive and expose the stable failure state;
- release of the old exact-entry kernel lock, processing service,
  notification, and inference wake lock; and
- the partial cache to retain exactly one committed segment.

Before explicit retry, the runner changed the persisted `tryGpu` value to the
opposite of the original admitted value. Retry then had to use a new PID,
process start tick, process generation, and execution run ID while preserving
the original backend policy. Completion required 48 unique committed segments,
readable vocals and instrumental stems, released ownership, and no terminal
foreground resources.

The external death request used `run-as ... kill -9` where Android allowed it.
On the S10 build, `run-as` could not deliver the signal, so the runner used
`am crash --user <id> <pid>` and verified that the exact old PID disappeared.
The report records the mechanism actually used.

## Frozen inputs

- Model: `uvr_mdxnet_3_9662@2`
- Model SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Fixture: `coast_town_full_mp3`
- Fixture SHA-256:
  `f66be47fc846459f8ac92543b38dab2019765b556cff98539339bbb44cabcae3`
- App APK SHA-256:
  `67661dad94f40c87d70f9028cd7437460d1de8287455340568dc3b1093350f3d`
- Test APK SHA-256:
  `0fb82b819e8b052cbe3352caa42901139f90719e102fbf9bb7d90eb834efb0a9`
- Catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- GPU profile: `gpu-opencl-bounded-fp32-v1`, LiteRT
  `2.1.5-bss.2`, OpenCL FP32, `kernelBatchSize=1`,
  `commandQueueWindowSize=1`

## Results

| Device | API | Backend | Death request | No-relaunch observation | Original / changed / resumed `tryGpu` | Segments before / final | Result |
| --- | ---: | --- | --- | ---: | --- | --- | --- |
| Samsung S25 (`SM-S9310`) | 35 | CPU FP32 | `adb-run-as-kill-9` | 30,233 ms | false / true / false | 1 / 48 | Pass |
| Samsung S25 (`SM-S9310`) | 35 | bounded GPU FP32 | `adb-run-as-kill-9` | 30,081 ms | true / false / true | 1 / 48 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | CPU FP32 | `adb-am-crash-pid` | 30,592 ms | false / true / false | 1 / 48 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | bounded GPU FP32 | `adb-am-crash-pid` | 30,292 ms | true / false / true | 1 / 48 | Pass |

All four reports recorded automatic retry budget 0, automatic relaunch count
0, no unexpected remote-process sample, unchanged journal sequence 6 during
the silent interval, a released old cache lock, and
`ObserverDisconnected(reason=owner-process-died)`. The replacement owner
preserved segment 0 and completed all 48 planned segments.

Both GPU rows attested to the exact bounded N=1 runtime and completed as
`LiteRtGpu` without CPU fallback. Both CPU rows resumed as `LiteRtCpu` without
any GPU allocation attempt despite the persisted preference having been
changed to enabled. Every row ended without the processing service,
notification, inference wake lock, or an active inference owner. An idle main
process scheduler is not treated as active inference ownership.

Elapsed inference values are lifecycle observations, not a performance
comparison. Device temperature, setup state, the external death mechanism,
and retry timing were not controlled as benchmark pairs.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/remote-death-final/<device>/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-remote-death-cpu-final-v2` | `bda91be4b6c99916e5fefa45de6f10ce9d8a6d2ed6dd2860a4859d2cb40bc499` | `08c8c33b614445648ad8d922b04de3c6043a5f8779311c2097c6b6f8309ccaa5` |
| `phase7-s25-remote-death-gpu-final-v2` | `09946555a99372147e3c9bd8193b0c9c2d9bcdf78f4bf200fdba9b3f23a915ab` | `c6ae9ae7195b7794fa86e7747ba696e53bb2e2039c74594f2d9474e8ac5ec6b5` |
| `phase7-s10-remote-death-cpu-final-v2` | `608541e479b60f2ef26507544c12f4cee09979f63d1125316432fa11154c3323` | `7eee737c05bbbb8376f92d0ffeb1a8af4650e6fa15ca44dd5f572b8ed6ab648f` |
| `phase7-s10-remote-death-gpu-final-v1` | `4fdfec5c9b49b18db9055cb625da9c1eaeb4de17d66f21ef57b6a760feab6e1a` | `eaa4c62b3987767c8e74f287f7ebd251beddb3452fc0685372daf764cc2f2f5c` |

## Remaining scope

- Repeat inference-process death before foreground-service handoff, during
  native invocation, after final segment publication, and during terminal
  journal commit.
- Exercise bounded-GPU death during an N=1 event wait, output read, and
  session close, including a journal whose CPU fallback latch is already set.
- Cache clearing now has a focused two-direction S25 result in
  [Phase 7 remote-death cache invalidation](remote-process-death-cache-clear-2026-07-29.md).
  Model switching/deletion and S10 cache-clear coverage remain open.
- Verify active original-playback continuity and cache-management UI around
  remote-process death and explicit retry.
- Repeat the no-resurrection gate on the eventual highest-API release target.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this work.
