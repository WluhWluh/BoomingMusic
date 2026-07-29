# Phase 7 main-process death at terminal commit

Date: 2026-07-29

Status: S10 passed with CPU and bounded GPU; a completed manifest survives
main-process death immediately before the remote owner commits the terminal
journal transition

Product and harness revision:

- `ae01c7fc9f84ed2ffff8a1cf734de5134390bc2b`

Runner: `phase7-runner-v57`

## Boundary

The debug-only `TerminalCommit` barrier is reached after the remote owner has:

- published all 48 segment pairs;
- copied and validated the completed WAV stems;
- written the `Completed` manifest and final byte count; and
- persisted the CPU or bounded-GPU runtime record.

The run journal is still `Running` at sequence 99. Its sequence-100
`Completed` transition and writer release have not happened. The harness
freezes that exact state, records every segment's path, size, and SHA-256, then
kills the real main process while retaining the authoritative remote PID,
generation, run ID, cache key, and writer lease.

The host runner validates the frozen cache root and fault token, confirms the
old main PID is gone and the remote PID remains authoritative, then writes the
exact release token as the application UID before restarting `MainActivity`.
This ordering is necessary only for the artificial barrier: starting the
product first would make application recovery wait on a remote thread that the
test had deliberately suspended.

No observer or second start is required at this boundary. The completed
manifest is already the product projection. After the remote owner performs
its final journal commit, the recreated product must expose an idle worker, no
processing notification, and an exact completed cache that opens for playback.

## Frozen inputs

- Device: Galaxy S10 (`SM-G9730`), API 31, `arm64-v8a`
- Build fingerprint:
  `samsung/beyond1qltezc/beyond1q:12/SP1A.210812.016/G9730ZCU8HWE2:user/release-keys`
- Model: `uvr_mdxnet_3_9662@2`
- Model SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Fixture: `coast_town_full_wav`, 48 planned segments
- Fixture SHA-256:
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`
- App APK SHA-256:
  `042f54a4722365b4314bc5c9e0687d6e5aab5f8f7b11635bdcb3d6b44c93098c`
- AndroidTest APK SHA-256:
  `e7ef4b6113e22ce14090e335c2c9785c740a5e07ec674c97b202d2d27d3ddb9c`
- Catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`

## Results

| Backend | Old/new main PID | Remote PID | Generation | Runtime elapsed | Result |
| --- | --- | ---: | ---: | ---: | --- |
| CPU FP32 | `24705` / `29411` | `24835` | `130957366566238` | 213,843 ms | Pass |
| Bounded GPU FP32 | `29666` / `4107` | `29784` | `107811109699715` | 195,840 ms | Pass |

Both rows passed the same terminal invariants:

- journal sequence 99 before death and sequence 100 after recovery;
- all segment indices 0 through 47 preserved;
- the same remote PID, process generation, execution run ID, and cache key;
- one original observer transition, no replacement observer, and no second
  `start()`;
- processing notification 21331 present before death and not recreated after
  terminal publication;
- recreated worker state `Idle`, with no protected or pending cache ownership;
- cache-management state `Completed`, `InstalledExact`, and `48/48`; and
- position zero opens as `Ready` from the completed output.

The GPU row retained `gpu-opencl-bounded-fp32-v1`, artifact
`2.1.5-bss.2`, OpenCL FP32, `kernelBatchSize=1`, and
`commandQueueWindowSize=1`. Its terminal runtime record was `LiteRtGpu` with
no fallback stage or reason. The CPU row had no GPU identity and recorded
`LiteRtCpu`.

A three-segment CPU smoke was used while refining the host-side barrier
ordering. The full 48-segment CPU and GPU rows above are the qualification
results.

## Evidence

Raw reports and input envelopes remain ignored under
`build/phase7-validation/main-terminal-boundary-v1/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s10-main-terminal-cpu-v3` | `3a1beda75d426964f66f9d56895dc2d6ba33ea720db8f69813b359f325a44c1c` | `ac71352508c99cead2444706d65cc5004a4394435988ff3c03ecc883e2f8a02e` |
| `phase7-s10-main-terminal-gpu-v1` | `ce2a8594cec2212e9ce26f27f8d80b27de66312b38d7bce5f810472227745505` | `a6bbadb6343d110cbfa5a8db95ab12fbef19ee27dcad9fa5ee8a09087f3308a2` |

## Limits

This checkpoint covers the terminal journal boundary on S10. It does not
cover death before foreground-service handoff, inside native invocation, or
between final segment publication and completed-manifest publication. It does
not claim active original-audio continuity across main-process death.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or model execution policy changed for this work.
