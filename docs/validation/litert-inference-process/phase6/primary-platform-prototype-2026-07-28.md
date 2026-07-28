# Phase 6D primary platform prototype

Status: S25 CPU lifecycle proof complete; production routing and remaining
platform/GPU coverage remain open

Implementation revision tested:

- `6e68573327eadffa57b944da1db3fc0c0b6263b5`

Execution protocol: 12

Run-journal schema: 5

Execution mode: internal `IndependentForeground` prototype

## Scope

The first admitted `ManualFullSong` run was executed by the private
`:source_separation` process with its own `mediaProcessing` foreground service
and bounded partial wake lock. The production Koin graph still selects
`InProcess`; this checkpoint qualifies the lifecycle path before that routing
change.

The run deliberately used CPU FP32, the pinned 9662 model, and the existing WAV
window decoder. It did not exercise GPU, x86, resident sessions, playback
demand, prefetch, or any decoder-policy change.

## Device result

| Field | Result |
| --- | --- |
| Device | Samsung S25 (`SM-S9310`), Android 15/API 35, `arm64-v8a` |
| Model | `uvr_mdxnet_3_9662@2` |
| Artifact SHA-256 | `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378` |
| Backend | `LiteRtCpu`, FP32, four threads, `tryGpu=false` |
| Input | 273.699-second stereo 44.1 kHz Coast Town WAV |
| First ready | 7,131 ms |
| Full song | 241,588 ms |
| Remote PID | `5757` |
| Remote peak PSS | 848,979,968 bytes |
| Main plus remote peak PSS | 949,788,672 bytes |
| Thermal peak | status 1 |
| Output | 12,070,130 frames; exact expected frame count; finite |
| Cache | exact identity, completed, FLAC-promoted, hydrated, playable |

After the first ready horizon, the runner sent HOME and then put the device to
sleep. Android reported the device as non-interactive/Dozing. Host events and
the durable journal both advanced while the screen was off, and the run reached
its terminal completed state before the device was woken by test cleanup.

The journal writer was the remote PID and retained `ManualFullSong` plus
`IndependentForegroundEligible`. Its final sequence was 99. No main-process
writer or completed-cache shortcut was accepted.

## Ownership result

The foreground service and wake lock used the same lease, run ID, and process
generation. The lock was acquired only after foreground attachment. At
completion:

- no active foreground lease remained;
- no active logical wake-lock lease remained;
- the platform wake lock reported `isHeld=false`;
- the foreground lease stopped with reason `completed`; and
- the wake lock released with reason `completed`.

The run completed in less than the five-minute renewal interval, so this sample
proves bounded acquisition and terminal release but does not claim a real
renewal. Renewal remains covered by controller tests until a longer device run
is retained as evidence.

## Deferred and timeout behavior

Protocol 11 maps denied service starts and denied asynchronous foreground
promotion to typed `Deferred` outcomes without an automatic retry loop.
Protocol 12 implements Android 15+
`Service.onTimeout(startId, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)`: it asks
the matching run to pause durably and immediately releases foreground state,
the notification, and the wake lock.

Unit and service tests cover those injected paths. This checkpoint does not
claim that Android's approximately six-hour rolling quota was naturally
exhausted on a device; that test remains open because it is both long-running
and dependent on platform quota state.

## Evidence

The full report remains an ignored build artifact:

```text
build/phase7-validation/192.168.8.197_44075/
  phase6-s25-cpu-independent-full-screenoff-v2-worker.json
```

Report SHA-256:
`2c83f68ac7d4d1b4241f585497cccf6c8885ef7e468470a04c7aa00fc1b20cca`

Input-envelope SHA-256:
`4de213611efc832291f6f233b9b194eb8ec4563fc5f133c3390b5eb8901eee01`

The repository unit suite and AndroidTest Kotlin compilation passed before the
device run. The connected instrumentation test passed 1/1 in 353.786 seconds.

## Remaining gate

This was started through the validation runner, not by tapping the visible app
command. Production manual-run routing, `PlaybackService` processing-lease
handoff during overlap, active-run Pause/Cancel/cache-loss device cases, API
36/current-highest coverage, natural FGS quota timeout, S10 repetition, and the
bounded-GPU matrix remain open.

No MP3 fallback threshold, window size, overlap, join placement, or other
listening-derived decode policy changed.
