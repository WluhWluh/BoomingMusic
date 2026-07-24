# LiteRT Inference Process and Background Execution Roadmap

Status: staged research and implementation plan

Updated: 2026-07-24

Current milestone: Phase 0, freeze the in-process baseline and acceptance
contract before introducing a process boundary.

This roadmap governs two related but separate experiments:

1. isolate LiteRT inference and source-separation execution from the playback
   process; and
2. decide whether that inference process should later own an independent
   background-execution lifetime.

The first experiment addresses native-memory isolation, deterministic runtime
reclamation, and the unsafe pure-x86 session lifecycle. The second changes
Android service, wake-lock, notification, and process-death behavior. Passing
the first experiment does not authorize the second.

This is a companion to the
[LiteRT and Multi-Preset Roadmap](litert-multi-preset-roadmap.md). The main
roadmap remains authoritative for model contracts, runtime compatibility,
cache identity, playback, model management, and release qualification. This
document is authoritative only for process placement and background execution.

## Goals

- Determine whether a dedicated same-UID process makes LiteRT session
  recreation safe on pure `x86`.
- Keep native allocation, XNNPACK state, GPU resources, and runtime failures
  out of the playback process.
- Make process exit a reliable last-resort reclamation boundary.
- Preserve source-separation correctness, playback readiness, pause/cancel,
  prefetch, cache recovery, and GPU-to-CPU fallback.
- Measure whether a remote process is useful on `armeabi-v7a`,
  `arm64-v8a`, and `x86_64` rather than assuming one policy
  fits every ABI.
- Separately determine whether an active run should continue after the main
  playback process is killed.
- Preserve a graceful fallback: if an experiment fails, supported ABIs remain
  in process and pure x86 remains fail-closed.

## Non-Goals

- This work does not change model files, model contracts, DSP parameters,
  stem semantics, cache identities, or catalog tiers.
- It does not make HQ4 supported on a resource-constrained ABI.
- It does not add another inference runtime.
- It does not introduce WorkManager as the owner of a live LiteRT session.
- It does not provide cloud or cross-device inference.
- It does not allow the inference process to download, activate, or delete
  models independently.
- It does not automatically admit another song after the main process dies.
- It does not guarantee uninterrupted work after Android force-stop, app-data
  clearing, or cache clearing.
- It does not add a user-facing process-mode preference until a production
  policy has passed all gates.

## Frozen Behavioral Invariants

### Source decode policy

The existing window-decode routes, MP3 gapless handling, no-Xing calibration,
format fallbacks, source timeline, frame counts, and join placement are frozen.
Moving the same executor into another process must not alter route selection.

The current reference is the
[Phase 7 source-format validation](validation/litert-phase7/source-format-results-2026-07-23.md).
Every remote-process report must record and compare the same:

- source fingerprint;
- source-decode mode and profile;
- MIME type and fallback reason;
- output sample rate and frame count;
- segment boundaries and join placement; and
- completed WAV/FLAC hashes where the existing oracle requires them.

Any future proposal to change MP3 or window-decoding behavior requires a
separate same-device comparison and representative listening plan. It must not
be folded into any phase in this document.

### Model and runtime policy

- Exact model artifact SHA-256, contract revision, execution profile, backend
  profile, and runtime settings are frozen when a run is admitted.
- GPU fallback remains one-way within the same exact run and cache identity.
- Cancellation is not a fallback.
- A model switch affects only work admitted afterward.
- Unknown or unsupported ABI/model/backend combinations continue to fail
  before native allocation.
- HQ4 retains its current compatibility and resource gates.

### Cache and storage policy

- Model weights remain application data.
- Partial windows, run journals, completed stems, FLAC promotion intermediates,
  hydration data, diagnostics, and performance reports remain cache data.
- System clear-cache may remove all run state. The service must terminate or
  recover cleanly without reconstructing deleted cache from application data.
- Per-song blend remains cache-local and is not added to backup.
- Process-host diagnostics, process generations, restart counters, and
  performance data are not backed up.

### Playback policy

- Ordinary music playback has priority over background separation.
- A remote-process failure must not stop original-audio playback.
- Existing ready-window gating and exact-cache playback remain unchanged.
- No PCM tensor, decoded window, or stem buffer may cross Binder as an ordinary
  byte array.

