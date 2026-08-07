# LiteRT Inference Process and Background Execution Roadmap

Status: frozen historical process and background-execution plan. No new
product, runtime-acquisition, resource-management, or backend-settings work is
planned in this document.

Last recorded implementation state: Phase 7A authority transfer and the Phase
7B zero-automatic-retry
remote-death policy are implemented; observer and product reattachment,
explicit retry after inference-process death, active-run Pause/Cancel cleanup,
recents policy, playback-owned shutdown, and force-stop non-resurrection are
proved on S10 and S25 for CPU and bounded GPU; post-death cache invalidation is
proved in both backend-policy directions on S25; post-death model switching,
old-weight deletion, cache isolation, and exact primary-model restoration are
proved on S25; stale client-binding callbacks are isolated and durable
separation-state reconstruction without playback replacement is proved on
S10; a retired bounded-runtime identity is rejected on explicit retry without
automatic rescheduling on S10; missing and exactly restored TFLite weights
remain terminal until a second explicit start on S10; a durable GPU fallback
latch resumes directly on CPU while retaining its bounded-GPU identity on S10;
after real S10 main-process recreation, CPU and bounded GPU runs retain their
processing notification, expose a playable partial-cache window, and
reconstruct the exact active entry for cache-management state

Updated: 2026-07-31

## Model Input Baseline Supersession

The model delivery baseline has since moved to the immutable
`bss-tflite` Release `v0.2.0-experimental.1`, whose v3 catalog contains 33
selectable experimental models. This frozen process document does not gain new
implementation tasks, but any future process-recreation or background test
that needs a model must acquire it through the production GitHub catalog,
artifact, sidecar, and SHA-256 verification path. Local staged fixtures remain
diagnostic evidence only. Model selection, cache identity, multistem playback,
and release qualification remain owned by the active companion roadmaps.

The unchecked tasks below are retained as the evidence gaps and release gates
that existed when this experiment was frozen. They are not an active product
backlog and will not be extended here. Runtime downloading, Quick Setup,
Runtime Management, GPU/NPU preference ownership, AOT, and QNN JIT are governed
by the
[Downloadable Runtime, Quick Setup, and Local Resource Management Roadmap](litert-runtime-setup-roadmap.md).
Completed validation artifacts and their legacy field names remain immutable.

This roadmap governs two related but separate experiments:

1. isolate LiteRT inference and source-separation execution from the playback
   process; and
2. decide whether that inference process should later own an independent
   background-execution lifetime.

The first experiment addresses native-memory isolation, deterministic runtime
reclamation, and the unsafe pure-x86 session lifecycle. The second changes
Android service, wake-lock, notification, and process-death behavior. Passing
the first experiment does not authorize the second.

This is a historical companion to the
[LiteRT and Multi-Preset Roadmap](litert-multi-preset-roadmap.md). The main
roadmap remains authoritative for model contracts, cache identity, playback,
model management, and model release qualification. This document records the
process-placement and background-execution experiment. The dedicated runtime
setup roadmap now supersedes every user-facing `tryGpu`, packaged-runtime,
runtime-acquisition, and backend-management decision in this document.

## Frozen Release and GPU Runtime Baseline

The near-term product is distributed through GitHub only. F-Droid acceptance,
Maven Central publication, and a fully source-rebuilt ML Drift GPU accelerator
are not release gates for this roadmap. They may be reconsidered in a separate
distribution track without blocking the GitHub APK.

The GitHub build uses the pinned `runtime-v2.1.5-bss.2` artifact and its
validated MACE-style bounded OpenCL queue behavior with `N=1`. This means the
exact tested wait boundary is
part of a versioned GPU runtime profile, not a user preference or an adaptive
hint. The stock unbounded `N=0` runtime remains a diagnostic and rollback
oracle only. It is not an automatic runtime fallback.

The completed process experiments used one persistent `tryGpu` Boolean, exposed
at that time under Advanced settings, to compare CPU-only and bounded-GPU
admission. That location and field name are historical test interfaces, not
the current product contract. Runtime Management now owns persistent
`gpuEnabled` and `npuEnabled` intent, and Quick Setup owns recommended defaults.
Each newly admitted run still freezes one versioned backend-policy snapshot.

For the completed GPU evidence, enabled meant that the run attempted
`gpu-opencl-bounded-fp32-v1` when runtime and model eligibility passed. A
recoverable setup, probe, invocation, or output failure could fall back once to
CPU only after GPU cleanup was confirmed. Uncertain cleanup, native death, or
unsafe memory pressure poisoned the process generation and required recycle or
termination without allocating CPU beside it. Disabled meant CPU-only
execution and no GPU environment, accelerator, probe, or session allocation.

The admitted policy is frozen for each run. Changing persistent intent affects
later runs only. Foreground, background, Activity visibility, and screen state
never change the frozen choice or cause a GPU/CPU session transition. The
historical experiment resolved capability from the packaged bounded artifact;
the product resolves it from the verified installed component, ABI and model
profile eligibility, accelerator discovery, successful setup/probe, and valid
output. It is not determined by an OEM, device-model, or GPU-driver allowlist.

Until public ML Drift sources can replace the binary transformation, the
custom AAR may be produced from the pinned official LiteRT AAR by the audited,
deterministic `bss-litert-android` workflow. GitHub-only distribution permits
that binary-derived artifact, but does not relax artifact hashes, patch
manifests, native-component inventory, reproducibility, device qualification,
or one-way GPU-to-CPU fallback requirements.

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
- Make bounded `N=1` OpenCL submission the only production-candidate GPU
  profile and verify that its foreground responsiveness gain survives final
  app integration and background-process placement.
- Default every dynamically eligible device to attempting that GPU profile,
  while preserving a persistent user-controlled CPU-only choice.
- Keep the admitted GPU preference and backend policy invariant across
  foreground, background, and screen-state changes.
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
- It does not expose queue-window size, forced backend, or stock `N=0` GPU as a
  user setting.
- It does not maintain an OEM, device-model, or GPU-driver allowlist. Device
  coverage remains validation evidence, not an admission database.
- It does not make F-Droid, Maven Central, or a source-only GPU accelerator a
  prerequisite for the near-term GitHub release.

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
- The admitted request freezes a versioned backend-policy snapshot. Completed
  protocol versions represented GPU intent as `tryGpu`; the product contract
  represents optional backend intent through `gpuEnabled` and `npuEnabled` in
  Runtime Management.
- A GPU-disabled admitted policy selects CPU before any GPU allocation attempt.
  A GPU-enabled policy selects the bounded GPU attempt whenever its higher
  priority eligible NPU path has not been selected and the ABI, model profile,
  installed capability, and runtime accelerator checks permit it.
- A bounded GPU run additionally freezes the custom LiteRT artifact identity,
  bounded-queue capability version, forced OpenCL FP32 profile, and queue
  window `N=1`. A process that cannot attest to that exact capability must not
  create an unbounded GPU session.
- GPU fallback remains one-way within the same exact run and cache identity.
- A missing, mismatched, or failed bounded GPU capability is a typed GPU skip
  or failure and may proceed only through the existing known-good CPU path. It
  must never fall back to stock `N=0` GPU in the same release.
- Cancellation is not a fallback.
- A model switch affects only work admitted afterward.
- A persistent backend-policy change affects only work admitted afterward. App
  foreground/background transitions and screen state never rewrite the
  admitted snapshot or trigger backend recreation.
- Unknown or unsupported ABI/model/backend combinations continue to fail
  before native allocation.
- HQ4 retains its current compatibility and resource gates.

Phase 3 may use one compile-time and AndroidTest-only exception for the exact
pinned x86 9662 and KARA artifacts. Every such report must retain the original
`Unsupported` decision and identify the effective validation-only override.
Normal debug, CI, and release graphs must continue to reject those records
before native allocation until a later explicit compatibility decision.

### Legacy GPU preference and capability evidence

- Completed phases used `tryGpu` as the stable Boolean test input. Existing
  journals, Binder payloads, fixtures, and validation reports retain that name.
- `tryGpu` and its former Advanced-section location are not the product UI or
  persistence contract. Runtime Management owns the canonical `gpuEnabled` and
  `npuEnabled` settings defined by the dedicated runtime setup roadmap.
- Recommended setup enables a release-qualified selected GPU component. Both
  enabled and disabled user intent survive process and app restarts and belong
  to the versioned source-separation backup allowlist.
- Enabling GPU never promises that it will produce the accepted output. It
  requests one bounded attempt for each newly admitted eligible run after any
  higher-priority exact NPU path is resolved. Typed ineligibility or failure
  uses the known-good CPU path when fallback is safe.
