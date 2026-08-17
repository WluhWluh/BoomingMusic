# LiteRT 2.2 MDX and HTDemucs Migration Roadmap

Status: active formal migration plan. This document is the implementation
authority for moving the current downloadable-runtime product path to exact
LiteRT `2.2.0-bss.2` and integrating the qualified native MDX and CPU
HTDemucs pipelines. It does not authorize QNN, HTDemucs GPU, stable model-tier
promotion, or a generic LiteRT 2.2.x runtime.

Created: 2026-08-16
Updated: 2026-08-17

## Frozen Branch and Source Boundary

The formal migration branch and base are:

| Item | Frozen identity |
| --- | --- |
| Branch | `migration/litert-2.2-mdx-demucs` |
| Base branch | `experiment/downloadable-litert-core` |
| Base commit | `c2b2d24fd15b4fed4a950fe3ae8fabb6b0e04d9e` |
| Donor branch | `experiment/litert-2.2-mdx-demucs-migration` |
| Donor tip reviewed for this plan | `4e43e23985740c09ddfc2a5268532dc5395afa58` |
| Upstream LiteRT tag | `v2.2.0` |
| Upstream LiteRT commit | `145c7523ff08d5e57ab5c582141775eea47da9c7` |

At roadmap creation, the donor's remote branch ends at `9f7a05b4`; commits
`4df22efe`, `5e1754f4`, and `4e43e239` exist only on the clean local donor
branch. Keep that worktree and branch intact until those commits have either
been transplanted or published as provenance.

The branch starts from the latest playback-correctness baseline, not from the
older donor base `c2e80234`. The donor branch is an implementation and evidence
source. It must not be merged wholesale, rebased over this branch, or treated
as if its old-base validation automatically validates the final integrated
tree.

This roadmap owns only the migration work described here. Existing documents
retain their established ownership:

- `litert-runtime-setup-roadmap.md` owns runtime-management, Quick Setup, and
  delivery-provider product contracts;
- `source-separation-multistem-contract-playback-roadmap.md` owns stem,
  cache, and playback data-plane contracts; and
- `litert-multi-preset-roadmap.md` owns model catalog, compatibility, and
  promotion policy.

Those roadmaps are reconciled only after the final branch-specific integration
gate. Historical phase descriptions must not be rewritten during code
transplantation.

## Migration Decision

Proceed with all three items in one controlled development sequence:

1. migrate downloadable CPU and bounded GPU delivery to exact
   `2.2.0-bss.2`;
2. integrate the native packed-real, managed-buffer, two-slot MDX path; and
3. integrate the direct managed-I/O, native DSP/OLA, CPU-only HTDemucs path.

"One sequence" does not mean one commit or one acceptance test. Runtime-only,
MDX, and HTDemucs changes must remain independently attributable. A failure in
one stage must be revertible without discarding the preceding accepted stage.

QNN remains a later project. LiteRT 2.2's QAIRT 2.47 alignment is a reason to
establish this runtime base before QNN work; it is not evidence that any QNN
device/model tuple is supported.

## New Baseline State

The base contains three playback fixes added after the donor branch split:

| Commit | Required behavior |
| --- | --- |
| `fa4df714` | Partial stem reads stop at the durable ready frontier. |
| `ae0ff892` | Rapid song changes reject stale asynchronous cache resolutions. |
| `c2b2d24f` | Stem EOF does not incorrectly replace valid transport output. |

These commits modify playback, cache admission, the runtime facade, and route
audits. The migration must preserve their behavior and tests. In particular,
no donor version of `SourceSeparationRuntimeFacade`, a production route audit,
or a cache/playback collaborator may overwrite the latest-base implementation.

The donor branch adds eleven commits beyond its `c2e80234` base:

| Donor commit | Formal-migration disposition |
| --- | --- |
| `7487bd7c` | Transplant as the runtime/catalog/loader foundation. |
| `064da05b` | Transplant the four-ABI runtime smoke build binding. |
| `b69b8989` | Transplant the explicit x86 qualification policy. |
| `d3670ed3` | Transplant bounded GPU dispatch/wait verification. |
| `9345d960` | Transplant production runtime-store installation gates. |
| `71f53249` | Transplant native packed-real MDX DSP foundations. |
| `3df22cb8` | Transplant product MDX DSP selection and cache identity. |
| `9f7a05b4` | Transplant the LiteRT 2.2 managed MDX bridge. |
| `4df22efe` | Use as a donor only; split by runtime identity, MDX, HTDemucs, and tests. |
| `5e1754f4` | Transplant typed multi-stem hard termination after HTDemucs integration. |
| `4e43e239` | Do not cherry-pick; retain as historical evidence and rewrite branch-specific conclusions later. |

