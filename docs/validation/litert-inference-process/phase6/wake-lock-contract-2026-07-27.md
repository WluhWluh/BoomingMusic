# Phase 6C wake-lock contract checkpoint

Status: implementation complete; actual-run and ownership-handoff validation
remain open

Implementation revisions tested:

- `b263a312cf844a782a9b4be649c54f7c5c7b79eb`
- `577e838cda13940e6e8be09cdface730ed3f8412`

Production execution mode: `InProcess`

Independent prototype: internal and disabled by default

Execution protocol: 10

## Implemented contract

Only an exact `ManualFullSong` command carrying an attached independent
foreground lease can acquire the inference-process wake lock. Playback-demand
and prefetch commands do not use it and remain under their existing owners.

The service ordering is fixed as follows:

1. Validate the descriptor, source, exact model, and cache identity.
2. Call `SourceSeparationCacheRunCoordinator.begin()` to create or reopen the
   durable run journal.
3. Attach the exact media-processing foreground lease.
4. Enter execution-session accounting.
5. Acquire the inference-process `PARTIAL_WAKE_LOCK` immediately before the
   range executor performs model setup and inference.

The platform lock is non-reference-counted and uses a ten-minute timeout. A
renewal is scheduled every five minutes. Each acquisition and renewal receives
a fresh bounded platform timeout. If the platform lock is already absent at
renewal, or renewal fails, the logical lease is released and the exact active
run is asked to pause rather than continuing unprotected.

Completion, pause, cancel, failure, process teardown, and failed acquisition
all release the lock. Cleanup is nested so a session-finalization exception
cannot skip wake-lock and foreground-service release. If a platform release
unexpectedly fails, one-second retries continue only until the original
ten-minute platform timeout guarantees release. While such an orphan is still
held, every new remote run fails closed rather than replacing its reference.

## Diagnostics

Protocol 10 adds processing wake-lock diagnostics to both process and host
completion snapshots. Each logical lease records:

- exact lease, run, and process-generation identity;
- platform tag and current `isHeld` state;
- acquisition and current expiration timestamps;
- release timestamp and reason; and
- every acquire, renew, and release event with its timeout or reason.

The existing Phase 7 JSON validation report now exports foreground-service and
wake-lock records, including the full event list. A platform lock with no
logical owner remains representable so a release failure cannot be hidden by
diagnostics.

## Automated verification

The GitHub debug suite passed:

```text
./gradlew :app:testGithubDebugUnitTest \
  :app:compileGithubDebugAndroidTestKotlin
```

Result: 275 unit tests, zero failures, zero errors, zero skipped; AndroidTest
Kotlin compilation passed.

Unit tests cover bounded acquisition, renewal, release, duplicate operations,
stale run/generation/lease rejection, active-owner replacement rejection, and
the diagnostic representation of a platform lock that outlives its logical
lease.

The Phase 6B device smoke was repeated on Samsung S25/API 35 after the wake-lock
integration. It passed and proves that a foreground lease waiting for run
admission does not acquire the processing lock, and that pre-admission Pause
leaves `platformHeld=false`.

## Open validation

This checkpoint does not claim an actual model run acquired the platform lock.
The following remain open:

- S25 CPU full-song proof from acquisition through completion;
- active-run Pause, Cancel, failure, cache loss, and service teardown;
- at least one run long enough to observe renewal, or a test-only shortened
  scheduler using the same controller path;
- backgrounding, playback stop, and screen-off progress;
- Android 15+ media-processing FGS timeout cleanup;
- simultaneous playback and manual separation, including proof that
  `PlaybackService` releases any duplicate processing lock after handoff; and
- S10 and bounded-GPU repetitions after CPU lifecycle behavior passes.

The production path and `PlaybackService` wake-lock implementation remain
unchanged until these ownership tests pass. No decoder, source-window strategy,
MP3 fallback threshold, overlap calibration, join placement, or
listening-derived policy changed in this checkpoint.
