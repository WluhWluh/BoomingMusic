# Phase 5 paired host-comparison method

Status: frozen and smoke-qualified

Implementation revision: `041b69b7562f3a3af884dfd61ccb0326b18560e0`

Instrumentation revision: `72d91d0885bd543531707d47842c075be9891336`

Production host policy: `InProcess`

## Frozen method

`paired-host-thresholds-v1.json` was committed before the first result. The
`run_phase5_host_pairs.ps1` runner requires at least three pairs and alternates
the first three as `InProcess/BoundRemote`, `BoundRemote/InProcess`, then
`InProcess/BoundRemote`.

Every sample:

- uses one application revision, APK identity, catalog, model artifact,
  contract, fixture, cache identity, decode route, thread policy, and concrete
  backend;
- retains the installed model but force-stops the target, inference, and test
  packages before instrumentation, then verifies that no old PID remains;
- starts a new app process and inference session and deletes only the exact
  source/model cache entry before admission;
- keeps the original source playing silently in repeat-one, samples position
  drift during separation, and rejects unexpected player events;
- captures power source and starting/peak thermal status rather than selecting
  a favorable run;
- force-stops the app after the report and measures disappearance of all
  related PIDs;
- retains every sample and reports host medians.

Non-x86 `BoundRemote` comparisons require exactly one native session creation
and an `Empty` final session state. This proves `SingleUse`; a retained session
would fail the comparison instead of silently changing the second policy axis.

Android instrumentation executes inside the target main process. Reports
record the test and target package identities, the shared PID/process name,
and count that process once. They do not invent a second PSS or CPU sample for
the test package. The remote inference process is recorded separately.

## Resource envelope

The device report now includes total/available memory, low-memory state and
threshold, `isLowRamDevice`, memory/large-memory class, effective
`Runtime.maxMemory()`, process ABI, and bitness.

Main and remote process records include:

- PSS, USS, RSS, Java/native/graphics PSS, Java/native allocated heap;
- `VmSize`, `VmPeak`, `VmRSS`, `VmData`, mapped-region count, and the largest
  free virtual-address gap;
- process CPU time, thread count, `oom_score_adj`, PID, process start ticks,
  and process generation;
- peak main, remote, and contemporaneous summed PSS/USS/RSS during the worker.

Remote diagnostics are sampled at one-second intervals. Main PSS, USS, RSS,
Java, native, and graphics memory are sampled on the worker poll loop. The
reports retain before/after process snapshots as well as peaks.

## Frozen gates

The comparison rejects identity, route, concrete backend, frame-count, WAV
hash, FLAC hash, or completed-cache differences. It also rejects:

- effective Java heaps below 128 MiB;
- a largest free VA gap below 128 MiB;
- idle remote PSS above 96 MiB;
- median remote summed-PSS regression above 128 MiB;
- both a median time ratio above 1.5x and an absolute regression above 5
  seconds first-ready or 30 seconds full-song;
- process exit above 30 seconds;
- original-playback drift above 1 second or any unexpected playback event;
- different power sources inside one pair or starting thermal statuses more
  than one Android thermal level apart.

Emulator thermal data remains diagnostic-only. Physical-device runs must
start at thermal status 0 or 1.

## S10 short-fixture smoke

The frozen method passed six cold-process samples on Samsung SM-G9730,
Android 12 / API 31, arm64-v8a. The 12-second WAV is a harness smoke, not the
Phase 5B performance result.

All runs used `LiteRtGpu`, identical cache identity, 529,200 output frames,
and identical WAV/FLAC stem hashes. All started at thermal status 0 on AC
power. Maximum original-playback drift was 142 ms, unexpected playback events
were zero, and all post-run processes exited within 490 ms.

| Median | In process | Bound remote | Remote change |
| --- | ---: | ---: | ---: |
| First ready | 13,573 ms | 13,639 ms | +66 ms / 1.005x |
| Full source | 18,364 ms | 18,935 ms | +571 ms / 1.031x |
| Summed peak PSS | 570.6 MiB | 582.8 MiB | +12.1 MiB |

The highest idle remote PSS was 84.4 MiB. The smallest recorded remote free
VA gap was about 372.8 GiB. Instrumentation/main co-location and remote
`SingleUse` were recorded in every applicable report.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed in Phase 5A.

## Full CPU checkpoint

The complete non-x86 CPU matrix subsequently passed on S10 arm32, S10 arm64,
S25 arm64, and API 37 x86_64. During arm32 validation, paired report schema v1
was found to compare immediate bind-time PSS with the two-second settled PSS
gate. Schema v2 now records both values and preserves the original 96 MiB
limit. The rejected v1 arm32 result and the successful corrected repeat remain
distinct evidence. See
[`cpu-host-matrix-2026-07-25.md`](cpu-host-matrix-2026-07-25.md).

## Raw ignored evidence

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Paired summary | 25,250 | `c6bc2e13bc5cc70f8f31fe556fd451ffcaa90de8ad4a247f6aa39417020c91b0` |
| Pair 1 in process | 15,466 | `90b2e5e0a34cccdb0e519bea01a6017a2244d03679266bf24333864615f52cf6` |
| Pair 1 bound remote | 18,098 | `ed5778df346c7b8693bd79e7200a0f44627f0d3383bba15c2381505c0ccd4bac` |
| Pair 2 bound remote | 18,226 | `26f1b705031c8f828264a5e18d5fdfbd25ecd4f679224959a68a76977585e13c` |
| Pair 2 in process | 15,397 | `3964935d9abedbc0224d51a10ffc42a038de9398476e55ca57fbb57128ca6d55` |
| Pair 3 in process | 15,399 | `b135605a68279e291d028dbeeb15be659f5eb93d048dcf83c98a94c1697bbce8` |
| Pair 3 bound remote | 18,223 | `021b2e254d175e715fdca81087033aeb7a445d54870ba1c249054c365772d2e1` |

## Exit decision

Phase 5A passes. The method can now be used for the full WAV CPU and GPU
host-placement matrices. The smoke does not select a production host and does
not authorize resident non-x86 sessions or independent background execution.
