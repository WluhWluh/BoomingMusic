# LiteRT and Multi-Preset Roadmap

Status: active plan for `feature/litert-multi-model-presets`

Updated: 2026-07-21

This document is the plan for the next Booming SS development stage. The
older `source-separation-roadmap.md` remains the historical record of the
ONNX-based prototype. This document supersedes its runtime and model-acquisition
plan, but keeps the playback, cache, and UI behavior already implemented by
Booming SS unless a phase below explicitly changes it.

## Direction

Booming SS will migrate from ONNX Runtime to LiteRT and will no longer ship or
load ONNX models. The app will provide a catalog of converted TFLite presets
hosted by the companion `bss-tflite` repository. The catalog will place the
first three validated presets at the top and expose a larger experimental
candidate list below them.

Downloading or importing a model and selecting the model used for new
separation work are separate operations. A completed download only makes a
model installed; it must not silently change the active model. A model that is
not active is removed only by an explicit user delete action. Model selection
changes must not delete caches or reuse another model's cache.

The LiteRT transition is intended to reduce installed native runtime size and
improve arm64 phone performance, while preserving the existing two-stem
playback, blend, cache, and background-processing behavior.

The app will not provide a Play Store release path. GitHub and F-Droid remain
the release targets currently maintained by this fork.

## Compatibility Boundary

Every feature implementation, refactor, and acceptance test in this roadmap
starts from a clean application-data state. Before installing or validating a
development build, clear all app data or uninstall and reinstall the app. No
new implementation is required to preserve state created by an earlier
Booming SS version.

The LiteRT branch must not discover, migrate, import, or otherwise depend on
old-version data, including:

- previously downloaded ONNX or TFLite model files and their manifests;
- source-separation entries under the old application-files directory;
- old partial or completed WAV/FLAC caches, hydration files, calibration
  records, debug reports, or playback traces; and
- old per-song separation settings tied to a previous cache identity.

The app may ignore residual old files if the operating system leaves them
behind, but it must not scan them or copy them into the new layout. This
boundary does not weaken normal behavior within the new implementation:
models may still be manually deleted and reinstalled, model-specific caches
may coexist, and caches created by the current version must recover after a
normal process restart or Android clears its cache.

The deliberate exception is a user-selected `.bmgbak` import through the
versioned backup compatibility path. That path may restore portable settings
and ordinary Booming Music data as described later in this document, but it is
not a general old-app-data migration and must never restore old model or cache
files.

In-place upgrades from an earlier Booming SS build, preservation of its model
downloads, and preservation of its separation results are not release or
acceptance requirements for this stage.

## Baseline

The stable Booming SS implementation is the seven-commit stack on top of the
rebased upstream branch:

