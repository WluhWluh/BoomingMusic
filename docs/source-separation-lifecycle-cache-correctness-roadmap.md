# Source-Separation Lifecycle and Cache Correctness Roadmap

Status: active highest-priority correctness and simplification plan.

Updated: 2026-08-03

This roadmap is the authoritative plan for active-model transitions,
source-separation scheduling, worker recovery, playback cache selection,
cache lifecycle truth, retention, and manual cache deletion. It must be
completed before product NPU work expands the scheduling state space.

The [LiteRT and Multi-Preset Roadmap](litert-multi-preset-roadmap.md) remains
authoritative for model contracts, catalog tiers, model installation, cache
identity fields, and release qualification. The
[Downloadable Runtime, Quick Setup, and Local Resource Management Roadmap](litert-runtime-setup-roadmap.md)
remains authoritative for runtime delivery, Quick Setup, backend preferences,
and runtime inventory. The frozen
[LiteRT Inference Process and Background Execution Roadmap](litert-inference-process-roadmap.md)
remains historical evidence for process ownership and recovery behavior.

If those documents or their completed historical tests conflict with this
roadmap about what happens after the active model changes, this roadmap wins.
In particular, the following previously accepted behaviors are superseded:

- a separated playback session may not remain on an inactive model's cache;
- Cache Management may not directly play an inactive model's completed cache;
- an admitted inference run may keep its immutable identity internally, but
  product orchestration must safely stop it when that identity is no longer
  active; and
- song ID alone is never sufficient to admit, deduplicate, recover, display,
  cancel, protect, or play source-separation work.

## Priority and Scope

This work precedes new NPU product integration, additional backend routing,
and broad model qualification. Those features multiply the number of possible
run identities and make the current song-only coordination gaps harder to
reason about.

The roadmap covers:

- one observable authority for active-model selection;
- model-aware work admission, deduplication, cancellation, and recovery;
- deterministic handoff when the active model changes;
- exact-active-cache playback and progress reporting;
- truthful persisted and live task state;
- model-aware cache protection, deletion, pruning, and LRU accounting; and
- removal of lifecycle code made obsolete by the LiteRT and model-aware
  cutovers.

It does not change:

- source-window decoding or the manually qualified MP3 window fallback
  boundaries;
- DSP, STFT parameters, overlap, joins, stem semantics, or render format;
- the existing cache identity formula, except for a separately reviewed schema
  change needed to express truthful lifecycle state;
- CPU/GPU backend qualification or the bounded `N=1` GPU profile;
- model contract or sidecar semantics;
- model/runtime download policy; or
- the rule that per-song blend data remains cache-local and is not backed up.

All implementation and device validation starts from clean application data.
No migration for unreleased lifecycle, preference, task, or cache schemas is
required.

## Review Baseline

The review found a sound data identity at the cache and engine boundary, but a
song-only control plane above it. The existing
[`SourceSeparationCacheIdentity`](../app/src/main/java/com/mardous/booming/separation/cache/v2/SourceSeparationCacheIdentity.kt)
already includes the finalized source fingerprint, model ID and SHA-256,
contract fingerprint, profile revision, pipeline version, and render profile.
Normal repository lookup validates that exact identity, and exact-entry read,
write, and exclusive leases provide a useful foundation.

The current focused JVM baseline passes:

- `SourceSeparationModelAwareEngineTest`;
- `SourceSeparationModelAwareCacheRepositoryTest`; and
- `SourceSeparationCacheRunCoordinatorTest`.

These tests establish the old baseline. They do not yet prove the product
semantics in this roadmap. The engine invariant that an admitted run never
mutates its own model identity remains correct; the outer scheduler must stop
that immutable run instead of changing it in place.

### Confirmed correctness gaps

`C1. Active selection has no observable lifecycle event.`

[`SourceSeparationPresetRepository`](../app/src/main/java/com/mardous/booming/separation/model/preset/SourceSeparationPresetRepository.kt)
writes active-model fields to preferences, but does not atomically notify the
scheduler, PlaybackService, current-cache queries, or process-recovery owner.
Each consumer can therefore continue with a different view of the selection.

`C2. Work admission and deduplication are keyed by song ID.`

[`SourceSeparationForegroundWorkerCoordinator`](../app/src/main/java/com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt)
uses the song ID for running and pending checks. A request for song S under
model B can be swallowed by an existing or recovering request for song S under
model A.

