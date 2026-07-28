# Phase 7 user and task lifecycle policy

Status: product policy implemented and unit-tested; active-run Pause and Cancel
cleanup pass on S10 and S25 for CPU and bounded GPU, recents removal passes on
S10 and S25, real PlaybackService owner teardown passes on S10 and S25 for
playback demand and prefetch with CPU and bounded GPU, and force-stop passes on
S10 and S25 for CPU and bounded GPU; current-highest-API force-stop coverage
remains open

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
- `8da0c45e` (validate active-run Cancel cleanup through the product path)
- `3b243650` (prove cross-process kernel cache-lock release)
- `6a520415` (exercise real PlaybackService owner teardown)
- `e7c273fb` (retain timeout diagnostics for the playback-owner gate)

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

### Cancel cleanup

The `cancel-cleanup` gate shares the product-shaped setup and terminal resource
checks used by the Pause gate, but sends `SourceSeparationForegroundWorkerCoordinator.cancel()`
after the first ready window. It additionally requires the last journal
transition to be `UserCanceled`, journal lifecycle `Canceled`, cache manifest
state `Canceled`, a non-completed model-aware cache, and an immediately
reacquirable exact-entry kernel lease.

The accepted matrix is:

| Device | Backend | Cancel latency | Report SHA-256 |
| --- | --- | ---: | --- |
| Galaxy S10 / API 31 | CPU | 536 ms | `e3a6df7d02aa5bdf0a4b60f6fa1866b93bc2aa738c8cf0988cede9613f3ccdde` |
| Galaxy S10 / API 31 | bounded GPU | 643 ms | `22b4284b9c5fe6f326ee520905908998b7282a9f7ab2200c95268de50dcd909c` |
| Galaxy S25 / API 35 | CPU | 277 ms | `c78f702964e3e68070c2da66202a145d16a58b18975741d5fff279a67ca5efe3` |
| Galaxy S25 / API 35 | bounded GPU | 479 ms | `1c645ff716a6532e711de9ba1677e968c7510accaef97b4b3a3585d99edf4e4a` |

Every row released run ownership, cache lease, processing notification,
inference FGS, wake lock, binding, and active native session. A discovery-only
rebind found no active run, an `Empty` session, no session ID, zero native
creations, and zero active leases. The canceled cache remained `Incomplete`
and could not be mistaken for completed output. The main-process lease table
was clear immediately, and the exact cross-process kernel lock was reacquired
within 2-10 ms.

Both GPU rows admitted `gpu-opencl-bounded-fp32-v1`; neither silently used CPU.
All reports use app commit `8da0c45e37a90a2cda87b348174fddd583b940c7`,
app APK SHA-256
`274ae03cbbbfca33d865521c0f507e9158174fc9d90b3a8ff21ebf466631b7fd`,
runner `phase7-runner-v44`, and test APK SHA-256
`23e49077edcee1d9436ccc051c83d63c2462ad3595afcee78ab6bb1169cfe67c`.

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

### PlaybackService owner teardown

The `playback-owner-stop` gate connects to the real MediaSession, starts muted
original-audio playback, and uses the singleton coordinator owned by the real
`PlaybackService`. It separately admits a `PlaybackDemandWindow` or
`NextSongPrefetch` run, waits for a committed ready segment, closes the
controller, and explicitly stops the service.

The accepted matrix is:

| Device | Backend | Playback-owned class | Service stop | Report SHA-256 |
| --- | --- | --- | ---: | --- |
| Galaxy S10 / API 31 | CPU | playback demand | 730 ms | `0eb202bfba8c72c971100ef2720039ad585edf34477ab75c11edd9d90a502422` |
| Galaxy S10 / API 31 | bounded GPU | playback demand | 962 ms | `ead9987a4902ee36a5665e36a99d6bfa5ebfc5f800ef40d719206a72cd073e87` |
| Galaxy S10 / API 31 | CPU | next-song prefetch | 977 ms | `afc7d300b297f2c350e2334d6dea8bf9a778ca316fe80eff45d0d55736c66718` |
| Galaxy S10 / API 31 | bounded GPU | next-song prefetch | 475 ms | `64390a8bc795b1a8a4710e8584e423a22ca757b2d0268f45368d78435f70532a` |
| Galaxy S25 / API 35 | CPU | playback demand | 387 ms | `2b69c488172708a7699107254c7fcad6be1dd479d0a3893a3c988aa89d827ae2` |
| Galaxy S25 / API 35 | bounded GPU | playback demand | 904 ms | `b4c1e01c4ef906ffb09ca3c22fd665a119f5bd1446139cf1afef6c288610daa7` |
| Galaxy S25 / API 35 | CPU | next-song prefetch | 413 ms | `ca1e15d38017d45753daa26577e9861b1c310b38fd8264b21fd47d4a45b51a4e` |
| Galaxy S25 / API 35 | bounded GPU | next-song prefetch | 390 ms | `4f1ba4a67ea937072fc1d681561bb46ae138f9a1ab1bfacd8e25ada828e34d22` |

Every row left a durable final `Paused` transition and an incomplete cache
with at least one committed segment. The cache lease and kernel entry lock were
released, both playback and processing notifications disappeared, neither
processing wake-lock tag remained, and the coordinator had no active or
pending work. Reapplying stale playback state could not admit demand or
prefetch. Playback-owned work remained in the playback process and never
created `SourceSeparationExecutionService`.

