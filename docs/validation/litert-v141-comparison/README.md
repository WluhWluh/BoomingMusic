# LiteRT 1.4.1 legacy runtime comparison

This experiment compares the legacy `Interpreter` and `GpuDelegate` APIs from
LiteRT 1.4.1 with the current LiteRT 2.1.5 `CompiledModel` implementation used
by the Booming SS validation harness. It is a raw-tensor, single-window test;
audio decoding, STFT/ISTFT, cache I/O, foreground UI contention, cancellation,
and full-song behavior are outside its scope.

## Frozen inputs

- Model: `UVR_MDXNET_3_9662_static_float32.tflite`, 29,700,464 bytes,
  SHA-256 `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`.
- Input: `uvr_mdxnet_3_9662_synthetic_00_nchw_f32.bin`, 8,388,608 bytes,
  SHA-256 `195c81b8c72c2865f18080a78433c4377b4375a8569f3bc4d7a243ffbc1b3a33`.
- ORT reference: `uvr_mdxnet_3_9662_synthetic_00_ort_nchw_f32.bin`,
  8,388,608 bytes, SHA-256
  `9c78d8489bb9be47f0be707f52f5d5be3d398382cf63fe8de1355f96e34c09d3`.
- Galaxy S10: `SM-G9730`, Android 12/API 31, arm64-v8a.
- Galaxy S25: `SM-S9310`, Android 15/API 35, arm64-v8a.
- Booming SS commit: `05c1d6b6e28ee666e488e38d43340f600fd04a6f`.

Every case used four CPU threads, no warm-up invocation, and two calls on one
session. The legacy delegate, interpreter, invocations, and close operations
all ran on the same worker thread. Legacy CPU enabled XNNPACK. Legacy GPU
forced OpenCL and disallowed precision loss. The 2.1.5 GPU control used the
versioned `gpu-opencl-fp32-v1` profile. No legacy GPU serialization directory
or model token was configured, so both legacy GPU profiles measured uncached
delegate preparation.

The legacy process was cleared before each case. Its logs show XNNPACK or the
GPU delegate replacing all 183 model nodes in one partition. LiteRT 2.1.5 does
not expose per-node placement, so its reports instead record explicit OpenCL
accelerator selection and the mapped GPU accelerator library.

## CPU results

| Device | Runtime | Setup ms | First ms | Reuse ms | Peak PSS delta MiB | SNR dB | Max error |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S10 | 1.4.1 Interpreter/XNNPACK | 407.8 | 6,134.7 | 6,121.5 | 695.6 | 93.901848 | 0.00005275 |
| S10 | 2.1.5 CompiledModel | 566 | 2,201 | 2,220 | 643.6 | 93.901848 | 0.00005275 |
| S25 | 1.4.1 Interpreter/XNNPACK | 242.3 | 2,421.7 | 2,417.9 | 694.6 | 93.891187 | 0.00005422 |
| S25 | 2.1.5 CompiledModel | 350 | 1,619 | 1,510 | 723.9 | 93.891187 | 0.00005422 |

Legacy CPU reuse was 2.76 times slower on S10 and 1.60 times slower on S25.
The measured PSS deltas are in the same broad range and do not establish a
consistent legacy memory advantage.

## GPU fast profile

`FAST_SINGLE_ANSWER` is the viable cold-start legacy profile.

| Device | Runtime | Setup ms | First ms | Reuse ms | Peak PSS delta MiB | SNR dB | Max error |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S10 | 1.4.1 OpenCL fast | 1,566.9 | 2,513.1 | 2,496.9 | 469.6 | 94.506037 | 0.00005448 |
| S10 | 2.1.5 OpenCL FP32 | 1,158 | 2,320 | 2,280 | 391.1 | 93.832758 | 0.00006688 |
| S25 | 1.4.1 OpenCL fast | 923.5 | 490.0 | 479.3 | 519.4 | 94.506037 | 0.00005448 |
| S25 | 2.1.5 OpenCL FP32 | 1,007 | 521 | 410 | 469.3 | 93.832758 | 0.00006688 |

Legacy fast reuse was 9.5% slower on S10 and 16.9% slower on S25. Its measured
PSS delta was 78.5 MiB higher on S10 and 50.1 MiB higher on S25. Setup was
slower on S10 and slightly faster on S25, so this profile does not provide a
repeatable performance or memory advantage over 2.1.5.

## GPU sustained profile

`SUSTAINED_SPEED` produced faster steady-state execution, but delegate setup
spent a very long time preparing the OpenCL program.

| Device | Setup ms | First ms | Reuse ms | Peak PSS delta MiB |
| --- | ---: | ---: | ---: | ---: |
| S10 | 301,922.4 | 1,083.7 | 1,066.8 | 465.3 |
| S25 | 59,392.5 | 339.7 | 309.9 | 522.1 |

Against the 2.1.5 OpenCL reuse result, the extra setup cost breaks even only
after about 248 S10 windows or 583 S25 windows in the same session. This is not
an acceptable uncached first-use path. A separate experiment would need to
validate legacy serialization parameters, cold and warm process restarts, and
cache invalidation before this profile could be reconsidered.

## Interpretation

- All outputs were finite, met the existing parity floor, and were bit-exact
  between the first and reused invocation within each case.
- Legacy GPU happened to be slightly closer to the frozen ORT reference than
  2.1.5 GPU. Both results remain numerically valid; this is not a quality test.
- LiteRT 1.4.1 is not a competitive CPU replacement for the current runtime on
  either phone.
- The practical legacy GPU profile is no faster than 2.1.5 and used more PSS in
  this harness. The sustained profile trades an unacceptable first-use delay
  for faster repeated calls.
- The PSS comparison is directional rather than byte-for-byte: legacy ran in a
  minimal standalone app while 2.1.5 ran in the Booming SS instrumentation
  process. Both tables report peak-minus-initial process PSS to reduce that
  baseline difference.
- These results do not determine UI smoothness. A legacy migration would still
  require the same foreground frame, GPU contention, process isolation, and
  full-song tests already used for the 2.1.5 path.

The result does not justify migrating from `CompiledModel` solely for runtime
performance. The legacy runtime remains relevant only as a separately scoped
source-availability or distribution fallback, with migration cost and missing
behavioral validation treated as independent gates.

The structured reports are stored in [`raw/`](raw/) and authenticated by
[`SHA256SUMS`](SHA256SUMS). Full-device delegate `logcat` captures remain local
and are intentionally excluded from version control because they contain
unrelated process output. The summary above retains the relevant delegate
placement result.