`C3. A model switch does not stop old-model inference.`

The running loop checks cancellation, pause, and song changes, but not active
selection changes. Model A can continue consuming resources and publishing
progress after B becomes active.

`C4. Independent-process recovery can adopt the wrong model.`

[`SourceSeparationIndependentRunRecoveryClient`](../app/src/main/java/com/mardous/booming/separation/process/ipc/SourceSeparationIndependentRunRecoveryClient.kt)
and coordinator reconnection compare song-level state without requiring the
current active model and exact run identity to match the journal.

`C5. Playback reuses a same-song session before resolving the active model.`

[`PlaybackService`](../app/src/main/java/com/mardous/booming/playback/PlaybackService.kt)
can retain a separated session for A after B is selected. Its stale-result
generation guard also omits the active selection identity.

`C6. Cache Management exposes an inactive-cache playback bypass.`

[`SourceSeparationModelAwareCacheManagementScreen`](../app/src/main/java/com/mardous/booming/ui/screen/player/SourceSeparationModelAwareCacheManagementScreen.kt)
can request playback by an arbitrary exact cache key. This is technically
identity-safe but violates the product rule that separated output must match
the active model.

`C7. Worker and UI state omit model/cache identity.`

[`PlayerViewModel`](../app/src/main/java/com/mardous/booming/ui/screen/player/PlayerViewModel.kt)
maps song-only worker state into panel and quick-control progress. Completion,
failure, or progress from A can be displayed as though it belonged to B.

`C8. Current-cache refresh guards only against song changes.`

An asynchronous lookup started for A can complete after B is selected and
overwrite the current cache state because its stale-result token contains the
song but not the selection revision or cache identity.

`C9. Automatic cleanup does not consistently protect the current exact entry.`

Some prune calls supply no protected keys, and Cache Management derives
protection from current leases only. The current song plus current active
model cache can be removed when no transient lease happens to be held.

`C10. A safely paused cache remains persisted as Running.`

[`SourceSeparationCacheRunCoordinator`](../app/src/main/java/com/mardous/booming/separation/cache/v2/SourceSeparationCacheRunCoordinator.kt)
records a paused journal while leaving the manifest `Running`. Playback can
then wait forever for a writer that no longer exists.

`C11. Scheduler ownership is racy.`

Mutable running, pending, cancel, and reconnect fields are accessed from the
main thread and `Dispatchers.IO` without one actor or mutex authority. A stale
job's `finally` block can clear state belonging to a newer run.

`C12. Inactive model deletion can race an old run's model load.`

After B is activated, A's weight can become deletable before A has reached a
safe stop or acquired everything it needs. The old run can fail because its
artifact disappears rather than ending as a model-switch handoff.

`C13. Current-cache deletion uses a global pause signal.`

The manual deletion path can pause unrelated next-song prefetch or a newly
admitted run. Deletion needs to target one immutable work/cache identity while
still sharing the ordinary user-cancel boundary and cleanup path.

`C14. Resume and completion do not consistently refresh LRU access time.`

A partial cache that was just resumed, or a cache just completed, can still
look old to
[`SourceSeparationModelAwareCacheRepository`](../app/src/main/java/com/mardous/booming/separation/cache/v2/SourceSeparationModelAwareCacheRepository.kt)
and be selected for pruning.

### Confirmed simplification opportunities

`R1. Locator-index rewrites are on the segment hot path.`

[`SourceSeparationCacheStore`](../app/src/main/java/com/mardous/booming/separation/cache/v2/SourceSeparationCacheStore.kt)
rebuilds and fsyncs the global locator index after manifest updates, while the
current production path does not use its candidate/matching APIs.

`R2. Completion triggers duplicate pruning.`

The worker coordinator and PlayerViewModel both request cleanup after one run
completes. Retention should have one owner; UI refresh should not own storage
policy.

`R3. Queue-replacement state is dead.`

All current separated-session construction paths set queue-replacement tokens
and files to null, leaving an older replacement-clock path unreachable.

`R4. A legacy non-model-aware hydration branch is unreachable.`

All production separated sessions now carry model-aware cache data. The v2
hydration implementation remains required, but the older branch should not
remain as a parallel lifecycle.

`R5. Temporary per-song blend policy is duplicated.`

PlayerViewModel and the worker coordinator independently derive the same
temporary blend behavior, risking different scheduling and UI decisions.

