# LiteRT Inference Process and Background Execution Roadmap

Status: staged research and implementation plan

Updated: 2026-07-25

Current milestone: Phase 5, compare host placement and session policies on
each ABI without changing background lifetime.

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

- Determine whether one resident LiteRT session plus whole-process recycle
  avoids unsafe in-process session recreation on pure `x86`.
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

Phase 3 may use one compile-time and AndroidTest-only exception for the exact
pinned x86 9662 and KARA artifacts. Every such report must retain the original
`Unsupported` decision and identify the effective validation-only override.
Normal debug, CI, and release graphs must continue to reject those records
before native allocation until a later explicit compatibility decision.

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

Production still owns an in-memory coroutine worker in the same application
process as `PlaybackService` and creates a
`SingleUseMdxInferenceSessionProvider` for each separation execution. The
manifest declares `PlaybackService` for `mediaPlayback|mediaProcessing`.

Phase 2 added an internal-only `BoundRemote` host in
`:source_separation`, process-specific startup, bounded IPC, exact remote
admission, and complete remote range execution. The main process still owns
the cache run-writer lease, manifest transitions, FLAC promotion, hydration,
and playback. Bound execution matches in-process output on the supported
worker ABIs, and pure x86 passes service startup, close, rebind, and
remote-death handling.

Phase 3 added one process-owned resident session only behind the exact pinned
pure-x86 validation gate. The session survived the 20-cycle 9662 matrix, and
20 alternating 9662/KARA key changes crossed acknowledged fresh-process
boundaries without interrupting original playback. Other ABIs retain the
Phase 2 single-use policy. Normal pure-x86 admission remains fail-closed before
native allocation. Accepted evidence is in
`validation/litert-inference-process/phase3/validation-2026-07-24.md`.

The processing wake lock and dynamic `mediaProcessing` foreground
type currently protect the case where playback is waiting for separation
cache. They are not a separate durable owner for every full-song background
run. Current background behavior therefore depends substantially on the
playback service and process remaining alive.

The inference process now starts only its small Koin graph and returns before
main-process preference population, crash UI, StrictMode, image loading, and
other unrelated startup. Its settled pre-LiteRT PSS passes the frozen 96 MiB
gate on S10, S25, x86_64, and pure x86.

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

### Orthogonal policy axes

Later phases must vary and decide these axes independently:

| Axis | Values | Question |
| --- | --- | --- |
| Host execution | `InProcess`, `BoundRemote` | where decode, DSP, and LiteRT execute in one build/run |
| Release host policy | `InProcess`, `RemotePreferred`, `RemoteRequired` | which qualified host a release selects for one scope |
| Session lifetime | `SingleUse`, `ResidentUntilProcessExit` | whether one process may retain native state across runs |
| Background owner | client-bound, independent processing FGS | whether work may outlive the playback/main process |

A successful remote host does not authorize a resident session. A successful
resident session does not authorize independent background execution. A
background experiment must use the already-qualified host/session combination
rather than changing multiple axes at once.

`RemotePreferred` means a release selects `BoundRemote` by default but a later
build-level policy change may roll that scope back to its separately qualified
`InProcess` path. `RemoteRequired` means no in-process path is supported for
that scope. Neither permits a failed remote run to fall back to in-process
execution at runtime.

### Support tiers

Process placement is also separate from the user-facing support commitment:

| Tier | Meaning |
| --- | --- |
| `Unsupported` | fail before native allocation |
| `Experimental` | gated, narrowly scoped, and not a general compatibility promise |
| `Supported` | release-qualified for the recorded ABI/backend/model resource scope |

Every Phase 5 and Phase 9 policy row must record support tier, release host
policy, concrete host execution, session lifetime, and background owner. For
example,
`Experimental + RemoteRequired + ResidentUntilProcessExit + client-bound` is
a valid x86 outcome; `RemoteRequired` alone is not a support declaration.

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
- unsupported models and ABIs fail before native allocation outside an
  explicitly identified AndroidTest-only compatibility override;
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

## Phase 3: Qualify a Single-Session Pure-x86 Process

Existing CPU evidence establishes that 9662 and KARA can run correctly on the
packaged x86 LiteRT runtime and can reuse one native session; Phase 0 freezes
the 9662 result as the diagnostic baseline. The failure mode is
production-shaped close/create/invoke in one 32-bit process. Phase 2 gave that
experiment a replaceable process boundary. Phase 3 now retains one exact
session in each validation-only x86 process incarnation and uses acknowledged
self-termination when the session key must change.

| Current evidence | Result | Phase 3 consequence |
| --- | --- | --- |
| Pure-x86 bound service startup, close, death, and rebind | passed | the process boundary is ready for inference experiments |
| 9662/KARA x86 numerical run and same-session reuse | passed at about 532 MiB session PSS delta | test one resident session instead of repeated allocation |
| Production-shaped single-use close/create/invoke | repeated LiteRT invocation failure | forbid a second native session in one process generation |
| HQ4 x86 first allocation | failed despite the enlarged AVD | retain zero-allocation rejection; it is not a Phase 3 candidate |

Phase 3 is a falsifiable, internal-only x86 experiment. It must not alter
source decode, cache identity, release compatibility, other ABI host policy,
or background ownership. Cache-race recovery and arbitrary process death
remain Phase 4 work.

Accepted evidence is recorded in
`docs/validation/litert-inference-process/phase3/validation-2026-07-24.md`.

### Phase 3A: Freeze the failure model and harness

- [x] Add a Phase 3 report extension that records process PID/generation,
  process-start identity from `/proc/<pid>/stat`, session ID and key,
  native-session creation count, lease count, invocation count, poisoned
  state, recycle reason/token, and expected versus unexpected Binder death.
