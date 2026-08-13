# Source-Separation Contract and Multi-Stem Data-Plane Roadmap

Status: implementation and device/listening qualification complete except for
one representative-content PCM16 attribution gate. This remains the design
authority for stem identity, model contracts, imported metadata, cache output
shape, and playback data flow.

Updated: 2026-08-08

## Current Release and Product-Path Baseline (2026-08-08)

The three frozen HTDemucs artifacts are now published in
[`bss-tflite v0.2.0-experimental.1`](https://github.com/WluhWluh/bss-tflite/releases/tag/v0.2.0-experimental.1)
with the independent `multistem-executable-contract-v1` schema, exact-name
sidecars, CPU-only backend policy, notices, and SHA-256-bound assets. They are
selectable experimental entries in `model-catalog-v3.json`, not merely local
research fixtures. The S25 Phase 6 v2 canonical numerical gate passed for all
three models.

All subsequent multi-stem tests must prefer the real Booming SS product path:
download v3 from the immutable Release, fetch the selected TFLite and sidecar,
verify artifact and contract hashes, install through the production model
delivery provider, explicitly select the model, then exercise the multistem
worker, cache, PlaybackService, and model-switch flows. The staged fixture
runner remains useful for contract/loader diagnostics and host/device tensor
gates, but its result alone is not product-path evidence.

The published three models are selectable experimental entries. Older planning
sections may mention `download-only` or non-activatable research inputs; those
labels describe superseded pre-release states and must not be applied to the
current v3 Release. Unknown contracts and unvalidated pipeline IDs remain
blocked from activation.

Current milestone: Phases 0-2 and the Phase 3 bounded two-stem data-plane
implementation are complete. Phase 4A now provides an ordered 2/4/6/8-stem
engine, list mixer, service session handle, and structural buffer admission.
The deterministic indexed-WAV/FLAC seek smoke still passes on all four ABI rows,
and the synthetic eight-stem engine has completed 30-minute S25/S10 arm64
throughput, seek, thermal, PSS, and cache-stability qualification. Two-stem
real-song playback and its process-recreation, background, cache-deletion, and
active-model product-state gates are complete. Phase 5 has frozen the three
real multi-stem executable contracts, and Phase 6 now routes completed official
4/6-stem product caches through `PlaybackService` on S25, including exact-model
switching with retained inactive caches. S10 now also has completed-cache
service-recreation and playback-time deletion evidence. The matching S25
service-recreation and direct active-cache deletion gates, strict S10
zero-underrun playback resources, S25 official-six-stem producer-ahead
playback, and active deletion through the S25 management panel are complete.
The bounded contention/resource soak and independent main-process-death
recovery gates are also complete on S25 and S10. Same-weight human listening
now passes for all three candidates. The only remaining Phase 6 closeout is to
attribute the wider-than-frozen PCM16 deltas exposed by that representative
listening sample without silently weakening the numerical gate.

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
`7ac9603`) justifies preparing generic N-stem contracts and playback and
selecting three exact CPU-only artifacts for simultaneous experimental product
qualification. It does not by itself qualify a release. The closure matrix is:

| Candidate or experiment | Evidence | Product decision |
| --- | --- | --- |
| Official HTDemucs 6-stem | Host pipeline passed; S25 CPU 180-second E2E RTF `0.555`; peak total PSS about `1.128 GiB`; strict per-stem device gate failed | First six-stem CPU-only experimental candidate |
| Official HTDemucs 4-stem base | Host pipeline passed; S25 CPU 180-second E2E RTF `0.617`; strict per-stem device gate failed; Batch 4A found no repeatable advantage from the larger bags or hybrids | Sole four-stem CPU-only experimental candidate |
| Official 6/4-stem GPU experiments | Only a small GPU+CPU neural-core hybrid was delegated; no canonical GPU E2E audio batch ran; memory and latency were worse | No Demucs GPU product claim |
| HTDemucs 6-stem guitar-ft | S25 diagnostic mean E2E RTF `0.6783`; listening showed useful guitar/piano behavior; the frozen host EOF gate was `79.245 dB` versus `80 dB` | Six-stem specialist CPU-only experimental candidate; preserve the author's Apache-2.0 and MoisesDB disclosures |
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

### Frozen first product candidate set

The first multi-stem release batch targets these three exact artifacts together:

- official HTDemucs 6-stem as the representative general six-stem candidate;
- official HTDemucs 4-stem base as the sole four-stem candidate; and
- HTDemucs 6-stem guitar-ft as an experimental guitar-specialist candidate.

All three are CPU-only. Their qualification record must expose only `CPU` as an
allowed backend, and the router must not send them to GPU or NPU even when the
user enables those backends globally. CPU-only does not imply support for every
ABI or memory class. Initial product qualification is arm64; arm32 and emulator
ABIs remain blocked until their address-space, allocation, and sustained-run
gates pass.

The three artifacts are published together in the v0.2.0 experimental Release
with independent model files, contracts, hashes, and notices. Quick Setup must
not install all three automatically. The official six-stem
candidate is the default representative within the experimental multi-stem
category; the stable two-stem recommendation remains unchanged.

The guitar-ft release record must preserve, without paraphrasing away the
distinction:

- the author's Apache-2.0 declaration at Hugging Face revision
  `163ec83135ee06e6f10cb8cd94d2ecef8f3f34ad`;
- the MIT attribution for the official HTDemucs 6-stem base;
- the statement that the fine-tune used MoisesDB and redistributes no raw
  training audio;
- the MoisesDB CC BY-NC-SA 4.0 identity and attribution; and
- a prominent notice that Booming SS converted the checkpoint to an FP32
  LiteRT FlatBuffer and is an open-source, free application.

The converted TFLite, contract sidecar, model details, model-repository release
notes, and bundled third-party notices must all point to those identities.

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
the conversion script; an unknown or unvalidated pipeline remains blocked from
activation even when its model file is present.

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
- `supportLevel`: recommended or experimental; and
- `activationPolicy`: selectable, with explicit backend/resource refusal when a
  device cannot safely admit the run.

The recommended section may contain several representative models later, but
published target models with reviewed contracts are selectable experimental
entries while the neutral contract, cache, playback, and full-song gates remain
visible as qualification status. Unknown contracts or pipelines remain blocked
from activation.

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
- [x] Add repository and `PlaybackService` process-recreation tests using real
  4/6-stem cache manifests after Phase 5 provided the multi-tensor contract
  and cache-snapshot schema. The S10/S25 product gates use the exact Release
  manifests and do not attach synthetic stems to an MDX snapshot; an 8-stem
  real-product manifest remains outside the current model batch.
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