`R6. Resident-session recycling reacts too late to model changes.`

Experimental x86 and arm32 resident-session modes primarily key proactive
recycling by backend. A different model can first fail admission and only then
force recycling. The complete executor/session identity should decide reuse.

## Frozen Correctness Contract

### Identity hierarchy

The implementation must use distinct typed identities for distinct stages:

1. `ActiveModelSelectionIdentity` contains model ID, artifact SHA-256,
   contract fingerprint, profile revision, pipeline version, and render
   profile. It contains no song and no backend.
2. `SelectionSnapshot` pairs that identity with a monotonically changing
   selection revision. Switching A to B and later back to A creates a new
   revision, but the revision is not part of the persistent cache key.
3. `SeparationWorkKey` pairs a stable song/source locator with the selection
   identity before final source fingerprinting is available.
4. `SourceSeparationCacheIdentity` remains the finalized data identity after
   decode/preflight establishes the source fingerprint.
5. `RunToken` uniquely identifies one admitted attempt. Asynchronous callbacks
   may mutate scheduler state only when both work key and run token still
   match.

Song ID may be a display or lookup field inside a work key. It is never the
whole key. CPU, GPU, fallback reason, and future NPU backend are run diagnostics
and policy, not cache identity.

### Exact-active playback invariant

At every routing decision, separated output must belong to the exact current
active-model selection and finalized source identity. Playback must never:

- continue reading model A after selection B is committed;
- fall back to another model's completed cache for the same song;
- combine A and B segments in one session;
- treat model A progress as readiness for B; or
- use a cache-key override to bypass active selection.

On a committed selection change, PlaybackService invalidates the old separated
session and any pending hydrated or queued data before it resolves B. It falls
back to original audio through the existing safe audio handoff until B's exact
cache passes the normal readiness gate. No new A frames may be read or queued
after the selection commit.

### Active-model handoff

Selecting B while A inference is admitted has two independently timed effects:

1. playback eligibility for A ends immediately; and
2. inference for A stops at the next existing safe window boundary.

The scheduler records the second effect as `ActiveModelSuperseded`, not as a
user cancellation or inference failure. It publishes the last atomically ready
window, transitions A to a resumable paused/partial state, releases the writer
and model-artifact leases, and retains A's cache. It does not delete, append B
windows to, or relabel A's entry.

An old-model next-song prefetch is subject to the same rule. A no-op selection
of the already active exact identity does not increment the revision or stop a
run. A profile, contract, model ID, artifact hash, pipeline, or render-profile
change is a real handoff even when filenames or display names match.

Model switching does not wait for inactive-cache FLAC compression. If
old-model promotion is in progress, stop or defer it at its existing atomic
boundary, retain the validated WAV result, and allow a later idle maintenance
pass to retry compression. This policy must be confirmed with promotion fault
tests before implementation is considered complete.

### Next action after selecting B

Automatic work is demanded only when separated playback is enabled, the blend
requires separated output, and automatic separation is enabled. An explicit
manual start can demand work independently of automatic separation.

| Exact B state | Automatic work demanded | Required result |
| --- | --- | --- |
| Valid completed cache | Either | Rebuild on B and use it through the ordinary readiness/playback path. |
| Partial cache, no live writer | Yes | Resume only B's exact partial entry and play when its readiness horizon passes. |
| No cache | Yes | Start a new B run and play when its readiness horizon passes. |
| Partial cache or no cache | No | Keep original audio, show no false processing state, and do not resume/start work. |
| Matching live/recoverable B run | Yes | Adopt only after exact work/cache identity and run ownership are verified. |
| Missing or invalid B artifact/contract | Either | Keep original audio and expose the actionable model error; never use A as a hidden fallback. |

Switching back to A may resume only A's exact partial cache. A completed A
cache can become playable again only after A is the active model.

### Cache Management behavior

Inactive-model caches remain independently listed, inspectable, manually
deletable, and eligible for the configured LRU policy. They are not directly
playable.

For a valid installed inactive model, Cache Management may offer `Use this
model`; that action first commits the model through the normal selection
authority and then lets ordinary playback re-resolve the current song. It is
not a session-scoped cache override. When the model/profile is absent, the UI
offers model details or installation instead of playback.

