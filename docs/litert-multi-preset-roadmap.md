# LiteRT and Multi-Preset Roadmap

Status: active plan for `feature/litert-multi-model-presets`

Updated: 2026-07-19

This document is the plan for the next Booming SS development stage. The
older `source-separation-roadmap.md` remains the historical record of the
ONNX-based prototype. This document supersedes its runtime and model-acquisition
plan, but keeps the playback, cache, and UI behavior already implemented by
Booming SS unless a phase below explicitly changes it.

## Direction

Booming SS will migrate from ONNX Runtime to LiteRT and will no longer ship or
load ONNX models. The app will provide a catalog of converted TFLite presets
hosted by the companion `bss-tflite` repository. Users will be able to install
one or more official presets, choose the active preset, and import their own
TFLite file.

The migration is intended to reduce installed native runtime size and improve
arm64 phone performance, while preserving the existing two-stem playback,
blend, cache, and background-processing behavior.

The app will not provide a Play Store release path. GitHub and F-Droid remain
the release targets currently maintained by this fork.

## Baseline

The stable Booming SS implementation is the seven-commit stack on top of the
rebased upstream branch:

- upstream baseline: `3a30569b`
- structured Booming SS tip: `d6613e7c`
- development branch: `feature/litert-multi-model-presets`
- model conversion repository: `C:\Users\User\Documents\BSSModels\bss-tflite`
- playback and cache reference project:
  `C:\Users\User\Documents\MusicSourceSeparation`

The first TFLite artifacts were converted to static batch-1 float32 FlatBuffers
and validated against ORT desktop output. The Android comparison established
that LiteRT 2.1.5 is preferable to `tensorflow-lite:2.16.1` on the tested S10
and S25 devices. The old TFLite runtime is a compatibility reference only and
must not become a second production runtime.

## Product Decisions

### In scope

- LiteRT 2.1.5 inference for MDX two-stem models.
- CPU execution on supported Android devices.
- GPU execution when the LiteRT GPU backend initializes and runs successfully.
- Automatic GPU-to-CPU fallback for a single separation session.
- Multiple official TFLite presets installed side by side.
- One active preset selected for new separation work.
- User-imported TFLite files with structural validation and visible hash data.
- Versioned preset metadata that describes DSP and stem semantics.
- Model-aware cache identity and safe handling of old ONNX-era caches.
- Removal of ONNX Runtime, ONNX model download, and ONNX import paths.
- GitHub/F-Droid builds only.

### Out of scope

- In-app conversion from ONNX, PyTorch, or checkpoint formats.
- Cloud separation or remote inference.
- Guessing DSP parameters or stem meaning from a TFLite tensor shape alone.
- Treating a hash mismatch as proof that a user file is unusable.
- Reintroducing `tensorflow-lite:2.16.1` as an x86 fallback.
- Play Store or AAB publishing.
- Automatic model updates from a mutable `latest` URL.
- Redistributing arbitrary user-imported weights.

## Model Contract

A TFLite FlatBuffer contains tensor structure, but it does not reliably encode
the application-level meaning needed by the MDX pipeline. In particular,
`dimF` and the mapping of output channels to vocals or instrumental cannot be
derived safely from the file without a model-specific contract.

Every usable model must therefore have a contract with at least:

- contract schema version;
- stable model ID and display name;
- TFLite file name, byte size, and SHA-256;
- source URL and source attribution where applicable;
- conversion repository and conversion-tool version;
- input and output dtype;
- input and output layout, currently NHWC;
- exact static input and output shape;
- `dimF`, `dimT`, `nFft`, sample rate, and hop length;
- channel count and batch size;
- target stem meaning for each output channel;
- complement or residual reconstruction rule;
- pipeline compatibility version;
- minimum and known-good Android ABI/backend information.

The contract must be stored separately from the weight file. Official catalog
entries supply it authoritatively. The conversion scripts in `bss-tflite`
must emit the same contract format so users can prepare compatible custom
models without reverse engineering the app.

### Custom import policy

The import screen should keep the existing non-blocking hash style:

- copy the selected TFLite file into app-private storage atomically;
- calculate and display its actual SHA-256;
- show a matching official catalog entry when the hash is known;
- show a warning, but do not reject, an unknown or mismatched hash;
- inspect dtype, rank, layout, and static shape before allowing a session;
- load a sidecar contract when supplied by the conversion script;
- otherwise require the user to select or enter the model profile explicitly.

An unknown file without a contract must not silently be treated as 9662. The
safe options are to match a known hash, import a `.json` sidecar, or present an
advanced profile form for `dimF`, DSP values, and stem mapping. Structural
validation remains mandatory, while hash mismatch remains a warning.

## Initial Preset Catalog

The first catalog revision should expose these three converted artifacts:

