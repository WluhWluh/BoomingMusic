# Phase 6C wake-lock contract checkpoint

Status: implementation and exact S10/S25 CPU/GPU product handoff proved;
renewal and remaining terminal-state validation remain open

Implementation revisions tested:

- `b263a312cf844a782a9b4be649c54f7c5c7b79eb`
- `577e838cda13940e6e8be09cdface730ed3f8412`
- `d5c36fd470078d0aeb4c36fe888ef95a71067302`

Production manual full-song execution mode: `IndependentForeground`

Playback-demand and prefetch execution mode: client-bound

Execution protocol: 12

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

The later `primary-platform-prototype-2026-07-28.md` report proves an actual
S25 CPU full-song run acquired the bounded lock after foreground attachment,
continued while the device was non-interactive, and released it with reason
`completed`. Product-path overlap tests additionally prove on S10 and S25, for
both CPU and bounded GPU, that the playback lock is held before exact remote
acceptance, is released only after that acceptance while the inference lock is
held, and that both locks are absent after completion. The following remain
open:

- active-run Pause, Cancel, failure, cache loss, and service teardown;
- at least one run long enough to observe renewal, or a test-only shortened
  scheduler using the same controller path;
- naturally delivered Android 15+ media-processing FGS timeout cleanup after
  quota exhaustion; and
- long-running bounded-GPU screen-off, fallback, and UI-interaction coverage.

The production path now transfers exact manual-run processing ownership, while
playback-demand and prefetch policy remain unchanged. No decoder,
source-window strategy, MP3 fallback threshold, overlap calibration, join
placement, or listening-derived policy changed in this checkpoint.