Deleting a model never deletes its caches. Deleting the active model remains
blocked until another model is selected. Deleting an inactive model with an
old run still stopping must wait for the model-artifact read lease to release;
it must not remove the file from beneath the executor.

### Task, manifest, and UI truth

- `Running` means a matching live or recoverable writer exists.
- Safe pause, including model handoff, must have a persisted paused/partial
  representation and a reason distinct from user cancellation and failure.
- Startup reconciliation converts an orphaned `Running` entry into a truthful
  resumable state before playback/status lookup can inspect it.
- `Processing` UI requires a matching active-selection work item that is
  queued, running, or verifiably recoverable. A manifest label alone is not
  enough.
- Worker state, notifications, source-separation panel progress, quick-control
  progress, and completion/error messages all carry and filter by work key,
  cache key when known, run token, and selection revision.
- An old callback may update its own retained manifest, but it may not clear a
  newer run, replace current-song state, publish current-model progress, or
  alter playback.

### Retention and deletion

- Each song/model/profile cache identity is one independent retention entry.
- Automatic pruning always protects the current song's exact active-model
  entry, plus entries covered by read, write, promotion, hydration, or
  exclusive leases.
- Manual deletion may override the current-entry retention protection, but it
  must first target and stop that exact run through the same safe cancellation
  path as the user-facing cancel command, then acquire the exclusive lease.
- Deleting an inactive cache must not pause a current run or next-song prefetch
  with another work key.
- Deleting the current exact cache immediately invalidates separated playback.
  Preserve the current product behavior that disables separated playback when
  automatic separation is enabled, so deletion cannot immediately recreate
  the entry.
- Resume admission, successful window publication, completion, successful
  playback adoption, and successful promotion refresh the appropriate access
  timestamp. Failed probes and status-only scans do not make an entry recent.
- Retention has one owner and one completion trigger. View models request a
  refresh; they do not independently run policy.

## Target Ownership Model

### Active selection authority

One repository owns a canonical `StateFlow<SelectionSnapshot>` and all
selection writes, including Model Management, Quick Setup, restore, and test
hooks. It persists one coherent selection snapshot rather than exposing a set
of independently readable preference fields. The main process owns live
selection authority; the inference process receives an immutable run request
over IPC and never reads preferences to infer the current model.

Every consumer receives the same ordered selection event. Direct preference
reads and polling outside the repository are prohibited. Restored pending-model
metadata remains separate and does not become active until the normal
activation transaction succeeds.

### Scheduler actor

One main-process coroutine actor owns pending work, active run, safe-stop
reason, recovery candidate, processing lease, and UI state. Requests from UI,
playback, song transitions, automatic start, manual delete, remote callbacks,
and process recovery enter as typed commands.

The actor may execute decoding and IPC work on background dispatchers, but all
state mutation returns as a command carrying its run token. Exact duplicate
work keys may coalesce. Same-song work for another model is a handoff, not a
duplicate. Targeted pause/cancel never uses a process-global Boolean.

### Playback authority

PlaybackService owns the active read session but not model selection. A
session key includes the finalized cache key and the selection revision under
which it was admitted. Reuse is allowed only after resolving the current
selection and matching both. Selection changes invalidate session checks,
hydration, pending hot swaps, and ready-horizon callbacks through the same
generation mechanism.

### Cache lifecycle authority

The cache repository owns inspection, touches, retention planning, and
exclusive deletion. The run coordinator owns manifest/journal transitions.
The scheduler supplies live-run protection and the current active work key;
PlaybackService and maintenance jobs supply exact leases. UI layers consume
snapshots and submit commands only.

## Implementation Phases

### Phase 0: Freeze authority and build regression oracles

Status: contract and authority complete; implementation support not started.

- [x] Record the reviewed gaps, frozen behavior, authority order, and explicit
  non-goals in this roadmap.
- [x] Mark conflicting multi-preset behavior as historical and add this work to
  the pre-NPU product gate.
- [ ] Inventory every production API and test that keys behavior by song ID,
  directly reads active-model preferences, or directly plays a cache key.
- [ ] Add reusable fixtures for A and B exact identities, A to B to A selection
  revisions, partial/completed caches, and stale asynchronous callbacks.
- [ ] Add structured debug tracing for selection revision, work key, cache key,
  run token, command, transition reason, and playback session key. Do not log
  model contents or user file paths in release telemetry.