The broad `4df22efe` commit combines 51 files and multiple ownership domains.
It is not an acceptable formal migration commit. Its content must be applied
without committing, reviewed against the new base, and divided into focused
commits described below.

## Frozen Runtime and Delivery Contract

The accepted producer release is:

```text
downloadable-runtime-v2.2.0-bss.2-exp.1
runtime artifact: 2.2.0-bss.2
component contract: bss-litert-downloadable-runtime-v3
component manifest schema: 2
```

The release inputs are pinned as follows:

| Artifact | SHA-256 |
| --- | --- |
| `litert-api-2.2.0-bss.2-downloadable-loader.aar` | `88a939aa5f3a65ff89bd90eed4b3af30b2a8866bedbbd3838761b143d2ccb387` |
| `litert-android-2.2.0-bss.2.aar` | `35b55a0ef9a6d28e56271a9bc3b6b6cc8a84b16732b17b34b2a6b51ee7be3124` |

Each CPU component contains two ordered libraries:

```text
1. libLiteRt.so
2. liblitert_jni.so
```

The app must verify role, filename, SHA-256, SONAME, runtime dependency,
Android minimum API, machine/class, load order, and 16 KiB load alignment at
installation. Normal inference startup may trust the already installed
manifest and file metadata; it must not re-hash the complete runtime or model
before every run.

The following policies are frozen:

- all CPU, GPU, MDX, and HTDemucs factories fail closed unless the loaded
  installation is exact `2.2.0-bss.2` from release
  `2.2.0-bss.2-exp.1`;
- semver-compatible or otherwise arbitrary `2.2.x` libraries are rejected;
- a process loads one immutable runtime generation, and a runtime update takes
  effect only in a new separation process;
- the runtime-free APK contains no `libLiteRt.so`, `liblitert_jni.so`, GPU
  accelerator, QNN, or vendor-NPU payload;
- bounded GPU remains arm64-only `gpu-opencl-bounded-fp32-v1`, kernel batch
  one, queue window one, with equal positive dispatch and event-wait counts;
- the new LiteRT mixed FP16 mode is a future profile and is not enabled by
  this migration;
- API 26 x86 uses the CPU-only JVM `TensorBuffer` fallback as an experimental
  GitHub product tier; the app does not apply an ABI-wide feature block, while
  native-managed x86 remains rejected;
- x86_64 may use the native-managed MDX path only while its exact device gate
  remains passing;
- HTDemucs is CPU-only on arm64-v8a and armeabi-v7a; and
- no backward-compatibility shim is added for unreleased 2.1.5 runtime
  manifests or obsolete render identities. Development installs may be
  cleaned and reinstalled through Runtime Management or Quick Setup.

## Frozen x86 Product and Qualification Policy (2026-08-17)

The GitHub product publishes and verifies an x86 split, offers the x86 CPU
runtime through the normal runtime catalog, and permits explicit user
selection and execution of experimental models. Runtime Management, Model
Management, Quick Setup, the separation panel, and the runtime facade must not
hide or reject source separation solely because the process ABI is x86.
Existing catalog `Unsupported` records remain model/runtime evidence for
stable or recommended promotion; they are not an ABI-wide activation ban.

The product execution contract is narrow and explicit:

- CPU only, through the JVM `TensorBuffer` fallback in the dedicated bound
  separation process;
- no native-managed x86, GPU, NPU, AOT, or resident-session claim;
- the historical `X86_PROCESS_VALIDATION` switch remains only for old
  resident-session fault matrices and is not required by users, CI, or a
  release APK; and
- high-memory models may still fail because one 32-bit process must obtain a
  large contiguous native mapping for XNNPACK.

The reproducible API 26 x86 validation environment is frozen at 4 GiB guest
RAM and a 384 MiB ART maximum heap. Controlled 576 MiB and 768 MiB runs failed
at the first XNNPACK invocation, while 1024 MiB prevented the 32-bit zygote
from mapping its address space. Two 384 MiB runs passed the frozen ORT oracle;
an additional-session replacement attempt still exposed address
fragmentation. The local `Medium_Phone` AVD therefore keeps both
`vm.heapSize=384` and a persistent API 26 `/data/local.prop` override, while
GitHub CI and release jobs declare `heap-size: 384M`. This is a qualification
environment constraint, not an app-side RAM check or user-facing restriction.

At the current branch state, the standard JVM fallback produced 97.5986 dB
SNR and maximum absolute error `5.9530e-5` against the frozen ORT reference.
Two fresh product-process runs also downloaded the real x86 runtime and 9662
model, published a completed two-stem cache, opened it through the playback
repository, and cleaned it successfully. CI and release publication must run
`MdxLiteRtX86GithubProductDeviceTest` through those production providers.

Future GitHub Release Notes need only the concise limitation: "32-bit x86 and
high-memory models may fail depending on device memory layout."

