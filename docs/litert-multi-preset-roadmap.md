# LiteRT and Multi-Preset Roadmap

Status: active plan for `feature/litert-multi-model-presets`

Updated: 2026-07-23

Current milestone: the Phase 6 production cutover is implemented and accepted
on the development branch. Phase 7 is the next stage: full-song playback,
resource, listening, and device-tier validation before any model is promoted
or a user release is prepared.

This document is the plan for the next Booming SS development stage. The
older `source-separation-roadmap.md` remains the historical record of the
ONNX-based prototype. This document supersedes its runtime and model-acquisition
plan, but keeps the playback, cache, and UI behavior already implemented by
Booming SS unless a phase below explicitly changes it.

## Direction

Booming SS will migrate from ONNX Runtime to LiteRT and will no longer ship or
load ONNX models. The app will provide a catalog of converted TFLite presets
hosted by the companion `bss-tflite` repository. Its first production release
will have exactly one recommended/default preset: `UVR_MDXNET_3_9662` FP32.
That preset is only a recommended candidate until it passes the Phase 7
full-song, playback, thermal, and resource gates; it must not be called stable
before then.

The remaining candidates do not all belong to one selectable "experimental"
bucket. KARA FP32 is the current candidate for a clearly labelled CPU-only
experimental tier after its own Phase 7 full-song and listening checks. HQ4 is
reviewed but resource-gated and remains download-only. Candidates without a
reviewed contract, a safe stem UI, or sufficient device evidence also remain
download-only. Download-only still permits acquisition and catalog inspection;
it never permits normal in-app activation.

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
- completed Phase 3 tip: `9c574447`
- completed Phase 4 tip: `b302e66e`
- accepted Phase 5 storage/cache tip: `7a26f2b1`
- accepted Phase 6 production-cutover tip: `bfd50b8a`
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

Phase 3 narrows the initial product candidates. Only 9662 FP32 has a GPU
profile eligible for Phase 7 promotion. KARA FP32 keeps its CPU evidence but
its GPU profile misses the raw-output gate. HQ4 has a reviewed contract but
exceeds the present resource gate and has no eligible CPU fallback. FP16
profiles are rejected for numerical parity and are not official presets.

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
- pipeline compatibility version.

The contract must be stored separately from the weight file. Official catalog
entries supply it authoritatively. The conversion scripts in `bss-tflite`
must emit the same contract format so users can prepare compatible custom
models without reverse engineering the app.

The first contract schema is versioned independently from both the application
version and the separation pipeline version. An app update does not imply a
contract migration, and a pipeline change must not silently reinterpret a
contract whose `contractSchemaVersion` is still accepted.

Phase 1 contract schema v1 also embedded `minimumAndroidApi` and mutable
ABI/backend status records. Phase 3 showed that this is the wrong ownership:
runtime qualification changes with LiteRT, ABI, execution profile, precision,
device evidence, and resource policy, while the model's tensor/DSP/stem
contract does not. Before any model Release or production activation, Phase 4
must introduce contract schema v2 without runtime evidence, regenerate the
reviewed contracts/sidecars, and move runtime requirements and evidence into
the versioned catalog qualification records. Schema v1 remains an immutable
Phase 1 history artifact and is not an accepted official-release contract.
There is no installed-data migration requirement under the clean-install
compatibility boundary.

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

The Phase 1 catalog provisionally placed the first three converted artifacts in
its recommended section. Phase 3 device evidence supersedes that provisional
classification. Before Phase 4 integrates downloads, the catalog must be
reclassified as follows:

| ID | Product tier | TFLite size | `dimF` | `nFft` | Activation plan |
| --- | --- | ---: | ---: | ---: | --- |
| `uvr_mdxnet_3_9662` | Sole recommended/default candidate | 29,700,464 bytes | 2048 | 6144 | FP32 CPU plus eligible FP32 GPU candidate; production activation after Phase 7 |
| `uvr_mdxnet_kara` | Reviewed experimental candidate | 29,700,460 bytes | 2048 | 6144 | CPU-only and explicit user choice; activation after full-song and listening validation |
| `uvr_mdxnet_inst_hq_4` | Reviewed download-only candidate | 59,057,268 bytes | 2560 | 5120 | Resource-gated; no normal `Use` action for the current artifact/profile |

All three use 44.1 kHz audio, hop length 1024, `dimTPower=8`, an actual model
time dimension of 256, and static batch-1 float32 tensors. The exact hashes and
validation reports live in `bss-tflite`; the app catalog must pin a release tag
and asset hash rather than follow a mutable branch or `latest` asset.
The FP16 experiments were runtime execution profiles over these FP32 artifacts,
not separate model presets. Their failed numerical results must not appear as a
download, backend choice, or supported optimization.

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
availability does not imply activation support. Contract review, product tier,
activation policy, validation maturity, and runtime-profile evidence are
independent catalog facts:

- `recommended`: the default intended for ordinary users. The first production
  catalog has only 9662 FP32 in this tier, and it is not stable or selectable in
  a release until all Phase 7 promotion gates pass.
- `experimental`: a reviewed specialist or test model. It becomes selectable
  only with an explicit warning, correct stem UI, desktop parity, full-song and
  listening validation, and a `known-good` CPU profile for the current ABI. A
  GPU profile is optional and cannot borrow CPU or another profile's evidence.
- `download-only`: published for inspection, external testing, or future work,
  but never selectable in normal UI. This includes incomplete contracts and
  stem semantics as well as reviewed models such as HQ4 that fail a resource or
  fallback gate.

A reviewed contract must survive demotion from `recommended` to `experimental`
or `download-only`; contract presence is not a reward for catalog prominence.
Likewise, assigning `experimental` does not itself grant a `Use` action. A
separate activation policy records whether the entry is blocked pending review,
blocked by resources, or selectable with an experimental warning. Runtime
evidence is keyed by LiteRT version, ABI, backend, and execution-profile ID so
that 9662 `gpu-auto-fp32-v1`, rejected FP16 profiles, KARA's rejected GPU
profiles, and a future retest cannot overwrite one another.

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
the legacy ONNX model's NCHW layout, but release contract schema v2 remains
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

The initial product policy remains `Auto`, but Phase 3 implements it only in
the isolated internal runner. LiteRT 2.1.5 exposes accelerator discovery and
GPU-only `CompiledModel` creation, but no supported API that reports delegated
operator coverage. The policy must therefore avoid pretending that operator
support can be decided from a static query:

1. For an Auto attempt, preflight the process ABI, packaged accelerator
   library, exact model/backend compatibility record, known-good CPU fallback,
   and a conservative memory floor before allocating a GPU resource. An
   `untested` GPU record, or a GPU probe without CPU fallback, is allowed only
   in an explicitly labeled internal validation path.
2. Create an `Environment`, require `Accelerator.GPU` in
   `getAvailableAccelerators()`, and compile the model with an explicit,
   versioned GPU option profile. Successful GPU-only compilation and invocation
   establish operator compatibility for that artifact and runtime profile.
3. Validate tensor metadata and run a bounded deterministic output probe when
   required by the eligibility policy. Do not keep a CPU model alive beside the
   GPU model merely to perform the probe.
4. Classify eligibility skips, setup failures, probe failures, invocation
   failures, output-read/validation failures, cancellation, memory exhaustion,
   and cleanup failures separately.
5. After a recoverable GPU failure, discard all GPU output, close the complete
   GPU session, create the same contract on its known-good CPU path, and run
   the same input window once. Latch that controller to CPU for the remainder
   of the session; never cycle from GPU to CPU and back to GPU.
6. Treat cancellation as cancellation rather than backend failure. If GPU
   cleanup cannot be confirmed, or memory exhaustion makes another large
   allocation unsafe, end the attempt with a terminal diagnostic instead of
   creating a concurrent CPU session.
7. Record the requested policy, GPU option profile, backend that produced the
   accepted output, setup/probe/inference timings, and typed fallback or
   terminal reason. A failed GPU result is never reported as successful.

CPU must remain a first-class path, not a test-only fallback. GPU behavior is
device-dependent, so a failed accelerator must never leave playback waiting
indefinitely or corrupt a partial cache. The first implementation must not
offer a `GPU only` mode. A model without a known-good CPU path, including HQ4
under the current resource gate, may be exercised on GPU internally but cannot
be made production-selectable through `Auto`.

The initial CPU thread count is:

```text
max(2, min(4, availableProcessors - 1))
```

S10 and S25 fallback testing should begin at four threads where the formula
permits it. Phase 3 must not change this policy while introducing GPU behavior.
The exact thread count, production device eligibility, memory threshold, probe
policy, and GPU precision remain tunable from Phase 7 full-song measurements.
Changing them requires wall-time, peak-memory, thermal, cancellation, and
playback-readiness comparisons; it does not require changing the model
contract. A changed GPU option profile does invalidate its runtime evidence and
must receive a new profile ID and validation report. Backend timing and thread-
performance statistics are runtime data and remain excluded from backup.

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
than fail into CPU after an avoidable accelerator attempt. The x86 validation
passed 9662 and KARA inference and numerical comparison. An explicit HQ4 probe
still failed XNNPACK tensor allocation after the API 26 AVD was expanded to
3,036 MiB and the CPU policy was reduced to two threads. HQ4 is unsupported for
the current x86 contract and must not become x86-selectable without a new
artifact/runtime review and explicit memory validation.

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
- independent presentation of reviewed contracts, product tier, maturity, and
  activation policy; and
- a distinction between selectable experimental models and download-only
  candidates, including resource-gated reviewed models.

The app should bundle a reviewed snapshot of the full catalog, including the
recommended, experimental, and download-only entries. The matching bss-tflite
Release should publish the catalog and manifests for audit and reproducibility,
but runtime metadata must not be fetched from a mutable branch or `latest` URL.
The app downloads only the artifact URL pinned by its bundled catalog and
verifies its size and SHA-256 before installation. A later signed remote
catalog can add discovery, but it must not change model code, DSP semantics,
product tier, release maturity, or activation support without an app update and
a pinned contract review.

The model-management screen should show the sole recommended/default model
first, selectable experimental models in a separately warned section, and
download-only entries below them with no normal `Use` action. An installed
candidate may remain inactive while a different model is used. Switching the
active model changes only future work; it does not delete, hide, or alter other
installed models.

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
is unavailable. A root provider must select one available root for a run and
must not combine entries from two roots. If the preferred root disappears,
the app may recreate it or use the fallback and rebuild missing data; it must
not manufacture a persistent migration or treat a path in the other root as
partially valid. This root includes:

- model-aware `entries/<entry>/work`, `segments`, and `completed` data;
- rendered WAV/FLAC stems and the manifest, playback settings, and timing
  files needed to interpret or resume a cache entry;
- playback hydration PCM files and their identity/ready markers, keyed to the
  same cache identity as the rendered entry;
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

The cache identity must be independent of `MdxModelVariant` and must contain
the complete model contract rather than only a display name or enum. The
canonical identity is:

```text
cacheIdentitySchemaVersion
+ canonical source/audio identity
+ stable model ID
+ exact TFLite artifact SHA-256
+ contract ID and contractSchemaVersion
+ canonical contract fingerprint
+ DSP/pipeline identity and pipeline version
+ renderProfileId for output-affecting execution choices
```

The source/audio identity must include the finalized source-audio fingerprint
and the decoder-relevant values needed to distinguish a changed source. A
database song ID or source path is only a locator and is not sufficient by
itself. The model ID, artifact hash, contract ID, and profile revision are
also separate fields: changing a profile in place must never reinterpret an
existing cache.

The canonical contract fingerprint covers only immutable rendering semantics:
tensor contract, DSP and compensation values, stem mapping/residual rule, and
pipeline compatibility. Display text, source URL, conversion provenance, and
mutable runtime qualification are stored for inspection but excluded from
that fingerprint. A profile/contract revision remains an explicit identity
field even when its semantic fingerprint happens to match an older revision.

Define `renderProfileId` separately from the runtime-qualification profile ID.
CPU and GPU execution may map to the same render profile when their output has
been explicitly validated as semantically equivalent; approved FP32 CPU and
FP32 GPU paths should therefore share one entry. Backend name, thread count,
runtime profile ID, timing, and diagnostic counters are not identity fields by
themselves. A precision change, compensation/DSP change, or delegate option
that changes the accepted rendering contract must receive a new render profile
and therefore a new cache entry.

The implementation must serialize the identity through a versioned canonical
encoding with fixed field names/order and UTF-8 normalization, then derive the
full (not truncated) SHA-256 `cacheKey`. Use that key for the entry directory
and lookup. Store the complete identity and key in the manifest and verify
that the manifest identity hashes to the directory key before using an entry.
Do not use a shortened digest, file name, or mutable path as the identity.