- [x] Capture remote `VmSize`, `VmPeak`, `VmRSS`, PSS, native PSS, thread and
  mapped-region counts, `smaps_rollup` or a `smaps` fallback,
  `AnonHugePages`, and the largest free virtual-address gap before setup, after
  first invocation, after pause, after reuse, and immediately before recycle.
- [x] Add an opt-in validation build gate that admits only the exact pinned
  x86 9662 and KARA artifacts through `BoundRemote`. It must be unavailable to
  normal debug/CI/release builds, have no preference or backup key, and record
  both the original `Unsupported` decision and the effective test override.
  The remote service must derive and verify the override itself; a client IPC
  field cannot grant compatibility.
- [x] In sacrificial fresh generations, compare at least five
  create/invoke/close/create attempts with a separate same-session-reuse
  control. Preserve failures and address-space evidence instead of treating a
  successful retry as proof that recreation is generally safe.
- [x] Freeze the session state machine as
  `Empty -> Creating -> Resident -> Poisoned -> Recycling`. A pure-x86
  generation may never transition directly from one resident session key to
  another.
- [x] Freeze the no-client/paused warm-retention deadline and recycle timeout
  and the minimum acceptable largest-free-address gap before the lifecycle
  matrix. Use the Phase 0 20-cycle, 64 MiB PSS-growth, and
  256-mapping-growth gates without retrospective adjustment. This deadline is
  an experimental reclamation bound, not the final user-facing pause policy;
  Phase 7C retains that production decision.

### Phase 3B: Add one process-owned session authority

- [x] Add a serialized process-owned session controller to the remote
  execution environment. Implement it generically, but enable persistent
  native-session ownership only for the internal pure-x86 experiment in this
  phase; other ABIs retain the Phase 2 single-use behavior.
- [x] Refactor the remote range executor to acquire from that environment-owned
  controller instead of calling a provider factory for every `separate()`.
  Closing an `ActiveRemoteRun` releases its lease and callbacks; it must not
  destroy the resident provider.
- [x] Key the resident session by factory, artifact SHA-256, contract and
  execution-profile identity, backend profile, precision, and runtime
  settings. Auto on pure x86 must resolve to the CPU backend before the key is
  accepted.
- [x] Permit exactly one native session creation and one active lease at a time
  in each x86 process generation. A same-key request reuses the exact session;
  a different key returns `RecycleRequired` without closing or creating a
  second session.
- [x] Make pause, cancel, bounded-prefetch completion, ordinary completion,
  and lease release leave a healthy same-key session resident. Session close
  is process teardown, not an in-process replacement mechanism.
- [x] Mark the controller poisoned after any native create, invoke, tensor
  read, non-finite output, or cleanup failure that may leave LiteRT state
  uncertain. A poisoned generation rejects all later starts.
- [x] Keep protocol/admission failures before session acquisition non-poisoning.
  Conservatively recycle after an unexpected execution failure once a native
  session has been acquired; do not guess that the session survived.
- [x] Preserve the Phase 2 rule that the main process owns the one cache
  run-writer lease. Session residency must not retain, duplicate, or bypass
  that lease.
- [x] Add fake-session tests for state transitions, same-key reuse, key
  mismatch, lease release, poisoning, and the invariant that creation count
  cannot exceed one. Cover client disconnect/rebind and delayed recycle at the
  device Binder boundary.

### Phase 3C: Add acknowledged whole-process recycle

- [x] Extend the private protocol with a recycle request containing the exact
  expected process generation, reason, and a unique recycle token. Accept it
  only when no remote run is active and the main process has observed the run's
  terminal/paused result and released its run-writer lease.
- [x] Have the inference process acknowledge the token and then terminate its
  own PID after the synchronous Binder response returns. The main process must
  not kill a remembered remote PID.
- [x] Treat the resulting Binder death as expected only for the acknowledged
  token. Block new admission until the old binder is dead and a new binding
  reports a new process generation and process-start identity. Record PID
  changes, but do not assume Android cannot reuse a numeric PID.
- [x] Use self-termination as the reclamation boundary. Measure
  `stopSelf()`, unbind, and service stop for diagnostics, but do not use them as
  proof that Android discarded native address-space state.
- [x] Recycle at a run boundary for model/profile/runtime-key changes, a
  poisoned session, or an explicit validation request. Never recycle merely
  because the active model changes while an already admitted run is still
  executing. The five-minute API 26 retention deadline releases the private
  binding rather than claiming deterministic process death; automatic idle
  and memory-pressure recycle is now a Phase 7C policy decision.
- [x] A native failure ends the current Phase 3 run. Recycle before the next
  attempt, but do not automatically resume or recover the failed cache until
  the Phase 4 journal and cross-process lease rules exist.
- [x] Prevent sticky restart and stale callbacks from reviving or completing
  the old generation. Verify that original-audio playback and the main process
  survive every expected recycle and injected remote death.
- [x] Hold no foreground-service state or wake lock merely to retain an idle or
  manually paused session. If Android kills it, classify that separately from
  the acknowledged recycle path.

### Phase 3D: Run the staged x86 qualification matrix

- [x] Start with one bounded `content://` 9662 worker and one complete song in
  `BoundRemote`. Require the existing decode route, exact frame count, cache
  identity, terminal state, and x86 numerical/output oracle before running the
  repetition matrix.
- [x] In one generation with one 9662 session, run at least 20 bounded
  production-worker jobs: five ordinary completions, five pause/resume cases,
  five pending-tail seek/resume cases, and five cancellations. Include client
  disconnect/rebind while paused and require one session ID and creation count
  throughout.