- Disabling GPU is strict for the admitted run: no GPU accelerator discovery,
  model compilation, deterministic probe, command submission, or graphics
  allocation may occur.
- A run records requested intent, eligibility, concrete backend, runtime
  identity, and fallback reason. Runtime failure must not silently mutate the
  persistent settings or create a per-device blacklist.
- `N`, OpenCL/OpenGL choice, CPU thread count, process placement, GPU-only mode,
  and fallback internals remain unavailable as user settings.

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
- Read `tryGpu` once and freeze it in a small, versioned execution request.
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
- When the admitted request has `tryGpu=true`, evaluate dynamic GPU eligibility
  and own the bounded GPU probe and one-way CPU fallback. When it is false,
  enter the CPU path without touching GPU allocation APIs.
- Verify the release-candidate bounded-queue runtime capability before GPU
  model creation and record the exact AAR/native identities and observed wait
  counters.
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
- the admitted `tryGpu` value, exact bounded GPU profile/capability identity
  when requested, and a one-way fallback latch;
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
| GPU dispatch profile | bounded `N=1`, stock `N=0` oracle | which exact command-submission contract is under test |
| User GPU policy | `TryGpu` (default), `CpuOnly` | whether a newly admitted eligible run may allocate GPU resources |

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
- `tryGpu=false` produces no GPU allocation attempt, while `tryGpu=true`
  attempts bounded GPU on every dynamically eligible device without consulting
  a static device allowlist;
- an admitted run keeps the same user GPU policy across Activity, process
  binding, foreground/background, and screen-state transitions;
- original playback survives a remote runtime failure; and
- reports identify app commit, model hash, contract, ABI, Android API, process
  mode, process generation, backend, and source fixture.

Any GPU promotion report must also identify the custom AAR release and
SHA-256, accelerator and shim hashes, bounded-queue capability version,
persisted and admitted `tryGpu` values, eligibility result, requested `N`,
observed wait count, and proof that the app did not resolve a stock or duplicate
LiteRT artifact.

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
  settings. `tryGpu=true` on CPU-only pure x86 must resolve to the CPU backend
  before the key is accepted and without a GPU allocation attempt.
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

- [x] Compare `InProcess + SingleUse` with `BoundRemote + SingleUse` on S10
  arm32, S10 arm64, S25 arm64, and x86_64.
- [x] Compare pure x86's unsupported baseline only with
  `BoundRemote + ResidentUntilProcessExit`.
- [x] Test 9662 on every eligible non-x86 CPU target. Test KARA and pure x86
  only where their exact CPU contract and support tier permit it.
- [x] Record main, remote, instrumentation, and summed PSS/USS/RSS; native,
  graphics, and Java memory; `VmSize`/`VmPeak`; largest free VA gap;
  `oom_score_adj`; and mapped-region count.
- [x] Add dedicated bind/start, model-setup, and first/reused-inference timing.
  Keep idle-process overhead, first-ready-window, full-song throughput, process
  CPU time, and time until the old PID and mappings disappear as separate
  fields.
- [x] Add explicit audio-underrun and scheduler-contention counters. Keep
  original-playback position drift, MediaSession continuity, and unexpected
  player events separate rather than treating them as underrun proxies.
- [x] Run pure-x86 9662 on 2, 3, and 4 GiB AVD memory configurations, with at
  least three cold process generations per configuration. Treat allocation
  failure, LMKD pressure, or crossing the VA-gap floor as a resource rejection,
  not a reason to lower the gate.
- [x] Repeat x86 smoke on a second pure-x86 API image if a compatible image is
  available. API 29 x86 passed acquisition, packaged-runtime, and IPC controls
  but rejected 9662 because both app processes had a 16 MiB ART heap.
- [x] Repeat the production worker and full process/cache/recovery matrices on
  that API 29 image with a verified 228 MiB ART growth limit. All matrices
  passed, but the limit required a transient privileged property override.
- [x] Reproduce the passing API 29 result across at least three complete cold
  framework boots without relying on an unrecorded transient property. Record
  `Runtime.maxMemory()` in both app processes and reject the run if it differs
  from the intended envelope.
- [x] Freeze and validate a pure-x86 runtime heap admission floor before any
  support-tier promotion. AVD `vm.heapSize`, generated hardware settings, and
  guest RAM are not substitutes for the effective per-process ART limit.
- [x] Verify the x86 APK's LiteRT ELF identity and SHA-256 against the pinned
  `bss-litert-android` release and run its CI smoke before any support-tier
  promotion.

Observability checkpoint (2026-07-26): a six-sample S10 arm64 short-fixture
matrix retained remote bind-to-connect, model setup, first-inference, and
reused-inference timing on both hosts. Every sample recorded three real model
invocations. A debug-only Media3 listener and player-thread `/proc` sampler
recorded zero audio underruns plus non-zero run-queue wait, timeslice, and
context-switch deltas in all six runs. This qualifies the counters, not a new
threshold or host decision. See
`docs/validation/litert-inference-process/phase5/observability-smoke-2026-07-26.md`.

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

This established that the prior API 29 rejection was specifically an
effective Java-heap failure, not a LiteRT x86, model-operator, Binder, LMKD, or
VA-gap failure. At that checkpoint it did not establish a release-compatible
device floor because the passing limit was transient and the RAM matrix was
incomplete. The later memory checkpoint below closes those resource tests but
does not itself change the release policy.

Non-x86 CPU checkpoint (2026-07-25): complete three-pair full-WAV matrices
passed on S10 arm32, S10 arm64, S25 arm64, and API 37 x86_64. Bound remote
added 17.7-25.4 MiB median summed peak PSS and stayed within every performance,
output, process-exit, and playback gate. Arm32 reduced median peak main-process
PSS from 755.5 MiB to 150.1 MiB while retaining a 464.2 MiB minimum remote VA
gap, so it advances to the Phase 5C resident-session experiment. Arm64 and
x86_64 remain in process pending a concrete reliability benefit.

Paired report schema v2 corrected a measurement mismatch without changing the
frozen 96 MiB limit: it gates PSS sampled after the established two-second
idle settle, while retaining immediate bind-time PSS separately. The first
arm32 full run remains recorded as rejected under the incorrect v1 field; the
corrected short and full repeats passed. See
`docs/validation/litert-inference-process/phase5/cpu-host-matrix-2026-07-25.md`.

Pure x86 memory checkpoint (2026-07-25): an automated API 29 matrix completed
three cold process generations on each explicitly requested 2, 3, and 4 GiB
AVD configuration. Three distinct cold boots exposed 2,089,164,800,
3,142,983,680, and 4,132,405,248 bytes to the guest, so the 32-bit image can
use nearly 4 GiB rather than being capped at 3 GiB. All nine exact-9662 workers
passed with one resident native session, identical output, zero unexpected
playback events, and at least 473,837,568 bytes of largest free VA gap.

A fourth cold boot set both ART growth and large-heap limits to exactly 128
MiB. Three more process generations reported exactly 134,217,728 bytes from
`Runtime.maxMemory()` in both processes and passed with at least 1,079,291,904
bytes of largest free VA gap. This directly validates the frozen runtime-heap
floor for exact 9662 and the current Java tensor pipeline. It does not qualify
unknown models or HQ4. Normal builds still fail closed until Phase 5E selects
and implements a support policy. See
`docs/validation/litert-inference-process/phase5/validation-2026-07-25-x86-memory-matrix.md`.

### Phase 5C: Session-lifetime matrix

- [x] Test `BoundRemote + ResidentUntilProcessExit` on a non-x86 ABI only after
  that ABI's `BoundRemote + SingleUse` host result passes.
- [x] Start with armeabi-v7a CPU because it has the strongest non-x86
  address-space rationale. Do not advance arm64 CPU or x86_64 CPU because the
  host matrix found no reliability need and arm32 residency retained roughly
  633-657 MiB without a measured setup or reliability gain.
- [x] For every resident candidate, repeat the Phase 3 20-cycle lifecycle,
  same-key full-song bookend, key-change recycle, memory-trend, and original
  playback gates. Do not infer residency safety from a short worker.
- [x] Keep GPU sessions single-use in this subphase. GPU residency requires its
  own graphics-memory and driver-lifetime decision.
- [x] Select `SingleUse` unless residency demonstrates a concrete latency or
  reliability gain without unacceptable retained memory or recycle cost.

