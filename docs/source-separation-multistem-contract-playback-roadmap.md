# Source-Separation Contract and Multi-Stem Data-Plane Roadmap

Status: active pre-implementation roadmap and design authority for stem
identity, model contracts, imported metadata, cache output shape, and playback
data flow.

Updated: 2026-08-04

This roadmap prepares Booming SS for more than two rendered stems while
preserving the currently qualified MDX two-stem product path. It combines the
following previously separate requirements:

- model repositories provide canonical English stem labels;
- the application resolves known labels to localized resources and falls back
  to the original label;
- imported models can carry user-entered labels;
- contracts describe output semantics rather than relying on filenames;
- separation, cache, hydration, and playback use a variable-size stem set; and
- model catalog tiers and model-purpose groups remain independent from labels.

This document does not design the visual controls for a multi-stem mix. It
defines the data and execution contracts those controls will consume.

## Authority and Boundaries

This roadmap is authoritative for:

- logical stem identity and canonical labels;
- the portable model-contract shape for two-stem and multi-stem pipelines;
- imported sidecars and custom profile metadata;
- separation result and cache representations;
- hydration, playback-session, and multi-stem audio data interfaces; and
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

The related MusicSourceSeparation experiments justify preparing the data plane,
but do not yet justify shipping a Demucs preset:

- the project-generated HTDemucs 6-stem two-second neural core passed host
  parity and completed one S25 Compiled CPU run;
- the same smoke artifact completed a GPU plus CPU hybrid run, but strict GPU
  creation failed and the run is not strict GPU evidence;
- QNN produced IR but failed before a usable `CompiledModel` and inference;
- a third-party 7.8-second neural core ran on S25 CPU, while its complete DSP
  boundary was not reproduced; and
- canonical full-window project-owned export, full-song DSP, peak memory,
  thermal behavior, and listening quality remain open.