Status: the static contract loader, canonical HTDemucs host pipeline, three
CPU-only executable identities, bounded parallel iSTFT, named LiteRT CPU
session, S25 executable fixture gate, scheduler/cache publication,
PlaybackService integration, and management UI activation are complete for the
current 4/6-stem candidate batch. They are represented by the bss-tflite
v0.2.0 experimental catalog and exact-name Release sidecars. Full numerical,
resource, and listening qualification remains separate from this integration
status.

- [x] Implement the static multi-input/output contract loader and strict
  tensor-axis validation.
- [x] Add a separate HTDemucs pipeline adapter for host DSP and branch
  reconstruction.
- [x] Freeze separate executable contracts for official 6-stem, official
  4-stem base, and guitar-ft 6-stem, including exact source weight, converter,
  FlatBuffer, tensor order, host-DSP revision, and fixture identities.
- [x] Add an immutable license/notices section to every executable contract.
  For guitar-ft, preserve the author's Apache-2.0 statement, the base-model MIT
  attribution, and the MoisesDB training-source and CC BY-NC-SA 4.0 disclosures.
- [x] Port the research `parallel-lanes` iSTFT mode behind the HTDemucs adapter;
  keep serial as the parity oracle and prove four/six-stem raw-FP32 equivalence
  before using the parallel implementation in product measurements.
- [x] Implement a named two-input/two-output LiteRT CPU session. One forward
  must publish one complete ordered stem set; cancellation or any branch error
  discards the entire window.
- [x] Run the frozen host PyTorch/LiteRT/DSP/OLA fixtures for all three exact
  artifacts before making any product device claim.
- [x] Publish the three candidates as selectable CPU-only experimental entries
  with independent Release contracts, sidecars, hashes, notices, and catalog
  records. Stable promotion remains gated by Phase 6.

Evidence: `12534fd0` adds the fail-closed static multi-tensor schema and tensor,
axis, binding, and ordered-stem validation. `47e631b4` adds the product-owned
canonical host STFT/iSTFT, global normalization, and atomic frequency/time
branch reconstruction for reviewed four- and six-stem geometry. `3a0bf4e6`
adds canonical EOF window planning and triangular FP32 overlap-add. Synthetic
tests cover deterministic four/six-stem reconstruction without introducing a
model artifact or large fixture into the application repository. `2c36f9eb`
adds the strict executable schema and validator; `fbccad0d` freezes the exact
official 6-stem, official 4-stem base, and guitar-ft source revisions,
conversion recipes, TFLite FlatBuffer identities, tensor indexes, canonical
fixture identities, CPU-only backend policy, and immutable license notices.
The fixture files and model weights remain outside the application repository;
the external instrumentation gate resolves them only from an explicitly staged,
contract-verified test bundle.

`09905504` ports the bounded four-worker `parallel-lanes` iSTFT while retaining
serial as the oracle; synthetic four/six-stem runs are raw-FP32 bit exact and
cover cancellation, pool reuse, and thread cleanup. `96369145` adds the atomic
named two-input/two-output CPU session, and `8576ca67` corrects the LiteRT
signature binding to use `args_0`/`args_1` and `output_0`/`output_1` while
retaining the distinct FlatBuffer tensor names as artifact identity.
`a48085d3` adds the external executable fixture gate without packaging model
weights or large fixtures in the APK. `6c047f7c` adds the frozen layered host
and per-stem metrics to the executable report, and `a4a6b761` makes those two
qualification gates fail closed instead of treating a diagnostic-only report
as an admission pass. Final PCM16 parity, clean provenance, full-song
execution, and device lifecycle evidence remain required before any candidate
can be activated.

The committed `a48085d3b219c017271fbb9442087f4dfa667017` build passed the full
gate on S25 (`SM-S9310`, API 35, arm64) for all three exact artifacts using
runtime `2.1.5-bss.2`, library SHA-256
`ae2b996fde27021b070e88b56eebc9626a5261feb72f09791bdac38b2f09abd2`.
The app APK SHA-256 was
`9862c4a6f140b0762f6769cb4f5df7a9908d4ac0ec33f2ba9f6d4c824134e085`;
the test APK SHA-256 was
`eb14ffe2ac0a15cef4d2acc9e11d353a400c284cae161f09aebbd531a4f219e8`.

| Candidate | Frequency tensor SNR | Waveform tensor SNR | Frequency iSTFT SNR | Combined SNR | Two-window OLA SNR | Report SHA-256 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Official 6-stem | 81.376 dB | 107.842 dB | 79.712 dB | 82.430 dB | 79.733 dB | `5073d9d5c533aad6b3e3490c656f4c033a65bc3588198036f904e2df171517ec` |
| Official 4-stem base | 90.443 dB | 107.668 dB | 103.416 dB | 109.068 dB | 80.185 dB | `9666d4dba4fedf190a0ebd715272d1c2cb8d465306a1c069ca9b2589a741c7dc` |
| Guitar-ft 6-stem | 88.567 dB | 103.084 dB | 87.891 dB | 90.254 dB | 66.628 dB | `3107e7250b0194b443ef58aa60350f17446d178d5e1f111b6167c9d9e62ce85f` |

All layers were finite and used contract-verified artifact/fixture hashes. These
are executable-path results, not Phase 6 quality admission. In particular, the
lower guitar-ft OLA SNR must be evaluated by the layered energy-aware and final
PCM16 gates rather than waived or rejected by one uniform tensor threshold.
S10 execution, long-song behavior, memory/thermal limits, cancellation across
the product process, cache publication, and listening remain Phase 6 work.

**Exit:** all three canonical candidates produce verified per-stem PCM through
the product-owned host pipeline and CPU session without falling back to an
undeclared pipeline or publishing a partial stem set.

### Phase 6: Experimental model qualification

- [x] Add the Release acquisition boundary for `model-catalog-v3.json`:
  pin `v0.2.0-experimental.1`, verify catalog size and SHA-256, validate all
  artifact/sidecar identities, and resolve the three HTDemucs entries through
  the provider-neutral `ModelDeliveryProvider`. This layer is deliberately
  separate from the existing MDX runtime-qualification catalog and does not
  install or activate a multi-stem model by itself.
- [x] Add an isolated multi-stem model store that acquires the selected
  artifact and exact-name sidecar into a staging directory, verifies both
  payload hashes and the executable contract's artifact binding, then publishes
  one SHA-256-addressed directory atomically. Failed installs leave no visible
  model, and an existing complete install is reused without another download.
