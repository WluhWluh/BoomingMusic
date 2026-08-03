# Source-Separation Lifecycle and Cache Correctness Roadmap

Status: active highest-priority correctness and simplification plan.

Updated: 2026-08-03

This roadmap governs active-model changes, separation scheduling, playback
cache selection, recovery, retention, and deletion. It must reach its focused
product gate before NPU routing adds more execution states.

The [LiteRT and Multi-Preset Roadmap](litert-multi-preset-roadmap.md) remains
authoritative for model contracts, catalog tiers, model installation, and
cache identity. The
[Downloadable Runtime, Quick Setup, and Local Resource Management Roadmap](litert-runtime-setup-roadmap.md)
remains authoritative for runtime delivery and backend preferences. The
[LiteRT Inference Process and Background Execution Roadmap](litert-inference-process-roadmap.md)
is frozen implementation evidence for process isolation and recovery.

When older documents conflict about model-switch behavior, this roadmap wins.

## Goal and Priorities

The required product behavior is small:

1. separated playback uses only the cache for the current active model and
   current source;
2. changing models stops old inference safely but preserves its cache;
3. stale work cannot alter the new model's playback or UI state;
4. deletion and pruning affect only the intended exact cache; and
5. the implementation stays responsive and avoids repeated model, runtime, or
   full-source hashing, global scans, redundant durable writes, and parallel
   policy owners.

Implementation choices follow these priorities, in order:

1. preserve exact cache and execution identity already enforced below the UI;
2. reuse existing types and owners before adding abstractions;
3. keep at most one admitted inference run;
4. serialize short control-state mutations without holding locks across I/O;
5. optimize measured hot paths and remove known redundant writes; and
6. qualify core user paths rather than a full Cartesian product of states.

All work assumes clean application data. No migration is required for
unreleased selection, task, manifest, journal, or cache schemas.

## Non-Goals

This work does not change:

- source-window decoding or the manually qualified MP3 fallback boundaries;
- DSP, STFT, overlap, joins, stem semantics, or render format;
- CPU/GPU qualification or the bounded GPU `N=1` profile;
- model sidecars, model/runtime delivery, or backup policy;
- the rule that per-song blend data remains cache-local and is not backed up;
- the one-active-inference-run product policy; or
- inactive-cache retention as independent song/model entries.

## Existing Implementation to Keep

The lower layers already provide the difficult identity and process safety.
They must be reused rather than wrapped in a second protocol.

| Existing component | Guarantee already present | Decision |
| --- | --- | --- |
| `SourceSeparationPresetRepository` | Serializes install and activation writes and persists one `SourceSeparationActiveModelReference` | Add observation to this owner; do not create another selection store |
| `SourceSeparationRuntimeSong` | Resolves the active model and source to one exact `SourceSeparationCacheIdentity` and cache key | Use its cache key after preflight; do not invent another finalized work identity |
| `SourceSeparationCacheSourceIdentityResolver` | Produces the exact encoded-audio fingerprint required by cache identity | Hash once per unchanged source stamp and memoize only successful preflight results |
| `SourceSeparationCacheRunCoordinator` | Owns manifest/journal transitions and a run-writer lease for one exact cache | Keep it as the durable run owner |
| `SourceSeparationCacheEntryLeaseRegistry` and entry locks | Protect exact entries in-process and across app processes | Reuse for run, playback, promotion, deletion, and pruning |
| `SourceSeparationExecutionDescriptor` | Carries cache identity, contract, model, source, `runId`, and `processGeneration` | Keep it as the admitted and remote run identity |
| Independent-run recovery | Matches the remote descriptor to the exact durable journal | Add only an active-selection admission check before UI adoption |
| `PlaybackService` readiness mutex and context generation | Serializes playback resolution and rejects stale song/blend checks | Extend the context with active selection; do not build a second playback state machine |

The trusted local-resource hot path is also established: normal inference may
trust installed model/runtime records, structure, and byte size without
rehashing large payloads on every start. Explicit verification and repair
flows retain full hash checks.

## Confirmed Gaps

### G1. Selection changes are not observable

`SourceSeparationPresetRepository` commits a reference, but the worker,
PlaybackService, current-cache refresh, and recovery owner receive no ordered
event. They can continue using different selections.

### G2. Upper scheduling state is keyed by song ID

`SourceSeparationForegroundWorkerCoordinator` compares running and pending
requests by song ID. A request for song S under model B can be coalesced with
or blocked by song S under model A.

Its control fields are also mutated by UI/service callers and the IO worker
without one short critical section. The existing single-worker loop should be
retained, but stale `finally` and callback paths need a generation guard.