The frozen reports are maintained in the
[`WluhWluh/MusicSourceSeparation`](https://github.com/WluhWluh/MusicSourceSeparation)
research repository:

- `docs/android-litert-demucs-multistem-feasibility-2026-08-03.md`;
- `docs/android-litert215-demucs6-s25-2026-08-03.md`; and
- `docs/android-litert-demucs6-bandbuddy-s25-2026-08-03.md`.

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
- `SourceSeparationCacheManifest.kt`, `SourceSeparationCacheHydrator.kt`, and
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

FLAC promotion, deletion, pruning, hydration, and total-byte accounting must
iterate over the set. A song/model cache remains one retention entry, while
its size naturally grows with stem count. Hydration remains cache data and may
be removed by system clear-cache or explicit cache cleanup.

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

The playback cache object, Hydration marker, and playback session should expose
an ordered list of `PlaybackStemSource` values instead of two file properties.
The list is validated as a complete set before a session is installed.

For a generic multi-stem path, use the original song as the Media3 clock and
read all separated stems as synchronized side inputs. Do not select
Instrumental as the implicit base stream; that assumption does not exist for
Bass/Drums/Guitar/Piano models.

The mixer API should eventually accept an immutable stem set and a per-stem
gain snapshot. UI design is explicitly outside this roadmap. The data plane
must nevertheless guarantee:

- one synchronized read per active stem for each audio block;
- preallocated scratch and resampling state, with no allocation in the audio
  callback;
- floating-point or wide-accumulator summation followed by one final clamp;
- atomic seek and hot-swap of the complete set;
- exact sample-rate, channel-count, and frame-count validation; and
- an explicit original-audio bypass rather than an implicit synthetic residual.

For N greater than two, compressed FLAC streams must not be decoded lazily by
multiple audio-thread readers as the default path. Background Hydration to
PCM should complete for the required set before playback, with the existing
readiness/hot-swap mechanism used to replace a temporary source set.

The first implementation should retain separate per-stem cache files for
inspection, export, deletion, and recovery. A future packed hydration file may
reduce file-descriptor and seek overhead, but it is an optimization to measure
after the list-based implementation is correct.

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

- [ ] Inventory every two-stem field in execution, cache, Hydration, IPC,
  playback, diagnostics, and tests.
- [ ] Freeze `StemDescriptor`, stable `stemId`, extensible semantic ID, and
  canonical-label rules.
- [ ] Decide the new contract kind/schema and the normalization from current
  MDX v2 contracts.
- [ ] Freeze direct, residual, and pipeline-native derivation types.
- [ ] Add bss-tflite contract-policy tests for English canonical labels and
  ordered output bindings.

**Exit:** the contract can describe current 9662, HQ4, and KARA without
localized text and can describe a 4/6-stem output without pretending it is an
MDX residual pair.

### Phase 1: Dynamic labels on the stable two-stem path

- [ ] Implement the explicit label resolver and base/localized resources.
- [ ] Drive settings endpoints, model details, cache details, progress, and
  timing from the active or cached contract.
- [ ] Add model-output and residual label fields to manual import and profile
  editing.
- [ ] Preserve exact labels in profile and cache snapshots.
- [ ] Test 9662, reversed HQ4 orientation, KARA, unknown English labels, and
  user-entered non-English labels.

**Exit:** no user-visible track name is supplied by a fixed
Vocals/Instrumental string when a contract is available.

### Phase 2: List-based execution and cache foundations

- [ ] Introduce list-based window results, segment artifacts, journals, IPC
  descriptors, and playback cache objects while keeping the MDX adapter's
  output unchanged.
- [ ] Add explicit stem role/binding data where semantic IDs are insufficient.
- [ ] Bump new manifest/Hydration/pipeline schemas under the clean-install
  boundary; do not migrate old experimental entries.
- [ ] Make segment commits, cancellation, recovery, promotion, deletion, and
  pruning operate on complete stem sets.
- [ ] Add synthetic 2-, 4-, and 6-stem cache fixtures.

**Exit:** the current two-stem product passes all existing lifecycle and cache
tests through the list-based data types.

### Phase 3: N-stem playback data plane

- [ ] Generalize Hydration and the playback session to a complete ordered stem
  set.
- [ ] Implement the list-based mixer behind the existing two-stem behavior.
- [ ] Add atomic seek, hot-swap, missing-stem rejection, clipping, resampling,
  and process-recreation tests.
- [ ] Measure audio-thread load, underruns, PSS, file descriptors, and cache
  size with synthetic 4- and 6-stem PCM/FLAC fixtures on S25, S10, and the
  available emulators.

**Exit:** N-stem playback is technically stable with synthetic outputs; this
does not yet activate a multi-stem model.

### Phase 4: Multi-tensor pipeline contract and neural-core adapter

- [ ] Implement the static multi-input/output contract loader and strict
  tensor-axis validation.
- [ ] Add a separate HTDemucs pipeline adapter for host DSP and branch
  reconstruction.
- [ ] Freeze official-weight provenance, converter identity, output order, and
  fixture files in a distinct executable contract.
- [ ] Run host PyTorch/LiteRT parity before any product device claim.
- [ ] Run the canonical S25 CPU allocation gate before GPU or QNN experiments.

**Exit:** a canonical multi-stem candidate produces verified per-stem PCM on
the host and one device window without falling back to an undeclared pipeline.

### Phase 5: Experimental model qualification

- [ ] Complete canonical 7.8-second and full-song DSP validation.
- [ ] Measure peak PSS, native/graphics memory, thermal behavior, cancellation,
  process death, resume, and cache recovery.
- [ ] Qualify CPU first; qualify GPU/QNN only with explicit delegation and
  per-stem numerical evidence.
- [ ] Perform human listening tests against the host reference.
- [ ] Keep the model download-only until every required gate passes.

**Exit:** a model-specific catalog decision exists. No broad multi-stem
promotion is implied by a successful smoke test.

### Phase 6: Catalog grouping and controlled activation

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
- FLAC promotion and Hydration integrity for every stem;
- deletion while a multi-stem run is active; and
- exact-active-model switching with retained inactive caches.

### Playback

- deterministic per-stem tones to detect ordering swaps;
- all-stem sum, single-stem, mute, seek, pause/resume, and hot-swap;
- sample-rate conversion and unequal-length rejection;
- compressed-source Hydration before audio-thread use;
- audio underrun and frame-time observation on S10 and S25; and
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
- Keep separate per-stem rendered files; defer packed hydration optimization.
- Use the original source as the generic playback clock and treat all model
  stems as synchronized side inputs.
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
2. Whether Hydration should later use one packed PCM file. Recommendation: keep
   per-stem files until multi-stem audio-thread measurements justify packing.
3. Whether all-stem playback should use model sum or original-source bypass when
   gains are neutral. Recommendation: make both explicit states and do not
   silently apply a residual correction.
4. The final maximum stem count and memory admission policy. Recommendation:
   start with eight and derive a higher limit only from device measurements.
5. Whether an imported multi-stem sidecar may declare a custom pipeline.
   Recommendation: only reviewed, app-bundled pipeline IDs may activate;
   unknown pipelines remain installed and inspectable but download-only.

## Completion Definition

This roadmap is complete only when:

- the contract and cache layers no longer assume exactly two stems;
- known English labels localize and unknown labels remain unchanged;
- imported profiles preserve and expose their labels;
- model-output order cannot be confused with display order;
- separation, recovery, cache deletion, Hydration, and playback operate on one
  coherent stem set;
- active-model switching preserves exact cache isolation;
- at least one 4- or 6-stem pipeline passes host, device, full-song, and
  listening gates; and
- catalog categories and activation policies are derived from validated
  metadata rather than filenames or localized text.

Until then, non-MDX multi-stem candidates remain download-only and the stable
MDX two-stem path remains the product default.