The finalized audio fingerprint must be known before a reusable ready window
is published under an entry key. The default implementation should move the
existing encoded-sample hash before cache-run creation: it streams compressed
audio packets without decoding and preserves resumable partial entries. Record
its startup cost on S10 and S25. If that cost proves unacceptable, write into
`staging/<run-id>` and atomically promote the run once the identity is complete.
A staging run may expose output only to its exact in-process worker/playback
lease; it is not listed, resumed after process death, or given cleanup priority
as a persistent cache entry before promotion.

Candidate discovery may use a rebuildable locator index from song ID/URI and
source diagnostics to cache keys, but that index is never authoritative cache
identity. Exact fingerprint agreement is required before resuming, appending,
or using a completed entry for the first time in a process. UI listing may use
stored locator metadata without hashing the source. A stale or corrupt index
must be rebuilt from validated manifests, and replacing audio under the same
database ID/path must not reuse the old entry.

### Manifest and path contract

The new cache manifest must use an independent `manifestSchemaVersion` (the
current v1 manifest is not an accepted input under the clean-install
boundary). It must retain the human-readable model name, artifact hash,
complete immutable contract/profile snapshot, and stem mapping so a cache
remains understandable after the active model changes. At minimum it stores:

- the complete cache identity and `cacheKey`;
- model ID, artifact SHA-256, contract ID/schema, contract fingerprint, and
  profile revision;
- the complete tensor, DSP, pipeline, compensation, and stem semantics used
  for the run;
- finalized audio fingerprint and decoder-relevant source metadata;
- output stem mapping and rendered stem format, channel count, sample rate,
  duration, frame coverage, and integrity information needed for read-only
  playback; and
- backend/profile diagnostics and timing records as non-identity metadata,
  when available.

All file references in a manifest must be relative to the entry root. Resolve
them against that root and reject absolute paths, traversal, symlink escapes,
missing required files, and paths that resolve outside the root. Absolute
paths may appear in ephemeral diagnostics, but never in the portable cache
manifest or playback-settings file. `playback-settings.json` must use its own
schema version and carry the exact `cacheKey` (plus the source fingerprint
needed for validation), not only a song ID or blend value; reject it when it
does not match the enclosing manifest.

Rules for switching models:

- selecting model B must never read or append windows to a cache generated by
  model A;
- switching the active model does not delete or automatically clear any other
  model cache;
- switching back to the exact same model identity may resume that model's own
  partial cache, but this is not cross-model cache reuse;
- normal playback and scheduler lookup use only the exact active model/profile
  identity and never fall back to another completed entry for the same song;
- an already running playback session retains its leased entry until that
  session is rebuilt or ends, so changing the active model cannot splice two
  model outputs into one playback session;
- cache management may explicitly play a validated completed entry by exact
  cache identity without activating or reinstalling its model. This
  session-scoped cache choice never permits new inference or changes the active
  model used for later work;
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
- a worker captures its immutable cache identity at start and must stop at a
  safe window boundary before another identity can start writing to that
  entry.

### Lifecycle, leases, and crash consistency

The cache store must expose exact-entry leases (or equivalent ownership
tokens), not only a set of active file paths or a protected song ID. The
implemented modes are `Read`, `RunWrite`, and `Exclusive`. Shared `Read`
leases protect playback. One `RunWrite` lease owns an inference or hydration
run while permitting readers to consume already atomically published output.
`Exclusive` blocks both modes for FLAC promotion, deletion, and pruning.
Cleanup must skip or wait for an entry with a conflicting lease, and a lease
for one model entry must not protect another model's entry for the same song.
Leases are process-local and never persisted as authoritative locks; after
process death, the on-disk state recovery path decides whether data is
resumable.

Use an explicit on-disk state machine for `staging`, `running`, `completed`,
`canceled`, and `failed` data. Write manifests and state transitions through a
temporary file followed by an atomic replacement. A run becomes `completed`
only after every required output and its integrity metadata have been
validated; cancellation, a failed invocation, or a failed FLAC promotion may
never mark an uncommitted window or promoted output ready. A failed promotion
must leave an already validated WAV output usable. On startup, remove orphaned
temporary files and quarantine or delete abandoned staging runs without
treating them as resumable partial cache entries.

The store must make worker writes, playback reads, model deletion, cache
cleanup, and GPU-to-CPU session recreation race-safe. A failed GPU attempt
must discard uncommitted output and release its GPU runtime/session lease
before CPU recreation, while the worker retains one exact-entry write lease
through the retry. It must not create a second entry or leave a partially
written output visible.

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

`stale`, `model not installed`, and `contract unverified` are derived
eligibility/display statuses, not destructive manifest rewrites performed when
a model or profile is removed. Reinstalling the exact artifact/profile can
make a stale partial entry resumable without changing its identity. Automatic
cleanup counts every non-completed retained entry, including canceled, failed,
and stale partial data, against the partial-entry limit; validated completed
output counts against the completed-entry limit. Staging/temp data is handled
by crash cleanup rather than user cache quotas.

Only caches created by the current LiteRT implementation are in scope. A
completed cache from the current app version may be played without creating a
new inference session, while incompatible or incomplete current-version data
must be rebuilt safely rather than resumed by guesswork. Phase 5 may use an
isolated debug/internal LiteRT cache-writing harness, but the normal ONNX
worker remains the production route until Phase 6. Its existing v1/old-layout
store may remain untouched as an isolated development baseline, but the new v2
store never scans, imports, wraps, or writes it. Phase 6 replaces the normal
worker/cache wiring rather than bridging the stores, and Phase 8 removes the
legacy path. No Phase 5 code may write TFLite data under the legacy 9482
identity or teach the v2 store to read or migrate the current cache.

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
- Offer `Play cached result` only for a validated completed entry. The action
  targets that exact cache for the playback session and does not activate its
  model or make it eligible for new separation work.

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

The installed-model details view must remain available after the initial
import and must also be available for every catalog preset before and after
download. It should show the artifact hash and size, contract/profile schema,
tensor shapes and layout, DSP values, pipeline compatibility, output stem
semantics, source/conversion provenance, runtime qualification, and whether
the metadata is built-in, sidecar-provided, or manually entered. Built-in
catalog contracts and each installed sidecar revision are read-only. An
explicit sidecar replacement/rebind or `Clone as custom profile` action creates
a new validated profile identity instead of mutating the installed revision. A
custom profile may be edited, but saving edits likewise creates a new profile
revision and identity. It must never mutate the profile referenced by an
existing cache or by a running job.

The old profile revision remains inspectable as an orphaned profile when it is
still referenced by a cache. Its completed caches retain their immutable
contract snapshot and remain read-only playable; its partial caches become
stale and require the exact artifact and profile revision to resume. The UI
should provide details, revision history, export of a portable sidecar where
possible, and explicit delete actions for orphaned custom profiles. Editing
metadata must never implicitly download, activate, delete, or rebuild a model
or cache.

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
- [x] Convert the three provisionally recommended `bss-tflite` manifests into
  catalog entries, then add experimental and download-only candidates.
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
- At contract-freeze time, catalog v1 classified three recommended, 19
  experimental, and eight generic target-stem download-only entries.
  Experimental entries remain blocked until conversion produces a pinned
  artifact and a reviewed complete contract. This is a historical contract
  snapshot, not the final product-tier decision: Phase 4 reclassifies 9662,
  KARA, and HQ4 using the Phase 3 evidence without discarding their reviewed
  contracts.
- The three complete sidecars pin the existing converted TFLite sizes and
  SHA-256 values. They also freeze output compensation at `1.035` for 9662 and
  KARA and `1.019` for HQ4 before mixture-minus-output reconstruction.
- The bundled Android catalog is copied from `bss-tflite` revision
  `73e6b25c51dd57da62e3eb30ba93d4a1fe8d5ea6` and pinned by catalog SHA-256
  `a1cb77832cdb28864dd4388ec8b66ca16e25903dde8752c568eaffadb1e45ff5`.
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

Status: completed on 2026-07-21. The device matrix, resource decisions, and
packaging evidence are recorded in
[`litert-cpu-validation-results.md`](litert-cpu-validation-results.md), with
checksummed raw reports under [`validation/litert-cpu-phase2/`](validation/litert-cpu-phase2/).
HQ4 passed 64-bit window parity but remains internal-only because it exceeds
the provisional memory limit; both 32-bit targets are explicitly unsupported.

Phase 2 deliberately keeps ORT as the production default while LiteRT is
validated through debug and test entry points. Builds temporarily contain both
runtimes; final installed-size targets apply after ORT removal in Phase 8, not
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

- [x] Introduce runtime-neutral factory, session, lease, execution-profile,
  backend-diagnostics, and runtime-settings types. The execution profile binds
  verified model identity, tensor contract, DSP profile, output scale, and
  stem semantics without implementing installed-model selection yet.
- [x] Keep flat NCHW tensors as the DSP-facing input/output contract and move
  every direct `OrtSession`, `OnnxTensor`, input-name, output-name, and result
  access out of `MdxRangeSeparator` into an ORT adapter.
- [x] Run the current 9482 production path through the new interface with a
  behavior-preserving legacy profile before adding LiteRT. Do not change
  scheduler priority, cache paths, output naming, or playback gating.
- [x] Construct `MdxDspConfig` and all derived window dimensions from an
  execution profile, while fixing the legacy profile to its current values.
  Add a contract-backed HQ4 configuration test before attempting inference.
- [x] Generalize the reusable provider so its cache key includes artifact
  SHA-256, contract/pipeline identity, backend, and runtime settings, and so a
  lease never exposes an engine-specific session type.
- [x] Add fake-session unit tests for acquire/reuse/replacement/close order,
  tensor element counts, backend diagnostics, pause, cancellation, and a
  failed invocation that must not mark a window ready.
- [x] Run host unit tests in ordinary CI in addition to lint and assembly.

#### Phase 2B: LiteRT packaging and native supply chain

- [x] Pin `com.google.ai.edge.litert:litert:2.1.5` in the version catalog and
  keep all LiteRT API use inside the runtime adapter package.
- [x] Vendor the canonical `v2.1.5-bss.1` x86 binary as
  `app/src/main/jniLibs/x86/libLiteRt.so` and record its Release URL, asset
  name, byte size, SHA-256, source commit/toolchain manifest, LiteRT license,
  and third-party notices in the repository.
- [x] Add CI checks for the vendored x86 file's hash, ELF32/i386 machine,
  expected LiteRT JNI and C API exports, and dynamic dependency allowlist.
- [x] Keep the official LiteRT libraries for `armeabi-v7a`, `arm64-v8a`, and
  `x86_64`; package exactly one `libLiteRt.so` per ABI without `pickFirst`.
  Inspect every ABI split and the universal APK rather than only Gradle's
  merged-native-libs directory.
- [x] Add an API 26 pure-x86 instrumentation smoke test with the small
  Apache-2.0 model so the exact app APK proves that `Environment`,
  `CompiledModel`, `TensorBuffer`, JNI loading, invocation, and close all work.

#### Phase 2C: LiteRT CPU session

- [x] Implement a CPU session with LiteRT `Environment` and `CompiledModel`.
  Create input/output `TensorBuffer` objects once per session and reuse them
  together with NCHW/NHWC conversion scratch buffers for every window.
- [x] Resolve minimum API, ABI, backend, and contract compatibility before
  source decoding, cache-run creation, output-file creation, tensor allocation,
  or `CompiledModel.create`. Treat `unsupported` as a hard preflight result;
  permit an `untested` status only in the internal validation path until it is
  promoted by device evidence. A missing ABI/backend status is unsupported,
  not an invitation to guess.
- [x] After model creation, validate one named float32 input and output against
  the exact contract names, static NHWC shapes, element counts, and layouts
  before the first invocation. Reject non-finite output before ISTFT.
- [x] Return raw output in canonical NCHW order. Apply
  `modelOutputScale` once after ISTFT, construct the residual from the scaled
  waveform, and map both outputs using the contract's stem semantics.
- [x] Use `max(2, min(4, availableProcessors - 1))` as the initial LiteRT CPU
  thread policy. Record the resolved count in diagnostics but do not expose it
  as a user setting or backup value.
- [x] Check cancellation before and after the non-interruptible invocation,
  discard an output canceled in flight, and prohibit concurrent session close.
- [x] Add unit tests for NCHW/NHWC round trips with non-symmetric dimensions,
  exact output compensation, residual reconstruction, tensor mismatch,
  non-finite output, compatibility decisions, and session replacement.
- [x] Add a factory-spy test proving HQ4/x86 is rejected before
  `CompiledModel.create` or any large tensor allocation.

