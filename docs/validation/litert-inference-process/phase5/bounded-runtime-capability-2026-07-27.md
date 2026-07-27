# Phase 5F bounded runtime capability smoke

Status: passed on the available arm64, x86_64, and x86 device matrix

## Scope

`MdxLiteRtBoundedGpuCapabilityDeviceTest` verifies the final packaged runtime
at the application boundary:

- an arm64 process can query the native capability and receives the exact
  `2.1.5-bss.2` / `gpu-opencl-bounded-fp32-v1` identity with
  `kernelBatchSize=1` and `commandQueueWindowSize=1`;
- the arm64 app class loader can resolve `libLiteRtClGlAccelerator.so`; and
- a CPU-only ABI returns `CpuOnlyAbi` before invoking the injected capability
  provider. The provider is deliberately fatal if called, and its observed
  call count must remain zero.

The tested AAR SHA-256 is
`88cd2f7eaf1443d1c570085b1c24f239db87eb24c788a590adf5158e17443d0e`,
published by
[`runtime-v2.1.5-bss.2`](https://github.com/WluhWluh/bss-litert-android/releases/tag/runtime-v2.1.5-bss.2).

## Results

| Device | API | Process ABI | Applicable assertion | Result |
| --- | ---: | --- | --- | --- |
| Samsung S25 (`SM-S9310`) | 35 | arm64-v8a | Exact capability and accelerator discovery | Pass |
| Samsung S10 (`SM-G9730`) | 31 | arm64-v8a | Exact capability and accelerator discovery | Pass |
| API 37 emulator | 37 | x86_64 | Reject before capability lookup | Pass |
| API 26 emulator | 26 | x86 | Reject before capability lookup | Pass |
| Low-memory API 29 emulator | 29 | x86 | Reject before capability lookup | Pass |

Each row ran the targeted `connectedGithubDebugAndroidTest` class. The
opposite-ABI test method was skipped by an explicit assumption; the applicable
method completed with no failures.

## Limits

This smoke proves packaging, native build identity, accelerator discovery, and
the CPU-only early gate. It does not compile or invoke 9662, exercise GPU to
CPU fallback, measure full-song throughput, or measure foreground UI
responsiveness. Those Phase 5F checks remain separate and open until their
device runs are completed with the same pinned artifact.

No source decode threshold, window strategy, join policy, or MP3 fallback
behavior changed for this validation.