## Pipeline and Product Invariants

The migration must preserve these existing contracts:

1. LiteRT is loaded at the separation-process entry point before application
   code references LiteRT classes.
2. Runtime, model, contract, backend profile, and render-pipeline identity are
   explicit and independently versioned.
3. MDX remains a two-stem residual pipeline whose user-visible stem labels
   come from the model contract.
4. HTDemucs remains an ordered four- or six-stem direct-output pipeline. It is
   not represented by MDX conditionals or a synthetic residual stem.
5. A cache is playable only when its model and render identity match the
   active model. Switching models cancels or supersedes the old task, preserves
   its valid cache, and resolves the new model independently.
6. Partial playback pauses only when it actually reaches unavailable data.
   Gain changes and seeks within a ready region do not create a new readiness
   gate.
7. Audio-thread code performs no model inference, FLAC decoding, blocking file
   I/O, future waits, or unbounded allocation.
8. Runtime or model removal uses the same typed cancellation path as manual
   task cancellation before deleting active resources.
9. The current song's explicitly requested cache deletion is honored even
   when that cache is active; auto-started stem playback is disabled as part
   of that operation.
10. Existing indexed FLAC playback, seek prefetch, transport epoch, and
    complete-session installation behavior remain unchanged by inference
    pipeline migration.

## Phase 0: Freeze and Validate the New Base

Status: complete.

- [x] Fetch the current origin and freeze
  `experiment/downloadable-litert-core` at `c2b2d24f`.
- [x] Create `migration/litert-2.2-mdx-demucs` in an isolated worktree.
- [x] Confirm the source worktree is clean and the donor branch is clean.
- [x] Audit the three new playback commits and the donor's eleven migration
  commits.
- [x] Normalize source text in the production route audit so its LF snippets
  are portable to a Windows CRLF checkout. The first local run exposed three
  line-ending-only failures; after the test-only fix, all 741 tests passed.
- [x] Record a passing baseline `:app:testGithubDebugUnitTest` run: 741 tests,
  zero failures, zero skipped.
- [x] Require the base commit's `Android CI` workflow to complete
  successfully before implementation transplantation begins. Workflow run
  `31991881037` passed lint, unit tests, build, and supply-chain checks.
- [x] Record the baseline APK inventory and verify that the current GitHub APK
  remains runtime-free. The same CI run passed `verify_litert_native.py` and
  `verify_litert_apks.py` before publishing all four split APKs and the
  universal APK.

**Phase 0 exit:** the new branch contains only the test portability correction
and this roadmap, its base is reproducible, and current playback/runtime
behavior has a passing host and CI baseline.

Recommended commit:

```text
test(separation): normalize route audit source lines
docs(migration): plan LiteRT 2.2 native pipeline integration
```

## Phase 1: Migrate Runtime Delivery to Exact LiteRT 2.2

Status: complete on `bd96eb55`.

### Phase 1A: API, catalogs, and loader

- Transplant `7487bd7c` onto the new base.
- Materialize the checksum-pinned classes-only API AAR without resolving the
  stock `litert` or `litert-api` AARs transitively.
- Replace CPU and GPU catalog v1 assets with catalog v2 entries for exact
  `2.2.0-bss.2` components.
- Upgrade CPU runtime installation from one library to role-aware ordered
  core/JNI libraries.
- Preserve no-backup storage, atomic installation, immutable generation,
  explicit process lease, and install-time verification.
- Preserve the latest-base `SourceSeparationRuntimeFacade` behavior rather
  than accepting the donor file wholesale.
- Keep runtime management and Quick Setup text capability-driven; no UI text
  should hard-code implementation details that are already available from the
  catalog.

### Phase 1B: Four-ABI and bounded GPU gates

- Transplant `064da05b`, `b69b8989`, `d3670ed3`, and `9345d960` in order.
- Verify arm64-v8a, armeabi-v7a, x86_64, and x86 CPU ZIP installation through
  `SourceSeparationRuntimeStore`, not a test-only copied library layout.
- Verify core then JNI absolute-path loading in a fresh process.
- Verify API 26 x86 selects the JVM path and never advertises native-managed
  support.
- Verify the exact released arm64 bounded GPU ZIP through the production GPU
  store and capability handshake.
- Keep `dispatchCount == eventWaitCount > 0` as a mandatory product result.
- Verify corrupted, truncated, wrong-ABI, wrong-SONAME, wrong-load-order, and
  mismatched CPU/GPU generation payloads fail before inference.

### Phase 1C: Runtime-only attribution

The donor historical report at commit `4e43e239`, path
`docs/validation/litert-2.2-migration/2026-08-16-migration-gates.md`, records
three alternating S25 2.1.5/2.2 runtime-only pairs. All ordered six-stem hashes
were equal and the 2.2 median differed by 3.68%. Preserve that report as prior
evidence.

