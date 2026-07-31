# LiteRT internal validation

The downloadable-runtime experiment validates LiteRT CPU inference from an
app-owned runtime. Phase 2 of the older process roadmap validated the original
packaged runtime; Phase 3 extends the same
AndroidTest-only runner with versioned GPU profiles and one-way Auto fallback.
The runner is not reachable from release UI, model acquisition, workers,
playback, settings, or production source-separation caches.

## Inputs

Use the canonical artifacts and NCHW fixtures from
[`bss-tflite`](https://github.com/WluhWluh/bss-tflite). Generate frozen ORT
references from the matching original ONNX hashes with
[`generate_ort_tensor_reference.py`](https://github.com/WluhWluh/MusicSourceSeparation/blob/main/tools/generate_ort_tensor_reference.py).
Do not add UVR weights or full tensor fixtures to this repository.

The runner verifies the downloaded CPU component manifest, ABI, library size
and SHA-256 before creating LiteRT. It also verifies the model contract,
filename, byte size and SHA-256, plus host-provided hashes for the input and
ORT reference. Host-staged files are read only from the app-private
`files/litert-validation-staging/<run-id>/` directory. The host first pushes
each file to a temporary shell-owned directory and then copies it with
`run-as`, so Android 37 and earlier releases expose the same app-owned files
to instrumentation. Staged inputs are removed after each run. Reports are
written below the internal cache root at
`cache/litert-validation/<run-id>/report.json`.

The validation script automatically downloads the pinned ABI-specific CPU ZIP
from the experimental
[`bss-litert-android`](https://github.com/WluhWluh/bss-litert-android/releases/tag/downloadable-runtime-v2.1.5-bss.2-exp.2)
Release, checks its ZIP and manifest hashes, and installs `libLiteRt.so` and
`manifest.json` under
`no_backup/source-separation/runtimes/cpu/<abi>/current/`. Use
`-SkipRuntimeInstall` only when that exact app-private component has already
been provisioned by another test harness. This is test provisioning; it does
not represent the production Runtime Management implementation.

## Run a CPU case

Build and install the ABI-specific app and AndroidTest APK, stage the files,
run parity, and pull the report with:

```powershell
tools/run_litert_cpu_validation.ps1 `
  -Serial <adb-serial> `
  -ProcessAbi arm64-v8a `
  -ModelId uvr_mdxnet_3_9662 `
  -ModelPath <bss-tflite-artifact> `
  -InputPath <nchw-input-bin> `
  -ReferencePath <ort-output-bin>
```

## Run GPU parity

Use the same staged artifact and frozen tensors with an explicit profile:

```powershell
tools/run_litert_cpu_validation.ps1 `
  -Serial <adb-serial> `
  -ProcessAbi arm64-v8a `
  -Backend gpu `
  -GpuProfileId gpu-auto-fp32-v1 `
  -ModelId uvr_mdxnet_3_9662 `
  -ModelPath <bss-tflite-artifact> `
  -InputPath <nchw-input-bin> `
  -ReferencePath <ort-output-bin>
```

`gpu-auto-fp32-v1` enforces the frozen Phase 2 parity floor.
`gpu-auto-fp16-v1` records separate numerical results without borrowing the
FP32 pass. The runner records accelerator discovery, APK inventory,
`libLiteRtClGlAccelerator.so` mappings after the first and reused invocation,
requested profile, setup/reuse timing, and native/graphics/aggregate memory
snapshots at setup, first invocation, reuse, and close. Because Android can map
an uncompressed native library directly from `base.apk` without retaining the
entry name in `/proc/self/maps`, the runner resolves each APK-backed mapping by
its ZIP data offset. A GPU parity run fails if it cannot prove that the
accelerator entry remained mapped after both invocations. LiteRT 2.1.5 has no
supported per-operator placement query, and the report states that limitation.

For a CPU-only ABI or an ABI with no exact GPU compatibility record, combine
`-Backend gpu` with `-PreflightOnly`. The rejecting allocator proves no native
GPU model or tensor buffer was created.

## Run real Auto fallback

The connected failpoint path creates a real GPU session, then verifies that it
is closed before a real CPU session recomputes the same input:

```powershell
tools/run_litert_cpu_validation.ps1 `
  -Serial <adb-serial> `
  -ProcessAbi arm64-v8a `
  -Backend auto-fallback `
  -GpuProfileId gpu-auto-fp32-v1 `
  -GpuFailpoint invocation `
  -ModelId uvr_mdxnet_3_9662 `
  -ModelPath <bss-tflite-artifact> `
  -InputPath <nchw-input-bin> `
  -ReferencePath <ort-output-bin>
```

Available failpoints are `setup`, `probe`, `invocation`, and `output-read`.
The report includes the ordered native-session events, typed fallback stage,
accepted output backend, and CPU parity result. Cancellation, out-of-memory,
cleanup failure, and CPU failure remain deterministic host fault-injection
tests because they must not be induced on a physical device.

Use `-TestInFlightCancellation` on selected long-running cases. Use
`-SecondaryModelId` and `-SecondaryModelPath` to verify native session
replacement after the first session lease is released. For a contract target
that must fail before native allocation, use `-PreflightOnly` without staging
a model. `-ProcessorCountOverride` is reserved for internal CPU thread-policy
experiments and does not create an app setting or backup field.

Use `-AllowUnsupportedResourceProbe` only to measure an explicitly unsupported
ABI/model pair after changing the test environment's resource envelope. The
AndroidTest process records the original status and evidence, then treats that
single CPU record as internal-only for the run. This switch does not change
the bundled contract, production compatibility policy, or the default
preflight test that proves native allocation is blocked.

Every report records the app commit, catalog and contract identities, model
and fixture hashes, numerical metrics, process ABI and bitness, APK LiteRT
inventory, loaded runtime/accelerator mappings, device identity, timings,
memory, session reuse, optional replacement, and cancellation behavior.