#### Phase 2D: Internal integration and parity validation

- [x] Add a debug/internal runner that accepts a locally staged TFLite file
  only after its file identity and complete bundled contract match. Keep it
  out of release UI, normal model acquisition, production defaults, and
  settings persistence. Write only to an isolated validation directory under
  the cache root; do not use the production `SourceSeparationCache`, foreground
  worker, playback gate, or existing 9482 cache identity.
- [x] Compare raw NCHW LiteRT output with the frozen ORT tensor reference for
  the same checked input, then independently validate compensation, stem
  mapping, and residual reconstruction.
- [x] Record the actual process architecture (`SUPPORTED_ABIS`, `os.arch`,
  `Process.is64Bit()`), installed APK/split identity, and loaded runtime
  inventory in every device report. An ABI list alone is not sufficient
  evidence that a particular native library executed.
- [x] Exercise 9662, KARA, and HQ4 in arm64 processes on S10 and S25 CPU. On
  S10, separately install the `armeabi-v7a` split and validate at least 9662
  and KARA; test HQ4 only after the 32-bit compatibility preflight accepts its
  memory budget, otherwise record it as unsupported.
- [x] Exercise all three models with the official x86_64 runtime and exercise
  9662 and KARA with the supplemental API 26 pure-x86 CPU runtime. Route pure
  x86 directly to CPU without attempting GPU setup.
- [x] Reconcile Phase 1 v1 `runtimeCompatibility` only from these app-packaged
  reports: update the authoritative v1 snapshots in `bss-tflite`, regenerate
  the bundled catalog snapshot, and keep any combination without sufficient
  evidence `untested` or `unsupported`. Do not patch only the app's copied JSON.
  Phase 4 supersedes this placement by moving runtime qualification out of
  contract schema v2.
- [x] Run cancellation before invocation and during a blocking invocation,
  session reuse, session replacement, and process restart tests. Confirm the
  unchanged production ORT path still follows existing scheduler and playback
  behavior.
- [x] Store the resulting parity, timing, memory, and packaging report with
  the app commit, catalog revision, contract IDs, runtime version, ABI, device,
  Android version, and fixture hashes.

Acceptance criteria:

- The ORT adapter is behaviorally equivalent to the pre-refactor production
  path before LiteRT is selected by any internal test, and production playback
  still has no route that silently selects LiteRT.
- All three Phase 1 reviewed models produce correctly shaped, finite CPU output in
  arm64 processes on S10 and S25 and in an x86_64 process; 9662 and KARA also
  pass the `armeabi-v7a` S10 process and pure-x86 CPU path.
- For the frozen synthetic and Coast Town `bss-tflite` parity fixtures, raw
  output meets these machine-checked floors against ORT. These limits apply to
  the named inputs; maximum absolute error is input-amplitude dependent and is
  not a universal quality threshold for arbitrary songs.

  | Model | Minimum SNR | Minimum cosine | Maximum absolute error |
  | --- | ---: | ---: | ---: |
  | 9662 | 93.8 dB | 0.999999999 | 0.00010 |
  | KARA | 109.0 dB | 0.999999999 | 0.00003 |
  | HQ4 | 89.4 dB | 0.999999999 | 0.00060 |

  The 9662 SNR floor was calibrated from the app-packaged LiteRT 2.1.5 CPU
  result for `synthetic_00` on the S25. Its stable 93.891 dB result was
  identical with two, three, and four CPU threads; cosine similarity and
  maximum absolute error remained within the stricter limits above.
  The HQ4 floor was likewise calibrated from its S25 `synthetic_00` result:
  89.500 dB with identical output at two and four threads, cosine similarity
  0.999999999439, and maximum absolute error 0.000118.

- Unit and connected tests prove that the contract scale is applied exactly
  once and that scaled primary plus residual reconstructs the unclipped input
  window with maximum absolute error at most `0.00001`.
- HQ4 returns an explicit unsupported compatibility result on the normal x86
  path, and a factory spy confirms no `CompiledModel`, tensor buffer, or large
  conversion buffer was allocated. The separate opt-in probe records that
  allocation still fails with 3,036 MiB AVD RAM and two CPU threads; it does
  not weaken the preflight block.
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
model-aware cache work in Phases 4 and 5 is complete. Production ORT routing
must remain unchanged throughout this phase.

Status: implementation and controlled device evidence completed on 2026-07-21.
The detailed matrix is recorded in
[`litert-gpu-validation-results.md`](litert-gpu-validation-results.md) and
[`validation/litert-gpu-phase3/`](validation/litert-gpu-phase3/). This does
not approve production Auto: 9662 `gpu-auto-fp32-v1` is the only Phase 7
full-song candidate; KARA FP32 deterministically missed its synthetic-input
raw-output floor, FP16 failed parity, and HQ4 exceeded its resource gate while
lacking a known-good CPU fallback. All arm64 GPU compatibility records remain
`untested` until the Phase 7 gates pass.

The implementation must target the API actually shipped by the pinned LiteRT
2.1.5 AAR. It provides `Environment.getAvailableAccelerators()`,
`CompiledModel.Options(Accelerator.GPU)`, and `GpuOptions`, while the packaged
`libLiteRtClGlAccelerator.so` exists only for `arm64-v8a` and `x86_64`. It does
not expose a supported operator-coverage query or the selected OpenCL/OpenGL
implementation. Reports must state this evidence boundary rather than infer
more than the API can prove.

#### Phase 3A: GPU adapter and runtime profiles

- [x] Add a GPU factory and session beside the CPU implementation under the
  same runtime-neutral interface. Keep LiteRT API types inside the adapter
  package and keep flat NCHW arrays at the DSP boundary.
- [x] Introduce versioned internal GPU runtime profiles and include the profile
  ID in the factory/session identity and diagnostics. Begin with explicit
  `AUTOMATIC + FP32` options as the correctness baseline; evaluate
  `AUTOMATIC + FP16` as a separate optimization candidate. Do not silently
  inherit LiteRT defaults or treat forced OpenCL/OpenGL diagnostic runs as the
  same profile.
- [x] Reuse one compiled model, one named input buffer, one named output buffer,
  and NCHW/NHWC scratch arrays per GPU session. Apply the Phase 2 tensor name,
  float32, static-shape, element-count, finite-output, and close-order checks
  without creating a second CPU session alongside it.
- [x] Require `Accelerator.GPU` from the created environment before model
  compilation. Treat successful GPU-only `CompiledModel.create` and invocation
  as the available operator-compatibility test because LiteRT 2.1.5 has no
  public delegated-operator coverage API.
- [x] Preserve the Phase 2 lease rule: invocation is non-interruptible, close
  cannot race an in-flight call, cancellation after return discards output,
  and session replacement occurs only after the active lease is released.

#### Phase 3B: Auto eligibility and one-way fallback

- [x] Add an `Auto` controller above the low-level GPU and CPU factories. Its
  key must bind artifact SHA-256, contract/pipeline identity, GPU runtime
  profile, CPU runtime settings, and process ABI.
- [x] Route `armeabi-v7a` and pure `x86` directly to CPU without creating a GPU
  environment. Permit arm64 GPU attempts only through the internal `untested`
  compatibility policy in this phase. Treat x86_64 as packaging/API evidence
  only until an exact GPU compatibility record and representative validation
  exist; native-library presence alone is not eligibility.
- [x] Require a known-good CPU record for the exact model, contract, and ABI
  before enabling recoverable fallback. Consequently, validate 9662 and KARA
  as the Auto candidates and keep HQ4 GPU work exploratory while its arm64 CPU
  path exceeds the resource gate.
- [x] Define typed outcomes for static skip, accelerator unavailable, GPU setup,
  tensor setup, probe write/invoke/read/validation, normal invocation,
  output read/validation, cancellation, out-of-memory, GPU cleanup, CPU setup,
  and CPU invocation. Preserve the first failure and attach later cleanup or
  fallback failures as secondary diagnostics.
- [x] Recover only explicitly classified accelerator failures. Do not turn an
  arbitrary `Throwable`, cancellation, VM error, or memory error into a CPU
  retry that could hide a programming fault or worsen process pressure.
- [x] For a recoverable GPU failure, discard its output, close every GPU
  resource, create CPU only after cleanup succeeds, and rerun the same input
  once. Latch the session to CPU after fallback and prohibit repeated retries
  or a CPU-to-GPU transition.
- [x] Do not fall back for cancellation. Treat an unconfirmed GPU cleanup or an
  out-of-memory condition as terminal for that attempt so Auto cannot retain a
  large GPU allocation while creating a CPU model.
- [x] Keep the Phase 2 CPU formula unchanged and keep `GPU only`, precision,
  forced API selection, probe controls, and backend timing out of user settings
  and backup schemas.
- [x] Emit structured internal diagnostics for requested policy and profile,
  eligibility decision, available accelerators, attempted and accepted
  backend, setup/probe/inference/cleanup timings, fallback stage and reason,
  and CPU retry result. Do not persist performance history as a preference.

#### Phase 3C: Fault injection and app-packaged device evidence

- [x] Extend the Phase 2 runner with deterministic GPU reports that pin the app
  commit, catalog revision, model/runtime hashes, process ABI, Android/device
  identity, GPU profile, fixture and ORT-reference hashes, and parity thresholds.
- [x] Keep complete fixture inputs and ORT reference tensors in AndroidTest
  staging only. Make the probe policy injectable so Phase 7 can choose a compact
  production probe without adding full validation tensors to release APKs.
- [x] Add host tests with blocking and fault-injecting factories for eligibility
  skip, setup, probe write/invoke/read/non-finite/parity, normal invocation,
  output read/non-finite, cancellation, cleanup, CPU recreation, CPU failure,
  session reuse/replacement, and process-level controller recreation.
- [x] On S10 and S25 arm64, run 9662 and KARA against the frozen synthetic and
  Coast Town fixtures. Establish FP32 correctness first, then measure FP16
  separately for parity, repeatability, setup/reuse time, and memory. A
  precision profile cannot borrow another profile's evidence.
- [x] Add internal connected-test failpoints around setup, probe, invocation,
  and output read. On each arm64 device, close at least one real GPU session and
  prove that a real CPU session recomputes the same 9662 input once.
- [x] Prove the strongest evidence available from LiteRT 2.1.5: the APK contains
  the arm64 accelerator library, the runtime reports `Accelerator.GPU`, the
  accelerator library appears in the process mappings, the model was requested
  with the GPU-only profile, and repeated invocation returns valid output.
  Explicitly record that per-operator placement cannot be queried.
- [x] Record snapshots before environment creation, after compilation/buffer
  allocation, after first and reused inference, and after close. Include total,
  native, graphics/EGL/mtrack where available, and aggregate PSS rather than
  relying on Java heap alone.
- [x] Exercise HQ4 on S10 and S25 only as an exploratory GPU resource probe.
  Even a successful window does not make it Auto-selectable while its CPU
  fallback is not production-approved.
- [x] Assert zero GPU allocator calls for `armeabi-v7a` and pure `x86`. An
  x86_64 emulator may validate loading and API behavior, but its host-backed GPU
  result must not create a production compatibility record by itself.
- [x] Reconcile GPU evidence through the Phase 1 v1 `bss-tflite` contracts and
  regenerate the bundled catalog; never patch only the app copy. Replace the
  current deferred evidence with Phase 3 report references. Keep a successful
  candidate `untested` until Phase 7 full-song, thermal, and playback-readiness
  gates approve a production profile; use `unsupported` only for a repeatable,
  explicitly evidenced incompatibility. Phase 3 must not set GPU `known-good`.
  Phase 4 moves these records into profile-aware catalog qualification.
- [x] Verify no failed GPU session leaves the internal job or validation state
  stuck. Repeat the cache-ready and playback-gate assertions after Phase 5
  integrates the controller with model-aware production state.

Acceptance criteria:

- Every 9662/KARA FP32 cell on S10 and S25 produces either an app-packaged GPU
  report meeting the Phase 2 model-specific raw-output floors or a repeatable,
  explicit unsupported result. Only a profile that passes both devices enters
  Phase 7 as a production candidate. FP16 results are reported separately, do
  not borrow an FP32 pass, and do not relax thresholds merely to finish Phase 3.
- Each report proves GPU accelerator discovery, GPU-only model creation,
  accelerator-library loading, valid repeated output, and the API's lack of an
  operator-placement query. It does not claim GPU use from ABI inventory alone.
- Forced recoverable failures at setup, probe, invocation, and output read or
  validation close GPU first and complete the same input once on known-good
  CPU. No failed GPU output is accepted, no controller loops back to GPU, and
  no large GPU and CPU sessions remain live together.