The formal branch must additionally run one clean S25 runtime-only product
smoke after conflict resolution. A runtime-only failure must be fixed before
any native DSP commit is introduced.

**Phase 1 exit:** runtime installation, loading, restart, cleanup, four-ABI
policy, and bounded GPU evidence pass on the formal branch while APK inventory
remains runtime-free.

### Phase 1 Evidence

- The checksum-pinned classes-only API, all four CPU components, and the
  arm64 bounded GPU component pass `verify_litert_native.py`; the API AAR
  identity is
  `88a939aa5f3a65ff89bd90eed4b3af30b2a8866bedbbd3838761b143d2ccb387`.
- `SourceSeparationRuntimeStore` installed the released CPU and GPU ZIPs on
  the S25. A fresh process loaded `libLiteRt.so` followed by
  `liblitert_jni.so`, and the exact bounded-GPU capability handshake passed.
- The released CPU ZIP and ordered dual-library load passed on S10 arm32,
  API 37 x86_64, and API 26 x86. API 26 x86 is the experimental JVM fallback
  product tier rather than native-managed support.
- `:app:testGithubDebugUnitTest` passed all 748 tests. GitHub debug app and
  instrumentation APK assembly passed, and `verify_litert_apks.py` confirmed
  that all four ABI splits and the universal APK contain no LiteRT runtime.
- S25 run `formal-phase1-runtime-only-s25-cpu-r2` exercised the production
  runtime store and released model contract on exact `2.2.0-bss.2`: setup
  59 ms, first inference 2106 ms, reused inference 1686 ms, ORT SNR
  97.764523 dB, maximum absolute error `5.9247e-5`, and byte-identical reused
  output. The app and test APK SHA-256 values were respectively
  `42ccc942c4bfcd29b3562e20508d735d6bbdca4fab87802d409239719ffe4b17`
  and `475561549fab6e1c9000ab9ac2ce9fee019464977b99c399b85bec45930781b5`.

Phase 2 may begin only from this accepted Phase 1 tip. Later MDX or HTDemucs
qualification must not be cited as a substitute for these runtime-only gates.

Recommended commits:

```text
feat(separation): migrate downloadable runtime to LiteRT 2.2
test(separation): qualify LiteRT 2.2 runtime delivery
```

## Phase 2: Integrate the Native MDX Pipeline

Status: complete through Phase 2D at `a25198b5`.

Current evidence as of 2026-08-17:

- the native packed-real DSP, managed dual-slot bridge, exact 2.2 runtime
  identity, staged waveform execution, one-way GPU fallback, and experimental
  user-attempt policy are present as focused commits;
- at `b95fe281`, all 13 frozen MDX shapes passed on S25 CPU, S25 bounded GPU,
  and S10 CPU. Every S25 GPU row recorded equal positive dispatch and event
  wait counts, and the S10 CPU matrix completed with bounded post-close
  accounting;
- direct exact-runtime smokes pass on S10 armeabi-v7a, API 37 x86_64, and API
  26 x86. The later x86 product gate additionally exercises GitHub delivery,
  bound-process JVM inference, completed cache publication, and playback
  opening at the frozen 384 MiB ART heap;
- at `15bbe455`, the 273.8-second 9662 bounded-GPU product run reached its
  first playback-ready window in 3,355 ms and completed in 32,619 ms. Its
  completed indexed-FLAC cache then passed a 60-second MediaSession soak with
  five seeks, 108 ms seek p95/max, no underrun, and no unexpected pause;
- at the same revision, the HQ4 CPU product run reached first-ready in
  8,393 ms and completed in 197,117 ms with thermal peak 0. Its completed
  FLAC cache switched to 9662 during playback, released the HQ4 lease in
  251 ms, and retained the exact HQ4 cache; and
- at `a25198b5`, the full-song bound-remote invocation failpoint completed a
  real GPU-to-CPU replay. One GPU session ran six invocations, the injected
  `GpuInvocation` failure closed it before one CPU session was created, and
  the CPU run published playable FLAC in 77,577 ms. First-ready was 3,897 ms,
  thermal peak was 0, remote PSS peaked at 891,102,208 bytes, and summed PSS
  peaked at 1,006,516,224 bytes. The diagnostic admission fix is part of the
  exact tested revision, not a report-only override.

### Phase 2A: Packed-real DSP

- Transplant `71f53249` and `3df22cb8` while preserving the latest cache and
  playback code.
- Keep one native packed-real implementation; do not add a product selector
  for old/new DSP implementations.
- Build pocketfft and the JNI bridge for all four application ABIs.
- Preserve MDX compensation, residual construction, window overlap, sample
  count, and contract-provided stem semantics.