Arm32 resident checkpoint (2026-07-25): S10 `armeabi-v7a` passed one
process generation, one native session, 141 invocations, 20 lifecycle cases,
and two byte-identical 273.7-second WAV bookends. Cycle-2 to cycle-20 PSS
changed by -4,931,584 bytes, mapped regions changed by -9, and the minimum
largest free VA gap was 486,793,216 bytes. A 20-switch 9662/KARA matrix then
created 20 acknowledged new generations with exactly one session each, zero
unexpected Binder deaths, and zero unexpected playback events.

The experiment also confirmed that main-process private warm retention can
preserve a healthy arm32 generation across a client rebind; it does not create
independent background execution. Because keeping that idle generation retains
roughly 633-657 MiB PSS and no latency or reliability advantage over
`SingleUse` was established, the arm32 session candidate remains `SingleUse`.
The host candidate remains `BoundRemote`, pending Phase 5E's
`RemotePreferred` versus `RemoteRequired` decision. See
`docs/validation/litert-inference-process/phase5/arm32-resident-session-2026-07-25.md`.

### Phase 5D: GPU host and fallback matrix

- [x] Verify accelerator library discovery from the remote arm64 process.
- [ ] Re-run 9662 GPU eligibility, finite-output probe, and at least three
  alternating full-song `InProcess`/`BoundRemote` pairs on S10 and S25 with
  app-level `TryGpu` admission and the final `gpu-opencl-bounded-fp32-v1`
  artifact. The GPU branch must be FP32/OpenCL/`N=1`, with only one-way CPU
  fallback. The completed stock `N=0` S10 pairs remain a baseline, not
  promotion evidence for the new profile.
- [x] Inject setup, invocation, output-read/non-finite, and cleanup failures.
  Confirm GPU closes before CPU fallback is created when cleanup is known-good.
- [x] If GPU cleanup is uncertain or fatal, require whole-process recycle
  before CPU creation rather than risking a second allocator in one process.
- [x] Record graphics/native memory, delegate/library identity, driver
  diagnostics, CPU fallback cost, thermal state, and playback continuity
  separately from aggregate PSS.
- [x] Attribute foreground GPU UI stalls and test the stock AAR's forced
  OpenGL and OpenCL low-priority controls without changing decode policy.
- [x] Test a MACE-style bounded OpenCL command queue through a strict
  diagnostic AAR, including tensor parity, whole-song output hashes,
  throughput, memory, thermal state, and foreground FrameTimeline/fence
  attribution on S10 and S25.

GPU checkpoint (2026-07-26): the stock-`N=0` baseline passed three alternating
full-song Auto pairs on each S10 host. Bound remote moved the 265.8 MiB graphics
allocation and OpenCL mapping out of the main process without duplicating
graphics PSS, but added 32.1 MiB median summed PSS, 595 ms first-ready time, and
2.27 seconds full-song time. Both S10 and S25 passed setup, invocation,
output-read, non-finite, and cleanup fault matrices. Known-good cleanup closes
GPU before creating CPU; fatal cleanup poisons the generation and forces
acknowledged process recycle without CPU creation. This historical host
baseline selected `InProcess + SingleUse`; it does not qualify the bounded
profile. The S25 host matrix and final S10/S25 bounded-profile pairs remain
open. See
`docs/validation/litert-inference-process/phase5/gpu-host-fallback-2026-07-26.md`.

GPU UI tuning checkpoint (2026-07-26): S25 Perfetto traces prove that every
measured long frame overlaps an app GPU-completion fence. Explicit OpenCL
reproduces the automatic backend's approximately 240 ms wait. OpenCL low
priority worsens the maximum wait to approximately 1.4 seconds and full-song
time from 31.8 to 86.1 seconds. OpenGL worsens the wait to approximately
0.65 seconds and peak PSS from 813 MiB to 1,236 MiB. Both diagnostic profiles
are rejected. Stock LiteRT 2.1.5 exposes native OpenCL kernel batching but not
through its Kotlin/JNI AAR surface; testing smaller flush batches requires a
separate custom-AAR experiment. See
`docs/validation/litert-inference-process/phase5/gpu-ui-contention-2026-07-26.md`.

OpenCL queue-window checkpoint (2026-07-26): the diagnostic AAR redirects the
otherwise unused Kotlin command-preparation setter to native
`kernel_batch_size` and waits on every Nth NDRange boundary event only during
`CompiledModel.run()`. `N=1` preserved the frozen tensor result and produced
identical WAV/FLAC hashes across 11 whole-song runs. On S25 it raised the
marked swipe interval from 61.5 to 116.8 fps, removed all frames above 50 ms,
and reduced the maximum GPU wait from 247 to 30 ms. On S10 it raised 22.3 to
37.0 fps and reduced the maximum frame from 1,364 to 201 ms, but one long
kernel still exceeds 200 ms. No no-Perfetto pair showed a systematic
whole-song throughput regression. The mechanism is effective, but the binary
transformation was diagnostic at this checkpoint and S10 remains visibly
imperfect. The GitHub-only decision now permits the same deterministic binary
transformation to advance through a pinned release-candidate AAR instead of
waiting for upstream ML Drift sources. It does not promote that AAR without
the Phase 5F identity, fallback, final-integration, and runtime-capability
gates. The product will not switch S10 or any other device to CPU merely while
the UI is visible. `N=1` remains active for the admitted GPU run in every app
state; a user who prefers strict CPU execution can disable `尝试使用 GPU` for
subsequent runs. See
`docs/validation/litert-inference-process/phase5/gpu-opencl-queue-window-2026-07-26.md`.

### Phase 5E: Process and support policy checkpoint

- [x] Publish one row per ABI/backend with support tier, release host policy,
  concrete host execution, session lifetime, model/resource scope, and current
  background owner.
- [x] Decide whether pure x86 becomes `Experimental + RemoteRequired` for exact
  9662, remains `Unsupported`, or has enough repeated evidence for a narrower
  `Supported` scope. KARA and every additional model require separate tier
  evidence; HQ4 remains rejected.
- [x] Keep unknown imported x86 models outside the stable support promise.
  Decide whether they are blocked from activation or allowed only through an
  explicit unverified-model flow that always uses a fresh process.
- [x] Decide whether armeabi-v7a is `RemotePreferred` or `RemoteRequired` based
  on 32-bit address-space, total-memory, and recovery evidence.
- [x] Keep arm64 and x86_64 in process unless remote execution demonstrates a
  concrete reliability benefit without unacceptable total-memory,
  performance, GPU, or playback regressions.
- [x] Document why a common all-ABI path is or is not worth its idle and active
  memory overhead. Never add automatic runtime fallback from a failed remote
  host to in-process inference.
- [x] Make an explicit branch decision: a qualified `BoundRemote` policy may
  proceed toward release with current client-bound background semantics even
  if Phases 6-8 later reject independent background execution.

Phase 5E checkpoint (2026-07-26): arm32 selects
`Supported + RemoteRequired + BoundRemote + SingleUse`; 64-bit CPU targets
remain `Supported + InProcess + SingleUse`; arm64 GPU selects the
`InProcess + SingleUse + gpu-opencl-bounded-fp32-v1 + N=1` candidate for 9662,
pending the final AAR and S10/S25 host/UI/full-song matrices. Pure x86 selects
an exact-9662-only
`Experimental + RemoteRequired + BoundRemote + ResidentUntilProcessExit`
candidate with a 128 MiB effective runtime-heap floor, but normal builds stay
fail-closed until later release qualification. KARA, HQ4, other models, and
custom imports are not included in the x86 scope. All remote rows remain
client-bound and never fall back to an in-process host at runtime. See
`docs/validation/litert-inference-process/phase5/process-support-policy-2026-07-26.md`.

### Phase 5F: Freeze the GitHub bounded-GPU runtime

This subphase converts the successful diagnostic `N=1` experiment into the
only GPU artifact/profile eligible for the near-term GitHub release. It does
not change the Phase 5 host decision: an admitted arm64 GPU run remains
`InProcess + SingleUse` until a later background-ownership phase explicitly
tests another host.

- [x] Publish an immutable `bss-litert-android` GitHub Release that records the
  pinned official LiteRT input AAR, deterministic transformation and shim
  revisions, output AAR/Maven-bundle SHA-256, every native component hash,
  per-ABI ELF inventory, notices, SBOM, and build provenance. Reproduce the
  same release candidate on a clean runner before app integration.
- [x] Make the Booming SS build fetch or materialize that exact release asset
  through a checksum-verifying setup step. Pin tag and hash; never use
  `latest`. Fail the build if stock LiteRT is also resolved transitively, if a
  required native component is missing, or if an ABI contains duplicate
  runtime/accelerator libraries.