- Cancellation causes no fallback; out-of-memory or unconfirmed cleanup ends
  cleanly without another large allocation. Every injected path reaches a
  terminal success, cancellation, or failure state with no concurrent close,
  duplicate retry, leaked lease, or permanent loading state.
- HQ4 and x86_64 GPU reports remain exploratory, and arm32/x86 perform zero GPU
  allocations. Production inference still routes through ORT, and no catalog
  GPU record becomes `known-good` before Phase 7.
- Phase 3 does not change the CPU thread formula or decide that Auto should
  prefer GPU in production. Full-song wall time, thermal behavior, memory,
  cancellation, and playback readiness remain Phase 7 gates.

### Phase 4: Multi-preset repository and post-Phase 3 catalog

Model management may be developed and tested before production inference is
switched, but it remains behind a development feature gate. Selecting a TFLite
model must not route a normal worker through that model while cache identity is
still song/legacy-variant based. The gate may reach the normal worker only
through the ordered Phase 6 cutover after the Phase 5 storage/cache contract is
accepted. Production-shaped player and UI integration is part of that cutover;
no release tier is promoted until Phase 7 closes.

The first deliverable is contract schema v2 plus a new catalog revision rather
than an in-place rewrite of the frozen Phase 1 snapshot. Contract v2 removes
the v1 `runtimeCompatibility` block, while preserving reviewed artifact,
tensor, DSP, stem, source, conversion, and pipeline facts. The catalog must
keep that reviewed contract independent from support level and activation
policy. In particular, KARA and HQ4 retain complete reviewed contracts even
though KARA becomes experimental and HQ4 becomes resource-gated download-only.
The catalog revision must represent at least these independent dimensions:

- contract review and the exact contract/artifact identity;
- product tier: `recommended`, `experimental`, or `download-only`;
- activation policy: normal selectable, explicitly warned CPU-only
  experimental, resource-gated download-only, or blocked pending contract/UI;
- release maturity: candidate, beta-ready, or stable; and
- runtime evidence keyed by model hash, contract/pipeline identity, LiteRT
  version, ABI, backend, and execution-profile ID, including precision.

Its initial policy is fixed: 9662 FP32 is the only recommended/default
candidate; KARA FP32 is a CPU-only experimental candidate and has no Auto GPU
path; HQ4 is reviewed but download-only because it has no eligible fallback and
exceeds the current resource gate. FP16 has no preset entry. Every other
candidate remains download-only until it satisfies its own contract, stem-UI,
desktop, CPU, and full-song promotion requirements. Promotion is per model and
per execution profile, never a bulk conversion of all candidates into
experimental models.

Before production download integration, `bss-tflite` must publish the canonical
candidate artifacts in an immutable versioned Release. Complete the pinned
conversion, provenance, sidecar/contract review where activation is claimed,
desktop numerical validation, per-asset checksums, and release manifest for all
canonical candidates intended for the first broad testing wave. Entries whose
DSP or stem semantics remain incomplete may still be published as
`download-only`; artifact availability must not upgrade activation support.

- [x] Add contract schema v2 without mutable runtime qualification, regenerate
  the three reviewed contracts and exact-name sidecars, and reject v1 as an
  official-release contract under the clean-install boundary.
- [x] Add a catalog schema/revision that separates contract review, product
  tier, activation policy, release maturity, and profile-aware runtime evidence;
  retain the Phase 1 catalog as an immutable historical snapshot.
- [x] Reclassify 9662 FP32 as the sole recommended/default candidate, KARA FP32
  as CPU-only experimental, and HQ4 as resource-gated download-only without
  deleting either reviewed contract.
- [x] Record rejected FP16 and KARA GPU profiles by exact execution-profile ID;
  permit a future new profile to be tested without overwriting that evidence.
- [x] Replace `MdxModelVariant.MDXNET_9482` as the sole active path with a
  catalog-backed model ID in the new repository and selection state, without
  yet changing the feature-gated production worker.
- [x] Pin the immutable `bss-tflite` Release tag, asset URLs, byte sizes, and
  hashes in a reviewed catalog revision; never resolve `latest` at runtime.
- [x] Install every artifact under a separate hash-aware model directory.
- [x] Track download/import state per model.
- [x] Add separate download, active-model selection, and manual deletion
  operations.
- [x] Prevent a completed download from changing the active model.
- [x] Keep inactive downloaded models until the user explicitly deletes them.
- [x] Display the sole recommended/default model, explicitly warned selectable
  experimental models, and download-only candidates with distinct activation
  rules.
- [x] Apply tier-specific `Use` gates: a recommended release model requires its
  Phase 7 stable evidence; an experimental model requires its Phase 7 CPU
  evidence and manual user confirmation; download-only entries have no normal
  `Use` action. A window-level `known-good` CPU record alone is not a release
  promotion.
- [x] Require a matching `known-good` CPU profile for every selectable
  model/ABI. `Auto` may add only an individually approved GPU profile and must
  retain that CPU fallback; `untested`, rejected, missing, and `unsupported`
  profiles remain downloadable but not usable outside internal validation.
- [x] Implement the import priority: built-in contract by SHA-256, matching
  sidecar, then advanced profile form with an unverifiable-quality warning.
- [x] Keep every download or import inactive until the user explicitly chooses
  `Use`.
- [x] Persist the active model as a stable ID/hash/contract reference rather
  than a model path; keep installed weights and official manifests outside
  manual and system backups.
- [x] Persist portable custom profile metadata separately from imported model
  files, content URIs, and download state.
- [x] Show a restored pending model target with its model ID and short hash;
  distinguish missing weights from an installed-but-inactive target, retain a
  different active model, and provide an explicit discard action.
- [x] Align Android full-backup/data-extraction rules with the same policy so
  model weights and source-separation cache cannot enter system backup.

Acceptance criteria:

- Every network-backed catalog entry resolves to one immutable Release asset
  with a matching size and SHA-256, or remains explicitly unavailable rather
  than falling back to a mutable source URL.
- The catalog has exactly one recommended/default entry, 9662 FP32. KARA and
  HQ4 retain their reviewed contracts while carrying their different activation
  policies.
- Official contracts and sidecars use schema v2 and contain no ABI, backend,
  precision, device, or runtime-profile qualification records.
- Runtime evidence for a GPU profile cannot overwrite CPU evidence, a different
  precision, or a different versioned profile.
- Recommended, selectable experimental, and download-only artifacts can coexist
  without overwriting files or metadata.
- Downloading a model does not select it, and selecting a model does not delete
  another installed model.
- The active model cannot be deleted accidentally.
- No model can become active on an ABI whose complete CPU compatibility state
  is anything other than `known-good`; no model becomes release-selectable from
  that condition alone.
- Switching models affects only new separation work.
- An unknown import can be installed through a valid sidecar or completed
  advanced form without being activated automatically.
- Official hash mismatch remains visible and non-blocking according to the
  existing import policy.

### Phase 5: Model-aware caches and storage

Phase 5 integrates the feature-gated LiteRT path with a new cache identity,
but it is intentionally split into storage-contract, storage-engine,
lifecycle, and user-facing work. Its storage, identity, lease, crash
consistency, and recovery contracts must pass through the isolated LiteRT
harness before any normal worker can honor the selected TFLite model. Full
MediaSession and gated-UI integration require that normal route and therefore
belong to Phase 6. No transitional implementation may write a TFLite result
under `MdxModelVariant.MDXNET_9482`, the current v1 manifest, or the old
`externalFilesDir` layout. The v2 store and UI are exercised behind the
development gate with the internal LiteRT harness; the unchanged ONNX/v1 path
is a regression baseline only. Phase 5 acceptance does not require a legacy
cache migration or a production-route cutover, and must not introduce either.

#### Phase 5A: Freeze cache identity and manifest v2

- [x] Define a runtime-neutral `SourceSeparationCacheIdentity` that does not
  depend on `MdxModelVariant` and contains the finalized audio identity,
  model ID, artifact SHA-256, contract ID/schema, canonical contract
  fingerprint, DSP/pipeline identity, and a `renderProfileId` for
  output-affecting execution choices.
- [x] Canonicalize the contract fingerprint from tensor, DSP, compensation,
  stem, and pipeline semantics only. Exclude display, provenance, and runtime
  evidence while retaining explicit contract and custom-profile revision IDs
  in the cache identity.
- [x] Define which runtime differences are cache identity changes. Permit CPU
  and GPU to share an entry only after their rendered output is explicitly
  validated as semantically equivalent and mapped to the same `renderProfileId`
  (for example, approved FP32 CPU/GPU). Make precision, compensation/DSP, or
  other rendering-contract changes use a new render profile. Keep backend,
  runtime qualification profile ID, thread count, and timing as diagnostics
  when they do not affect output semantics.
- [x] Specify a canonical, versioned identity encoding and derive a full
  SHA-256 `cacheKey`. Use the key as the entry directory name and verify it
  against the identity stored in the manifest; do not truncate the digest or
  use a path/name as identity.
- [x] Move the existing encoded-sample fingerprint to preflight before cache
  run creation. Retain staging plus atomic promotion only as a possible
  fallback; a provisional run may be visible only through its exact
  in-process lease, never as reusable cache.
- [x] Measure encoded-sample fingerprint startup cost on S10 and S25 before
  signing the Phase 5 exit gate. Keep the current preflight path: the 12-second
  Coast Town fixture measured 107 ms on S10 and 24 ms on S25 for the first
  model run, with no evidence that the staging fallback is needed.
- [x] Replace the current manifest with an independent `manifestSchemaVersion`
  v2 that stores the complete immutable contract/profile snapshot, source
  identity, stem mapping, rendered-output metadata, and integrity data.
- [x] Version `playback-settings.json` independently and bind it to the full
  cache key and source fingerprint before loading or writing a per-song blend.
- [x] Store only entry-relative file names in manifests and playback settings;
  reject absolute paths, traversal, symlink escapes, and manifest identities
  whose key does not match their directory.
- [x] Define explicit rejection of v1/unknown manifests under the clean-install
  boundary. Do not add a v1 reader, migration marker, old-layout scan, or
  compatibility fallback.
- [x] Add pure JVM tests for canonicalization, deterministic keys across
  process restarts, same-render-profile CPU/GPU identity sharing,
  output-affecting precision separation, profile revision identity, malformed
  manifests, and path containment.

#### Phase 5B: Introduce a testable cache store and root provider

- [x] Separate cache identity, entry lifecycle, and file access from Android
  `Context` through a root provider and an injectable/testable cache store (or
  equivalent filesystem, clock, and hashing seams). Keep production model and
  runtime types out of the pure identity/manifest layer.
- [x] Resolve `externalCacheDir/source-separation` first and
  `cacheDir/source-separation` second. Keep a run and all of its entry files in
  one root; do not join entries across roots or scan the old
  `externalFilesDir(Environment.DIRECTORY_MUSIC)` tree.
- [x] Implement `staging`, `entries`, and temporary-manifest layout with
  atomic directory promotion, atomic manifest replacement, required-file
  validation, and safe orphan/staging cleanup after process restart.
- [x] Add a rebuildable locator index for candidate discovery only. Require
  exact source fingerprint agreement before first playback use, resume, or
  append; rebuild a stale index from validated manifests and never treat song
  ID/path as cache identity.
- [x] Validate every resolved path remains below its entry root before opening,
  deleting, sharing, or promoting it. Keep diagnostic exports separate from
  the portable manifest path contract.
- [x] Add focused JVM tests for preferred-root and fallback-root selection,
  root deletion/recreation, external storage becoming unavailable, process
  restart during each state transition, stale/corrupt locator indexes, source
  replacement under the same locator, corrupt temporary files, and missing
  output files.

#### Phase 5C: Integrate model-aware lifecycle and playback

- [x] Adapt cache and engine APIs to accept the immutable cache identity and
  contract snapshot rather than a `MdxModelVariant`; capture the identity at
  run start and prevent any later active-model change from changing that run.
  Keep the v2 path behind the development gate and the normal worker on ORT
  until the ordered Phase 6 cutover; use only an isolated internal LiteRT
  harness while this phase is being validated. Do not retrofit the old v1
  cache or create a bridge between the stores.
- [x] Keep separate partial and completed entries for every model/profile used
  by one song. Selecting a model must never read, append, or promote files
  belonging to another identity.
- [x] Make normal scheduler/playback lookup exact-active-identity only. Keep a
  running playback session on its leased entry across an active-model change,
  and support an explicit completed-cache playback session that cannot start
  inference or alter the active model.
- [x] Keep completed output read-only playable after model or custom-profile
  deletion when its files, manifest, output mapping, and contract snapshot
  validate. Keep incomplete entries stale until the exact artifact and
  profile revision return.