- Change the render profile/cache identity so old Java-DSP caches are not
  silently treated as output of the new implementation.
- Do not add per-run model or runtime hashing back to the separation hot path.

### Phase 2B: Managed buffers and two-slot staging

- Transplant `9f7a05b4`.
- Keep one `CompiledModel`, two managed input buffers, two managed output
  buffers, and one native DSP plan per session.
- Allow preparation of window N+1 while invocation N runs, but never access a
  single DSP workspace concurrently.
- Use absolute-path `dlopen`/`dlsym` only after the exact runtime installation
  has been verified and loaded.
- Require the native symbols to resolve from the verified absolute core path.
- Keep invocation non-interruptible within LiteRT; cancellation is observed at
  the established window/session boundaries.
- Preserve the JVM path when XNNPACK flags are explicitly required and for
  the API 26 x86 experimental product tier.

### Phase 2C: Product routing, identity, and fallback

Split the MDX-owned parts of `4df22efe` into focused commits:

- add `LiteRt220RuntimeIdentity` and require exact CPU/GPU release identity;
- add staged waveform execution and lookahead without changing user-visible
  task ordering;
- retain one-way GPU-to-CPU fallback after GPU setup or invocation failure;
- close the GPU session before creating CPU fallback;
- preserve the original waveform until fallback acceptance is complete;
- never reinterpret CPU fallback as successful GPU delegation; and
- keep compatibility policy, user-attempt policy, and catalog maturity
  separate from runtime availability.

### Phase 2D: MDX qualification

- Split product-shape tests and host tooling out of `4df22efe`.
- Run one released model for each of the 13 unique MDX tensor/DSP shapes on:
  - S25 CPU;
  - S25 bounded GPU; and
  - S10 CPU.
- Require finite output, exact two-slot repeats, correct tensor names/shapes,
  model-specific numerical floors, and bounded memory after close.
- On GPU require positive equal dispatch/wait evidence for every accepted
  shape.
- Run direct managed CPU smokes on arm64-v8a, armeabi-v7a, and x86_64.
- Run API 26 x86 through the user-accessible JVM fallback with the frozen
  384 MiB qualification heap and no app-side memory gate.
- Run full-song product paths for 9662 and representative long/HQ shapes,
  including cache publication, playback admission, cancel, restart, model
  switch, and automatic GPU-to-CPU fallback.

Completion evidence is retained in the ignored local report sets
`formal-15bbe455` and `formal-a25198b5`. Phase 5 must still rerun the required
aggregate gates at one final clean candidate revision; that consolidation is
not a reason to keep Phase 2D open.

**Phase 2 exit:** all 13 MDX shapes and product lifecycle gates pass on exact
2.2, with no native-managed x86 claim and no regression of current playback
demand behavior.

Recommended commits:

```text
feat(separation): add native packed-real MDX DSP
feat(separation): add LiteRT 2.2 managed MDX sessions
feat(separation): route staged MDX execution through exact runtime
test(separation): qualify LiteRT 2.2 MDX product shapes
```

## Phase 3: Integrate the CPU HTDemucs Pipeline

Status: Phase 3A through Phase 3C are implemented; Phase 3D qualification is
in progress.

Current evidence as of 2026-08-17:

- the direct managed-spectrum/waveform path, native packed pocketfft DSP,
  bounded parallel iSTFT, overlap-add, planar PCM16 publication, ordered
  N-stem cache integration, and typed cooperative/hard terminal control are
  present on the formal branch;
- at `b95fe281`, canonical executable fixtures passed for official 4-stem,
  official 6-stem, and guitar-ft on S25. All three also completed the real
  installed-Release product/cache/FLAC path on the 30-second YOASOBI fixture;
- S10 arm64 passed the official 6-stem executable fixture, the guitar-ft
  30-second product path, cancel/restart, 6-to-4 supersession, and typed hard
  termination. S10 armeabi-v7a passed exact Release installation for all
  three candidates and 30-second official 4/6 product execution; and
- these short representative-content runs do not satisfy the explicit
  full-song wording below. Still required are current-candidate full-song
  product runs for all three models on S25, official 6-stem full-song product
  execution on S10 arm64, and the final sustained resource/thermal record.

### Phase 3A: Direct native pipeline

Split the HTDemucs-owned implementation from `4df22efe`:

- direct STFT into LiteRT managed spectrum input;
- direct managed waveform input;
- direct managed frequency and waveform outputs;
- native packed pocketfft forward/inverse processing;
- parallel bounded iSTFT workers;
- restricted native overlap-add for the frozen 25% overlap contract;
- planar PCM16 conversion, using arm64 NEON and arm32 scalar paths; and
- complete native/session cleanup on success, failure, cancel, and close.