- [x] Add the product-facing Release installer and persistent catalog cache.
  Catalog bytes are cached only after the same pinned size/SHA/schema checks;
  corrupted cached bytes are discarded and reacquired from the immutable
  Release. UI and Quick Setup integration remain a later activation step.
- [x] Bridge an installed Release pair to the reviewed HTDemucs LiteRT CPU
  session factory. Reopening trusts the install record and file length rather
  than hashing the large artifact again, while the sidecar contract, model ID,
  contract ID, pipeline ID, filename, size, and SHA binding are revalidated.
- [x] Extend the cache contract snapshot with an explicit multi-tensor branch.
  It preserves the complete static multi-tensor model contract and ordered
  pipeline-native `StemSet` without manufacturing MDX tensor/DSP fields. Its
  execution fingerprint includes tensor geometry, output bindings, pipeline,
  stem IDs, semantics, order, and production, while excluding display names and
  canonical labels. Existing MDX manifests retain their original branch.
- [x] Generalize cache preparation and completion around an exact ordered list
  of stem files. The existing MDX result is now an adapter to that boundary;
  multi-stem runs can publish preparation state, segment readiness, runtime
  evidence, and one complete 4/6-stem result without coordinator conditionals.
  A completed manifest is published only after every expected stem has been
  copied and verified.
- [x] Implement the canonical full-track HTDemucs framing primitives without
  whole-song N-stem PCM: streaming mono mean/sample-standard-deviation,
  343,980-sample centered tail windows, the frozen 257,985-sample stride, and
  triangular overlap-add. The OLA holds one bounded N-stem window and emits each
  finalized stride before accepting more output; it preserves the official
  crop/pad and denormalization rules.
- [x] Add a platform-neutral HTDemucs range runner over random-access planar
  stereo input and an ordered window session. It performs the two-pass global
  normalization/inference flow, writes stable ordinal full-song and segment
  WAV paths, publishes each segment only after every stem is written, and
  supports cancellation and workspace-loss checks without depending on Android
  decoding or LiteRT in deterministic tests.
- [x] Connect the range runner to the unchanged `MdxSourceInput` decoder policy,
  downloaded-model CPU session factory, source-fingerprint gate, and generic
  cache preparation/completion types. Preparation validates ordered target
  paths before writers create them; completion alone requires every file to
  exist. This preserves the early progress callback used by the MDX path while
  making the same boundary available to HTDemucs.
- [x] Add a dedicated CPU-only HTDemucs lifecycle engine over the generic cache
  coordinator. It derives the exact identity from the installed executable
  sidecar, drives begin/preparation/segment/completion publication, and maps
  ordinary pause, active-model supersession, user cancellation, and failure to
  distinct durable journal transitions. The runner checks pause independently
  from cancellation during normalization and window execution.
- [x] Freeze partial-run behavior for this engine revision. A paused multi-stem
  cache remains inspectable until another run is admitted, but the next run
  clears and rebuilds the incomplete full-track WAV set because the current
  writer has no qualified append/resume state. The engine rejects any future
  non-null resumable state until exact N-stem writer restoration is implemented
  and tested; it never silently combines old and new output.
- [x] Expose the installed Release store and HTDemucs engine through one
  product-facing multi-stem facade in the application dependency graph. Model
  installation still uses the pinned GitHub delivery provider, while listing
  and opening already installed model records remains available offline and
  does not reacquire the Release catalog. Existing MDX model selection and
  playback behavior are unchanged.
- [x] Download each model and sidecar from the immutable
  `v0.2.0-experimental.1` Release through the production model-delivery path;
  verify the v3 catalog, artifact hash, sidecar hash, contract schema, and
  CPU-only backend policy before creating a product cache.
- [x] Freeze a separate layered numerical gate before qualification runs:
  strict host FP32 fixture parity, energy-aware per-stem Android tensor checks,
  and final whole-render PCM16 parity. Low-energy stems use an absolute-error
  rule; PCM16 SNR remains diagnostic-only.
- [x] Re-run the canonical 7.8-second host/device tensor comparison for all
  three exact Release-installed artifacts from a provenance-bound product
  build. The fixture runner now loads model and sidecar only from the product
  installation store; staging supplies hash-bound fixture tensors, never an
  alternate model. All three pass the Frozen V2 energy-aware per-stem FP32
  gate. Final PCM16 agreement remains a separate gate.
- [x] Compare a complete 30-second product render with the same-weight host
  reference for all three Release artifacts. Commit `50fcbad7`, app APK
  `2cad204f...dad8`, and test APK `150fb74d...e55c` processed the identical
  Athletics II PCM payload `c35318b7...69f3` on S25 through Release install,
  remote CPU execution, durable cache publication, indexed FLAC promotion,
  temporary-WAV cleanup, and completed-cache reuse. Every stem in official
  six-stem, official four-stem, and guitar-ft passed Frozen V2 PCM16 parity
  with a maximum one-LSB difference. The staged fixture runner without a host
  reference remains diagnostic-only and cannot satisfy this item.
- [x] Validate the official 6-stem candidate first, then run the same unchanged
  pipeline against official 4-stem base and guitar-ft. A shared architecture
  does not allow one artifact's result to stand in for another. All three exact
  Release artifacts independently completed the same 30-second S25 product
  engine/cache/FLAC gate. The frozen Athletics II numerical row remains closed
  by the same-weight product render gate above; the later representative-content
  numerical follow-up and human listening are recorded separately below.
- [x] Verify user cancellation and clean restart on S25 and S10. The canceled
  run retains exactly one atomically completed segment, records a durable
  `Canceled` transition, exposes no completed cache, and cleanly rebuilds the
  non-resumable writer state under the same exact cache identity.
- [x] Verify active-model supersession on S25 and S10. Superseding official
  six-stem after its first ready segment promptly records `Paused` with
  `ActiveModelSuperseded`, retains that partial cache, and completes official
  four-stem under a distinct exact-model cache identity.
- [x] Verify the multi-stem cache coordinator's process-generation handoff in
  the product engine. An abandoned `Running` owner can be admitted by a new
  generation, records `PreviousOwnerDied`, clears the non-resumable partial
  writer state, and republishes a complete result under the same exact cache
  identity. This is a durable cache-boundary test, not yet proof of an actual
  remote Binder/process-death run.
- [x] Put the multi-stem product facade behind a dedicated execution-host
  boundary. The current implementation is an in-process host with unchanged
  behavior; the request carries the installed executable model, exact source
  preflight, ordered callbacks, pause/cancel policy, and run class so a future
  remote host does not reuse MDX-only request types.