## Known Baseline

The production coordinator currently owns an in-memory coroutine worker in the
same application process as `PlaybackService`. It creates a
`SingleUseMdxInferenceSessionProvider` for each separation execution.
The manifest declares `PlaybackService` for
`mediaPlayback|mediaProcessing`.

The processing wake lock and dynamic `mediaProcessing` foreground
type currently protect the case where playback is waiting for separation
cache. They are not a separate durable owner for every full-song background
run. Current background behavior therefore depends substantially on the
playback service and process remaining alive.

The application `App.onCreate()` currently starts the complete Koin
module graph in every app process. A remote process would therefore initialize
far more UI, network, image, and repository state than it needs unless process
specific startup is added.

The [LiteRT CPU results](litert-cpu-validation-results.md) establish:

| ABI and model | Reused-session PSS delta | Current result |
| --- | ---: | --- |
| x86 / 9662 | 531.7 MiB | numerical and same-session reuse pass |
| x86 / KARA | 532.7 MiB | numerical and same-session reuse pass |
| armeabi-v7a / 9662 | 517.9 MiB | supported CPU evidence |
| arm64-v8a / 9662 | 654.9-683.6 MiB | supported CPU evidence |
| x86_64 / 9662 | 827.4 MiB | supported CPU evidence |

Pure x86 has a packaged and validated CPU-only LiteRT runtime. Its blocker is
not JNI or model operator support. A production-shaped lifecycle that closes
one large session and invokes a newly created session is unsafe. Test-only
single-session reuse survives pause, resume, seek, and cancellation. HQ4 is a
separate first-allocation/resource failure and is outside the proposed x86
recovery.

## Candidate Architecture

### Main process responsibilities

- Own playback, MediaSession, UI, user commands, and active-model selection.
- Resolve and admit an exact model-aware run.
- Freeze a small, versioned execution request.
- Bind to or start the inference service according to the qualified host mode.
- Observe progress and reconcile it with playback readiness.
- Keep model download, activation, deletion, and cache-management UI in the
  main process.
- Treat Binder death as a typed runtime event, never as permission to run a
  second writer against the same cache entry.

### Inference process responsibilities

- Run under a private, same-UID process name such as
  `:source_separation` with `exported=false`.
- Never use `isolatedProcess=true`; it needs app-private model and
  cache access and the package's media permissions.
- Validate the frozen request, canonical model location, artifact SHA-256,
  contract, cache identity, and runtime compatibility again.
- Own source decode, DSP, LiteRT session creation, inference, residual
  reconstruction, segment commits, and run diagnostics.
- Own GPU probe and one-way CPU fallback when the selected policy permits it.
- Return compact state and progress events only.
- In the later independent-background phases, own foreground-service and
  wake-lock lifetime for the exact active run.

Moving the full range execution loop avoids Binder's transaction-size limit.
An MDX tensor is many times larger than a normal Binder transaction. Passing
it window by window would add copies, latency, and a new failure surface.

### IPC contract

Use a versioned AIDL or equally explicit Binder contract. The first protocol
must include:

- independent `protocolVersion`;
- command ID, run ID, process generation, and event sequence;
- exact cache key and source fingerprint;
- model ID, artifact SHA-256, contract ID/revision, pipeline identity, and
  runtime settings;
- source URI and immutable source metadata needed by the existing decoder;
- requested run class, including full song or bounded prefetch;
- pause/cancel semantics;
- typed state, progress, prepared-window, completed, paused, failed, and
  process-recycling events; and
- concrete runtime/backend diagnostics and fallback data.

The protocol must not contain model bytes, PCM arrays, TFLite tensors, stem
arrays, mutable SharedPreferences state, UI DTOs, or absolute paths intended
for long-term persistence.

Commands are idempotent by command ID. Events are monotonic within a process
generation. Rebinding clients request a complete state snapshot before
accepting incremental events.

### Process modes

The implementation may expose these modes to debug and instrumentation code:

