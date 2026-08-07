# Source-Separation Contract and Multi-Stem Data-Plane Roadmap

Status: active implementation roadmap and design authority for stem identity,
model contracts, imported metadata, cache output shape, and playback data flow.

Updated: 2026-08-06

Current milestone: Phases 0-2 and the Phase 3 bounded two-stem data-plane
implementation are complete. Phase 4A now provides an ordered 2/4/6/8-stem
engine, list mixer, service session handle, and structural buffer admission.
The deterministic indexed-WAV/FLAC seek smoke still passes on all four ABI rows,
and the synthetic eight-stem engine has completed 30-minute S25/S10 arm64
throughput, seek, thermal, PSS, and cache-stability qualification. Two-stem
real-song playback and the separate process-recreation, background,
cache-deletion, and active-model product-state gates are complete. The real
multi-stem repository/`PlaybackService` recreation gate stays deferred until
Phase 5 supplies its contract and cache snapshot; no real multi-stem model is
admitted until its own pipeline and full-song gates pass.

This roadmap prepares Booming SS for more than two rendered stems while
preserving the currently qualified MDX two-stem product path. It combines the
following previously separate requirements:

- model repositories provide canonical English stem labels;
- the application resolves known labels to localized resources and falls back
  to the original label;
- imported models can carry user-entered labels;
- contracts describe output semantics rather than relying on filenames;
- separation, cache, and playback use a variable-size stem set without relying
  on persistent full-song PCM Hydration; and
- model catalog tiers and model-purpose groups remain independent from labels.

This document does not design the visual controls for a multi-stem mix. It
defines the data and execution contracts those controls will consume.

## Authority and Boundaries

This roadmap is authoritative for:

- logical stem identity and canonical labels;
- the portable model-contract shape for two-stem and multi-stem pipelines;
- imported sidecars and custom profile metadata;
- separation result and cache representations;
- streaming decode, playback-session, and multi-stem audio data interfaces; and
- the prerequisites for exposing generic model families in the catalog.

The following documents remain authoritative for their separate concerns:

- [Downloadable Runtime, Quick Setup, and Local Resource Management Roadmap](litert-runtime-setup-roadmap.md) for runtime delivery, installation, and
  backend enablement;
- [Source-Separation Lifecycle and Cache Correctness Roadmap](source-separation-lifecycle-cache-correctness-roadmap.md) for active-model changes,
  task cancellation, exact-active-cache playback, retention, and deletion;
- [LiteRT and Multi-Preset Roadmap](litert-multi-preset-roadmap.md) for release
  tiers, catalog publication, and model download policy; and
- [LiteRT Inference Process and Background Execution Roadmap](litert-inference-process-roadmap.md)
  as frozen evidence for process isolation and background ownership.

If an older document assumes exactly two output files or uses a localized
label as an identity, this roadmap supersedes that data-shape assumption. The
active-model, cache-retention, deletion, and source-window-decoding rules do
not change here.

All implementation and validation starts from clean application data. No
migration of unreleased model, profile, cache, hydration, or playback-setting
data is required.

## Evidence and Decision Boundary

The latest `MusicSourceSeparation` branch (`experiment/s10-parallel-istft`, tip
`7ac9603`) justifies preparing generic N-stem contracts and playback, but it
does not justify shipping a Demucs preset. The closure matrix is:

| Candidate or experiment | Evidence | Product decision |
| --- | --- | --- |
| Official HTDemucs 6-stem | Host pipeline passed; S25 CPU 180-second E2E RTF `0.555`; peak total PSS about `1.128 GiB`; strict per-stem device gate failed | CPU offline/producer-ahead research only |
| Official HTDemucs 4-stem | Host pipeline passed; S25 CPU 180-second E2E RTF `0.617`; strict per-stem device gate failed | CPU offline/producer-ahead research only |
| Official 6/4-stem GPU experiments | Only a small GPU+CPU neural-core hybrid was delegated; no canonical GPU E2E audio batch ran; memory and latency were worse | No Demucs GPU product claim |
| HTDemucs 6-stem guitar-ft | S25 diagnostic mean E2E RTF `0.6783`; listening showed useful guitar/piano behavior, but the frozen host EOF gate was `79.245 dB` versus `80 dB` and training-data terms need review | Diagnostic/research only, not admitted |
| Four-stem Batch 4A | Official base selected as the research baseline; the full specialist bag and hybrids had no repeatable listening advantage; Psytrance was clearly worse | Do not add a bag or Psytrance preset |
| S10 parallel iSTFT | Four outer workers were raw-FP32/PCM-hash equivalent to serial; E2E RTF improved to `1.292`/`1.382`/`1.310` for official 6s/4s/guitar-ft, but stayed above real time | Offline research optimization only |
| QNN | Non-empty IR was produced, but VTCM scheduling failed before model creation and no NPU inference occurred | Do not retry this unchanged graph or infer NPU support |

The complete reports and provenance limits are maintained in the research
repository. In particular, the official four-stem report has unresolved
source/runtime provenance placeholders, the official six-stem report records a
dirty source tree, and the guitar-ft diagnostic lacks a resolved source
identity. These bounded research reports must not become release evidence by
extrapolation. A two-second or neural-core-only result is not a canonical
full-window or full-song qualification.