- [x] Ensure every repetition actually invokes LiteRT. Use a distinct exact
  cache identity or remove the prior exact entry only after terminal state and
  run-writer lease release; a completed-cache hit does not count as a cycle.
- [x] Run one full-song 9662 job before and after the 20-cycle series, plus
  repeated bounded-prefetch/resume work, without replacing the resident
  session. No canceled or failed window may become `Ready`.
- [x] Alternate exact 9662 and KARA keys through at least 20 acknowledged
  recycle boundaries. Each switch must use a fresh process incarnation and
  generation, create one session only, run a bounded worker through valid
  output without a completed-cache shortcut, and reject every stale callback
  or old-generation command.
- [x] Inject validation failure before native setup, native setup failure,
  invocation failure, output-read/non-finite failure, callback death, recycle
  timeout, and unexpected idle remote death at the narrowest meaningful test
  boundary. Controller tests verify healthy/poisoned native-session
  classification; device tests verify callback/rebind and fresh-generation
  recovery. A synthetic remote Kotlin throw would add no native evidence.
  True native death during an active cache-file transition remains Phase 4.
- [x] At cycles 2, 10, and 20, compare address-space and allocator evidence.
  Require zero unexplained failures, at most 64 MiB PSS growth from cycle 2 to
  cycle 20, at most 256 additional mapped regions, no shrinking trend that
  crosses the frozen largest-free-gap floor, and no unexpected process restart.
- [x] Run original-audio playback during at least one long resident-session
  case and one 20-switch recycle case. Remote failure or recycle must not stop,
  seek, or replace original playback.
- [x] Keep HQ4 fail-closed and verify its existing zero-allocation preflight.
  Do not repeat the known-disqualified x86 HQ4 allocation probe in Phase 3.

### Phase 3E: Record a bounded decision

- [x] Commit compact Phase 3 reports and a summary that distinguishes
  same-session reliability, expected recycle, unexpected death, memory trend,
  output parity, and the remaining Phase 4 cache-safety dependency.
- [x] The matrix passed; retain x86 as `Unsupported` in normal builds and
  carry the result forward only as candidate evidence for Phase 4 and final
  device qualification. Do not enable a user-visible model or process mode in
  this phase.
- [x] Preserve the failure branch: any future unexplained allocation,
  stale-generation, cache, playback, or trend regression ends the pure-x86
  route, removes the validation override, and leaves other ABI policy
  unchanged.
- [x] Evaluate persistent sessions on arm32, arm64, x86_64, and GPU only as a
  later independent optimization. Phase 3 must not silently change their
  already-qualified single-use behavior.

**Phase 3 exit:** passing means one exact x86 session can survive the frozen
same-model matrix and exact model changes can cross an acknowledged fresh
process boundary without harming playback. Production remains `InProcess`,
`BoundRemote` remains internal-only, and pure x86 remains fail-closed until
Phase 4 process-safe cache ownership and later release qualification pass. A
failure ends the pure-x86 route without changing other ABI policy.

## Phase 4: Make Cache Ownership Process-Safe

Phase 4 extends the in-process lease graph with one OS-backed mutation
authority and a durable run journal. Final evidence is recorded in
[Phase 4 process-safe cache validation](validation/litert-inference-process/phase4/validation-2026-07-24.md).

### Phase 4A: Cross-process exact-entry lease

- [x] Add an OS-backed file lock for each exact cache entry.
- [x] Acquire the kernel lock before admitting a writer and retain its open
  file descriptor through the last durable terminal transition. Do not treat a
  successful metadata write as ownership.
- [x] Keep run ID, owner process generation, PID, and timestamps as diagnostic
  metadata; the kernel lock, not metadata age, is authoritative.
- [x] Keep inspection read-only over atomically published state, and make
  main-process delete, prune, promotion, hydration, playback-settings
  mutation, and cleanup respect the same exclusive lock.
- [x] Route operations through one owner where a file lock alone cannot make a
  multi-file transition atomic.
- [x] Define one exact-entry mutation authority at every instant. A main and
  remote process may both read, but may not independently update the same
  journal or manifest under separate in-memory locks.
- [x] Treat disappearance of the cache directory or lock file during a run as
  a typed cache-loss outcome. Never recreate system-cleared cache from model
  or application data while the old run is still active.
- [x] Confirm process death releases the kernel lock.

### Phase 4B: Durable run journal

- [x] Write the admitted immutable request and lifecycle state under the cache
  entry before native setup.
- [x] Give journal records a schema version, monotonic transition sequence,
  run ID, process generation, exact cache/model/contract identity, and last
  committed window.
- [x] Freeze the window commit order: write a run-scoped temporary output,
  flush and close it, verify expected size/integrity, atomically publish the
  segment, and only then journal that window as `Ready`.
- [x] Atomically record terminal transitions only after every required segment
  and output integrity record is durable. A completed journal must never point
  at a temporary or missing file.
- [x] Never persist raw model paths as portable identity.
- [x] Mark pause, user cancel, incompatibility, FGS timeout, cache clearing,
  and unexpected process death distinctly.
- [x] Make journal replay idempotent. Ignore or clean orphan temporary files,
  reject `Ready` records whose published file fails integrity, and ensure an
  incomplete entry cannot become playable.

### Phase 4C: Death and race injection

- [x] Kill the inference process during decode, DSP, native invocation, output
  publication, journal/manifest commit, and terminal commit.
- [x] Keep FLAC promotion main-owned after run-writer release; while its
  exclusive handoff is blocked, kill the idle inference process and prove it
  cannot become a second owner or interrupt promotion.