- [ ] Rewrite the test specification for historical device cases that currently
  expect A to finish, A playback to continue, or A's weight to be deleted
  beneath a run after B is selected. Land changed assertions only together
  with the implementation that makes them pass.
- [ ] Preserve the engine-level immutable admitted-run test as a lower-layer
  invariant.

**Phase 0 exit:** every affected behavior has an owning component and a named
test destination; no roadmap or active test specification claims that inactive
model output may remain in normal playback.

Suggested commit boundaries:

1. roadmap authority and test matrix;
2. identity fixtures and trace vocabulary; and
3. non-behavioral characterization coverage.

### Phase 1: Establish observable active selection

- [ ] Introduce typed selection identity and revision snapshots.
- [ ] Route preset activation, custom-profile activation, Quick Setup commit,
  restore resolution, and test activation through one serialized repository
  operation.
- [ ] Publish one ordered StateFlow only after the complete valid selection is
  persisted. Invalid activation leaves the prior selection unchanged.
- [ ] Treat exact same-selection activation as idempotent; A to B to A receives
  distinct revisions without changing either cache identity.
- [ ] Include selection revision and work identity in current-cache refresh
  tokens so A results cannot overwrite B state.
- [ ] Remove or guard direct active preference reads outside the authority and
  add an architectural test/search allowlist.

**Phase 1 exit:** all activation paths produce one coherent event, every
consumer can reject stale A work after B is selected, and process recreation
reconstructs one valid current snapshot.

Suggested commit boundaries:

1. selection types and canonical store;
2. activation-path migration; and
3. consumers, stale-result guards, and tests.

### Phase 2: Serialize scheduling around model-aware work keys

- [ ] Replace shared coordinator fields with one command actor or an equivalent
  rigorously serialized owner.
- [ ] Make pending, running, prefetch, reconnected, UI, and completion state
  carry a full work key and run token; attach the finalized cache key once
  preflight resolves it.
- [ ] Coalesce only exact work-key duplicates. A same-song B request must not be
  swallowed by A.
- [ ] Compare run token and work key before every `finally`, completion,
  cancellation, retry, recovery, and UI mutation.
- [ ] Replace global pause/cancel flags with targeted commands and explicit
  reasons.
- [ ] Keep at most one inference run admitted under the current product policy,
  while allowing retained caches for arbitrarily many model identities.
- [ ] Centralize temporary per-song blend demand calculation so playback and
  scheduling use one decision.

**Phase 2 exit:** deterministic race tests prove an old run cannot clear,
complete, cancel, or relabel a newer run, and all same-song/different-model
requests receive an explicit handoff outcome.

Suggested commit boundaries:

1. work-key/run-token types and actor shell;
2. request, deduplication, and terminal-state migration; and
3. targeted commands, blend demand, and concurrency tests.

### Phase 3: Implement safe model handoff and recovery

- [ ] Send every real selection change to the scheduler actor and request safe
  stop of any inference or prefetch whose selection identity differs.
- [ ] Publish `ActiveModelSuperseded` at the existing window boundary, retain
  atomically ready A segments, and release A's resources without reporting
  cancellation or failure to current-model UI.
- [ ] Evaluate B using the frozen state/action table after A has been detached;
  never use A as a readiness or playback fallback.
- [ ] Bind recovery journals and IPC reconnection to work key, cache key, and
  run token/generation. Reject or safely pause a recovered A when B is active.
- [ ] Ensure a B request is not swallowed by a same-song A recovery candidate.
- [ ] Add model-artifact read leases held from admission through terminal safe
  stop. Make manual model deletion wait or report busy instead of racing the
  loader.
- [ ] Stop/defer old-model FLAC promotion at its atomic boundary, retain valid
  WAV output, and test later idle retry.
- [ ] Cover selection during model preparation, decode, every window state,
  ready partial playback, final promotion, FLAC compression, and remote process
  recovery.

**Phase 3 exit:** A to B always leaves A as a valid retained completed or
resumable partial entry, no A executor remains admitted after the safe boundary,
and B takes exactly the action required by its own cache and auto-start state.

Suggested commit boundaries:

1. scheduler handoff state and reasons;
2. recovery identity and model-artifact leases;
3. FLAC deferral; and
4. handoff matrix tests.

### Phase 4: Enforce exact-active playback

- [ ] Resolve the current selection before considering same-song session reuse.
- [ ] Key session reuse, active-check generations, hydration, pending hot swap,
  readiness callbacks, and queue state by exact cache key plus selection
  revision.