| Mode | Process boundary | Background owner | Intended use |
| --- | --- | --- | --- |
| `InProcess` | none | current PlaybackService behavior | parity oracle and rollback |
| `BoundRemote` | dedicated inference process | PlaybackService/client binding | memory and x86 lifecycle experiment |
| `IndependentForeground` | dedicated inference process | inference media-processing FGS | separate background experiment |

No normal release should silently fall back from a failed remote production
host to in-process inference. That could create a second large session or a
second cache writer. The in-process mode remains an explicit test or
ABI-policy decision.

## Global Acceptance Rules

Every phase is independently reversible and must leave normal production
behavior unchanged until its own promotion gate passes.

No phase passes unless:

- all existing JVM tests and relevant connected tests pass;
- the exact source-decode route remains unchanged;
- output numerical and frame-count gates remain unchanged;
- no run can have two live cache writers;
- cancellation and pause remain distinguishable;
- process death cannot promote a partial cache to completed;
- unsupported models and ABIs fail before native allocation;
- original playback survives a remote runtime failure; and
- reports identify app commit, model hash, contract, ABI, Android API, process
  mode, process generation, backend, and source fixture.

Performance and memory thresholds must be frozen in Phase 0 before remote
results are reviewed. They may be revised only with an explicit rationale,
never retrospectively to make a failing result pass.

## Phase 0: Freeze Baseline and Experimental Contract

### Phase 0A: Current lifecycle inventory

- [x] Diagram current ownership of coordinator scope, PlaybackService scope,
  foreground state, wake lock, model session, cache lease, FLAC promotion, and
  playback readiness.
- [x] Record which behavior survives Activity destruction, PlaybackService
  destruction, recents removal, main-process death, and inference exceptions.
- [x] Record the current `oom_score_adj`, process state, wake-lock
  state, and foreground-service type during full-song, prefetch, and
  playback-waiting runs.
- [x] Confirm how current process death is recovered from v2 cache manifests.
- [x] Inventory every in-memory lock or singleton that assumes one process.

### Phase 0B: Baseline device reports

- [x] Capture in-process 9662 full-song and bounded-prefetch baselines on S10
  arm64, S10 arm32, S25 arm64, pure x86, and x86_64 where current compatibility
  permits.
- [x] Keep pure-x86 baseline execution behind an instrumentation-only
  compatibility override; its production preflight remains fail-closed.
- [x] Record main-process PSS/USS/RSS, native and graphics memory,
  `VmSize`/`VmPeak`, mapped-region count, CPU time, first
  ready-window time, windows per minute, total run time, and thermal state.
- [x] Record playback underruns, MediaSession interruptions, and progress gaps
  while music is playing.
- [x] Capture screen-on and screen-off baselines without changing source
  fixtures or decode policy.
- [x] Freeze explicit per-device comparison thresholds for later phases.

### Phase 0C: Protocol and rollback contract

- [x] Define protocol v1 and its compatibility policy.
- [x] Define the exact durable run journal fields and location under the
  model-aware cache entry.
- [x] Define which terminal reasons permit bounded automatic recovery.
- [x] Define compile-time and internal runtime gates that can return every
  supported ABI to `InProcess` while leaving x86 fail-closed.
- [x] Add this roadmap's report schemas and directory naming convention under
  `docs/validation/litert-inference-process/`.

**Phase 0 exit:** baseline reports and thresholds are committed; no process
boundary or user-visible behavior has changed.

## Phase 1: Introduce a Runtime Host Boundary In Process

This phase is a behavior-preserving refactor. It proves that process placement
can change without leaking Binder or Android Service types into the separation
engine.

### Phase 1A: Host interfaces

- [x] Define a runtime-neutral execution-host interface for start, state
  snapshot, pause, cancel, and close.
- [x] Keep model resolution and admission outside the host.
- [x] Make requests carry exact immutable model, source, cache, and runtime
  identities.
- [x] Keep progress and terminal events typed and serializable without making
  them Android Parcelables inside core separation code.
- [x] Implement `InProcess` using the current production executor.

### Phase 1B: State and diagnostics

- [x] Add host mode, run ID, process generation, backend, and host lifecycle to
  runtime diagnostics.