- [x] Freeze the first multi-stem execution wire contract separately from the
  MDX IPC protocol. Its descriptor binds the exact artifact, executable
  contract, pipeline, source/cache identity, CPU-only runtime policy, and
  ordered stem paths; preparation, progress, segment-state, and completion
  payloads cannot silently collapse a multi-stem result into two MDX files.
- [x] Add one manifest-to-wire adapter for multi-stem preparation and
  completion payloads. Remote code must use the durable ordered WAV paths and
  segment plan, rather than reconstructing paths from labels or promoted FLAC
  state.
- [x] Execute the product multi-stem facade through the dedicated
  `:source_separation` Binder service. The service owns the HTDemucs engine and
  runtime lease, while the app process receives only bounded progress/events
  and reads the durable cache manifest.
- [x] Run the real remote process-death recovery gate on S25 and S10. Killing
  the service after the first ready window releases the caller, leaves a
  durable `Running` journal without falsely completing the cache, and a new
  service generation completes the same exact cache identity with
  `PreviousOwnerDied`.
- [x] Reject a concurrent multi-stem product request with a typed `Busy` result
  without disturbing the accepted remote run. The official six-stem 30-second
  product run completed on both S25 and S10 after contention was introduced
  after the first committed segment; the second request returned in 36 ms and
  143 ms respectively, and the original exact cache identity completed.
- [x] Add bounded continuous remote-process resource sampling to the contention
  gate. The S25 and S10 runs sampled PSS/native/Dalvik memory and thermal status
  every 100 ms while `:source_separation` remained alive. Peak total PSS was
  about 1.13 GiB on both devices; S10 thermal status rose from 1 to 2 and its
  final PSS remained about 228 MiB. This is a short product-path smoke, not a
  long-running memory or thermal qualification.
- [x] Verify independent background ownership rather than only a bound call
  surviving while the app is backgrounded. Cover caller/activity teardown,
  service reattachment, cancellation, result publication, and player adoption
  without relying on a live instrumentation owner.
  The debug-only non-instrumentation gate now starts an official six-stem
  manual run from the real app process, durably freezes its exact descriptor
  and journal, kills only that main PID, and lets the independently owned
  `:source_separation` foreground service continue. A new product coordinator
  instance selects the active multi-tensor journal separately from MDX,
  adopts the exact multi-stem event stream, and routes pause/cancel, model
  supersession, cache deletion, progress, and terminal publication without
  projecting six stems into the two-stem protocol. On S25, the old main PID
  died after one committed segment at journal sequence 5; the same remote PID
  and generation completed all six segments at sequence 15. On S10, the old
  main PID died before the first segment at sequence 2; the same remote PID
  and generation likewise completed all six segments at sequence 15. Both
  completed caches then passed indexed-FLAC promotion, ordered
  `drums,bass,other,vocals,guitar,piano` adoption through the real
  `PlaybackService`, and pause/seek/resume. Notification Pause/Cancel and
  observer replacement remain independently covered by the preceding control
  gate; these process-death runs do not infer immediate process reclamation
  from an idle cached worker PID.
- [x] Run a bounded repeated-run CPU lifecycle/resource soak on S25 and S10,
  combined with the independently completed cancellation/restart and process-
  death gates above. Each device completed five consecutive official six-stem
  30-second product runs with first-segment contention, 100 ms remote PSS/native/
  Dalvik/thermal sampling, cache cleanup, and a five-second idle boundary.
  S25 took 22.908-35.852 s with peak total PSS 1,158,452-1,165,064 KiB and
  native final PSS 9,036-9,312 KiB. S10 took 51.005-96.057 s with peak total
  PSS 1,146,895-1,223,901 KiB; some runs ended with high retained PSS, but all
  five used distinct worker PIDs and every old process was reclaimed before the
  next run. Both devices reported `processMissing=false` while active, thermal
  status remained `0` throughout this batch, and no worker service/PID remained
  after the final idle boundary. These are per-device bounded soak results, not
  an hours-long endurance or energy qualification.
- [x] Run the new service-recreation segment on S10 from a clean player
  restoration barrier. The test stops the first `PlaybackService`, reconnects
  a fresh `MediaController`, and re-adopts the same exact official six-stem
  cache and ordered `drums,bass,other,vocals,guitar,piano` set. The S10 run
  completed pause, seek, resume, and service recreation; it recorded one seek
  underrun on the resource-constrained device under an explicit diagnostic
  allowance of one. A later strict rerun also passed with the default zero
  allowance.
- [x] Run the completed-cache service-recreation gate on S25 with the default
  zero-underrun allowance. The run re-adopted the exact official six-stem
  cache after service restart, preserved ordered
  `drums,bass,other,vocals,guitar,piano`, and recorded zero seek underruns.
- [x] Repeat the S10 service-recreation run without the diagnostic allowance.
  The strict rerun passed with zero seek underruns, so the combined S10/S25
  service-recreation gate is complete.
- [x] Delete an actively adopted completed official six-stem cache on S10 and
  S25 through the product `PlaybackService` path. The mode disables separated
  playback, waits for the data plane and artifact lease to release, deletes the
  exact cache directory, notifies `PlaybackService`, and verifies that no old
  stem remains while the original song stays in the active transport.
- [x] Delete a completed official six-stem cache on S25 through the real cache
  management panel button while the worker is idle. The production Compose
  path removed the exact cache directory and refreshed the visible item list;
  it did not change the installed model selection.
- [x] Repeat completed-cache deletion on S25 while that cache is actively
  adopted by playback. The panel callback must release the data-plane lease,
  disable separated playback, send the playback-state broadcast, and verify
  that the current-song status is refreshed. The S10 service/repository gate
  proves the data boundary but does not substitute for this user-interaction
  gate.
- [x] Validate S25 producer-ahead playback with the historical two-ready-window
  startup policy. On devices whose
  measured production rate cannot sustain the stride, keep separation offline
  or wait for completion instead of repeatedly pausing playback; S10 results
  above RTF `1.0` must not be presented as streaming-capable.
- [x] Complete the first real 4/6-stem repository and `PlaybackService` gate on
  S25: exact Release model selection, remote full-song publication, indexed
  FLAC promotion, original-source transport clock, ordered N-stem adoption,
  pause, seek, and resume. Process recreation and cache deletion during
  playback remain separate follow-ups.
- [x] Invalidate a completed multi-stem playback session when the selected
  exact model changes. The S25 product gate switches an actively playing
  official six-stem cache to the already completed official four-stem cache,
  retains and reopens the inactive six-stem cache, adopts only the replacement
  stem order, and completes pause, seek, and resume without an underrun.
