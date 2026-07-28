# Phase 6B foreground ownership checkpoint

Status: pending-lease platform contract and exact product handoff complete;
active-pause and current-highest-API coverage remain open

Implementation revisions tested:

- `252f7ab8c66bd90ab4478ce3719da5d99c9e4d49`
- `a991da3ff972f7635e6981214d93af95b72ffee3`
- `d5c36fd470078d0aeb4c36fe888ef95a71067302`

Production manual full-song execution mode: `IndependentForeground`

Playback-demand and prefetch execution mode: client-bound

Execution protocol: 12

## Implemented contract

`SourceSeparationExecutionService` now declares only the
`mediaProcessing` foreground-service type. It promotes an exact manual-run
lease immediately from the service start command and uses:

- the two-argument `startForeground()` call on API 26-28;
- the three-argument call with media-processing type `0x2000` on API 29-34;
  and
- the timed media-processing policy on API 35 and newer.

The dedicated low-importance notification uses channel
`source_separation_processing` and notification ID `21331`. It shows the song
display name and immutable Pause and Cancel actions. Each action carries the
lease ID, run ID, process generation, and a deterministic command ID. Repeated
identical commands are idempotent; reusing one command ID for another action
is rejected; an action for a stale lease cannot affect the current owner.

A lease that has not attached to an admitted run expires after 15 seconds.
Pause or Cancel before admission stops it immediately. Completion, pause,
cancel, rejection, failure, process destruction, and pending-admission timeout
all have explicit stop paths. The latest delivered Android service start ID is
tracked independently from lease identity so a stale start or control intent
cannot strand the service or replace the active owner.

The independent foreground factory enables this policy only for
`ManualFullSong`. Production manual actions route through that factory after
admission; `PlaybackDemandWindow` and `NextSongPrefetch` remain client-bound
and do not acquire this foreground lease.

## Automated verification

The GitHub debug suite passed:

```text
./gradlew :app:testGithubDebugUnitTest \
  :app:compileGithubDebugAndroidTestKotlin
```

Result: 272 unit tests, zero failures, zero errors, zero skipped; AndroidTest
Kotlin compilation passed.

Structured manifest tests verify:

- the inference service is non-exported, runs in `:source_separation`, and
  declares exactly `mediaProcessing`;
- `PlaybackService` retains `mediaPlayback`; and
- the base, media-playback, and media-processing foreground permissions exist.

The device smoke starts a pending exact lease without loading a model. It
checks the live notification, channel, text, ongoing category, action labels,
platform-policy diagnostics, Pause and Cancel delivery, notification removal,
duplicate delivery, and isolation from a stale prior notification action.

| Device | API | ABI | Result |
| --- | ---: | --- | --- |
| Samsung S25 (`SM-S9310`) | 35 | `arm64-v8a` | Pass |
| Samsung S10 (`SM-G9730`) | 31 | `arm64-v8a` | Pass |
| `Medium_Phone` AVD | 26 | `x86` | Pass |
| `Resizable_Experimental` AVD | 37 | `x86_64` | Inconclusive: instrumentation process exited before running a test |

The API 37 AVD showed prolonged ART and system-server stalls. A first attempt
that launched the app UI timed out waiting for UI idleness, and later attempts
ended with zero tests after the instrumentation process was terminated. No
application exception identified a foreground-service contract failure. This
row remains open rather than being counted as a pass.

## Open validation

The later `primary-platform-prototype-2026-07-28.md` report proves an attached
S25 CPU run continued through HOME and screen-off, then removed its notification
and foreground state at completion. The product handoff report proves CPU and
bounded-GPU overlap on S10 and S25: playback protection remains active until
the exact remote owner is accepted, and the playback processing lease then
ends with `remoteOwnershipChanged`. The combined evidence still does not cover:

- startup by tapping the visible UI rather than sending the same production
  MediaSession command from instrumentation;
- notification and foreground removal after an attached run pauses;
- the complete notification/type/timestamp overlap matrix beyond the exact
  processing-owner transition;
- naturally delivered Android 15+ `Service.onTimeout()` behavior after quota
  exhaustion;
- current-highest-API execution; or
- bounded-GPU one-way fallback and long-running lifecycle coverage.

`PlaybackService` retains `mediaProcessing` only for the playback-owned waiting
interval. It no longer claims processing ownership for the exact cache after
the remote service accepts that run. Removing the declaration entirely still
requires later playback-demand and release-policy cleanup.

No decoder, source-window strategy, MP3 fallback threshold, overlap
calibration, join placement, or listening-derived policy changed in this
checkpoint.