- [x] Include a real active native-invocation process kill rather than only a
  Kotlin failpoint. Original audio and the main process must survive, and the
  old exact-entry lock must become acquirable only after kernel cleanup.
- [x] Kill the main process while the inference process owns the entry.
- [x] Race cache inspection, deletion, pruning, model deletion, and active-model
  switching against the remote writer.
- [x] Clear cache during an internal test run and require a typed terminal
  outcome without recreation from model/application data.
- [x] Recover only from the next uncommitted window and never duplicate a
  completed segment.
- [x] Repeat every kill point at least three times and distinguish recovery
  correctness from automatic continuation. Phase 4 may require a new explicit
  command to resume; it does not yet authorize background restart.

### Phase 4D: Cross-process cache qualification

- [x] Run the complete death/race matrix first with exact 9662 on pure x86,
  then repeat representative lock, journal, and cache-clear cases on arm64 so
  the contract is not accidentally x86-specific.
- [x] Record lock acquisition/release, journal sequence, published segment
  integrity, old/new process generations, cache terminal state, and playback
  continuity in compact reports.
- [x] Keep FLAC promotion and hydration outside the active writer lease unless
  the test proves a single explicit handoff. No phase may leave two owners
  capable of mutating completed output.

**Phase 4 exit:** process death and management races cannot corrupt, splice,
misidentify, or prematurely promote a cache. Active native-process death is
recoverable from a durable boundary without harming original playback, but no
independent background restart is implied. This exit passed on API 26 x86,
with representative API 31 and API 35 arm64 coverage. Recovery remains an
explicit later request; production remains `InProcess`, `BoundRemote` remains
internal-only, and pure x86 remains fail-closed.

## Phase 5: Compare Host and Session Policies on Every ABI

This phase asks whether process isolation is useful beyond x86 and whether any
non-x86 ABI benefits from retaining a session. It does not enable independent
background execution. Host placement and session lifetime are changed in
separate subphases so their effects remain attributable.

### Phase 5A: Freeze paired-comparison method

- [x] Pair `InProcess` and `BoundRemote` runs by app revision, exact model and
  contract, source fixture, cache identity, backend request, thread settings,
  battery/charger state, and starting thermal status.
- [x] Run at least three cold A/B pairs per policy candidate, alternating which
  host runs first. Report all samples and medians; do not select one favorable
  run.
- [x] Keep non-x86 comparison runs `SingleUse` initially. Pure x86 uses its
  already-qualified resident policy because production-shaped recreation is
  not a valid oracle there.
- [x] Account for instrumentation/test-runner PSS and CPU time separately from
  inference work. Android instrumentation shares the target main PID, so
  record that co-location and count it once rather than inventing a second
  process sample. Record device total/available memory and
  `ActivityManager.isLowRamDevice`, memory/large-memory class, and effective
  `Runtime.maxMemory()` at admission.
- [x] Freeze performance, summed-memory, playback, process-exit, and output
  gates before reviewing the paired results. Include a Java tensor working-set
  gate; total RAM and free virtual-address space are not sufficient proxies.

Phase 5A froze `phase5-paired-host-thresholds-v1` before results and added a
dedicated AB/BA/AB runner. Six S10 arm64 short-fixture smoke runs passed exact
identity/output, cold-process, non-x86 `SingleUse`, resource, thermal, power,
and original-playback gates. This smoke qualifies the method, not a production
host policy or full-song performance result. See
`docs/validation/litert-inference-process/phase5/methodology-2026-07-25.md`.

### Phase 5B: CPU host-placement matrix

- [ ] Compare `InProcess + SingleUse` with `BoundRemote + SingleUse` on S10
  arm32, S10 arm64, S25 arm64, and x86_64. Compare pure x86's unsupported
  baseline only with `BoundRemote + ResidentUntilProcessExit`.
- [ ] Test 9662 everywhere it is currently eligible and KARA only where its
  exact CPU contract and support tier permit it.
- [ ] Record main, remote, instrumentation, and summed PSS/USS/RSS; native,
  graphics, and Java memory; `VmSize`/`VmPeak`; largest free VA gap;
  `oom_score_adj`; and mapped-region count.
- [ ] Record idle-process overhead, bind/start latency, model setup,
  first/reused inference, first-ready-window, full-song throughput, process CPU
  time, memory return after process exit, and time until the old PID and its
  mappings disappear.
- [ ] Measure original-playback position drift, audio underruns, MediaSession
  continuity, and scheduler contention while separation uses its production
  thread policy.
- [ ] Run pure-x86 9662 on 2, 3, and 4 GiB AVD memory configurations, with at
  least three cold process generations per configuration. Treat allocation
  failure, LMKD pressure, or crossing the VA-gap floor as a resource rejection,
  not a reason to lower the gate.
- [x] Repeat x86 smoke on a second pure-x86 API image if a compatible image is
  available. API 29 x86 passed acquisition, packaged-runtime, and IPC controls
  but rejected 9662 because both app processes had a 16 MiB ART heap.
- [x] Repeat the production worker and full process/cache/recovery matrices on
  that API 29 image with a verified 228 MiB ART growth limit. All matrices
  passed, but the limit required a transient privileged property override.
- [ ] Reproduce the passing API 29 result across at least three complete cold
  framework boots without relying on an unrecorded transient property. Record
  `Runtime.maxMemory()` in both app processes and reject the run if it differs
  from the intended envelope.
- [ ] Freeze and validate a pure-x86 runtime heap admission floor before any
  support-tier promotion. AVD `vm.heapSize`, generated hardware settings, and
  guest RAM are not substitutes for the effective per-process ART limit.