- [x] Scope per-song blend settings, hydration PCM, FLAC promotion, and any
  ready markers to the same cache identity. Keep per-song blend authoritative
  in the entry-local file and outside all backup payloads.
- [x] Replace song-level cleanup protection and path-based `activeFiles` with
  exact-entry `Read`, `RunWrite`, and `Exclusive` leases covering worker
  writes, playback reads, hydration, FLAC promotion, delete, and prune. Keep
  leases process-local. Ensure GPU failure cleanup and CPU retry retain one
  `RunWrite` lease without exposing partial output.
- [x] Count each model/profile entry independently for automatic cleanup;
  count stale/canceled/failed non-completed data in the partial bucket and
  validated output in the completed bucket. Deletion of an installed model
  must never rewrite or delete its cache entries or unrelated entries.
- [x] Define and test the state transitions for cancellation, failed
  inference, failed promotion, process death, and clear-cache loss. A ready
  window or completed output may be published only after successful atomic
  write and validation.
- [x] Add focused JVM lifecycle tests for immutable model switching, resumable
  pause/cancel/failure state, concurrent cleanup/playback leases, manual entry
  deletion, FLAC promotion/hydration, and GPU-to-CPU recreation.
- [x] Complete device instrumentation for model-aware runs, independent
  multi-model entries, FLAC promotion, PCM hydration, busy read-lease
  protection, process restart between lifecycle and recovery checks, and
  clear-cache recovery on S10, S25, x86, and x86_64.

#### Phase 5D: Management UI, profile revisions, and recovery

- [x] Show model ID, artifact hash/short identity, contract/profile revision,
  state, format, size, and last access for each cache entry; treat same-song
  entries as independent items even when grouped visually.
- [x] Add a details view for every downloaded preset and imported model. Show
  built-in, sidecar, or manual metadata, tensor/DSP/stem semantics,
  provenance, runtime qualification, and uninstalled/unverified status.
- [x] Keep the v2 cache-management UI and cache playback action behind the
  development gate until Phase 6 switches the normal worker; the old cache
  screen must not silently display or mutate v2 entries.
- [x] Make custom profile edits create a new validated revision instead of
  mutating an identity referenced by existing caches. Keep old revisions
  inspectable and offer explicit orphan-profile delete/export actions.
- [x] Keep `Download`, `Use`, `Delete model`, and `Delete cache entry` as
  separate operations. Switching or deleting a model must not clear or reuse
  another model's cache.
- [x] Offer an explicit `Play cached result` action for validated completed
  entries, including entries whose model/profile is no longer installed;
  never use this as an automatic fallback from the active model.
- [x] Make Android clear-cache and partial-root-loss recovery visible and
  deterministic: installed model weights and persistent model metadata remain,
  while generated separation data is rebuilt as disposable cache.
- [x] Ensure backup creation never reads cache manifests,
  `playback-settings.json`, hydration files, or temporary per-song blend
  keys, and that restore never creates cache entries or restores blend values.
- [x] Add JVM state and contract tests for metadata inspection, immutable
  profile revision editing/export, orphan deletion rules, independent model
  entries, cache-root recreation, and backup exclusion.
- [x] Cover multiple model entries, clear-cache recovery, busy deletion, and
  exact completed-cache file access on S10, S25, x86, and x86_64 through the
  Phase 5 device test. JVM backup tests cover backup/restore exclusion; a full
  UI-driven backup/restore run remains pending.

Deferred production-integration checks:

- Phase 6C owns MediaSession/instrumented lifecycle tests for real playback
  pause/resume, seeking, song transitions, concurrent cleanup during playback,
  FLAC hydration handoff inside an active player, and GPU-to-CPU recreation.
- Phase 6C also owns Compose/instrumentation tests for inspection before and
  after download, profile-revision actions, and separate Download/Use/Delete
  controls. Running them there verifies the same UI and repository graph that
  the cutover-enabled app actually exposes.

Phase 5 implementation record (2026-07-22):

- `40a11e08` through `1450e2f9` freeze identity/manifest v2, add the atomic
  cache store, preflight source identity, three-mode leases, isolated
  lifecycle, and resumable run coordination.
- `91ed6d1e` through `32a73d83` integrate the debug-only LiteRT engine, WAV/FLAC
  promotion and hydration, cache-root cleanup, immutable profile revisions,
  metadata/cache management UI, exact completed-cache playback, and hydrated
  playback handoff.
- `562bf507` permits multiple portable profile revisions for one artifact while
  retaining unique profile IDs and exact active-profile references.
- `8c6aa147` adds portable profile-revision export without model weights,
  content URIs, or cache data.
- `4ba1b424` adds the reusable Phase 5 device instrumentation and host runner.
- Full GitHub debug JVM tests, debug lint, debug/release APK assembly, and
  AndroidTest compilation passed on 2026-07-22. The release APK manifest and
  ZIP inventory contain no debug-only source-separation receiver/activity.
- The device runner passed on a clean debug install for all four devices:

  | Device | ABI/API | 9662 backend/time | KARA backend/time | Preflight (9662/KARA) | Entries / clear-cache |
  | --- | --- | --- | --- | --- | --- |
  | Galaxy S10 | arm64-v8a / 31 | LiteRT GPU / 19,793 ms | LiteRT CPU / 14,226 ms | 107 / 35 ms | 2 / pass |
  | Galaxy S25 | arm64-v8a / 35 | LiteRT GPU / 4,452 ms | LiteRT CPU / 5,925 ms | 24 / 10 ms | 2 / pass |
  | API 26 emulator | x86 | LiteRT CPU / 9,348 ms | LiteRT CPU / 8,766 ms | 109 / 208 ms | 2 / pass |
  | API 37 emulator | x86_64 | LiteRT CPU / 10,661 ms | LiteRT CPU / 7,753 ms | 240 / 240 ms | 2 / pass |

  Each run used the 12-second Coast Town fixture and produced three windows
  per model. These are cache/lifecycle checks, not full-song MediaSession or
  thermal acceptance. They close the Phase 5 foundation gate; production
  player/UI integration moves to Phase 6C, and full-song thermal and promotion
  evidence remains in Phase 7.

Phase 5 storage/cache exit gate (met):

- No cache contains windows from multiple incompatible model contracts, and a
  key is deterministic and independently verifiable from its manifest.
- A song can retain and display multiple model/profile entries simultaneously;
  switching models does not delete or reuse another entry.
- Normal lookup never substitutes a different model's cache, while an explicit
  completed-cache playback session can use its exact entry without activating
  or reinstalling the model.
- CPU/GPU sharing occurs only for an explicitly equivalent output profile, and
  output-affecting precision or profile changes create separate entries.
- Completed LiteRT FLAC playback does not require a live inference session;
  completed custom output remains playable after profile deletion, while
  partial output remains stale until the exact profile returns.
- Entry leases prevent cleanup, deletion, promotion, hydration, and playback
  races; cancellation, runtime failure, and GPU-to-CPU recreation cannot mark
  a window ready before its validated output is atomically written.
- Android clear-cache removes generated separation data without removing model
  weights, active-model state, or model metadata, and a clean install uses no
  old model/cache reader or migration marker.
- Restoring settings does not recreate a cache entry or a per-song blend
  value, and cache manifests never contain absolute portable file paths.

These criteria are accepted for the v2 contract, store, isolated engine, and
management surfaces. The historical limitation that the normal player graph
still used the legacy engine is closed by the Phase 6 cutover below; full-song
player quality and resource evidence remains a Phase 7 concern.

### Phase 6: Feature-gated LiteRT production cutover

Phase 6 switches the normal application graph from the legacy engine to the
contract-backed LiteRT route. It is an integration phase, not a second cache
implementation. ORT remains compiled through Phase 7 as a regression oracle,
but it is never an error fallback and is never constructed by a normal worker,
playback, cache, or management surface.

The cutover gate is selected at construction time and injected into the graph.
It is not a user preference, catalog state, or catch-based runtime fallback. A
cutover-enabled process constructs one v2 normal route; the legacy route may be
created only by an explicitly invoked oracle test entry point. During the
incremental 6A/6B work a test-only selector may keep a focused legacy fixture
available, but no production-shaped test may run both routes in one process.
At Phase 6 exit, both debug and release-like application graphs select the v2
normal route. The release-like build is a CI artifact only and must fail closed
when no release-qualified model is active; it must not select the legacy route.
There is no supported Phase 6 user release, and no model tier is promoted until
Phase 7 closes.

Phase 6 is the route-correctness gate. It proves that normal application code
uses the v2 facade and LiteRT, and that lifecycle operations preserve exact
model/cache identities. Full-song MediaSession, listening, thermal, peak-PSS,
and backend-promotion evidence remains in Phase 7; the Phase 6 graph smoke is
not presented as a substitute for that matrix.

#### Phase 6A: Runtime-neutral production facade

- [x] Define a production-facing facade, or a small set of capability
  interfaces behind one facade, consumed by the foreground coordinator,
  `PlaybackService`, and `PlayerViewModel`. It must cover run admission and
  results/progress, exact active-model resolution, cache status and playability,
  ready horizon, runtime-neutral diagnostics, per-song blend, FLAC
  promotion/hydration, cache touch,
  cleanup, deletion, pruning, and recovery.
- [x] Make the facade use v2 identities, manifests, leases, and contract
  snapshots directly. Do not adapt the v2 route through `MdxModelVariant`, the
  legacy `SourceSeparationEngine` DTOs, song-only cache IDs, or absolute paths.
- [x] Define explicit outcomes and localized UI mapping for no active model,
  a pending restored active-model reference, a missing model file, an invalid
  or unqualified profile, an unsupported ABI/runtime, a busy exact entry, and a
  completed cache whose model is no longer installed. None of these outcomes
  may select another model or start ORT.
- [x] Map v2 progress, preparation stages, cancellation, pause, failure, and
  completion to the existing player/notification states without leaking
  LiteRT, ORT, or legacy model-variant types through the UI boundary.
- [x] Make every run request carry the exact model/profile identity selected at
  admission. A process restart must either recover that same identity after
  validating its model and manifest, or terminate/requeue explicitly; it must
  never silently bind a pending request to the model currently active later.
- [x] Add JVM fakes and contract tests for the facade, including missing-model,
  invalid-profile, exact-cache, stale-partial, completed-read-only, and
  model-switch outcomes.

#### Phase 6B: Worker and scheduler cutover

- [x] Bind the normal foreground coordinator to the v2 facade through the
  construction-time cutover gate. Remove its ownership of
  `ReusableMdxInferenceSessionProvider`; session creation, backend recreation,
  and close must belong to the LiteRT runtime boundary.
- [x] Resolve the active model/profile when a run is admitted, then freeze it
  for that run. A model switch affects only work admitted afterward. A queued
  prefetch that has not been admitted must re-resolve the active identity or be
  canceled and re-enqueued; it must never append to an identity captured by an
  older queued request.
- [x] Preserve current scheduler behavior: current-song priority, next-song
  prefetch, pause/cancel, playback ready-window gating, progress reporting,
  automatic FLAC promotion, temporary cleanup, foreground-service lifetime,
  and wakelock ownership.
- [x] Ensure LiteRT GPU failure recreates the same exact run on LiteRT CPU under
  its lease, while CPU failure, invalid output, cancellation, model loss, or
  contract failure reaches a terminal state. No worker exception may invoke the
  legacy engine or create a second cache identity.
- [x] Prove that a cutover-enabled process never reads or writes the v1/legacy
  cache and v2 cache for the same operation, including scheduler startup,
  prefetch, pause/resume, process restart, and cleanup.
- [x] Add worker/scheduler tests for active-model changes, queued prefetch,
  process death, duplicate admission, cancellation, GPU-to-CPU recreation,
  foreground-service teardown, and exact-entry lease release.

#### Phase 6C: Playback and management cutover

- [x] Replace all legacy status, ready-horizon, touch, cleanup, direct
  separation, promotion, and blend calls in `PlaybackService` with the v2
  facade. Replace the corresponding cache, promotion, blend, deletion, prune,
  and status calls in `PlayerViewModel`.
- [x] Make ordinary playback lookup exact-active-identity only. Keep an
  explicitly requested completed-cache playback session bound to its manifest
  and output mapping, inference-free, and independent of the current active
  model. An already-started separated playback session remains bound to its
  exact entry until an explicit stop or track transition; a model switch must
  not splice outputs or silently restart inference.
- [x] Exercise the production-shaped lifecycle seams through facade, cache,
  scheduler, management-state, and four-device application-graph tests:
  ready horizon, blend changes, concurrent cleanup/deletion, FLAC hydration,
  active-model changes, process recovery, and GPU-to-CPU recreation. Repeat
  pause/resume, seeking, transitions, background playback, and audio handoff as
  full-song MediaSession cases in Phase 7 rather than treating graph tests as
  playback-quality evidence.