- [x] Perform human listening against the same-weight host reference for every
  candidate, including guitar/piano-dense material for guitar-ft. A blind A/B
  handoff used the same 90-120 second excerpt of YOASOBI - Yoru ni Kakeru for
  official six-stem, official four-stem base, and guitar-ft. The 44.1 kHz
  stereo PCM16 source was 1,323,000 frames with file SHA-256
  `c8b30daa9d16ab33c805714d702974caa8090c18b31aadc29f06bd7093fa4ec0`
  and payload SHA-256
  `5e50e0c341a5b72ba66eaf597a3c307ea07332ce85000f85fedc82b0236a5e98`.
  The user found no discernible product-versus-host difference for any of the
  three exact Release weights.
- [ ] Reproduce and attribute the representative-content PCM16 deltas on one
  provenance-bound current build before declaring the numerical closeout
  complete. The exploratory listening render measured maximum per-sample
  deltas of 7 LSB for official six-stem, 8 LSB for official four-stem, and
  2 LSB for guitar-ft, while the frozen final PCM16 gate is one LSB. Separate
  neural-core, STFT/iSTFT, OLA, normalization, and PCM quantization effects;
  do not raise the threshold from an inaudible listening result alone.
- [x] Publish all three as selectable CPU-only experimental candidates with
  explicit incomplete-validation status. GPU and QNN remain unsupported for
  this batch and are not Phase 6 follow-ups.

Evidence: `d2cd8482` adds `phase6-thresholds-v1.json`, and `e77f73ba` adds the
reviewed `phase6-thresholds-v2.json` plus product-side metric tests. The v2
revision raises the low-energy reference RMS boundary from `1e-4` to `1e-3`;
it does not lower the energetic-stem SNR, cosine, maximum-error, or PCM16
limits. The gates cover strict FP32 parity, low-energy stem handling,
energetic-stem SNR/cosine gates, and one-LSB PCM16 equivalence. The S25 Phase 5
fixture reports above are intentionally not retroactively evaluated as a
qualification pass under this revision. The later Release-installed
same-weight product comparisons close the clean full-song and per-stem
numerical gate without retroactively changing this threshold result.

The first clean-provenance S25 attempt for official six-stem was run from
product commit `34c49f7e` with APK SHA-256
`3892aa4ca7401c571941715d83ba33f68721d1384a9ba889d8925bbc10f512c6`, test APK
SHA-256 `e0853f1f4d1a37e740797003e647e7dc510ece8c03e9cd354377577ed31c4cea`,
runtime `2.1.5-bss.2`, and library SHA-256
`ae2b996fde27021b070e88b56eebc9626a5261feb72f09791bdac38b2f09abd2` on S25
(`SM-S9310`, API 35, arm64). Artifact and all fixture identities matched. The
run was correctly rejected by the frozen per-stem gate: `other` was classified
as energetic under the `1e-4` RMS boundary and measured 57.789 dB SNR,
`5.42e-6` maximum absolute error, and `0.9999998697` cosine similarity. This
is retained as a qualification failure, not waived by the small absolute
error. The host strict gate is diagnostic-only on Android; it remains a host
qualification gate. The threshold requires a separately reviewed revision
before more device runs can be used as admission evidence. The threshold was
then reviewed and superseded by v2 before the unified rerun below.

The v2 unified S25 canonical fixture run used product commit `e77f73ba`, runtime
`2.1.5-bss.2`, and the same exact artifact/fixture identities for all three
candidates on `SM-S9310`, API 35, arm64:

| Candidate | Status | Combined SNR | Two-window OLA SNR | Neural core + reconstruction | Per-stem gate |
| --- | --- | ---: | ---: | ---: | --- |
| Official 6-stem | pass | 82.430 dB | 79.733 dB | 7.353 s | pass |
| Official 4-stem base | pass | 109.068 dB | 80.185 dB | 6.033 s | pass |
| Guitar-ft 6-stem | pass | 90.254 dB | 66.628 dB | 7.354 s | pass |

These are canonical 7.8-second and two-window device checks, not full-song
qualification. At this checkpoint they established that all three exact CPU
artifacts passed the same v2 numerical device gate on S25; the later product
runs below separately close full-song PCM16, resource, lifecycle, and cache
gates. Human listening was still open at this checkpoint and is closed by the
later blind representative-content pass above.

The Release-installed rerun closes the remaining executable-origin gap at
commit `a7d4ef4d`, app APK SHA-256 `2cad204f7d3c...6cdad8`, and test APK
SHA-256 `8c1984f0a670...22ea9c`. On the same S25 and runtime `2.1.5-bss.2`, the
runner loaded the official six-stem, official four-stem, and guitar-ft model
and exact-name sidecar from the product install store. Artifact SHA-256 values
were `8b19e919...ba4ed7`, `98557180...ed81`, and `ab632a5a...9b9a5`; sidecar
SHA-256 values were `c9c288a4...1fcd6`, `6ba70a42...f6fe5c`, and
`fa18936f...11865`. Every fixture identity matched its installed sidecar, STFT
maximum error was `4.77e-7` for all three, and every ordered stem passed Frozen
V2. The Android strict-host projection remains diagnostic-only as frozen above;
it is not substituted for the energy-aware device gate.

Product integration then advanced in four independently tested steps:
`34dbc64d` connected Android source decoding and the installed CPU session;
`8f8c9187` separated pause from cancellation; `660c4f31` added the exact-model
cache lifecycle engine; and `f25e143a` exposed the installer-to-engine product
boundary. This is local deterministic lifecycle evidence only. It does not
satisfy the open real-Release download, independent-process, device resource,
full-song parity, playback, or listening gates.

The first real product-path S25 matrix is frozen in
`docs/validation/htdemucs/phase6-product-path-s25-2026-08-07.json`. It exposed
and fixed two integration gaps before passing: the published v3 catalog's
structured `validation` field was absent from the app schema, and the shared
cache repository recognized only installed MDX presets. At commit `411204b0`,
all three exact Release pairs then independently completed a 30-second,
six-window MediaStore-to-product-engine run, published ordered 4/6-stem WAV
caches, reopened a generic playback lease, promoted every stem to indexed
FLAC, removed WAV/work/segment temporaries, reopened the promoted lease, and
returned `AlreadyCompleted` on re-entry in 38-40 ms. Inference took
22.107-22.342 s and FLAC promotion 5.802-8.882 s. Peak PSS was
1,229,923-1,298,284 KiB with thermal status `0` before and after. These rows
close Release acquisition and same-pipeline product publication on S25; they
do not close host PCM parity, S10 resources, process lifecycle, actual
`PlaybackService` multi-stem routing, or listening.

