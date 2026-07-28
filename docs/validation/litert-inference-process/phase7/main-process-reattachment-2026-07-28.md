# Phase 7 main-process reattachment

Status: product `MainActivity` reattachment passed on S10 and S25 for CPU and
bounded GPU at both the durable `SegmentRunning`/zero-commit boundary and the
first committed-segment boundary

Product and debug-harness revision:

- `55e6f5f3803c3e500f4ba9b9b13e38fa85b5cbb5`

Supporting test-infrastructure revisions:

- `736deb21` (non-instrumentation main-death harness)
- `1c9a9c71` (portable debug main-process self-termination)
- `f8e9a14f` (parameterized boundary and committed-segment integrity evidence)

Device-test runners: `phase7-runner-v34` and `phase7-runner-v47`

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

The second matrix selects `after-first-committed-segment`. Before terminating
the main process, scenario schema 2 freezes segment 0's vocals and instrumental
paths, byte sizes, and SHA-256 values from the durable journal. Final
validation requires those exact records to remain present and unchanged after
product reattachment and full completion. This distinguishes continuation
after an atomic cache commit from the earlier zero-commit boundary.

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

### First committed segment

| Device | API | Backend | Runtime elapsed | Journal sequence before death / before harness / final | Segments before / final | Result |
| --- | ---: | --- | ---: | --- | --- | --- |
| Samsung S25 (`SM-S9310`) | 35 | CPU FP32 | 117,836 ms | 5 / 10 / 102 | 1 / 48 | Pass |
| Samsung S25 (`SM-S9310`) | 35 | bounded GPU FP32 | 54,985 ms | 6 / 14 / 102 | 1 / 48 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | CPU FP32 | 196,068 ms | 5 / 12 / 102 | 1 / 48 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | bounded GPU FP32 | 180,638 ms | 6 / 12 / 102 | 1 / 48 | Pass |

All four runs killed the main process only after segment 0 was committed.
Segment 0's two paths, byte sizes, and content hashes survived unchanged. Each
replacement product observer connected before the validation bridge obtained
the coordinator, reused the original remote PID/run/generation, issued no
second start, and completed exactly 48 unique segments. Both GPU rows admitted
the exact bounded N=1 profile and completed without fallback.

The first-commit matrix uses app commit
`f8e9a14fb27f990dcd983ff29bd29ea407fe0272`, app APK SHA-256
`8c0c821ec41088deb123e0cd53f39a935b08440b6844a9e2856b0ca2844a0d12`,
test APK SHA-256
`4d4c39855d0600627f3015ce8dd3be88b41c9ae42884db426f5d4e03b5ec1d29`,
runner `phase7-runner-v47`, and scenario schema 2.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/<device>/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-product-main-death-cpu-v1` | `1df332b3796f72c2da39430f556aba04516cbbe5948d963e212d88bcfaacd01f` | `381e36eda17911f8836c7abfb1bfbd2b3f82c21a997b3d9c7d871758acb017ff` |
| `phase7-s25-product-main-death-gpu-v1` | `290e815b6010a44878afacc85cc7c9a21c1bce61c0f92670a39a934c165be8de` | `8be758183a5411569a6ca196ce8b277dc6d34b243ffef9ca779f4471cea9f474` |
| `phase7-s10-product-main-death-cpu-v1` | `76acd1ae27f649d3c56ebf3f56e247c9bdd501c5dbe9bbe791bf4dbc858786b5` | `a2c80fc04d18224967ca3f884715c5a93f8ea52b06d5fa14873c3c42535afba4` |
| `phase7-s10-product-main-death-gpu-v1` | `75a7c176aa35ae2632dc40a0d061c534e6563aa9ff02fb34fd39f83ed418c3c9` | `ebd67c3ac47f94a72af1a637947f3f39742f4d05e06215ad3266c9b67c10cac7` |

First committed-segment evidence:

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-main-death-first-commit-cpu-v1` | `6a8d6d6ea2e5b483943d1acd3b48d1aeaa3d9717519d4d83777156f13e9115b3` | `dc72c07a0af0b2551c591e24d612a13de0d51a3ba4ad61d5d9456f8b3ecb63b6` |
| `phase7-s25-main-death-first-commit-gpu-v1` | `48af81b0ac6a91010eef5b794cbf09184afae2ec42bebebb5455795dde509888` | `708e125dbecb6fc8ba3b5cd3ee5cab550b3942840166f4d1465153bb1793fc36` |
| `phase7-s10-main-death-first-commit-cpu-v1` | `7819ca831141e1c1d618234f87e8ec52bfe535e67c7ab50ee4b26119b7fb9fd4` | `7416ab597e3680d8811b59604b2904a43f0ce0a984472954d56aa63be7b1a84c` |
| `phase7-s10-main-death-first-commit-gpu-v1` | `417ee490eafae91cf7848f8a14800502e238ca76a2f1374701d05f72328fb9e6` | `262b33c2852b90923f4f7079772c163d8a3b5777df337ad08836ff0cb443a935` |

## Remaining scope

- Repeat main-process death before foreground-service handoff, during a native
  invocation, after final segment publication, and during terminal journal
  commit.
- Exercise active original-playback continuity, Pause, Cancel, recents removal,
  force-stop, model deletion, and cache clearing around main-process death.
- Select the inference-process death policy before testing remote death at the
  same boundaries. No automatic remote restart is proved by this matrix.
- Add process-incarnation start ticks, notification lifetime, and wake-lock
  snapshots to the committed evidence bundle during release qualification.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this work.
