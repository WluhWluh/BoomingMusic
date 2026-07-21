# LiteRT GPU Phase 3 validation results

Phase 3 was completed on 2026-07-21 with the app-packaged LiteRT 2.1.5
runtime. The production worker and playback path still select ONNX Runtime;
LiteRT GPU and Auto remain reachable only through the isolated AndroidTest
runner.

The complete reports and their checksums are stored under
[`validation/litert-gpu-phase3/`](validation/litert-gpu-phase3/). The report
set contains 27 GPU/Auto/preflight runs and one CPU control run used to
interpret the KARA synthetic-input result.

## Identities

- GPU session profiles and Auto fallback: Booming SS commits `de0f36f7` and
  `82d69256`.
- Final validation runner: Booming SS commit `a0836c59`.
- Every archived report uses app commit
  `a0836c59aa0afcac2d7aefb7b97ffd60967b7dca`.
- Matrix catalog: `bss-tflite` commit `cb6a4d3`, SHA-256
  `ded5070bcac3194cb62e83aca91b3e2d62427ec203fcb2d9ebe4dbd7d70b7d59`.
- Runtime: LiteRT 2.1.5, with `gpu-auto-fp32-v1` and
  `gpu-auto-fp16-v1` evaluated separately.

The tested TFLite artifact hashes were:

| Model | SHA-256 |
| --- | --- |
| 9662 | `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378` |
| KARA | `4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64` |
| HQ4 | `5f091562bd0297ff2223015a219d12cc1136a9d54df975680ff4eb239629c742` |

## Evidence boundary

LiteRT 2.1.5 exposes accelerator discovery and explicit GPU-only
`CompiledModel` creation, but no supported API for per-operator placement or
the selected OpenCL/OpenGL implementation. The reports therefore prove only
the strongest available facts:

- The APK contains `libLiteRtClGlAccelerator.so` for arm64.
- `Environment.getAvailableAccelerators()` reported `GPU`.
- The model was created with the explicit versioned GPU profile and returned
  valid, repeatable output.
- The accelerator library remained mapped after both the first and reused GPU
  calls.

Android directly maps uncompressed native libraries from `base.apk`, so a
mapping line need not include the library entry name. The runner resolves the
APK ZIP data offset for every LiteRT entry and matches that range against
`/proc/self/maps`; each arm64 GPU parity report found three accelerator mapping
segments after both calls. This proves the accelerator library was loaded, not
which individual model operations executed on it.

## FP32 matrix

PSS delta is the peak of setup, first inference, reused inference, and active
session snapshots minus the report's initial process PSS. It is an
in-process validation delta, not an ordinary-playback baseline. All successful
cases reused one session and returned bit-identical first and reused outputs.

| Device | Model / fixture | Result | SNR dB | Max error | First / reuse ms | Peak PSS delta MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| S10 | 9662 / Coast Town | pass | 97.067 | 0.00006825 | 2,402 / 2,372 | 415.3 |
| S10 | 9662 / synthetic | pass | 93.833 | 0.00006688 | 2,528 / 2,482 | 415.2 |
| S25 | 9662 / Coast Town | pass | 97.067 | 0.00006825 | 430 / 338 | 477.1 |
| S25 | 9662 / synthetic | pass | 93.833 | 0.00006688 | 419 / 343 | 478.4 |
| S10 | KARA / Coast Town | pass | 109.351 | 0.00001812 | 2,418 / 2,377 | 408.1 |
| S10 | KARA / synthetic | rejected | 108.484 | 0.00001212 | 2,422 / 2,376 | 411.0 |
| S25 | KARA / Coast Town | pass | 109.351 | 0.00001812 | 437 / 338 | 477.3 |
| S25 | KARA / synthetic | rejected twice | 108.484 | 0.00001212 | 438 / 348 | 476.6 |
| S10 | HQ4 / synthetic | exploratory | 93.346 | 0.00008577 | 4,191 / 4,162 | 782.2 |
| S25 | HQ4 / synthetic | exploratory | 93.346 | 0.00008577 | 909 / 818 | 821.3 |