- [x] Introduce the explicit runtime profile
  `gpu-opencl-bounded-fp32-v1`. It freezes FP32, OpenCL, and the exact tested
  `N=1` wait boundary. If the binary implementation must reuse an otherwise
  unused upstream option internally, contain that detail inside the runtime
  adapter and expose only the bounded-queue name and capability to app code.
- [x] Add the persistent `尝试使用 GPU` switch to the source-separation panel's
  Advanced section. Default a missing key to enabled. Do not expose `N`, a
  forced graphics API, GPU-only mode, process placement, or CPU thread count.
- [x] Freeze `tryGpu` at run admission and carry it through the execution
  request, protocol, journal, diagnostics, and restart snapshot. A setting
  change during an active or paused run affects only a newly admitted run.
  Bump the protocol and journal schemas rather than interpreting absent fields
  through mutable process preferences.
- [x] Add `tryGpu` to the versioned source-separation settings backup allowlist.
  Restore it only when that category and key are present; an upstream or older
  backup without the key must not overwrite the destination value.
- [x] Add an early native capability/build-ID check. Missing or mismatched
  capability must skip GPU and use the known-good CPU path; it must not create
  stock `N=0` GPU and must not rely on a log message as capability proof.
- [x] Replace static OEM/device/GPU-driver admission with dynamic eligibility:
  `tryGpu=true`, GPU-capable packaged ABI, GPU-eligible model profile, exact
  bounded capability, accelerator discovery, successful compile/probe, and
  valid output. A failure is typed and falls back one-way to CPU when cleanup
  is safe; it must not persistently disable the user's setting.
- [x] Keep `N=1` unchanged while the app is foregrounded, backgrounded, or
  screen-off. Do not rebuild a session merely because Activity visibility
  changed. Retain `N=0` only in internal A/B tests and require a new versioned
  profile plus fresh evidence for any future queue-window value.
- [ ] Repeat tensor parity, first/reused invocation, finite output, setup and
  invocation failure, output validation, known-good cleanup and CPU fallback,
  fatal cleanup and process recycle, cancellation, and session close using the
  final release-candidate AAR. Prove that no fallback path silently creates an
  unbounded GPU session.
  The strict fake-session failure matrix and packaged capability smoke pass;
  one real 9662 bounded invocation now passes on both S10 and S25 with the
  pinned AAR. Repetitions and real fallback stages remain open.
- [ ] Test a clean install, missing-key default, app/process restart,
  source-separation backup and restore of both values, and an upstream backup
  with no key. With the switch off, prove zero GPU discovery, compile, probe,
  graphics-allocation, and event-wait attempts. With it on, prove successful
  bounded GPU and each typed one-way CPU fallback.
  Missing-key, both stored values, a real process restart, schema-v2 restore,
  and schema-v1 no-overwrite now pass. A disabled full inference run with
  native counters and all enabled fallback stages remains open.
- [ ] Toggle the setting during active, paused, foreground, background, and
  screen-off runs. Prove the admitted request and backend do not change and the
  new value is observed by the next run only.
  Request, IPC, journal, restart identity, and paused-run immutability pass in
  unit tests. Foreground/background/screen-off device toggles remain open.
- [ ] Repeat at least three no-Perfetto full-song pairs and three foreground
  Perfetto swipe intervals per phone with alternating `N=0` oracle/`N=1`
  candidate order. Freeze S10 and S25 responsiveness, throughput, first-ready,
  memory, thermal, and output thresholds before reviewing those final runs.
- [x] Attempt bounded GPU by default on every runtime-eligible device; do not
  ship a Samsung, device-model, GPU-vendor, or driver allowlist. Exercise Mali
  and non-Samsung devices when available to broaden regression coverage, but
  treat missing coverage as a documented evidence limit rather than a reason
  to force otherwise eligible devices to CPU.
  S10 and S25 pass the exact packaged capability check. No non-Samsung or Mali
  device was available for this checkpoint, so that coverage limit remains
  explicit.
- [x] Verify GitHub debug, release-like split APKs, and universal APKs use the
  identical pinned artifact and profile. Record a build-level rollback that
  returns an affected scope to CPU or a later GitHub build; do not implement a
  live downgrade from bounded to stock GPU.

**Phase 5 exit:** each ABI/backend has an evidence-backed support tier, release
host policy, concrete host execution, and session-lifetime candidate. Process
isolation can be accepted or rejected independently of the still-unimplemented
processing foreground service.

Phase 5E satisfies this policy checkpoint. The immutable runtime, ABI
inventory, exact capability, settings lifecycle, and 260-test GitHub debug
unit suite pass with the pinned artifact. See
`docs/validation/litert-inference-process/phase5/bounded-runtime-capability-2026-07-27.md`
and `bounded-9662-smoke-2026-07-27.md`, plus
`gpu-preference-lifecycle-2026-07-27.md`. The overall Phase 5 release
qualification remains open for repeated bounded-profile real-model/fallback
pairs and the foreground UI matrix. Those expensive matrices do not block
Phase 6 protocol and ownership implementation; they still block a final
release decision for the affected GPU scope.

## Phase 6: Prototype an Independent Media-Processing Service

This phase changes background ownership. It must remain behind a separate
internal gate from `BoundRemote`. Protocol, journal, and service ownership
implementation may proceed after exact identity and short real-model smokes
pass. Enabling an independent production host still requires the exact
`BoundRemote + SingleUse` CPU candidate, and separately the bounded-GPU
candidate where tested, to pass the corresponding host and lifecycle matrix.
The production host remains unchanged until this phase passes. Within each
Phase 6 comparison, host and session policy are fixed while background
ownership is varied. Any GPU case must also use the Phase 5F-pinned
`gpu-opencl-bounded-fp32-v1` runtime; stock `N=0` is not a background candidate.

### Phase 6A: Freeze run-class eligibility

- [x] Classify admitted work as manual full-song, playback-demand window, or
  bounded next-song prefetch in the protocol and journal.
- [x] Make only an explicitly user-started manual full-song run eligible for
  independent foreground execution in the first prototype.
- [x] Keep playback-demand work under `PlaybackService` ownership so it cannot
  outlive the playback intent it serves.
- [x] Keep next-song prefetch client-bound initially. Queue replacement,
  playback stop, or loss of a live main-process decision must prevent it from
  becoming an independently continuing job.
- [x] Keep model download, activation/deletion, and cache management outside
  the inference foreground service. Decide FLAC handoff separately rather than
  broadening the run implicitly.
- [x] Record the selected run-class/background policy in diagnostics and
  exclude the internal gate from backup.
- [x] Freeze the admitted GPU runtime profile and custom artifact identity in
  the protocol and durable journal together with `tryGpu` and the one-way
  fallback latch, so reattachment or restart cannot resume a run with stock or
  mismatched GPU code or reinterpret a later preference change.

Phase 6A is complete at protocol 8 and run-journal schema 5. The implementation
keeps user GPU intent, exact bounded-runtime identity, effective backend, and a
one-way fallback latch distinct. Unit tests prove that a latched run resumes on
CPU without losing its admitted identity and that duplicate/conflicting latch
events are handled strictly. Host and foreground-lifetime selection remains
internal and absent from backup. See
`docs/validation/litert-inference-process/phase6/eligibility-and-fallback-2026-07-27.md`.
Real-device fallback injection and the expensive full-song/UI matrices remain
release gates rather than Phase 6A implementation blockers.

### Phase 6B: Foreground-service ownership

- [x] Let the inference service declare only
  `foregroundServiceType="mediaProcessing"` where the platform supports that
  type, with explicit legacy behavior for API 26-34.
- [x] Keep `PlaybackService` responsible for `mediaPlayback`.
- [x] Define a handoff that never leaves active inference unprotected and never
  has two components claiming the processing lifetime indefinitely.
- [x] Call `startForeground()` within the platform deadline with a dedicated,
  user-comprehensible processing notification.
- [x] Provide Pause and Cancel notification actions with idempotent command
  IDs and exact run identity.
- [x] Do not keep an idle or manually paused process in foreground solely to
  retain a model session.
- [ ] Record both foreground services, types, notification IDs, start/stop
  timestamps, and ownership handoffs when playback and separation overlap.