- [x] Make stale-generation events impossible to apply to current playback.
- [x] Ensure one admitted run owns one cache lease and one host generation.
- [x] Exclude internal host selection and diagnostics from backup.

### Phase 1C: Parity

- [x] Run existing scheduler, cache, full-song, playback, and format suites
  through the host interface.
- [x] Confirm no source-decode fixture or output hash changes.
- [x] Confirm foreground and wake-lock traces are byte-for-byte or
  semantically equivalent to the pre-refactor baseline.

**Phase 1 exit:** production still runs in process, and the new host boundary
has no observable behavior, output, memory-policy, or background-policy change.
The accepted evidence is recorded in
`docs/validation/litert-inference-process/phase1/validation-2026-07-24.md`.

## Phase 2: Build a Bound Remote-Process Prototype

This phase tests process isolation only. The remote service does not yet own an
independent foreground lifetime.

### Phase 2A: Lightweight process startup

- [x] Detect the current process before initializing application-wide modules.
- [x] Split the Koin graph into main-process and inference-process modules.
- [x] Keep UI, image loading, network clients, update checks, widgets, and
  unrelated repositories out of the inference process.
- [x] Initialize only contract parsing, model validation, cache execution,
  source decode, DSP, LiteRT, diagnostics, and IPC dependencies.
- [x] Verify that default-preference initialization and crash UI setup do not
  race or repeat in the inference process.
- [x] Measure the idle remote-process baseline before loading LiteRT.

### Phase 2B: Private service and IPC

- [x] Add a non-exported same-UID service in
  `:source_separation`.
- [x] Implement protocol v1 with request-size and event-rate assertions.
- [x] Bind from the current controller with explicit connection, binder-death,
  timeout, and rebind states.
- [x] Coalesce progress callbacks so one processed window cannot create an
  unbounded Binder queue.
- [x] Reject malformed, stale-generation, duplicate, or identity-mismatched
  commands before touching cache or LiteRT.
- [x] Validate canonical model/cache roots and hashes in the remote process.

### Phase 2C: Move the complete range execution

- [x] Execute source decode, DSP, LiteRT, segment commit, and run completion in
  the remote process.
- [x] Keep the exact existing decoder and route selector unchanged.
- [x] Keep playback mixing and hydration in the playback process.
- [x] Initially keep post-completion FLAC promotion under its current owner,
  while recording whether independent execution will later require moving it.
- [x] Do not transmit PCM or tensors through Binder.
- [x] Ensure production MediaStore source URIs work from the same-UID process
  on every supported API. Imported model documents are copied into app-private
  storage before execution, so no live SAF grant crosses Binder.

### Phase 2D: Bound-mode parity

- [x] Run the same fixture once in `InProcess` and once in
  `BoundRemote` within a fresh app-data state.
- [x] Compare decode route, progress order, ready horizon, frame counts,
  output hashes, cache manifest, and terminal state.
- [x] Background the UI and turn off the screen while PlaybackService remains
  active.
- [x] Confirm current production recents-removal behavior has not changed.
  `PlaybackService.onTaskRemoved()` and production `InProcess` selection are
  unchanged; a dynamic `BoundRemote` recents-removal matrix remains Phase 7/8
  work.

**Phase 2 exit:** the bound remote process matches in-process output and current
background semantics. It is still internal-only on every ABI. The accepted
evidence and the explicit recents-removal limitation are recorded in
`docs/validation/litert-inference-process/phase2/validation-2026-07-24.md`.

## Phase 3: Qualify the Pure-x86 Session Strategy

### Phase 3A: One resident session

- [ ] Give the dedicated process a serialized reusable-session owner keyed by
  factory, artifact SHA-256, contract/profile identity, backend profile, and
  runtime settings.
- [ ] Keep the session alive across pause/resume and successive work for the
  same exact model.
- [ ] Never hold two large x86 sessions concurrently.
- [ ] Distinguish lease release from session destruction.
- [ ] Record process address-space and allocator evidence before setup, after
  inference, after pause, after reuse, and before recycle.

### Phase 3B: Deterministic process recycle

- [ ] Treat model switch, incompatible runtime settings, fatal native state,
  and failed session validation as process-recycle boundaries on x86.
