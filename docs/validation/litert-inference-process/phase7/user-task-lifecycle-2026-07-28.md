# Phase 7 user and task lifecycle policy

Status: product policy implemented and unit-tested; active-run Pause cleanup
passes on S10 and S25 for CPU and bounded GPU; recents, Cancel, and force-stop
device matrices remain open

Implementation revisions:

- `77bf5d35` (stop playback-owned inference with `PlaybackService`)
- `d5b4ddd3` (exclude non-completed outcomes from validation warm retention)
- `ac774669` (add the active-run Pause cleanup device gate)
- `02e0973f` (capture the rebound idle-process state)
- `39b25efb` (report native-session release after Pause)
- `90ed5157` (close playback-owner admission races during service teardown)

## Playback lifetime

`STOP_WHEN_CLOSED_FROM_RECENTS` remains a playback setting, matching its
existing title and summary. `PlaybackService.onTaskRemoved()` is unchanged:
when playback is inactive, or when the setting is enabled, it stops playback
and the service. With the setting disabled, active playback continues after
the task is removed.

When `PlaybackService` is destroyed, the foreground worker coordinator now:

1. freezes its estimated playback position and marks the playback clock as
   stopped;
2. disables automatic worker admission and removes queued work;
3. requests Pause for `PlaybackDemandWindow` and `NextSongPrefetch`; and
4. leaves an already admitted `ManualFullSong` or reconnected independent run
   untouched so it can finish under its own processing foreground service.

The worker loop exits after the surviving manual run reaches a terminal state.
It cannot derive a new playback-demand request from stale playback state.

## Pause and idle retention

The remote service handles Pause through the same terminal `finally` path as
other outcomes. That path releases the exact processing wake-lock lease, stops
the exact media-processing foreground lease and notification, closes the run,
and lets the scoped client unbind.

Normal GitHub debug and release builds use `SingleUse` native-session
ownership. The x86 and arm32 resident-session modes are compile-time internal
validation gates and are disabled in release and CI. Those gates previously
could create a five-minute private warm binding after any healthy idle result.
Warm retention is now allowed only after a validated `Completed` response.
Pause, Cancel, Deferred, Failed, malformed, and unknown outcomes unbind without
retention, allowing Android service teardown to close the resident environment.

The product does not intentionally keep a paused process warm and does not
self-terminate merely because it became idle. The normal policy is to release
the binding and let Android reclaim an unstarted, unbound process. Explicit
acknowledged recycle remains reserved for a poisoned session or a request that
requires a fresh process generation.

## FLAC promotion

FLAC promotion remains a main-process cache operation. An independent remote
run completes by publishing a valid WAV cache and releasing its processing
resources. If the main process was absent at completion, the durable terminal
callback is delivered after reattachment and the existing product callback may
then promote the completed stems. Promotion does not keep or reacquire the
inference foreground service or wake lock.

## Verification

`./gradlew.bat :app:testGithubDebugUnitTest` passes after the playback-lifetime
change. Focused lifecycle and warm-retention tests pass after the terminal gate
change. Unit coverage fixes the task-class policy and proves that only
`Completed` permits the internal validation retention path.

The product-shaped `pause-cleanup` device gate starts a cold manual full-song
run under `IndependentForeground`, waits for an active processing foreground
service and wake lock, sends Pause through the product command path, releases
the client, then rebinds only to inspect the idle service. The accepted matrix
is:

| Device | Backend | Run | Pause cleanup | Report SHA-256 |
| --- | --- | --- | ---: | --- |
| Galaxy S10 / API 31 | CPU | `phase7-s10-pause-cleanup-cpu-v1` | 751 ms | `b4fc430469a3c8c2c912e4d55e62cc39a1c77ffb3b7f508de4485f3bcf78d5ab` |
| Galaxy S10 / API 31 | bounded GPU | `phase7-s10-pause-cleanup-gpu-v1` | 4007 ms | `d55341bd92d120b3864e12ff10c32020222d514b6bd34a776400c94697b63d6f` |
| Galaxy S25 / API 35 | CPU | `phase7-s25-pause-cleanup-cpu-v3` | 2001 ms | `93ea7a66186275407c83490f23b74381c464104fa1c73d42da074764afb67503` |
| Galaxy S25 / API 35 | bounded GPU | `phase7-s25-pause-cleanup-gpu-v3` | 1001 ms | `4b7e03fd010cb1ebc671872ef08826a98d633e5f9b8a8f6807a1211031680ef1` |

Both GPU runs admitted `gpu-opencl-bounded-fp32-v1` with
`kernelBatchSize=1` and `commandQueueWindowSize=1`; neither silently used CPU.
Every row reached durable `Paused` with an `Incomplete` cache, released run
ownership, removed the processing notification and foreground service, and
released the wake lock. The rebound service had no active run, an `Empty`
session, no session ID, zero native-session creations, and zero active native
leases. Android could retain the Linux process as an idle cached process; that
is expected and is not a retained product binding or LiteRT session.

The S25 reports use app commit `39b25efbd1576577166e8d3166fe14eae7a8a5a3`
and app APK SHA-256
`086e35d3663bc711a2bbab2336b5c5f845adb9fbe1ec18ef032b94a83b983efc`.
The S10 reports use app commit `90ed515759ca2d2111131e49f36ebc39e8c96f40`
and app APK SHA-256
`92b49f8702a4218d2ca535d6b0c1cfb2db7f67f78fb97117c6ef260455ad3b9a`.
All four use runner `phase7-runner-v37` and test APK SHA-256
`2ee024f5c606504754cb3afc9de2abe2231ab35ce4b85288a45707fca09f47ab`.

The following claims remain deliberately open until device evidence exists:

- recents removal with the setting enabled and disabled while playback and a
  manual run overlap;
- active-run Cancel cleanup of notification, foreground service, wake lock,
  process binding, native session, and cache lease;
- playback-demand and prefetch shutdown through a real `PlaybackService`
  teardown;
- force-stop with a nonterminal journal, proving that `START_NOT_STICKY` and
  reconnection discovery cannot resurrect work; and
- deferred FLAC promotion after actual main-process recreation.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this policy.
