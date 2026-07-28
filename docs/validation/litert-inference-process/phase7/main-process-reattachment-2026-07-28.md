# Phase 7 main-process reattachment

Status: product `MainActivity` reattachment passed on S10 and S25 for CPU and
bounded GPU at the durable `SegmentRunning` boundary

Product and debug-harness revision:

- `55e6f5f3803c3e500f4ba9b9b13e38fa85b5cbb5`

Supporting test-infrastructure revisions:

- `736deb21` (non-instrumentation main-death harness)
- `1c9a9c71` (portable debug main-process self-termination)

Device-test runner: `phase7-runner-v34`

Execution protocol: 13

Run-journal schema: 6

## Scope

This matrix starts the debug app normally and does not invoke `am instrument`
for the process-death stage. The production coordinator starts an independent
`ManualFullSong` run. Once the remote journal has durably recorded
`SegmentRunning`, while no segment is committed, the debug main process writes
its scenario envelope and terminates itself with
`Process.killProcess(Process.myPid())`.

The runner then requires the old main PID to disappear while the inference PID
remains alive. It starts the real `MainActivity` with `am start -W`. Before the
debug validation command is allowed to obtain the coordinator, the journal
must already contain the replacement `ObserverConnected` transition. This
separates product startup recovery from recovery triggered by the test bridge.

The admitted run must retain one cache key, run ID, process generation, remote
PID, backend policy, and writer through completion. No second `start()` is
issued. Terminal validation requires all 48 segments, released protected-cache
ownership, a valid completed cache, and readable vocals and instrumental
stems. A later normal playback update may move the worker UI state from
`Completed` to `Idle`; the terminal state is therefore not required to remain
latched after ownership has been released.

## Frozen inputs

- Model: `uvr_mdxnet_3_9662@2`
- Model SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Fixture: `coast_town_full_wav`, 273.699-second stereo 44.1 kHz PCM WAV
- Fixture SHA-256:
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`
- App APK SHA-256:
  `19dec6c7ee326312719e628f1d511579ddfa6cc463dcf08da23d2384f23d24b5`
- GPU profile: `gpu-opencl-bounded-fp32-v1`, OpenCL FP32,
  `kernelBatchSize=1`, `commandQueueWindowSize=1`

## Results

| Device | API | Backend | Runtime elapsed | Journal sequence before death / before harness / final | Segments | Result |
| --- | ---: | --- | ---: | --- | ---: | --- |
| Samsung S25 (`SM-S9310`) | 35 | CPU FP32 | 93,818 ms | 4 / 8 / 102 | 48 | Pass |
| Samsung S25 (`SM-S9310`) | 35 | bounded GPU FP32 | 42,895 ms | 4 / 10 / 102 | 48 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | CPU FP32 | 189,716 ms | 4 / 10 / 102 | 48 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | bounded GPU FP32 | 189,444 ms | 4 / 10 / 102 | 48 | Pass |

Every run recorded exactly three observer transitions: initial connection,
main-process death disconnection, and replacement product connection. All four
reports set `productObserverConnectedBeforeHarness=true`, retained the original
remote process identity, and recorded `secondStartIssued=false`. Both GPU runs
used the exact bounded N=1 contract and completed as `LiteRtGpu` without
fallback.

The elapsed values confirm lifecycle completion but are not a performance
qualification. App cold start, media-library work, device temperature, and the
main-process recreation itself were not controlled as a benchmark pair.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/<device>/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-product-main-death-cpu-v1` | `1df332b3796f72c2da39430f556aba04516cbbe5948d963e212d88bcfaacd01f` | `381e36eda17911f8836c7abfb1bfbd2b3f82c21a997b3d9c7d871758acb017ff` |
| `phase7-s25-product-main-death-gpu-v1` | `290e815b6010a44878afacc85cc7c9a21c1bce61c0f92670a39a934c165be8de` | `8be758183a5411569a6ca196ce8b277dc6d34b243ffef9ca779f4471cea9f474` |
| `phase7-s10-product-main-death-cpu-v1` | `76acd1ae27f649d3c56ebf3f56e247c9bdd501c5dbe9bbe791bf4dbc858786b5` | `a2c80fc04d18224967ca3f884715c5a93f8ea52b06d5fa14873c3c42535afba4` |
| `phase7-s10-product-main-death-gpu-v1` | `75a7c176aa35ae2632dc40a0d061c534e6563aa9ff02fb34fd39f83ed418c3c9` | `ebd67c3ac47f94a72af1a637947f3f39742f4d05e06215ad3266c9b67c10cac7` |

## Remaining scope

- Repeat main-process death before foreground-service handoff, after a
  committed window, during a native invocation, after final segment
  publication, and during terminal journal commit.
- Exercise active original-playback continuity, Pause, Cancel, recents removal,
  force-stop, model deletion, and cache clearing around main-process death.
- Select the inference-process death policy before testing remote death at the
  same boundaries. No automatic remote restart is proved by this matrix.
- Add process-incarnation start ticks, notification lifetime, and wake-lock
  snapshots to the committed evidence bundle during release qualification.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this work.