| ID | Role | TFLite size | `dimF` | `nFft` | Expected behavior |
| --- | --- | ---: | ---: | ---: | --- |
| `uvr_mdxnet_3_9662` | Balanced vocals/instrumental default | 29,700,464 bytes | 2048 | 6144 | Default preset for normal use |
| `uvr_mdxnet_kara` | Karaoke and accompaniment-focused | 29,700,460 bytes | 2048 | 6144 | Optional preference for karaoke-like use |
| `uvr_mdxnet_inst_hq_4` | Higher-quality instrumental | 59,057,268 bytes | 2560 | 5120 | Optional quality preset; higher memory and latency |

All three use 44.1 kHz audio, hop length 1024, `dimT` 8, width 256, and
static batch-1 float32 tensors. The exact hashes and validation reports live
in `bss-tflite`; the app catalog must pin a release tag and asset hash rather
than follow a mutable branch or `latest` asset.

The 9662 preset replaces 9482 as the default. The old 9482 ONNX preset is not
silently mapped to a TFLite file. Existing 9482 cache entries are handled by
the cache migration policy below.

## Runtime Architecture

### Adapter boundary

Introduce a narrow model-runtime boundary between the existing MDX DSP code
and the inference implementation. The DSP layer should provide a model
contract and an NHWC float input; the runtime adapter should return an NHWC
float output and expose backend diagnostics.

The adapter must own:

- LiteRT model loading and interpreter/compiled-model lifetime;
- input and output tensor validation;
- NCHW-to-NHWC and NHWC-to-NCHW conversion at the boundary if needed;
- CPU thread configuration;
- GPU backend creation;
- session recreation after backend failure;
- cancellation and close behavior;
- backend name and failure reason for diagnostics.

The rest of the separation engine must not import LiteRT classes directly.
This keeps cache scheduling, DSP, and playback independent from runtime API
changes.

### Backend policy

The default policy should be `Auto`:

1. Try the GPU backend only on an ABI and device where the LiteRT GPU path is
   eligible.
2. If initialization or an invocation fails, close that session and recreate
   the same model on CPU.
3. Continue the current window on CPU when it is safe to retry.
4. Record the selected backend and fallback reason in diagnostics and cache
   timing reports.

CPU must remain a first-class path, not a test-only fallback. GPU behavior is
   device-dependent, so a failed GPU delegate must never leave playback waiting
   indefinitely or corrupt a partial cache.

Only one active inference session should be created per worker/model task.
Model selection changes should close the old session at a window boundary and
must not retain multiple large HQ4 interpreters in memory.

### ABI policy

The LiteRT artifact tested for this phase does not provide the x86 native
runtime used by the old TFLite benchmark. The app should therefore:

- gate source separation by runtime ABI capability;
- show a localized unsupported-runtime state on pure x86 when LiteRT cannot
  load;
- never load the old TFLite or ONNX runtime just to support x86;
- measure arm64-v8a and, if supplied by the LiteRT artifact, armeabi-v7a
  package sizes separately;
- decide explicitly whether general music APKs keep x86/x86_64 splits even
  though source separation is unavailable there.

The first production target is arm64-v8a on devices equivalent to the tested
S10 and S25. Pure x86 support is not an acceptance criterion for source
separation.

## Model Repository and Downloads

Replace the current single-variant repository with a catalog-aware repository:

- `PresetCatalog`: immutable app metadata for known release assets;
- `InstalledModel`: file, metadata, contract, hash state, and backend status;
- `ActiveModel`: the selected preset/profile for new work;
- download state per model, not one global download state;
- atomic temporary-file installation;
- progress and retry/mirror behavior retained from the current repository;
- deletion that cannot remove the active model without an explicit selection;
- import of a TFLite file and optional sidecar contract;
- clear distinction between installed, active, downloading, invalid, and
  unsupported models.

The catalog should be static in the app release for the first implementation.
Remote catalog updates can be considered later, but a remote response must
not change model code or DSP semantics without an app update and a pinned
contract review.

The About/model UI should explain that official weights are hosted by the
separate `bss-tflite` repository and that users converting other models should
follow its scripts and provide the resulting contract. Attribution and source
notices belong in the model-management information surface as well as the
companion repository documentation.

## Cache and Migration Policy

The existing cache scheduler, ready-window playback gate, FLAC promotion, and
blend settings remain the behavioral baseline. The cache identity must be
made model-aware before multiple presets are enabled.

Every new manifest should include:

- model ID;
- actual model SHA-256;
- contract schema and pipeline version;
- DSP parameters used for the song;
- audio fingerprint and decoder-relevant source metadata;
- output stem mapping;
- backend used for each timing record where available.

Rules for switching models:

- completed caches from another known contract remain read-only playable when
  their output stem mapping is compatible;
- new windows must never be appended to a cache generated by a different
  model hash or incompatible contract;