Only the frozen official 4-stem, official 6-stem, and guitar-ft 6-stem
contracts are admitted. Tensor count, names, shapes, stem order, window size,
stride, overlap, sample rate, and model artifact identity must all be checked
before session creation.

The direct path must fail closed on a runtime identity mismatch. HTDemucs does
not receive GPU, bounded GPU, QNN, AOT, or generic NPU capability in this
phase.

### Phase 3B: Product range, cache, and publication

- Integrate the direct session through `HtdemucsRangeRunner` and the existing
  ordered N-stem cache contract.
- Preserve playback-priority scheduling, provisional leading-overlap rules,
  exact segment publication, final WAV assembly, indexed FLAC promotion, and
  temporary WAV cleanup.
- Retain valid partial cache when a task is canceled or superseded.
- Ensure switching 6 -> 4 -> 6 stems cannot publish a stale stem set or reuse
  a cache under the wrong model identity.
- Treat the roughly 79.7 dB strict frequency-to-waveform projection as
  `host-fixture-generation-only` and
  `requiredForDeviceAdmission=false`.
- Keep finite output, energy-aware per-stem checks, final PCM16 checks, and
  ordered output identity as Android admission requirements.

### Phase 3C: Bounded cancellation and supersession

Transplant `5e1754f4` only after the direct product path is in place:

- use one typed manual/policy cancellation path;
- request cooperative cancellation first;
- after the production two-second grace period, hard-terminate an adopted
  non-responsive multi-stem execution process;
- recover foreground ownership and process lease deterministically;
- record `PreviousOwnerDied` rather than guessing success;
- move the cache to a durable canceled state while preserving valid partial
  output; and
- allow a replacement model/task to start only after terminal ownership is
  resolved.

Default S10 cancel and active-model supersession should remain below five
seconds in the qualification fixture. A longer non-interruptible inference
must take the typed hard-termination path rather than blocking UI state for an
entire model window.

### Phase 3D: HTDemucs qualification

- Run canonical fixtures for official 4-stem, official 6-stem, and guitar-ft
  on S25.
- Run all three downloaded Release artifacts through a real full-song product
  path on S25.
- Run at least guitar-ft and official 6-stem on S10 arm64.
- Run official 4/6 process-lifecycle smokes in an armeabi-v7a process on S10.
- Cover cancel/restart, 6-to-4 supersession, process death, background/foreground,
  cache hit, FLAC promotion, active deletion, and terminal process recycling.
- Record window time, full-song time, PSS, native heap, ART allocation/GC,
  thermal state, FLAC promotion, and cleanup bytes.

**Phase 3 exit:** all three HTDemucs models are selectable CPU-only
experimental entries on the formal branch, publish ordered playable caches,
and have bounded cancellation/supersession behavior.

Recommended commits:

```text
feat(separation): add direct LiteRT 2.2 HTDemucs pipeline
feat(separation): integrate native HTDemucs product execution
fix(separation): bound multi-stem terminal control
test(separation): qualify CPU HTDemucs lifecycle
```

## Phase 4: Reconcile the Latest Playback Baseline

Status: implementation reconciliation and targeted regression coverage are
substantially complete; final UI/device closeout remains open.

At `b95fe281`, S25 device gates passed unready-window seek retargeting,
completed 6-to-4 cache switching, active-cache UI deletion, and remote
multi-stem process death recovery. The corresponding S10 playback wait/seek
gate also passed, while host tests cover ready-frontier generations, stale
resolution rejection, gain-only updates, EOF, FLAC promotion, and exact-model
cache selection. Before marking this phase complete, rerun the high-level
playback smoke on the final candidate and exercise real 2-, 4-, and 6-stem
sessions through both the separation panel and lyrics-overlay controls.

This phase is mandatory even if earlier cherry-picks apply without textual
conflicts. It validates the semantic overlap introduced by the new base.

- Review every migration change to `SourceSeparationRuntimeFacade` against
  `fa4df714..c2b2d24f` and retain both playback-demand and native-pipeline
  behavior.
- Re-run the production route audit with no exceptions added merely to make
  the migration pass.
- Verify partial MDX and HTDemucs playback stops exactly at the durable ready
  frontier and resumes only under the existing demand policy.
- Verify rapid song, model, and cache-generation changes cannot install stale
  asynchronous resolutions.
- Verify valid transport output remains active at stem EOF and does not enter
  a pause/play or original/stem fallback loop.
- Verify gain changes do not trigger cache readiness checks or rebuild a
  separation session.
- Verify seeks within ready data remain immediate; seeks into unavailable data
  create one bounded wait generation from the new target.
- Verify complete WAV-to-FLAC promotion does not force an in-playback source
  switch or expose stale partial-cache status.
- Verify current-song cache deletion cancels through the shared path, deletes
  the selected model cache, updates Current Song state, and disables
  auto-started stem playback.