### G3. Model switching does not hand off the old run

An admitted model A run checks pause, cancel, song, and playback ownership,
but not the active selection. It can continue consuming resources and publish
A progress after model B becomes active.

### G4. Playback can bypass or outlive the active selection

Playback can reuse a same-song A session before resolving B. Cache Management
also exposes an arbitrary completed-cache command that is exact-cache safe but
violates the product rule that normal separated playback follows the active
model.

### G5. UI and cache refresh results are song-only

Worker state contains song fields but no selection generation or cache key.
`PlayerViewModel` accepts progress and asynchronous cache refreshes when only
the song still matches, so delayed A results can be presented as B.

### G6. Recovery is exact but selection-blind

The recovery client already validates the full descriptor, journal, run ID,
and process generation. The missing check is whether that exact recovered run
still belongs to the active selection before the main process adopts it into
current UI and scheduling state.

### G7. Cache data state and writer lifecycle overlap

The manifest uses `Running`, `Canceled`, and `Failed` while the run journal
also records `Running`, `Paused`, `Canceled`, and `Failed`. A paused run keeps a
`Running` manifest, and playback can interpret missing future windows as an
active producer even when only a resumable partial cache remains.

### G8. Maintenance has duplicate owners and avoidable I/O

- completion can trigger pruning from both the worker coordinator and
  `PlayerViewModel`;
- some prune calls omit the current exact active cache from policy protection;
- current-cache deletion partly targets song ID and can stop a different
  same-song model request;
- the global locator index is rebuilt and fsynced after manifest writes even
  though production lookup already has an exact cache key; and
- six production `resolve` call sites can repeat an encoded-sample hash of the
  full source even when the song has not changed; and
- current queue-replacement and legacy hydration branches appear unreachable
  after the model-aware cutover.

## Minimal Frozen Contract

### Selection and request identity

Use three existing identity levels plus one lightweight generation:

1. `ActiveSelectionSnapshot` contains the existing
   `SourceSeparationActiveModelReference?` and a process-local monotonically
   increasing generation.
2. A pending `SourceSeparationWorkerRequest` captures the song plus that
   snapshot. Before preflight, this pair is sufficient to distinguish A from
   B.
3. After `SourceSeparationRuntimeFacade.resolve`, the existing
   `SourceSeparationRuntimeSong.cacheKey` is the exact work/cache identity.
4. After admission, the existing `runId` and `processGeneration` distinguish
   attempts and remote callbacks.

Do not add a persistent selection revision, a parallel contract identity, or
a second run token. A process restart invalidates process-local jobs; remote
survivors already carry durable exact run identity. The selection generation
does not enter the cache key or backup.

Activating the already active exact reference is a no-op and emits no new
generation. A different model, artifact hash, schema, or custom profile ID is
a real change. Custom profile IDs already include revision content, so the
existing reference is sufficient before full contract resolution.

### Model switch

When B is committed while A is active:

1. PlaybackService invalidates A's session, hydration, readiness callbacks,
   and pending hot swap as soon as it observes the new selection. Original
   audio is used while B is resolved.
2. The coordinator records a model-switch stop reason for A and lets the
   existing separation loop stop at its normal window boundary.
3. Ready A windows remain committed. The journal records an
   `ActiveModelSuperseded` transition with lifecycle `Paused`; the cache is
   retained and resumable.
4. A pending B request is evaluated only against B's resolved cache. A is
   never a readiness or playback fallback.
5. Old A callbacks may finish their own atomic cache/journal write, but may not
   publish current UI, clear B state, or start playback.

Do not stop completed-cache FLAC promotion merely because the model becomes
inactive. Promotion is already bound to one exact cache and protected by its
lease. It may finish unless deletion, shutdown, or measured resource
contention independently cancels it.

### Next action for model B

| Exact B state | Automatic demand on | Automatic demand off |
| --- | --- | --- |
| Valid completed cache | Use B through normal readiness | Use B if separated playback is requested |
| Resumable partial cache | Resume B and wait only when the needed horizon is absent | Keep original audio; manual start may resume B |
| No cache | Start B | Keep original audio; manual start may start B |
| Matching live/recoverable run | Adopt only after exact selection/cache/run checks | Continue only explicit manual work; requested playback may use ready B windows |
| Missing/invalid model or contract | Keep original audio and show the actionable error | Same |

Automatic demand still requires separated playback, non-centered blend, and
automatic separation. Manual start remains independent of automatic demand.

### Exact-active playback and UI

- A separated session is reusable only when its cache key equals the cache key
  resolved for the current song and current selection generation.
- An already admitted session with unchanged media and selection generation is
  checked before calling full source resolution again.