- [x] Verify the x86 APK's LiteRT ELF identity and SHA-256 against the pinned
  `bss-litert-android` release and run its CI smoke before any support-tier
  promotion.

Initial low-heap pure-x86 checkpoint (2026-07-25): the second API image is an API
29 AVD configured for 1 GiB but exposing roughly 2 GiB to the guest. Its idle
remote process retained a 1.20 GiB largest free VA gap, and the guest still had
about 1.24 GiB available when ART rejected an 8 MiB tensor allocation against
a 16 MiB per-process growth limit. The small-model LiteRT CPU smoke and all
three process-control tests passed; direct 9662, the production worker, and the
process-session matrix did not. This is a resource rejection, not an x86 ELF,
Binder, LMKD, or VA-fragmentation failure. Remaining inference matrices are
blocked at the same prerequisite and were not repeated. See
`docs/validation/litert-inference-process/phase5/validation-2026-07-25-x86-api29-low-memory.md`.

Pure x86 therefore remains `Unsupported` at this checkpoint. The API 26 result
cannot be generalized to lower effective Java-heap classes. Do not promote x86
unless the complete RAM matrix also records an adequate ART heap or a later
native/direct tensor pipeline removes the current model-sized Java arrays.

Follow-up pure-x86 checkpoint (2026-07-25): editing the AVD heap setting did
not alter the running 16 MiB growth limit. After a temporary root property
override set the effective limit to 228 MiB and the Android framework was
restarted, the same roughly 2 GiB API 29 guest passed the production worker,
direct 9662 parity, 20-cycle resident-session matrix, 20 alternating
9662/KARA process generations, fault matrix, 21-case cache matrix, FLAC/model
management races, and main-process-death recovery. The resident matrix peaked
at 664.5 MiB PSS with a 404.3 MiB largest free VA gap and showed no PSS growth,
LMKD event, unexpected playback event, or unexpected Binder death. See
`docs/validation/litert-inference-process/phase5/validation-2026-07-25-x86-api29-growth228.md`.

This establishes that the prior API 29 rejection was specifically an
effective Java-heap failure, not a LiteRT x86, model-operator, Binder, LMKD, or
VA-gap failure. It does not establish a release-compatible device floor: the
passing limit was transient, the full 2/3/4 GiB matrix and paired host study
remain incomplete, and normal builds still fail closed. Pure x86 therefore
remains `Unsupported`; no catalog or production host policy changes in this
checkpoint.

### Phase 5C: Session-lifetime matrix

- [ ] Test `BoundRemote + ResidentUntilProcessExit` on a non-x86 ABI only after
  that ABI's `BoundRemote + SingleUse` host result passes.
- [ ] Start with armeabi-v7a CPU because it has the strongest non-x86
  address-space rationale; then test arm64 CPU and x86_64 CPU only if measured
  setup/reclamation costs justify the experiment.
- [ ] For every resident candidate, repeat the Phase 3 20-cycle lifecycle,
  same-key full-song bookend, key-change recycle, memory-trend, and original
  playback gates. Do not infer residency safety from a short worker.
- [ ] Keep GPU sessions single-use in this subphase. GPU residency requires its
  own graphics-memory and driver-lifetime decision.
- [ ] Select `SingleUse` unless residency demonstrates a concrete latency or
  reliability gain without unacceptable retained memory or recycle cost.

### Phase 5D: GPU host and fallback matrix

- [ ] Verify accelerator library discovery from the remote arm64 process.
- [ ] Re-run 9662 GPU eligibility, finite-output probe, and at least three
  alternating full-song `InProcess`/`BoundRemote` Auto pairs on S10 and S25.
- [ ] Inject setup, invocation, output-read/non-finite, and cleanup failures.
  Confirm GPU closes before CPU fallback is created when cleanup is known-good.
- [ ] If GPU cleanup is uncertain or fatal, require whole-process recycle
  before CPU creation rather than risking a second allocator in one process.
- [ ] Record graphics/native memory, delegate/library identity, driver
  diagnostics, CPU fallback cost, thermal state, and playback continuity
  separately from aggregate PSS.

### Phase 5E: Process and support policy checkpoint

- [ ] Publish one row per ABI/backend with support tier, release host policy,
  concrete host execution, session lifetime, model/resource scope, and current
  background owner.
- [ ] Decide whether pure x86 becomes `Experimental + RemoteRequired` for exact
  9662, remains `Unsupported`, or has enough repeated evidence for a narrower
  `Supported` scope. KARA and every additional model require separate tier
  evidence; HQ4 remains rejected.
- [ ] Keep unknown imported x86 models outside the stable support promise.
  Decide whether they are blocked from activation or allowed only through an
  explicit unverified-model flow that always uses a fresh process.
- [ ] Decide whether armeabi-v7a is `RemotePreferred` or `RemoteRequired` based
  on 32-bit address-space, total-memory, and recovery evidence.
- [ ] Keep arm64 and x86_64 in process unless remote execution demonstrates a
  concrete reliability benefit without unacceptable total-memory,
  performance, GPU, or playback regressions.
- [ ] Document why a common all-ABI path is or is not worth its idle and active
  memory overhead. Never add automatic runtime fallback from a failed remote
  host to in-process inference.
- [ ] Make an explicit branch decision: a qualified `BoundRemote` policy may
  proceed toward release with current client-bound background semantics even
  if Phases 6-8 later reject independent background execution.

**Phase 5 exit:** each ABI/backend has an evidence-backed support tier, release
host policy, concrete host execution, and session-lifetime candidate. Process
isolation can be accepted or rejected independently of the still-unimplemented
processing foreground service.

## Phase 6: Prototype an Independent Media-Processing Service