The Phase 6B platform checkpoint began at protocol 9 and the exact product
handoff is complete at protocol 12. Eligible manual runs now use the production
MediaSession facade and `IndependentForeground` host. S10/API 31 and S25/API 35
CPU and bounded-GPU tests prove that `PlaybackService` keeps its processing
lease until the remote host accepts the same cache key, then drops only its
processing claim while playback remains active. Stale or unknown ownership
cannot release a newer or unrelated lease. Manifest and device tests also
prove the dedicated processing service, legacy and timed platform policies,
notification identity, pre-admission Pause/Cancel, duplicate command handling,
stale-action isolation, and notification removal on S25/API 35, S10/API 31,
and an API 26 x86 AVD. Active-run Pause cleanup on S10 and S25 for CPU and
bounded GPU additionally proves that no processing notification or FGS remains
after the durable Pause transition. The current API 37 x86_64 AVD terminated
instrumentation before running any test, so current-highest-API coverage
remains open. Complete overlap diagnostics and startup by tapping the visible
UI remain unchecked. See
`docs/validation/litert-inference-process/phase6/foreground-ownership-2026-07-27.md`
and `product-ownership-handoff-2026-07-27.md`.

### Phase 6C: Wake-lock ownership

- [x] Move the active-computation `PARTIAL_WAKE_LOCK` to the process actually
  executing inference.
- [x] Acquire it only after exact admission, durable journal creation, and FGS
  protection, immediately before heavy setup.
- [ ] Use bounded acquisition/renewal and release it on pause, cancel,
  completion, failure, timeout, cache loss, process teardown, and failed FGS
  promotion.
- [x] Record every acquire, renewal, and release in validation diagnostics.
- [x] Ensure the playback process does not retain a duplicate processing lock
  after a successful ownership handoff.

The Phase 6C implementation checkpoint began at protocol 10. An eligible
manual run now acquires a ten-minute, five-minute-renewed partial wake lock in
the inference process only after remote cache admission and FGS attachment.
Platform-lock loss requests a durable pause, stale identities cannot release a
new owner, and an unreleased orphan blocks the next run. Process diagnostics
and Phase 7 JSON reports retain the complete event history. S25 device smoke
proves a pending lease does not acquire early and Pause leaves no lock. Actual
full-song acquisition and completion release pass on S25. At protocol 12, exact
product handoff also passes on S10 and S25 with CPU and bounded GPU: the
playback lock remains held before remote acceptance, is absent after the exact
handoff while the inference lock is held, and both are absent after completion.
A real renewal and naturally delivered Android FGS timeout cleanup remain
unchecked. See
`docs/validation/litert-inference-process/phase6/wake-lock-contract-2026-07-27.md`
and `product-ownership-handoff-2026-07-27.md`.

### Phase 6D: Primary platform prototype

- [x] Implement and validate the first manual full-song prototype on S25
  arm64 CPU. Keep GPU, x86, and session-residency changes out of this first
  lifecycle proof.
- [ ] Start from a visible user command, background the UI, stop playback, and
  turn the screen off while requiring journal progress and one bounded wake
  lock.
- [x] Handle `ForegroundServiceStartNotAllowedException` and every denied
  promotion as a typed paused/deferred outcome, never a retry loop.
- [x] Implement and test the injectable `Service.onTimeout()` path for Android
  15+ `mediaProcessing`; leave natural exhaustion of the approximately six-hour
  per-24-hour background allowance as a long-running platform test.
- [x] Stop promptly after an injected timeout while preserving the last
  committed window and releasing notification, foreground state, lock, and
  native session.
- [ ] Test API 35, API 36, and the current highest emulator API before
  expanding to older devices. Record cumulative media-processing FGS budget
  for long and repeated runs.
- [ ] After CPU lifecycle semantics pass, repeat the prototype on both S25 and
  S10 with `tryGpu=true` and the Phase 5F bounded-GPU policy. Verify graphics
  memory, queue-wait diagnostics, output, one-way CPU fallback, and process
  recycle on fatal cleanup. UI measurements inform runtime fixes and user
  guidance; they must not create a foreground-only CPU policy.
- [x] Repeat the lifecycle prototype with `tryGpu=false` and prove that
  foreground-service handoff, backgrounding, and screen-off never cause a GPU
  discovery or allocation attempt.
- [ ] While bounded GPU runs in the processing service, repeat foreground app
  swipes and FrameTimeline/fence attribution, then background and screen-off
  throughput. Process isolation does not remove shared physical-GPU
  contention, so a remote host may not inherit the in-process UI result.
- [ ] Keep `N=1` for the whole admitted run. Do not transition between bounded
  and unbounded GPU, or recreate GPU solely on foreground/background changes.
- [ ] During this phase, treat main-process Binder death as a controlled durable
  pause. Continuing without the main process is enabled only after Phase 7
  transfers active-run authority and passes its recovery gates.

The first Phase 6D CPU lifecycle proof passed on S25/API 35 at protocol 12. A
273.699-second 9662 FP32 run reached its first ready horizon in 7.131 seconds,
continued after HOME and screen-off, and completed in 241.588 seconds while the
device remained non-interactive. Host events and journal sequence advanced
while the screen was off. The remote process alone owned the journal, FGS, and
wake lock; both leases ended with `completed`, and the exact completed cache
was playable. The report also proves `tryGpu=false` admitted CPU directly and
made no GPU allocation attempt. See
`docs/validation/litert-inference-process/phase6/primary-platform-prototype-2026-07-28.md`.
The production MediaSession route and exact `PlaybackService` overlap handoff
subsequently passed with the 12-second fixture on S10 and S25 for both CPU and
the pinned bounded GPU profile. These short product-path tests prove routing,
exact ownership, cache completion, and terminal lease release; they do not
substitute for the longer screen-off, fallback, memory, thermal, or UI matrices.
Visible UI-tap startup, a naturally exhausted Android quota,
API 36/current-highest coverage, S10 long-song screen-off, and bounded-GPU
long-song/UI lifecycle coverage remain open.

**Phase 6 exit:** one exact, user-started manual full-song run can remain
protected on the primary arm64 target with playback stopped and the screen off,
without relying on `PlaybackService`'s processing lease. Playback-demand and
prefetch work remain client-bound, and main-process death continuation is not
yet enabled. CPU lifecycle proof and GPU-profile proof are promoted separately.
Any GPU evidence uses the pinned bounded profile, preserves admitted `tryGpu`
across every app state, and cannot be inferred from CPU-only success.

## Phase 7: Support Independent Process Lifetime and Recovery

### Phase 7A: Transfer active-run authority

- [x] Once an eligible manual full-song run has a durable journal, exact-entry
  kernel lock, and acknowledged processing FGS, make the inference service
  authoritative for that exact admitted run until a durable terminal
  transition.
- [x] Keep the main coordinator as a proxy and observer rather than a second
  in-memory worker.
- [x] Let a reconnecting main process query and adopt the current snapshot.
- [x] Do not let callback loss cancel an otherwise valid foreground run, and
  persist the exact observer disconnection before accepting a replacement.
- [x] Bound an otherwise valid foreground run to 5 hours 45 minutes after its
  observer disconnects. Exact reconnection or terminal close cancels the
  deadline; expiry requests Pause through the existing durable cache and
  FGS/wake-lock cleanup path. Android may impose an earlier platform timeout.
- [x] Do not let the inference process admit a new song after completing the
  frozen request without a live main-process decision.
- [x] Keep playback-demand and prefetch authority in the main/playback process;
  they must not inherit manual full-song survival semantics accidentally.

The deterministic S10/S25 CPU and bounded-GPU observer-loss matrix is recorded
in
[Phase 7 observer reattachment](validation/litert-inference-process/phase7/observer-reattachment-2026-07-27.md).
It proves callback-loss continuation, journal-based discovery, snapshot
adoption, UI/cache ownership reconstruction, terminal callback delivery, and
the absence of a second `start()`. It deliberately does not stand in for an
actual Android main-process kill.

### Phase 7B: Bounded restart policy

- [x] Compare sticky restart, redelivered intent, and explicit durable-journal
  re-admission. Select `START_NOT_STICKY`, an automatic retry budget of zero,
  and explicit user retry as the only recovery mechanism. Do not combine an
  Android restart mode with an application retry loop.
- [x] After unexpected inference-process loss, keep the remote owner's
  nonterminal `Running` journal and partial cache unchanged. Surface a stable,
  localized `Failed` UI state, release confirmed-dead foreground resources,
  and never relaunch the process automatically.
- [x] Treat explicit retry as a new process/execution generation. Require the
  old process incarnation to be dead, release its kernel lock, reacquire the
  exact cache entry, and append typed old-owner death/disconnection plus new
  admission transitions before writing another segment.
- [x] Use the same no-automatic-retry outcome for system/LMKD loss, native
  fatal state, and otherwise unexpected Binder death. User Pause/Cancel,
  force-stop, FGS timeout, protocol incompatibility, model loss, and cache
  invalidation retain their existing typed terminal or re-admission behavior;
  none authorizes process resurrection.