- A selection event invalidates playback context generation even when the song
  and blend do not change.
- Cache Management does not directly play an inactive cache. It may offer
  `Use this model`, which activates the installed model and then uses the
  ordinary playback path.
- Worker/UI events carry selection generation and, after resolution, cache
  key. Remote events also retain their existing run ID/process generation.
- Current-cache refresh captures song ID plus selection generation and commits
  only if both still match. Exact cache key is checked when available.
- Completion, failure, Snackbar, panel progress, quick-control progress, and
  notifications ignore stale selection generations.

### Source preflight efficiency

`DefaultSourceSeparationRuntimeFacade` owns a small process-local memo of
successful source preflight results for the current and next few songs. The
key is media URI/file path plus a cheap source stamp such as size, modified
time, and duration. A stamp mismatch or memo miss performs the exact encoded
sample hash once. Cancellation and failures are never memoized.

The source fingerprint is model-independent, so switching A to B does not
invalidate a matching source memo. Keep the memo bounded and in memory first;
do not retain the global manifest locator index for this purpose. Consider a
persistent source sidecar only if profiling shows process restarts make the
one-time hash material.

### Durable cache truth

Avoid storing writer lifecycle twice:

- Manifest state is reduced to data completeness: `Partial` or `Completed`.
- The journal is the sole authority for live lifecycle: `Running`, `Paused`,
  `Canceled`, `Failed`, `CacheLost`, or `Completed`.
- Cancellation/failure diagnostics may remain in manifest metadata, but do
  not create another lifecycle state.
- A manifest is considered actively processing only when its exact journal is
  `Running` and a matching live or recoverable owner exists.
- Startup reconciliation changes an orphaned journal from `Running` to
  `Paused` with an interruption transition before UI/readiness uses it.
- A paused, canceled, or failed partial cache remains inspectable and may be
  resumed by a later explicit admission under the same exact identity.

This clean-install schema change is preferred over adding a fifth `Paused`
manifest state, which would preserve the existing duplicated truth.

### Retention, deletion, and model files

- Every cache key is one retention entry. The exact-entry lease system already
  protects active readers, writers, promotion, hydration, and mutation.
- `SourceSeparationForegroundWorkerCoordinator` is the one automatic-prune
  trigger owner because it already owns run completion and cleanup settings.
  It coalesces completion and settings-change requests; Cache Management
  refresh and `PlayerViewModel` do not prune as a side effect.
- Automatic pruning additionally protects only policy state not guaranteed by
  a lease: the current song's exact active-model cache while it is eligible for
  immediate playback.
- Manual deletion first asks the coordinator to stop only the matching cache
  key or preflight selection. It then waits for that exact run/promotion to
  release and acquires the existing exclusive entry lease.
- Deleting an inactive A cache must not pause a pending or running same-song B
  request.
- Deleting the current exact cache immediately returns to original audio. Keep
  the validated behavior that disables separated playback when automatic
  separation is enabled, preventing immediate recreation.
- Model deletion is rejected as busy while an active, pending, or recoverable
  run references its artifact hash. Start with this focused query; do not add a
  general model-file lease framework unless process-death tests prove it is
  needed.
- Touch LRU time once on successful admission/resume, completion, playback
  session close, or promotion. Status probes and failed checks do not touch it.

## Minimal Ownership Model

### Selection owner

`SourceSeparationPresetRepository` remains the only normal mutation owner. It
publishes one `StateFlow<ActiveSelectionSnapshot>` after the active-model store
write returns and read-back matches. Keep the existing non-blocking atomic
preference edit; do not add a synchronous disk fsync to activation. Quick Setup
and Model Management already route activation through this repository. Restore
must either request a repository reload or restart the process after replacing
preferences.

The inference process never reads selection preferences. It continues to use
the immutable execution descriptor.

### Scheduling owner

`SourceSeparationForegroundWorkerCoordinator` remains the only scheduling
owner and keeps its existing one-worker loop. Do not replace it with a full
command actor in the first implementation.

Add one short state lock and one coordinator request generation. Pending,
active, recovery, stop reason, and worker-job ownership are read or changed
under that lock. Long decode, cache, IPC, and file operations run outside it.
Callbacks and `finally` blocks commit control/UI state only when their captured
request generation still owns the slot.

The existing atomic pause/cancel flags may remain because only one run is
admitted. They must be paired with the owning request generation and reset
inside the same state transition so a new run cannot consume a stale flag.

Escalate to a channel actor only if deterministic stress tests still find a
transition that cannot be made correct with this lock and generation. An
actor is a fallback, not a roadmap requirement.

### Playback owner