This phase changes background ownership. It must remain behind a separate
internal gate from `BoundRemote`. It uses the Phase 5-selected host and session
policy and must not silently change either while background behavior is under
test.

### Phase 6A: Freeze run-class eligibility

- [ ] Classify admitted work as manual full-song, playback-demand window, or
  bounded next-song prefetch in the protocol and journal.
- [ ] Make only an explicitly user-started manual full-song run eligible for
  independent foreground execution in the first prototype.
- [ ] Keep playback-demand work under `PlaybackService` ownership so it cannot
  outlive the playback intent it serves.
- [ ] Keep next-song prefetch client-bound initially. Queue replacement,
  playback stop, or loss of a live main-process decision must prevent it from
  becoming an independently continuing job.
- [ ] Keep model download, activation/deletion, and cache management outside
  the inference foreground service. Decide FLAC handoff separately rather than
  broadening the run implicitly.
- [ ] Record the selected run-class/background policy in diagnostics and
  exclude the internal gate from backup.

### Phase 6B: Foreground-service ownership

- [ ] Let the inference service declare only
  `foregroundServiceType="mediaProcessing"` where the platform supports that
  type, with explicit legacy behavior for API 26-34.
- [ ] Keep `PlaybackService` responsible for `mediaPlayback`.
- [ ] Define a handoff that never leaves active inference unprotected and never
  has two components claiming the processing lifetime indefinitely.
- [ ] Call `startForeground()` within the platform deadline with a dedicated,
  user-comprehensible processing notification.
- [ ] Provide Pause and Cancel notification actions with idempotent command
  IDs and exact run identity.
- [ ] Do not keep an idle or manually paused process in foreground solely to
  retain a model session.
- [ ] Record both foreground services, types, notification IDs, start/stop
  timestamps, and ownership handoffs when playback and separation overlap.

### Phase 6C: Wake-lock ownership

- [ ] Move the active-computation `PARTIAL_WAKE_LOCK` to the process actually
  executing inference.
- [ ] Acquire it only after exact admission, durable journal creation, and FGS
  protection, immediately before heavy setup.
- [ ] Use bounded acquisition/renewal and release it on pause, cancel,
  completion, failure, timeout, cache loss, process teardown, and failed FGS
  promotion.
- [ ] Record every acquire, renewal, and release in validation diagnostics.
- [ ] Ensure the playback process does not retain a duplicate processing lock
  after a successful ownership handoff.

### Phase 6D: Primary platform prototype

- [ ] Implement and validate the first manual full-song prototype on S25
  arm64 CPU. Keep GPU, x86, and session-residency changes out of this first
  lifecycle proof.
- [ ] Start from a visible user command, background the UI, stop playback, and
  turn the screen off while requiring journal progress and one bounded wake
  lock.
- [ ] Handle `ForegroundServiceStartNotAllowedException` and every denied
  promotion as a typed paused/deferred outcome, never a retry loop.
- [ ] Implement and test `Service.onTimeout()` for the Android 15+
  media-processing time budget, including its approximately six-hour
  per-24-hour background allowance.
- [ ] Stop promptly after timeout while preserving the last committed window
  and releasing notification, foreground state, lock, and native session.
- [ ] Test API 35, API 36, and the current highest emulator API before
  expanding to older devices. Record cumulative media-processing FGS budget
  for long and repeated runs.
- [ ] After CPU lifecycle semantics pass, repeat the prototype with the
  Phase 5-qualified S25 GPU policy and verify graphics memory and fallback.
- [ ] During this phase, treat main-process Binder death as a controlled durable
  pause. Continuing without the main process is enabled only after Phase 7
  transfers active-run authority and passes its recovery gates.

**Phase 6 exit:** one exact, user-started manual full-song run can remain
protected on the primary arm64 target with playback stopped and the screen off,
without relying on `PlaybackService`'s processing lease. Playback-demand and
prefetch work remain client-bound, and main-process death continuation is not
yet enabled.

## Phase 7: Support Independent Process Lifetime and Recovery

### Phase 7A: Transfer active-run authority

- [ ] Once an eligible manual full-song run has a durable journal, exact-entry
  kernel lock, and acknowledged processing FGS, make the inference service
  authoritative for that exact admitted run until a durable terminal
  transition.
- [ ] Keep the main coordinator as a proxy and observer rather than a second
  in-memory worker.
- [ ] Let a reconnecting main process query and adopt the current snapshot.
- [ ] Do not let callback loss cancel an otherwise valid foreground run, but
  persist that the observer disconnected and bound how long unobserved work may
  continue.
- [ ] Do not let the inference process admit a new song after completing the
  frozen request without a live main-process decision.
- [ ] Keep playback-demand and prefetch authority in the main/playback process;
  they must not inherit manual full-song survival semantics accidentally.

### Phase 7B: Bounded restart policy

- [ ] Compare sticky restart, redelivered intent, and explicit
  durable-journal restart, then select at most one mechanism. Do not combine
  Android restart modes with a second application retry loop.
- [ ] Gate restart on a matching nonterminal journal, intact model/cache,
  released old kernel lock, available FGS permission/quota, and retry budget.
- [ ] Distinguish system/LMKD death, native fatal state, FGS timeout, and
  protocol incompatibility. Each class needs an explicit retry or terminal
  policy rather than a generic service restart.
- [ ] Apply bounded backoff and stop after repeated death or allocation
  failure. Record every attempt and never create two process generations for
  one retry slot.
- [ ] Never restart work canceled or paused by the user, stopped by FGS
  timeout, invalidated by cache clearing, or made incompatible by model loss.
- [ ] Surface a stable terminal/deferred state after the retry budget ends.

### Phase 7C: User and task lifecycle

