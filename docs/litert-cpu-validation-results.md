# LiteRT CPU Phase 2 validation results

Phase 2 was validated on 2026-07-21 with the app-packaged LiteRT 2.1.5
runtime. The production worker and playback path still select ONNX Runtime;
LiteRT remains reachable only through the isolated AndroidTest runner.

The complete device reports and their checksums are stored under
[`validation/litert-cpu-phase2/`](validation/litert-cpu-phase2/).

## Identities

- LiteRT CPU implementation: Booming SS commit `0bf2be4c`.
- Device-matrix app commit: `545a1a5f`.
- Matrix catalog: `bss-tflite` commit `815b8361`, SHA-256
  `3bbcfb976ddeed3eb85bf424bfc28bcc54ab5622dda8e4cc912d83d689140e78`.
- Final contract revision: `bss-tflite` commit `73e6b25c`, SHA-256
  `a1cb77832cdb28864dd4388ec8b66ca16e25903dde8752c568eaffadb1e45ff5`.
- Final bundled-catalog app commit: `79e606ab`.
- Supplemental x86 runtime: `v2.1.5-bss.1`, 7,482,132 bytes, SHA-256
  `02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af`.

The final contract revision differs from the matrix catalog only by changing
HQ4 on `armeabi-v7a` from `untested` to `unsupported`. That change is backed
by the S10 32-bit failure report and the final zero-allocation preflight
report at app commit `79e606ab`.

The tested TFLite artifacts were:

| Model | SHA-256 |
| --- | --- |
| 9662 | `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378` |
| KARA | `4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64` |
| HQ4 | `5f091562bd0297ff2223015a219d12cc1136a9d54df975680ff4eb239629c742` |

## Numerical matrix

PSS delta is the in-process increase from the checked input/reference state
to the active, reused LiteRT session. It is not an ordinary-playback baseline.
Every successful case reused the same session and produced an output identical
to its first invocation.

| Device / ABI | Model / fixture | SNR dB | Max error | First ms | Reuse ms | PSS delta MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Galaxy S10 / arm64-v8a | 9662 / Coast Town | 97.520 | 0.0000585 | 2,464 | 2,459 | 654.9 |
| Galaxy S10 / arm64-v8a | KARA / Coast Town | 111.260 | 0.0000137 | 2,349 | 2,468 | 685.0 |
| Galaxy S10 / arm64-v8a | HQ4 / Coast Town | 103.460 | 0.0004005 | 6,785 | 7,484 | 1,059.2 |
| Galaxy S25 / arm64-v8a | 9662 / Coast Town | 97.599 | 0.0000608 | 1,430 | 1,345 | 683.6 |
| Galaxy S25 / arm64-v8a | KARA / Coast Town | 110.720 | 0.0000171 | 1,430 | 1,336 | 683.1 |
| Galaxy S25 / arm64-v8a | HQ4 / synthetic_00 | 89.500 | 0.0001177 | 3,465 | 3,446 | 1,079.7 |
| Galaxy S10 / armeabi-v7a | 9662 / Coast Town | 97.490 | 0.0000593 | 2,829 | 2,981 | 517.9 |
| Galaxy S10 / armeabi-v7a | KARA / Coast Town | 111.460 | 0.0000130 | 2,733 | 2,948 | 519.3 |
| API 26 emulator / x86 | 9662 / Coast Town | 97.599 | 0.0000595 | 1,830 | 1,459 | 531.7 |
| API 26 emulator / x86 | KARA / Coast Town | 110.730 | 0.0000173 | 1,550 | 1,428 | 532.7 |
| API 37 emulator / x86_64 | 9662 / Coast Town | 97.599 | 0.0000595 | 954 | 863 | 827.4 |
| API 37 emulator / x86_64 | KARA / Coast Town | 110.730 | 0.0000173 | 905 | 861 | 835.9 |
| API 37 emulator / x86_64 | HQ4 / Coast Town | 103.431 | 0.0003204 | 2,392 | 2,360 | 1,313.9 |

All successful rows passed the model-specific SNR, cosine, and maximum-error
floors. Stem compensation was applied exactly once, and primary plus residual
reconstructed the input within the `0.00001` limit.

## Lifecycle checks

- Cancellation before invocation is checked in every successful report.
- In-flight cancellation returned `CancellationException` after the native
  call completed: S10 2,728 ms, S25 1,257 ms, x86 1,410 ms, and x86_64 825 ms.
- 9662 to KARA session replacement created a distinct session on S10, S25,
  and native x86_64.
- Every numerical report performs a second invocation through the reusable
  provider and checks exact output equality.
- Every host run force-stops and restarts the app before instrumentation,
  exercising fresh-process model creation as well as in-process reuse.

## HQ4 resource decisions

- `arm64-v8a`: window parity passed on S10 and S25, but the approximately
  1.1 GiB session PSS increase exceeds the provisional 384 MiB hard limit.
  The CPU status remains `untested`, so production activation is blocked.
- `x86_64`: window parity passed, but the approximately 1.3 GiB PSS increase
  also exceeds the hard limit. The CPU status remains `untested`.
- `armeabi-v7a`: session creation failed while LiteRT created output buffers,
  before first inference. The final contract marks it `unsupported`; the
  final preflight made zero native allocator calls.
- `x86`: the normal preflight made zero native allocator calls. An explicit
  AndroidTest-only probe with 3,036 MiB device RAM and two CPU threads reached
  LiteRT, then XNNPACK failed to allocate tensors during its first invocation
  at approximately 449 MiB process PSS. Increasing the AVD RAM did not make
  HQ4 viable, so the CPU status remains `unsupported`.

HQ4 must not become selectable on the two 64-bit targets until a later
full-song run satisfies a revised, explicitly documented memory gate. The
single-window result does not justify raising the current hard limit.

## Packaging and supply chain

`verify_litert_native.py` confirmed the pinned x86 SHA-256 and this dependency
allowlist: `libandroid.so`, `libc.so`, `libdl.so`, `liblog.so`, and `libm.so`.

`verify_litert_apks.py` confirmed:

| APK | Bytes | LiteRT inventory |
| --- | ---: | --- |
| arm64-v8a | 79,988,461 | `libLiteRt.so`, `libLiteRtClGlAccelerator.so` |
| armeabi-v7a | 66,941,882 | `libLiteRt.so` |
| x86 | 85,007,530 | `libLiteRt.so` |
| x86_64 | 88,487,888 | `libLiteRt.so`, `libLiteRtClGlAccelerator.so` |
| universal | 195,526,024 | exactly the matching inventory for all four ABIs |

The API 26 pure-x86 `LiteRtPackagingSmokeTest` passed through the exact app
APK using `Environment`, `CompiledModel`, `TensorBuffer`, JNI invocation, and
close. Device reports also resolve `libLiteRt.so` through the app class loader
and record the matching split inventory.

These APK sizes include both ONNX Runtime and LiteRT during migration. They
are not the Phase 6 final installed-size result.

## Remaining gates

- GPU setup, probe, fallback, and device eligibility belong to Phase 3.
- Production model installation, selection, and model-aware caches belong to
  later phases; no Phase 2 entry point writes production separation caches.
- Full-song memory, thermal, playback-readiness, and cache behavior remain
  mandatory before HQ4 or a GPU backend can be promoted.