PlaybackService owns one active read session. It observes the selection flow
through the existing runtime facade or a thin facade exposure, adds selection
generation to its current context guard, and resolves the exact active cache
before reusing a session.

### Cache owner

`SourceSeparationCacheRunCoordinator` remains the manifest/journal transition
owner. `SourceSeparationModelAwareCacheRepository` remains the inspection,
lease, deletion, and exact prune-execution owner. The worker coordinator
supplies policy limits and the current exact cache to one coalesced automatic
prune request. View models request work and display snapshots; they do not
implement storage policy.

Do not introduce a second scheduler, cache registry, retention planner, or
playback cache override.

## Implementation Phases

### Phase 0: Characterize the narrow failure surface

Status: contract revised; implementation support not started.

- [x] Map existing selection, cache, run, IPC, playback, and deletion identity
  to this roadmap.
- [x] Replace the speculative actor and parallel identity design with the
  minimal reuse plan above.
- [ ] Add reusable A/B fixtures around existing active references, runtime
  songs/cache keys, and run descriptors.
- [ ] Add focused traces for selection generation, cache key, coordinator
  request generation, run ID, and stop reason. Avoid user paths and model
  contents.
- [ ] Add passing characterization coverage and named regression destinations
  for same-song A/B request coalescing, stale current-cache refresh, arbitrary
  completed-cache playback, duplicate prune triggers, and orphaned `Running`
  journals. Do not commit a red test suite.

**Exit:** every confirmed gap has a named regression destination or passing
characterization test using existing identities. No production behavior
changes yet, and the suite remains green.

Suggested commits:

1. A/B fixtures and trace vocabulary;
2. characterization tests.

### Phase 1: Observe selection and serialize the existing coordinator

- [ ] Add process-local selection generation and
  `StateFlow<ActiveSelectionSnapshot>` to `SourceSeparationPresetRepository`.
- [ ] Make activation idempotent and publish only after active-store write and
  read-back succeed, without adding a synchronous preference fsync.
- [ ] Capture the selection snapshot in every full-song and prestart request.
- [ ] Replace song-only pending/running deduplication with song plus captured
  active reference before resolve, then exact cache key after resolve.
- [ ] Guard coordinator control fields with one short lock and request
  generation. Reject stale callbacks and stale `finally` cleanup.
- [ ] Compare a reconnectable descriptor with the current selection before
  adopting it. Pause and retain a mismatched recovered run without publishing
  it as current work.
- [ ] Add selection generation/cache key to existing worker UI state rather
  than creating another state hierarchy.

**Exit:** same-song A and B are never coalesced, one run remains admitted, and
old local or remote callbacks cannot overwrite the current coordinator slot.

Suggested commits:

1. selection observation and request capture;
2. coordinator lock, generation, and exact deduplication;
3. recovery and stale-callback guards.

### Phase 2: Implement model handoff and exact-active playback

- [ ] On a real selection change, invalidate old playback immediately and
  request a safe window-boundary pause for mismatched active/prefetch work.
- [ ] Add `ActiveModelSuperseded` to the existing journal transition enum and
  preserve the old exact cache as partial.
- [ ] Re-evaluate the current song under B using only the table in this
  roadmap and the existing auto-start decision inputs.
- [ ] Resolve the current active cache before same-song playback session reuse;
  include selection generation in hydration, readiness, and hot-swap guards.
- [ ] Remove the arbitrary `PLAY_SOURCE_SEPARATION_COMPLETED_CACHE` product
  command and Cache Management play action. Add `Use this model` only where an
  installed model can be activated normally.
- [ ] Filter worker state, cache refresh, notifications, panel progress,
  quick-control progress, and messages by selection generation and cache key.
- [ ] Reject model deletion as busy while the focused active/pending/recovery
  query reports the artifact in use.

**Exit:** after B is committed, no A cache can provide output or current UI;
A stops safely and remains resumable; B alone determines subsequent work.

Suggested commits:

1. safe model handoff;
2. exact playback invalidation and cache-management UI;
3. UI filtering and model-delete busy check.

### Phase 3: Make cache lifecycle and maintenance single-source

- [ ] Reduce manifest state to `Partial`/`Completed`; derive canceled, failed,
  paused, and live-running presentation from the journal.
- [ ] Reconcile orphaned `Running` journals before recovery/status publication.
- [ ] Make playback wait for future windows only when an exact live/recoverable
  producer exists or a matching request has actually been queued.
- [ ] Replace song-based deletion preparation with exact cache/selection
  targeting while reusing the ordinary pause/cancel stop routine.
- [ ] Route completion and settings-change pruning through the worker
  coordinator's one coalesced request, remove ViewModel/Cache Management
  refresh triggers, and keep explicit user deletion separate.