- [x] Preserve the original admitted `tryGpu` value across explicit retry.
  Recreate only the exact bounded profile when GPU was admitted, or remain on
  CPU when CPU was admitted or the durable CPU fallback latch was already set.
  Never substitute stock `N=0` GPU.
- [x] Define cache invalidation as deletion of the abandoned manifest, journal,
  committed segments, and frozen backend policy. Only a later explicit user
  start may create a fresh sequence-1 run from zero with the then-current
  `tryGpu` value. The coordinator contract is unit-tested, and both
  GPU-to-CPU and CPU-to-bounded-GPU directions pass on S25.
- [x] Verify a retired bounded-runtime artifact identity at the pending
  explicit-retry boundary. S10 rejects it before launching a new inference
  process, preserves the abandoned journal and frozen `tryGpu`, localizes the
  TFLite load failure, and remains terminal across ordinary playback position
  updates. See
  [Phase 7 explicit-retry runtime policy](validation/litert-inference-process/phase7/explicit-retry-runtime-policy-2026-07-29.md).
- [ ] Repeat the artifact-mismatch case as a real two-APK update: create the
  durable run with the old APK, use `adb install -r` to install the new APK
  without clearing data, and then perform the explicit retry. The deterministic
  retired-identity test does not claim this Android package-update boundary.
- [x] Verify model loss at explicit retry. S10 reports typed
  `ModelNotInstalled` without a remote-process start or cache mutation,
  restores the identical TFLite weight without auto-resume, and resumes the
  preserved cache only after another explicit user start. See
  [Phase 7 explicit retry after model loss](validation/litert-inference-process/phase7/explicit-retry-model-loss-2026-07-29.md).
- [x] Verify an already-latched GPU-to-CPU fallback at explicit retry. S10
  creates a direct `LiteRtCpu` session in the new generation while preserving
  the original admitted `tryGpu=true`, bounded-GPU identity, and durable latch.
  The resumed session has no new fallback stage or reason. See
  [Phase 7 explicit retry with a latched CPU fallback](validation/litert-inference-process/phase7/explicit-retry-latched-fallback-2026-07-29.md).

The selected policy and the first-committed-segment S10/S25 matrix are recorded
in
[Phase 7 remote-process death and explicit retry](validation/litert-inference-process/phase7/remote-process-death-2026-07-28.md).

### Phase 7C: User and task lifecycle

- [x] Preserve the existing "stop when closed from recents" setting as a
  playback setting. Swiping the task with the setting enabled still stops
  playback; it does not reinterpret an already admitted manual full-song job
  as playback-owned work. Real task removal passes with the setting enabled and
  disabled on S10 CPU and S25 CPU/bounded GPU. Playback follows the setting in
  every row while the already admitted manual run continues and advances its
  durable journal.
- [x] Define and unit-test behavior when playback stops while an eligible manual
  full-song run continues. Playback-demand and prefetch work must stop or pause
  with their owning playback intent. `PlaybackService` shutdown now freezes
  the playback clock, clears queued work, and pauses only those two classes;
  the worker exits after an independently admitted manual run completes. Real
  service teardown passes for playback demand and next-song prefetch on S10
  and S25 with CPU and bounded GPU. Every row reaches durable `Paused`, leaves
  an incomplete cache, releases its cache/kernel ownership and playback
  resources, rejects stale admission, and never creates the independent
  inference FGS.
- [x] A manually paused production session retains no private warm binding.
  The terminal path releases the processing FGS and wake lock, and normal
  builds use `SingleUse` native sessions. The resident x86/arm32 validation
  gate may retain only a successfully completed run, never Pause, Cancel,
  Deferred, Failed, or an unknown outcome. Active-run Pause cleanup passes on
  S10 and S25 for CPU and bounded GPU: ownership, notification, FGS, wake lock,
  binding, and native session are all released while the cache remains
  durably `Incomplete`.
- [x] Active-run user Cancel passes on S10 and S25 for CPU and bounded GPU.
  Require a final `UserCanceled` journal transition, `Canceled` journal and
  manifest state, a non-completed cache, released exact-entry lease, and no
  retained owner, notification, FGS, wake lock, binding, or native session.
- [x] An expired idle-retention deadline merely releases the private binding;
  idle memory pressure is left to Android. Do not add automatic self-recycle,
  sticky service lifetime, or a second restart mechanism. Explicit
  acknowledged recycle remains available only when a new run requires a fresh
  process generation.
- [x] Keep completed-stem FLAC promotion in the main process initially. The
  remote owner publishes a valid completed WAV cache without it; after
  reattachment, the existing deferred terminal callback may request promotion.
  Promotion must not extend the inference FGS or wake-lock lifetime.
- [x] Restore worker progress, protected-cache ownership, Pause/Cancel routing,
  and deferred terminal callbacks from an adopted snapshot and event stream.
- [x] Recreate the product `MainActivity`, reconnect the worker observer before
  the debug verifier obtains the coordinator, restore protected-cache
  ownership, and publish a valid completed cache after main-process death.
  This passes both after durable `SegmentRunning` with no committed segment and
  after segment 0 is durably committed; the latter preserves the exact two
  stem paths, sizes, and SHA-256 values through completion.
- [ ] Verify active playback readiness, notification rendering, and cache
  management UI after main-process recreation.
  The S10 CPU and bounded-GPU product-state projections now pass after the
  first committed segment: the processing notification retains its localized
  content and Pause/Cancel actions, position zero is playable with one ready
  window, and cache-management state exposes the exact 9662 entry as `Partial`
  with its installed model identity. See
  [Phase 7 main-process product state](validation/litert-inference-process/phase7/main-process-product-state-2026-07-29.md).
  S10 Compose instrumentation also renders the exact partial-cache metadata,
  routes its Delete action by cache key, and routes an installed preset's Use
  action by model ID. Separate live S10 transactions now perform real preset
  download/activation and exact cache deletion through the production
  ViewModels. Navigation from the recreated product surface and active
  original-audio continuity remain open, so this item is not yet complete.
- [x] Confirm on S10/API 31 and S25/API 35 that Android force-stop terminates an
  active manual CPU or bounded-GPU run without automatic resurrection. Accept a stale
  `Running` journal, but require every process to exit, package `stopped=true`,
  no processing service/notification/wake lock, a stable journal and complete
  entry digest throughout 30 seconds, and no reconnectable run after explicit
  restart.
- [ ] Repeat the force-stop gate on the eventual highest-API release device.
  Do not generalize the Samsung results into an all-platform claim.

The selected Phase 7C product policy and its remaining device-only gates are
recorded in
[Phase 7 user and task lifecycle](validation/litert-inference-process/phase7/user-task-lifecycle-2026-07-28.md).
One S10 bounded-GPU prefetch attempt stalled before its first committed segment
while a 12-second repeat-one playback fixture churned; two cold repetitions and
a long-current-song control passed. The gate now retains full failure state.
No source decode, MP3 fallback, window, overlap, join, or product scheduler
policy changed in response.

### Phase 7D: Recovery and reattachment matrix

- [x] Detach the original observer after the first durable progress event,
  adopt the same run from a second observer, and complete without a second
  writer or `start()` on S10 and S25 for CPU and bounded GPU.
- [ ] Kill and restart the main process before FGS handoff, during native
  invocation, and after final segment publication.
- [x] Kill the main process during terminal journal commit, after the completed
  manifest and all 48 segment pairs are durable but before the journal changes
  from sequence 99 `Running` to sequence 100 `Completed`. S10 CPU and bounded
  GPU retain the original remote PID/generation/run, issue no second start,
  recreate no terminal processing notification, and expose an idle worker and
  playable `Completed 48/48` cache. See
  [Phase 7 terminal-commit main-process death](validation/litert-inference-process/phase7/main-process-terminal-commit-2026-07-29.md).
- [x] Kill the main process after durable `SegmentRunning` and before the first
  committed segment, then let the real `MainActivity` adopt and complete the
  same run on S10 and S25 for CPU and bounded GPU. See
  [Phase 7 main-process reattachment](validation/litert-inference-process/phase7/main-process-reattachment-2026-07-28.md).
- [x] Kill the main process after the first committed segment, then require the
  replacement product observer to preserve that segment's exact path, size,
  and SHA-256 evidence while the original remote run completes all 48 segments.
  This passes on S10 and S25 for CPU and bounded GPU without a second start.
