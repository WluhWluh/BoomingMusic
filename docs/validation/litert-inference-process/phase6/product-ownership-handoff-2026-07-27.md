# Phase 6 product ownership handoff

Status: exact S10/S25 CPU and bounded-GPU handoff complete; broader lifecycle
qualification remains open

Implementation revision tested:

- `d5c36fd470078d0aeb4c36fe888ef95a71067302`

Execution protocol: 12

Run-journal schema: 5

## Product path

The device test uses the actual product ownership route rather than calling the
remote executor directly:

1. `PlaybackService` starts real playback and waits for the exact separation
   cache, acquiring its existing processing protection.
2. The real `SEPARATE_CURRENT_SONG_OFFLINE` MediaSession command enters the
   production facade and admits a `ManualFullSong` run.
3. The independent foreground service validates the request, creates the
   durable journal, attaches its `mediaProcessing` foreground lease, and
   accepts the exact `cacheKey`, `runId`, and process generation.
4. Only after that accepted event does `PlaybackService` release its processing
   lease for the same cache. Unknown, stale, or unrelated ownership remains
   conservative and cannot release it.
5. Completion is accepted only when the journal and cache manifest identify
   the remote PID and the exact admitted model. Both processing wake locks must
   then be absent from the active `dumpsys power` wake-lock section.

The playback wake-lock tag is
`com.mardous.booming:SourceSeparationProcessing`. The inference tag uses the
runtime package:
`com.wluhwluh.booming.sourcesep.debug:SourceSeparationInference`.

## Frozen inputs

- Model: `uvr_mdxnet_3_9662@2`
- Model SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Fixture: `coast_town_short_wav`, 12-second stereo 44.1 kHz PCM WAV
- Fixture SHA-256:
  `0fa980cace732c47647043d1a5fce3589e54771ca3574b003f152acfa6ceec96`
- Cache key:
  `dc1d709c6620b41263f3ae7cffec778bcf26c3936e790a368f18597afe5638c8`
- GPU profile: `gpu-opencl-bounded-fp32-v1`, artifact
  `2.1.5-bss.2`, OpenCL FP32, `kernelBatchSize=1`,
  `commandQueueWindowSize=1`

## Results

| Device | API | Backend | Full command | Runtime | Exact owner | Lock handoff | Final release |
| --- | ---: | --- | ---: | ---: | --- | --- | --- |
| Samsung S25 (`SM-S9310`) | 35 | CPU FP32 | 9,074 ms | 7,936 ms | Pass | Pass | Pass |
| Samsung S25 (`SM-S9310`) | 35 | bounded GPU FP32 | 7,900 ms | 6,541 ms | Pass | Pass | Pass |
| Samsung S10 (`SM-G9730`) | 31 | CPU FP32 | 16,034 ms | 14,037 ms | Pass | Pass | Pass |
| Samsung S10 (`SM-G9730`) | 31 | bounded GPU FP32 | 18,034 ms | 16,135 ms | Pass | Pass | Pass |

Both GPU rows used `LiteRtGpu` without fallback and admitted the exact N=1
profile. In all four rows, the playback lock was present before remote
acceptance, absent afterward while the inference lock was present, and both
were absent after completion. The playback lease stopped with
`remoteOwnershipChanged`; the exact completed cache was playable.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/<device>/`:

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase6-s25-product-handoff-cpu-v3` | `5274f2dc4b588f7b7c2a540daf68bc32763dc44e190d0569be62b21947b9058a` | `03dc78328351d98e3b877006ac0784e25d82250d216486e88d65df2ef10e69e9` |
| `phase6-s25-product-handoff-gpu-v1` | `38f0ab27568dcbd01f998b44e31d9c4243c12fdeec606b8ace06383b4b6764c8` | `0a09ad05368f10bd26fba1fc7ced8f6f57bf82e89b18b877aa5a328155dd6472` |
| `phase6-s10-product-handoff-cpu-v1` | `ea4184a88c2993e6fba530295db407ddba362c28859fc27045ad237176e2adb8` | `eaa2deff8c3d8b98b535be418b0ee90ffee5d63c7a77abb18f2cc2be5f5d690` |
| `phase6-s10-product-handoff-gpu-v1` | `175df0f010251cb2a6fdf8e4855bf03741f024cc55dc6da045cd5a2f0a7fa3b5` | `86c72068e9890a1561a9fea77b03d7b9ccc6a252fe56e2edbc5eaaeaa6068623` |

## Remaining scope

This result does not claim visible UI-tap coverage, an active-run Pause/Cancel
or cache-loss matrix, a naturally exhausted Android 15 media-processing quota,
API 36/current-highest behavior, long-song screen-off GPU behavior, one-way
GPU fault fallback, graphics-memory bounds, thermal qualification, or
foreground FrameTimeline performance. Those remain explicit later gates and do
not block product implementation work.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this handoff.
