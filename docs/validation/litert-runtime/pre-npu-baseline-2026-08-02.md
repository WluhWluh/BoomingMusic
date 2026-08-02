# Pre-NPU CPU/GPU Baseline

Date: 2026-08-02

This record covers the post-ONNX-removal GitHub graph. It is a validation
record, not a release approval. The application has not shipped, so old ONNX,
model, cache, and preference data are outside the supported data contract.

## Release Graph

The current GitHub Release build was assembled with:

```text
:app:assembleGithubRelease
```

APK sizes:

| Artifact | Bytes |
| --- | ---: |
| arm64-v8a split | 16,936,252 |
| armeabi-v7a split | 16,390,746 |
| x86_64 split | 17,030,699 |
| x86 split | 16,892,770 |
| universal | 24,699,104 |

Every split contains only the existing player native libraries:

```text
libandroidx.graphics.path.so
libffmpegJNI.so
libtaglib.so
```

The APKs contain no `libonnxruntime*.so`, `libLiteRt.so`,
`libLiteRtClGlAccelerator.so`, `libBssOcl.so`, QNN, or vendor NPU library.
The remaining LiteRT matches are license notices, catalog JSON, and build
metadata, which are required for the downloadable-runtime contract.

The current compressed runtime assets are:

| Component | ABI | Download bytes |
| --- | --- | ---: |
| LiteRT CPU core | arm64-v8a | 2,220,465 |
| LiteRT CPU core | armeabi-v7a | 1,843,957 |
| LiteRT CPU core | x86_64 | 2,938,161 |
| LiteRT CPU core | x86 | 2,886,192 |
| bounded GPU | arm64-v8a | 1,251,488 |

## Automated Coverage

- Complete `testGithubDebugUnitTest`: passed, including the ONNX route audit,
  delivery-provider doubles, Quick Setup planner/executor, runtime stores,
  cache ownership, and failed replacement preservation.
- `SourceSeparationProductionGraphTest`: passed on S10 and S25.
- `SourceSeparationInferenceProcessDeviceTest`: passed 3/3 on S10 and 3/3
  on S25.
- `SourceSeparationDownloadedRuntimeDeviceTest`: passed 2/2 on each of the
  four ABI rows. S10 was forced through the 32-bit `armeabi-v7a` application
  and instrumentation process; S25 used `arm64-v8a`; the API 26 emulator used
  pure `x86`; and the API 37 emulator used `x86_64`. Each row loaded its
  downloaded CPU runtime from the app-owned absolute path. The non-arm64 rows
  correctly reported no installed GPU component.
- The same test passed 2/2 on S25 with the downloaded bounded GPU component.
  It loaded the verified `gpu-opencl-bounded-fp32-v1` profile with
  `kernelBatchSize=1` and `commandQueueWindowSize=1`.
- `SourceSeparationPhase7DeviceTest#validateDeviceEvidenceIdentity`: passed
  for the installed KARA contract on S10 and installed 9662 contract on S25.

These tests preserve the existing application data. They do not claim a clean
install, full-song audio, listening, thermal, power, or FrameTimeline result.

## Current Device Inventory

| Device | Installed model evidence | CPU | bounded GPU |
| --- | --- | --- | --- |
| S10 | KARA | loaded | loaded and manifest-verified |
| S25 | 9662 and KARA | loaded | loaded and manifest-verified |

The new APK was installed with `adb install -r`; model files, runtime files,
preferences, and generated caches were not cleared or migrated.

## Support Boundary

| ABI/API row | Current status |
| --- | --- |
| arm64-v8a on S10/S25 | downloaded CPU loader smoke passed; S10/S25 GPU loading evidence exists; full separation qualification pending |
| armeabi-v7a on S10/API 31 | downloaded CPU loader smoke passed in a forced 32-bit process; GPU unavailable; full separation qualification pending |
| x86_64 on API 37 emulator | downloaded CPU loader smoke passed; GPU unavailable; full separation qualification pending |
| pure x86 on API 26 emulator | downloaded CPU loader smoke passed; GPU unavailable; full separation qualification pending |

The four rows use the same runtime contract and were selected by process ABI,
not merely by the device's preferred 64-bit ABI. The S10 arm32 row therefore
proves that a 32-bit LiteRT CPU library can be installed and loaded on that
device. It does not promote arm32 to a supported full-song product tier.

## Remaining Gate Items

NPU implementation remains gated until the following product qualification is
run on a disposable clean-install state or a reproducible isolated test copy:

- clean-install Quick Setup with CPU, model, GPU opt-out, and retry paths;
- an API 29 CPU-loader row, if a suitable emulator is retained for the matrix;
- final downloaded 9662 CPU/GPU full-song numerical and listening checks;
- update, removal, reinstall, cancellation, and cache-clear UI flows;
- foreground FrameTimeline, thermal, power, PSS, and playback-underrun data;
- final UI/accessibility checks for Quick Setup and Runtime Management.

No NPU capability is enabled by this record. It provides the reproducible
CPU/GPU runtime-loading baseline against which the later AOT/JIT experiments
must be compared.