- [ ] Compare graceful service stop, unbind/stop, and explicit dedicated
  process termination. Do not assume `stopSelf()` creates a fresh
  process.
- [ ] Require a recycle acknowledgement and a new generation before the next
  model can acquire its cache lease.
- [ ] Prevent sticky restart from reviving the old generation.
- [ ] Verify that the main process and original playback survive every recycle.

### Phase 3C: x86 lifecycle matrix

- [ ] Run 9662 through at least 20 pause/resume/seek/cancel cycles in one
  process generation.
- [ ] Run repeated full-song and bounded-prefetch jobs for the same model.
- [ ] Alternate 9662 and KARA through process recycle for at least 20 switches.
- [ ] Inject failure during setup, invocation, output read, segment commit, and
  recycle.
- [ ] Compare output against the existing x86 numerical oracle.
- [ ] Confirm the second, tenth, and twentieth successful run do not show
  growing mapped-region, PSS, or restart trends.
- [ ] Keep HQ4 unsupported; run only an explicit allocation probe if useful.

**Phase 3 exit:** pure x86 remains unsupported unless repeated production-shaped
jobs and model switches pass through a fresh process boundary. A failure ends
this route without changing other ABI policy.

## Phase 4: Make Cache Ownership Process-Safe

The current in-process lease graph cannot by itself arbitrate a remote writer
against main-process cleanup or deletion.

### Phase 4A: Cross-process exact-entry lease

- [ ] Add an OS-backed file lock for each exact cache entry.
- [ ] Keep run ID, owner process generation, PID, and timestamps as diagnostic
  metadata; the kernel lock, not metadata age, is authoritative.
- [ ] Make main-process delete, prune, promotion, and inspection respect the
  same lock.
- [ ] Route operations through one owner where a file lock alone cannot make a
  multi-file transition atomic.
- [ ] Confirm process death releases the kernel lock.

### Phase 4B: Durable run journal

- [ ] Write the admitted immutable request and lifecycle state under the cache
  entry before native setup.
- [ ] Atomically record each committed window and terminal transition.
- [ ] Never persist raw model paths as portable identity.
- [ ] Mark pause, user cancel, incompatibility, FGS timeout, cache clearing,
  and unexpected process death distinctly.
- [ ] Ensure a journal cannot make an incomplete entry playable.

### Phase 4C: Death and race injection

- [ ] Kill the inference process during decode, DSP, native invocation, output
  write, manifest update, FLAC handoff, and terminal commit.
- [ ] Kill the main process while the inference process owns the entry.
- [ ] Race cache inspection, deletion, pruning, model deletion, and active-model
  switching against the remote writer.
- [ ] Clear cache during an internal test run and require a typed terminal
  outcome without recreation from model/application data.
- [ ] Recover only from the next uncommitted window and never duplicate a
  completed segment.

**Phase 4 exit:** process death and management races cannot corrupt, splice,
misidentify, or prematurely promote a cache.

## Phase 5: Compare Bound Remote Execution on Every ABI

This phase asks whether process isolation is useful beyond x86. It does not
enable independent background execution.

### Phase 5A: CPU matrix

- [ ] Compare `InProcess` and `BoundRemote` on S10 arm32,
  S10 arm64, S25 arm64, API 26 x86, and x86_64.
- [ ] Test 9662 everywhere it is currently eligible and KARA where its CPU
  contract permits.
- [ ] Record main, remote, and summed PSS/USS/RSS rather than reporting only
  the inference process.
- [ ] Record idle-process overhead, model setup, first/reused inference,
  first-ready-window, full-song throughput, process restart, and memory return
  after process exit.
- [ ] Measure playback underruns and scheduling contention.

### Phase 5B: GPU and fallback matrix

- [ ] Verify accelerator library discovery from the remote arm64 process.
- [ ] Re-run 9662 GPU eligibility, finite-output probe, full-song Auto, and
  injected setup/invocation/output failures on S10 and S25.
- [ ] Confirm GPU closes before CPU fallback is created in the same remote
  process.
- [ ] Confirm fatal GPU cleanup can request a process recycle instead of
  risking a second allocator when cleanup is unconfirmed.
- [ ] Record graphics/native memory and driver diagnostics separately from PSS.