- [x] Require a reconnecting client to reject stale Binder generations and
  reconstruct UI state from the durable snapshot without seeking or replacing
  original playback. Each binding now owns a distinct callback and reconnect
  buffer; deterministic tests reject retired callbacks before decoding or
  delivery and reset event identity for the replacement generation. S10
  instrumentation restores a different song's separation state while keeping
  the current playback song, position, duration, playing state, and blend
  unchanged, with no second runtime start. See
  [Phase 7 binding generation and UI reconstruction](validation/litert-inference-process/phase7/binding-generation-ui-reconstruction-2026-07-29.md).
- [ ] Kill the inference process at the same boundaries and verify the selected
  no-automatic-retry policy never duplicates a writer, segment, notification,
  wake lock, or native session.
- [x] Kill the inference process after the first committed segment on S10 and
  S25 for CPU and bounded GPU. Require 30 seconds without automatic relaunch,
  an unchanged `Running` journal and cache digest, a stable failed UI state,
  released old resources, and explicit user retry into a new generation that
  preserves segment 0 and completes all 48 segments. See
  [Phase 7 remote-process death and explicit retry](validation/litert-inference-process/phase7/remote-process-death-2026-07-28.md).
- [ ] For GPU, include death during an `N=1` event wait, after the final queued
  command, during output read, and during bounded-session close. Require the
  next generation to prove the custom capability before any GPU recreation.
  A journal with a persisted CPU fallback latch must not retry GPU.
- [x] Run user Pause against an active manual full-song run on S10 and S25 with
  CPU and bounded GPU. Require durable `Paused`, an `Incomplete` cache, released
  ownership, and no retained notification, FGS, wake lock, binding, or native
  session. See
  [Phase 7 user and task lifecycle](validation/litert-inference-process/phase7/user-task-lifecycle-2026-07-28.md).
- [x] Run force-stop against active manual CPU and bounded-GPU work on S10/API
  31 and S25/API 35. Prove continuous no-resurrection evidence, stable journal
  and entry bytes, and an empty discovery-only remote after explicit restart.
  See the same Phase 7 user and task lifecycle report.
- [x] Run real recents removal with both recents-policy values while playback
  overlaps an independent manual run. Cover `tryGpu=false` on S10 and S25 and
  bounded `tryGpu=true` on S25. Require task disappearance, the selected
  PlaybackService outcome, continued journal progress, and terminal resource
  cleanup.
- [x] Run user Cancel against active manual CPU and bounded-GPU work on S10 and
  S25. Prove durable canceled identity and complete release of processing and
  cache resources. See the same Phase 7 user and task lifecycle report.
- [x] Stop the real PlaybackService while it owns playback-demand or prefetch
  work on S10 and S25 with CPU and bounded GPU. Require durable Pause,
  incomplete-cache retention, complete owner/resource release, rejection of
  stale playback admission, and no independent inference FGS.
- [x] Clear the cache against pending explicit-retry state on S25 in both
  backend-policy directions. Require removal of the old journal and frozen
  policy, no automatic resurrection, and a later explicit sequence-1 start
  from zero with the current `tryGpu`. See
  [Phase 7 remote-death cache invalidation](validation/litert-inference-process/phase7/remote-process-death-cache-clear-2026-07-29.md).
- [x] Run model switching and deletion against pending explicit-retry state on
  S25. Deleting active 9662 remains blocked. After selecting KARA and deleting
  the inactive 9662 weights, the old partial cache becomes
  `Stale/ModelNotInstalled`; a later explicit start uses KARA's exact identity,
  a different cache key, and a sequence-1 journal without an old-owner
  transition. Pause releases the new run, and exact 9662 restoration does not
  resume the abandoned generation. See
  [Phase 7 remote-death model switching](validation/litert-inference-process/phase7/remote-process-death-model-switch-2026-07-29.md).
- [x] Repeat the focused cache-clear and model-switch paths on S10 and exercise
  the visible preset- and cache-management actions through their production
  ViewModels. Neither path resurrects an old generation. Navigation from a
  recreated `MainActivity` remains part of the broader main-process boundary
  matrix. See
  [Phase 7 main-process product state](validation/litert-inference-process/phase7/main-process-product-state-2026-07-29.md),
  [cache invalidation](validation/litert-inference-process/phase7/remote-process-death-cache-clear-2026-07-29.md),
  and [model switching](validation/litert-inference-process/phase7/remote-process-death-model-switch-2026-07-29.md).
- [x] Change `tryGpu` after inference-process death and prove explicit retry
  still uses the original admitted value. This passes with false-to-true CPU
  and true-to-false bounded GPU changes on both S10 and S25.
- [x] Commit compact reports that separate independent continuation,
  no-automatic-restart failure, and explicit user retry; a single generic
  "recovered" result is insufficient.

**Phase 7 exit:** an admitted run may survive main-process death, or the feature
is rejected with a documented reason. Only the explicitly selected run classes
may continue. Recovery is bounded, cache-safe, visible, and incapable of
creating duplicate ownership. CPU and bounded-GPU recovery are qualified
separately; CPU success cannot promote GPU recovery, and neither app-state
changes nor a later preference value may alter the admitted backend policy.

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
- sustained foreground interaction while an admitted bounded-GPU run executes
  in each candidate host;
- foreground/background/screen transitions and a live Runtime Management
  backend-preference change while both a GPU-admitted run and a CPU-only
  admitted run are active;
- model switch and x86 process recycle; and
- media-processing foreground-service timeout.

Manual full-song continuation, playback-demand work, and next-song prefetch
must be reported separately. A passing manual run cannot be used to claim that
prefetch may outlive playback.

### Phase 8B: Devices

- [ ] Run the complete scenario, notification, recents, screen-off, and Samsung
  battery-policy matrix on Galaxy S10 arm64 and Galaxy S25 arm64.
- [ ] Repeat the final bounded-GPU FrameTimeline/fence and full-song smoke on at
  least one non-Samsung Adreno device and one Mali device when available.
  Record this as driver-diversity coverage and use failures to improve dynamic
  eligibility, fallback, or the bounded runtime. Missing coverage must be
  disclosed, but must not create a static allowlist or force an otherwise
  runtime-eligible device to CPU.
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
- [ ] For every GPU case, record requested/observed `N`, event-wait count and
  duration, longest wait, app FrameTimeline distribution, GPU-completion fence
  overlap, and proof of the pinned custom accelerator. Use stock `N=0` only as
  an internal paired oracle.
- [ ] Record persisted GPU/NPU intent, the admitted backend-policy snapshot,
  each dynamic eligibility stage, actual backend, fallback latch/reason, and
  app/screen-state transitions. Prove no transition changes the admitted
  backend and no runtime failure rewrites user intent.
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
- [ ] Publish the exact bounded GPU profile and custom runtime release in every
  GPU row, plus the dynamic eligibility and one-way fallback contract. Device
  evidence describes tested coverage; it must not become an OEM/GPU allowlist.
- [ ] Reject independent background without rejecting an otherwise qualified
  `BoundRemote` process-isolation policy.
- [ ] Reject one ABI/backend/model scope without weakening another row's
  frozen gates.

**Phase 8 exit:** background execution is promoted only if it preserves
correctness and playback, has bounded recovery, meets the Phase 0 memory and
performance thresholds, and behaves acceptably on both Samsung generations
and modern Android FGS policy. Promotion is per run class and support tier, not
an all-or-nothing app-wide switch. All dynamically eligible devices still
default to the same bounded GPU attempt; vendor-diverse testing measures the
strength of that claim and guides fixes, fallback diagnostics, and user-facing
limitations rather than selecting devices through a static list.

## Phase 9: Select Production Policy and Remove Transitional Ownership

### Phase 9A: Policy decision

- [ ] Publish an evidence table for each ABI/backend/model-resource scope that
  selects support tier, release host policy, concrete host execution, session
  lifetime, and background owner for manual full-song, playback-demand, and
  prefetch work.
- [ ] For every GPU-eligible ABI/model row, select only
  `gpu-opencl-bounded-fp32-v1`, its exact `bss-litert-android` GitHub Release,
  dynamic runtime eligibility, and one-way CPU fallback contract. Stock `N=0`
  remains internal, and no device/GPU/driver allowlist may be emitted.
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
- [ ] Verify release workflows, dependency resolution, and user documentation
  implement the already selected GitHub-only channel. F-Droid or Maven
  publication work, if resumed later, is a separate release track and cannot
  silently replace the qualified native artifact in an existing GitHub build.

### Phase 9B: Production cleanup

User-facing runtime delivery, Quick Setup, Runtime Management, and backend
preferences were transferred to the dedicated runtime setup roadmap when this
document was frozen. This historical phase retains only process/background
cleanup concerns.

