# Downloadable Runtime, Quick Setup, and Local Resource Management Roadmap

Status: active product and implementation plan. The product and data contracts
in this document are frozen; phase checklists may be refined only without
silently changing those contracts.

Updated: 2026-08-14

## Current Model-Delivery Baseline (2026-08-07)

`WluhWluh/bss-tflite` now publishes the immutable prerelease
[`v0.2.0-experimental.1`](https://github.com/WluhWluh/bss-tflite/releases/tag/v0.2.0-experimental.1).
It contains 33 selectable experimental models: 30 MDX two-stem artifacts and
three CPU-only HTDemucs multi-stem artifacts. The authoritative download index
is `model-catalog-v3.json`; each artifact is paired by SHA-256 with its
exact-name sidecar. `UVR_MDXNET_3_9662` remains the Quick Setup default, but it
is experimental rather than stable.

All subsequent runtime/model acquisition tests in this roadmap must start from
a clean app-data state and use the real GitHub Release path:

1. fetch the immutable v3 catalog from the Release;
2. select a catalog entry and fetch its artifact plus exact-name sidecar;
3. verify catalog SHA-256, sidecar SHA-256, artifact SHA-256, contract schema,
   and pipeline/backend policy;
4. install the model through the production `ModelDeliveryProvider`; and
5. perform explicit model selection before separation.

Direct local staging is reserved for loader diagnostics and must not be used as
the primary evidence path for Quick Setup, Runtime Management, model download,
or product separation tests. Demucs entries remain CPU-only and must enter the
multi-stem pipeline, cache, and playback gates before any GPU/NPU policy can
apply.

This document is authoritative for:

- downloadable LiteRT CPU and GPU components;
- optional vendor NPU runtimes, model-specific AOT variants, and QNN JIT;
- local runtime inventory, installation, removal, repair, and diagnostics;
- the persistent GPU and NPU enablement policy;
- end-to-end local-separation readiness;
- the Quick Setup product flow; and
- the boundary between Runtime Management and Model Management.

The frozen
[LiteRT Inference Process and Background Execution Roadmap](litert-inference-process-roadmap.md)
remains historical evidence for process placement, process death, background
ownership, and bounded-GPU behavior. It is no longer an active location for
runtime-acquisition or user-facing backend planning. The
[LiteRT and Multi-Preset Roadmap](litert-multi-preset-roadmap.md) remains
authoritative for model tiers, catalog publication, and release qualification.
The
[Source-Separation Contract and Multi-Stem Data-Plane Roadmap](source-separation-multistem-contract-playback-roadmap.md)
owns stem/model data shape and playback transport, while the lifecycle/cache
roadmap owns active-model handoff, exact-cache admission, retention, and
deletion.

If older roadmap text conflicts with this document about runtime packaging,
the location of backend settings, automatic setup, AOT/JIT ownership, or
runtime persistence, this document supersedes it. Completed validation records
remain immutable historical evidence and retain the field names used by their
test harnesses.

## Current Cross-Roadmap Status (2026-08-08)

- Phases 0-4 are implemented on the downloadable-runtime branch: the
  classes-only LiteRT API, app-owned CPU/GPU component store, explicit loader,
  Runtime Management, Quick Setup, persistent GPU intent, and bounded `N=1`
  fallback path are present. The S10/S25 downloaded-path functional matrix is
  now exercised through the Debug product providers; the final performance,
  thermal, power, and release-support baseline remains qualification work.
- The active release catalog contains 33 selectable experimental candidates.
  9662 FP32 remains the Quick Setup default; all other MDX candidates and the
  three HTDemucs candidates require explicit user selection. KARA's arm64 bounded
  GPU profile is experimental rather than stable; HQ4 remains heavily
  resource-gated and is not a stable or recommended path. This product policy
  is governed by the multi-preset roadmap, not by runtime availability alone.
- The lifecycle/cache correctness implementation and four-ABI lifecycle smoke
  are complete. The remaining short human handoff-listening check and final
  downloaded CPU/GPU baseline remain pre-NPU release-confidence gates.
- The three HTDemucs artifacts now have release contracts and are selectable
  CPU-only experimental catalog entries. Their product full-song, cache,
  playback, lifecycle, and same-weight human-listening gates pass. A separate
  representative-content PCM16 attribution item remains open after an
  inaudible blind sample exceeded the frozen one-LSB objective bound. Their
  partial GPU experiments and failed QNN attempt do not create GPU/NPU support
  rows.
- S10 was re-tested on the real downloaded path as an explicit
  `armeabi-v7a` row. Official HTDemucs 4/6 CPU execution, 6-stem FLAC
  promotion/playback, model-family activation, active deletion, process death,
  app force-stop/manual resume, and resource cleanup passed. A 32-bit
  HTDemucs terminal run now recycles the dedicated inference process before a
  later run; this is an experimental CPU lifecycle rule, not a GPU or NPU
  qualification and not a generalized all-ABI claim.

The next implementation priority is to close the narrow multi-stem PCM16
attribution item, then finish the downloaded CPU/GPU product baseline. Vendor
NPU work stays scoped to an already qualified MDX model and a named
device/runtime tuple.

## Direction

Booming SS will ship a small classes-only LiteRT API surface in the APK and
install native runtime components from immutable, checksum-pinned GitHub
Release assets. Local music playback must remain usable without any separation
runtime or model installed.

Initial source-separation setup should require one short, device-specific
review followed by one explicit install action. Advanced users must later be
able to inspect, install, update, disable, and remove local components without
losing unrelated models or caches.

Runtime installation, model installation, and selection of the model used for
new work are independent operations. Quick Setup may coordinate them in one
transaction, but it must preserve those independent repository operations and
must not teach ordinary downloads to activate resources implicitly.

The current implementation and release target is GitHub only. It must not add
Play Core, Dynamic Feature, AI Pack, or F-Droid-specific build dependencies.
The domain and UI must nevertheless depend on delivery abstractions rather
than HTTP or GitHub classes so a later reduced-capability Play or F-Droid
variant can replace artifact delivery without forking readiness, installation,
model activation, backend admission, or resource-management logic. Store
variants, Maven Central, and a fully source-rebuilt ML Drift distribution may
be reconsidered in separate distribution work, but they are not gates for this
roadmap.

## Scope

### Goals

- Remove native LiteRT CPU, GPU, and vendor NPU payloads from the base APK when
  they can be installed safely after launch.
- Preserve the explicit-loader and isolated-inference-process safety boundary.
- Make a verified CPU runtime and one valid active model the universal local
  fallback path.
- Default every release-qualified GPU-capable device to enabling the bounded
  `N=1` GPU path while preserving a user-controlled opt-out.
- Make NPU use explicit, model-aware, vendor-aware, and removable.
- Prefer an exact, validated model-specific AOT variant over automatically
  installing QNN JIT.
- Keep runtime and model management understandable when many optional local
  resources are installed side by side.
- Repair corrupt or incomplete resources without deleting unrelated valid
  resources or generated audio.
- Keep backend choice invariant across foreground, background, Activity
  visibility, and screen-state changes.
- Implement runtime and model acquisition behind release-channel-neutral
  delivery interfaces while shipping only their GitHub implementations now.
- Keep release-channel capability decisions explicit so a future store build
  can remove NPU, JIT, remote runtime catalogs, or other customization without
  scattering flavor checks through product code.

### Non-goals

- Do not change source-window decoding, MP3 fallback boundaries, DSP, join
  placement, stem semantics, or playback behavior.
- Do not perform model conversion or NPU AOT compilation inside the app.
- Do not infer a model contract, `dimF`, or stem semantics from tensor shape.
- Do not expose queue depth, precision, forced OpenCL/OpenGL selection, CPU
  thread count, process mode, or raw provider options as product settings.
- Do not require cloud inference, account login, Play delivery, or a mutable
  remote configuration service.
- Do not implement or release a Play Store or F-Droid provider in the current
  phases; preserving an interface boundary is not a commitment to either
  store.
- Do not require future store variants to expose feature parity with the
  GitHub product.
- Do not let a missing optional accelerator block a valid CPU-plus-model path.
- Do not automatically delete an inactive model, AOT variant, valid runtime,
  or model-specific separation cache.

## Validated Baseline

The downloadable-runtime producer currently publishes the experimental
`downloadable-runtime-v2.1.5-bss.2-exp.2` release from
[`WluhWluh/bss-litert-android`](https://github.com/WluhWluh/bss-litert-android).
It contains:

- a classes-only API AAR with
  `com.google.ai.edge.litert.LiteRtNativeLibraryLoader`;
- one CPU-core bundle for each of `arm64-v8a`, `armeabi-v7a`, `x86_64`, and
  `x86`;
- an `arm64-v8a` bounded OpenCL GPU component with profile
  `gpu-opencl-bounded-fp32-v1`; and
- an immutable runtime contract, release index, checksums, licenses, and
  notices.

The explicit loader has passed the current API 26 through API 37 and four-ABI
CPU loading experiment. This is a bootstrap implementation baseline, not a
production support claim. Booming SS integration must repeat installation,
process-generation, model, and full-song validation from the final app graph.

Qualcomm QNN JIT and AOT experiments remain research evidence until their
distribution contracts, exact device selectors, and release packages satisfy
the phases below. No generic NPU support may be inferred from a successful run
on one Snapdragon generation.

### Current release-graph baseline

The current GitHub Debug universal APK built from the post-ONNX-removal graph
is 104,709,363 bytes. Its native inventory contains only the existing player
libraries (`libffmpegJNI.so`, `libtaglib.so`, and
`libandroidx.graphics.path.so`) for the four packaged ABIs. It contains no
`libonnxruntime*.so`, `libLiteRt.so`, `libLiteRtClGlAccelerator.so`,
`libBssOcl.so`, QNN, or vendor NPU library. LiteRT classes, catalogs, and
license notices remain in the APK as the classes-only/downloadable-runtime
bootstrap contract requires.

The release split/universal audit is repeated after every release-graph
change. Stale APKs from earlier dual-runtime builds are not validation
evidence. The historical ORT comparison reports remain archived outside the
production graph; original ONNX filenames in model provenance are not
executable acquisition or loading paths.

## Frozen Product Contract

The decisions in this section are product requirements. A phase may reject a
specific runtime, backend, ABI, device scope, or AOT artifact, but it must not
replace these ownership and interaction rules with a hidden heuristic.

### Navigation and ownership

The source-separation settings surface provides four peer entries:

1. Quick Setup;
2. Runtime Management;
3. Model Management; and
4. Separated Cache Management.

The source-separation Advanced section no longer owns a `Try to use GPU`
switch. It may show a read-only effective-backend summary that opens Runtime
Management, but it must not duplicate GPU or NPU controls.

Quick Setup owns recommendation and orchestration only. Runtime Management owns
runtime components and backend preferences. Model Management owns model
artifacts, portable model contracts, active-model selection, custom profiles,
and model-specific AOT artifacts. Cache Management owns generated separation
entries and must not install or remove runtimes or models.

### Delivery abstraction and release-channel boundary

The application defines three stable release-channel boundaries:

- `RuntimeDeliveryProvider`;
- `ModelDeliveryProvider`; and
- `ProductCapabilityPolicy`.

These interfaces isolate artifact transport and channel restrictions. They do
not replace the shared runtime store, model repository, verifier, readiness
evaluator, Quick Setup planner, backend policy, or run-admission protocol.

`RuntimeDeliveryProvider` is responsible for:

- identifying its provider and supported delivery operations;
- resolving a provider-neutral runtime delivery reference;
- acquiring or requesting one runtime component;
- reporting progress, cancellation, availability, and platform-managed state;
- returning a candidate payload handle to the common verifier/installer; and
- requesting provider-appropriate removal when supported.

It does not decide whether a component is trusted, compatible, recommended,
active, or safe to load. The common runtime catalog, verifier, installer,
leases, and process-generation contract retain those decisions.

`ModelDeliveryProvider` is responsible for:

- resolving a provider-neutral model or AOT delivery reference;
- acquiring or requesting one model-owned artifact;
- reporting progress, cancellation, availability, and platform-managed state;
  and
- returning a candidate model payload handle to the common model repository.

It never activates a model, interprets its contract, changes cache identity,
or decides AOT equivalence. Model import remains a local acquisition path under
the same repository contract and must not bypass verification.

`ProductCapabilityPolicy` is an immutable, build-selected declaration of what
one release channel exposes. It controls at least:

- allowed runtime and model delivery provider IDs;
- remote runtime and model catalog availability;
- runtime install, update, repair, and uninstall actions;
- GPU, vendor NPU, AOT, and QNN JIT availability;
- custom model import and any future custom-runtime developer mode;
- Quick Setup item classes and recommended defaults; and
- diagnostic, export, and local resource-management surfaces.

The policy may narrow catalog capabilities but never promote an unqualified
artifact. It is not downloaded from a server, changed by a remote catalog, or
used as an OEM blacklist. Unsupported capabilities are omitted or explained in
the UI rather than attempted and failed as if they were present.

The first implementations are fixed as follows:

- `GitHubRuntimeDeliveryProvider` uses immutable HTTPS GitHub Release assets;
- `GitHubModelDeliveryProvider` uses immutable `bss-tflite` Release assets;
- `GitHubProductCapabilityPolicy` enables only reviewed, implemented product
  capabilities. The CPU/GPU baseline must report vendor NPU, AOT, QNN JIT, and
  custom-runtime support as unavailable until their own phases pass; and
- deterministic test providers simulate success, progress, cancellation,
  corruption, platform-managed installation, and unsupported operations
  without network access before vendor acquisition is integrated.

Future implementations are intentionally possible but not current tasks:

- a Play runtime provider may use Play Dynamic Feature modules for executable
  code, while a Play model provider may use AI Packs for model-only payloads;
- a Play capability policy may expose only CPU, bounded GPU, and one
  recommended model and omit vendor JIT or arbitrary runtime customization;
- an F-Droid provider may resolve source-built packaged runtime components and
  use packaged or import-only models; and
- an F-Droid capability policy may omit proprietary vendor runtimes,
  unlicensed presets, remote executable downloads, and advanced customization.

Provider-specific dependencies stay in channel-specific adapter modules.
Shared fragments, Compose screens, ViewModels, repositories, readiness, and
Quick Setup code must not import Play Core, GitHub HTTP, or F-Droid packaging
types directly. A future store variant may have fewer rows and actions, but it
must preserve the same model activation, validation, cache, and admitted-run
correctness rules for every capability it keeps.

### Quick Setup planning modes and entry policy

The plan schema retains three explicit modes for deterministic planning and
tests:

- `BootstrapRecommended`: construct a first runnable recommended path;
- `RepairCurrent`: preserve a valid active model while repairing its blockers;
  and
- `RestoreRecommended`: install, repair, and activate the current release's
  recommended configuration.

They are not current user-selectable tabs. Every current Quick Setup entry
performs a fresh readiness evaluation and presents a `RestoreRecommended`
plan. A valid custom model is never deleted, but executing the reviewed model
item may make the installed recommended model active. Normal Model Management
downloads and imports remain independent and never activate implicitly.

An explicit separation request automatically opens Quick Setup only when the
readiness evaluator reports no runnable end-to-end path. A missing optional GPU
or NPU component is a degradation, not an automatic-popup condition, when CPU
and the active model are ready.

Opening or analyzing Quick Setup never mutates preferences. The user reviews
the generated checklist and presses `Execute selected items`; when no work is
needed, analysis immediately shows the completed result card. Canceling the
screen must not create a prompt loop. Automatic prompting may recur only after
the readiness fingerprint changes or after another explicit separation
request.

### Recommendation defaults

Quick Setup distinguishes `available` from `recommended`. A technically
downloadable experimental component is not automatically selected merely
because a URL exists.

- The compatible CPU core is required whenever no verified CPU core is
  installed.
- The single release-recommended base TFLite model is required whenever it is
  missing or is not the active model in the reviewed recommended plan. An
  already installed model is represented as a zero-download `Use` item.
- A release-qualified bounded GPU component is recommended and selected by
  default on a compatible device.
- A vendor NPU path is recommended and selected by default only when the
  recommended model has an exact validated AOT variant for the detected device
  and runtime tuple.
- When that exact AOT variant exists, Quick Setup must not also recommend QNN
  JIT.
- Without an exact AOT variant, QNN JIT may be shown as an advanced available
  path, but it is not selected by the recommended bootstrap plan.
- A detected corrupt or incomplete selected resource adds a scoped repair or
  cleanup item selected by default.

CPU and a required model row may be locked while they are the only way to
produce a runnable fallback. GPU and NPU rows remain deselectable. Deselecting
one means the corresponding persistent enablement preference is disabled when
the plan commits.

### Installation and commit behavior

Quick Setup executes selected work serially in dependency order:

1. remove only verified-invalid staging or selected corrupt artifacts;
2. install and validate the CPU core;
3. install and validate the bounded GPU component;
4. install and validate a vendor NPU runtime component;
5. install if needed and activate the recommended base model when required;
6. install and validate an exact AOT model variant when selected;
7. validate the complete candidate execution paths;
8. atomically commit active-model and backend preferences; and
9. recycle or start the next inference-process generation.

Completed valid installation steps may remain installed after cancellation or
an optional failure. Active-model and backend-policy changes commit only after
all required postconditions pass. An optional accelerator failure must not
destroy an existing runnable configuration or enable a missing component.

In `RepairCurrent`, a valid active model remains active. In
`BootstrapRecommended` and a confirmed `RestoreRecommended`, the recommended
model becomes active only as part of the validated setup transaction. The UI
uses one `Install and use` or zero-download `Use` row rather than separate
download and selection rows. Ordinary Model Management downloads and imports
never activate a model automatically.

### Runtime Management behavior

Runtime Management provides:

- device ABI, API, SoC, and discovered accelerator capability;
- installed, invalid, update-available, active-generation,
  pending-activation, and pending-deletion states;
- download size, installed size, version, source Release, SHA-256, profile,
  dependencies, license, and notices;
- install, repair, update, and uninstall actions;
- persistent GPU and NPU enablement controls;
- QNN JIT installation and removal;
- JIT compilation-cache cleanup;
- the actual backend and runtime identities used by the latest run; and
- typed ineligibility and fallback diagnostics.

CPU has no enablement toggle because it is the fallback contract, but an
advanced explicit uninstall action is allowed. Removing the last valid CPU
core makes local separation unready and causes the next explicit separation
request to offer Quick Setup. A loaded runtime cannot be removed immediately;
the UI records pending deletion and explains that it will complete after the
owning inference process exits.

GPU and NPU enablement are user intent, not proof of availability. A runtime
failure records diagnostics and may select a safe fallback, but it must not
silently rewrite the persistent preference or create a hidden device blacklist.

### Model Management interaction

Model Management retains independent `Download`, `Use`, and `Delete` actions.
It additionally displays:

- CPU/GPU qualification for the model;
- exact AOT availability for the current device/runtime tuple;
- installed AOT variants and their storage use;
- whether NPU would require QNN JIT; and
- the backend that produced each relevant validation record.

An AOT artifact is a model-specific execution variant, not a general runtime
and not a separately selectable semantic model. Its payload is managed beside
the base model. Vendor libraries shared by multiple models remain Runtime
Management resources.

Activating a model without an exact AOT remains allowed when CPU or GPU can run
it. When NPU is enabled but no exact AOT exists, show a nonblocking action that
opens the QNN JIT entry in Runtime Management. Do not automatically download
JIT, repeatedly prompt for the same model/runtime fingerprint, or replace the
selected model.

### Backend policy

The first product schema stores two optional-backend preferences:

- `gpuEnabled`; and
- `npuEnabled`.

CPU is always an implicit fallback while its runtime is installed. There is no
foreground/background policy setting. A clean recommended setup enables every
release-qualified selected accelerator. A user opt-out persists until a later
explicit Quick Setup commit or Runtime Management change.

When both optional backends are enabled, run admission uses this deterministic
priority:

1. exact verified vendor NPU AOT;
2. installed and eligible vendor NPU JIT;
3. bounded GPU;
4. CPU.

The selected backend and exact runtime/model identities are frozen at run
admission. Foreground, background, Activity visibility, and screen state never
switch an admitted run. A recoverable failure before useful work may try the
next enabled path after complete cleanup. The initial product policy for a
mid-run accelerator failure is direct CPU fallback rather than chaining
through another accelerator; broader chaining requires separate lifecycle and
output validation.

The only production-candidate GPU profile remains
`gpu-opencl-bounded-fp32-v1` with `N=1`. Stock `N=0`, forced backend selection,
precision, and queue controls remain internal diagnostics.

## Frozen Data Contract

### Independent schema ownership

The following schemas are versioned independently:

- portable model contract schema;
- model catalog schema;
- artifact delivery-reference schema;
- downloadable runtime producer contract;
- Booming SS runtime catalog schema;
- installed-runtime record schema;
- AOT variant schema;
- readiness schema;
- Quick Setup plan schema;
- backend preference schema;
- product capability-policy schema;
- run-admission protocol; and
- backup format and source-separation settings schema.

An app version change does not imply a schema change. A producer contract
revision does not silently reinterpret installed state. Unknown future schema
versions fail closed before native loading while leaving unrelated playback
and local resources intact.

Because Booming SS has not shipped a release, app-private product state also
has no predecessor-schema compatibility contract. Runtime inventory, readiness,
Quick Setup state, run admission, cache journals, debug controls, and ordinary
preferences accept only their current schema and key set. A schema replacement
must update the writer and reader together; it must not add a fallback parser,
field alias, migration marker, or value conversion for an unreleased format.
Invalid durable resources are repaired or reinstalled, disposable state is
discarded and rebuilt, and an incompatible Room database is destructively
recreated.

The sole compatibility exception is the user-invoked `.bmgbak` boundary. Its
versioned common-settings payload and filtered package preference projections
remain interoperable with upstream Booming Music. That explicit import/export
contract does not make any runtime, model, cache, journal, mix-setting, debug,
or database predecessor schema readable during normal application startup.

### Provider-neutral delivery reference

Runtime and model content identity is independent from its delivery channel.
One reviewed artifact may have multiple delivery references without receiving
a new component, model, contract, or cache identity.

Each `ArtifactDeliveryReference` records at least:

- delivery-reference schema version;
- provider ID and artifact kind;
- immutable provider artifact ID;
- expected logical component/model identity;
- expected bytes, SHA-256, and inner manifest identity where applicable;
- provider-specific locator data; and
- required provider capability and minimum adapter version.

For GitHub, locator data includes an immutable Release tag and HTTPS asset URL.
For Play, it may contain a Dynamic Feature module or AI Pack name tied to the
installed base-app version. For a packaged build, it may contain a build-time
component ID and packaged-library/resource identity. Portable contracts and
backups never contain a device-local absolute path.

A delivery provider returns bytes or a platform-managed payload handle. The
common verifier resolves that handle to the same content and compatibility
checks before installation or admission. Provider success alone is never proof
that a runtime or model is valid.

### Runtime catalog and producer contract

`bss-litert-android` remains authoritative for the native bundle inventory,
ELF identity, profile, dependencies, checksums, source lock, licenses, and
release index. Booming SS consumes that contract; it does not duplicate or
weaken it.

Each bundled Booming SS runtime catalog entry records at least:

- stable component ID and component type;
- immutable producer Release tag and release version;
- producer contract schema and contract SHA-256;
- one or more provider-neutral delivery references;
- byte size, SHA-256, and inner manifest SHA-256;
- runtime artifact version and base LiteRT version;
- ABI and minimum Android API;
- device or provider selector where applicable;
- dependency component IDs and exact accepted versions/hashes;
- backend profile and capability ID;
- license and notice references; and
- product maturity: recommended, experimental, or unavailable.

The GitHub provider never installs from a mutable branch or `latest` asset. A
remotely discovered catalog may advertise a later immutable release, but it
must not change the active version, support tier, delivery provider, or backend
preference without an explicit user action and local verification. A future
provider must supply an equally immutable channel-specific reference.

### Installed runtime record

Installed inventory is derived from verified files and manifests under the
runtime store. Preferences never assert that a runtime is installed.

Each installed component exposes at least:

- component and release identity;
- canonical install directory;
- ABI and API compatibility;
- verified files, byte sizes, and SHA-256 values;
- dependency resolution result;
- install and last-validation timestamps;
- state: installed, invalid, pending activation, active in process generation,
  or pending deletion; and
- validation or rejection reason.

Absolute paths are local derived values and never enter a catalog, sidecar,
backup, or portable manifest.

### AOT variant contract

Every AOT variant binds at least:

- AOT schema version and stable variant ID;
- exact base model ID, artifact SHA-256, contract ID, and pipeline version;
- vendor and provider type;
- target SoC identifier or reviewed compatible SoC family;
- ABI and Android API range;
- required vendor runtime and exact accepted version range;
- compiler/toolchain version and compile options;
- execution profile and precision;
- AOT delivery references, size, SHA-256, and inner-file inventory;
- output-equivalence validation identity and tolerances; and
- source, attribution, license, and notice records.

Quick Setup installs the base TFLite model together with the AOT variant so a
known CPU/GPU fallback remains possible. AOT does not change model or cache
identity when the variant is explicitly validated as output-equivalent to the
same model/render contract. An artifact that changes rendering semantics or
exceeds the accepted equivalence gate requires a new render or model identity;
it must not masquerade as an optimization of the old model.

### Readiness contract

`LocalSeparationReadiness` is a structured result, not a Boolean model-file
check. It contains:

- schema version and deterministic readiness fingerprint;
- overall state;
- zero or more runnable execution paths;
- blocking issues;
- optional degradations;
- repair candidates; and
- the active-model and process-generation references used for evaluation.

The overall states are:

- `Ready`: at least one requested or fallback path is immediately runnable;
- `Degraded`: CPU or another safe path is runnable, but an enabled optional
  path is missing, invalid, or ineligible;
- `NeedsSetup`: no runnable path exists because required resources are absent;
- `RepairRequired`: no runnable path exists and selected local resources are
  corrupt, mismatched, or incomplete; and
- `Unsupported`: no cataloged path can run on the current ABI/API/device scope.

Typed blockers include missing/invalid CPU runtime, wrong ABI or API, missing
active model, missing model file, model-contract mismatch, missing required
runtime dependency, process-generation activation failure, and unsupported
model/device combination. Download storage and network conditions are setup
preconditions, not false claims that an already installed path is unready.

### Quick Setup plan contract

A generated `QuickSetupPlan` records:

- schema version, mode, plan ID, and input readiness fingerprint;
- detected device/runtime/model catalog revisions;
- selected delivery provider IDs and immutable delivery references;
- ordered plan items and dependency IDs;
- action type: install, repair, remove invalid, validate, select, configure, or
  recycle process;
- required, recommended, selectable, and selected flags;
- reason and disabled reason;
- expected download and installed bytes;
- exact resource identity and postcondition;
- proposed final active model and backend preferences; and
- terminal result for each executed item.

The plan is a transient transaction description, not installed-resource truth
and not a backup payload. Before execution it must be invalidated and
recomputed if the device, catalog, installed inventory, active model, available
storage, or readiness fingerprint changes.

### Run-admission snapshot

Every admitted run records a versioned immutable snapshot containing:

- active model artifact and contract identity;
- selected execution path and profile;
- exact CPU, GPU, NPU, and AOT identities used or considered;
- admitted `gpuEnabled` and `npuEnabled` values;
- eligibility, setup/probe, and fallback results;
- process generation; and
- source/cache identity required by the existing separation protocol.

Only the current run-journal schema is accepted. Its `tryGpu` member is an
immutable per-run admission fact that must agree with the recorded GPU runtime
identity; it is not a preference alias or a migration input. Diagnostic reports
retained outside app-private product state may describe old experiments, but no
production reader translates those reports into settings or resume state.

### Storage ownership

- Runtime components live in an app-private, no-backup, versioned runtime store.
  They are durable installed resources and are removed through Runtime
  Management, app-data clearing, or uninstall, not ordinary cache eviction.
- Model weights, contracts, custom profiles, and AOT model variants remain
  durable app-private model data and are excluded from backup binaries.
- Partial windows, completed stems, FLAC intermediates, hydration data, run
  journals, diagnostics, performance reports, and QNN JIT compilation output
  are cache data.
- Installation staging uses a same-filesystem private staging directory so
  final installation can use an atomic rename. Abandoned staging is eligible
  for scoped repair cleanup but is never reported as installed.
- Clearing system cache may remove every separation output and JIT compilation
  cache. It must not remove installed runtime or model resources.

### Persistence and backup

Persistent source-separation settings include desired `gpuEnabled` and
`npuEnabled` values and the existing portable active-model reference. Installed
runtime/model/AOT inventory is reconstructed from disk and never stored as a
preference source of truth.

The versioned source-separation backup allowlist includes backend user intent,
but excludes:

- runtime, model, and AOT binaries;
- installed-resource inventories and absolute paths;
- delivery-provider sessions, platform module/pack state, and capability policy;
- Quick Setup plans and prompt fingerprints;
- downloads, staging, and worker progress;
- QNN compilation output;
- cache manifests, WAV/FLAC/PCM data, and per-song blend;
- process generations, fallback history, diagnostics, and performance data.

Restore never downloads a component, activates a substitute model, or installs
a vendor runtime. Missing resources remain pending references. A later explicit
separation request may offer Quick Setup, and downloading a missing model in
Model Management still requires an explicit `Use` action unless the user
explicitly runs `RestoreRecommended`.

The clean-install compatibility boundary remains the release baseline. The old
`source_separation.try_gpu` preference is rejected rather than mapped to
`source_separation.gpu_enabled`; only the explicit `.bmgbak`
settings-compatibility boundary may translate an allowlisted upstream
preference representation. The current journal's `tryGpu` admission fact does
not weaken that preference boundary.

## UX Contract

### Source-separation settings

The settings surface should be a compact operational list, not a second
dashboard. Each management entry shows a short derived summary:

- Quick Setup: ready, repair available, or setup required;
- Runtime Management: installed components and effective backend;
- Model Management: active model and installed-model count; and
- Cache Management: entry count and total generated size.

Backend toggles do not appear in Advanced. A read-only current-backend row may
deep-link to Runtime Management.

### Quick Setup screen

The screen uses four stable states:

1. analyzing device and local resources;
2. reviewing an ordered checklist;
3. installing one item at a time with per-item progress; and
4. complete, partially complete, blocked, or canceled result.

Each checklist row keeps its checkbox at the left of the title and a details
action at the right. A download row shows expected download and installed size;
a zero-download `Use` row omits both. Recommendation, reason, non-pending
execution state, failure, and disabled details live in the details dialog. The
always-available status dialog reports CPU, supported GPU, active model, and
typed readiness issues.

The footer action is `Execute selected items`. During execution it becomes a
cancel action that stops after the current atomic step reaches a safe boundary.
Required-item failure yields a blocked result with retry and management
actions. Optional-item failure yields a partial result and must keep or finish
the required CPU/model path. The result card has no redundant heading; its
retry, Runtime Management, and Model Management actions each occupy one full
row. A fully recommended configuration shows this completed result card
directly after automatic analysis.

`RestoreRecommended` must state that it will select the recommended model and
reset GPU/NPU enablement to the reviewed selection. It repairs or installs the
recommended resources but does not delete unrelated custom models, experimental
presets, valid older runtime versions, or separation caches.

### Runtime Management screen

Use sectioned lists rather than nested cards:

- current device and effective backend;
- backend policy toggles;
- installed components;
- available components and updates;
- storage and compilation-cache actions; and
- diagnostics.

Each component row exposes one primary state-appropriate action and an overflow
menu for details, repair, or removal. Destructive removal requires confirmation
and identifies dependents. Removing a shared vendor runtime never silently
deletes model AOT artifacts; those become visibly unavailable until the runtime
is restored or the AOT artifact is removed from Model Management.

### Model Management screen

Preset and custom-model details remain available before and after download.
Backend compatibility is displayed as derived status, not editable model
metadata. Exact AOT variants appear under their base model with target device,
runtime requirement, version, and size.

When the active model lacks an exact AOT and QNN JIT is absent, a single
nonblocking action opens the matching Runtime Management entry. Dismissing that
guidance records only a local prompt fingerprint; it does not disable NPU,
change the model, or enter backup.

## Runtime Lifecycle and Concurrency

Runtime directories are immutable after validation. Installation uses an
OS-backed cross-process lock, canonical paths, ZIP path validation, size limits,
hash verification, ELF/ABI/SONAME/dependency checks, read-only permissions, and
atomic publication.

The inference process must configure and load the selected absolute CPU runtime
path before referencing any other LiteRT API class. GPU and vendor dependencies
load in their recorded order. A process generation may bind one CPU runtime
identity only; reconfiguration to another path is rejected.

Installing an update creates a pending version. If the current inference
process is idle, the app may stop it and start the new generation immediately.
An active run remains on its frozen generation. Removing a loaded version marks
it pending deletion, prevents new admission to it, and deletes it only after the
owning process exits and all install/run leases are released.

Concurrent Quick Setup, Runtime Management, app-update, and worker operations
must resolve through the same installer and lease APIs. UI state never deletes
files directly.

## Implementation Phases

### Phase 0: Freeze authority and contracts

**Status: complete.**

- [x] Make this document authoritative for runtime setup and local runtime
  management.
- [x] Freeze panel ownership, Quick Setup modes, recommendation defaults,
  backend priority, AOT/JIT behavior, and commit semantics.
- [x] Freeze runtime catalog, installed record, AOT, readiness, setup-plan,
  admission, storage, persistence, and backup boundaries.
- [x] Freeze `RuntimeDeliveryProvider`, `ModelDeliveryProvider`, and
  `ProductCapabilityPolicy` as the only release-channel boundaries; keep the
  current implementation GitHub-only.
- [x] Freeze the inference-process roadmap as historical process/background
  evidence and remove conflicting future UI requirements from it.
- [x] Reconcile the active multi-preset roadmap's authority and GitHub-only
  near-term release wording.

**Phase 0 exit:** no active roadmap places the GPU preference in Advanced,
requires native LiteRT payloads in the base APK, or routes a missing local path
directly to Model Management.

### Phase 1: Integrate the downloadable CPU core in Booming SS

**Status: implementation complete; current-API loader smoke covers all four
ABIs, while API 29, clean-install, and full product qualification remain open.**

- [x] Introduce `RuntimeDeliveryProvider`, `ModelDeliveryProvider`, and
  `ProductCapabilityPolicy` in release-channel-neutral modules. Deterministic
  provider doubles and policy contract tests cover the acquisition boundary
  without coupling common code to GitHub transport details.
- [x] Implement and wire only `GitHubRuntimeDeliveryProvider`,
  `GitHubModelDeliveryProvider`, and `GitHubProductCapabilityPolicy`; prove the
  GitHub graph contains no Play Core, Dynamic Feature, AI Pack, or
  F-Droid-specific adapter dependency.
- [x] Replace the complete native AAR dependency with the verified classes-only
  API AAR and prove the APK contains no `libLiteRt.so` or
  `libLiteRtClGlAccelerator.so`.
- [x] Add a process-start runtime bootstrap that resolves one verified CPU
  component, calls `configureAbsolutePath`, then `load`, before any
  `Environment`, `CompiledModel`, or `TensorBuffer` reference.
- [x] Make runtime absence, invalid contract, wrong ABI, missing dependency,
  load failure, and conflicting loader path typed failures outside playback.
- [x] Keep the loaded component immutable for one inference-process generation.
- [x] Complete the install, cold start, warm start, process death,
  main-process death, corrupt file, wrong ABI, delete/reinstall, and version
  switching matrix at the store/process-contract level. Current device
  coverage proves source-separation process death/reconnect and process
  recycle on S10 and S25; clean-install destructive UI flows remain a release
  qualification item.
- [x] Run the downloaded CPU loader smoke on all four available ABI rows:
  forced 32-bit `armeabi-v7a` on S10, `arm64-v8a` on S25, pure `x86` on the
  API 26 emulator, and `x86_64` on the API 37 emulator. The test selected the
  process ABI explicitly and loaded only the matching app-owned library.
- [x] Record current-API downloaded CPU-loader evidence for the four available
  rows: S10 forced `armeabi-v7a`, S25 `arm64-v8a`, API 26 pure `x86`, and API
  37 `x86_64`.
- [ ] Decide the support tier for the untested API 29 row and for each
  non-arm64 ABI. Either add the missing API 29 evidence or explicitly keep that
  row out of the release support matrix; loader smoke alone must not promote a
  full-song separation tier.

**Phase 1 status:** the real Booming SS inference process now loads a verified
CPU runtime only from the app-owned absolute path, and the base APK remains a
fully functional music player without a native LiteRT payload. Current-API
loader smoke now covers S10 forced arm32, S25 arm64, API 26 pure x86, and API
37 x86_64. These are runtime-loading results only; they do not infer full-song
separation support, GPU support, or API 29 coverage.

### Phase 2: Build the runtime store and CPU management UI

**Status: feature complete; downloaded-path clean-install, destructive
operation, and UI lifecycle qualification remain open.**

- [x] Add the bundled immutable runtime catalog snapshot and strict parser.
- [x] Implement cross-process install locks, staging, verification, atomic
  publish, inventory rebuild, leases, pending activation, and pending deletion.
- [x] Keep the runtime store provider-neutral and implement resumable HTTP only
  inside the GitHub runtime provider without treating a partial file as an
  installed component.
- [x] Add free-space preflight for compressed download, staging, and installed
  bytes.
- [x] Add Runtime Management with device details, CPU install/repair/update/
  uninstall, hashes, provenance, size, and diagnostics.
- [x] Test resumable/interrupted download, hash mismatch, ZIP traversal,
  repeated-install idempotence, low disk, and lease-deferred deletion.
- [x] Test canceled repair, app update, duplicate installer handling, orphan
  staging cleanup, and Runtime Management store lifecycle behavior. A failed
  replacement preserves the installed runtime and a subsequent retry publishes
  the replacement atomically; UI-driven destructive flows remain in the final
  qualification matrix.

**Phase 2 implementation status (2026-08-02):** the CPU runtime catalog, store,
GitHub delivery path, and Runtime Management page are implemented. The page
shows every catalog ABI but only permits operations for the process ABI; runtime
operations do not modify model selection or separation caches. The current
GitHub Debug variant compiles, assembles, and passes the complete JVM unit-test
suite. Installed CPU and GPU component files are made read-only after publish;
GPU inventory also removes unknown staging directories. The CPU store preserves
the installed version across a failed replacement and atomically publishes a
verified retry. Phase 2 remains open only for clean-install device/UI lifecycle
coverage.

**Phase 2 exit:** CPU runtime state is derived from verified disk records and is
fully manageable without model or cache side effects.

### Phase 3: Add readiness and CPU-first Quick Setup

**Status: product flow and transactional executor complete; downloaded-path
clean-install and UI qualification remain open.**

- [x] Introduce `LocalSeparationReadiness` and replace the current model-only
  readiness gate and automatic Model Management opening.
- [x] Implement all readiness states, typed blockers, degradations, repair
  candidates, and deterministic fingerprints.
- [x] Implement `BootstrapRecommended`, `RepairCurrent`, and
  `RestoreRecommended` plan generation.
- [x] Add sequential plan execution, item progress, cancellation boundaries,
  retry, final validation, and atomic settings/model commit.
- [x] Integrate one recommended base model without changing ordinary Model
  Management download/activation separation or exposing GitHub transport types
  outside `ModelDeliveryProvider`.
- [x] Add manual Quick Setup entry and automatic opening only when no runnable
  path exists.
- [x] Reanalyze on every entry, present the current recommended plan without a
  mode switch, combine recommended-model installation and activation into one
  row, and show the completed result card when no work remains.
- [x] Validate on S25 the installed-ready state, status details, zero-download
  GPU preference reset, execution result, and fresh reanalysis after re-entry.
- [ ] Test fresh install, valid custom model, missing active model, corrupt
  model, corrupt runtime, no network, low storage, process death, cancel, retry,
  and optional-resource partial completion.

**Phase 3 implementation status (2026-08-02):** `LocalSeparationReadiness` now
evaluates the verified CPU runtime and active model as one runnable path. The
planner produces deterministic bootstrap, repair, and recommended-restore plans;
the executor validates the plan fingerprint, installs selected resources in
dependency order, reports progress, observes cancellation at item boundaries,
and commits an official model selection only after all selected resources have
installed, followed by final readiness validation.
Quick Setup is available manually from Source Separation settings and is the
automatic recovery destination when a separation request has no runnable local
path. Runtime and model handoffs remain separate, and the runtime handoff opens
the Runtime Management page directly. The current screen always reviews the
release-recommended configuration, automatically rechecks on entry, keeps
zero-download activation items compact, and exposes readiness through an
on-demand status dialog.

The planner and executor tests, complete GitHub JVM unit-test suite, Kotlin
compilation, AndroidTest compilation, GitHub Debug APK assembly, and focused
S25 UI smoke pass. The executor schedules required work independently of the
optional GPU, commits active-model/backend settings only after final
readiness, and preserves the prior selection on cancellation, stale plans,
required failure, and optional failure.

**Phase 3 exit:** a fresh user can obtain a verified CPU-plus-recommended-model
path with one reviewed action, while an experienced user's valid model and
unrelated resources remain untouched by repair.

### Phase 4: Add downloadable bounded GPU

**Status: feature path complete; final downloaded-path full-song and clean
install qualification remain open.**

- [x] Add the bounded GPU catalog component and exact CPU-core dependency.
- [x] Load accelerator and `libBssOcl.so` in the verified dependency order and
  attest `gpu-opencl-bounded-fp32-v1` with `N=1`.
- [x] Move the persistent GPU preference from Advanced to Runtime Management.
- [x] Make a qualified GPU component recommended by Quick Setup and deselectable
  by the user.
- [x] Preserve the admitted preference and backend across UI, background, and
  screen-state changes.
- [x] Add deterministic setup/probe/invocation/output failure, complete-cleanup,
  one-way CPU fallback, process-poison, and backend-policy recycle coverage.
- [ ] Repeat those cases through the final product download path and test
  update, pending activation, loaded-version removal, and process death.
- [ ] Repeat S10/S25 full-song, PSS, thermal, power, foreground interaction,
  FrameTimeline, and playback-underrun comparisons using the final downloaded
  component path.

**Phase 4 exit:** qualified devices default to bounded GPU without shipping its
native payload in the APK, and CPU remains a complete verified fallback.

**Phase 4 implementation record (2026-08-02):**

- `litert-gpu-runtime-catalog-v1.json` describes the arm64 bounded OpenCL
  component, its exact CPU library SHA-256 dependency, two native libraries,
  and the fixed `N=1` capability profile.
- GPU installation uses its own catalog/store directory and install record but
  shares the ABI process lease with the CPU runtime. ZIP, manifest, ELF-file
  hashes, dependency identity, pending activation, pending deletion, and
  corrupted-payload tests pass. Low-storage preflight rejects before network
  acquisition, invalid complete payloads remove their staging directory, and
  unknown staging directories are removed during inventory rebuild.
- The source-separation process loads the verified CPU library first, then
  `libBssOcl.so`, then `libLiteRtClGlAccelerator.so` from absolute paths. JNI
  capability is compared with the manifest before GPU eligibility is exposed;
  the loader also binds the GPU manifest, install record, component catalog,
  and CPU `install.json` identity before loading; failure leaves CPU
  available.
- The production worker now requests a fresh inference-process generation when
  an idle resident session was created under a different CPU/Auto backend
  policy. JVM tests and the device-test harness assert generation, PID, binder
  death, and admitted-backend identities rather than assuming a reconnect used
  new native state.
- Runtime Management owns the only canonical `gpu_enabled` preference and the
  GPU component actions. The old `try_gpu` key is not read or written; this
  unreleased product has no legacy-settings migration requirement.
- Readiness and Quick Setup treat the qualified GPU as an optional selected
  recommendation. Model installation and model selection depend only on
  required CPU/model items, so deselecting GPU remains valid. A successful
  Quick Setup commit resets the GPU preference to the selected recommendation.
- JVM coverage currently passes the complete GitHub unit-test suite, including
  runtime stores, locator, planner, bounded capability, Auto fallback, and
  model-aware engine tests. AndroidTest sources also compile with the
  downloaded-runtime diagnostics. A focused S25 product smoke has exercised
  installed CPU/GPU discovery, recommended GPU preference restoration, status
  reporting, and Quick Setup re-entry. The downloaded-runtime smoke passed on
  S10 and S25 for the bounded GPU state and on all four ABI rows for CPU
  loading; this does not replace clean-install, full-song, update/removal,
  failure-injection, or performance reports and is not release qualification.
  No non-arm64 ABI is promoted to GPU or full-song separation by inference.

The disposable S25 Debug pass on 2026-08-14 added a clean-data product
lifecycle row. Quick Setup downloaded and installed the pinned arm64 CPU core,
bounded GPU, and default 9662 Release model, then reached `Ready`. Removing the
GPU while the inference process was idle recycled that process; with GPU still
enabled, the next MDX run correctly used `LiteRtCpu`. Repair during an active
CPU run was rejected without interrupting or corrupting the run, while the
same repair succeeded after pause. Removing the CPU made readiness non-runnable
and invalidated the installed GPU dependency; `RepairCurrent` then reinstalled
CPU and repaired GPU in one transaction without changing the active model or
selection generation. Runtime/model downloads, failed-operation retry, process
death, cache playback, and diagnostics export were exercised through the real
 Debug product providers. This closes the S25 functional lifecycle row; the
 S10 CPU-only multi-stem row is recorded below. Update/version-switch payloads,
 thermal/power/PSS runs, and release qualification remain open below.

### Pre-Phase 5 gate: close the CPU/GPU product baseline

**Status: release graph, contract, implementation, and S10/S25 functional
downloaded-path gates are closed; performance/thermal measurement, stable UI
coverage, and the short human handoff qualification gates remain open before
any NPU capability is exposed.**

The
[Source-Separation Lifecycle and Cache Correctness Roadmap](source-separation-lifecycle-cache-correctness-roadmap.md)
is an additional hard pre-NPU gate. Its active-model handoff, scheduler,
playback, cache-state, retention, deletion, simplification, and automated/device
qualification phases are complete; the remaining short human handoff-listening
check is still a release-confidence gate. NPU schema drafting and offline
tooling research may continue, but no NPU route may be added to the product
catalog until the downloaded CPU/GPU baseline below is closed. The generic
N-stem data-plane work may proceed independently and does not constitute NPU
qualification.

The current evidence is recorded in
[pre-npu-baseline-2026-08-02.md](validation/litert-runtime/pre-npu-baseline-2026-08-02.md).

The functional S10 arm32 source-separation evidence is recorded in
[`source-separation-multistem-contract-playback-roadmap.md`](source-separation-multistem-contract-playback-roadmap.md)
under the Phase 8 S10 boundary matrix. It closes product-path behavior and
resource-lifecycle coverage for the CPU-only multi-stem row, but does not close
the CPU/GPU performance baseline or grant any NPU support.

Phase 5 must not begin as product implementation until the following hard
gates are closed. Schema drafting and offline AOT tooling research may continue,
but no NPU capability may be enabled in the app catalog or UI before this gate
exits.

#### Remaining work before NPU product implementation

The remaining work is intentionally a product-baseline gate, not a reason to
change the frozen source-separation algorithm:

0. Replace staged/local model inputs in qualification scripts with the
   immutable `v0.2.0-experimental.1` GitHub catalog and Release assets. The
   downloaded artifact and sidecar must be installed through the real model
   delivery path before any CPU/GPU/Demucs result is counted as product-path
   evidence. Keep local staging only as a lower-level loader diagnostic.

1. Run a disposable clean-install Quick Setup pass on S10 and S25. Cover CPU
   runtime plus 9662 installation, optional bounded-GPU selection and opt-out,
   failed and retried downloads, automatic and manual entry, cold/warm start,
   force-stop, inference-process death, repair, update, removal, and reinstall.
2. Run the final downloaded-path 9662 CPU/GPU full-song matrix. Compare digital
   output and representative listening, cancellation, background continuation,
   playback readiness, and cache playback. Keep the existing window decode and
   MP3 fallback policies unchanged; use the already-qualified GPU
   `gpu-opencl-bounded-fp32-v1` profile with `N=1`.
3. Record at least three cold repetitions where a recommendation decision uses
   performance. Preserve wall time, first-ready time, peak PSS, graphics/native
   memory, temperature, power, foreground FrameTimeline, and playback-underrun
   data separately for CPU and GPU. These are the baseline inputs for any later
   NPU claim.
4. Close the downloaded-runtime lifecycle boundary: update while idle and
   active, pending activation/deletion, loaded-version removal, process death,
   CPU fallback, and recovery after corrupt or missing local resources. Confirm
   that runtime/model ownership and system clear-cache behavior remain intact.
5. Complete stable UI and accessibility coverage for Quick Setup and Runtime
   Management: automatic no-loop prompting, manual re-entry, result/details
   dialogs, cancellation/retry/partial results, process recreation, long
   localized labels, and destructive-action confirmations.
6. Publish an explicit support matrix. Current evidence supports CPU-loader
   smoke on four ABI rows and bounded-GPU loading only on the qualified arm64
   path. The S10 `armeabi-v7a` row now has full CPU product-path evidence,
   including the experimental HTDemucs lifecycle rule that recycles the
   inference process after terminal runs; it still has no GPU row. x86,
   x86_64, and API 29 remain loader-only or unqualified unless separately
   tested. NPU work may initially target only a named, qualified arm64 device
   and exact model/runtime combination.

Scope boundary: keep the Demucs research matrix separate from this gate. Its
CPU, hybrid GPU, and failed QNN rows may inform future adapter work, but cannot
qualify a vendor runtime or unlock NPU UI/backend routing.

No NPU capability should be added to the catalog, Quick Setup recommendation,
Runtime Management controls, backup schema, or model guidance until items 1–6
are either completed or explicitly waived with a documented support boundary.

#### Release graph and capability truth

- [x] Complete the multi-preset roadmap's ONNX retirement: archive the frozen
  comparison evidence, remove `SourceSeparationOrtOracle`,
  `onnxruntime.android`, executable ONNX acquisition/import paths, legacy
  profiles, and obsolete legacy cache routing from the release app.
- [x] Build ABI splits and a universal GitHub APK from the current release
  graph; prove they contain no ORT, LiteRT CPU, bounded-GPU, QNN, or vendor
  native payload, then record base APK size separately from every downloadable
  component.
- [x] Make `GitHubProductCapabilityPolicy` advertise only implemented and
  release-visible capabilities. Vendor NPU, AOT, QNN JIT, and custom-runtime
  support remain unavailable until their own product phases pass.
- [x] Add deterministic runtime/model delivery-provider doubles and policy
  contract tests before vendor-specific acquisition multiplies the state space.

#### Quick Setup transaction safety

- [x] Reorder or dependency-schedule setup so required CPU/model work completes
  independently of optional accelerators. A selected GPU failure must not skip
  a later required model install/activation.
- [x] Make active-model and backend-preference changes a final validated commit,
  or implement explicit rollback. Cancellation, optional failure, stale plans,
  and final-readiness failure must preserve the previously runnable selection.
- [x] Add executor-level tests for clean bootstrap, installed-resource reuse,
  valid custom model, missing/corrupt model, corrupt CPU/GPU runtime, offline,
  bad hash, low disk, cancellation at every item boundary, retry, stale plan,
  optional partial completion, and no-work completion.
- [ ] Add stable UI tests for automatic prompt/no-loop behavior, manual entry,
  result and details dialogs, selection persistence during one review, process
  recreation, management navigation, accessibility labels, and long localized
  text.

#### Runtime lifecycle and persistence

- [x] Complete CPU and GPU canceled repair, duplicate/concurrent install,
  orphan staging, app update, version switch, corrupt installed file,
  wrong-ABI payload, pending activation/deletion, delete/reinstall, main-process
  death, inference-process death, and loaded-version removal tests.
- [x] Promote `source_separation.gpu_enabled` into the versioned backup
  allowlist and device tests. The old `source_separation.try_gpu` key is not
  canonical and is rejected under the unreleased clean-install boundary.
- [x] Close the unreleased predecessor-data audit. Cache identity/manifest/
  journal/lock records, model mix settings, Room data, execution-selection
  requests, and Debug fault controls now require their current representation;
  only the user-invoked `.bmgbak` codec retains reviewed Booming Music
  interoperability (`13b4e712` through `f3be6f66`).
- [x] Verify by path-ownership tests that clear-cache targets generated
  separation data only and leaves runtime/model inventory roots outside it.
  A destructive clear-app-data/uninstall run remains a manual qualification
  item because it intentionally removes the installed test resources.

#### Final CPU/GPU device baseline

- [x] Exercise the downloaded Release path through Debug control on S10
  `armeabi-v7a` and S25 arm64 for runtime/model installation, MDX and
  CPU-only HTDemucs execution, cache deletion, model-family activation,
  inference-process death, app force-stop/manual resume, and diagnostics.
  This functional row is separate from performance and GPU qualification.
- [ ] On clean app data, run the complete CPU + recommended 9662 + optional
  bounded-GPU Quick Setup flow on S10 and S25, including cold/warm start,
  force-stop, process death, repair, update, removal, opt-out, and reinstall.
- [ ] Repeat 9662 full-song CPU/GPU numerical and listening checks on the final
  downloaded components and record wall time, peak PSS, thermal state, power,
  cancellation, background continuation, foreground FrameTimeline, and
  playback underruns. These measurements become the comparison baseline for
  every NPU claim.
- [x] Record the current downloaded CPU-loader support evidence for S10 arm32,
  S25 arm64, API 26 pure x86, and API 37 x86_64.
- [ ] Close the Phase 1 ABI/API support table by adding API 29 evidence or
  explicitly marking it unavailable, and assign non-arm64 rows a loader-only
  or ordinary-player tier. NPU work may target only a named qualified arm64
  device/model/runtime tuple.

**Pre-Phase 5 exit:** the release graph is TFLite-only and runtime-download
only; capability flags tell the truth; Quick Setup cannot let an optional
backend block or mutate the required path; GPU intent survives backup/restore;
the downloaded CPU/GPU lifecycle and clean-install flows pass on the claimed
support rows; Quick Setup and Runtime Management have stable UI/accessibility
coverage; and S10/S25 provide a repeatable full-song CPU/GPU baseline against
which an exact NPU claim can be measured. Unqualified API/ABI rows remain
loader-only or disabled, and the frozen window-decoding policy remains
unchanged.

### Phase 5: Add exact vendor NPU AOT variants

**Status: not started; product implementation is blocked by the open
pre-Phase 5 product-baseline gate.**

This phase initially targets only an already qualified MDX two-stem artifact,
one named Qualcomm device/SoC tuple, and one exact LiteRT/QNN toolchain. The
official HTDemucs 4/6-stem and guitar-ft artifacts remain outside this phase:
their completed CPU-only multi-stem product path does not grant vendor NPU
support, and their representative-content PCM16 attribution remains owned by
the multi-stem roadmap.

- [ ] Freeze a vendor-neutral AOT catalog schema and Qualcomm implementation.
- [ ] Package shared vendor runtime files separately from model-specific AOT
  artifacts.
- [ ] Require exact model/runtime/device matching before recommending NPU.
- [ ] Install the base TFLite model together with the AOT variant.
- [ ] Add NPU enablement to Runtime Management and Quick Setup's recommended
  final settings.
- [ ] Validate numerical output, full-song audio, setup time, PSS, thermal,
  power, cancellation, process death, and fallback on local and App Live
  Qualcomm devices.
- [ ] Prove that one SoC family's AOT artifact is rejected rather than guessed
  compatible on another generation.
- [ ] Keep MediaTek or another vendor unavailable until its own provider,
  package, device selector, licensing, and validation rows pass the same gates.

**Phase 5 exit:** Quick Setup can prefer one exact AOT path without installing
JIT or weakening CPU/GPU fallback.

### Phase 6: Add QNN JIT as an on-demand runtime

QNN JIT is not a general fallback for an unsupported graph. A provider-ready
state, non-empty IR, graph finalization, actual delegated execution, and output
parity are separate gates. The recorded Demucs VTCM allocation failure stopped
before model creation, so option-only retries of that graph are closed.

- [ ] Publish an immutable QNN JIT component with exact provider, backend, HTP,
  ABI, API, SoC, license, and dependency inventory.
- [ ] Expose JIT installation only in Runtime Management and model guidance,
  not as a duplicate model or automatic AOT companion.
- [ ] Add first-compile state, storage estimate, compile cancellation, versioned
  compilation-cache identity, and explicit cache cleanup.
- [ ] Route an enabled NPU model without AOT through JIT only after provider and
  actual delegation evidence pass.
- [ ] Suppress repeated guidance for the same model/catalog/runtime fingerprint.
- [ ] Test unsupported SoC, provider-ready CPU fallback, empty QNN IR, partial
  delegation, process death during compile, low disk, update, and removal.

**Phase 6 exit:** advanced users can opt into JIT for models without AOT, while
recommended setup continues to prefer exact AOT and never claims unverified
delegation.

### Phase 7: Complete persistence, backup, repair, and model integration

- [ ] Extend the pre-Phase 5 versioned `gpuEnabled` backup contract with
  `npuEnabled`; do not reintroduce `tryGpu` as canonical product state.
- [ ] Verify restore with no runtime, missing active model, another valid active
  model, unsupported NPU, and unavailable AOT without automatic downloads.
- [ ] Add installed-model and AOT details, storage use, runtime dependencies,
  and JIT guidance to Model Management.
- [ ] Add scoped repair for corrupt manifests, missing inner files, stale
  staging, broken dependencies, and pending deletion.
- [ ] Verify system clear-cache removes generated separation and JIT data but
  leaves runtime and model inventory valid.
- [ ] Verify clear-app-data and uninstall remove all runtime, model, AOT, cache,
  preference, and prompt state.
- [ ] Complete localization and accessibility coverage for all states, reasons,
  progress, and destructive actions.

**Phase 7 exit:** resource facts, user intent, transient work, and backup data
remain distinct through restore, repair, cache clearing, and process recreation.

### Phase 8: Release qualification

- [ ] Build split and universal GitHub APKs from a clean runner and verify no
  ONNX Runtime, native LiteRT, GPU, QNN, or vendor payload is accidentally
  packaged.
- [ ] Verify the final GitHub graph contains only GitHub delivery adapters and
  that common product code has no Play Core, AI Pack, or F-Droid packaging
  imports.
- [ ] Run provider contract tests with HTTP and deterministic fake providers,
  including platform-managed install/remove and unsupported-operation states,
  without implementing a store release.
- [ ] Verify every catalog URL, Release tag, manifest, hash, license, and notice
  from a clean networked device.
- [ ] Run the full bootstrap, repair, recommended restore, runtime update,
  model switch, optional-backend opt-out, process death, and cache-clear matrix.
- [ ] Publish one support row per ABI/API/model/backend/runtime/AOT scope and
  keep missing evidence unavailable rather than inferred.
- [ ] Repeat representative full-song listening and digital comparisons without
  changing the frozen window-decode policy.
- [ ] Record APK size, each optional download, installed size, peak PSS,
  first-ready time, full-song time, thermal state, power, and foreground frame
  behavior separately.
- [ ] Verify that a GitHub-only build remains useful as an ordinary music player
  when the runtime host is unreachable or every optional component is removed.

**Phase 8 exit:** the release APK has a small fixed runtime footprint, fresh
setup is one reviewed flow, optional local resources remain independently
manageable, and every enabled execution path is traceable to immutable
artifacts and device evidence.

## Validation Matrix

At minimum, exercise these independent dimensions rather than deriving one
from another:

| Dimension | Required coverage |
| --- | --- |
| Setup mode | internal bootstrap/repair/restore plans; current product restore entry; automatic no-runnable trigger |
| Resource state | absent, valid, corrupt, partial, outdated, pending activation, pending deletion |
| Delivery | GitHub HTTP, deterministic fake, platform-managed fake, unsupported operation |
| Backend | CPU, bounded GPU, exact NPU AOT, QNN JIT, typed fallback |
| Preference | GPU/NPU enabled and disabled independently; change during active run |
| Model | recommended, valid custom, no AOT, exact AOT, mismatched AOT, missing active reference |
| Process | cold start, warm start, inference death, main-process death, app update, force-stop |
| Network/storage | offline, interrupted, bad hash, low disk, cache cleared, app data cleared |
| ABI/API | arm64, arm32, x86_64, x86; API 26, representative middle API, current API |
| Device | S10, S25, qualified App Live Qualcomm rows, later vendor-specific rows |
| UI | auto prompt, manual entry, cancel, retry, partial result, no prompt loop, accessibility |

Performance comparisons use at least three cold repetitions when they inform a
support or recommendation decision. Record all samples and medians. Actual
backend/provider evidence is mandatory; successful output alone cannot prove
GPU or NPU delegation.

## Commit Strategy

Keep commits small, buildable, and specific. Recommended sequence:

1. roadmap and contract authority;
2. delivery interfaces and initial GitHub capability policy;
3. GitHub runtime/model providers;
4. classes-only API dependency and process bootstrap;
5. runtime catalog/store and CPU installer;
6. Runtime Management CPU UI;
7. readiness evaluator;
8. Quick Setup CPU/model orchestration;
9. bounded GPU component and canonical preference cutover;
10. ORT retirement and clean APK inventory baseline;
11. deterministic provider/executor tests and Quick Setup transaction repair;
12. CPU/GPU lifecycle, backup, clean-install, and S10/S25 baseline closure;
13. AOT schema and first Qualcomm path;
14. on-demand QNN JIT; and
15. NPU persistence, repair, localization, and release qualification.

Use concrete commit scopes such as `litert-runtime`, `runtime-manager`,
`quick-setup`, `npu-aot`, `qnn-jit`, `backup`, or `runtime-release`; do not use a
generic `separation` scope for these changes.

## Overall Completion Criteria

This roadmap is complete only when:

1. the base APK contains no ONNX Runtime or downloadable native runtime payload,
   and ordinary music playback works without local separation resources;
2. CPU, GPU, and each enabled NPU path install from immutable verified assets;
3. the inference process loads one absolute CPU runtime identity before any
   LiteRT API initialization and never hot-swaps it;
4. Quick Setup is the only automatic destination for an unready local path and
   completes a recommended setup through one reviewed action;
5. Runtime Management owns all runtime install/remove and GPU/NPU preferences,
   while Model Management owns models, profiles, active selection, and AOT;
6. GPU and NPU opt-outs persist, restore safely, and never change an admitted
   run because of foreground/background state;
7. exact AOT is preferred over JIT, and a model without AOT remains usable on
   CPU/GPU with nonblocking JIT guidance;
8. corrupt resources can be repaired without deleting unrelated valid models,
   runtimes, or caches;
9. runtime/model facts are reconstructed from verified files while backups
   contain only portable user intent and references;
10. every release-enabled ABI/model/backend tuple has immutable artifact,
    numerical, lifecycle, resource, UI, and device evidence; and
11. common repositories, readiness, Quick Setup, management UI, and admission
    depend only on the three delivery/capability boundaries, while the shipped
    product implements only the GitHub channel and does not claim store feature
    parity.