- [ ] On selection commit, synchronously invalidate A reads and pending output,
  release A's read lease, and hand off to original audio before resolving B.
- [ ] Remove the release command that starts playback from an arbitrary cache
  key and remove the Cache Management `Play cached result` action.
- [ ] Add `Use this model` for eligible inactive entries by routing through the
  ordinary activation authority; do not retain a session-only override.
- [ ] Ensure current panel, quick controls, notification, and Snackbar state
  display only exact-current work and cache results.
- [ ] Add digital routing assertions that record the cache key of every source
  used after a selection event and fail on A/B output mixing.

**Phase 4 exit:** no public or internal release path can send inactive-model
cache output to the player, and A cannot remain audible through continued
cache reads after B is committed.

Suggested commit boundaries:

1. session identity and invalidation;
2. cache-management command/UI removal and replacement; and
3. playback/UI routing tests.

### Phase 5: Make persisted and live lifecycle state truthful

- [ ] Add a truthful persisted paused/partial state or otherwise separate
  cache completeness from live-writer state. Do not leave a stopped writer
  represented only as `Running`.
- [ ] Persist distinct reasons for user pause, active-model supersession,
  process interruption, cancellation, and failure where recovery/UI behavior
  differs.
- [ ] Reconcile manifest, journal, writer lock, and independent-process record
  before exposing startup state. Orphaned `Running` becomes resumable, not
  indefinitely processing.
- [ ] Require a matching queued/running/recoverable work snapshot for playback
  waiting and UI processing state.
- [ ] Include exact identity in worker progress and terminal events and filter
  stale model events in PlayerViewModel.
- [ ] Verify force-stop, main-process death, inference-process death, and
  recovery both before and after A to B selection.

**Phase 5 exit:** every visible processing state has a real matching producer,
every stopped partial cache is resumable without pretending to run, and stale
old-model events cannot alter current-model UI or playback gates.

Suggested commit boundaries:

1. lifecycle schema and transitions;
2. startup reconciliation and processing gate; and
3. identity-rich UI state and death/recovery tests.

### Phase 6: Centralize retention, deletion, and access accounting

- [ ] Build one retention plan from exact current-entry protection plus active
  read/write/promotion/hydration/exclusive leases.
- [ ] Route all automatic prune triggers through one repository/coordinator
  owner and remove ViewModel-owned policy execution.
- [ ] Touch access time on resume admission, successful publication/completion,
  playback adoption, and promotion according to the frozen contract.
- [ ] Make current-cache deletion target the exact work/cache key, use the same
  safe cancel boundary as manual cancel, await writer release, acquire the
  exclusive lease, and then delete.
- [ ] Prove inactive-entry deletion cannot pause a different current run or
  prefetch.
- [ ] Preserve the already validated current-song behavior: output returns to
  original audio immediately and automatic separation cannot recreate a cache
  the user just deleted.
- [ ] Race prune/delete against model switch, playback, ready-window publish,
  completion, hydration, FLAC promotion, process death, and A to B to A resume.

**Phase 6 exit:** automatic cleanup cannot remove the current exact entry or a
leased entry, manual deletion affects only its target, and recently resumed or
completed entries have correct LRU order.

Suggested commit boundaries:

1. protection snapshot and single prune owner;
2. access-time semantics; and
3. exact deletion/cancel integration and race tests.

### Phase 7: Remove obsolete lifecycle paths

This phase follows correctness changes so dead-code removal cannot conceal a
behavioral fix.

- [ ] Re-audit locator-index consumers. If none remain, remove the index,
  candidate/matching APIs, and segment-hot-path rebuild/fsync under the clean
  install boundary. If a consumer remains necessary, rebuild lazily outside
  segment publication rather than rewriting globally per window.
- [ ] Remove duplicate completion-prune hooks after the Phase 6 owner is in
  place.
- [ ] Prove queue-replacement fields are always null in the current graph, then
  remove the unreachable replacement-clock branch and tests.
- [ ] Remove only the obsolete non-model-aware hydration branch; retain and
  retest the current model-aware v2 hydration path.
- [ ] Keep one blend-demand implementation shared by UI and scheduler.
- [ ] Key resident executor/session reuse by complete model, contract, backend,
  runtime, and process-generation identity so x86/arm32 modes recycle before a
  mismatched allocation attempt.