- [x] Add management state and application-graph tests for model/cache details,
  immutable profile revisions, separate Download/Use/Delete actions, pending
  restored state, and explicit completed-cache playback. Keep screenshot- and
  gesture-level Compose coverage with the Phase 7 device UI pass; the Phase 6
  route audit proves the exposed screens cannot call the old ONNX management
  backend.
- [x] Verify that foreground playback and management use the same v2 repository
  graph, so a busy read/run/delete race is decided by one exact-entry lease
  system rather than competing legacy and v2 locks.
- [x] Validate clean-install behavior for no active model, active-model deletion
  protection, manual model deletion/reinstall, cache retention across model
  switching, and Android clear-cache recovery in the cutover-enabled app.
- [x] Delete an unknown custom profile through the management state/repository
  path with both partial and completed entries present. Completed output must
  remain read-only playable; partial output must remain stale until the exact
  model and profile revision return.

#### Phase 6D: ORT isolation and route verification

- [x] Move ORT construction behind an explicitly named oracle module/qualifier
  and test-only entry point. The normal Koin/application graph, coordinator,
  playback service, view model, cache store, and model-management graph must
  not resolve `SourceSeparationEngine`, the old model repository, or
  `MdxModelVariant` when cutover is enabled.
- [x] Add dependency-graph and route-audit checks (static references plus
  construction tests) proving that a selected LiteRT model cannot instantiate
  ORT after setup, GPU, CPU, tensor, output-validation, cancellation, or model
  switching failures. Only the documented LiteRT GPU-to-CPU transition is
  allowed.
- [x] Keep 9482 only as an explicitly invoked tensor/audio oracle. Do not retain
  it as a catalog entry, selectable model, active-model reference, cache
  identity, or normal worker route.
- [x] Move the legacy window-decode experiment and ORT-specific traces behind a
  debug/oracle controller so `PlayerViewModel` can drop its legacy engine and
  model-repository dependencies. Hidden debug tools must not affect the normal
  application graph.
- [x] Fault-inject missing/altered model files, invalid contracts, unsupported
  ABIs, missing supplemental x86 runtime, GPU initialization failure, GPU
  execution failure, CPU failure, invalid output, cancellation, and process
  restart. Every case must produce a localized terminal/fallback state without
  invoking another inference engine.
- [x] Verify cutover-enabled debug and release-like APK graphs and native
  inventories. The x86 APK must contain exactly the pinned supplemental
  `libLiteRt.so`; ORT may remain in the Phase 6 development artifact only as an
  unreachable oracle dependency.

Acceptance criteria:

- A cutover-enabled clean install can download, explicitly select under the
  development gate, and use a contract-backed TFLite preset without any ONNX
  file or legacy cache path.
- The foreground worker, scheduler, `PlaybackService`, and `PlayerViewModel`
  all use the same runtime-neutral v2 facade and exact model/cache identity.
- Active-model changes, queued prefetch, process restart, cancellation,
  completed-cache playback, FLAC hydration, cleanup, and deletion preserve the
  v2 identity and lease rules through the production graph. Phase 7 repeats
  these cases as full-song real-player validation.
- No selected LiteRT model path can fall back to ORT. GPU failure may recreate
  the exact run on LiteRT CPU; all other failures terminate or use only the
  explicitly defined LiteRT behavior.
- ORT construction is confined to explicitly invoked oracle code and cannot be
  reached by normal worker, scheduler, cache, playback, or management surfaces.
- Phase 4 tier and activation policy prevents an unqualified model/profile from
  becoming active merely because its native library is present.
- Every ABI APK contains the expected LiteRT inventory, and the x86 APK
  contains exactly the pinned supplemental `libLiteRt.so`.

Phase 6 implementation record (2026-07-22):

- `4762767f` through `fff8e207` add explicit active-model failures, exact cache
  readiness, and the runtime-neutral production facade.
- `4e9ca43f` and `75b0fb65` bind admitted worker runs and playback sessions to
  immutable model/cache identities, including queued work, completed-cache
  playback, hydration, promotion, cleanup, and lease ownership.
- `9fe7967b`, `cf4ffd4d`, and `d84d7bab` expose only the preset/v2-cache
  management surfaces, remove the legacy player routes and dead cache UI, and
  remove the old engine/model repository from Koin and debug cache cleanup.
- `e764b2c9` removes every implicit ORT provider and confines ORT construction
  to `SourceSeparationOrtOracle`; generic separator APIs now require an
  explicitly supplied provider.
- `ee631e7e` verifies the selected model artifact against its SHA-256 and uses
  a path/size/mtime stamp to avoid rehashing an unchanged large model on every
  resolution. Missing or altered artifacts fail before inference.
- `fccc0592` adds static production-route auditing, a Koin construction test,
  and a missing-x86-runtime fault case. Existing LiteRT tests cover GPU setup,
  probe, invocation, output, cleanup, cancellation, CPU failure, unsupported
  targets, invalid contracts, and process/cache recovery.

Phase 6 validation record (2026-07-22):

- `testGithubDebugUnitTest`, `lintGithubDebug`, `assembleGithubDebug`,
  `assembleGithubRelease`, and `compileGithubDebugAndroidTestKotlin` passed.
- The GitHub debug and release-like universal APKs contain LiteRT for all four
  declared ABIs. The pure x86 inventory has one LiteRT entry,
  `lib/x86/libLiteRt.so`; its size is 7,482,132 bytes and its SHA-256 is
  `02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af`,
  matching the pinned supplemental-runtime manifest. ORT remains packaged only
  as the temporary Phase 6/7 development oracle dependency.
- `SourceSeparationProductionGraphTest` passed with one test, zero failures,
  and zero errors on Galaxy S10 (API 31/arm64), Galaxy S25 (API 35/arm64),
  API 26 x86, and API 37 x86_64. It resolves the v2 facade and confirms that
  Koin has no legacy engine or model-repository definitions.
- Phase 5's 12-second two-model device lifecycle evidence still covers actual
  LiteRT output, multiple exact cache entries, FLAC promotion, hydration,
  lease conflicts, and clear-cache recovery on the same four targets. Phase 7
  must repeat the relevant behavior through full-song playback before model
  promotion.

Phase 6 route/cutover exit gate (met):

- Debug and release-like normal graphs construct one v2 LiteRT route; no
  normal worker, player, cache, or management source can construct ORT.
- Worker admission, playback, cache management, model switching, and completed
  cache playback preserve exact v2 identities and share one lease system.
- Failure paths either use the documented one-way LiteRT GPU-to-CPU transition
  or terminate; no exception path selects the legacy engine.
- The legacy ONNX implementation remains source-visible only for the named
  regression oracle and Phase 8 deletion. It is not selectable, registered, or
  reachable from the production graph.

### Phase 7: Full-device validation and tier promotion

Phase 7 is a promotion-or-decline gate, not a second implementation phase. It
decides model and runtime tiers from full-song worker, playback, audio,
resource, and lifecycle evidence. A model that has only window parity remains a
candidate even if it is downloadable. The phase has five ordered gates. Do not
change the catalog tier while an earlier gate is open; backup interoperability
remains part of Phase 9 Beta readiness. Product behavior remains frozen except
for validation-only injection/observability, fixes found by the matrix, and the
evidence-driven release-graph/catalog decision in Phase 7E. A product-code fix
changes the app identity and reruns every affected row.

#### Phase 7 progress snapshot (2026-07-22)

The validation graph and preliminary device evidence are now real, but the
promotion gate is still open. The current reports use the prerelease catalog
artifact `uvr_mdxnet_3_9662`, contract `uvr_mdxnet_3_9662@2`, and LiteRT 2.1.5.
The runner now records the requested `LiteRtAuto` profile separately from the
concrete backend that completed the cache, including structured fallback stage
and reason.

Preliminary full-song results (historical `phase7-thresholds-v1` reports) are:

| Target | Profile | Backend | Full song | Peak PSS delta |
| --- | --- | --- | ---: | ---: |
| Galaxy S10 arm64 | `cpu-default-fp32-v1` | CPU | 228.2 s | 781.5 MiB |
| Galaxy S10 arm64 | `gpu-auto-fp32-v1` | GPU | 205.2 s | 411.2 MiB |
| Galaxy S25 arm64 | `cpu-default-fp32-v1` | CPU | 123.7 s | 748.0 MiB |
| Galaxy S25 arm64 | `gpu-auto-fp32-v1` | GPU | 37.1 s | 504.5 MiB |

The S25 GPU export compared with the desktop ORT reference at PCM16
quantization equivalence: exact frame counts, maximum one-LSB sample delta,
and maximum two-LSB join delta. S25 MediaSession playback and both-device
Auto lifecycle scenarios passed. Production-worker fault injection also
passed for setup, probe, and invocation-after-ready on both devices; each
report proves GPU cleanup before CPU creation and preserves the same cache
identity.

Runner v10 passed active-model changes during an admitted run and during
a completed-cache MediaSession lease. S10 passed both 9662-to-KARA and
KARA-to-9662; S25 passed 9662-to-KARA. The original run/playback identity was
retained and the subsequent model received a separate cache entry. Home after
the first ready window completed the full WAV on S10 and S25 under the same
service-owned run. Two-window next-song prefetch stopped incomplete and then
completed under the same key after transition on both devices. The prefetch
case uses different frozen current/next audio identities so content-addressed
cache reuse cannot produce a false pass.

Runner v11 retains those lifecycle stages and adds the frozen source-format
contract, structured decode evidence, and app-private fixture staging.

The v2 source-format corpus subsequently passed all nine routes on S25 arm64
Auto, S10 arm64 Auto, S10 armeabi-v7a CPU, and API 37 x86_64 CPU. It found and
fixed MP3 gapless overrun and API-dependent AAC edit-list handling; x86_64
FLAC safely uses an explicit full-song fallback because that extractor reports
`audio/raw`. See
[`validation/litert-phase7/source-format-results-2026-07-23.md`](validation/litert-phase7/source-format-results-2026-07-23.md).

These are not promotion results yet. The v2 threshold revision must be used to
rerun the affected rows after the final decision commit. S10 GPU audio export,
repeated warm/resource/thermal measurements, representative listening and UI
coverage, KARA full-song qualification, and the final catalog promotion matrix
remain open. Pure x86 retains ordinary playback but source separation now fails
closed before native allocation; x86_64 remains CPU evidence only until a
separate GPU qualification exists.

#### Phase 7A: Freeze the validation inputs and evidence format

- [x] Freeze the exact app commit, bundled catalog SHA-256, `bss-tflite`
  Release tag, artifact SHA-256, contract ID/schema, pipeline revision, and
  LiteRT runtime revision in every report. The current acquisition baseline is
  the published prerelease `v0.1.0-candidates.1`; it is not a stable model
  release and must not be silently replaced by a mutable branch asset.
- [ ] Freeze the digital fixtures and their hashes. Keep the existing 12-second
  Coast Town source and a synthetic mixture as parity/control fixtures, but add
  at least one representative full-length track for worker, playback, resource,
  and thermal evidence. Add a source-format corpus covering every production
  window-decode class and full-song fallback class, with the expected decode
  mode recorded for each file. Generate matching desktop references through
  `MusicSourceSeparation`; record source duration, sample rate, channel count,
  codec/container, redistribution status, and expected output stem semantics.
  A short fixture cannot satisfy a full-song gate; non-redistributable media
  and full reference outputs stay out of the app repository, with hashes and
  acquisition/reproduction instructions retained instead.
- [x] Freeze numerical and behavioral pass thresholds before running the
  promotion matrix: finite output, sample count and timeline drift, full-track
  SNR/error against desktop references, join discontinuity, ready-window and
  seek tolerance, cancellation latency, and resource limits. Changing a
  threshold creates a new evidence revision and reruns affected rows; it cannot
  retroactively turn an existing report into a pass.
- [x] Define and independently version one JSON report schema containing model
  identity, device/build fingerprint, Android API, process ABI, backend/profile/
  precision, CPU thread count, cold-session or warm-session run class, fixture
  hashes, first-ready time, full-song time, cancellation result, cache key, runtime
  diagnostics, idle and peak Java/native/graphics/PSS memory, and thermal/power
  observations. Record the app APK SHA-256, runner revision, and schema version
  in each report. Define `cold-session` as a new app process and inference session
  inside a clean-install scenario; do not imply that unprivileged tests have
  dropped the kernel page cache. A report without these identity fields is not
  promotion evidence.
