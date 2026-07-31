# Vendor NPU BrowserStack Device Matrix

This document records the Android devices identified through BrowserStack App
Live for Qualcomm QNN, MediaTek accelerator, and future Exynos experiments. It
is an inventory and test-ordering document, not production device admission.

Status: the first Qualcomm campaign was closed on 2026-07-31. Additional
Qualcomm devices, MediaTek bring-up, and Exynos discovery are deferred until
the common LiteRT CPU core can be downloaded and loaded from app-private
storage.

## Evidence Scope

The inventory was captured on 2026-07-30 with the following BrowserStack
ADB Shell commands:

```text
getprop ro.product.manufacturer
getprop ro.vendor.product.model
getprop ro.board.platform
getprop ro.build.version.release
getprop ro.system.build.version.sdk
```

BrowserStack returned an empty value for `ro.vendor.product.model` on every
device. The device names below therefore come from the App Live selector. The
manufacturer, board platform, Android release, and API level come from the ADB
capture. BrowserStack currently accepts only the commands documented in its
[ADB command allowlist](https://www.browserstack.com/docs/app-live/adb-commands).

This report contains the 28 device rows for which command results were
provided: 12 Qualcomm rows, 11 MediaTek rows, and 5 Exynos rows. Other models
visible in the App Live selector remain unverified until their same command
set is captured.

The SoC names in this document are mappings from the observed board platform
and the selected retail device. They remain inferred until an instrumented app
report records `Build.SOC_MANUFACTURER`, `Build.SOC_MODEL`, `Build.HARDWARE`,
and the actual accelerator selected at runtime.

Important interpretation rules:

- A board platform identifies a family, not always one exact SoC. In
  particular, `canoe` appears on both Snapdragon 8 Gen 5 and Snapdragon 8
  Elite Gen 5 devices.
- MediaTek board IDs similarly cover related bins or branded variants. For
  example, `mt6878` appears on Dimensity 7300, 7300 Ultra, and 7400-class
  devices in this inventory.
- Device and OEM diversity is evidence for dynamic capability handling. It
  must not become a production model-name or board-platform allowlist.
- App Live is suitable for compatibility, numerical, lifecycle, and
  within-device backend comparisons. Its absolute cross-device timing,
  temperature, and power results are not hardware benchmarks.

## Initial Qualcomm Campaign Result

The first campaign validated generation-specific LiteRT 2.1.5 and QAIRT
2.44.0.260225 packages against the 9662 FP32 model. Verified QNN IR was emitted
on representative SM8750/HTP v79, SM8650/v75, SM8550/v73, SM8475/v69, and
SM8450/v69 devices. Quick and Full profiles passed on the Galaxy S25,
OnePlus 13R, OnePlus 12R, Galaxy S23 Ultra, OnePlus 11R, and a China-market
Galaxy S22. The Galaxy S25 Ultra passed Quick.

Two boundaries remained non-admitted:

- one unhealthy BrowserStack Galaxy Tab S8 session silently used a CPU path
  after provider registration and emitted no QNN IR; the successful Galaxy S22
  means this cannot be generalized to SM8450 or Samsung devices; and
- the Edge 50 Fusion loaded the research v73 package but emitted no QNN IR and
  reproduced CPU output at CPU speed.

The harness now treats provider readiness and an `NPU` label only as preflight
signals. Every claimed QNN tensor or audio stage must emit a non-empty QNN IR
partition or the run fails closed as CPU fallback. The full immutable summary,
including device identities, performance, numerical interpretation, and
limitations, is in the
[MusicSourceSeparation QNN device matrix](https://github.com/WluhWluh/MusicSourceSeparation/blob/e1fd8205680fe01937e548defee3bd53babebc57/docs/android-litert-qnn-device-matrix-2026-07-31.md).

These results establish research feasibility, not a Booming SS backend or SoC
allowlist. QNN and MediaTek work must not block the next common-runtime
downloadability experiment.

## Qualcomm Inventory

| Device | Manufacturer | Android / API | Board platform | Inferred SoC or family | Test priority |
| --- | --- | ---: | --- | --- | --- |
| Galaxy S25 | samsung | 15 / 35 | `sun` | Snapdragon 8 Elite | P0 cloud control for the locally validated S25 path |
| OnePlus 13R | OnePlus | 15 / 35 | `pineapple` | Snapdragon 8 Gen 3 | P0 modern flagship generation |
| OnePlus 12R | OnePlus | 14 / 34 | `kalama` | Snapdragon 8 Gen 2 | P0 previous flagship generation |
| Edge 50 Fusion | motorola | 14 / 34 | `parrot` | Snapdragon 7s Gen 2 | P0 modern midrange boundary |
| OnePlus 11R | OnePlus | 13 / 33 | `taro` | Snapdragon 8+ Gen 1 | P1 older HTP generation |
| Moto G71 5G | motorola | 11 / 30 | `holi` | Snapdragon 695 | P1 older midrange and resource boundary |
| Galaxy Tab S7 | samsung | 11 / 30 | `kona` | Snapdragon 865+ | P1 oldest planned QNN boundary |
| OnePlus 15R | OnePlus | 16 / 36 | `canoe` | Snapdragon 8 Gen 5, expected SM8845 | P2 forward-compatibility probe |
| Galaxy Z Fold 8 Ultra | samsung | 17 / 37 | `canoe` | Snapdragon 8 Elite Gen 5, expected SM8850 | P2 `canoe` family collision and API 37 probe |
| Galaxy Tab S8 | samsung | 12 / 31 | `taro` | Snapdragon 8 Gen 1 | P2 same-family and OEM comparison |
| Galaxy S23 Ultra | samsung | 13 / 33 | `kalama` | Snapdragon 8 Gen 2 | P2 same-platform OEM comparison |
| Galaxy S25 Ultra | samsung | 15 / 35 | `sun` | Snapdragon 8 Elite | P2 same-platform form-factor comparison |

The local Galaxy S25 remains the trusted performance and listening baseline.
The BrowserStack Galaxy S25 row is valuable first because it distinguishes an
App Live packaging or runtime problem from a QNN generation problem before the
matrix expands to other platforms.

## MediaTek Inventory

| Device | Manufacturer | Android / API | Board platform | Inferred SoC or family | Test priority |
| --- | --- | ---: | --- | --- | --- |
| Galaxy Tab S10+ | samsung | 15 / 35 | `mt6989` | Dimensity 9300+ | P0 high-end discovery and bring-up |
| Edge 60 Fusion | motorola | 15 / 35 | `mt6878` | Dimensity 7300 family | P0 current mainstream phone bring-up |
| Edge 40 Neo | motorola | 13 / 33 | `mt6879` | Dimensity 7030 / 1050 family | P1 preceding midrange family |
| Galaxy A34 | samsung | 13 / 33 | `mt6877` | Dimensity 1080 | P1 older mainstream family and Samsung firmware |
| Realme 11X 5G | realme | 13 / 33 | `mt6835` | Dimensity 6100+ | P1 entry-level Dimensity boundary |
| Galaxy Tab S11 | samsung | 16 / 36 | `mt6991` | Dimensity 9400+ | P2 latest high-end forward-compatibility probe |
| Galaxy M32 | samsung | 11 / 30 | `mt6853` | Dimensity 720, identifying the M32 5G variant | P2 legacy and fallback boundary |
| Redmi Note 14 Pro 5G | Xiaomi | 15 / 35 | `mt6878` | Dimensity 7300 Ultra | P2 cross-OEM `mt6878` comparison |
| Vivo T5x 5G | vivo | 16 / 36 | `mt6878` | Dimensity 7400 Turbo | P2 newer-bin and API 36 comparison |
| Vivo T4x 5G | vivo | 15 / 35 | `mt6878` | Dimensity 7300 | P2 cross-OEM `mt6878` comparison |
| Realme 12+ | realme | 14 / 34 | `mt6877` | Dimensity 7050 | P2 cross-OEM `mt6877` comparison |

`mt6878` and `mt6877` duplicates should not all run during initial bring-up.
First establish one working path per family, then use the duplicate rows to
separate SoC-family behavior from OEM firmware behavior.

## Exynos Control Inventory

These devices are not candidates for the first QNN or MediaTek implementation.
They remain useful later as nonmatching-vendor fallback and generic CPU/GPU
controls.

| Device | Manufacturer | Android / API | Board platform | Inferred SoC or family | Test priority |
| --- | --- | ---: | --- | --- | --- |
| Galaxy S26 | samsung | 16 / 36 | `erd9965` | Exynos 2600 family | Deferred vendor discovery |
| Galaxy S24 | samsung | 14 / 34 | `erd9945` | Exynos 2400 family | Deferred vendor discovery |
| Galaxy S21 | samsung | 12 / 31 | `universal2100_r` | Exynos 2100 | Deferred OS-upgrade control |
| Galaxy S21 | samsung | 11 / 30 | `universal2100_r` | Exynos 2100 | Deferred same-device OS control |
| Galaxy S20 | samsung | 10 / 29 | `universal990` | Exynos 990 | Deferred legacy fallback control |

The observed platforms confirm that BrowserStack's Galaxy S20, S21, S24, and
base S26 rows must not be assumed to contain Qualcomm hardware from their
retail names alone.

## Deferred Test Order

The ordering below is retained for a later vendor-backend phase. It is not an
active prerequisite for the common LiteRT core experiment.

### Q0: Validate the existing QNN package across representative platforms

Run the exact 9662 FP32 single-window fixture in this order:

1. Galaxy S25 `sun`, as the BrowserStack control for the locally passing S25.
2. OnePlus 13R `pineapple`.
3. OnePlus 12R `kalama`.
4. Edge 50 Fusion `parrot`.

For every row, record runtime package identity, QNN backend and HTP library
load results, selected skeleton or architecture, graph-finalization status,
delegated partitions, cold compilation time, one warm invocation, actual
backend, fallback stage, finite output, and CPU-reference error metrics.

Do not continue to a performance comparison when plugin discovery, graph
finalization, or numerical validation fails. Preserve the exact typed failure
and retry only with a deliberately changed runtime package or configuration.

### Q1: Establish old-generation and resource boundaries

After Q0 passes or yields understood incompatibilities, test:

1. OnePlus 11R `taro`.
2. Moto G71 5G `holi`.
3. Galaxy Tab S7 `kona`.

These rows determine how many older HTP generations the downloadable QNN
runtime can support and whether runtime size, compilation memory, or sustained
execution makes that support useful. Passing initialization alone is not a
performance or product-support result.

### Q2: Test new platforms and same-platform OEM differences

Test `canoe` only after the packaged QAIRT/QNN revision is known to support
that family. Run OnePlus 15R first, then Galaxy Z Fold 8 Ultra to prove that one
board platform does not imply one exact SoC or HTP binary. Use Galaxy Tab S8,
Galaxy S23 Ultra, and Galaxy S25 Ultra only when a same-platform OEM, Android,
or form-factor comparison is needed.

### M0: Discover and bring up a MediaTek execution path

Use Galaxy Tab S10+ `mt6989` and Edge 60 Fusion `mt6878` as the first pair.
Before selecting a long-term backend, record from app-owned diagnostics:

- available public or redistributable accelerator API and runtime version;
- plugin or delegate discovery and initialization result;
- whether execution uses a MediaTek NPU/APU, GPU, or CPU in practice;
- supported and rejected model operators and resulting partitions;
- compile-cache behavior and failure diagnostics; and
- runtime package source, license, and APK/download size.

An NNAPI result is a useful control but must not be labeled a MediaTek NPU
result unless the actual delegated backend is observed.

### M1: Cover distinct MediaTek families

After one modern MediaTek path executes correctly, test Edge 40 Neo `mt6879`,
Galaxy A34 `mt6877`, and Realme 11X 5G `mt6835`. This is the minimum useful
cross-generation phone matrix. Add Galaxy Tab S11 `mt6991` only after the
runtime version requirement for that latest family is understood.

### M2: Separate family behavior from OEM behavior

Use Redmi Note 14 Pro 5G, Vivo T5x 5G, and Vivo T4x 5G as the `mt6878`
cross-OEM set. Use Realme 12+ as the `mt6877` cross-OEM row. Galaxy M32
`mt6853` is the final legacy and graceful-fallback boundary, not an initial
performance target.

## Common Validation Ladder

Every new vendor backend follows the same progression:

1. Compile and invoke one frozen 9662 FP32 window.
2. Compare the output with the same-device LiteRT CPU result and the frozen
   desktop reference; require finite output and preserve SNR, maximum error,
   mean absolute error, and reconstruction metrics.
3. Run repeated warm windows and report all samples, median, and p95.
4. Run a bounded 20- or 100-window stability case with cancellation and
   backend-failure handling.
5. Measure main, inference, native, graphics, and total PSS; record compile
   transients separately from steady execution.
6. Exercise foreground interaction and report frame timing while NPU work is
   active.
7. Run the full-song, cache, playback, process-death, and background matrix
   only after the short numerical and lifecycle gates pass.
8. Test KARA only after 9662 is stable. Keep HQ4 last because its model and
   working-set size can hide a backend-integration failure behind resource
   pressure.

Within-device CPU, bounded GPU, and NPU comparisons should alternate order and
include at least three cold repetitions before supporting a performance claim.
BrowserStack results can establish compatibility and gross regressions, but
thermal, battery, and final performance policy still require local physical
device evidence.

## Required App-Side Device Report

The BrowserStack ADB allowlist is insufficient to disambiguate every platform.
The benchmark or diagnostic APK should emit a machine-readable record with:

- `Build.MANUFACTURER`, `Build.MODEL`, `Build.DEVICE`, and `Build.HARDWARE`;
- `Build.SOC_MANUFACTURER` and `Build.SOC_MODEL` where the API exposes them;
- Android release, API level, supported ABIs, process bitness, total memory,
  and low-RAM classification;
- requested and actual backend, accelerator/plugin version, and all loaded
  vendor runtime identities;
- model ID, model SHA-256, contract version, tensor shapes, and precision;
- compilation, partition, fallback, numerical, timing, memory, thermal, and UI
  frame metrics; and
- app commit, APK SHA-256, runtime package SHA-256, and dirty-tree state.

This report, rather than the retail device name, must be the identity anchor
for later committed validation evidence.