The frozen reports are maintained in the
[`WluhWluh/MusicSourceSeparation`](https://github.com/WluhWluh/MusicSourceSeparation)
research repository:

- `docs/android-litert-demucs-multistem-feasibility-2026-08-03.md`;
- `docs/android-litert215-demucs6-s25-2026-08-03.md`;
- `docs/android-litert215-demucs6-canonical7p8-host-2026-08-04.md`;
- `docs/android-litert215-demucs6-canonical7p8-s25-2026-08-04.md`;
- `docs/android-litert215-demucs4-official-s25-2026-08-05.md`;
- `docs/htdemucs4-batch4a-host-quality-2026-08-05.md`;
- `docs/htdemucs6-guitar-ft-litert-s25-diagnostic-2026-08-05.md`; and
- `docs/android-litert215-demucs3-s10-parallel-istft-2026-08-05.md`.

The reports are experimental evidence, not product contracts. In particular,
the two-second RTF must not be extrapolated to the canonical 7.8-second
workload, and a finite or partially delegated output is not a quality pass.

## Frozen Design Principles

1. A stem has three separate identities: a stable machine ID, a semantic ID,
   and a human label. None may be substituted for another.
2. The repository stores canonical English labels only. Android resource IDs,
   localized text, and translation policy never enter a model artifact
   contract.
3. Labels are presentation metadata. They are not cache keys, filenames,
   tensor bindings, or output-direction rules.
4. Output direction comes from an explicit contract binding. The application
   must never infer it from a label, model filename, or tensor position alone.
5. A pipeline-specific adapter owns DSP and reconstruction. MDX residual
   subtraction and HTDemucs multi-output reconstruction must not be represented
   as one collection of nullable DSP fields.
6. A model execution produces one coherent stem set. A cache window is not
   ready until all required stems for that window are committed atomically.
7. A model switch invalidates old work and playback adoption but preserves the
   old model's cache according to the lifecycle roadmap.
8. Large tensors and PCM never cross Binder as ordinary payloads. The
   inference process writes or references files and returns bounded metadata.
9. The existing source-window decode strategy and manually qualified MP3
   fallback boundaries remain unchanged.
10. The initial product data plane may support a bounded number of stems, but
    the wire contract must not encode a permanent assumption of exactly two,
    four, or six.

## Current Implementation Hotspots

The first inventory must explicitly cover these known two-stem assumptions:

- `SourceSeparationModelContract.kt` and `MdxContractExecutionProfile.kt`;
- `MdxStemOutputMapper.kt`, `MdxRangeSeparator.kt`, and `MdxRangeTiming.kt`;
- `SourceSeparationSegmentPlan.kt` and the cache run journal;
- `SourceSeparationCacheManifest.kt` and
  `SourceSeparationModelAwareCacheRepository.kt`;
- `SourceSeparationExecutionHost.kt` and its independent-process descriptors;
- `SourceSeparationMixAudioProcessor.kt`; and
- `PlaybackService.kt`, custom-profile import, model details, and their tests.

The current v2 JSON field `stemContract.*.displayLabel` should remain the wire
field for existing contracts. Its documented meaning becomes canonical source
label, and the application may expose it internally as `canonicalLabel` without
adding a second serialized label field. A field rename or a new multi-stem
contract kind must be versioned explicitly; it must not silently reinterpret a
v2 sidecar.

## Target Contract Model

### Logical stem descriptor

The normalized application model should contain a list similar to:

```text
StemDescriptor
  stemId: stable lower-case ID unique within the contract
  semanticId: extensible semantic ID, such as vocals, guitar, or piano
  canonicalLabel: repository English label or exact user-entered label
  order: logical output order
```

`stemId` is the primary key for cache files, playback sources, gain state, and
IPC descriptors. It is deliberately separate from `semanticId`: two future
models may contain two related vocal stems, and a user may edit a label without
changing the numerical output.

The current closed `ContractStemSemantic` enum can remain an adapter for the
existing MDX contracts, but the normalized multi-stem domain should accept a
validated semantic ID string. Known semantic IDs can expose typed constants;
unknown reviewed or imported IDs must still round-trip and remain usable as
labels. The application may impose a product maximum, initially recommended
as eight playable stems, without making eight a wire-schema requirement.

### Contract layers

Do not overload the current MDX-only `TensorContract` and `ContractDsp` with
HTDemucs-specific optional fields. Introduce a new contract kind or schema
revision with these logical sections:

```text
common model identity and artifact
tensorContract
  inputs: list of named static tensors with dtype, shape, and axes
  outputs: list of named static tensors with dtype, shape, and axes
stemContract
  stems: ordered list of StemDescriptor
  outputBindings: tensor-to-stem packing and axis mapping
  derivation: direct, residual, or pipeline-native reconstruction
pipelineContract
  pipeline ID and version
  input preparation and DSP parameters
  window, padding, overlap, and render rules
```

The existing two-stem MDX contract normalizes to:

- one direct model-output stem;
- one derived residual stem;
- an explicit `mixture-minus-scaled-model-output` rule; and
- the existing MDX STFT pipeline.

An HTDemucs contract normalizes to a direct multi-stem set. Its waveform and
frequency outputs bind to the same ordered stem IDs and are combined by the
HTDemucs pipeline adapter. They are two tensor branches, not two independent
sets of final stems.

Every binding must state the tensor index or name, axes, stem axis (if any),
packing mode, and exact ordered stem IDs. Static shapes remain mandatory for
the first product implementation. Cross-field validation must reject:

- duplicate stem IDs or ambiguous output bindings;
- a stem axis whose length differs from the declared stem set;
- inconsistent stem order between two neural-core output branches;
- a derived stem with no declared source or reconstruction rule; and
- an artifact whose tensor contract is valid structurally but has no supported
  pipeline adapter.

Runtime qualification remains outside the model contract. CPU, GPU, QNN,
precision, ABI, and device evidence belong to catalog/runtime qualification
records keyed by exact artifact, contract, pipeline, and runtime profile.

### Identity and labels

The canonical execution fingerprint includes tensor, DSP, pipeline, stem IDs,
semantic IDs, output bindings, order, and derivation rules. It excludes:

- canonical or localized display text;
- source URLs and attribution;
- conversion provenance; and
- backend timing and diagnostic records.

The complete canonical label remains in the contract snapshot and cache
manifest for inspection. A custom profile revision remains a separate identity
even when changing only presentation metadata, so an existing cache never gets
silently reinterpreted by an edited profile.

## Application Label Resolution

Add one application-owned resolver for every user-visible stem name:

```text
resolveStemLabel(context, canonicalLabel): String
```

Resolution rules:

1. Preserve the original string for storage and fallback display.
2. For lookup only, trim, collapse internal whitespace, and case-fold with
   `Locale.ROOT`.
3. Use an explicit, reviewed map from English labels to Android string
   resources. Do not use `Resources.getIdentifier()` or fuzzy translation.
4. Support reviewed aliases only when their meaning is unambiguous.
5. Return the original label when no mapping exists.

The initial resource family should include `Vocals`, `Instrumental`, `Bass`,
`Drums`, `Other`, `Reverb`, `No Crowd`, `Guitar`, `Piano`, `Target Stem`, and
`Remaining Audio`. New model-specific labels such as `Lead Vocals` or
`Backing Track` remain unchanged until an intentional translation is added.

Localized text is computed at presentation time only. It must not be written
to contract files, cache identities, cache manifests, backup payloads, file
names, or IPC identity fields. Model details, cache management, progress,
timing reports, and playback diagnostics all use the resolver.

The two-stem MDX endpoints should be sourced from the active contract, so HQ4
can correctly present `Instrumental` as the model output and `Vocals` as the
residual. The original-audio label remains a separate generic application
resource.

## Imported Models and Sidecars

The import pipeline remains:

1. known artifact hash and built-in contract;
2. hash-bound adjacent sidecar; then
3. an advanced custom profile.

Every imported multi-stem sidecar must provide the complete stem list, labels,
tensor bindings, pipeline ID/version, DSP, output derivation, and artifact
identity. Labels alone are never enough to activate a multi-input or
multi-output graph.

For a simple two-stem custom model, the manual profile may initially expose:

- model-output semantic;
- model-output label;
- residual semantic;
- residual label; and
- existing tensor/DSP fields.

For a generic or multi-stem model, manual entry should not attempt to recreate
all tensor axes and DSP rules in a casual form. Require a sidecar generated by
the conversion script, or keep the model installed-but-download-only until a
validated adapter exists.

Manual label rules:

- allow Unicode user input;
- require every label to be nonblank and distinct after normalization;
- reject control characters and unsafe path characters;
- preserve the exact entered text;
- show a localized preview when an explicit mapping exists; and
- retain the permanent quality-unverified warning.

Editing an imported profile creates a new revision. It never mutates the
revision referenced by a running job or existing cache. Import and metadata
editing never activate a model; `Use` remains an explicit operation.

## Separation Data Plane

### Pipeline adapter boundary

Introduce a narrow boundary between model inference and audio reconstruction:

```text
SourceSeparationPipeline
  validateContract()
  createWindowPlan()
  prepareInputs(decodedPcm)
  runInference(boundInputs)
  reconstructWindow(outputs)
  renderStemChunks()
```

The current MDX implementation remains one adapter and keeps its tested decode,
STFT, compensation, residual, and window behavior. A future HTDemucs adapter
owns waveform input, host STFT input, frequency/time branch combination,
iSTFT, target length, and triangular overlap-add. No MDX class should learn
Demucs-specific branches through conditionals.

Inference result types become collections:

```text
SeparationWindowResult
  stems: list of StemChunk

StemChunk
  stemId
  frame range
  PCM/output artifact
```

The scheduler still admits one model run at a time. One forward that produces
six stems is one inference window, not six independent tasks. A window commit
must atomically cover every expected stem. Cancellation, process death,
resume, and model supersession operate at the window boundary.

The LiteRT session boundary must support named lists of static inputs and
outputs. It should expose views into packed output tensors where possible and
write each stem promptly, rather than copying an entire full-song N-stem array.
The process must release output and scratch buffers after each window.

For the canonical HTDemucs workload, the contract must record that attention
and activation memory do not scale linearly with window duration. A 2-second
smoke pass cannot admit a 7.8-second product run. S25 CPU is the first gate;
S10, GPU, and QNN require independent evidence.

### Cache and journal shape

Replace fixed fields such as `vocalsPath` and `instrumentalPath` with a list of
stem artifacts keyed by `stemId`. The manifest must:

- accept a validated non-empty stem list rather than `stems.size == 2`;
- preserve the complete contract and canonical-label snapshot;
- record order, role, format, sample rate, frame count, and integrity per stem;
- require all expected stems for a segment before marking it playback-ready;
- commit or roll back a segment as one set; and
- include ordered stem IDs and pipeline derivation in the cache fingerprint.

Use stable ordinal paths such as `stems/00.wav` and
`segments/0001/stem-00.wav`. Never derive paths from localized or arbitrary
user labels. The manifest maps each path back to its stem ID.

FLAC promotion, deletion, pruning, and total-byte accounting must iterate over
the set. A song/model cache remains one retention entry, while its size
naturally grows with stem count. Persistent full-song PCM Hydration is not part
of the target product path. Any temporary decoded-block data remains bounded
cache data and may be removed by system clear-cache or explicit cache cleanup.

### Model switching

The existing lifecycle rules remain unchanged and apply to the complete stem
set:

- activating model B stops or supersedes admitted model A work at a safe
  window boundary;
- A's complete and partial stem sets remain under A's exact cache identity;
- B may only resume or play a cache whose model, artifact, profile, pipeline,
  and stem binding all match B; and
- delayed A callbacks cannot publish B progress, playback, or readiness.

No individual stem may be borrowed from another model's cache merely because
its semantic or label matches.

## Result Playback Data Plane

The playback cache object and playback session expose an ordered list of
`PlaybackStemSource` values instead of two file properties. The list is
validated as a complete set before a session is installed. Phase 3 removed
persistent Hydration markers and full-song PCM files; bounded decoded blocks
are session memory only and are not cache artifacts.

Retain Media3/ExoPlayer, `MediaSession`, `PlaybackService`, the existing queue,
and the original song as the single transport clock. All separated stems are
synchronized side inputs. Do not select Instrumental as an implicit base
stream; that assumption does not exist for Bass/Drums/Guitar/Piano models.

The Phase 3 ownership boundary deliberately reuses the existing service and
processor rather than adding a pass-through coordinator class:

```text
PlaybackService / MediaSession
  - queue, focus, transport and separation policy
  - asynchronous input preparation and readiness monitoring
        |
SourceSeparationMixAudioProcessor
  - engine/session adapter
  - realtime gain ramp and two-stem mixing
        |
SourceSeparationStemPlaybackEngine
  - immutable ordered stem session
  - bounded decode and prefetch
  - logical-frame seek and hot-swap barriers
  - transport epoch and reusable block pool
        |
background stem sources/readers

SourceSeparationMixAudioProcessor -> DefaultAudioSink / AudioTrack
```

The engine owns one bounded decode worker shared by all stems. A second worker
is not introduced without S10 evidence. It decodes one logical FLAC block for
the complete stem set, publishes that block set atomically, and retains
reusable PCM blocks in a bounded ring. Phase 3 uses a three-block resume
waterline, a separate one-block ordinary-seek resume waterline, an eight-block
target, and a twelve-block pool. At 44.1 kHz one 4096-frame block covers about
92.9 ms, so even twelve six-stem blocks require only about 1.125 MiB of PCM.

`queueInput()` may only consume an already published block set. It must never
open files, decode FLAC, wait on a `Future`, grow a buffer, construct tracing
strings, or synchronously repair an underflow. A low-water event is reported
once to the service monitor; playback pauses through an explicit buffering
state and resumes once after the target waterline is restored. Hysteresis must
prevent the earlier pause/resume loop.

Seek, song change, model change, process recreation, and source hot-swap each
advance a transport epoch. A seek coalesces obsolete requests, decodes the
target block set first, positions every stem by the same logical frame, and
resumes only after the complete set is ready. Hot-swap uses the Media3 logical
position, never the minimum physical reader pointer.

The mixer accepts an immutable stem set and one atomic per-stem gain snapshot
per output block. It uses floating-point or wide accumulation and clamps once.
The current two-stem blend curve and audible output remain compatibility
requirements until a separate listening-qualified change; short gain ramps may
remove clicks without changing steady-state gain values.

Keep separate compressed per-stem cache files for inspection, export, deletion,
and recovery. Do not persist full-song PCM by default. A small in-memory recent
block LRU or a bounded session-scoped decoded cache may be tested only if
repeated-seek evidence requires it. A full-song temporary PCM fallback is a
last-resort device policy, must never become durable cache state, and requires
explicit low-end-device evidence.

Newly promoted caches use one `.flac.idx` contract and must open on the indexed
path. Missing or invalid index data is an explicit cache error, not permission
to decode an entire song into Java heap. The embedded FLAC SEEKTABLE may later
replace the sidecar after equivalent corruption, seek, and recovery coverage.

Normal playback admission trusts locally promoted artifacts after checking
identity, path, size, and audio geometry. Full-file hashes belong to promotion,
recovery after suspicious metadata changes, and explicit repair. Indexed FLAC
reads must instead validate frame bounds and CRCs before repeated full-open
hashing is removed.

Per-song mix state must become a cache-keyed map or ordered list keyed by
`stemId`, not a single vocals/instrumental float. It remains cache-local and is
not included in backups.

## Catalog and Model Classification

Catalog presentation must never infer a model family or target from a filename
or localized label. Store these as independent metadata:

- `modelFamily`: for example MDX-Net or HTDemucs;
- `purpose` or `semanticId`: vocals, instrumental, bass, drums, and so on;
- `supportLevel`: recommended, experimental, or download-only; and
- `activationPolicy`: the actual selectable/download-only rule.

The recommended section may contain several representative models later, but
generic target models remain download-only until the neutral contract, cache,
playback, and full-song gates pass. A model can have a reviewed contract and
localized labels while still being blocked from activation.

The folded catalog UI can be implemented after the contract and data-plane
phases. Its collapsed rows should consume precomputed category metadata; they
must not perform semantic inference from display text.

## Phased Work Plan

### Phase 0: Freeze the normalized domain

- [x] Inventory every two-stem field in execution, cache, Hydration, IPC,
  playback, diagnostics, and tests.
- [x] Freeze `StemDescriptor`, stable `stemId`, extensible semantic ID, and
  canonical-label rules.
- [x] Decide the new contract kind/schema and the normalization from current
  MDX v2 contracts.
- [x] Freeze direct, residual, and pipeline-native derivation types.
- [x] Add bss-tflite contract-policy tests for English canonical labels and
  application normalization tests for ordered output bindings.

**Exit:** the contract can describe current 9662, HQ4, and KARA without
localized text and can describe a 4/6-stem output without pretending it is an
MDX residual pair.

Phase 0 evidence:

- Booming SS GitHub unit tests cover v2 normalization, reversed HQ4 output,
  six pipeline-native stems, unknown semantic IDs, strict catalog parsing,
  custom profiles, and presentation-independent cache fingerprints.
- `bss-tflite@c19e17a` enforces canonical English labels in conversion code and
  schema, with all 30 repository tests passing.

### Phase 1: Dynamic labels on the stable two-stem path (complete)

- [x] Implement the explicit label resolver and base/localized resources.
- [x] Drive settings endpoints, model details, cache details, progress, and
  timing from the active or cached contract.
- [x] Add model-output and residual label fields to manual import and profile
  editing.
- [x] Preserve exact labels in profile and cache snapshots.
- [x] Test 9662, reversed HQ4 orientation, KARA, unknown English labels, and
  user-entered non-English labels.

**Exit:** no user-visible track name is supplied by a fixed
Vocals/Instrumental string when a contract is available.

Phase 1 evidence:

- `SourceSeparationStemLabelResolver` uses an explicit reviewed map across all
  37 existing Booming SS locales and preserves unknown labels exactly.
- Settings endpoints, model details, cache details, and timing reports resolve
  labels from active or cached contracts; HQ4 remains semantically ordered as
  vocals/instrumental despite its reversed model-output direction.
- Manual profiles preserve editable Unicode model-output and residual labels,
  include them in content-addressed profile revisions, and snapshot them into
  cache contracts without changing presentation-independent cache fingerprints.
- Focused GitHub unit tests pass for 9662, KARA, HQ4, unknown labels, Unicode
  profile labels, contract validation, cache identity, and cache-management
  projection.

### Phase 2: List-based execution and cache foundations (complete)

- [x] Introduce list-based window results, segment artifacts, journals, IPC
  descriptors, and playback cache objects while keeping the MDX adapter's
  output unchanged.
- [x] Add explicit stem role/binding data where semantic IDs are insufficient.
- [x] Bump the affected identity, manifest, journal, Hydration, and IPC schemas
  under the clean-install boundary; do not migrate old experimental entries.
- [x] Make segment commits, cancellation, recovery, promotion, deletion, and
  pruning operate on complete stem sets.
- [x] Add synthetic 2-, 4-, and 6-stem cache fixtures.

**Exit:** the current two-stem product passes all existing lifecycle and cache
tests through the list-based data types.

Phase 2 evidence:

- Transient window results, segment plans, rendered outputs, run journals,
  the former Hydration markers, playback sources, and IPC
  preparation/resume/completion descriptors carried ordered stem collections
  keyed by stable `stemId` at the Phase 2 checkpoint.
- Cache identity schema 2, manifest schema 4, journal schema 7, the former
  Hydration schema 2, and execution protocol 17 intentionally rejected older
  unreleased data under the clean-install boundary. Phase 3 subsequently
  removed the Hydration schema instead of migrating it.
- Completed, promoted, formerly hydrated, and per-segment files used stable
  ordinal paths. Readiness and journal commit fail when any expected artifact
  is absent, aliased, reordered, or has inconsistent audio geometry.
- Synthetic 2-, 4-, and 6-stem tests cover window PCM, segment paths, atomic
  commit/integrity, deletion, the former Hydration serialization, and IPC
  serialization. The existing MDX execution adapter still accepts exactly
  vocals and instrumental while resolving files by ID.
- At the Phase 2 checkpoint the full GitHub debug unit-test suite passed,
  including cache, FLAC/Hydration, engine lifecycle, and process IPC coverage.
  HQ4 verified contract order `instrumental, vocals` while standard playback
  still resolved the correct semantic files. GitHub Android-test sources also
  compiled with list-based device evidence and export keys.

### Phase 3: Rebuild the separated-playback data plane

Status: complete. The functional two-stem data plane, real-song and percentile
exits, and separate process/lifecycle product-state gates are closed.

#### Phase 3A: Freeze the realtime contract and baseline

- [x] Align promotion and playback on the single `.flac.idx` contract and add a
  real WAV-to-FLAC-to-promoted-cache regression that rejects whole-song mixer
  fallback (`3e8a7bb5`).
- [x] Record current two-stem WAV, indexed-FLAC, seek, pause/resume, model
  switch, and process-recreation output as compatibility fixtures.
- [x] Add structured metrics for decode-block p50/p95/p99, ring occupancy,
  low-water events, underruns, seek readiness, audio-thread time, allocations,
  file descriptors, and session epochs.
- [x] Freeze explicit session states for preparing, buffering, ready, seeking,
  hot-swapping, ended, and failed. One epoch may publish at most one active
  complete stem set.
- [x] Freeze the mapping from the original-source transport position to logical
  stem frames, including sample rate, channel count, exact frame count, delay,
  padding, and any required timing metadata. Reject unsupported geometry before
  session installation.
- [x] Freeze the realtime rule: the Media3 audio thread performs no file I/O,
  FLAC decode, hashing, executor waits, dynamic allocation, or contended lock
  acquisition.

**3A exit:** existing two-stem behavior has repeatable audio and timing oracles,
and every forbidden realtime operation is observable in tests.

#### Phase 3B: Introduce the engine boundary and bounded PCM transport

- [x] Add the frozen data-plane contract, `SourceSeparationStemPlaybackEngine`,
  immutable sessions, atomic PCM block sets, and a reusable block pool. Keep
  coordinator duties at the existing `PlaybackService`/processor boundary
  instead of adding a pass-through coordinator class.
- [x] Keep `PlaybackService` responsible for MediaSession, queue, focus,
  transport position, separation policy, and one current engine handle; move
  stem files, readers, prefetch, and buffer ownership out of the service.
- [x] Start with one dedicated decode worker and a bounded latest-command slot
  that coalesces obsolete seeks; qualify a second worker only if S10
  measurements improve without reordering or excess contention.
- [x] Publish one complete same-frame block set atomically. Never expose one
  stem from a block before every required stem is decoded and validated.
- [x] Use a three-block normal resume waterline, a one-block seek-resume
  waterline, an eight-block target, and a bounded twelve-block pool. Make the
  values measurable and bounded, not user-facing settings in the first
  implementation.
- [x] Convert `SourceSeparationMixAudioProcessor` into a consumer of ready PCM
  blocks. Remove direct `RandomAccessFile`, FLAC-reader, and scratch-growth
  ownership from `queueInput()`.

**3B exit:** the existing two-stem WAV path plays through the engine with
bit-identical or explicitly bounded output differences and no realtime file
access.

#### Phase 3C: Move indexed FLAC entirely off the audio thread

- [x] Open and validate FLAC metadata and indexes before installing a ready
  session. Decode target and ahead block sets only on the bounded workers;
  `PlaybackService` entry points must not wait on decoder work or the engine's
  audio-consumer state.
- [x] Reuse decoder scratch and PCM blocks; eliminate per-frame `ByteArray`,
  `Future`, task-result, and trace-string allocation from steady playback.
- [x] Strengthen index validation for monotonic, contiguous, non-overlapping,
  in-file byte spans and exact PCM coverage; validate FLAC header CRC8 and frame
  CRC16 during indexed reads.
- [x] Delete the whole-song Java-heap decode fallback. A missing, truncated, or
  invalid index fails the exact cache and directs the lifecycle layer to repair
  or rerun separation.
- [x] Remove repeated full FLAC/index hashes from normal playback admission.
  Retain full verification for promotion, explicit repair, and recovery after
  suspicious size or metadata changes.
- [x] Evaluate parsing the embedded per-frame SEEKTABLE as a replacement for
  the external index. Retain the now-validated `.flac.idx` sidecar for this
  engine revision; reconsider removal only after long-form qualification,
  without combining a storage-contract change with scheduling validation.

**3C exit:** indexed FLAC playback and seek never synchronously decode on the
Media3 thread and cannot allocate an entire decoded song.

#### Phase 3D: Make transport changes atomic

- [x] Give every start, seek, song change, active-model change, hot-swap,
  process recreation, and cache invalidation a monotonically increasing
  transport epoch.
- [x] Coalesce rapid seeks to the latest target. Decode the target block set
  first, establish the resume waterline, position all stems at one logical
  frame, then resume once.
- [x] Replace physical-reader-pointer hot-swap with a logical-frame barrier.
  Clear all old resampling and decoded-block state before publishing the new
  complete source set.
- [x] Implement `onFlush`, `onReset`, format-change, EOS, and Media3 internal
  seek handling so the engine and transport clock cannot advance separately.
- [x] Route model switching, song switching, manual cache deletion, task
  cancellation, and delayed old callbacks through the same epoch barrier while
  preserving exact-model cache isolation from the lifecycle roadmap.
- [x] Add low-water hysteresis and one-shot readiness notification so an
  underrun cannot create an automatic pause/resume loop.

**3D exit:** seek, switch, recreation, and deletion either install one complete
exact-session stem set or remain explicitly buffered/failed; no mixed epoch or
mixed model can reach output.

#### Phase 3E: Harden mixing and realtime gain control

- [x] Replace independent volatile gain fields with one immutable atomic gain
  snapshot read once per output block.
- [x] Apply short frame-based ramps for gain changes while preserving the
  current two-stem steady-state blend curve and center behavior.
- [x] Accumulate in float or a sufficiently wide integer and clamp once when
  writing PCM16. Do not change the established audible gain law without a
  separate listening comparison.
- [x] Report unequal lengths, unexpected EOF, short reads, missing stems, and
  decode failure explicitly. Do not silently convert structural errors into
  indefinite zero samples.
- [x] Make slider gain updates lightweight engine commands. They must not bump
  playback-context generation, reopen caches, cancel readiness, or rebuild a
  playback session.

**3E exit:** rapid gain changes are click-free and cannot trigger lifecycle
work, while existing two-stem output remains listening-compatible.

#### Phase 3F: Remove persistent Hydration and qualify the two-stem engine

- [x] Stop scheduling full-song PCM Hydration during normal FLAC playback and
  remove product dependence on Hydration markers, pending hydrated files, and
  pointer-based hot-swap fields.
- [x] Under the clean-install boundary, delete obsolete persistent Hydration
  schema and recovery paths once all playback callers use the bounded engine.
- [x] Run the deterministic 100-seek indexed-WAV/FLAC smoke on S25, S10
  arm32, API 26 x86, and API 37 x86_64. It compares every emitted PCM sample
  and requires zero underruns.
- [x] Run at least 30 minutes of real-song FLAC playback and 100 cold/warm
  random seek episodes on S25 and S10. The run includes rapid scrubbing and
  four explicit pause/resume cycles while the real MediaSession and AudioSink
  consume the completed FLAC cache.
- [x] Run the separate product-state gates for process recreation, background
  separation, cache deletion, and active-model changes on the qualified Phase 3
  builds, then rerun any affected path after a gate-driven fix. These
  operations must remain independently observable rather than being inferred
  from a long playback run.
- [x] Require zero normal-path `fallbackWholeFileDecode`, zero mixed-epoch
  output, zero sustained pause/resume loops, and zero audio-thread file/decode
  operations.
- [x] Meet the initial resource targets: aggregate decode throughput above 3x
  realtime, two-stem block-group p99 well below 92.9 ms, FLAC seek-readiness
  p95 below 100 ms on S25 and 200 ms on S10, and bounded memory/file-descriptor
  counts. Revisit numerical thresholds from the recorded baseline rather than
  hiding misses.
- [x] Compile and smoke the final engine on arm64-v8a, armeabi-v7a, x86_64, and
  x86. Performance gates belong to S25 and S10; emulators verify portability,
  lifecycle, corruption, and deterministic PCM output.
- [x] Keep the bounded in-memory recent-block LRU, session-scoped temporary PCM,
  and native-decoder ideas out of the implementation because current random-seek
  evidence does not warrant them. Reopen an option only if a qualified device
  cannot sustain bounded streaming; none may become a persistent fallback.

#### Phase 3 implementation evidence

- The implementation is split across `1ac81924` through `e094a8f2`; persistent
  Hydration removal is isolated in `1214d168`, seek latency and reader reuse in
  `e31a2bba`/`6db16bf8`, and completed-cache handoff in
  `8d00ff2d`/`378928d6`.
- The device smoke generates real WAV and indexed-FLAC stems, performs 100
  deterministic random seeks, compares every emitted PCM sample, and requires
  zero underruns. It passed on S25 arm64-v8a (16.919 s), S10 forced
  armeabi-v7a (22.991 s), API 26 x86 (13.795 s), and API 37 x86_64 (16.345 s).
- The API 37 x86_64 AVD required ART `speed` compilation after its first
  post-install instrumentation launch exceeded the emulator's application
  startup watchdog. The test itself then passed; this is portability evidence,
  not an x86_64 startup-performance qualification.
- The S25 seek path now prefetches before the Media3 seek, reuses the installed
  reader/index, releases after one complete block set, and then fills the normal
  target waterline in the background. Completed WAV-to-FLAC and partial-to-
  completed upgrades are deferred while playback is active and are adopted only
  at an explicit seek, pause, or song transition, avoiding an in-playback stall.
- FLAC promotion now has a stable indexed handoff, completion cannot leave a
  stale `Partial` presentation, and obsolete staging/temporary artifacts are
  removed after the exact entry leases are released. The Current Song cleanup
  indicator is refreshed through the same cleanup events.
- Readiness progress now has one stable wait-episode identity: an initial wait
  cannot repeatedly reset to zero, a later wait begins a fresh episode, and the
  nonblocking 200 ms visual completion may finish without delaying playback or
  suppressing a newer wait.
- The exact-tip real-song qualification ran Coast Town through the real
  MediaSession/AudioSink for 30 minutes on each arm64 device. Each row issued
  100 seek episodes and 120 total requests, including 10 three-request rapid
  scrub bursts, with four pause/resume cycles, zero audio underruns, zero
  sustained non-playing samples, stable cache bytes, and thermal peak 0:

  | row | seek p95 | seek p99 | decode block-group p99 | realtime multiple | PSS peak delta | FD baseline/peak/final |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | S25 arm64 | 56 ms | 59 ms | 2.67 ms | 34.7x | 21,105 KiB | 141 / 142 / 140 |
  | S10 arm64 | 169 ms | 185 ms | 13.61 ms | 6.8x | 25,979 KiB | 110 / 111 / 109 |

  PSS baseline/peak/final was 182,568/203,673/173,343 KiB on S25 and
  159,451/185,430/180,591 KiB on S10. Decode groups are derived from paired
  per-stem indexed-FLAC trace records for the exact run; they are qualification
  evidence, not a new product metric.
- Fresh-process completed-cache recreation passed on S25 and S10. Each new
  process resolved the same exact 9662 cache and opened completed indexed-FLAC
  playback at the start and tail without re-running inference.
- Full Coast Town background continuation passed on S25 and S10 after Home was
  pressed at the first ready window. First-ready/full-song times were
  3,639/119,876 ms and 7,772/219,562 ms respectively, process importance stayed
  at 125, and the exact completed caches remained playable. These rows used the
  persisted CPU backend, so they qualify background ownership rather than GPU
  background execution.
- S25 cache management independently covered inactive deletion, automatic
  pruning, preservation of the exact current cache, and deleting a running
  current cache. The latter canceled its producer and the deleted entry did not
  reappear.
- Active-model switching with playback demand and automatic start enabled
  passed on S25 and on a full-song S10 rerun. The S10 run switched
  9662 -> KARA -> 9662 after the first ready window: the primary retained 2/48
  segments, KARA retained 1/48, both journals recorded
  `Paused/ActiveModelSuperseded`, and the reactivated primary resumed to 48/48.
  A prior 12-second S10 attempt completed naturally before its delayed switch
  assertion and was rejected as an undersized timing fixture, not counted as a
  product failure.
- Completed-cache playback exposed one additional automatic-demand bug: with
  automatic start disabled, changing from a completed 9662 session to a KARA
  partial cache incorrectly treated the recoverable journal as work that must
  block original playback. `13256e6e` limits that active-selection wait to
  automatic demand. The exact-commit S10 MediaSession rerun released the old
  playback lease in 251 ms, retained its cache, and passed pause, 5-second seek,
  resume, and blend while continuing original audio.

**Phase 3 exit:** stable two-stem WAV and FLAC playback uses one logical clock,
bounded background decode, atomic transport barriers, and a realtime-safe
mixer. Normal playback creates no persistent full-song PCM.

### Phase 4: N-stem playback data plane

Status: Phase 4A and the Phase 4B synthetic/compressed-source device
qualification are complete. The schema-dependent repository/service recreation
gate remains explicitly deferred to Phase 5. Completed Phase 3 product-state
gates qualify only the two-stem path and do not authorize a real multi-stem model.

#### Phase 4A: Ordered synthetic data plane

- [x] Generalize the engine session, atomic block set, gain snapshot,
  diagnostics, and playback-service handle to a complete ordered stem set.
- [x] Implement the list-based mixer and list-based resampling caches while
  preserving the Phase 3 realtime and transport invariants.
- [x] Cover deterministic 2-, 4-, 6-, and 8-stem output, same-frame atomic
  publication, seek, hot-swap, short/unequal-length rejection, EOF handling,
  gain ramp, clipping, and sample-rate conversion with synthetic sources.
- [x] Enforce the current eight-stem contract limit and reject over-budget block
  pools before worker startup; expose allocated stem count and pool bytes in
  playback metrics.

Evidence: `6148b589` adds the engine matrix, `a7816b50` adds the list mixer and
  4/6/8-stem processor coverage, `a92d95e2` carries ordered cache stems through
  the service session, and `b84894ff` adds structural pool admission.

#### Phase 4B: Compressed-source and device qualification

- [x] Reject a missing FLAC index, frame-CRC failure, or truncated file from any
  required stem as one complete 4-stem session; no whole-file fallback may be
  reintroduced.
- [x] Extend compressed-source corruption coverage to every 2/4/6/8-stem
  geometry and explicit indexed-WAV malformed-file cases. WAV admission now
  parses and validates RIFF/WAVE chunks, PCM16 geometry, contract sample rate
  and channel count, and the declared data boundary instead of assuming a
  fixed 44-byte header.
- [x] Cover 6-stem engine recreation and 8-stem in-flight close/cancellation;
  existing epoch tests continue to reject stale seek and hot-swap output.
- [x] Cover 4/6/8-stem artifact deletion, path-aware playback leases, engine
  recreation, and the complete-set recovery barrier after file replacement.
- [ ] Add repository and `PlaybackService` process-recreation tests using a
  real 4/6/8-stem cache manifest after Phase 5 provides the multi-tensor
  contract and cache-snapshot schema. Do not fake this gate by attaching a
  synthetic stem list to the current MDX-only two-stem snapshot.
- [x] Run 4- and 6-stem indexed-FLAC smokes with 20 random seeks on S25, S10,
  API 26 x86, and API 37 x86_64. All rows produced exact PCM with zero
  underruns. The 6-stem pool was 1,179,648 bytes, six descriptors were open,
  and the FLAC plus index payload was 1,173,999 bytes. The observed 6-stem
  decode p95 / audio-callback p95 / PSS delta were:

  | row | decode p95 | audio p95 | PSS delta |
  | --- | ---: | ---: | ---: |
  | S25 | 4.577 ms | 0.176 ms | 2,928 KiB |
  | S10 | 4.551 ms | 0.231 ms | 4,003 KiB |
  | API 26 x86 | 4.986 ms | 0.209 ms | 2,603 KiB |
  | API 37 x86_64 | 5.796 ms | 0.501 ms | 2,912 KiB |

  The corresponding 4-stem pool was 786,432 bytes and the compressed payload
  was 782,114 bytes. `Debug.getPss()` is a coarse process snapshot, not a
  thermal or allocation-trace peak; these results are bounded-resource smoke
  evidence only.
- [x] Collect synthetic eight-stem thermal behavior, sustained 30-minute
  throughput, full-run seek percentiles, and separately sampled PSS/cache
  stability on S25 and S10 arm64. This synthetic evidence remains distinct from
  the exact-tip two-stem real-song qualification recorded in Phase 3.
- [x] Retain the current eight-stem/4 MiB structural admission ceiling. The
  default eight-stem geometry allocates only 1,572,864 bytes, and the long run
  showed bounded PSS with no cache growth, so lowering the guard would not
  reduce the actual pool. Unsupported counts continue to fail before session
  installation rather than degrading into partial playback or full-song PCM.

  The opt-in 30-minute indexed-FLAC soak generated eight real compressed stems,
  played at realtime cadence, and completed 99 deterministic random seeks. Both
  rows retained eight descriptors, one startup low-water event, zero underruns,
  a 1,572,864-byte pool, and an unchanged 9,391,312-byte cache payload:

  | row | setup | seek p95 | decode p99 | audio p99 | PSS delta | late callbacks |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | S25 arm64 | 14.203 s | 9 ms | 3.187 ms | 0.514 ms | 20,498 KiB | 0 / 38,743 |
  | S10 arm64 | 8.344 s | 61 ms | 36.006 ms | 9.579 ms | 9,197 KiB | 25 / 38,676 |

  Thermal status remained `0` throughout both runs. Reported battery temperature
  ranged from 21.1-23.4 C on S25 and 32.1-37.6 C on S10. Seek percentiles cover
  the complete run; decoder and audio percentiles are the final 256-sample
  hot-state windows. These are arm64 measurements even though the separate S10
  portability smoke also covers forced armeabi-v7a.

Evidence: `d661340e` adds 4-stem indexed-FLAC and failure coverage,
`109ae194` adds cancellation/recreation and the first S25/S10 instrumentation,
`28c3b155` adds the truncated-FLAC boundary, and `fa9176cf` expands the
instrumentation to 6 stems and both emulator ABI rows. `abe33196` adds strict
WAV parsing and malformed 2/4/6/8-stem admission, `178bf51e` expands missing
index, truncation, and frame-CRC rejection to all four geometries, and
`eeb9957e` covers path leases, replacement barriers, and 4/6/8-stem engine
recreation. The complete four-row device matrix ran on 2026-08-06 and
completed every 2/4/6-stem test with zero test failures. `ced5ceef`, `8cd928fa`,
and `a7855686` add the opt-in soak, device-side wake lock, and buffered fixture
generation used by the successful S25/S10 30-minute runs.

**Exit:** N-stem playback is technically stable with synthetic outputs and the
same bounded engine; this does not yet activate a multi-stem model.

### Phase 5: Multi-tensor pipeline contract and neural-core adapter

Status: contract design is ready; product implementation is not started. The
current MusicSourceSeparation Demucs artifacts are research inputs only.

- [ ] Implement the static multi-input/output contract loader and strict
  tensor-axis validation.
- [ ] Add a separate HTDemucs pipeline adapter for host DSP and branch
  reconstruction.
- [ ] Freeze official-weight provenance, converter identity, output order, and
  fixture files in a distinct executable contract.
- [ ] Run host PyTorch/LiteRT parity before any product device claim.
- [ ] Run the canonical S25 CPU allocation gate before GPU or QNN experiments.
- [ ] Keep official 4/6-stem and guitar-ft artifacts behind a separate research
  catalog until host EOF/per-stem gates, canonical full-song DSP, provenance,
  licensing, and listening review all pass.

**Exit:** a canonical multi-stem candidate produces verified per-stem PCM on
the host and one device window without falling back to an undeclared pipeline.

### Phase 6: Experimental model qualification

- [ ] Complete canonical 7.8-second and full-song DSP validation.
- [ ] Measure peak PSS, native/graphics memory, thermal behavior, cancellation,
  process death, resume, and cache recovery.
- [ ] Qualify CPU first; qualify GPU/QNN only with explicit delegation and
  per-stem numerical evidence.
- [ ] Treat a partial neural-core GPU delegation as hybrid evidence, not GPU
  support; do not claim QNN when graph finalization or `CompiledModel` creation
  failed.
- [ ] Perform human listening tests against the host reference.
- [ ] Keep the model download-only until every required gate passes.

**Exit:** a model-specific catalog decision exists. No broad multi-stem
promotion is implied by a successful smoke test.

### Phase 7: Catalog grouping and controlled activation

- [ ] Add independent family/purpose metadata for all candidate models.
- [ ] Add representative recommendations and folded category records.
- [ ] Keep unsupported generic models inspectable and downloadable but blocked
  from activation.
- [ ] Expose a model only when its exact contract, pipeline, playback, and
  device evidence support the selected activation policy.

**Exit:** catalog grouping is presentation over validated metadata, not a new
source of model semantics.

## Required Tests and Gates

### Contract and serialization

- round-trip canonical labels without localization;
- reject duplicate IDs, invalid axes, inconsistent packed stem dimensions, and
  mismatched branch order;
- verify label edits do not alter execution fingerprints;
- verify profile revisions remain distinct; and
- verify unknown semantic IDs remain inspectable without unsafe activation.

### Separation and cache

- one-window atomic commit for 2, 4, and 6 stems;
- cancellation halfway through reconstruction;
- process death after one stem and after all but one stem are written;
- resume without mixing stem sets or models;
- FLAC promotion, index, frame-integrity, and decoded-PCM parity for every stem;
- deletion while a multi-stem run is active; and
- exact-active-model switching with retained inactive caches.

### Playback

- deterministic per-stem tones to detect ordering swaps;
- all-stem sum, single-stem, mute, seek, pause/resume, and hot-swap;
- sample-rate conversion and unequal-length rejection;
- bounded background decode of compressed sources before audio-thread use;
- missing/corrupt index, CRC failure, EOF, low-water, and stale-epoch behavior;
- no file I/O, decode, hashing, allocation, or contended waits on the Media3
  audio thread;
- sustained playback, rapid random seek, underrun, PSS, file-descriptor, and
  frame-time observation on S10 and S25; and
- locale changes and deleted-profile read-only cache playback.

### Model qualification

- official source-to-runtime parity per output stem;
- exact output order and tensor-axis validation;
- separate smoke and canonical identities;
- CPU, GPU, and NPU evidence never merged across artifacts or profiles; and
- full-song reconstruction before any experimental activation.

## Recommended Frozen Decisions

- Keep current MDX v2 contracts readable, but normalize them internally to a
  two-item stem list.
- Add a new contract kind/schema for multi-input or multi-output pipelines;
  do not turn the MDX contract into a collection of optional Demucs fields.
- Use stable ordinal cache paths and manifest mappings, not labels as paths.
- Keep separate compressed per-stem rendered files. Do not persist full-song
  decoded PCM during normal playback.
- Use the original source as the generic playback clock and treat all model
  stems as synchronized side inputs.
- Keep Media3/ExoPlayer, MediaSession, and one AudioTrack. Put bounded stem
  decode and transport barriers behind a playback engine instead of adding one
  player or output clock per stem.
- Do not migrate the product player to CompositionPlayer, multiple ExoPlayers,
  Oboe, or miniaudio unless the bounded engine fails measured correctness,
  underrun, CPU, or power gates that a lower-level engine can demonstrably fix.
- Require one atomic block set and one transport epoch across every active stem;
  the audio processor consumes ready PCM and never owns cache files or decoders.
- Preserve the current two-stem steady-state gain law until a separately
  listening-qualified change; use atomic snapshots, ramps, wide accumulation,
  and one final clamp to make its implementation realtime-safe.
- Treat Demucs output as model-native direct stems unless its contract declares
  a specific derivation; never invent a residual automatically.
- Cap the first product playback implementation at eight stems while allowing
  the wire contract to remain extensible.
- Require generated sidecars for complex imported multi-stem models.
- Exclude localized labels, backend diagnostics, and per-song mix settings
  from portable identity and backups.

## Open Decisions to Validate During Implementation

1. Whether the normalized semantic ID is represented as a Kotlin value class
   or a string-backed enum adapter. Recommendation: value class plus known
   constants, so unknown reviewed IDs survive parsing.
2. Final low/high waterlines and whether a second decode worker helps.
   Current default: one block for ordinary seek resume, three blocks for normal
   startup/recovery, eight target blocks, a twelve-block pool, and one worker.
   Change them only from S10/S25 block-latency and underflow evidence.
3. Whether all-stem playback should use model sum or original-source bypass when
   gains are neutral. Recommendation: make both explicit states and do not
   silently apply a residual correction.
4. The final maximum stem count and memory admission policy. Recommendation:
   start with eight and derive a higher limit only from device measurements.
5. Whether the external `.flac.idx` should eventually be replaced by the
   embedded SEEKTABLE. Recommendation: keep the now-tested sidecar for the first
   engine implementation, then compare corruption handling, seek latency, and
   recovery before removing it under the clean-install boundary.
6. Whether any low-end device needs session-scoped decoded PCM or a native FLAC
   decoder. Recommendation: add neither by default; require evidence that the
   bounded Kotlin decoder cannot sustain the qualified stem count. Never retain
   full-song PCM as durable cache.
7. Whether an imported multi-stem sidecar may declare a custom pipeline.
   Recommendation: only reviewed, app-bundled pipeline IDs may activate;
   unknown pipelines remain installed and inspectable but download-only.
8. Whether the current HTDemucs candidates should enter the product catalog.
   Recommendation: no. Keep them in the research repository until an exact
   artifact passes host and per-stem device gates, canonical full-song DSP,
   provenance/license review, resource/cancellation tests, and listening. Do not
   generalize MDX GPU/QNN evidence or a neural-core hybrid to Demucs.

## Completion Definition

This roadmap is complete only when:

- the contract and cache layers no longer assume exactly two stems;
- known English labels localize and unknown labels remain unchanged;
- imported profiles preserve and expose their labels;
- model-output order cannot be confused with display order;
- separation, recovery, cache deletion, and playback operate on one
  coherent stem set;
- normal FLAC playback uses bounded background decode and creates no persistent
  full-song PCM;
- the Media3 audio thread performs no file I/O, FLAC decode, hashing, dynamic
  allocation, or blocking repair work;
- active-model switching preserves exact cache isolation;
- at least one 4- or 6-stem pipeline passes host, device, full-song, and
  listening gates; and
- catalog categories and activation policies are derived from validated
  metadata rather than filenames or localized text.

Until then, non-MDX multi-stem candidates remain download-only and the stable
MDX two-stem path remains the product default.