- [ ] Preserve the existing "stop when closed from recents" setting.
- [ ] Define and test behavior when playback stops while an eligible manual
  full-song run continues. Playback-demand and prefetch work must stop or pause
  with their owning playback intent.
- [ ] Define whether a manually paused session retains the remote process and
  for how long; do not hold foreground state or wake lock while paused.
- [ ] Decide whether an expired idle-retention deadline or idle memory pressure
  should request acknowledged self-recycle or merely release the private
  binding. Phase 3 proves explicit recycle only; its five-minute API 26
  experiment currently releases the binding and classifies later OS death.
- [ ] Decide whether completed-stem FLAC promotion must move into the remote
  process so an independently running job can finish without the main process.
- [ ] Reconnect notifications, UI, playback readiness, and cache management
  after main-process recreation.
- [ ] Confirm Android force-stop always terminates work without automatic
  resurrection.

### Phase 7D: Recovery and reattachment matrix

- [ ] Kill and restart the main process before FGS handoff, after first durable
  window, during native invocation, after final segment publication, and during
  terminal journal commit.
- [ ] Require a reconnecting client to reject stale Binder generations and
  reconstruct UI state from the durable snapshot without seeking or replacing
  original playback.
- [ ] Kill the inference process at the same boundaries and verify the selected
  bounded-restart policy never duplicates a writer, segment, notification,
  wake lock, or native session.
- [ ] Run user Pause, Cancel, recents removal with both setting values, force
  stop, model deletion, and cache clearing against pending restart state.
- [ ] Commit compact reports that separate continuation, explicit resume,
  bounded restart, and terminal defer; a single generic "recovered" result is
  insufficient.

**Phase 7 exit:** an admitted run may survive main-process death, or the feature
is rejected with a documented reason. Only the explicitly selected run classes
may continue. Recovery is bounded, cache-safe, visible, and incapable of
creating duplicate ownership.

## Phase 8: Background and OEM Qualification

Qualification uses a layered matrix, not every scenario on every ABI. Android
API/FGS behavior is primarily covered by modern emulators and arm64 devices;
Samsung task and battery behavior is covered by both physical generations;
32-bit ABI runs focus on address space, resource pressure, and recovery. Any
ABI promoted to production still receives a release smoke for every enabled
run class.

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

Manual full-song continuation, playback-demand work, and next-song prefetch
must be reported separately. A passing manual run cannot be used to claim that
prefetch may outlive playback.

### Phase 8B: Devices

- [ ] Run the complete scenario, notification, recents, screen-off, and Samsung
  battery-policy matrix on Galaxy S10 arm64 and Galaxy S25 arm64.
- [ ] Run the complete modern foreground-service policy matrix on API 35, API
  36, and the current highest supported emulator API.
- [ ] Run targeted long-job, low-memory, process-death, and recovery smokes on
  Galaxy S10 armeabi-v7a, API 26 pure x86, and the current x86_64 emulator.
- [ ] Re-run the selected x86 support scope at its passing and first failing AVD
  memory configurations. Do not publish an untested minimum-RAM claim.
- [ ] Record explicitly that emulator-only x86 evidence does not constitute
  real x86 device/OEM coverage. Narrow the support tier if no representative
  hardware can be tested.
- [ ] Samsung battery modes: Unrestricted, Optimized, and Restricted where the
  OS permits the comparison.

### Phase 8C: Evidence

- [ ] Compare windows per minute, first-ready time, full-song duration, CPU
  time, thermal status, and battery discharge.
- [ ] Compare main, remote, instrumentation, and summed memory plus
  graphics/native allocation, total available system memory, LMKD events,
  `oom_score_adj`, and memory returned after process exit.
- [ ] Record wake-lock duration and require no lock after every terminal state.
- [ ] Record foreground-service type, notification lifetime, start failures,
  timeouts, process deaths, restart count, and progress gaps.
- [ ] Record audio underruns, playback discontinuities, and MediaSession state.
- [ ] Verify output, frame, decode-route, join, cache, and FLAC gates.
- [ ] Perform representative listening only as a regression check; do not use
  this phase to retune window decoding.
- [ ] Run at least three cold repetitions for every performance or memory
  comparison used to promote policy, alternate baseline/candidate order, and
  publish all samples plus medians.

### Phase 8D: Run-class and support-tier gate

- [ ] Decide independently whether manual full-song, playback-demand, and
  bounded prefetch use client-bound or independent background ownership.
- [ ] Publish support tier, release host policy, concrete host execution,
  session lifetime, background owner, model/resource scope, minimum tested
  memory, Android API range, and device-evidence scope for every production
  candidate.
- [ ] Reject independent background without rejecting an otherwise qualified
  `BoundRemote` process-isolation policy.
- [ ] Reject one ABI/backend/model scope without weakening another row's
  frozen gates.

**Phase 8 exit:** background execution is promoted only if it preserves
correctness and playback, has bounded recovery, meets the Phase 0 memory and
performance thresholds, and behaves acceptably on both Samsung generations
and modern Android FGS policy. Promotion is per run class and support tier, not
an all-or-nothing app-wide switch.

## Phase 9: Select Production Policy and Remove Transitional Ownership

### Phase 9A: Policy decision

- [ ] Publish an evidence table for each ABI/backend/model-resource scope that
  selects support tier, release host policy, concrete host execution, session
  lifetime, and background owner for manual full-song, playback-demand, and
  prefetch work.
- [ ] Prefer one common production architecture only when the all-ABI evidence
  justifies its memory and lifecycle cost.