- partial caches that cannot be resumed with the active contract are marked
  stale and can be deleted or rebuilt;
- switching the active model does not delete other completed caches;
- cache-management UI shows the model ID and contract version for every entry;
- per-song blend settings remain attached to the cache/audio identity rather
  than the currently selected model preference.

ONNX-era completed FLAC/WAV caches should not require ONNX Runtime merely to
play their already-rendered audio. They may remain playable when their
manifest proves compatible output. ONNX-era partial work must not be resumed
as if it were a TFLite cache; it should be rebuilt or explicitly discarded.

## UI Plan

The current settings sheet and cache-management screens should evolve rather
than be replaced wholesale.

### Model management

- Add an installed preset list with role, size, hash state, and active marker.
- Show separate download progress for each model.
- Allow activating an installed model without deleting other models.
- Add a delete action that protects the active model until another model is
  selected or separation is disabled.
- Keep preset and custom import actions visually distinct.
- Keep the existing hash comparison style: informative, not an artificial
  license or compatibility gate.

### Current-song and advanced sections

- Show which model and backend generated the current cache.
- Show a clear unsupported-ABI/runtime state instead of a generic model error.
- Keep normal users focused on playback, blend, readiness, and cache actions.
- Keep detailed tensor/profile fields behind an advanced or diagnostic view.
- Remove ONNX terminology from user-facing strings after the migration is
  complete.

### Custom profile editing

When a custom file is not recognized by hash or sidecar metadata, the advanced
flow must make the missing semantics explicit. It should show:

- tensor shape and dtype discovered from the file;
- required `dimF` and DSP values;
- output stem selection and complement rule;
- a validation result before the model is activated;
- a persistent warning that the app cannot verify separation quality for an
  unknown profile.

## Implementation Phases

### Phase 0: Rebased baseline

- [x] Rebase the seven-commit Booming SS stack onto current upstream.
- [x] Preserve the no-Play-Store release policy.
- [x] Verify GitHub debug build, lint, and current source-separation code.
- [x] Push `rebuild/source-separation-structured` to the fork remote.
- [x] Create `feature/litert-multi-model-presets` from the verified tip.

### Phase 1: Freeze the model contract

- [ ] Define the app-side contract schema and Kotlin serialization types.
- [ ] Convert the three `bss-tflite` manifests into catalog entries.
- [ ] Add contract validation tests for 9662, KARA, and HQ4.
- [ ] Define explicit output stem semantics for every official preset.
- [ ] Decide how sidecar contracts are named and paired with imported files.

Acceptance criteria:

- No DSP parameter is inferred from a model file name alone.
- A known preset resolves to one complete contract and one pinned hash.
- An unknown file cannot become active without explicit profile metadata.

### Phase 2: Implement LiteRT CPU inference

- [ ] Add the LiteRT 2.1.5 dependency and isolate it behind the runtime
  adapter.
- [ ] Replace the ONNX session provider only for an internal test path first.
- [ ] Validate static tensor shapes, dtype, and layout before invocation.
- [ ] Compare desktop and Android output against the existing ORT reference.
- [ ] Preserve cancellation and session reuse at model-window boundaries.

Acceptance criteria:

- All three official models produce finite output with the expected contract.
- Real-device SNR and cosine similarity remain within the conversion reports.
- CPU inference works on S10 and S25 without changing playback scheduling.

### Phase 3: Add GPU execution and fallback

- [ ] Add the LiteRT GPU backend behind the same adapter.
- [ ] Define eligibility and failure classification.
- [ ] Recreate the model on CPU after GPU setup or invocation failure.
- [ ] Record backend, setup time, inference time, and fallback reason.
- [ ] Verify no failed GPU session leaves a worker or playback gate stuck.

Acceptance criteria:

- GPU is used where it passes validation.
- CPU fallback completes the same model window after a forced GPU failure.
- A failed GPU attempt never produces a partial cache marked ready.

### Phase 4: Multi-preset repository

- [ ] Replace `MdxModelVariant.MDXNET_9482` as the sole active path with a
  catalog-backed model ID.
- [ ] Install official presets under separate model directories.
- [ ] Track download/import state per model.
- [ ] Add active-model selection and safe deletion.
- [ ] Implement sidecar contract import and unknown-profile handling.

Acceptance criteria:

- 9662, KARA, and HQ4 can coexist without overwriting files or metadata.
- Switching models affects only new separation work.
- Official hash mismatch remains visible and non-blocking according to the
  existing import policy.

### Phase 5: Model-aware caches

- [ ] Extend cache manifests with model hash and contract version.
- [ ] Prevent incompatible partial-cache continuation.
- [ ] Keep compatible completed stem caches playable after model switches.
- [ ] Show model identity in cache management.
- [ ] Define and test legacy 9482/ONNX cache migration behavior.