### Phase 5C: ABI policy checkpoint

- [ ] Decide whether x86 is `RemoteRequired` or remains unsupported.
- [ ] Decide whether armeabi-v7a is `RemotePreferred` based on
  32-bit address-space and recovery evidence.
- [ ] Keep arm64 and x86_64 in process unless remote execution demonstrates a
  concrete reliability benefit without unacceptable total-memory,
  performance, GPU, or playback regressions.
- [ ] Document why a common all-ABI path is or is not worth its active-memory
  overhead.

**Phase 5 exit:** each ABI has an evidence-backed process-placement candidate.
No independent foreground service exists yet.

## Phase 6: Prototype an Independent Media-Processing Service

This phase changes background ownership. It must remain behind a separate
internal gate from `BoundRemote`.

### Phase 6A: Foreground-service ownership

- [ ] Let the inference service declare only
  `foregroundServiceType="mediaProcessing"`.
- [ ] Keep PlaybackService responsible for `mediaPlayback`.
- [ ] Define a handoff that never leaves active inference unprotected and never
  has two components claiming ownership indefinitely.
- [ ] Call `startForeground()` within the platform deadline with a
  dedicated processing notification.
- [ ] Provide Pause and Cancel notification actions with idempotent command
  IDs.
- [ ] Do not keep an idle process in foreground solely to retain a model
  session.

### Phase 6B: Wake-lock ownership

- [ ] Move the active-computation `PARTIAL_WAKE_LOCK` to the process
  actually executing inference.
- [ ] Acquire it only after an exact run is admitted and before heavy setup.
- [ ] Use bounded acquisition/renewal and release it on pause, cancel,
  completion, failure, timeout, cache loss, and process teardown.
- [ ] Record every acquire, renewal, and release in validation diagnostics.
- [ ] Ensure the playback process does not retain a duplicate processing lock.

### Phase 6C: Modern Android constraints

- [ ] Test foreground-service start from a visible UI command.
- [ ] Test automatic prefetch while the app UI is backgrounded and playback is
  already foreground.
- [ ] Handle `ForegroundServiceStartNotAllowedException` as a typed
  paused/deferred outcome, not a retry loop.
- [ ] Implement and test `Service.onTimeout()` for the Android 15+
  media-processing time budget, including its approximately six-hour
  per-24-hour background allowance.
- [ ] Stop promptly after timeout while preserving the last committed window.
- [ ] Test API 31, 35, 36, and the current highest emulator API because the app
  targets API 36.
- [ ] Measure the cumulative media-processing FGS budget for long or repeated
  runs.
- [ ] During this prototype, treat main-process Binder death as a controlled
  pause. Continuing without the main process is enabled only after Phase 7
  transfers active-run authority and passes its recovery gates.

**Phase 6 exit:** one exact run can remain protected with the screen off and
without relying on PlaybackService's processing lease. Main-process death
continuation is not yet enabled.

## Phase 7: Support Independent Process Lifetime and Recovery

### Phase 7A: Transfer active-run authority

- [ ] Once a run is acknowledged, make the inference service authoritative for
  that exact admitted run until a durable terminal transition.
- [ ] Keep the main coordinator as a proxy and observer rather than a second
  in-memory worker.
- [ ] Let a reconnecting main process query and adopt the current snapshot.
- [ ] Do not let callback loss cancel an otherwise valid foreground run.
- [ ] Do not let the inference process admit a new song after completing the
  frozen request without a live main-process decision.

### Phase 7B: Bounded restart policy

- [ ] Define whether system-killed active work uses sticky restart, redelivered
  intent, or an explicit durable-journal restart.
- [ ] Gate restart on a matching nonterminal journal, intact model/cache,
  available FGS permission, and retry budget.
- [ ] Apply bounded backoff and stop after repeated death or allocation failure.
- [ ] Never restart work canceled or paused by the user, stopped by FGS
  timeout, invalidated by cache clearing, or made incompatible by model loss.
- [ ] Surface a stable terminal/deferred state after the retry budget ends.

### Phase 7C: User and task lifecycle

