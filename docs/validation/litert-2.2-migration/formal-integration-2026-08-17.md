# LiteRT 2.2 Formal Integration Evidence

Status: Phase 5 complete at the source-clean candidate described below.

Captured: 2026-08-17

## Candidate

| Item | Identity |
| --- | --- |
| Branch | `migration/litert-2.2-mdx-demucs` |
| Candidate commit | `5ec08d84e1d2e2534880c321d6f5d28efff96950` |
| Runtime release | `downloadable-runtime-v2.2.0-bss.2-exp.1` |
| Runtime release URL | `https://github.com/WluhWluh/bss-litert-android/releases/tag/downloadable-runtime-v2.2.0-bss.2-exp.1` |
| Runtime artifact | `2.2.0-bss.2` |
| Runtime contract | `bss-litert-downloadable-runtime-v3` |
| Model release | `bss-tflite/v0.2.0-experimental.1` |
| Model release URL | `https://github.com/WluhWluh/bss-tflite/releases/tag/v0.2.0-experimental.1` |
| Bundled model catalog SHA-256 | `ef2fb4cab58d238bcae10e963203de43edeffd8f0228515450e944aed94cf6ce` |

`git status --short` was empty before the report edit. Raw audio, downloaded
runtime bundles, APKs, screenshots, and device reports remain under ignored
`build/litert-validation/` or `build/litert-runtime-cache/` roots.

The S25 and S10 product reports were produced at app-source tip `7574ce0d`.
The only later commit, `5ec08d84`, changes
`tools/run_litert_cpu_validation.ps1` so validation launches the app through
the Debug API and keeps it in the foreground. The scoped app diff from
`7574ce0d` to `5ec08d84` is empty, so those physical-device reports qualify
the same app and test sources. The two emulator rows were rerun against APKs
built and hashed from the exact `5ec08d84` candidate.

## Host And Packaging Gates

The exact candidate passed:

```text
:app:testGithubDebugUnitTest
:app:lintGithubDebug
:app:assembleGithubDebug
:app:assembleGithubDebugAndroidTest
```

- Unit tests: 785 passed, zero failed, zero skipped across 123 XML suites.
- Lint: passed with an empty `lint-results-githubDebug.txt` report.
- CMake built `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`.
- `tools/verify_litert_apks.py` verified all five APKs contain the product DSP
  for their ABI set and contain no downloadable LiteRT CPU/GPU library.
- `tools/verify_litert_native.py` verified the classes-only AAR, source lock,
  runtime contract, four CPU catalog rows, and bounded arm64 GPU profile.
- `dependencyInsight` found no `com.google.ai.edge.litert` Maven dependency in
  `githubDebugRuntimeClasspath`.
- The complete production-route unit suite passed and retains no ORT fallback.

### APK identities

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `github-arm64-v8a` | 100061761 | `d200f8d981819aff53fb3b8376154cfbe1ad2e224abffa0dca4404807bd99040` |
| `github-armeabi-v7a` | 99254113 | `180bdbf050b6ae838f4c17cfc60bfe7c3cd3a60e5db891ad4b07b4cbf83025ab` |
| `github-x86_64` | 100172589 | `96f26cc5f63dd1c5137fd66dce23fef2137f6f4121c346ff59bf901ec00b659d` |
| `github-x86` | 100116577 | `f01c4c3feefc592f9aa7383f941fab04b2b72ae6eda4b3da7157d2c516d20340` |
| `github-universal` | 109840099 | `a794e3733cddac701b5fefe3ced1e1f849f99731e173dc48b74fb9263130ae65` |
| `github-debug-androidTest` | 2275176 | `c8d97737a3fb0af0a86815fea7b674262df4f00ee8cda2617719b33f2285065c` |

The classes-only API AAR is 87693 bytes with SHA-256
`88a939aa5f3a65ff89bd90eed4b3af30b2a8866bedbbd3838761b143d2ccb387`.
The CPU and GPU catalog SHA-256 values are respectively
`8b00b7bc5303a6d89f75e810e92579f4f77589f67e57c2bc8cdbee453d41a114`
and `7673801435a3c3a88235799d40d6089f50539221cb059f27e6dc4eeef50bcd4c`.
The bounded arm64 GPU ZIP is 1439172 bytes with SHA-256
`669c9285cf126bff394f8c568b1d395db192f6f6dc31a296c8f818151185245e`;
its accelerator and `libBssOcl.so` hashes are respectively
`941f892418dae72b7bd740f2f2a26c61d6e50e0cf32c0c6bffca52aaffbc032f`
and `a8490866ed4b42e2c5b3bf39e7a0c5b4f03fe8a977c3d79b52ca31dd4d001bee`.

### CPU runtime bundle identities

| ABI | ZIP SHA-256 | `libLiteRt.so` SHA-256 | `liblitert_jni.so` SHA-256 |
| --- | --- | --- | --- |
| `arm64-v8a` | `da55d4470bf99761b086672822e682b2d48f5e3fcb2a7dc6da53f6bf6db274a2` | `97355a36cb8ac7628cf407773291e98da79f3ef184cc43cb0e57dedf5f0c0637` | `708b7a2bcdef55b698878ae237971fbd31d9a6bfcfe6ac81dc62c880ad6b4e8a` |
| `armeabi-v7a` | `6106c506024b15e11ef401bd5e9fea3e50735bc379e68de883d4b2991535ed74` | `5860fcecc1cef9bfb69a33b799465eba058102aa4d80e05320bc9be7a687e075` | `6bb45f8c3fa7a97d65a5d44b0800244da7a09a2b17cb8348880fc43c2e2b6c7a` |
| `x86_64` | `9c53fa2eb0327bac9f2c4251dbee267518865adaa5ce710185f767bf386e71e7` | `38a71966ac2ccd76c2782d5d96e317f5e6d3322ae9f8e3dfff6e3327e284c591` | `a05652e65e71ddb5b1f38c569a9e3722b87efeb76d2036266154a429bab9c657` |
| `x86` | `6a06556c25cdd2dbcbcd447beba6e6425efd81504ab2494f2b1be8d6614e6c0f` | `83132f9eb2fbbc0858a2d96c45bc5cb39c54922c9f7f5aed26bb5563ce2cb21c` | `570452100ba34041b95b066310cbc8db7a14a14d66dc51742727bbe35afc8699` |