The matching S10 matrix is frozen in
`docs/validation/htdemucs/phase6-product-path-s10-2026-08-07.json` with the
same APKs, runtime, downloaded Release pairs, source PCM, cache, and FLAC
gates. Official six-stem, official four-stem, and guitar-ft took 47.462 s,
98.258 s, and 56.370 s respectively for the 30-second source, so all three are
offline-only on this device; official four-stem is decisively the slowest and
must not be presented as the lower-resource choice. Peak PSS ranged from
1,236,240 to 1,294,985 KiB, and official four-stem raised thermal status from
`0` to `1`. The S10/S25 official-six PCM16 comparison found at most one LSB of
difference per stem and 96.853 dB SNR after summing all stems, consistent with
cross-device FP32 rounding rather than a product-pipeline split. This closes
the first S10 full-track/resource observation but not the combined Phase 6
cancellation, process-death, recovery, switching, or contention item.

User cancellation and restart are independently frozen in
`docs/validation/htdemucs/phase6-cancel-restart-s25-s10-2026-08-07.json`.
Official six-stem was canceled after the first of six segments became ready on
S25 and S10. Both runs durably recorded `Canceled`, retained exactly the one
complete segment, reset the running segment, and exposed no completed cache.
Re-entry used the same cache identity but deliberately rebuilt the
non-resumable full-track WAV writer state, completing six stems in 21.254 s on
S25 and 48.210 s on S10. This closes manual cancellation and clean restart for
the current in-process product engine; process death, independent background
ownership, and background contention remain separate gates.

Active-model replacement is independently frozen in
`docs/validation/htdemucs/phase6-active-model-supersession-s25-s10-2026-08-07.json`.
On S25 and S10, official six-stem was superseded immediately after its first
ready segment. Both runs retained exactly `1/6` old segments in a partial cache,
recorded `Paused` with `ActiveModelSuperseded`, and completed official four-stem
under a distinct exact-model cache key. The first S10 attempt exposed one extra
window of work because pause was observed only at HTDemucs window boundaries;
`ddfc098a` passes the combined pause/cancel interrupt probe into source reads
and the LiteRT session. The fixed build paused in 8.352 s on S25 and 16.632 s on
S10, then completed the replacement in 21.555 s and 43.756 s respectively.
This closes in-process exact-model supersession and retention, not process
background ownership, or real `PlaybackService` adoption.

The multi-stem engine now also has a JVM process-generation handoff test. It
abandons an admitted `Running` owner without a terminal transition, then
re-enters the same exact cache with a new generation and owner PID. The
coordinator records `PreviousOwnerDied`, removes the non-resumable writer
state, and the HTDemucs engine publishes a complete ordered four-stem result.
This proves the durable cache handoff contract only; an actual independent
background owner and real `PlaybackService` adoption remain open.

The first production-shaped remote multi-stem process-death gate is recorded in
`docs/validation/htdemucs/phase6-remote-process-death-s25-s10-2026-08-08.json`.
On both arm64 devices, the test killed the dedicated `:source_separation`
service after the first ready window, observed the caller return from the dead
Binder, and retried through the normal product facade. The replacement process
published a completed cache with a strictly newer process generation and a
`PreviousOwnerDied` journal transition. This closes remote process death and
cache recovery for the current in-process-background policy; it does not close
independent background ownership or real PlaybackService multi-stem adoption.

The first real `PlaybackService` product gate used the installed bss-tflite
Release pairs and the 30-second Coast Town source on S25. It exposed and fixed
one missing product boundary: playback resolution only consulted the legacy
MDX preset selection, and the first multi-stem adapter omitted the
`htdemucs-cpu-fp32-v1` render profile from the exact cache identity. The fixed
path persists multi-stem playback selection independently, resolves the exact
multi-tensor contract for playback without routing it through the MDX engine,
and preserves the existing MDX scheduling path. Official four-stem adopted
`drums,bass,other,vocals`; official six-stem adopted
`drums,bass,other,vocals,guitar,piano`. Both completed pause, one-third-track
seek, and resume through `MediaController` with zero additional underruns.
This closes basic completed-cache 4/6-stem routing on S25, not guitar-ft
listening, producer-ahead playback, the remaining service-recreation and cache
deletion rows above, S10 playback resources, or background contention.

The completed-cache active-model switch gate then ran on the same S25 product
path. Playback started from official six-stem
`drums,bass,other,vocals,guitar,piano`, changed the persisted exact selection,
and adopted only official four-stem `drums,bass,other,vocals`. The old and new
caches had distinct cache keys, the inactive six-stem cache remained readable,
and the replacement session completed pause, one-third-track seek, and resume
with zero underruns. Selection generations now invalidate both cached session
reuse and an already installed data-plane session; the change does not delete
the superseded cache or route either model through the MDX scheduler. This
closes completed-cache model switching on S25, not switching while production
is still partial, the remaining service-recreation and cache-deletion rows, or
S10 playback resources.

**Exit:** each of the three exact artifacts has its own CPU-only experimental
activation decision. No broad Demucs, GPU, NPU, or unrelated multi-stem support
is implied.

### Phase 7: Catalog grouping and controlled activation

- [x] Add independent, app-owned family/purpose/category metadata for all 33
  candidates in the published `bss-tflite` v0.2.0 experimental catalog. The
  reviewed table is keyed by exact `modelId`; it does not infer semantics from
  filenames, localized display names, or artifact families at runtime. Unknown
  IDs remain inspectable but unclassified.
- [x] Record the official six-stem candidate as the representative experimental
  multi-stem recommendation, official four-stem base as the four-stem option,
  and guitar-ft as the six-stem guitar-specialist option. Keep 9662 as the
  sole stable Quick Setup representative; the multi-stem representative does
  not alter that stable recommendation.
- [x] Add representative metadata and folded category records to the current
  preset-management surface without changing the stable two-stem Quick Setup
  recommendation. Experimental and candidate sections use the reviewed
  category records; collapsed rows expose only title, summary, and expansion
  affordance. Unknown records remain visible in an explicit fallback group.
- [x] Expose the three HTDemucs Release entries through the same management
  surface, while preserving their separate multi-stem installer, exact
  contract, and CPU-only activation boundary. Their download, selection,
  deletion, details, and supersession paths remain distinct from the MDX
  repository implementation.
- [x] Route a selected, installed multi-stem model through the runtime facade
  for both ordinary separation and playback resolution. Missing selected
  multi-stem artifacts fail as `ModelNotInstalled`; they never silently fall
  back to the active MDX model. The dedicated HTDemucs executor remains
  CPU-only and returns a typed multi-stem completion result.
