# LiteRT CPU validation

Phase 2 validates real UVR models through an AndroidTest-only runner. The
runner is not reachable from release UI, model acquisition, workers, playback,
settings, or production source-separation caches.

## Inputs

Use the canonical artifacts and NCHW fixtures from
[`bss-tflite`](https://github.com/WluhWluh/bss-tflite). Generate frozen ORT
references from the matching original ONNX hashes with
[`generate_ort_tensor_reference.py`](https://github.com/WluhWluh/MusicSourceSeparation/blob/main/tools/generate_ort_tensor_reference.py).
Do not add UVR weights or full tensor fixtures to this repository.

The runner verifies the bundled contract, model filename, byte size and
SHA-256 before creating LiteRT. It also verifies host-provided hashes for the
input and ORT reference. Host-staged files are read only from the app-private
`files/litert-validation-staging/<run-id>/` directory. The host first pushes
each file to a temporary shell-owned directory and then copies it with
`run-as`, so Android 37 and earlier releases expose the same app-owned files
to instrumentation. Staged inputs are removed after each run. Reports are
written below the internal cache root at
`cache/litert-validation/<run-id>/report.json`.

## Run one case

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
inventory, loaded `libLiteRt` mappings, device identity, timings, memory,
session reuse, optional replacement, and cancellation behavior.