- [ ] Preserve the existing "stop when closed from recents" setting.
- [ ] Define and test behavior when playback stops while separation continues.
- [ ] Define whether a manually paused session retains the remote process and
  for how long; do not hold foreground state or wake lock while paused.
- [ ] Decide whether completed-stem FLAC promotion must move into the remote
  process so an independently running job can finish without the main process.
- [ ] Reconnect notifications, UI, playback readiness, and cache management
  after main-process recreation.
- [ ] Confirm Android force-stop always terminates work without automatic
  resurrection.

**Phase 7 exit:** an admitted run may survive main-process death, or the feature
is rejected with a documented reason. Recovery is bounded, cache-safe, and
visible.

## Phase 8: Background and OEM Qualification

### Phase 8A: Scenario matrix

Run at least these scenarios in both the accepted baseline host and candidate
independent host:

- active playback with UI backgrounded;
- active playback with screen off;
- playback paused while a manual full-song run continues;
- next-song prefetch during background playback;
- recents removal with the stop setting both enabled and disabled;
- main-process kill;
- inference-process kill;
- repeated low-memory kills and bounded recovery;
- Doze and battery saver;
- charger connected and disconnected;
- thermal throttling;
- model switch and x86 process recycle; and
- media-processing foreground-service timeout.

### Phase 8B: Devices

- [ ] Galaxy S10 arm64.
- [ ] Galaxy S10 armeabi-v7a.
- [ ] Galaxy S25 arm64.
- [ ] API 26 pure-x86 emulator.
- [ ] x86_64 emulator for the current highest supported Android API.
- [ ] API 35 and API 36 emulator coverage for foreground-service policy.
- [ ] Samsung battery modes: Unrestricted, Optimized, and Restricted where the
  OS permits the comparison.

### Phase 8C: Evidence

- [ ] Compare windows per minute, first-ready time, full-song duration, CPU
  time, thermal status, and battery discharge.
- [ ] Compare main, remote, and summed memory plus graphics/native allocation.
- [ ] Record wake-lock duration and require no lock after every terminal state.
- [ ] Record foreground-service type, notification lifetime, start failures,
  timeouts, process deaths, restart count, and progress gaps.
- [ ] Record audio underruns, playback discontinuities, and MediaSession state.
- [ ] Verify output, frame, decode-route, join, cache, and FLAC gates.
- [ ] Perform representative listening only as a regression check; do not use
  this phase to retune window decoding.

**Phase 8 exit:** background execution is promoted only if it preserves
correctness and playback, has bounded recovery, meets the Phase 0 memory and
performance thresholds, and behaves acceptably on both Samsung generations
and modern Android FGS policy.

## Phase 9: Select Production Policy and Remove Transitional Ownership

### Phase 9A: Policy decision

- [ ] Publish an evidence table selecting `InProcess`,
  `BoundRemote`, `IndependentForeground`, or Unsupported
  for each ABI/backend combination.
- [ ] Prefer one common production architecture only when the all-ABI evidence
  justifies its memory and lifecycle cost.
- [ ] Keep pure x86 fail-closed if process recycling is not repeatably safe.
- [ ] Keep HQ4's separate model-resource restrictions regardless of process
  mode.

### Phase 9B: Production cleanup

- [ ] Remove duplicate worker, foreground-service, wake-lock, and cache-lease
  ownership from the nonselected path.
- [ ] Keep a test oracle for in-process correctness if production becomes
  remote, but do not expose automatic runtime fallback between hosts.
- [ ] Remove internal process-mode controls from release UI.
- [ ] Update compatibility catalog evidence only after device reports are
  committed.
- [ ] Update the main LiteRT roadmap, runtime documentation, release notes, and
  user-visible unsupported-device messages.
- [ ] Verify ABI split APK and universal APK service/native-library inventory.

### Phase 9C: Release gate

- [ ] Repeat clean-install model acquisition, selection, separation, playback,
  cache management, backup/restore, and clear-cache recovery.
- [ ] Repeat full-song and background smoke tests on every production-enabled
  ABI.
- [ ] Verify no service, binder callback, session, file lock, notification, or
  wake lock remains after completion or cancellation.
- [ ] Record the final app commit, protocol version, process policy, model
  hashes, and device evidence.