- upstream baseline: `3a30569b`
- structured Booming SS tip: `d6613e7c`
- development branch: `feature/litert-multi-model-presets`
- completed Phase 1 tip: `2f32f8be`
- model conversion repository:
  [`WluhWluh/bss-tflite`](https://github.com/WluhWluh/bss-tflite)
- supplemental x86 runtime repository:
  [`WluhWluh/bss-litert-android`](https://github.com/WluhWluh/bss-litert-android)
- pinned x86 runtime release:
  [`v2.1.5-bss.1`](https://github.com/WluhWluh/bss-litert-android/releases/tag/v2.1.5-bss.1)
- playback and cache reference project:
  [`WluhWluh/MusicSourceSeparation`](https://github.com/WluhWluh/MusicSourceSeparation)

The first TFLite artifacts were converted to static batch-1 float32 FlatBuffers
and validated against ORT desktop output. The Android comparison established
that LiteRT 2.1.5 is preferable to `tensorflow-lite:2.16.1` on the tested S10
and S25 devices. The old TFLite runtime is a comparison reference only and
must not become a second production runtime.

## Product Decisions

### In scope

- LiteRT 2.1.5 inference for MDX two-stem models.
- CPU execution on supported Android devices.
- CPU execution on pure x86 through the separately built and audited
  supplemental LiteRT runtime.
- GPU execution when the LiteRT GPU backend initializes and runs successfully.
- Automatic GPU-to-CPU fallback for a single separation session.
- Multiple official TFLite presets installed side by side.
- One active preset selected for new separation work.
- A broad candidate catalog with recommended, experimental, and download-only
  entries whose compatibility state is explicit.
- Independent download, activation, and manual deletion operations.
- User-imported TFLite files with structural validation and visible hash data.
- Versioned preset metadata that describes DSP and stem semantics.
- Model-aware cache identity without an old-version cache compatibility path.
- Removal of ONNX Runtime, ONNX model download, and ONNX import paths.
- GitHub/F-Droid builds only.

### Out of scope

- In-app conversion from ONNX, PyTorch, or checkpoint formats.
- Cloud separation or remote inference.
- Guessing DSP parameters or stem meaning from a TFLite tensor shape alone.
- Treating a hash mismatch as proof that a user file is unusable.
- Reintroducing `tensorflow-lite:2.16.1` as an x86 fallback.
- Building LiteRT from source in every ordinary Booming SS CI or release job.
- Play Store or AAB publishing.
- Automatic model updates from a mutable `latest` URL.
- Redistributing arbitrary user-imported weights.

## Model Contract

A TFLite FlatBuffer contains tensor structure, but it does not reliably encode
the application-level meaning needed by the MDX pipeline. In particular,
`dimF` and the mapping of output channels to vocals or instrumental cannot be
derived safely from the file without a model-specific contract.

Every usable model must therefore have a contract with at least:

- an explicit `contractSchemaVersion` that is independent from the app version;
- stable model ID and display name;
- TFLite file name, byte size, and SHA-256;
- source URL and source attribution where applicable;
- conversion repository and conversion-tool version;
- input and output dtype;
- input and output layout, currently NHWC;
- exact static input and output shape;
- `dimF`, the actual model time dimension, `dimTPower`, `nFft`, sample rate,
  and hop length;
- the model-output compensation scale applied before residual reconstruction;
- channel count and batch size;
- primary and residual stem meaning, including generic target-stem labels when
  a model is not a vocals/instrumental model;
- complement or residual reconstruction rule;
- pipeline compatibility version;
- minimum and known-good Android ABI/backend information.

The contract must be stored separately from the weight file. Official catalog
entries supply it authoritatively. The conversion scripts in `bss-tflite`
must emit the same contract format so users can prepare compatible custom
models without reverse engineering the app.

The first contract schema is versioned independently from both the application
version and the separation pipeline version. An app update does not imply a
contract migration, and a pipeline change must not silently reinterpret a
contract whose `contractSchemaVersion` is still accepted.

A portable custom sidecar uses the exact name `<model file name>.json`. For
example, `model.tflite` is paired with `model.tflite.json`, not `model.json`.
The sidecar must contain the model SHA-256, input and output shape, dtype,
layout, `dimF`, all DSP parameters, primary and residual stem semantics,
complement rule, `contractSchemaVersion`, and pipeline compatibility version.
The importer always pairs a sidecar by the model SHA-256 recorded inside it;
the adjacent filename is a discovery aid and never sufficient identity.

The catalog must distinguish source metadata from the reviewed Booming SS
contract. ONNX metadata is useful provenance, but it is not allowed to replace
an application contract without end-to-end validation. In particular, a model
source may declare DSP values that differ from the values already validated by
the Booming SS pipeline.

### Custom import policy

The import screen should keep the existing non-blocking hash style:

- copy the selected TFLite file into app-private storage atomically;
- calculate and display its actual SHA-256;
- use a matching built-in contract immediately when the hash is known;
- show a warning, but do not reject, an unknown or mismatched hash;
- inspect dtype, rank, layout, and static shape before allowing a session;
- prefer a hash-matching sidecar contract when no built-in contract exists;
- otherwise require the user to complete an advanced profile form for `dimF`,
  DSP values, and stem mapping; and
- install the imported model without changing the active model.

An unknown file without a contract must not silently be treated as 9662. The
three supported paths are built-in contract by SHA-256, sidecar-first import,
or the advanced profile form. Structural validation remains mandatory, while
hash mismatch remains a warning. A manually entered profile must retain a
visible warning that Booming SS cannot verify its separation quality. Import
is an installation operation only; selecting `Use` remains a separate explicit
action.

## Preset Catalog and Candidate Inventory

The first catalog revision should place these three converted artifacts in its
recommended section:

| ID | Role | TFLite size | `dimF` | `nFft` | Expected behavior |
| --- | --- | ---: | ---: | ---: | --- |
| `uvr_mdxnet_3_9662` | Balanced vocals/instrumental default | 29,700,464 bytes | 2048 | 6144 | Default preset for normal use |
| `uvr_mdxnet_kara` | Karaoke and accompaniment-focused | 29,700,460 bytes | 2048 | 6144 | Optional preference for karaoke-like use |
| `uvr_mdxnet_inst_hq_4` | Higher-quality instrumental | 59,057,268 bytes | 2560 | 5120 | Optional quality preset; higher memory and latency |

All three use 44.1 kHz audio, hop length 1024, `dimTPower=8`, an actual model
time dimension of 256, and static batch-1 float32 tensors. The exact hashes and
validation reports live in `bss-tflite`; the app catalog must pin a release tag
and asset hash rather than follow a mutable branch or `latest` asset.

The 9662 preset replaces 9482 as the default. The old 9482 ONNX preset is not
silently mapped to a TFLite file. A clean app-data state has no legacy 9482
model or cache entries, and the new implementation does not provide a
conversion or migration path for them.

The initial candidate inventory contains 48 ONNX source records: 30 files from
the TRvlvr `all_public_uvr_models` release and 18 files from the k2-fsa
`source-separation-models` release. Each overlapping k2-fsa/TRvlvr pair has
identical graph and initializer data; the files differ in ONNX metadata and
packaging. The conversion pipeline must still verify all 48 source hashes, but
the release should publish one canonical TFLite artifact per semantically
equivalent graph and retain the other source records as provenance aliases.

Byte-identical TFLite SHA-256 values are not a publication gate for duplicate
sources. The conversion process must compare normalized source graph structure
and initializer data, use a pinned converter version and options, and compare
converted output numerically. If graph, weights, DSP contract, stem semantics,
and output agree, one canonical artifact may be published with `aliasOf`
records even when FlatBuffer metadata causes different file hashes. A separate
artifact is required when graph, weights, output semantics, or validated
numerical behavior differs.

The catalog should therefore contain:

- source records for all downloaded candidates, including URL and SHA-256;
- approximately 30 distinct artifact records after duplicate verification;
- `aliasOf` links for source files that resolve to the same converted artifact;
- extracted graph metadata and source-declared metadata kept in separate
  fields;
- a Booming SS contract only when the DSP and stem semantics are reviewed;
- conversion, desktop, device, and full-song validation states separately.

Every candidate is downloadable when its artifact is available, but download
availability does not imply activation support. Each entry has an explicit
support level:

- `recommended`: one of the first three presets, with the strongest validation;
- `experimental`: structurally compatible and selectable, but with limited
  device or audio validation;
- `download-only`: published for inspection or external testing, but not
  selectable until its DSP or stem contract is complete.

Models such as bass, drums, other, or reverb targets must not be presented as
vocals or instrumental by filename guesswork. They remain `download-only`
until both the generic primary-stem-plus-residual contract and generic stem UI
are implemented and validated. They may then become `experimental` with
neutral labels such as "target stem" and "remaining audio"; they must never be
opened early through the vocals/instrumental UI.

The full candidate conversion set is expected to contain about 1.36 GiB of
distinct float32 model data before FlatBuffer overhead. The app must download
models on demand and must not install the entire candidate set on first use.

## Runtime Architecture

### Adapter boundary

Introduce a narrow model-runtime boundary between the existing MDX DSP code
and the inference implementation. The existing `MdxSpectrogram` layout is the
engine-side canonical layout: a flat NCHW float tensor with shape
`[1, 4, dimF, modelTimeFrames]`. The TFLite artifact contract remains NHWC
with shape `[1, dimF, modelTimeFrames, 4]`. Runtime-neutral sessions accept
and return the engine-side NCHW representation; the LiteRT adapter alone owns
the NCHW-to-NHWC and NHWC-to-NCHW conversion. This avoids combining a runtime
migration with a rewrite of the validated STFT/ISTFT implementation.

The boundary should have three explicit ownership levels:

- a factory opens a session from a verified model artifact, execution profile,
  backend request, and runtime settings; a contract-backed profile embeds one
  complete model contract, while the temporary ORT baseline uses an explicit
  non-catalog legacy profile;
- a session validates and executes one model invocation at a time without
  exposing ORT or LiteRT types; and
- a lease allows the worker to release a use while a reusable provider keeps
  the matching session warm for a later job.

A reusable session matches only when model SHA-256, execution-profile identity
(including contract ID and pipeline version where applicable), backend, and
runtime settings all match. File path, length, and modification time are not
sufficient model identity. The internal tensor specification may represent
the legacy ONNX model's NCHW layout, but published contract schema v1 remains
NHWC-only. The first refactor must put the existing ORT implementation behind
this boundary and preserve its current production behavior before a LiteRT
implementation is added. The production worker continues to select ORT until
the explicit Phase 6 switch.
The temporary legacy 9482 execution profile therefore uses the existing DSP,
stem mapping, and an output scale of `1.0`; it is a regression baseline, not a
new catalog contract or a reason to retain 9482 after migration.

For a contract-backed path, the execution profile must construct
`MdxDspConfig` from the reviewed DSP fields before source decoding, segment
planning, STFT, or tensor allocation. No contract-backed run may inherit the
current default 2048-bin configuration implicitly. This is required for HQ4,
whose `dimF=2560` and `nFft=5120` differ from 9662 and KARA. Derived chunk,
trim, generation, and tensor sizes must agree with the tensor contract before
opening a runtime session.

The adapter must own:

- runtime environment, compiled-model, tensor-buffer, and session lifetime;
- input and output tensor validation;
- reusable NCHW-to-NHWC and NHWC-to-NCHW conversion buffers;
- CPU thread configuration;
- GPU backend creation;
- session recreation after backend failure;
- cancellation and close behavior;
- backend name and failure reason for diagnostics.

The adapter returns raw, unscaled model output. After ISTFT, the separation
pipeline applies the contract's `modelOutputScale` exactly once to the primary
waveform and only then constructs the residual as
`mixture - scaledModelOutput`. Stem semantics from the contract decide which
waveform is vocals, instrumental, or a generic target; the runtime adapter
does not own compensation or stem naming. Keeping raw inference and
post-processing separate allows parity tests to detect both runtime errors and
a missing or duplicated compensation step.

The separation engine, cache scheduler, and playback code must not import ORT
or LiteRT classes directly. Runtime-specific option construction and display
text also move out of the current ORT-shaped `MdxRuntimeSettings` API. This
keeps scheduling, DSP, and playback independent from runtime API changes and
allows the legacy ORT path to retain a behavior-preserving profile while the
contract-backed LiteRT path is tested.

### Invocation and cancellation semantics

LiteRT `CompiledModel.run` is treated as a synchronous, non-interruptible
invocation. Cancellation is checked before tensor conversion, immediately
before invocation, and after invocation/output read before ISTFT, file writes,
or cache-state changes. A cancellation that arrives during an invocation
discards that invocation's output; its segment must never become `Ready`.

No other thread may close a session while `run` is active. Session replacement
and provider shutdown occur on the worker path after the invocation returns
and at a model-window boundary. Tests must use a blocking fake session to prove
that cancellation does not cause a concurrent close, output write, or ready
transition. Pause keeps the same window-boundary semantics.

### Backend policy

The default policy should be `Auto`:

1. Check that the ABI includes the GPU accelerator, delegate creation is
   supported, the model operators are compatible, and the device has a viable
   memory budget.
2. When static eligibility is not enough, run a bounded output-validity probe
   before committing a full separation worker to GPU.
3. If initialization, the probe, or a later invocation fails, close the GPU
   session and recreate the same model on CPU.
4. Continue the current window on CPU when it is safe to retry.
5. Record only the backend actually used and the fallback reason in
   diagnostics and cache timing reports.

CPU must remain a first-class path, not a test-only fallback. GPU behavior is
device-dependent, so a failed GPU delegate must never leave playback waiting
indefinitely or corrupt a partial cache. The first implementation must not
offer a `GPU only` mode; a delegate failure must always leave a usable CPU
path.

The initial CPU thread count is:

```text
max(2, min(4, availableProcessors - 1))
```

S10 and S25 testing should begin at four threads where the formula permits it.
The exact thread count, device allowlist, memory threshold, and need for a GPU
validity probe remain tunable from Phase 3 and Phase 7 measurements. Changing
those values requires full-song wall-time, peak-memory, thermal, cancellation,
and playback-readiness comparisons; it does not require changing the model
contract. Backend timing and thread-performance statistics are runtime data
and remain excluded from backup.

Only one active inference session should be created per worker/model task.
Model selection changes should close the old session at a window boundary and
must not retain multiple large HQ4 interpreters in memory.

### ABI policy

The official `com.google.ai.edge.litert:litert:2.1.5` artifact supplies native
libraries for `armeabi-v7a`, `arm64-v8a`, and `x86_64`, but omits 32-bit
`x86`. Booming SS will retain all four ABI outputs already declared by the
project and fill only the x86 gap with the separately audited CPU-only runtime
from
[`WluhWluh/bss-litert-android`](https://github.com/WluhWluh/bss-litert-android).

The expected native inventory is:

| ABI | Provider | Expected libraries | Backend policy |
| --- | --- | --- | --- |
| `arm64-v8a` | Official LiteRT AAR | `libLiteRt.so`, `libLiteRtClGlAccelerator.so` | CPU and eligible GPU |
| `armeabi-v7a` | Official LiteRT AAR | `libLiteRt.so` | CPU-only |
| `x86_64` | Official LiteRT AAR | `libLiteRt.so`, `libLiteRtClGlAccelerator.so` | CPU and eligible GPU |
| `x86` | Pinned supplemental Release | `libLiteRt.so` | CPU-only |

The supplemental runtime adds 7,482,132 uncompressed native bytes to the x86
installation. It does not increase an ABI-specific ARM installation; the
universal APK carries the x86 payload alongside the other ABI libraries. The
convenience AAR is another package of the same binary and must not be counted
or packaged a second time.

The first accepted supplemental runtime is fixed as follows:

- Release tag: `v2.1.5-bss.1`.
- Canonical asset: `libLiteRt-2.1.5-bss.1-android-x86.so`.
- Size: 7,482,132 bytes.
- SHA-256:
  `02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af`.
- LiteRT source: `v2.1.5` at
  `9d26e89d88ef8785b6a1e54ec41ac8add215a125`.
- Toolchain: Bazel 7.7.0, Android NDK r25b (`25.1.8937393`),
  `rules_android_ndk` 0.1.3, and Android API 23.
- Build profile: x86, CPU-only, with unsupported AVX-VNNI, AVX-VNNI-INT8,
  AVX512-FP16, and AMX XNNPACK targets disabled.

The app integration policy is:

- vendor the reviewed canonical binary as
  `app/src/main/jniLibs/x86/libLiteRt.so`;
- commit that reviewed binary to the app repository; Gradle and ordinary CI
  must not download or rebuild it as an implicit build input;
- keep the official LiteRT Maven dependency as the Java/Kotlin API and the
  native runtime source for the other ABIs;
- do not consume the supplemental native-only AAR in Booming SS; it remains a
  convenience artifact for external consumers;
- do not add a `pickFirst` rule for `libLiteRt.so`; if a future official LiteRT
  release adds x86, a duplicate library must fail packaging and force an
  explicit migration review;
- verify the vendored file's SHA-256, ELF32/i386 identity, JNI exports, and
  dynamic dependency allowlist in Booming SS CI;
- keep source compilation in the low-frequency `bss-litert-android` producer
  workflow, not in ordinary app builds; and
- pin the producer Release and GitHub provenance rather than downloading a
  mutable branch or `latest` asset during a build.

The producer workflow rebuilds from the pinned LiteRT source, verifies the ELF
and JNI surface, runs an API 26 pure x86 CPU inference smoke test, creates
checksums and third-party notices, and publishes GitHub build provenance. A
LiteRT upgrade or x86 patch change requires a new producer release and a new
reviewed hash before the app updates its vendored binary.

Pure x86 is CPU-only for this stage. `Auto` must skip GPU setup there rather
than fail into CPU after an avoidable delegate attempt. The initial x86
validation passed 9662 and KARA inference and numerical comparison; HQ4 failed
allocation in the tested 2 GB, 32-bit x86 environment. HQ4 must therefore be
marked unsupported for that baseline and must not become x86-selectable unless
a later device class passes explicit memory validation.

Arm64 devices equivalent to the tested S10 and S25 remain the primary
performance and GPU targets, but pure x86 CPU support for compatible presets
is now an acceptance requirement rather than an unsupported-ABI fallback.

### Provisional resource budgets

Resource budgets use binary MiB (`1 MiB = 1,048,576 bytes`) and keep model
storage, packaged runtime size, and live inference memory separate:

- HQ4 installed model target: at most 64 MiB. The current 59,057,268-byte
  artifact is about 56.3 MiB and fits this budget.
- LiteRT native-runtime increase over the ordinary-player installation: target
  at most 10 MiB and hard limit 16 MiB for an ABI-specific installation. The
  7,482,132-byte supplemental x86 runtime is about 7.1 MiB and fits the target.
- HQ4 separation peak-PSS increase over the same device's ordinary playback
  baseline: target at most 256 MiB and hard limit 384 MiB.

GPU testing must record graphics and native allocations separately rather than
relying on Java heap or aggregate PSS alone. S10 measurements are the lower
device constraint; S25 success cannot override an S10 budget failure. A model
that exceeds a hard memory limit on a device class remains `download-only` or
is disabled there instead of risking process death. Phase 7 may tighten target
values after repeated full-song measurements, but raising a hard limit requires
an explicit roadmap update and evidence from both devices.

## Model Repository and Downloads

Replace the current single-variant repository with a catalog-aware repository:

- `PresetCatalog`: immutable app metadata for known release assets;
- `InstalledModel`: file, metadata, contract, hash state, and backend status;
- `ActiveModel`: the separately selected preset/profile for new work;
- download state per model, not one global download state;
- atomic temporary-file installation;
- progress and retry/mirror behavior retained from the current repository;
- explicit `Download`, `Use`, and `Delete` operations;
- downloading or importing a model must not activate it automatically;
- deletion is manual and cannot remove the active model until another model is
  selected or source separation is disabled;
- deleting a model must not delete its completed or partial cache entries;
- import of a TFLite file and optional sidecar contract;
- clear distinction between installed, active, downloading, invalid, and
  unsupported models;
- a distinction between selectable experimental models and download-only
  candidates.

The app should bundle a reviewed snapshot of the full catalog, including the
recommended and experimental candidate entries. The matching bss-tflite
Release should publish the catalog and manifests for audit and reproducibility,
but runtime metadata must not be fetched from a mutable branch or `latest` URL.
The app downloads only the artifact URL pinned by its bundled catalog and
verifies its size and SHA-256 before installation. A later signed remote
catalog can add discovery, but it must not change model code, DSP semantics,
or activation support without an app update and a pinned contract review.

The model-management screen should show the recommended group first and the
candidate group below it. An installed candidate may remain inactive while a
different model is used. Switching the active model changes only future work;
it does not delete, hide, or alter other installed models.

The About/model UI should explain that official weights are hosted by the
separate `bss-tflite` repository and that users converting other models should
follow its scripts and provide the resulting contract. Attribution and source
notices belong in the model-management information surface as well as the
companion repository documentation.

## Persistence and Backup Policy

The LiteRT multi-model stage must keep four different kinds of state separate:
user settings, installed model data, generated separation cache, and transient
runtime state. The current `.bmgbak` implementation copies the package-named
default `SharedPreferences` XML and restores it by replacing a file directly.
The new implementation must not extend that approach to fork-specific state:
it can capture temporary keys, runtime statistics, and cache-related values that
do not belong in a portable backup, and the package name is different between
Booming Music and Booming SS.

### Persistence classes

- Stable Booming Music user settings remain persistent and may be included in a
  backup through an explicit, versioned common-settings snapshot.
- Stable Booming SS user settings remain persistent and may be included in a
  separate source-separation settings snapshot. This includes the panel and
  quick-control visibility, separated playback state, global blend, remember-
  per-song preference, automatic separation and FLAC options, progress and
  playback messages, preroll values, ready-window count, and cache cleanup
  policy and limits.
- The active model is a persistent selection, but a backup stores only its
  stable model ID, artifact SHA-256, contract version, and profile reference.
  It never stores the model file. A restored active-model reference is usable
  only after the exact model is installed and passes hash and contract
  validation; restore must not download or silently substitute another model.
- Official installed model files and their manifests are persistent app data
  and must survive Android's clear-cache action, but they are excluded from
  manual backups and Android system backup. The bundled catalog and a pinned
  release can recreate official metadata after a clean restore; the large
  weight still requires an explicit download.
- A custom imported model's portable sidecar contract may be backed up as
  metadata keyed by file hash. A content URI, local absolute path, download
  progress record, or temporary import file must not be backed up. Restoring a
  sidecar without its model creates a pending profile, not an installed model.
- All generated source-separation data is disposable cache data and is never
  included in a manual or system backup. This includes partial and completed
  WAV/FLAC output, manifests, window files, hydration PCM, calibration data,
  debug reports, playback traces, cache-entry cleanup metadata, and last-access
  timestamps.
- Per-song blend is deliberately cache-local. Its authoritative value remains
  in the model-aware cache entry's `playback-settings.json`; the temporary
  `SharedPreferences` pending key used while a cache file is unavailable is
  transient as well. Neither the cache file nor that pending key may be
  serialized into a backup. Clearing the cache or restoring a backup may lose
  per-song blend values, while the global blend setting remains restorable.
- Separation timing averages, sample counts, backend timing, worker progress,
  download progress, temporary files, and current playback state are runtime
  state. They are not backup settings. Equalizer DataStore state remains
  governed by the upstream backup policy unless it is explicitly added to a
  future common-settings schema.

### Active-model restore behavior

The backup keeps the requested active model as a stable ID, artifact SHA-256,
contract version, and profile reference. Restore applies it according to the
destination state:

- if no other valid active model is in use and the exact weight and contract
  are already installed, restore that active selection;
- if the exact model is absent, retain the reference as a visible pending
  target and show "model not installed" without downloading or substituting;
- if another valid active model is already in use, keep it active and retain
  the restored reference as a pending target so restore does not interrupt
  playback; and
- after a missing model is downloaded or imported, require the user to press
  `Use`; installation alone never completes the pending selection.

A pending reference is portable preference state, not an installed-model
record. It must not create a model directory, cache entry, or worker.

The clean-install boundary in this roadmap still applies to implementation and
upgrade testing: old model and cache files are not migrated. Deliberate backup
restore remains a supported import operation, but it restores only the
portable user settings and ordinary Booming Music data defined by the backup
schema. It is not an old ONNX/model/cache migration path.

### Versioned backup format

Keep the `.bmgbak` ZIP extension, playlists, lyrics, and artist-image payloads,
but add a package-independent manifest and explicit settings payloads. The
canonical entries should be shaped like:

```text
backup-manifest.json
settings/common.json
source_separation/settings.json
```

The first manifest fixes these independent schema numbers:

```json
{
  "formatVersion": 1,
  "commonSettingsSchema": 1,
  "sourceSeparationSettingsSchema": 1
}
```

The manifest must contain a format version, producer package and flavor,
application version, generation time, settings schema versions, and the list
of included payloads. Format and settings schema versions never derive from
the application version. The common snapshot contains only a maintained
allowlist of stable Booming Music settings. The source-separation snapshot
contains only the fork allowlist below. Neither file is a dump of the default
preferences file.

An unknown optional fork payload or source-separation schema must be reported
and skipped without preventing restoration of recognized common settings,
playlists, lyrics, or artist images. An unsupported top-level `formatVersion`,
an invalid canonical payload declared by the manifest, or an unsafe archive
path still fails the staged restore before any state is applied.

### Backup allowlist

The source-separation settings allowlist includes only:

- panel-entry and quick-control visibility;
- separated-playback enabled state and global blend;
- remember-per-song preference, but not any recorded per-song value;
- automatic separation and automatic FLAC options;
- progress and playback Snackbar/message settings;
- mixed-output preroll values and ready-window count;
- automatic cache-cleanup policy and partial/completed entry limits;
- active-model ID, artifact SHA-256, contract version, and profile reference;
  and
- portable custom-profile contract metadata keyed by model hash.

It explicitly excludes:

- per-song blend, `playback-settings.json`, and temporary per-song preference
  keys;
- model weights, installed-model inventory, local paths, and content URIs;
- cache manifests, window files, WAV/FLAC stems, and hydration PCM;
- downloads, worker progress, current playback state, and pending temporary
  files;
- timing samples, backend statistics, and other performance measurements; and
- debug window-decode settings, reports, and playback traces.

During the Beta format period, every new backup must also emit filtered legacy
preference projections under both known package names:

```text
prefs/com.mardous.booming_preferences.xml
prefs/com.wluhwluh.booming.sourcesep_preferences.xml
```

These projections are compatibility views, not the source of truth. They must
contain only schema-approved values, must exclude per-song blend and runtime
keys, and must never contain model weights or cache paths. Including the
upstream projection lets an unmodified Booming Music build recover shared
settings from a Booming SS backup; unknown Booming SS keys are ignored by the
upstream app. The fork can likewise import the upstream projection and apply
shared keys while leaving fork-only settings at their defaults.

A new Booming SS restore reads the versioned JSON whenever the manifest and
canonical payload are present. It reads the legacy XML only when the new
format is absent; it must never apply both representations or let ZIP entry
order decide which value wins. After the format has remained stable through
Beta testing, a later roadmap may move compatibility projections behind an
explicit compatibility-export action.

The restore path must validate the manifest and payload names, stage and parse
settings before applying them, and apply values through the current
`SharedPreferences.Editor` and other supported settings APIs. It must not
overwrite the running preferences XML directly. After a successful settings
restore, the app must refresh its in-memory settings or request a controlled
process restart before claiming that the restored values are active.

Legacy `.bmgbak` files containing only
`prefs/<package>_preferences.xml` remain importable through a compatibility
parser. The parser maps only known common and Booming SS keys, ignores unknown
keys, and never treats legacy model, cache, or per-song data as restorable.
Path traversal, malformed XML/JSON, wrong value types, unsupported top-level
format, and unsupported common-settings schema must fail without partially
applying settings. An unsupported optional fork schema follows the skip-and-
report rule above and applies none of that fork payload.

### Cross-application behavior

The package names are intentionally different:

```text
Booming Music: com.mardous.booming
Booming SS:    com.wluhwluh.booming.sourcesep
```

With the versioned format and filtered projections, the supported direction is
asymmetric in content but symmetric in the common settings that can be
understood:

- Booming Music -> Booming SS imports playlists, lyrics, artist images, and
  shared settings. Booming SS-specific settings, model references, models,
  caches, and per-song blend values are absent or left at defaults.
- Booming SS -> Booming Music imports the same shared data and common settings.
  Source-separation settings and model references are ignored because the
  upstream app does not define them.
- A Booming SS backup restored back into Booming SS can restore its stable
  global source-separation settings and active-model reference, but it still
  cannot restore a model file, generated cache, or per-song blend.
- Playlist paths, lyric song IDs, and artist-image identities remain dependent
  on the destination device's media database. Cross-device restoration can
  therefore import the records without guaranteeing that every item resolves
  to a local song.

The compatibility projections are for import interoperability only. They do
not promise that an old binary understands the new manifest or fork-specific
JSON. The new fork must keep its native format as the authoritative path and
test both package directions explicitly.

## Cache and Storage Policy

The existing cache scheduler, ready-window playback gate, FLAC promotion, and
blend settings remain the behavioral baseline. The cache identity must be
made model-aware before multiple presets are enabled.

### Storage classification

Model weights and the metadata required to identify an installed model are
persistent application data. They must remain under the app-private model
directory in `filesDir` and must not be removed by Android's "clear cache"
action. The active model, installed-model records, and other user-level model
preferences must remain persistent for the same reason.

All generated source-separation data is disposable cache data. The canonical
root must be:

```text
externalCacheDir/source-separation/
```

with `cacheDir/source-separation/` as the fallback when external cache storage
is unavailable. This root includes:

- model-aware `entries/<entry>/work`, `segments`, and `completed` data;
- rendered WAV/FLAC stems and the manifest, playback settings, and timing
  files needed to interpret or resume a cache entry;
- playback hydration PCM files and their identity/ready markers;
- MP3 no-gapless calibration results; and
- source-separation debug reports and playback traces when diagnostics are
  enabled.

The implementation must treat this entire root as losable at any time. It
must recreate missing directories, discard incomplete temporary files safely,
and start a new separation rather than leave playback or a worker waiting for
cache data that was removed by the system. Sharing a cache-based diagnostic
file must also use a matching `external-cache-path` in the FileProvider
configuration.

The new implementation must use the canonical cache root from its first write.
It must not scan or migrate the old
`externalFilesDir(Environment.DIRECTORY_MUSIC)/source-separation/` location,
old model directories, or any other pre-reset application data. Compatibility
with those files is outside this roadmap; the required clean app-data
precondition removes them from the supported starting state. Existing
`cacheDir/source-separation` subtrees from an earlier version are likewise not
part of the supported input.

The cache identity must include the complete model contract rather than only
an enum name:

```text
audio fingerprint
+ model ID
+ TFLite artifact SHA-256
+ contract schema/version
+ DSP profile
+ separation pipeline version
```

The implementation should derive a canonical `cacheKey` from these fields and
use it for the entry directory and manifest lookup. The manifest must retain
the human-readable model name, artifact hash, contract snapshot, and stem
mapping so a cache remains understandable after the active model changes.

Every new manifest should include:

- model ID;
- actual model SHA-256;
- the complete contract snapshot, including contract schema and pipeline
  version;
- DSP parameters used for the song;
- audio fingerprint and decoder-relevant source metadata;
- output stem mapping;
- rendered stem filenames, format, channel count, sample rate, duration, and
  coverage needed for read-only playback; and
- backend used for each timing record where available.

Rules for switching models:

- selecting model B must never read or append windows to a cache generated by
  model A;
- switching the active model does not delete or automatically clear any other
  model cache;
- switching back to the exact same model identity may resume that model's own
  partial cache, but this is not cross-model cache reuse;
- new windows must never be appended to a cache generated with a different
  model hash or incompatible contract;
- partial caches that cannot be resumed with the active contract are retained
  as stale entries until the user deletes them or the matching model is
  installed again;
- a partial custom-model cache whose profile was deleted remains stale and can
  resume only after the exact model hash and matching profile are installed
  again;
- completed caches from known or custom contracts remain read-only playable
  after their model or profile is deleted when the contract snapshot, output
  mapping, playback metadata, and rendered files are complete and valid;
- a completed custom cache without its original profile displays "model not
  installed" or "contract unverified" rather than blocking playback or
  pretending to be an official preset;
- cache-management UI shows the model ID and contract version for every entry;
- per-song blend settings are keyed by the same audio-plus-model identity,
  written only to that entry's `playback-settings.json`, and must never be
  copied between models or included in a backup;
- any temporary per-song blend preference key is recovery state only and must
  be excluded from the backup allowlist;
- a worker captures its model identity at start and must stop at a safe window
  boundary before another model can start writing.

Each `(song, model identity)` pair is one independent cache entry. The same
song may therefore have separate partial or completed entries for 9662, KARA,
HQ4, and other candidates. Automatic cleanup counts and ranks those entries
independently by state and `lastAccessedAtEpochMs`. The current protection
concept must evolve from protected song IDs to protected entry keys; otherwise
one active song would unintentionally protect every model cache for that song.
Deleting an installed model is also independent from cache deletion. Completed
audio remains available as a cache entry with a "model not installed" state,
while a matching model installation can make a retained partial entry
resumable again.

Only caches created by the current LiteRT implementation are in scope. A
completed cache from the current app version may be played without creating a
new inference session, while incompatible or incomplete current-version data
must be rebuilt safely rather than resumed by guesswork.

## UI Plan

The current settings sheet and cache-management screens should evolve rather
than be replaced wholesale.

### Model management

- Add an installed preset list with role, size, hash state, and active marker.
- Separate the `Download`, `Use`, and `Delete` actions in both state and UI.
- Completing a download must leave the current active model unchanged.
- Show separate download progress for each model.
- Allow activating an installed model without deleting or clearing other
  models or their caches.
- Require an explicit user action to delete an inactive installed model.
- Protect the active model from deletion until another model is selected or
  source separation is disabled.
- Show recommended presets above experimental and download-only candidates.
- Show model size, workload class, contract state, and whether the entry is
  selectable before downloading it.
- Keep preset and custom import actions visually distinct.
- Keep the existing hash comparison style: informative, not an artificial
  license or compatibility gate.

### Cache management

- Show the same song's caches as separate model-specific entries, or group
  them under the song while keeping each model entry independently selectable
  and deletable.
- Display the model ID, artifact hash or shortened identity, contract version,
  state, format, size, and last access time for every entry.
- Describe partial and completed cleanup limits as cache-entry limits, not song
  limits; every model cache for one song counts separately.
- Do not run model deletion as a side effect of changing the active model.
- Keep a completed entry visible with a "model not installed" state after its
  model file is manually deleted.

### Current-song and advanced sections

- Show which model and backend generated the current cache.
- Show a clear unsupported-ABI/runtime state instead of a generic model error.
- Keep normal users focused on playback, blend, readiness, and cache actions.
- Keep detailed tensor/profile fields behind an advanced or diagnostic view.
- Remove ONNX terminology from user-facing strings after the LiteRT transition is
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

### Backup and restore

- Show the backup scope before creation: common settings and selected fork
  settings can be included, while models, generated separation cache, and
  per-song blend are explicitly excluded.
- Show a restore summary after validation, including a missing active model or
  pending custom profile that requires a separate download or import.
- Never trigger a model download, cache rebuild, cache deletion, or blend
  restoration as a side effect of importing settings.
- Require a settings refresh or controlled restart after applying restored
  preferences so the player and source-separation UI cannot continue using
  stale in-memory values.

## Implementation Phases

### Phase 0: Rebased baseline

- [x] Rebase the seven-commit Booming SS stack onto current upstream.
- [x] Preserve the no-Play-Store release policy.
- [x] Verify GitHub debug build, lint, and current source-separation code.
- [x] Push `rebuild/source-separation-structured` to the fork remote.
- [x] Create `feature/litert-multi-model-presets` from the verified tip.
- [x] Create the independent `bss-litert-android` producer with pinned source,
  toolchain, patch, license collection, ELF/JNI checks, and API 26 x86 smoke.
- [x] Publish and independently verify supplemental runtime Release
  `v2.1.5-bss.1`, its checksums, native-only convenience AAR, and GitHub
  provenance.

### Phase 1: Freeze the model contract

- [x] Define contract schema v1 and Kotlin serialization types with an explicit
  `contractSchemaVersion` independent from app and pipeline versions.
- [x] Import the full candidate inventory into the catalog: 48 source records,
  18 duplicate aliases, and 30 distinct artifact records.
- [x] Convert the three recommended `bss-tflite` manifests into catalog
  entries, then add experimental and download-only candidates.
- [x] Add contract validation tests for 9662, KARA, and HQ4.
- [x] Define explicit output stem semantics and support levels; keep generic
  target-stem candidates download-only until the neutral-label UI is complete.
- [x] Keep source-declared DSP metadata separate from the validated Booming SS
  DSP contract.
- [x] Implement `<model file name>.json` sidecars and require their embedded
  model SHA-256 to match before pairing.
- [x] Record canonical artifacts and `aliasOf` source records by normalized
  graph/initializer and numerical equivalence rather than requiring identical
  converted FlatBuffer bytes.
- [x] Define backup format v1, both settings schema v1 payloads, and their
  explicit allowlists before adding new persistent model state.
- [x] Mark model files, generated cache files, per-song blend files, and
  runtime statistics as non-backup data in the design and backup tests.

Frozen Phase 1 outputs:

- `bss-tflite` inventory v1 contains 48 source records from the two pinned
  distributions and 30 canonical graph records. All 18 k2-fsa aliases have an
  identical ONNX `GraphProto`, initializer fingerprint, and deterministic ORT
  probe output with maximum absolute error `0.0` against the canonical TRvlvr
  source.
- Catalog v1 classifies three recommended, 19 experimental, and eight generic
  target-stem download-only entries. Experimental entries remain blocked until
  conversion produces a pinned artifact and a reviewed complete contract.
- The three complete sidecars pin the existing converted TFLite sizes and
  SHA-256 values. They also freeze output compensation at `1.035` for 9662 and
  KARA and `1.019` for HQ4 before mixture-minus-output reconstruction.
- The bundled Android catalog is copied from `bss-tflite` revision
  `a5ef10e96f8082ad2cb884a9d5b47a7eb28f6fe3` and pinned by catalog SHA-256
  `a246f08675534e2b49044b25d85bed4a0179196c08646a2ab3fcf511834d735b`.
  JSON line endings are fixed to LF so the byte identity survives Windows
  checkouts.
- Source-declared ONNX metadata remains provenance only. For example, the
  k2-fsa 9662 file declares `n_fft=4096`, while its separately reviewed UVR and
  Booming SS contract uses `nFft=6144`; validators never promote the embedded
  value by filename or source priority.
- Backup format v1 now has strict serializable manifest and payload types, a
  117-key common-settings table, a 15-key source-separation table, portable
  active-model/custom-profile metadata, and explicit non-backup rules. The
  existing archive writer and restore path are intentionally unchanged until
  the later persistence implementation phase.
- Phase 1 tests cover strict parsing, relational catalog validation, exact-name
  sidecar binding, mismatched hashes, blocked unknown activation, generic-stem
  policy, custom-profile requirements, backup value types, optional unknown
  fork payloads, unsafe archive paths, and representative excluded data.

The catalog and sidecars are release candidates, not mutable download URLs.
Publishing the converted assets under an immutable `bss-tflite` Release and
adding those pinned URLs to a later bundled catalog revision remains separate
from this contract freeze.

Acceptance criteria:

- No DSP parameter is inferred from a model file name alone.
- A known preset resolves to one complete contract and one pinned hash.
- A sidecar with a mismatched embedded hash cannot bind by filename alone.
- Equivalent source aliases resolve to one reviewed canonical artifact even
  when non-semantic FlatBuffer metadata changes its bytes.
- Downloading an entry never changes the active model.
- An unknown file cannot become active without explicit profile metadata.

### Phase 2: Implement LiteRT CPU inference

Phase 2 deliberately keeps ORT as the production default while LiteRT is
validated through debug and test entry points. Builds temporarily contain both
runtimes; final installed-size targets apply after ORT removal in Phase 6, not
to this dual-runtime development interval. No user-visible backend setting or
backup field is added in this phase.

The three real UVR artifacts, pinned NCHW inputs, and generated ORT reference
tensors are staged by validation tooling from the exact `bss-tflite` source
and artifact hashes for connected-device testing. They must not be committed
to the app repository, bundled in an APK, or fetched by an unfinished
production downloader. CI uses a small, reproducibly generated Apache-2.0
TFLite smoke model and keeps its generator/source beside the fixture for
JNI/API coverage; it does not use UVR weights.

#### Phase 2A: Runtime-neutral boundary with an ORT baseline

- [ ] Introduce runtime-neutral factory, session, lease, execution-profile,
  backend-diagnostics, and runtime-settings types. The execution profile binds
  verified model identity, tensor contract, DSP profile, output scale, and
  stem semantics without implementing installed-model selection yet.
- [ ] Keep flat NCHW tensors as the DSP-facing input/output contract and move
  every direct `OrtSession`, `OnnxTensor`, input-name, output-name, and result
  access out of `MdxRangeSeparator` into an ORT adapter.
- [ ] Run the current 9482 production path through the new interface with a
  behavior-preserving legacy profile before adding LiteRT. Do not change
  scheduler priority, cache paths, output naming, or playback gating.
- [ ] Construct `MdxDspConfig` and all derived window dimensions from an
  execution profile, while fixing the legacy profile to its current values.
  Add a contract-backed HQ4 configuration test before attempting inference.
- [ ] Generalize the reusable provider so its cache key includes artifact
  SHA-256, contract/pipeline identity, backend, and runtime settings, and so a
  lease never exposes an engine-specific session type.
- [ ] Add fake-session unit tests for acquire/reuse/replacement/close order,
  tensor element counts, backend diagnostics, pause, cancellation, and a
  failed invocation that must not mark a window ready.
- [ ] Run host unit tests in ordinary CI in addition to lint and assembly.

#### Phase 2B: LiteRT packaging and native supply chain

- [ ] Pin `com.google.ai.edge.litert:litert:2.1.5` in the version catalog and
  keep all LiteRT API use inside the runtime adapter package.
- [ ] Vendor the canonical `v2.1.5-bss.1` x86 binary as
  `app/src/main/jniLibs/x86/libLiteRt.so` and record its Release URL, asset
  name, byte size, SHA-256, source commit/toolchain manifest, LiteRT license,
  and third-party notices in the repository.
- [ ] Add CI checks for the vendored x86 file's hash, ELF32/i386 machine,
  expected LiteRT JNI and C API exports, and dynamic dependency allowlist.
- [ ] Keep the official LiteRT libraries for `armeabi-v7a`, `arm64-v8a`, and
  `x86_64`; package exactly one `libLiteRt.so` per ABI without `pickFirst`.
  Inspect every ABI split and the universal APK rather than only Gradle's
  merged-native-libs directory.
- [ ] Add an API 26 pure-x86 instrumentation smoke test with the small
  Apache-2.0 model so the exact app APK proves that `Environment`,
  `CompiledModel`, `TensorBuffer`, JNI loading, invocation, and close all work.

#### Phase 2C: LiteRT CPU session

- [ ] Implement a CPU session with LiteRT `Environment` and `CompiledModel`.
  Create input/output `TensorBuffer` objects once per session and reuse them
  together with NCHW/NHWC conversion scratch buffers for every window.
- [ ] Resolve minimum API, ABI, backend, and contract compatibility before
  source decoding, cache-run creation, output-file creation, tensor allocation,
  or `CompiledModel.create`. Treat `unsupported` as a hard preflight result;
  permit an `untested` status only in the internal validation path until it is
  promoted by device evidence. A missing ABI/backend status is unsupported,
  not an invitation to guess.
- [ ] After model creation, validate one named float32 input and output against
  the exact contract names, static NHWC shapes, element counts, and layouts
  before the first invocation. Reject non-finite output before ISTFT.
- [ ] Return raw output in canonical NCHW order. Apply
  `modelOutputScale` once after ISTFT, construct the residual from the scaled
  waveform, and map both outputs using the contract's stem semantics.
- [ ] Use `max(2, min(4, availableProcessors - 1))` as the initial LiteRT CPU
  thread policy. Record the resolved count in diagnostics but do not expose it
  as a user setting or backup value.
- [ ] Check cancellation before and after the non-interruptible invocation,
  discard an output canceled in flight, and prohibit concurrent session close.
- [ ] Add unit tests for NCHW/NHWC round trips with non-symmetric dimensions,
  exact output compensation, residual reconstruction, tensor mismatch,
  non-finite output, compatibility decisions, and session replacement.
- [ ] Add a factory-spy test proving HQ4/x86 is rejected before
  `CompiledModel.create` or any large tensor allocation.

#### Phase 2D: Internal integration and parity validation

- [ ] Add a debug/internal runner that accepts a locally staged TFLite file
  only after its file identity and complete bundled contract match. Keep it
  out of release UI, normal model acquisition, production defaults, and
  settings persistence. Write only to an isolated validation directory under
  the cache root; do not use the production `SourceSeparationCache`, foreground
  worker, playback gate, or existing 9482 cache identity.
- [ ] Compare raw NCHW LiteRT output with the frozen ORT tensor reference for
  the same checked input, then independently validate compensation, stem
  mapping, and residual reconstruction.
- [ ] Record the actual process architecture (`SUPPORTED_ABIS`, `os.arch`,
  `Process.is64Bit()`), installed APK/split identity, and loaded runtime
  inventory in every device report. An ABI list alone is not sufficient
  evidence that a particular native library executed.
- [ ] Exercise 9662, KARA, and HQ4 in arm64 processes on S10 and S25 CPU. On
  S10, separately install the `armeabi-v7a` split and validate at least 9662
  and KARA; test HQ4 only after the 32-bit compatibility preflight accepts its
  memory budget, otherwise record it as unsupported.
- [ ] Exercise all three models with the official x86_64 runtime and exercise
  9662 and KARA with the supplemental API 26 pure-x86 CPU runtime. Route pure
  x86 directly to CPU without attempting GPU setup.
- [ ] Reconcile `runtimeCompatibility` only from these app-packaged reports:
  update the authoritative contracts in `bss-tflite`, regenerate the bundled
  catalog snapshot, and keep any combination without sufficient evidence
  `untested` or `unsupported`. Do not patch only the app's copied JSON.
- [ ] Run cancellation before invocation and during a blocking invocation,
  session reuse, session replacement, and process restart tests. Confirm the
  unchanged production ORT path still follows existing scheduler and playback
  behavior.
- [ ] Store the resulting parity, timing, memory, and packaging report with
  the app commit, catalog revision, contract IDs, runtime version, ABI, device,
  Android version, and fixture hashes.

Acceptance criteria:

- The ORT adapter is behaviorally equivalent to the pre-refactor production
  path before LiteRT is selected by any internal test, and production playback
  still has no route that silently selects LiteRT.
- All three recommended models produce correctly shaped, finite CPU output in
  arm64 processes on S10 and S25 and in an x86_64 process; 9662 and KARA also
  pass the `armeabi-v7a` S10 process and pure-x86 CPU path.
- For the frozen synthetic and Coast Town `bss-tflite` parity fixtures, raw
  output meets these machine-checked floors against ORT. These limits apply to
  the named inputs; maximum absolute error is input-amplitude dependent and is
  not a universal quality threshold for arbitrary songs.

  | Model | Minimum SNR | Minimum cosine | Maximum absolute error |
  | --- | ---: | ---: | ---: |
  | 9662 | 94.0 dB | 0.999999999 | 0.00010 |
  | KARA | 109.0 dB | 0.999999999 | 0.00003 |
  | HQ4 | 95.0 dB | 0.999999999 | 0.00060 |

- Unit and connected tests prove that the contract scale is applied exactly
  once and that scaled primary plus residual reconstructs the unclipped input
  window with maximum absolute error at most `0.00001`.
- HQ4 returns an explicit unsupported compatibility result on the 2 GB x86
  baseline, and a factory spy confirms no `CompiledModel`, tensor buffer, or
  large conversion buffer was allocated.
- A cancellation received during invocation waits for the call to return,
  discards its output, does not close the session concurrently, and does not
  mark the segment ready.
- CPU inference and session reuse work on S10, S25, and the API 26 pure-x86
  test environment without changing production playback scheduling.
- Every `known-good` status used by the app is backed by an app commit, exact
  model/runtime hashes, actual process ABI, Android/device identity, and
  numerical report. `untested`, missing, and `unsupported` combinations remain
  unavailable outside internal validation.
- The packaged x86 runtime has the pinned
  `02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af`
  hash; each ABI split contains only its matching runtime inventory, and the
  universal APK contains exactly one `libLiteRt.so` for each of the four ABIs.
- CI runs host unit tests, native supply-chain checks, APK inventory checks,
  and the app-packaged x86 JNI/API smoke test without downloading UVR weights.

### Phase 3: Add GPU execution and fallback

Phase 3 uses the same isolated internal runner as Phase 2. GPU results do not
enter playback or a production cache until the model repository and
model-aware cache work in Phases 4 and 5 is complete.

- [ ] Add the LiteRT GPU backend behind the same adapter.
- [ ] Implement ABI, accelerator-library, operator, delegate, and memory
  eligibility checks plus failure classification.
- [ ] Add a bounded output-validity probe where static eligibility is
  insufficient.
- [ ] Recreate the model on CPU after GPU setup or invocation failure.
- [ ] Record backend, setup time, inference time, and fallback reason.
- [ ] Retain the Phase 2 CPU thread policy for fallback initially; tune it only
  from the recorded S10/S25 full-song matrix rather than changing it while
  introducing GPU behavior.
- [ ] Keep `GPU only` out of the initial UI and settings schema.
- [ ] Verify no failed GPU session leaves the internal runtime job or
  validation state stuck. Repeat the production worker/playback-gate assertion
  after Phase 5 integration.

Acceptance criteria:

- GPU is used where it passes validation.
- CPU fallback completes the same model window after a forced GPU failure.
- A failed GPU attempt never exposes its output as successful or ready in the
  internal runner; the model-aware cache assertion remains a Phase 5 and
  Phase 7 gate.
- Thread-policy or probe changes are supported by full-song performance,
  memory, thermal, cancellation, and readiness measurements on S10 and S25.

### Phase 4: Multi-preset repository

Model management may be developed and tested before production inference is
switched, but it remains behind a development feature gate. Selecting a TFLite
model must not route a normal worker through that model while cache identity is
still song/legacy-variant based. The gate is removed only by the ordered Phase
6 cutover after Phase 5 acceptance.

Before production download integration, `bss-tflite` must publish the canonical
candidate artifacts in an immutable versioned Release. Complete the pinned
conversion, provenance, sidecar/contract review where activation is claimed,
desktop numerical validation, per-asset checksums, and release manifest for all
canonical candidates intended for the first broad testing wave. Entries whose
DSP or stem semantics remain incomplete may still be published as
`download-only`; artifact availability must not upgrade activation support.

- [ ] Replace `MdxModelVariant.MDXNET_9482` as the sole active path with a
  catalog-backed model ID in the new repository and selection state, without
  yet changing the feature-gated production worker.
- [ ] Pin the immutable `bss-tflite` Release tag, asset URLs, byte sizes, and
  hashes in a reviewed catalog revision; never resolve `latest` at runtime.
- [ ] Install every artifact under a separate hash-aware model directory.
- [ ] Track download/import state per model.
- [ ] Add separate download, active-model selection, and manual deletion
  operations.
- [ ] Prevent a completed download from changing the active model.
- [ ] Keep inactive downloaded models until the user explicitly deletes them.
- [ ] Display recommended, experimental, and download-only candidates with
  distinct activation rules.
- [ ] Allow production activation only when the current ABI has a `known-good`
  CPU path for that exact contract. `Auto` may add a known-good GPU path or
  fall back to that CPU path; `untested`, missing, and `unsupported` statuses
  remain downloadable but not usable outside internal validation.
- [ ] Implement the import priority: built-in contract by SHA-256, matching
  sidecar, then advanced profile form with an unverifiable-quality warning.
- [ ] Keep every download or import inactive until the user explicitly chooses
  `Use`.
- [ ] Persist the active model as a stable ID/hash/contract reference rather
  than a model path; keep installed weights and official manifests outside
  manual and system backups.
- [ ] Persist portable custom profile metadata separately from imported model
  files, content URIs, and download state.
- [ ] Align Android full-backup/data-extraction rules with the same policy so
  model weights and source-separation cache cannot enter system backup.

Acceptance criteria:

- Every network-backed catalog entry resolves to one immutable Release asset
  with a matching size and SHA-256, or remains explicitly unavailable rather
  than falling back to a mutable source URL.
- The recommended and experimental artifacts can coexist without overwriting
  files or metadata.
- Downloading a model does not select it, and selecting a model does not delete
  another installed model.
- The active model cannot be deleted accidentally.
- No model can become active on an ABI whose complete CPU compatibility state
  is anything other than `known-good`.
- Switching models affects only new separation work.
- An unknown import can be installed through a valid sidecar or completed
  advanced form without being activated automatically.
- Official hash mismatch remains visible and non-blocking according to the
  existing import policy.

### Phase 5: Model-aware caches and storage

Phase 5 integrates the feature-gated LiteRT path with the new cache identity.
It must pass cache isolation and recovery tests before any normal worker can
honor the selected TFLite model. No transitional implementation may write a
TFLite result under `MdxModelVariant.MDXNET_9482`.

- [ ] Extend cache keys with model hash, contract version, DSP profile, and the
  canonical audio identity; store the complete contract, stem mapping, and
  playback metadata snapshot in each manifest.
- [ ] Prevent incompatible partial-cache continuation and retain a custom
  partial cache as stale until its exact model hash and profile return.
- [ ] Keep separate completed and partial entries for every model used on the
  same song.
- [ ] Keep valid completed stem caches read-only playable after model switches
  or deletion of their model/profile, with an explicit uninstalled or
  unverified status.
- [ ] Scope blend settings by the same model-aware cache identity.
- [ ] Count each model cache as an independent entry during auto cleanup.
- [ ] Replace song-level cleanup protection with exact entry-level protection.
- [ ] Preserve caches when an installed model is manually deleted.
- [ ] Show model identity in cache management.
- [ ] Place all generated separation data directly under
  `externalCacheDir/source-separation`, with a `cacheDir` fallback, from the
  first write of a clean installation.
- [ ] Co-locate hydration, MP3 calibration, and enabled debug artifacts under
  the canonical source-separation cache root.
- [ ] Do not add readers, migration markers, or copy paths for old model and
  cache layouts.
- [ ] Make workers and playback recover cleanly when Android clears or
  partially removes the source-separation cache root.
- [ ] Ensure backup creation never reads cache manifests or
  `playback-settings.json`, and never serializes temporary per-song blend
  preference keys.

Acceptance criteria:

- No cache contains windows from multiple incompatible model contracts.
- A song can retain and display multiple model cache entries simultaneously.
- Switching models does not delete caches or reuse another model's cache.
- Partial and completed cleanup limits count model-specific entries
  independently.
- Android's clear-cache action removes generated separation data without
  removing installed model weights, active-model state, or model metadata.
- After a cache clear or partial cache loss, the app recreates its cache root
  and can separate the current song again without a permanent waiting state.
- A clean installation uses only the canonical model and cache layouts and
  does not require any previous model, cache, or migration marker.
- Completed LiteRT FLAC playback does not require a live inference session.
- A completed custom cache with valid files and a complete contract snapshot
  remains playable after profile deletion without being relabeled as an
  official model.
- A partial custom cache cannot resume until the exact model and profile are
  installed again.
- Deleting a model does not silently delete unrelated completed caches.
- Restoring settings does not recreate a cache entry or a per-song blend value.
- Cancellation, runtime failure, and GPU-to-CPU session recreation cannot mark
  a model-aware window ready until the CPU result has completed and been
  written successfully.

### Phase 6: Remove ONNX Runtime

- [ ] In a build that still contains both runtimes, switch all production
  engine construction to the selected contract-backed LiteRT `Auto` path and
  verify that no error silently falls back to ORT.
- [ ] Run the production worker, playback, model-switch, model-aware-cache, and
  process-restart suite with ORT still available only as an unreachable
  regression oracle.
- [ ] Remove ONNX model URLs, import validation, and user-facing ONNX text.
- [ ] Remove `onnxruntime.android` and all ONNX native libraries from release
  artifacts.
- [ ] Remove obsolete ONNX-only tests and diagnostics after equivalent LiteRT
  coverage exists.
- [ ] Remove legacy ONNX model, manifest, and cache discovery paths instead of
  retaining compatibility readers.
- [ ] Remove the temporary legacy 9482 execution profile, old model repository,
  and `MdxModelVariant` routing after all production references are gone.

Acceptance criteria:

- `rg` finds no production ONNX Runtime dependency or model-loading path.
- Release APKs contain no `libonnxruntime*.so`.
- Every production-selectable model/backend/ABI combination is `known-good`;
  an untested combination cannot become active merely because its native
  library is present.
- Every ABI APK contains the expected LiteRT inventory, and the x86 APK
  contains exactly the pinned supplemental `libLiteRt.so`.
- A clean install can download and use a TFLite preset without any ONNX file.

### Phase 7: Full-device validation

- [ ] Test 9662, KARA, and HQ4 on S10 with CPU and eligible GPU paths.
- [ ] Test all three on S25 with CPU and eligible GPU paths.
- [ ] Install the `armeabi-v7a` split on S10 and run full worker/playback tests
  for every model marked known-good there; confirm any 32-bit HQ4 rejection
  occurs during compatibility preflight.
- [ ] Run full worker/playback tests for every model marked known-good on an
  x86_64 emulator using the official LiteRT runtime.
- [ ] Run conversion and LiteRT smoke validation for every published candidate
  artifact, recording unsupported or download-only states explicitly.
- [ ] Test 9662 and KARA with the supplemental CPU runtime on an API 26 pure
  x86 emulator, including model load, one-window parity, cancellation, and
  session recreation.
- [ ] Confirm HQ4 is reported as unsupported on the 2 GB x86 baseline without
  attempting an allocation known to fail.
- [ ] Test a missing, altered, or wrong-architecture supplemental runtime and
  confirm that build verification or the localized runtime error fails
  clearly rather than loading another inference engine.
- [ ] Start every installation and device-validation run after clearing all
  application data.
- [ ] Test model switching during paused and active playback.
- [ ] Test download without activation, manual deletion, and model reinstall
  without cache loss.
- [ ] Compare the initial CPU thread formula with neighboring thread counts on
  S10 and S25 using full-song time, peak PSS, thermal behavior, cancellation,
  and playback readiness before changing the default.
- [ ] Validate the GPU operator/memory eligibility rules and bounded probe on
  both devices; record false-positive and false-negative delegate decisions.
- [ ] Test Android clear-cache behavior with installed models, partial entries,
  completed entries, hydration PCM, and debug artifacts present.
- [ ] Test backup format v1 and both settings schema v1 payloads with the
  restored active model installed, missing, and different from a currently
  valid active model.
- [ ] Confirm a missing restored model becomes a pending reference, a current
  valid model is not interrupted, and later installation still requires
  explicit `Use`.
- [ ] Confirm an unknown optional fork payload is skipped without blocking
  recognized common settings, playlists, lyrics, or artist images.
- [ ] Test Booming Music -> Booming SS and Booming SS -> Booming Music imports
  for shared settings, playlists, lyrics, and artist images, with fork-only
  settings ignored by the upstream app.
- [ ] Test a restored backup with cache files and per-song blend data present
  in the source device; neither the cache nor the blend may appear in the
  destination.
- [ ] Test legacy package-named `.bmgbak` imports and verify malformed or
  unsupported payloads do not partially overwrite running settings.
- [ ] Verify every Beta backup contains both filtered compatibility XML files,
  canonical JSON wins when present, and one setting is never applied twice by
  ZIP entry order.
- [ ] Delete an unknown custom profile with both partial and completed caches:
  completed output remains read-only playable, while partial output stays
  stale until the exact model and profile return.
- [ ] Verify a clean first launch writes only the new model and cache layouts
  and never requires a legacy path.
- [ ] Test seeking, ready-window gating, blend changes, FLAC promotion, and
  background continuation.
- [ ] Inspect all four ABI APKs plus the universal APK for native library
  duplication, x86 hash agreement, and absence of ONNX Runtime.
- [ ] Measure package size, install size, peak PSS, thermal behavior, and
  full-song wall time for every preset/backend.
- [ ] Evaluate HQ4 against the 64 MiB model, 256/384 MiB peak-PSS, and S10
  lower-device gates; evaluate each ABI runtime against the 10/16 MiB
  target/hard limits and record any model or device-class downgrade.

Acceptance criteria:

- No regression in continuous separated playback, seeking, or blend changes.
- GPU failure and process/lifecycle recreation recover without a permanent
  loading state.
- Performance and memory reports are stored with the model/catalog revision.
- Any change to the provisional thread, probe, GPU eligibility, or resource
  targets is justified by recorded S10 and S25 results and preserves the hard
  fallback/disable behavior.

### Phase 8: Beta readiness

- [ ] Complete model attribution and conversion guidance in the app and
  `bss-tflite` repository.
- [ ] Add all newly supported upstream language directories to the fork
  localization set, including `bqi` and `ta` introduced by the rebase.
- [ ] Review every LiteRT-specific string for terminology consistency.
- [ ] Build GitHub and F-Droid release variants only.
- [ ] Include the supplemental runtime source link, Release tag, checksum,
  LiteRT license, third-party notices, and provenance verification guidance in
  release documentation.
- [ ] Confirm whether the F-Droid build policy accepts the pinned vendored x86
  binary; if it requires a source build, reproduce the same pinned producer
  inputs in the F-Droid recipe rather than introducing a fallback runtime.
- [ ] Emit canonical backup JSON plus both filtered compatibility XML
  projections in every Beta backup, with tests enforcing canonical priority.
- [ ] Publish the full candidate catalog with all source records, duplicate
  aliases, pinned artifacts, manifests, and reproducible checksums.
- [ ] Keep the first three models at the top of the catalog and label all
  other entries with their actual experimental or download-only state.
- [ ] Publish a beta with pinned catalog assets and reproducible hashes.

## Verification Matrix

Every runtime or model change should run the narrowest applicable checks:

| Area | Required check |
| --- | --- |
| Catalog | Source provenance, normalized duplicate equivalence, aliases, support level, and pinned asset |
| Acquisition | Download, verify, install, activate, manual delete, and reinstall |
| Contract | Schema v1, sidecar/hash pairing, shape, dtype, layout, DSP, stem mapping, and migration rejection |
| Conversion | Desktop LiteRT/TFLite output versus ORT reference |
| Runtime | ORT abstraction baseline, NCHW/NHWC conversion, raw parity, output compensation/residual, CPU thread matrix, GPU eligibility/probe, fallback, close/recreate, and cancellation |
| Playback | Start, pause/resume, seek, song transition, blend update |
| Cache | Multiple models per song, deleted custom profile, partial stale/resume, read-only completed playback, FLAC promotion, delete/cleanup, and system clear-cache recovery |
| Persistence/Backup | Format/schema v1, key allowlists, pending active model, unknown fork payload, canonical/legacy priority, both package directions, and excluded model/cache/per-song data |
| Lifecycle | Activity recreation, process restart, background worker continuation |
| Device | Galaxy S10 arm64 and armeabi-v7a, Galaxy S25 arm64, official x86_64 emulator runtime, API 26 pure x86 for 9662/KARA, actual process-ABI evidence, and explicit HQ4 x86 rejection |
| Resource budgets | Model/runtime install size, peak PSS, graphics/native memory, thermal behavior, and target/hard-limit decisions |
| Native supply chain | Pinned source/toolchain, Release hash, ELF/JNI audit, checksums, notices, and GitHub provenance |
| Packaging | Four ABI splits plus universal APK, one runtime per ABI, native inventory, and APK/install size |
| Localization | Fork string completeness and terminology review |

The most important correctness test is an end-to-end full-song comparison. A
single model-window SNR result proves tensor compatibility, but not that DSP,
window trimming, stem mapping, cache joins, or playback timestamps are
correct.

## Commit and Rebase Strategy

Keep the LiteRT transition commits above the seven stable Booming SS commits.
Prefer small, buildable commits in this order:

1. model contract and preset catalog;
2. runtime-neutral interface and behavior-preserving ORT adapter;
3. LiteRT dependency, pinned x86 runtime, provenance, and packaging checks;
4. LiteRT CPU session, internal integration, and parity tests;
5. LiteRT GPU backend and fallback;
6. multi-preset repository and download UI;
7. model-aware cache identities and cache storage;
8. ONNX removal and packaging cleanup;
9. device validation, documentation, and localization.

Before starting a new LiteRT milestone:

```text
git fetch upstream
git switch rebuild/source-separation-structured
git rebase upstream/master
git switch feature/litert-multi-model-presets
git rebase rebuild/source-separation-structured
```

The fork should avoid merge commits from upstream. Rewritten branches should
be pushed with `--force-with-lease`, while release tags and backup refs remain
immutable. The structured branch is the rebase checkpoint; the LiteRT
development branch is the place for active implementation and may be rewritten
until a beta is cut.

## Open Decisions: Adopted Defaults and Validation Gates

The former open items now have explicit implementation defaults. A validation
gate may tune a provisional threshold or heuristic, but it must not silently
reverse the associated correctness, identity, fallback, or backup rule.

### Contract schema and sidecars

Adopt contract schema v1 with `contractSchemaVersion` independent from the app
version and pipeline version. A sidecar is named `<model file name>.json`, so
`model.tflite` uses `model.tflite.json`. It carries the model SHA-256, tensor
shape, dtype, layout, `dimF`, DSP values, stem semantics, complement rule,
contract schema, and pipeline compatibility version. Pair by embedded SHA-256;
never accept filename adjacency as identity.

Validation must cover sidecar round trips, unknown fields, schema rejection,
shape/contract disagreement, and a correct filename with the wrong model hash.
Changing the schema requires an explicit schema migration; changing the app
version alone does not.

### Generic target-stem models

Keep target-stem-plus-residual models `download-only` until the generic
contract and neutral-label UI are complete. After end-to-end validation they
may become `experimental` using labels such as "target stem" and "remaining
audio". Bass, drums, other, or reverb targets must never pass through a UI that
labels them as vocals/instrumental.

The validation gate is a full-song test of DSP, stem mapping, cache playback,
blend labels, and accessibility/localization for the generic UI. Structural
tensor compatibility alone is insufficient.

### Duplicate source artifacts

Do not require duplicate sources to produce byte-identical TFLite SHA-256
values. Establish equivalence through normalized graph structure, initializer
data, pinned converter version/options, DSP and stem semantics, and numerical
output comparison. Publish one canonical artifact with `aliasOf` provenance
records when those checks agree. Publish separate artifacts only when graph,
weights, semantics, or validated output differ.

Before each catalog release, rerun the equivalence report for every duplicate
group. A hash difference caused only by FlatBuffer metadata is recorded but is
not itself a release blocker.

### Custom model import

Support all three paths in this order: built-in contract by SHA-256,
hash-matching sidecar, then an advanced manual profile form. The form exposes
`dimF`, DSP values, and stem mapping and permanently warns that separation
quality is not verified. Download or import installs a model but never changes
the active model; `Use` is always separate.

Validation must exercise each path, sidecar/hash mismatch, incomplete forms,
structural failure, and import while another model is active.

### Caches after model or profile deletion

Allow a completed cache to remain read-only playable when its model or custom
profile has been deleted, provided its manifest contains the complete contract
snapshot, output mapping, playback metadata, and valid rendered WAV/FLAC
files. Show "model not installed" or "contract unverified" and do not present
the cache as an official preset.

Keep a partial cache stale after profile deletion. It can resume only when the
exact model hash and matching profile are installed again; no reconstructed or
guessed contract may continue inference. Phase 5 and Phase 7 tests must cover
both outcomes.

### GPU eligibility and CPU threads

Adopt `Auto` as the only initial accelerated mode. Eligibility checks ABI,
accelerator availability, delegate creation, model operators, and memory; a
bounded output-validity probe is allowed where static checks are insufficient.
Any setup, probe, or invocation failure closes the GPU session and recreates
the model on CPU. Do not expose `GPU only` initially.

Use this initial CPU thread formula:

```text
max(2, min(4, availableProcessors - 1))
```

The fallback behavior and diagnostic recording are fixed. The exact device
eligibility rules, memory threshold, validity probe, and thread count are
provisional. Tune them only after repeated S10 and S25 full-song comparisons
of wall time, peak memory, thermal behavior, cancellation, and playback
readiness. Performance statistics remain runtime data and are never backed up.

### HQ4 and runtime resource budgets

Use these provisional targets and hard limits:

- HQ4 installed model: 64 MiB target; current artifact about 56.3 MiB.
- ABI-specific LiteRT runtime increase: 10 MiB target, 16 MiB hard limit.
- HQ4 separation peak-PSS increase: 256 MiB target, 384 MiB hard limit.

Record GPU graphics/native allocations separately from Java heap and aggregate
PSS. S10 is the lower-device constraint; S25 success does not waive an S10
failure. Exceeding a hard memory limit keeps the model download-only or
disabled on that device class. Phase 7 may tighten targets, but raising a hard
limit requires an explicit roadmap revision backed by repeated measurements.

### F-Droid x86 packaging

The GitHub build vendors the pinned supplemental x86 `.so`. Before Beta,
confirm whether F-Droid accepts that reviewed binary. If policy requires source
construction, reproduce the exact pinned LiteRT commit, patch, Bazel, NDK,
rules, API level, ELF/JNI checks, and output hash in the F-Droid recipe. Do not
introduce ONNX Runtime or `tensorflow-lite:2.16.1` as a packaging workaround.

This remains an external-policy validation gate, not an inference-design
choice. Failure to satisfy the source policy blocks the F-Droid x86 artifact,
not the verified GitHub x86 build or the other ABI builds.

### Backup format and allowlists

Adopt backup `formatVersion` 1, `commonSettingsSchema` 1, and
`sourceSeparationSettingsSchema` 1. The manifest also records producer package,
flavor, app version, generation time, and payload list. Unknown optional fork
payloads are skipped and reported without blocking recognized common data;
unsupported top-level format or invalid declared canonical payload fails before
application.

The common allowlist is a maintained table of stable, user-facing upstream
settings with key, type, default, and schema introduction. Freeze that table
from the rebased upstream implementation before Phase 1 acceptance; never use
the package preference XML wholesale.

The fork allowlist includes panel/quick-control visibility, separated playback,
global blend, remember-per-song preference, automatic separation and FLAC,
message settings, preroll, ready-window count, cache-cleanup policy/limits,
active-model ID/hash/contract reference, and portable custom profiles. It
excludes per-song blend, model weights and installed inventory, cache data,
downloads/workers, performance statistics, debug window-decode state, and
current playback. Backup tests must assert every included key and representative
excluded keys rather than checking only archive filenames.

### Restored active-model references

If no valid model is active and the exact restored model is installed, restore
the selection. If it is missing, retain a visible pending reference without
download or substitution. If another valid model is already active, keep it in
use and retain the restored reference as a pending target. Installing the
missing model later still requires the user to press `Use`.

Test all three destination states. A pending reference must never create model
files, cache entries, workers, or playback interruption.

### Legacy compatibility projections

During Beta, every new backup emits both filtered projections:

```text
prefs/com.mardous.booming_preferences.xml
prefs/com.wluhwluh.booming.sourcesep_preferences.xml
```

They are compatibility views, not canonical state. A new restore reads the
versioned JSON when present and reads legacy XML only when the new format is
absent; it never applies both or lets ZIP order choose a winner. Test imports in
both package directions and duplicate-key conflicts. After the format remains
stable through Beta, reconsider whether projections should move behind an
explicit compatibility-export action.