- [ ] Remove duplicate worker, foreground-service, wake-lock, and cache-lease
  ownership from the nonselected path.
- [ ] Keep a test oracle for in-process correctness if production becomes
  remote, but do not expose automatic runtime fallback between hosts.
- [ ] Remove internal process-mode controls from release UI. User-visible
  labels may describe experimental support or resource rejection, but users do
  not select an unsafe host/session combination manually.
- [ ] Keep internal host/session controls out of release UI. Verify that the
  Runtime Management backend preferences remain distinct from process-host
  policy and that an admitted run snapshots them rather than observing live UI
  changes.
- [ ] Update compatibility catalog evidence only after device reports are
  committed.
- [ ] Update process/background documentation, release notes, and user-visible
  unsupported-process-policy messages without duplicating runtime setup UI.
- [ ] Keep explicit internal stock and `N=0` oracles for tests, but ensure no
  production admission path resolves them.
- [ ] Verify ABI split APK and universal APK service inventory. Native runtime
  delivery and inventory are release gates in the dedicated runtime setup
  roadmap.

### Phase 9C: Release gate

- [ ] Repeat clean-install runtime/model acquisition, selection, separation,
  playback, cache management, backup/restore, and clear-cache recovery through
  the dedicated runtime setup contract. Verify persisted GPU/NPU intent,
  selective-category restore, and older or upstream backup behavior.
- [ ] Repeat full-song and every enabled run-class/background smoke on each
  production-enabled ABI/backend/model scope, including the minimum tested
  memory configuration for a resource-limited tier.
- [ ] With admitted GPU intent enabled, repeat foreground UI interaction for
  every GPU-eligible ABI/model test row and prove the selected component reports
  `N=1`; a mismatched capability must take the CPU path and must never run stock
  GPU. With admitted GPU intent disabled, prove there is no GPU allocation
  attempt.
- [ ] Verify no service, binder callback, session, file lock, notification, or
  wake lock remains after completion or cancellation.
- [ ] Record the final app commit, protocol version, process policy, model
  hashes, bounded GPU Release/AAR/ELF hashes and capability version, observed
  `N=1`, x86 runtime artifact where applicable, support tier, and device/API
  evidence coverage.
- [ ] Build and install the final GitHub split and universal APKs from a clean
  runner, verify their checksums and process/service inventory, and repeat a
  clean-device acquisition/separation smoke using only runtime assets admitted
  by the dedicated runtime setup roadmap.
- [ ] Verify unsupported ABI/model combinations fail before native allocation,
  and runtime-ineligible GPU attempts take the typed CPU path without changing
  the persistent setting or loading stock GPU.

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
- LiteRT GitHub release tag, input and output AAR SHA-256, transformation
  manifest, bounded capability version, shim and per-ABI ELF identities when a
  custom packaged runtime is under qualification;
- concrete CPU/GPU backend and fallback;
- persisted and admitted `tryGpu` values, dynamic eligibility result, and
  one-way fallback-latch state;
- requested and observed GPU queue window, event-wait count/duration, and proof
  that no stock or duplicate GPU runtime was loaded;
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
| Custom LiteRT runtime drifts from its pinned inputs | immutable GitHub release, deterministic transformation manifest, AAR/ELF/hash verification, provenance, and CI smoke |
| Wrong or stock AAR silently restores unbounded GPU | native capability/build-ID admission, dependency graph audit, APK inventory, and CPU-only fallback on mismatch |
| Binary transformation targets the wrong native layout | exact upstream AAR hash, patch preconditions, post-transform disassembly/ELF checks, and connected `N=1` wait-count smoke |
| `N=1` behaves differently on another GPU driver | dynamic discovery/compile/probe/output validation, typed one-way CPU fallback, user CPU-only control, and vendor-diverse regression coverage without a static allowlist |
| A valid GPU still causes unacceptable UI contention | keep one `N=1` policy across app states, publish measured limitations, let the user disable GPU for later runs, and improve or replace the runtime rather than switching on visibility |
| GPU preference changes during a run or restore | freeze it in request/journal state, version and allowlist the backup key, and test missing-key plus selective-restore behavior |
| Two foreground owners drift | explicit ownership handoff and one active processing lease |
| Screen-off stalls | inference-owned bounded partial wake lock |
| FGS start is denied | typed deferred state, no retry loop |
| Android 15+ FGS timeout | `onTimeout()`, durable pause, prompt stop |
| Low-memory restart thrash | retry budget, backoff, and terminal defer |
| GPU driver differs out of process | complete S10/S25 probe, fallback, and full-song rerun |
| Playback loses CPU priority | bounded inference threads and underrun/thermal gates |
| Source output changes during migration | frozen decode routes and Phase 7 fixture parity |
| Debug host mode leaks into backup | internal-only setting excluded from backup |

## Frozen Process and GPU Execution Decisions

Qualification may reject an artifact or delay release, but implementation must
not replace these decisions with a device allowlist, visibility heuristic, or
stock GPU fallback:

- Ship the near-term product through GitHub only; do not block this roadmap on
  F-Droid, Maven Central, or public ML Drift source availability.
- Admit only immutable, checksum-pinned `bss-litert-android` component
  identities produced by the audited workflow. Whether a component is packaged
  or downloaded is governed by the dedicated runtime setup roadmap.
- Use `gpu-opencl-bounded-fp32-v1` with `N=1` as the only production-candidate
  GPU profile. Keep stock `N=0` as an internal oracle only.
- Keep `N=1` fixed across foreground, background, and screen-off execution.
  Do not add visibility-driven GPU session recreation.
- Runtime Management owns persistent GPU/NPU user intent, and recommended Quick
  Setup defaults qualified GPU components to enabled. The source-separation
  Advanced section does not duplicate these controls.
- Freeze the versioned backend-policy snapshot when a run is admitted. Enabled
  GPU intent attempts bounded GPU when no higher-priority exact NPU path is
  selected and dynamic eligibility passes; disabled GPU intent performs no GPU
  allocation attempt.
- Determine eligibility at runtime from the verified installed capability,
  ABI/model profile, accelerator setup/probe, and valid output. Do not ship an
  OEM, device-model, GPU-vendor, or driver allowlist for the generic GPU path.
- If the bounded capability is absent or mismatched, select the known-good CPU
  path; never silently run unbounded GPU.

## Provisional Process Decisions

These process and lifecycle decisions remain subject to their phase gates:

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
- Release the private binding and native session after a manual Pause. An idle
  cached process may remain at Android's discretion, but it owns no run, FGS,
  wake lock, or LiteRT session.
- Allow Phase 5 to accept process isolation independently of whether later
  independent background execution is accepted.
- Treat all ABI process placement as evidence-driven.
- Never modify current window-decoding policy as part of process migration.

## Decisions That Must Remain Open Until Tested

- Whether any future GPU session should remain resident after a run; the
  current bounded profile remains `SingleUse`.
- Whether pure x86 can pass a useful minimum-memory scope repeatedly enough for
  `Experimental` or `Supported`, given emulator-only device evidence.
- Whether unknown imported models may be activated on x86 and, if so, what
  explicit unverified-resource flow contains their failure.
- Whether enabled GPU intent with `gpu-opencl-bounded-fp32-v1` remains stable in
  the processing service on S10, S25, and available non-Samsung/Mali coverage
  after final downloadable-component integration.
- Which conservative memory floor and probe cadence best contain repeated
  driver failures without creating a persistent blacklist or changing the
  recommended default.
- Whether repeated final-AAR S10 traces justify further bounded-runtime work or
  user-facing performance guidance. They must not create CPU-while-visible or
  foreground/background backend switching.
- Whether a later public-source LiteRT release can replace the deterministic
  binary transformation without changing the bounded profile's behavior. This
  is a maintenance opportunity, not a GitHub release gate.
- How a real app update should be presented at explicit-retry time across API
  26 through target 36. The zero-automatic-retry policy itself is frozen;
  deterministic runtime mismatch, missing-model, and latched-CPU behavior are
  implemented and covered on S10.
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
   are reflected in catalog and release documentation; and
9. Runtime Management GPU intent persists and restores correctly, recommended
   setup enables a qualified component, and the admitted backend-policy
   snapshot remains frozen; every dynamically eligible GPU run then uses the
   checksum-pinned bounded component and attests `N=1`, while disabled or
   ineligible runs allocate no GPU or route one-way to CPU rather than stock
   GPU; and
10. no foreground, background, Activity-visibility, or screen-state transition
    changes the admitted backend policy.