- Verify model switching during and after separation cannot play a cache from
  the previous model.
- Exercise 2-, 4-, and 6-stem sessions through the source-separation panel and
  lyrics-overlay controls without changing the established UI contract.

**Phase 4 exit:** the final inference implementation coexists with all
post-donor playback fixes and passes their host and S25 regression suites.

Recommended commit:

```text
fix(separation): reconcile LiteRT 2.2 with playback demand state
```

## Phase 5: Formal Integration Qualification

Status: in progress.

The `49efc109` checkpoint has a clean 780-test host suite, passing GitHub-debug
lint, a successful minified GitHub Release build, verified runtime-free
four-ABI and universal APKs with the product DSP library in every ABI, and a
passing API 26 x86 GitHub product gate after a no-override cold AVD restart.
The later `15bbe455` and `a25198b5` S25 full-song MDX, playback, model-switch,
and real GPU-to-CPU fallback runs close the Phase 2 device gaps. These are
valid incremental evidence, not the Phase 5 exit: the remaining HTDemucs
full-song runs, S10/S25 resource consolidation, final UI, exact-candidate
report, and pushed CI gates below still apply.

### Phase 5A: Host and packaging gates

Run from a clean worktree at the exact candidate revision:

```text
:app:testGithubDebugUnitTest
:app:lintGithubDebug
:app:assembleGithubDebug
:app:assembleGithubDebugAndroidTest
```

Also require:

- all four JNI ABIs compile;
- GitHub APK inventory contains no LiteRT runtime/provider native library;
- classes-only AAR SHA-256 is exact;
- catalog, notices, licenses, and source lock are complete;
- no stock LiteRT Maven artifact is resolved;
- runtime/model Release URLs and SHA-256 values are exact;
- release-like route audit selects LiteRT only and contains no ORT fallback;
  and
- ignored raw audio, runtime ZIPs, APKs, and device reports remain untracked.

### Phase 5B: Device matrix

| Target | Required formal-branch coverage |
| --- | --- |
| S25 arm64 | CPU/GPU runtime install, MDX 13-shape matrix, bounded GPU evidence, 9662 full song, three HTDemucs models, playback/cache regression, process death, cancel/supersession, UI smoke. |
| S10 arm64 | CPU/GPU runtime install, representative MDX shapes, official 6-stem and guitar-ft, long-session memory/thermal, cancel/supersession, hard termination. |
| S10 armeabi-v7a | CPU runtime, MDX managed smoke, official 4/6-stem lifecycle, process recycle; no GPU claim. |
| API 37 x86_64 AVD | CPU runtime/store and native-managed MDX smoke. |
| API 26 x86 AVD | Real GitHub runtime/model download plus bound-process JVM MDX fallback and completed-cache gate at 4 GiB RAM / 384 MiB ART heap; native-managed remains rejected and the app applies no ABI-wide block. |

All device tests must install runtime and model assets through the same
providers/stores used by the product. Test-only bundled native libraries or
models cannot satisfy a formal gate.

### Phase 5C: Evidence and roadmap reconciliation

- Preserve the donor report as historical migration-readiness evidence with
  its old app commits and APK hashes intact.
- Add a new report under `docs/validation/litert-2.2-migration/` that records
  the formal branch commit, source cleanliness, APK/test APK hashes, runtime
  component hashes, model/contract hashes, device identities, commands,
  numerical results, lifecycle results, and retained raw-report locations.
- Compare the transplanted native/runtime files against their donor versions
  and explain every intentional difference caused by the new base or commit
  split.
- Update the three owning roadmaps only after all formal gates pass.
- Do not mark a historical phase incomplete merely because this migration
  introduces a newer implementation.

**Phase 5 exit:** host, packaging, four-ABI, S25/S10, playback, lifecycle, and
evidence gates all pass at one exact clean candidate revision.

Recommended commits:

```text
test(separation): qualify formal LiteRT 2.2 integration
docs(separation): record LiteRT 2.2 migration evidence
docs(separation): reconcile runtime and model roadmaps
```

## Phase 6: Migration Closeout

Status: not started. Phase 6 remains blocked on the open Phase 3D, Phase 4,
and Phase 5 gates above.

- Confirm the branch contains no generated binaries, raw test audio, runtime
  downloads, device output, or relay payloads.
- Confirm every phase commit is independently reviewable and no donor merge
  commit is present.
- Confirm the final diff retains the three latest-base playback commits and
  their tests.
- Re-run the complete GitHub CI workflow on the pushed candidate.
- Mark this roadmap complete only after the exact pushed commit passes.
- Preserve `experiment/litert-2.2-mdx-demucs-migration` as experimental
  provenance until the formal migration is accepted; do not force-update or
  delete it during this work.