- [x] Keep unsupported generic models inspectable and downloadable but blocked
  from activation.
- [x] Expose a model only when its exact contract, pipeline, playback, and
  device evidence support the selected activation policy.
- [x] Show model-specific CPU-only compatibility and download/install size;
  never imply that four stems are the lower-resource choice, because the
  official four-stem artifact and measured S10 workload are larger/slower than
  official six-stem.

**Exit:** catalog grouping is presentation over validated metadata, not a new
source of model semantics.

#### Phase 7A: Reviewed presentation metadata

Phase 7A is complete for the domain layer. The app now has an independent
presentation table covering every model in the published 33-entry Release,
with explicit family, purpose, category, and representative roles. It also
provides deterministic category grouping and exact-coverage validation. This
layer is deliberately separate from executable contracts, cache identity,
activation policy, and localized labels. The three HTDemucs records are now
connected to the same management surface while unknown or unsupported models
remain inspectable without receiving activation.

#### Phase 7B: Folded preset-management categories

Phase 7B is complete. The management sheet carries the reviewed presentation
record with each catalog item and renders experimental/download candidate
items under collapsed category rows. The stable recommended row is
intentionally unchanged. The three HTDemucs entries are supplied by the
separate Release installer but use the same management surface; their
installer, exact contract, CPU-only activation, and model-specific details
remain separate. Runtime routing and selected-model supersession are covered
by facade tests. A production-graph AndroidTest now refreshes the real Release
catalog, expands the `MultiStem` category, selects the official six-stem
candidate through its experimental confirmation, verifies the exact selection
store and installed record, and restores the previous selection. The S25 run
passed with the current `bss-tflite` artifact source; this validates management
activation only and does not close separation, playback, resource, or listening
gates. Device playback/resource/listening gates remain independent.

The first S25 management verification used `SM-S9310`, API 35, arm64. The
management Compose suite passed after updating its KARA action to expand the
collapsed category before selecting the model. The production Release install
gate then reopened the exact official six-stem artifact and sidecar through
the application graph: 117,624,880-byte model, SHA-256
`8b19e919dd17c6a93d862ca9b1158ed72f09feb4c52745819346369506ba4ed7`,
contract `htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0@1`, and CPU-only backend
policy. This is catalog/installation evidence; it does not replace separation,
playback, resource, or listening qualification.

The follow-up production-graph management test verifies the exact displayed
download/install payload and pre-activation backend details for all three
entries. Official six-stem reports `117,635,940` bytes, official four-stem
reports `178,052,690` bytes, and guitar-ft reports `117,742,547` bytes; every
detail record exposes only `cpu`. The test explicitly asserts that official
four-stem is larger than official six-stem so the category cannot regress into
using stem count as a resource estimate.

The generic-target Compose gate expands the reviewed `TargetStem` category for
`kuielab_a_bass`, verifies that the model remains visible and downloadable,
and asserts that no use action is rendered. This matches the catalog validator
and activation resolver: a generic target/residual model remains blocked until
it has a reviewed executable contract and neutral playback semantics.

Multi-stem activation now also compares the installed model ID, model and
sidecar filenames, model byte size and SHA-256, contract ID, and pipeline ID
against the current immutable Release entry before enabling use. A stale
same-name installation remains visible and deletable but cannot be activated.
Unit tests reject mismatched hashes, sizes, contracts, and sidecars; the S25
production-graph management rerun confirms that each exact installed Release
pair remains selectable through the experimental confirmation path.

The S25 completed-cache service-recreation rerun is recorded in
`files/source-separation/multistem-product-device-reports/
playback-recreate-s25-20260809-r1.json`. It used the same `SM-S9310`, API 35,
arm64 product path and official six-stem artifact, resolved a completed cache
without re-running inference, and passed with `serviceRecreated=true`,
`seekUnderruns=0`, and `allowedSeekUnderruns=0`.

The strict S10 rerun is recorded in
`files/source-separation/multistem-product-device-reports/
playback-recreate-s10-strict-20260809-r1.json`. It passed on `SM-G9730`, API
31, arm64 with the same exact cache identity, six ordered stems, and zero seek
underruns under the default allowance of zero.

The S25 idle-management deletion gate used the production cache-management
ViewModel and Compose delete button against completed official six-stem cache
`1227059621e8596ea33945b7a834c1f733d71097d1f7febc3f57f6312c96c1ac`.
The entry disappeared from both the repository and rendered list, its cache
directory was removed, and the foreground worker remained idle. Because no
playback session was active, this evidence does not cover lease release or the
`PlaybackService` cache-deleted notification.

The S25 active product-path rerun `playback-delete-s25-20260809-r2` adopted the
same exact six-stem cache through `PlaybackService`, disabled separated
playback, released the lease, deleted the cache, and completed the
`cache-deleted` notification path while retaining the original transport. The
test is intentionally separate from the management-panel gate because it
drives the service command directly.

The S25 producer-ahead gate `producer-ahead-s25-20260808-r1` started a fresh
official six-stem `PlaybackDemandWindow` run and waited for two complete
ordered stem block sets before enabling separated playback. The partial cache
became playable at `12.500 s`; the producer completed the 30-second source at
`22.870 s`. Media3 retained the ordered
`drums,bass,other,vocals,guitar,piano` session throughout, with zero added
low-water events, underruns, or playback stall transitions. This qualifies the
two-window policy only for this S25 official-six-stem row. It does not imply
that S10, official four-stem, or guitar-ft can sustain producer-ahead playback.
The current product contract no longer waits for two windows at startup: it
adopts a partial session when the current window is ready, pauses only after
the playback head actually enters an unready window, and applies the configured
window count only as the recovery waterline after that miss.

The S25 `active-panel-delete-s25-20260808-r1` gate launched the real
`MainActivity`, adopted the completed official six-stem cache through
`PlaybackService`, navigated to the production cache-management page, and
pressed the cache row's delete button. The page removed the entry, the
repository removed the exact cache, the playback lease was released, separated
playback was disabled, and the original song retained ready/play intent. The
selected model remained installed and selected; only the active song cache was
deleted.

### Phase 8: Converge MDX and HTDemucs lifecycle behavior

This phase closes cross-family differences found after the first multi-stem UI,
cache, and playback integration. It preserves the intentional DSP and control
differences while making selection, scheduling, recovery, product management,
and diagnostics obey one lifecycle contract.

#### Phase 8A: Unified execution selection and exact admission (complete)