## Model And Contract Identities

| Model | Stems | Contract SHA-256 | Artifact SHA-256 |
| --- | ---: | --- | --- |
| Official HTDemucs 4-stem base | 4 | `6a5dba3382614d027d4c2792b61027eed101d56d25bf98b186d851a97bd83544` | `9855718072ee819bacacdb6b670bd6257feca172bf27ac1d72dff994cdbeed81` |
| Official HTDemucs 6-stem | 6 | `9961b1470f436886abbae45603de198980036a15e8d398ef4c88fbb60c2e8a0c` | `8b19e919dd17c6a93d862ca9b1158ed72f09feb4c52745819346369506ba4ed7` |
| HTDemucs 6-stem guitar-ft | 6 | `9ff4917cdb8400dc7e1471505e3c790d411c69540209db51a699da251d286d1d` | `ab632a5a024033d557eabb716f8829230532e8e5b4cd7ba146812a301f89b9a5` |
| MDX 9662 | 2 | `uvr_mdxnet_3_9662@2` / conversion `695cf49db9bbe0d43ef0c8b52ae145a16c10cf28` | `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378` |

All three HTDemucs contracts admit CPU only. The guitar-ft contract retains
the author, Apache-2.0, official-base MIT, and MoisesDB notices. No migration
gate enables HTDemucs GPU or any NPU backend.

## Device Matrix

| Target | Result | Raw evidence |
| --- | --- | --- |
| S25 arm64 | CPU/GPU 13-shape MDX, 9662 full song, forced GPU-invocation fallback, all three HTDemucs models, cancellation, 6-to-4 supersession, process death, seek retarget, completed-cache switching, and 2/4/6-stem UI reconciliation passed. | `build/litert-validation/formal-7574ce0d/s25/`, `build/litert-validation/mdx-product-shapes/192.168.8.197_40077/`, and `build/litert-validation/phase4-ui/s25/` |
| S10 arm64 | CPU 13-shape MDX, downloaded bounded GPU 9662 full song, official 6-stem and guitar-ft product paths, resource sampling, and terminal lifecycle passed. | `build/litert-validation/formal-7574ce0d/s10/` and `build/litert-validation/mdx-product-shapes/192.168.8.181_5555/` |
| S10 armeabi-v7a | Managed 9662 CPU smoke, official 4/6-stem product lifecycle, FLAC/cache publication, and terminal process recycling passed. | `build/litert-validation/formal-7574ce0d/s10/` |
| API 37 x86_64 | Downloaded CPU runtime and native-managed dual-slot 9662 passed on the exact candidate. | `build/litert-validation/formal-5ec08d84/x86_64/emulator-5554/formal-5ec08d84-api37-x86_64-mdx-managed-r2.json` |
| API 26 x86 | After clearing app data, the standard GitHub providers downloaded the real x86 runtime and 9662 model; the bound-process JVM fallback published and reopened a completed two-stem cache. No validation build switch or ABI-wide product block was used. | `build/litert-validation/formal-5ec08d84/api26-x86-github-product-gate.txt` |

The exact x86_64 report records 97.598605 dB SNR, maximum absolute error
`5.953014e-5`, exact same-session reuse, a 3274 ms first invocation, and a
1639 ms reused invocation. Its APK and AndroidTest hashes match the table
above.

The API 26 row ran on `x86`, API 26, 4 GiB guest RAM, and a persistent 384 MiB
ART heap. The instrumentation completed in 21.386 seconds with `OK (1 test)`.
The app was launched and restored to the foreground through `ui.launch`.

During the API 37 run, an AVD that had remained up for roughly 34 hours reached
a load average above 17 and killed unrelated Settings providers as well as the
app for `timeout publishing content providers`. A no-snapshot cold launch
returned the guest to a normal idle state; the same APK then published its
Debug provider in about 4.4 seconds and passed. This was an AVD health failure,
not an app/provider regression. Foreground screenshots are retained under
`build/litert-validation/formal-5ec08d84/screens/`.

## Donor Comparison

The donor branch remains historical provenance. The formal branch did not
merge it. Runtime, native DSP, MDX, HTDemucs, process, and test changes were
split into focused commits. Intentional differences from the donor are:

- the latest-base ready-frontier, rapid-song, and EOF playback fixes remain;
- the formal branch uses exact `2.2.0-bss.2` delivery and current catalog
  identities rather than donor-local staging assumptions;
- x86 is a user-accessible JVM fallback product tier without the old resident
  validation switch;
- Debug automation uses the provider-backed control API and leaves the app in
  the foreground; and
- current 2/4/6-stem playback and UI state is reconciled without changing the
  established stem-icon mapping.

## Decision

Phase 5 is accepted. Exact LiteRT 2.2 CPU delivery, bounded arm64 GPU, native
MDX, CPU HTDemucs, four-ABI packaging, lifecycle, cache, playback, and UI gates
are complete for this migration scope.

This does not promote a model to stable, qualify HTDemucs GPU, or enable QNN,
AOT, or any vendor NPU. Phase 6 still requires the final branch-content audit,
push, and complete GitHub CI run on the pushed candidate.