- [ ] Protect the exact current active cache by policy; rely on existing leases
  for active operations.
- [ ] Normalize LRU touches to the limited events in the frozen contract.
- [ ] Check unchanged active playback sessions before source resolution and
  add the bounded successful-preflight memo described above. Verify repeated
  sync and A/B selection do not rehash an unchanged song.
- [ ] Remove the unused locator index and its manifest-write rebuild/fsync if
  the characterization search confirms no production caller.
- [ ] Remove proven-dead queue-replacement and non-model-aware hydration paths;
  retain the current v2 hydration path.
- [ ] Keep one blend-demand calculation shared by playback and scheduling.
- [ ] Recycle resident execution sessions by their existing complete execution
  session identity before allocation, not after a mismatch failure.

**Exit:** cache data and writer lifecycle have one authority each; prune and
delete cannot cross exact identities; segment publication performs no unused
global-index write.

Suggested commits:

1. manifest/journal simplification and reconciliation;
2. exact deletion, retention, and LRU behavior;
3. locator-index and dead-path removal;
4. blend/session reuse cleanup.

### Phase 4: Focused product qualification

Run broad JVM coverage, but keep device work proportional to product risk.

- [ ] Run all source-separation JVM tests plus repeated A/B scheduler and stale
  callback tests.
- [ ] On S25, test CPU and bounded GPU for A to B to A with: A partial and
  running; B missing, partial, and completed; auto-start on and off; playback
  paused and playing.
- [ ] On S25, switch during model preparation, ready partial playback, final
  completion, and independent-process recovery.
- [ ] Verify current and inactive cache deletion, automatic prune, force-stop,
  main-process restart, and inference-process death.
- [ ] Verify panel/quick-control progress and structured cache-key traces. Use
  digital output capture for the model-switch boundary cases, not every UI
  permutation.
- [ ] Run CPU lifecycle smoke tests on S10 arm32, API 26 x86, and API 37 x86_64.
  Repeat GPU cases only on ABIs/devices where GPU is already supported.
- [ ] Run a short full-song listening check for handoff artifacts. Do not tune
  decode, DSP, or MP3 fallback policy in this phase.

**Exit:** focused traces prove exact-active output and stale-event rejection on
S25 CPU/GPU; the other supported ABIs pass lifecycle smoke; no core workflow
regresses. NPU product work may then resume.

## Required Regression Scenarios

These scenarios are mandatory; they are not multiplied into a full Cartesian
matrix unless a failure points to a specific interaction.

| Scenario | Required assertion |
| --- | --- |
| Activate A again | No generation change, pause, or playback rebuild |
| A running, select B with no cache | A pauses at a safe boundary; B starts only when demanded |
| A playing, select B completed | Original audio bridges the switch; only B becomes separated output |
| A running, select B partial | A is retained; only B may resume and publish progress |
| A to B to A | Each real change invalidates stale callbacks; A's exact cache can resume |
| Delayed A progress/completion | No B UI, cache state, playback, or scheduler mutation |
| Recover A while B is active | A is paused/retained and not adopted as current work |
| Delete inactive A while B runs | B is unaffected; A deletion waits only for A users |
| Delete current B | Playback returns to original audio and B is not immediately recreated |
| Prune during playback/promotion | Leased and exact-current entries survive; limits still apply |
| Main or inference process death | Durable exact run is adopted only when selection matches |
| Repeated sync on one song | Source preflight hashes once per unchanged source stamp |

## Explicitly Deferred Complexity

Do not add the following unless the focused implementation or tests produce a
concrete need:

- a full command actor for the coordinator;
- a persisted selection generation;
- parallel active-model, work-key, and run-token hierarchies;
- a general model-artifact lease subsystem;
- stopping unrelated inactive-cache FLAC promotion on model switch;
- a second cache retention or playback authority;
- a full device/backend/state Cartesian matrix; or
- large class extraction before ownership and behavior are stable.

## Overall Exit Gate

This roadmap is complete when:

1. normal activation emits one ordered process-local selection snapshot;
2. pending, active, recovered, UI, and playback state reject a stale selection
   or exact cache key;
3. model switching safely pauses old inference while preserving its cache;
4. separated output can only use the exact active-model cache;
5. manifest data state and journal writer lifecycle no longer conflict;
6. exact deletion, leases, pruning, and LRU behavior pass focused races;
7. unused global-index and duplicate-policy work is removed; and
8. unchanged playback does not repeat full-source preflight hashing; and
9. S25 CPU/GPU plus four-ABI smoke validation passes without changing audio
   decode or DSP policy.
