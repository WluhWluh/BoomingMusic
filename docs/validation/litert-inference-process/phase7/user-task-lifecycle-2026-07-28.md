# Phase 7 user and task lifecycle policy

Status: product policy implemented and unit-tested; recents, active-run
Pause/Cancel, and force-stop device matrices remain open

Implementation revisions:

- `77bf5d35` (stop playback-owned inference with `PlaybackService`)
- `d5b4ddd3` (exclude non-completed outcomes from validation warm retention)

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

The following claims remain deliberately open until device evidence exists:

- recents removal with the setting enabled and disabled while playback and a
  manual run overlap;
- active-run Pause and Cancel cleanup of notification, foreground service,
  wake lock, process binding, native session, and cache lease;
- playback-demand and prefetch shutdown through a real `PlaybackService`
  teardown;
- force-stop with a nonterminal journal, proving that `START_NOT_STICKY` and
  reconnection discovery cannot resurrect work; and
- deferred FLAC promotion after actual main-process recreation.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this policy.
