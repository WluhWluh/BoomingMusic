# Phase 7 user and task lifecycle policy

Status: product policy implemented and unit-tested; active-run Pause cleanup
passes on S10 and S25 for CPU and bounded GPU, recents removal passes on S10
and S25, and force-stop passes on S25 for CPU and bounded GPU; Cancel and S10
force-stop coverage remain open

Implementation revisions:

- `77bf5d35` (stop playback-owned inference with `PlaybackService`)
- `d5b4ddd3` (exclude non-completed outcomes from validation warm retention)
- `ac774669` (add the active-run Pause cleanup device gate)
- `02e0973f` (capture the rebound idle-process state)
- `39b25efb` (report native-session release after Pause)
- `90ed5157` (close playback-owner admission races during service teardown)
- `5df6b9eb` (prepare the debug-only force-stop probe)
- `b7891c38` (drive force-stop from the ADB host)
- `3cfbcfe3` (continuously sample the silent force-stop interval)
- `a2d1b86d` (make malformed force-stop evidence reportable)
- `bc5465a9` (exercise real task removal and both playback policies)
- `8de14568` (pass the recents policy as an explicit runner string)
- `a630c91b` (isolate persisted playback mode and retain failure evidence)

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

### Recents removal

The `task-removal` gate launches the real `MainActivity`, identifies its exact
`ActivityManager.AppTask`, and calls `finishAndRemoveTask()`. It requires the
task to disappear both from `appTasks` and `dumpsys activity recents`. Original
audio is muted but actively decoded through the real MediaSession while a
manual full-song run owns the independent processing FGS.

The test forces persisted source-separation playback off for its duration and
restores the previous value afterward. This matters because a late
`MainActivity` controller connection otherwise reapplies the user's persisted
blend mode after a test-only MediaSession command disables it. The first S25
CPU attempt exposed exactly that harness race: the product correctly paused
for 4.859 seconds while waiting for a first stem window. It was not a recents
policy failure and is not accepted evidence.

The accepted matrix is:

| Device | Backend | Recents setting | PlaybackService after removal | Maximum playback drift | Report SHA-256 |
| --- | --- | --- | --- | ---: | --- |
| Galaxy S10 / API 31 | CPU | keep playing | present | 144 ms | `9e32d1da07441bb2cfd2ef6560e338886bebd3b93844130c400ea4ed840ca444` |
| Galaxy S10 / API 31 | CPU | stop | absent | expected stop | `43f5b7c92c1ee4874d00cf94589ff55bad9c47f67abd2d528e6b5d90f2ce8640` |
| Galaxy S25 / API 35 | CPU | keep playing | present | 122 ms | `b7264a925914ec722944d37704e9c2aff8c31a75e719f0d9d22cf684ea16c2ba` |
| Galaxy S25 / API 35 | CPU | stop | absent | expected stop | `266d8c6d29cae462a43f32a5f8a0999d8ba5790a09c002271314e513c31b33cd` |
| Galaxy S25 / API 35 | bounded GPU | keep playing | present | 121 ms | `4e5ab1e77770e5c73f9cb551cb828c920ec9b3c2170ed967e6fb03b286539efa` |
| Galaxy S25 / API 35 | bounded GPU | stop | absent | expected stop | `5579887d7ffc4e2e9fa0a7ed070213ae0f7b5a3a070a89accca4de1d6f277b61` |

Every keep-playing row retained the original media item, repeat-one state,
`playWhenReady`, and active playback with no unexpected player event or audio
underrun. Every stop row observed the expected user-request pause and service
removal. In all six rows, journal sequence advanced after task removal, proving
that the already admitted manual run continued independently of the playback
policy. The verifier then explicitly paused that run and found no processing
notification, inference FGS, wake lock, or active owner.

Both bounded-GPU rows admitted `gpu-opencl-bounded-fp32-v1`; neither silently
used CPU. All reports use app commit
`8de145683780b0eb94677e015da6079e12bf9dfb`, app APK SHA-256
`274ae03cbbbfca33d865521c0f507e9158174fc9d90b3a8ff21ebf466631b7fd`,
runner `phase7-runner-v42`, and test APK SHA-256
`5233563f420efe6195557ef15c67b64808b8b7f64ef3aef214b3fa8ea41052a7`.

### Force-stop

The `force-stop` gate prepares the same product-shaped independent manual run
without instrumentation, proves that its processing service, notification, and
wake lock are active, and then executes `am force-stop` from the host. It does
not expect `onDestroy()` to run or invent a terminal journal transition.
Instead, a nonterminal journal may remain durably `Running` while its old PID
and process generation are treated as stale ownership evidence.

The accepted S25/API 35 matrix is:

| Backend | Run | Process exit | Silent PID samples | Report SHA-256 |
| --- | --- | ---: | ---: | --- |
| CPU | `phase7-s25-force-stop-cpu-v6` | 269 ms | 54 | `746dbda86199732779effbdc3cd3ffe6e49c2c3b1b166cf508593dc1b4e486ad` |
| bounded GPU | `phase7-s25-force-stop-gpu-v2` | 390 ms | 57 | `17e6324b12922c9b39006372c9633927bae642c76edd96efb9627d14499f022f` |

Both rows observed every package process exit, kept the package
`stopped=true`, and found zero process relaunches throughout a 30-second silent
interval. No processing service, notification, or wake lock remained. The
journal sequence and SHA-256, plus the complete cache-entry file count, byte
count, and content digest, were identical immediately after force-stop, after
the silent interval, and after an explicit app restart.

The explicit restart is allowed to create an empty inference-service process
while discovery asks whether the stale journal has a matching live run. Both
rows returned no reconnectable run and reported an `Empty` session with no
session ID, zero native-session creations, zero active leases, no foreground
lease, and no wake lock. The stale entry was then removed through the normal
cache API. This proves no automatic resurrection; the mere presence of an idle
service record after explicit restart is not execution.

Both reports use app commit `3cfbcfe3073d628ceb35f9303a10c7f8dca52b67`,
app APK SHA-256
`9d80b148da5e760b041aa911b8da07c836f6dee3aca1592d7c77be241b413046`,
runner `phase7-runner-v40`, and test APK SHA-256
`2ee024f5c606504754cb3afc9de2abe2231ab35ce4b85288a45707fca09f47ab`.

The following claims remain deliberately open until device evidence exists:

- active-run Cancel cleanup of notification, foreground service, wake lock,
  process binding, native session, and cache lease;
- playback-demand and prefetch shutdown through a real `PlaybackService`
  teardown;
- force-stop repetition on S10/API 31 and later current-API coverage; and
- deferred FLAC promotion after actual main-process recreation.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this policy.