Every GPU row admitted `gpu-opencl-bounded-fp32-v1` with
`kernelBatchSize=1` and `commandQueueWindowSize=1`; none latched CPU fallback.
Seven accepted rows use app commit
`6a52041556ad9c8cf3ee4f2880d28173c08caf86`, runner
`phase7-runner-v45`, and test APK SHA-256
`2849eb4c1ba219257b67db0c53c75b27f2fe939cb9b270fc185564d31d8f0ed1`.
The accepted S10 GPU-prefetch repetition uses app commit
`e7c273fb63f4da232e6d0017a171e0b52ccd0f2e`, runner
`phase7-runner-v46`, and test APK SHA-256
`4d4c39855d0600627f3015ce8dd3be88b41c9ae42884db426f5d4e03b5ec1d29`.
All use app APK SHA-256
`274ae03cbbbfca33d865521c0f507e9158174fc9d90b3a8ff21ebf466631b7fd`.

The first S10 bounded-GPU prefetch attempt timed out before teardown and is not
accepted evidence. Its report SHA-256 is
`2b59262ab741ec105e3106787ebc2909cec9d502b6c791186c32a0ec43caf9ba`.
Logcat shows normal bounded-GPU initialization and 254 waited dispatches, then
no further inference summaries while the 12-second repeat-one WAV repeatedly
reopened. It showed no process death, GPU fallback, or thermal throttling, but
the v45 report did not retain enough worker state to identify the exact stall.
After a force-stop and thermal-status-zero cooldown, the same short-current
configuration passed twice in 17.787 and 17.056 seconds. A control using the
273.699-second WAV as the current playback item passed in 18.685 seconds; its
report SHA-256 is
`126a6460445f9d3e3bab30fa97d7e14659d707db13a2e52158ccfe646929f59d`.
Runner v46 now preserves coordinator status, window samples, journal,
manifest, playback state, ownership, and service state on any future timeout.
This transient result does not justify a production decode or scheduler
change.

### Force-stop

The `force-stop` gate prepares the same product-shaped independent manual run
without instrumentation, proves that its processing service, notification, and
wake lock are active, and then executes `am force-stop` from the host. It does
not expect `onDestroy()` to run or invent a terminal journal transition.
Instead, a nonterminal journal may remain durably `Running` while its old PID
and process generation are treated as stale ownership evidence.

The accepted matrix is:

| Device | Backend | Run | Process exit | Silent PID samples | Report SHA-256 |
| --- | --- | --- | ---: | ---: | --- |
| Galaxy S10 / API 31 | CPU | `phase7-s10-force-stop-cpu-v1` | 511 ms | 49 | `e92d885c7a0578d329a61d66f048ea4ddb52057431bc8a3ea7ada19ba0d26e02` |
| Galaxy S10 / API 31 | bounded GPU | `phase7-s10-force-stop-gpu-v1` | 505 ms | 46 | `5f0c13eaff96e1c512ebe7bb881650197477b04755b6c3888ee451e2220e4354` |
| Galaxy S25 / API 35 | CPU | `phase7-s25-force-stop-cpu-v6` | 269 ms | 54 | `746dbda86199732779effbdc3cd3ffe6e49c2c3b1b166cf508593dc1b4e486ad` |
| Galaxy S25 / API 35 | bounded GPU | `phase7-s25-force-stop-gpu-v2` | 390 ms | 57 | `17e6324b12922c9b39006372c9633927bae642c76edd96efb9627d14499f022f` |

All four rows observed every package process exit, kept the package
`stopped=true`, and found zero process relaunches throughout a 30-second silent
interval. No processing service, notification, or wake lock remained. The
journal sequence and SHA-256, plus the complete cache-entry file count, byte
count, and content digest, were identical immediately after force-stop, after
the silent interval, and after an explicit app restart.

The explicit restart is allowed to create an empty inference-service process
while discovery asks whether the stale journal has a matching live run. All
rows returned no reconnectable run and reported an `Empty` session with no
session ID, zero native-session creations, zero active leases, no foreground
lease, and no wake lock. The stale entry was then removed through the normal
cache API. This proves no automatic resurrection; the mere presence of an idle
service record after explicit restart is not execution.

The S25 reports use app commit
`3cfbcfe3073d628ceb35f9303a10c7f8dca52b67`,
app APK SHA-256
`9d80b148da5e760b041aa911b8da07c836f6dee3aca1592d7c77be241b413046`,
runner `phase7-runner-v40`, and test APK SHA-256
`2ee024f5c606504754cb3afc9de2abe2231ab35ce4b85288a45707fca09f47ab`.
The S10 reports use app commit
`a44fc6399f8be02e7d35e88a171fc580d4cb469d`, app APK SHA-256
`274ae03cbbbfca33d865521c0f507e9158174fc9d90b3a8ff21ebf466631b7fd`,
runner `phase7-runner-v46`, and test APK SHA-256
`4d4c39855d0600627f3015ce8dd3be88b41c9ae42884db426f5d4e03b5ec1d29`.
The S10 GPU row admitted `gpu-opencl-bounded-fp32-v1` with both queue bounds
equal to one and no fallback.

The following claims remain deliberately open until device evidence exists:

- force-stop repetition on a later current-highest-API release device; and
- deferred FLAC promotion after actual main-process recreation.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this policy.