- [x] Project the MDX preset selection and HTDemucs playback selection into one
  `SourceSeparationExecutionSelectionSnapshot` with family, model ID, exact
  artifact/contract/profile/pipeline/render identity, and one persisted global
  generation.
- [x] Capture that snapshot in full-song and prestart requests and use it for
  both MDX and HTDemucs recovery admission, stale callback rejection, worker/UI
  filtering, deletion preflight, and debug reporting.
- [x] Make `PlaybackService` observe one selection flow, invalidate an admitted
  session on any exact identity mismatch, and remove stale requested stem sets
  when switching families.
- [x] Keep inactive partial and completed caches intact; selection affects
  admission and visibility, not retention.

Focused JVM coverage passes for exact playback reuse, worker request identity,
and cross-family generation behavior. The GitHub Debug APK also builds. Product
verification used the ADB debug provider (`model.select`, `playback.song`,
`separation.output`, and `state`). On S25, switching from an active official
four-stem run to MDX paused the old run and rejected its delayed pause callback
as stale. The final A-to-B-to-A check ran on S10 with the updated playback path:
the unified generation advanced exactly `4 -> 5 -> 6`; the current exact cache
identity changed from official six-stem
`30f00aeadcb3ce9f715e21e9e69d6e9ed1c56b7028f59e50d9c27ffda822fd7e`
to MDX 9662
`1f05653087928bad4cbdc9f6c8ecf39f25d77a08d920ea7d3612d43e35bceb3f`
and back; no superseded playback session or current-cache marker survived the
family switch.

#### Phase 8B: Resumable HTDemucs execution (complete)

- [x] Preserve committed multi-stem segments across pause, process death, and
  app restart instead of resetting the working cache. Recovery trusts only the
  journal-committed per-segment WAV artifacts; temporary whole-track work files
  are rebuilt and cannot overwrite committed output.
- [x] Persist and validate a small model/source/geometry-bound normalization
  checkpoint. If it is missing or corrupt, it is discarded and recomputed.
  OLA state is deterministically rebuilt by replaying at most the preceding
  boundary window before the first pending segment, so no large float buffer is
  persisted and the overlap boundary remains exact.
- [x] Verify manual pause/resume and forced application/inference-process death
  through the Debug control provider on the official four-stem contract. On S10
  (`SM-G9730`, API 31, arm64), `Colour Spectrum` was paused at `1/11`, resumed
  to `3/11`, then the app and `:source_separation` process were force-stopped.
  After normal Launcher restart the same cache key retained `5/11` and
  continued to `11/11` completed; no ready segment returned to zero and
  separated output stayed admitted during recovery. The short 4-stem
  12-second product-path smoke also completed `3/3`.

The JVM range-runner, OLA, engine, and cache-coordinator regression tests pass,
including uninterrupted-versus-resumed PCM equality and invalid-checkpoint
recomputation. Six-stem execution remains covered by the existing ordered
range-runner tests; full six-stem device recovery remains a follow-up resource
gate, not a prerequisite for this lifecycle fix.

#### Phase 8C: Shared scheduling and playback-demand progress

- [ ] Give HTDemucs the same scheduler snapshot, playback position, ready
  waterline, prefetch stop, timing, and source-decode diagnostics contract used
  by MDX.
- [ ] Stop next-song work after the requested ready window count and resume only
  when demand changes.
- [ ] Keep timing statistics separated by family, model/profile, and backend.

#### Phase 8D: Product management parity

- [ ] Make Quick Setup understand an active HTDemucs selection and never commit
  a hidden MDX recommendation over it.
- [ ] Make Cache Management report, activate, and delete MDX and HTDemucs exact
  models through one family-aware action contract.

#### Phase 8E: One remote execution authority

- [ ] Enforce one process-wide execution owner across MDX and HTDemucs, then
  converge the two service/control protocols where doing so removes duplicate
  lifecycle state.
- [ ] Map remote death to one recoverable product error and replace the fixed
  30-minute HTDemucs await timeout with journal/process-lifecycle observation.

#### Phase 8F: Diagnostics and remaining duplication

- [ ] Localize all HTDemucs stages and expose family-specific ETA and window
  timing through the existing progress surfaces.
- [ ] Keep next-song prefetch ownership only in `PlaybackService`.
- [ ] Reuse the shared source-preflight memo for multi-stem resolution.
- [ ] Remove the unused two-file mixer compatibility entry point after 2/4/6
  stem regressions pass.

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
- full-song reconstruction before any experimental activation;
- same-weight Torch versus product CPU PCM16 parity for official six-stem,
  official four-stem base, and guitar-ft separately;
- no partial-cache publication after one neural-output branch fails; and
- license, attribution, training-source, conversion, and modification notices
  preserved in the guitar-ft model details and release artifact.

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
- Freeze the first three Demucs candidates as CPU-only. A global GPU or NPU
  preference cannot override an artifact's backend qualification.
- Keep official six-stem as the experimental multi-stem representative; do not
  describe official four-stem base as a low-resource model.
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
   unknown pipelines remain installed and inspectable but blocked from
   activation.
8. Which current HTDemucs candidates should enter the first product catalog.
   Decision: official six-stem, official four-stem base, and guitar-ft are exact
   CPU-only experimental candidates and are selectable for explicit testing.
   Keep them outside stable recommendation until individual executable
   contract, layered parity, full-song, resource, lifecycle, and listening
   gates pass. Preserve the guitar-ft author/base-model/MoisesDB notices in
   every published form. Do not generalize MDX GPU/QNN evidence or a
   neural-core hybrid to Demucs.
9. Whether CPU-only qualification should imply all-ABI support. Decision: no.
   Begin with arm64 device qualification. Admit arm32, x86, or x86_64 only from
   separate allocation, address-space, sustained, and lifecycle evidence for
   the exact artifact.

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
- the representative-content PCM16 delta is reproduced and attributed without
  silently weakening the frozen numerical gate;
- the three frozen first-batch artifacts each receive an explicit CPU-only
  experimental activation or fail-closed decision from host, device,
  full-song, lifecycle, and listening evidence; and
- catalog categories and activation policies are derived from validated
  metadata rather than filenames or localized text.

The three frozen HTDemucs candidates are selectable experimental entries.
Product-path download, the frozen Athletics II full-song numerical/resource
row, lifecycle, cache/playback, and same-weight human listening pass. They
remain experimental while the broader-content PCM16 discrepancy is attributed;
passing this listening gate does not by itself assign a stable catalog tier.
The stable MDX two-stem path remains the product default, with all other
published models available for explicit experimental testing.