**Phase 9 exit:** process placement and background ownership are explicit
production contracts, not incidental consequences of where a coroutine runs.

## Validation Artifacts

Committed summaries and compact reports should live under:

`docs/validation/litert-inference-process/<phase>/<date>/`

Large traces, PCM, stems, profiler captures, `/proc/<pid>/maps`
snapshots, and repeated-run raw logs remain ignored build artifacts. Each
committed summary must include SHA-256 values for any ignored evidence used to
make a compatibility decision.

Every report should identify:

- app commit and dirty-tree state;
- protocol and journal schema versions;
- package, flavor, ABI, Android API, and device fingerprint;
- process mode and process generations;
- main and inference PIDs;
- model artifact SHA-256 and contract/profile identity;
- concrete CPU/GPU backend and fallback;
- source fixture and decode route;
- cache key and run ID;
- foreground-service and wake-lock timeline;
- per-process and summed memory;
- timings, thermal state, process deaths, and restart count; and
- terminal state and output/cache validation.

## Principal Risks and Required Mitigations

| Risk | Required mitigation |
| --- | --- |
| Remote process initializes the whole app | process-specific Application/Koin graph and measured idle baseline |
| Binder transaction overflow | move full execution loop; prohibit PCM/tensor payloads |
| Stale callbacks mutate current playback | process generation plus monotonic event sequence |
| Duplicate cache writers | OS-backed exact-entry lock and idempotent run journal |
| Main process dies | remote durable snapshot and reconnect protocol |
| Remote process dies | atomic window commits and bounded recovery |
| x86 model switch recreates in fragmented process | deterministic dedicated-process recycle |
| Two foreground owners drift | explicit ownership handoff and one active processing lease |
| Screen-off stalls | inference-owned bounded partial wake lock |
| FGS start is denied | typed deferred state, no retry loop |
| Android 15+ FGS timeout | `onTimeout()`, durable pause, prompt stop |
| Low-memory restart thrash | retry budget, backoff, and terminal defer |
| GPU driver differs out of process | complete S10/S25 probe, fallback, and full-song rerun |
| Playback loses CPU priority | bounded inference threads and underrun/thermal gates |
| Source output changes during migration | frozen decode routes and Phase 7 fixture parity |
| Debug host mode leaks into backup | internal-only setting excluded from backup |

## Provisional Decisions

These decisions guide implementation but remain subject to the phase gates:

- Use a private same-UID process, not Android isolated-process mode.
- Move the full separation range executor, not individual tensor invocation.
- Use an explicit versioned Binder protocol.
- Keep model management and run admission in the main process.
- Keep one serialized session per inference process generation.
- Recycle pure-x86 inference process on model identity change or fatal native
  state.
- Test bound isolation before adding a second foreground service.
- Make the inference process own foreground and wake-lock state only in the
  independent-background experiment.
- Treat all ABI process placement as evidence-driven.
- Never modify current window-decoding policy as part of process migration.

## Decisions That Must Remain Open Until Tested

- Whether armeabi-v7a should use remote execution by default.
- Whether arm64/x86_64 reliability gains justify active total-memory overhead.
- Whether GPU Auto remains stable in a service process on both Samsung devices.
- Whether the service should retain an idle same-model session after pause.
- Which bounded restart mechanism is reliable across API 26 through target 36.
- Whether FLAC promotion belongs in the independently running process.
- Whether a separate processing notification can be grouped without obscuring
  playback controls.
- Whether background auto-prefetch can start or promote the service reliably
  under modern FGS restrictions.
- Whether independent background completion is worth its wake-lock, quota,
  battery, and OEM-policy cost.

## Overall Completion Criteria

This roadmap is complete only when:

1. pure x86 is either repeatably supported through a documented remote-process
   policy or explicitly retained as unsupported;
2. every other ABI has an evidence-backed process-placement decision;
3. independent background execution is either qualified or explicitly
   rejected separately from process isolation;
4. source decode and output behavior remain unchanged;
5. playback survives remote failures;
6. cache ownership and recovery are process-safe;
7. foreground-service and wake-lock ownership are singular and bounded; and
8. the final policy is reflected in catalog evidence and release
   documentation.