9662 FP32 passed its frozen raw-output floors on both fixtures and both
devices, so it is the only Phase 7 GPU full-song candidate. Its validation PSS
still needs full-song, thermal, cancellation, and playback-readiness review.

KARA FP32 passed Coast Town but failed the `109.0 dB` synthetic-input SNR floor
on S10, S25, and a second S25 run with the identical `108.483658 dB` result.
This is not an input-independent threshold problem: the S25 LiteRT CPU control
for the same input reached `109.201036 dB`, and the pinned desktop conversion
report reached `109.638281 dB`. The GPU maximum error and cosine passed, but
the SNR gate is conjunctive and is not relaxed to finish Phase 3. Therefore
`gpu-auto-fp32-v1` is not a KARA Phase 7 production candidate.

HQ4 passed the single-window numerical check but consumed about 0.76 to
0.80 GiB of validation PSS delta, exceeding the provisional 384 MiB HQ4 hard
limit. It also lacks a known-good CPU fallback. It remains exploratory and
must not be selected through Auto.

## FP16 result

FP16 reduced S10 inference time and peak validation PSS, but its output parity
was unacceptable on every measured cell:

| Model | Device | Synthetic / Coast SNR dB | Synthetic / Coast max error | Peak PSS delta MiB |
| --- | --- | ---: | ---: | ---: |
| 9662 | S10 | 17.031 / 18.850 | 0.46984 / 0.77803 | 262.1 / 263.8 |
| 9662 | S25 | 17.145 / 18.235 | 0.44399 / 0.86953 | 338.6 / 338.4 |
| KARA | S10 | 30.180 / 27.544 | 0.09551 / 0.22057 | 263.8 / 260.4 |
| KARA | S25 | 29.969 / 28.159 | 0.09622 / 0.15807 | 338.1 / 335.3 |

`gpu-auto-fp16-v1` is rejected. It must not inherit the FP32 evidence or enter
Phase 7 unless a new profile and fresh evidence change that result.

## Auto fallback

Connected failpoints used real GPU and CPU sessions for 9662 on Coast Town.
S10 covered invocation; S25 covered setup, probe, invocation, and output
read. Every report recorded one GPU creation, one CPU creation, zero active
sessions after close, and `cpuCreatedWhileGpuActive=false`.

The event order was always GPU close before CPU creation. For example,
invocation and output-read used:

```text
gpu-created -> LiteRtGpu-run-1 -> LiteRtGpu-run-2 -> gpu-closed -> cpu-created -> LiteRtCpu-run-1 -> cpu-closed
```

Probe used one GPU call before close; setup closed the newly created GPU session
before a GPU call. All five reports accepted only CPU output after fallback.
The CPU recomputation met the 9662 floor: `97.523 dB` on S10 and `97.597 dB`
on S25. Host fault-injection tests additionally cover cancellation, OOM,
unconfirmed cleanup, CPU setup/run failures, session reuse, and terminal
post-fallback behavior.

## ABI preflight

Current app-packaged preflights made zero GPU allocator calls on:

- Galaxy S10 `armeabi-v7a`.
- API 26 pure `x86`.
- API 37 `x86_64`.

The x86_64 APK contains the accelerator library, but lacks an exact GPU
compatibility record, so native-library inventory alone does not permit a GPU
attempt. Arm32 and pure x86 are CPU-only by policy.

## Result and remaining gates

Phase 3 implementation and its internal evidence are complete. This is not
production GPU approval:

- GPU runtime compatibility remains `untested` in the model contract.
- Only 9662 FP32 may enter the Phase 7 full-song GPU matrix.
- KARA FP32, all FP16 profiles, and HQ4 are excluded from production Auto for
  the reasons above.
- Production source separation still routes through ONNX Runtime until the
  ordered model repository, cache, and LiteRT cutover phases are complete.
- Phase 7 must still measure full-song wall time, peak memory, thermal
  behavior, cancellation, and playback readiness on clean installs.