- [ ] Keep pure x86 fail-closed if cache safety, process recycling, memory-tier
  repeatability, binary provenance, or release smoke is incomplete. If enabled,
  require `BoundRemote`, exact known-good model scope, and the recorded support
  tier; do not imply HQ4 or unknown-model support.
- [ ] Keep HQ4's separate model-resource restrictions regardless of process
  mode.
- [ ] Decide the unknown imported-model policy per ABI. An unverified custom
  profile must not inherit the stable tier of a same-shaped known artifact.
- [ ] Record process isolation and independent background as separate release
  decisions. Failure of the latter must not force a qualified host policy back
  in process.

### Phase 9B: Production cleanup

- [ ] Remove duplicate worker, foreground-service, wake-lock, and cache-lease
  ownership from the nonselected path.
- [ ] Keep a test oracle for in-process correctness if production becomes
  remote, but do not expose automatic runtime fallback between hosts.
- [ ] Remove internal process-mode controls from release UI. User-visible
  labels may describe experimental support or resource rejection, but users do
  not select an unsafe host/session combination manually.
- [ ] Update compatibility catalog evidence only after device reports are
  committed.
- [ ] Update the main LiteRT roadmap, runtime documentation, release notes, and
  user-visible unsupported-device messages.
- [ ] Verify ABI split APK and universal APK service/native-library inventory.
- [ ] Pin the x86 LiteRT release URL/version, ELF identity, SHA-256, build
  provenance, and CI smoke in the application release record whenever x86 is
  not `Unsupported`.

### Phase 9C: Release gate

- [ ] Repeat clean-install model acquisition, selection, separation, playback,
  cache management, backup/restore, and clear-cache recovery.
- [ ] Repeat full-song and every enabled run-class/background smoke on each
  production-enabled ABI/backend/model scope, including the minimum tested
  memory configuration for a resource-limited tier.
- [ ] Verify no service, binder callback, session, file lock, notification, or
  wake lock remains after completion or cancellation.
- [ ] Record the final app commit, protocol version, process policy, model
  hashes, x86 runtime artifact where applicable, support tier, and device/API
  evidence scope.
- [ ] Verify unsupported and out-of-scope combinations still fail before native
  allocation with a stable user-facing reason.

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
- support tier, release host policy, concrete host execution, session lifetime,
  and background owner by run class;
- process mode and process generations;
- main and inference PIDs;
- model artifact SHA-256 and contract/profile identity;
- LiteRT binary/ELF identity and SHA-256 when a custom packaged runtime is
  under qualification;
- concrete CPU/GPU backend and fallback;
- source fixture and decode route;
- cache key and run ID;
- foreground-service and wake-lock timeline;
- per-process, instrumentation, and summed memory; device total/available
  memory; configured AVD memory; low-RAM classification; and LMKD evidence;
- repetition number, cold/warm classification, A/B order, and the complete
  sample set used for each median;
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
| x86 passes only on an oversized emulator | fixed 2/3/4 GiB AVD matrix, minimum passing scope, and emulator-only disclosure |
| Custom x86 runtime drifts from its build source | pinned release, ELF/hash verification, provenance, and CI smoke |
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
- Keep non-x86 sessions single-use during the first host-placement comparison;
  test residency only as a separate optimization after the host passes.
- Treat pure x86 as an `Experimental + RemoteRequired` candidate for exact
  9662, not as supported, until Phase 4/5/8/9 gates pass. KARA and every other
  model retain independent evidence tiers.
- Test bound isolation before adding a second foreground service.
- Make the inference process own foreground and wake-lock state only in the
  independent-background experiment.
- Make only explicit manual full-song work eligible for the first independent
  foreground prototype; keep playback-demand and prefetch client-bound.
- Allow Phase 5 to accept process isolation independently of whether later
  independent background execution is accepted.
- Treat all ABI process placement as evidence-driven.
- Never modify current window-decoding policy as part of process migration.

## Decisions That Must Remain Open Until Tested

- Whether armeabi-v7a should use remote execution by default.
- Whether arm64/x86_64 reliability gains justify active total-memory overhead.
- Whether non-x86 CPU or GPU sessions should ever remain resident after a run.
- Whether pure x86 can pass a useful minimum-memory scope repeatedly enough for
  `Experimental` or `Supported`, given emulator-only device evidence.
- Whether unknown imported models may be activated on x86 and, if so, what
  explicit unverified-resource flow contains their failure.
- Whether GPU Auto remains stable in a service process on both Samsung devices.
- Whether the service should retain an idle same-model session after pause.
- Which bounded restart mechanism is reliable across API 26 through target 36.
- Whether FLAC promotion belongs in the independently running process.
- Whether a separate processing notification can be grouped without obscuring
  playback controls.
- Whether background auto-prefetch can start or promote the service reliably
  under modern FGS restrictions.
- Whether playback-demand windows or prefetch should ever become independently
  owned after the manual full-song path is qualified.
- Whether independent background completion is worth its wake-lock, quota,
  battery, and OEM-policy cost.

## Overall Completion Criteria

This roadmap is complete only when:

1. pure x86 has an explicit support tier, model/resource scope, minimum tested
   memory, binary provenance, and remote-process policy, or remains
   unsupported;
2. every other ABI/backend has an evidence-backed release host policy,
   concrete host execution, and session-lifetime decision;
3. independent background execution is either qualified or explicitly
   rejected per run class, separately from process isolation;
4. source decode and output behavior remain unchanged;
5. playback survives remote failures;
6. cache ownership and recovery are process-safe;
7. foreground-service and wake-lock ownership are singular and bounded; and
8. the final support tier, release host policy, concrete host execution,
   session/background policy, model/resource scope, and evidence limitations
   are reflected in catalog and release documentation.