- [x] Add a Phase 7 host runner and Android instrumentation suite that drives
  the production facade, foreground worker, MediaSession/player, and management
  graph. Backend/thread/failpoint selection must be construction-time,
  debug/test-only injection with no preference, backup key, or release-graph
  reference. Existing one-window and direct-engine tools remain narrow probes,
  not substitutes for this runner.
- [x] Separate acquisition and execution tests. First clear app data and test
  the pinned Release download, SHA-256 verification, install, metadata display,
  and explicit `Use`. Then use the same verified artifact for runtime tests.
  Warm performance repetitions may reuse the installed model and OS/runtime
  warm state inside one declared scenario, but inference timing must delete the
  exact completed cache or use a fresh source identity before each repetition.
  They must not be mixed with cold-install or download timing.
- [x] Build ABI splits and the AndroidTest APK in separate Gradle invocations.
  Requesting an AndroidTest task disables ABI splits in the current build
  configuration. The host runner must install the requested standalone split
  after the builds, install the test APK separately, invoke instrumentation
  directly, and assert the actual process ABI/bitness. Do not let a connected
  test task reinstall its universal app APK over an arm32 or x86 target. Run the
  native inventory verifier on the split directory and universal APK separately,
  including duplicate detection and the pinned supplemental x86 hash.

#### Phase 7B: Full-song correctness and production playback

- [x] Run the 9662 FP32 CPU baseline on Galaxy S10 and S25 arm64, then on the
  S10 `armeabi-v7a` split, API 26 pure x86, and API 37 x86_64. The CPU baseline
  is a test-only LiteRT CPU injection used for comparison; it is not a user
  setting and does not alter the production `Auto` policy.
- [ ] Exercise the real worker/player sequence for 9662 on every claimed CPU
  ABI: acquisition and explicit selection, full-song separation, partial
  ready-window playback, pause/resume, seek across ready and pending windows,
  background continuation, song transition and next-song prefetch,
  cancellation, process recreation, FLAC promotion/hydration, blend changes,
  and exact completed-cache playback. Capture digital output joins and
  timestamps on every target; perform representative listening and the full
  gesture-level UI pass on S10 and S25.
- [x] Run the frozen source-format corpus through the production worker and
  verify the expected local-window or full-song decode route, output duration,
  source fingerprint, join placement, and fallback reason. A format-specific
  decoder regression blocks stable promotion even when the canonical full-track
  fixture passes.
- [x] Switch models while a run and a separated playback session are active.
  Verify that admitted work and the active playback session retain their exact
  cache identity, that a later run creates a different entry, and that no
  output is spliced or silently re-inferred under the new model.
- [x] Run `gpu-auto-fp32-v1` through the same full-song flow on S10 and S25
  only when eligibility permits it. Exercise setup/probe/invocation failure
  before output and after ready windows have been published through the debug
  validation harness, then verify one-way recreation of the same run on LiteRT
  CPU without changing its render/cache identity. Do not add a force-GPU or
  force-CPU user preference to make this test possible.
- [ ] Treat a successful window comparison as necessary but insufficient:
  full-song stem joins, output scale/residual compensation, duration, cache
  append, and player timestamps must all pass before 9662 receives stable
  release maturity for that ABI/profile.

#### Phase 7C: Resource, thread, and thermal gates

- [ ] For each supported 9662 CPU target, collect one cold-session run and at
  least three warm-session repetitions. Record wall time, first-ready time,
  full-song time, idle/peak/delta PSS, Java heap, native heap, graphics
  allocation where available, cancellation latency, and thermal state. Keep
  model download and APK install space in separate measurements. Emulator
  timing and thermal data are regression diagnostics, not a substitute for
  physical-device performance or resource qualification.
- [ ] Compare the default thread formula
  `max(2, min(4, availableProcessors - 1))` with neighboring counts on S10 and
  S25. Change the default only when repeated full-song evidence improves the
  target metric without violating playback readiness, cancellation, memory, or
  thermal gates.
- [ ] Measure GPU and CPU separately on S10/S25. GPU eligibility must include
  library discovery, GPU-only compilation, memory decision, bounded probe, and
  fallback evidence; do not infer delegated operator coverage from LiteRT's
  public API. If GPU is not consistently better or less resource-intensive,
  bind the release graph directly to the LiteRT CPU provider and keep `Auto`
  internal; do not describe the current GPU-first `Auto` controller as
  CPU-first.
- [ ] Keep the existing resource gates: HQ4 remains a 64 MiB model with a
  256 MiB target and 384 MiB hard PSS-increase limit. A changed HQ4 artifact or
  runtime must pass the hard gate on S10 before any allocation beyond preflight
  is allowed; S25 success cannot waive an S10 failure.
- [ ] Record missing, altered, and wrong-architecture supplemental-runtime
  results as build or localized terminal failures. They must never trigger ORT,
  another model, or a second large allocation.
- [ ] Record the dual-runtime Phase 7 APK and installed-size inventory per ABI
  as the comparison baseline. It is not final size acceptance while ORT remains;
  Phase 8 owns the post-removal 10/16 MiB runtime gate.

#### Phase 7D: Experimental and download-only catalog validation

- [ ] Run KARA FP32 CPU full-song, playback, resource, cancellation, and
  representative listening checks on the ABIs for which it may be selectable.
  Its rejected GPU profiles remain rejected. It can become a warned CPU-only
  experimental model only with complete per-ABI evidence; otherwise keep it
  download-only or restrict activation to the qualified ABI set.
- [ ] Keep HQ4 download-only for the current artifact. Use compatibility and
  preflight tests to confirm rejection without model allocation, including the
  expanded x86 AVD; do not repeat the known-disqualified full-song allocation
  merely to fill a matrix.
- [ ] For every other published candidate, verify pinned download, contract and
  sidecar inspection, structural/TFLite smoke on a compatible target, and an
  explicit download-only, rejected, or unsupported state. Do not reconvert the
  model in this repository or grant activation from conversion success alone;
  conversion reproducibility belongs to `bss-tflite`.
- [ ] Keep target-stem-plus-residual candidates download-only until neutral
  stem labels and the generic playback/cache UI have passed their own full-song
  gate. Never expose them as vocals/instrumental based on filename inference.

#### Phase 7E: Promotion decision and catalog revision

- [ ] Generate a promotion matrix keyed by
  `(modelId, artifactSha256, contractId, contractSchemaVersion, abi, backend,
  profileId, precision)`. Each row must be `passed`, `rejected`,
  `unsupported`, or `not-tested`, with links to immutable reports. A failure
  for one backend or ABI must not erase evidence for another row.
- [ ] Promote 9662 FP32 to the sole stable recommended/default model only if
  its CPU rows pass on every ABI that the release claims to support. Promote
  `gpu-auto-fp32-v1` independently only if its S10 and S25 rows pass; otherwise
  bind the normal release route to LiteRT CPU and retain `Auto` as an internal
  validation path. FP32 evidence cannot promote FP16.
- [ ] Promote KARA only as a warned CPU-only experimental model after its own
  qualified-ABI rows pass. Keep HQ4 and all remaining candidates
  resource-gated or download-only according to their individual matrices.
- [ ] Before assigning stable maturity, publish an immutable non-prerelease
  `bss-tflite` Release containing the exact validated 9662 artifact, sidecar,
  full candidate manifest, and checksums. The Release may retain experimental
  and download-only assets, but their per-artifact tiers must remain explicit.
  Reusing the validated bytes needs only hash/URL verification; any artifact or
  contract hash change reopens every affected promotion row.
- [ ] Update the bundled catalog, release maturity, runtime qualifications,
  changelog, and evidence manifest in one reviewed commit. The app must not
  change a tier or activation policy before the corresponding report and
  catalog revision are present.
- [ ] Build and install the exact decision commit after its catalog and release
  graph change, then rerun clean-install acquisition, production-graph, exact
  active-model, worker/playback smoke, and native-inventory checks on every
  claimed ABI. A runtime, profile, artifact, or contract change reopens the
  affected full matrix rather than being covered by this final smoke.

Acceptance criteria:

- 9662 FP32 has complete full-song CPU evidence on every ABI claimed by the
  release, with no output-join, timestamp, cache-identity, lifecycle, memory,
  or cancellation regression. It becomes the only stable recommended/default
  preset after the Phase 7E review.
- A GPU profile is an independent qualification. GPU failure never blocks the
  CPU model, and an Auto result cannot be promoted without its own S10/S25
  reports and one-way fallback evidence.
- KARA is selectable only as an explicitly warned CPU-only experimental model
  on ABIs with complete evidence; otherwise it remains download-only.
- HQ4 remains download-only unless a new artifact/runtime passes its resource
  and fallback gates on S10. FP16, generic target-stem, and unreviewed models
  remain non-selectable.
- Every report is reproducible from immutable catalog/artifact/fixture hashes,
  and every promoted row is traceable to a report. Full-song performance data
  is not placed in backups.
- Any change to CPU threads, probe, GPU eligibility, runtime profile, or hard
  resource limits is justified by new S10/S25 evidence and a catalog revision.

### Phase 8: Retire ONNX Runtime

Begin Phase 8 only after Phase 7E has produced the reviewed promotion matrix,
the first selectable release catalog, and immutable desktop/device reports.
Archive the ORT comparison reports before deleting the oracle; the oracle is no
longer needed once every release-selectable row has equivalent LiteRT evidence.

- [ ] Freeze the Phase 7 promotion matrix, catalog revision, and desktop ORT
  references in validation artifacts. No Phase 8 code change may alter the
  evidence used for model promotion.
- [ ] Remove legacy ONNX acquisition URLs, import validation, loader metadata,
  and user-facing ONNX runtime text from the app. Preserve original ONNX source
  URLs and attribution where the TFLite contract needs provenance, but never
  expose them as executable model-download or import targets. Keep immutable
  validation reports outside the release app.
- [ ] Remove `SourceSeparationOrtOracle`, `onnxruntime.android`, and all ONNX
  native libraries after the final LiteRT route audit. Delete obsolete
  ONNX-only tests and diagnostics only after equivalent LiteRT checks are
  retained in the repository or validation artifacts.
- [ ] Remove the temporary legacy 9482 execution profile, old model repository,
  `MdxModelVariant` routing, legacy model manifest, and legacy cache discovery
  paths. The clean-install boundary means no compatibility reader or migration
  marker is required.
- [ ] Run a clean-install smoke, pinned TFLite acquisition, explicit model
  selection, full worker/playback flow, model switch, cancellation, cache
  clear, and APK/native-inventory audit after each removal stage.
- [ ] Build ABI splits and the universal APK in standalone invocations, verify
  the final LiteRT inventory and x86 hash, and measure final APK and installed
  runtime size against the 10/16 MiB LiteRT-runtime budget. Do not count model
  weights or separation cache in the runtime budget.

Acceptance criteria:

- Source and release packaging scans find no production ONNX Runtime dependency
  or model-loading path. Historical reports and explicitly marked source
  provenance may still mention ORT.
- Release APKs contain no `libonnxruntime*.so`.
- Every release-selectable model/backend/ABI combination passed its Phase 7
  tier gate; an untested combination cannot become active merely because its
  native library is present.
- Every ABI APK contains the expected LiteRT inventory, and the x86 APK
  contains exactly the pinned supplemental `libLiteRt.so`.
- A clean install can download, inspect, explicitly select, and use a TFLite
  preset without any ONNX file, legacy model directory, or legacy cache path.

### Phase 9: Beta readiness

- [ ] Complete model attribution and conversion guidance in the app and
  `bss-tflite` repository.
- [ ] Add all newly supported upstream language directories to the fork
  localization set, including `bqi` and `ta` introduced by the rebase.
- [ ] Review every LiteRT-specific string for terminology consistency.
- [ ] Build GitHub and F-Droid release variants only.
- [ ] Keep the Play Store variant out of Booming SS CI, release artifacts, and
  publication. Do not use a Play Store/AAB build as evidence for the GitHub or
  F-Droid product.
- [ ] Include the supplemental runtime source link, Release tag, checksum,
  LiteRT license, third-party notices, and provenance verification guidance in
  release documentation.
- [ ] Confirm whether the F-Droid build policy accepts the pinned vendored x86
  binary; if it requires a source build, reproduce the same pinned producer
  inputs in the F-Droid recipe rather than introducing a fallback runtime.
- [ ] Emit canonical backup JSON plus both filtered compatibility XML
  projections in every Beta backup, with tests enforcing canonical priority.
- [ ] Test backup format v1 and both settings schema v1 payloads with the
  restored active model installed, missing, and different from a currently
  valid active model. A missing restored model becomes a pending reference, a
  current valid model is not interrupted, and later installation still
  requires explicit `Use`.
- [ ] Confirm an unknown optional fork payload is skipped without blocking
  recognized common settings, playlists, lyrics, or artist images.