Acceptance criteria:

- No cache contains windows from multiple incompatible model contracts.
- Existing completed FLAC playback does not require ONNX Runtime.
- Deleting a model does not silently delete unrelated completed caches.

### Phase 6: Remove ONNX Runtime

- [ ] Switch all production engine construction to LiteRT.
- [ ] Remove ONNX model URLs, import validation, and user-facing ONNX text.
- [ ] Remove `onnxruntime.android` and all ONNX native libraries from release
  artifacts.
- [ ] Remove obsolete ONNX-only tests and diagnostics after equivalent LiteRT
  coverage exists.
- [ ] Keep only migration code needed to read already-rendered compatible
  caches.

Acceptance criteria:

- `rg` finds no production ONNX Runtime dependency or model-loading path.
- Release APKs contain no `libonnxruntime*.so`.
- A clean install can download and use a TFLite preset without any ONNX file.

### Phase 7: Full-device validation

- [ ] Test 9662, KARA, and HQ4 on S10 with CPU and eligible GPU paths.
- [ ] Test all three on S25 with CPU and eligible GPU paths.
- [ ] Test unsupported x86 behavior and confirm graceful UI fallback.
- [ ] Test model switching during paused and active playback.
- [ ] Test seeking, ready-window gating, blend changes, FLAC promotion, and
  background continuation.
- [ ] Measure package size, install size, peak PSS, thermal behavior, and
  full-song wall time for every preset/backend.

Acceptance criteria:

- No regression in continuous separated playback, seeking, or blend changes.
- GPU failure and process/lifecycle recreation recover without a permanent
  loading state.
- Performance and memory reports are stored with the model/catalog revision.

### Phase 8: Beta readiness

- [ ] Complete model attribution and conversion guidance in the app and
  `bss-tflite` repository.
- [ ] Add all newly supported upstream language directories to the fork
  localization set, including `bqi` and `ta` introduced by the rebase.
- [ ] Review every LiteRT-specific string for terminology consistency.
- [ ] Build GitHub and F-Droid release variants only.
- [ ] Publish a beta with pinned catalog assets and reproducible hashes.

## Verification Matrix

Every runtime or model change should run the narrowest applicable checks:

| Area | Required check |
| --- | --- |
| Contract | Shape, dtype, layout, DSP, stem mapping, and hash validation |
| Conversion | Desktop LiteRT/TFLite output versus ORT reference |
| Runtime | CPU, GPU, fallback, close/recreate, and cancellation |
| Playback | Start, pause/resume, seek, song transition, blend update |
| Cache | Partial restart, model switch, FLAC promotion, delete/cleanup |
| Lifecycle | Activity recreation, process restart, background worker continuation |
| Device | Galaxy S10, Galaxy S25, and unsupported pure x86 behavior |
| Packaging | ABI splits, native library inventory, APK/install size |
| Localization | Fork string completeness and terminology review |

The most important correctness test is an end-to-end full-song comparison. A
single model-window SNR result proves tensor compatibility, but not that DSP,
window trimming, stem mapping, cache joins, or playback timestamps are
correct.

## Commit and Rebase Strategy

Keep the migration commits above the seven stable Booming SS commits. Prefer
small, buildable commits in this order:

1. model contract and preset catalog;
2. LiteRT CPU adapter and parity tests;
3. LiteRT GPU backend and fallback;
4. multi-preset repository and download UI;
5. model-aware cache migration;
6. ONNX removal and packaging cleanup;
7. device validation, documentation, and localization.

Before starting a new migration milestone:

```text
git fetch upstream
git switch rebuild/source-separation-structured
git rebase upstream/master
git switch feature/litert-multi-model-presets
git rebase rebuild/source-separation-structured
```

The fork should avoid merge commits from upstream. Rewritten branches should
be pushed with `--force-with-lease`, while release tags and backup refs remain
immutable. The structured branch is the rebase checkpoint; the migration
branch is the place for active implementation and may be rewritten until a
beta is cut.

## Open Decisions

These decisions should be settled during Phase 1 rather than hidden in
runtime code:

- exact app contract schema and sidecar filename;
- whether custom profiles use a form, a sidecar-only flow, or both;
- whether completed caches from unknown custom profiles can be played after
  the profile file is deleted;
- whether x86/x86_64 APK splits remain for ordinary music playback while
  source separation is disabled;
- exact LiteRT GPU eligibility and CPU thread policy;
- whether official catalog metadata is bundled permanently or later signed and
  fetched from a pinned release;
- cache migration behavior for old 9482 partial and completed entries;
- final installed-size and peak-memory budgets for HQ4.

The default implementation choice should favor explicit metadata, deterministic
hash-pinned assets, graceful CPU fallback, and no silent reuse of a cache or
model whose semantics are uncertain.