- [ ] Split oversized coordinator, PlaybackService, and PlayerViewModel logic
  only along the authorities established above. Do not introduce a second
  scheduler or cache policy owner.

**Phase 7 exit:** each lifecycle decision has one owner, no production write
amplification exists solely for an unused index, and removed branches have
explicit coverage proving their replacement remains reachable.

Suggested commit boundaries:

1. locator-index decision and implementation;
2. dead playback/hydration path removal;
3. session reuse and blend consolidation; and
4. ownership-focused extraction with no behavior change.

### Phase 8: Product and device qualification

- [ ] Run all JVM tests plus repeated scheduler race and virtual-time suites.
- [ ] Run instrumentation from clean app data on all four ABI rows: S25
  `arm64-v8a`, S10 `armeabi-v7a`, API 26 pure `x86`, and API 37 `x86_64`.
- [ ] On S25, cover CPU and bounded GPU for A to B to A during preparation,
  partial readiness, active playback, near completion, completed playback,
  pause/resume, song transition, and force-stop/restart.
- [ ] On S10 and both emulators, repeat the lifecycle matrix for every supported
  backend tier without inferring unsupported GPU capability.
- [ ] Cross product B cache state (missing, partial, completed, corrupt) with
  auto-start on/off, separated playback on/off, centered/non-centered blend,
  and manual start.
- [ ] Verify panel progress, quick-control progress ring, notification,
  PlaybackService output, cache list state, and current-cache deletion by
  screenshot plus structured identity trace.
- [ ] Capture digital output around every switch and prove that no rendered
  segment after the selection boundary comes from an inactive cache key.
- [ ] Test selection and deletion during remote process death, runtime fallback,
  model load, cache promotion, hydration, and automatic pruning.
- [ ] Repeat existing full-song listening probes only to detect lifecycle join
  or handoff regressions. Do not tune window decode or MP3 fallback behavior in
  this work.
- [ ] Update the multi-preset and runtime-setup validation records with the new
  contract and retire historical assertions that intentionally exercised the
  superseded behavior.

**Phase 8 exit:** the frozen model-switch matrix passes on every claimed ABI
and backend tier; structured traces and digital output prove exact-active
cache use; no stale task, UI, recovery, deletion, or pruning action crosses a
work identity; and the pre-NPU correctness gate is closed.

## Minimum Regression Matrix

Every phase should add the smallest applicable slice of this matrix rather
than deferring all coverage to Phase 8.

| Axis | Required cases |
| --- | --- |
| Selection timing | Before admission, queued, preparing model, decoding, segment running, first ready horizon, active partial playback, final window, promotion, FLAC, completed idle, process recovery |
| Selection sequence | A to B, A to B to A, exact A to A no-op, profile revision change, same filename with different identity |
| B cache | Missing, partial resumable, completed valid, corrupt, model missing, contract invalid |
| Demand | Auto-start on/off, manual start, separated playback on/off, centered/non-centered blend |
| Playback | Paused, playing, seeking, track transition, background, hydration pending/ready, original-audio handoff |
| Task control | User pause, user cancel, model supersession, current-cache delete, inactive-cache delete, prune, process death |
| Backend | CPU, bounded GPU, GPU to CPU fallback; NPU remains out of scope |
| Platform | S25 arm64, S10 arm32, API 26 x86, API 37 x86_64; only already claimed capabilities are exercised |

## Overall Exit Gate

This roadmap is complete only when:

1. active selection has one observable serialized authority;
2. no scheduler, recovery, UI, deletion, cleanup, or playback decision is
   keyed only by song ID;
3. model switching safely pauses old inference, preserves its cache, and
   evaluates only the new model's state;
4. separated playback can use only the exact current active-model cache;
5. persisted cache state distinguishes a live writer from a stopped partial
   entry;
6. stale callbacks and process recovery cannot mutate a newer run;
7. model files, current caches, and leased entries cannot be deleted beneath
   active users;
8. retention and blend-demand policy each have one owner;
9. obsolete index, playback, hydration, and duplicate policy paths are removed
   or explicitly justified; and
10. the four-ABI/device matrix passes without changing the established audio
    decode, DSP, or join strategy.

Only after this gate closes should the runtime setup roadmap expose NPU AOT or
JIT capability in the app catalog, Quick Setup, Runtime Management, or backend
routing.