- [ ] Test Booming Music -> Booming SS and Booming SS -> Booming Music imports
  for shared settings, playlists, lyrics, and artist images, with fork-only
  settings ignored by the upstream app.
- [ ] Test a restored backup whose source device contains cache files and
  per-song blend data; neither the cache nor the blend may appear in the
  destination.
- [ ] Test legacy package-named `.bmgbak` imports and verify malformed or
  unsupported payloads do not partially overwrite running settings.
- [ ] Verify every Beta backup contains both filtered compatibility XML files,
  canonical JSON wins when present, and one setting is never applied twice by
  ZIP entry order.
- [ ] Publish the full candidate catalog with all source records, duplicate
  aliases, pinned artifacts, manifests, and reproducible checksums.
- [ ] Place only stable 9662 FP32 in the recommended/default section. Place
  KARA FP32 in the warned CPU-only experimental section only if Phase 7 admits
  it; otherwise keep it download-only. Keep HQ4 resource-gated download-only.
- [ ] Label every other entry from its actual contract, stem-UI, activation,
  runtime-profile, and maturity evidence. Do not describe a downloadable or
  window-tested artifact as stable.
- [ ] Publish a beta with pinned catalog assets and reproducible hashes.

## Verification Matrix

Every runtime or model change should run the narrowest applicable checks:

| Area | Required check |
| --- | --- |
| Catalog | Source provenance, normalized duplicate equivalence, aliases, reviewed contract, product tier, activation policy, release maturity, profile-aware evidence, and pinned asset |
| Acquisition | Download, verify, install, tier-specific `Use` gate, manual delete, and reinstall |
| Contract | Release schema v2, historical v1 rejection, sidecar/hash pairing, shape, dtype, layout, DSP, stem mapping, runtime-evidence exclusion, and cache-contract fingerprint |
| Conversion | Desktop LiteRT/TFLite output versus ORT reference |
| Evidence | Independently versioned report schema, immutable app/catalog/artifact/fixture identities, cold/warm classification, production worker/player runner, promotion matrix, and report links |
| Runtime | Runtime-neutral production facade, explicit ORT oracle through Phase 7, cutover graph audit, no ORT fallback, production worker/player instrumentation, NCHW/NHWC conversion, raw parity, output compensation/residual, CPU thread matrix, versioned GPU profiles, eligibility/probe, one-way LiteRT GPU-to-CPU fallback, close/recreate, and cancellation |
| Playback | Start, pause/resume, seek, song transition, blend update, exact-active cache lookup, explicit completed-cache playback, and no model-output splice during selection changes |
| Audio | Full-length source, digital output joins/timestamps, and representative listening for every model requesting selection; short-fixture or window parity alone is insufficient |
| Cache | Canonical identity/key determinism, manifest v2 and relative-path validation, multiple models per song, profile revisions, deleted custom profile, partial stale/resume, read-only completed playback, FLAC promotion, entry leases, crash consistency, delete/cleanup, and system clear-cache recovery |
| Persistence/Backup | Format/schema v1, key allowlists, pending active model, unknown fork payload, canonical/legacy priority, both package directions, and excluded model/cache/per-song data |
| Lifecycle | Activity recreation, process restart, background worker continuation |
| Device | Galaxy S10/S25 arm64 9662 CPU and eligible GPU evidence, CPU-only KARA promotion evidence, S10 armeabi-v7a CPU, official x86_64 CPU/runtime evidence without GPU promotion, API 26 pure x86 CPU for 9662/KARA, actual process-ABI evidence, and explicit HQ4 resource rejection |
| Resource budgets | Model/runtime install size, peak PSS, graphics/native memory, thermal behavior, and target/hard-limit decisions |
| Native supply chain | Pinned source/toolchain, Release hash, ELF/JNI audit, checksums, notices, and GitHub provenance |
| Packaging | Four ABI splits plus universal APK built separately from AndroidTest, one runtime per ABI, native inventory, and APK/install size |
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
7. runtime-neutral cache identity and manifest v2 contract;
8. cache root/store boundary, relative paths, and crash recovery;
9. model-aware entry lifecycle, exact-entry leases, cleanup, and playback;
10. cache/model details UI and immutable custom-profile revisions;
11. runtime-neutral production facade and construction-time cutover gate;
12. foreground worker and scheduler cutover;
13. playback, management, MediaSession, and gated-UI cutover;
14. ORT oracle isolation, dependency-graph audit, and fault injection;
15. full-device validation and model-tier promotion;
16. ONNX removal and packaging cleanup; and
17. beta backup interoperability, documentation, localization, and release
    validation.

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

Phase 1 adopted contract schema v1 with `contractSchemaVersion` independent
from the app and pipeline versions. Phase 4 replaced it for official release
use with schema v2 because v1 incorrectly embedded mutable runtime
qualification. Schema v2 preserves model/artifact, tensor, DSP, stem, source,
conversion, and pipeline facts, but ABI, backend, LiteRT version, precision,
device, and execution-profile evidence live only in catalog qualification
records. The clean-install boundary means no installed v1 migration is needed;
v1 remains an immutable development-history artifact.

A v2 sidecar is named `<model file name>.json`, so `model.tflite` uses
`model.tflite.json`. It carries the model SHA-256, tensor shape, dtype, layout,
`dimF`, DSP values, stem semantics, complement rule, contract schema, and
pipeline compatibility version. Pair by embedded SHA-256; never accept
filename adjacency as identity.

Validation must cover sidecar round trips, unknown fields, v1 official-release
rejection, unsupported future schemas, shape/contract disagreement, a correct
filename with the wrong model hash, and the absence of runtime qualification in
v2. Changing the schema requires an explicit schema revision; changing the app
version alone does not.

### Preset support tiers and promotion

Adopt one recommended/default target rather than treating the three first
contracts as equivalent release presets. The post-Phase 3 catalog keeps
contract review, product tier, activation policy, release maturity, and
profile-aware runtime evidence independent. Demoting an entry must not delete
its reviewed contract, and adding a converted artifact must not grant `Use`.

Use these initial decisions:

- 9662 FP32 is the only recommended/default candidate. Mark it stable only
  after Phase 7 full-song, playback, device, lifecycle, and resource acceptance.
- KARA FP32 may become an explicitly warned CPU-only experimental model after
  its Phase 7 full-song and listening gate. Its Phase 3 GPU profile is rejected
  and cannot be used by `Auto`.
- HQ4 retains its reviewed contract but remains resource-gated download-only.
  Reconsider it only for a materially changed implementation with a safe CPU
  fallback and new resource evidence.
- FP16 is rejected by the current numerical evidence and is not an official
  preset or user-facing execution choice.
- Every other converted candidate remains download-only until its own contract,
  stem UI, desktop parity, CPU, full-song, and resource gates pass. Promotion is
  individual and does not happen merely because a broad Release is published.

Runtime evidence must include the exact model hash, contract and pipeline,
LiteRT version, ABI, backend, profile ID, and precision. A failed profile stays
recorded even if a future profile succeeds. Tests must prove that a reviewed
download-only contract resolves for inspection while activation remains
blocked, and that a window-level CPU pass cannot create stable release maturity.

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

Installed metadata remains inspectable after import, and catalog metadata is
inspectable before download. Built-in contracts and installed sidecar
revisions are immutable in the app. An explicit sidecar replacement/rebind or
`Clone as custom profile` action creates a new identity. Editing a custom
profile likewise creates a new profile revision after validation; it never
mutates the revision referenced by an existing cache or running job. Keep old
revisions available as orphaned records while a cache references them, and
provide explicit details, export, and delete actions. Validation must exercise
each path, sidecar/hash mismatch, incomplete forms, structural failure,
profile revisioning, and import while another model is active.

### Cache identity and storage

Use a full SHA-256 of a canonical, versioned cache identity as the entry key.
The identity includes finalized audio identity, model ID, exact artifact hash,
contract ID/schema and fingerprint, DSP/pipeline identity, and a
`renderProfileId` for output-affecting execution choices. A CPU and GPU runtime
profile may share an entry only after rendered-output equivalence is explicitly
validated and both map to that render profile; backend and timing diagnostics
alone do not split the cache, while precision or another change to the
rendering contract does. A song ID or file path alone is never a cache identity.

The contract fingerprint covers tensor, DSP, compensation, stem, and pipeline
semantics, not display/provenance/runtime evidence. Song ID, URI, and source
diagnostics may feed a rebuildable candidate index, but exact source
fingerprint agreement is required before playback use, resume, or append. A
replacement file under the same locator must resolve to a different entry.

Compute the existing encoded-sample fingerprint in preflight before publishing
reusable ready windows. Retain a run-scoped staging/atomic-promotion path only
if measured startup cost requires it. Manifests use independent schema v2,
contain the complete immutable contract snapshot, and contain only
entry-relative paths with containment checks. The preferred root is
`externalCacheDir/source-separation`, with `cacheDir` fallback; a run does not
span roots. v1/old-layout readers and migration are out of scope under the
clean-install boundary.

Use process-local `Read`, `RunWrite`, and `Exclusive` exact-entry leases.
Playback holds shared `Read`; one inference or hydration owner holds
`RunWrite`, which may coexist with readers of already published output; FLAC
promotion, deletion, and cleanup require `Exclusive`. Atomic state transitions
and startup orphan cleanup are required; completed output is playable without
the model, while partial output is stale until its exact model/profile returns.
Phase 5 must prove these rules with deterministic key, corruption,
process-restart, concurrent-operation, and clear-cache tests before Phase 6
routes the normal worker through LiteRT.

### Caches after model or profile deletion

Allow a completed cache to remain read-only playable when its model or custom
profile has been deleted, provided its manifest contains the complete contract
snapshot, output mapping, playback metadata, and valid rendered WAV/FLAC
files. Show "model not installed" or "contract unverified" and do not present
the cache as an official preset.

Normal playback lookup remains bound to the exact active model/profile and
does not choose another model's completed cache automatically. The cache UI may
start a session-scoped `Play cached result` action for one validated completed
entry; that action does not activate a model, permit inference, or affect later
scheduler work. A playback session already using an entry keeps its lease when
the active model changes and never splices outputs from two cache identities.

Keep a partial cache stale after profile deletion. It can resume only when the
exact model hash and matching profile are installed again; no reconstructed or
guessed contract may continue inference. Phase 5 and Phase 7 tests must cover
both outcomes.

### GPU eligibility and CPU threads

Use `Auto` as the development graph's only accelerated mode, but keep it
internal until Phase 7 decides whether the release graph should retain it. The
current controller is GPU-first whenever eligibility passes; it is not a
CPU-first policy. If its Phase 7 profile fails promotion, bind the release graph
directly to LiteRT CPU and keep `Auto` debug/internal rather than adding a
misleading user mode. The pinned LiteRT 2.1.5 API can report available
accelerators and can create a GPU-only compiled model; it cannot report
delegated operator coverage or the OpenCL/OpenGL backend chosen by `AUTOMATIC`.
Eligibility therefore combines ABI/library preflight, exact runtime records, a
known-good CPU fallback, accelerator discovery, successful GPU-only
compilation, memory gates, and a bounded deterministic probe.

Version GPU options independently from the model contract. Phase 3 tested
explicit `AUTOMATIC + FP32` and separate `AUTOMATIC + FP16` profiles. Only 9662
`gpu-auto-fp32-v1` proceeds to Phase 7; KARA's FP32 profile and all tested FP16
profiles remain rejected. A future option or implementation change receives a
new profile ID and new evidence rather than overwriting those results. Forced
OpenCL/OpenGL runs are diagnostic profiles, not silent substitutes. Do not
expose precision, forced backend, probe controls, or `GPU only` as initial user
settings.

After a recoverable setup, probe, invocation, or output-validation failure,
discard GPU output, close GPU completely, recreate the same contract on CPU,
and retry the same input once. The controller remains on CPU afterward.
Cancellation does not trigger fallback; out-of-memory or unconfirmed cleanup
does not trigger a second large allocation. KARA's possible release path is
CPU-only. HQ4 remains internally GPU-exploratory and product download-only
because its current arm64 CPU path cannot satisfy the fallback resource gate.
Arm32 and x86 skip GPU, while x86_64 library or emulator evidence alone cannot
establish a production GPU record.

Use this initial CPU thread formula:

```text
max(2, min(4, availableProcessors - 1))
```

The one-way fallback, cancellation, cleanup, and diagnostic rules are fixed.
The production device eligibility, memory threshold, validity probe, selected
GPU profile, and thread count are provisional. Tune them only after repeated
S10 and S25 full-song comparisons of wall time, peak memory, thermal behavior,
cancellation, and playback readiness. Performance statistics remain runtime
data and are never backed up.

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
