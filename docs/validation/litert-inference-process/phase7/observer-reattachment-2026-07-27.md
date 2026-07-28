# Phase 7 observer reattachment

Status: S10 and S25 CPU and bounded-GPU observer reattachment passed; actual
main-process death and remote-process restart remain open

Product implementation revision:

- `d655eaa72f5d5ea1716920e505609f69d52a6203`
- `e52adcb04217296338b05b37f4631390d034d1ca` (unobserved deadline)

Device-test runner revision:

- `d5929b18` (`phase7-runner-v32`)

Execution protocol: 13

Run-journal schema: 6

## Scope

The test deliberately throws from the original Binder callback after the first
durable progress event. The inference service must detach that observer without
canceling the eligible `ManualFullSong` run. A new production recovery client
then:

1. finds the nonterminal independent journal;
2. queries the service's exact active descriptor;
3. rejects any identity mismatch before adoption;
4. applies the current snapshot as its event-sequence baseline;
5. reconstructs worker UI and protected-cache ownership;
6. observes the same run to completion without calling `start()` again; and
7. closes the terminal run so its foreground service and wake lock are released.

The original caller must fail with
`SourceSeparationRemoteCallbackException`. The journal must contain one initial
observer connection, one persisted disconnection, and one replacement
connection, while retaining one `runId`, process generation, writer, and cache
key through completion.

This is a deterministic observer-loss test in a retained main process. It does
not claim that Android killed and recreated the main process.

An independently authoritative run now receives a 5-hour-45-minute deadline
when its observer detaches. A matching replacement observer or terminal close
cancels that exact deadline. Expiry requests Pause, retaining partial cache
work while reusing the normal foreground-service and wake-lock cleanup path.
This deadline leaves margin below the Android 15 six-hour media-processing
quota; a platform timeout may still pause the run earlier. Scheduling,
replacement, stale-identity, single-expiry, and cancellation behavior is
covered by deterministic unit tests. The full wall-clock duration is not
replayed in device tests.

## Frozen inputs

- Model: `uvr_mdxnet_3_9662@2`
- Model SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Fixture: `coast_town_short_wav`, 12-second stereo 44.1 kHz PCM WAV
- Fixture SHA-256:
  `0fa980cace732c47647043d1a5fce3589e54771ca3574b003f152acfa6ceec96`
- App APK SHA-256:
  `947ca7f380436d508835a1f1c57e5998cbc6c0d365e02b09497dca2c1b788ede`
- Test APK SHA-256:
  `227894bf9a4f01fd2628b9b8cca5504061ef8828b631ee984951dc00e683a29e`
- GPU profile: `gpu-opencl-bounded-fp32-v1`, OpenCL FP32,
  `kernelBatchSize=1`, `commandQueueWindowSize=1`

## Results

| Device | API | Backend | Runtime elapsed | Journal sequence | Observer transitions | Result |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| Samsung S25 (`SM-S9310`) | 35 | CPU FP32 | 7,122 ms | 12 | 3 | Pass |
| Samsung S25 (`SM-S9310`) | 35 | bounded GPU FP32 | 10,128 ms | 12 | 3 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | CPU FP32 | 13,338 ms | 12 | 3 | Pass |
| Samsung S10 (`SM-G9730`) | 31 | bounded GPU FP32 | 16,986 ms | 12 | 3 | Pass |

All four runs persisted observer detachment at journal sequence 3 and completed
at sequence 12. Their completed caches were playable, the original observer
reported the expected callback failure, and the inference wake lock remained
held while detached and was absent after terminal close. Both GPU runs passed
the exact bounded-runtime admission assertions and completed as `LiteRtGpu`
without fallback.

These short runs compare lifecycle correctness, not backend speed. Their timing
is not a CPU/GPU performance qualification.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/<device>/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-reattach-cpu-v2` | `070450cd28bdba2588d4538381023505601cd996cd047c479b6ec563d2c1ce59` | `8fa1c5c9cfec5e1ccf4e9ab0e5dce863367c3209ba7298e170b23d2250693594` |
| `phase7-s25-reattach-gpu-v2` | `71e51b9e1818da9ea058ab0a63ec5171cdc2f42df1d01686c37ced7a69d0581a` | `b43a63f2e548f609cbcda95e30827ab9fe65c845c4b856d2ce0f030c5c4c326e` |
| `phase7-s10-reattach-cpu-v1` | `28dd6a5af0f873c7a2ccdb6b12b7fec160f2ed0c5b41cb5e7ab0dd4c2c32855b` | `8c4934f1c1f11eb7437117a0c0f0111f78c5a745947a93e2a1bf9fd1429572be` |
| `phase7-s10-reattach-gpu-v1` | `9f709594799b21c6b9cc05322cbb96a4555e15480e23e5e1a6b93463832ee401` | `f386480257611901269ab52eede8635063368fa53450d188a2fc2fa93a7a8524` |
| `phase7-s25-reattach-deadline-cpu-v1` | `aa165ae597527538be60c27cc7d9452cc1259ff7b28dda74ff80d864cc304efc` | `168a77b7f53c286c29bdaa84177cbb533778eab1517ec44613b63332e43dbd02` |

## Remaining scope

- Kill and recreate the main process at each Phase 7D boundary.
- Exercise original playback continuity across actual main-process recreation.
- Define and validate one bounded remote-process restart mechanism.
- Cover stale generation, model/cache removal, recents, force-stop, and FGS
  timeout races through the product coordinator.
- Repeat long-song, thermal, UI-frame, and current-highest-API qualification.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this work.