**Migration exit:** `migration/litert-2.2-mdx-demucs` is the reviewed exact
2.2 CPU/bounded-GPU runtime, native MDX, and CPU HTDemucs product candidate.
No required implementation or validation work remains within this scope.

## Phase 7: QNN Handoff, Not QNN Implementation

Only after Phase 6 may a separate QNN branch be created from the accepted
formal migration tip. That later plan must:

- freeze a new downloadable provider/component contract for QAIRT 2.47,
  compiler/dispatch plugins, QNN runtime libraries, and HTP architecture;
- start with one qualified MDX model, normally 9662, rather than HTDemucs;
- keep QNN JIT and AOT identities and results separate;
- select devices by exact SoC/HTP tuple rather than marketing model name;
- require non-empty compiled IR, verified HTP execution, no undisclosed CPU
  fallback, numerical comparison, lifecycle, thermal, and listening evidence;
- retain prior V69/V75, VTCM, and silent-compile failures as negative evidence;
  and
- avoid changing CPU/GPU behavior while introducing NPU support.

LiteRT 2.2 QAIRT changes do not reopen or weaken any migration gate in this
document.

## Validation Acceptance Summary

| Area | Acceptance |
| --- | --- |
| Runtime identity | Exact `2.2.0-bss.2` and release `2.2.0-bss.2-exp.1`; arbitrary 2.2.x rejected. |
| APK | Runtime-free for every ABI; classes-only loader API only. |
| CPU delivery | Four ABI components install and load core then JNI from verified absolute paths. |
| GPU delivery | arm64 bounded FP32 profile only; positive equal dispatch/wait counts. |
| MDX | 13 shapes, finite output, exact slot repeat, profile thresholds, product lifecycle, correct cache identity. |
| HTDemucs | Frozen 4/6/guitar contracts, finite energy-aware stems, ordered PCM/cache publication, CPU-only. |
| x86 | User-accessible experimental GitHub CPU tier through the bound-process JVM fallback; API 26 CI uses 4 GiB RAM / 384 MiB ART heap; no native-managed, GPU, NPU, or stable-support claim. |
| Cancellation | Cooperative first, typed hard termination after grace, durable partial cache and ownership recovery. |
| Playback | Ready-frontier, rapid-transition, EOF, seek, model-switch, FLAC, and active-delete regressions pass. |
| Efficiency | No repeated full hashes, no Java tensor churn on accepted ARM native paths, bounded memory/GC and recorded thermal state. |
| Evidence | Exact clean revision and all executable/data hashes recorded; raw artifacts ignored. |

## Commit and Conflict Rules

1. Never merge the donor branch into the formal branch.
2. Cherry-pick the narrow donor commits in the phase order above, resolving
   conflicts in favor of the latest behavioral contract rather than either
   file version wholesale.
3. Apply `4df22efe` without committing and divide it by ownership. If a split
   leaves an intermediate tree uncompilable, combine only the minimum coupled
   implementation files and keep qualification tests separate.
4. Do not cherry-pick `4e43e239`; create branch-specific evidence and roadmap
   reconciliation after rerunning formal gates.
5. Keep at most one phase in progress. Complete its focused tests before
   starting the next phase.
6. Do not amend or force-push the donor branch. Do not rewrite unrelated
   playback, UI, translation, or release history.
7. Any product code fix made during device qualification invalidates the
   candidate identity and requires the affected gate to rerun.
8. A performance result never overrides a correctness, identity, lifecycle,
   or delegation-evidence failure.

## Known Risks

- The direct C bridges freeze LiteRT 2.2 public ABI layouts and use dynamic
  symbol lookup. Exact runtime identity is therefore a hard safety boundary,
  not a catalog display detail.
- The donor's broad qualification commit crosses runtime, MDX, HTDemucs,
  policy, process, UI, and tests. Careless cherry-picking would erase the
  value of staged attribution.
- The latest playback fixes overlap the runtime facade and route audits. A
  textually clean transplant can still create a semantic regression.
- HTDemucs inference is non-interruptible inside one LiteRT invocation. The
  typed process-level hard-termination path is required for bounded user
  cancellation on slow or throttled devices.
- x86 can consume very high memory and about 500-600 MiB of native XNNPACK
  buffers. The 384 MiB ART heap leaves a viable contiguous mapping on the
  frozen AVD, but different device layouts and high-memory models may still
  fail. Preserve user access and report the failure; do not convert this risk
  into an ABI-wide app gate.
- Runtime-only and DSP performance runs are sensitive to device temperature
  and run order. Use alternating cold pairs before attributing a regression or
  improvement to LiteRT 2.2.
- Existing development installs may contain 2.1.5 manifests and caches. Since
  the app is unreleased, explicit cleanup is preferable to carrying permanent
  compatibility code into the product.
