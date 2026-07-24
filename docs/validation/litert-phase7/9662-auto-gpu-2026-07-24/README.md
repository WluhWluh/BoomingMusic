# Phase 7 9662 Auto GPU Qualification

This evidence set covers the successful production `LiteRtAuto` full-song,
process-recreation, and MediaSession path for 9662 FP32 on the Galaxy S10 and
S25. Both runs selected `LiteRtGpu` with profile `gpu-auto-fp32-v1`; neither
run used CPU fallback.

The reports use the same frozen app, APK, catalog, artifact, contract, fixture,
runner, and threshold identities as the 9662 CPU objective set. Existing Phase
7 setup, probe, and invocation fault-injection reports cover the separate
one-way GPU-to-CPU fallback requirement.

## Frozen Identity

- Booming SS app commit:
  `b01b7c0e577b5d04c61ea370fc51b674aee19131`
- Arm64 app APK SHA-256:
  `499b3c1f193a9a6988254185245d99fb1cf9db13e3f374335740758ce69eeaa1`
- AndroidTest APK SHA-256:
  `89ff3ed8712ac4508870b562704b3b81d7b8bca23cd97f88fa1ce90121dab7d3`
- Bundled catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- Model artifact SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Contract and Release: `uvr_mdxnet_3_9662@2`, schema 2,
  `v0.1.0-candidates.1`
- Runtime/report inputs: LiteRT 2.1.5, `gpu-auto-fp32-v1`, runner v11,
  thresholds v2, fixtures v2
- Full-track fixture: 273.699 seconds, 12,070,130 frames, SHA-256
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`

## Full Song And Playback

| Target | Actual backend | First ready | Full song | Recreation / hydration | MediaSession / drift |
| --- | --- | ---: | ---: | --- | --- |
| Galaxy S10 arm64 | `LiteRtGpu` | 11.560 s | 165.698 s | passed | passed / 0 ms |
| Galaxy S25 arm64 | `LiteRtGpu` | 3.656 s | 49.553 s | passed | passed / 0 ms |

Both workers produced exact 12,070,130-frame WAV and FLAC caches under the
same content and model identity. A fresh process resolved and hydrated each
completed cache. The real `PlaybackService` MediaSession then passed cache
adoption, pause, seek, resume, blend, and exact-identity checks.

## Digital Comparison

The exported PCM16 stems were compared with the independently regenerated ORT
reference from
[`WluhWluh/MusicSourceSeparation`](https://github.com/WluhWluh/MusicSourceSeparation)
revision `58fcedc9005a9dbfb2de728b66a21de8a0a5d7b5`.

Both devices produced the same comparison result:

- exact frame counts and 48 exact segment placements;
- maximum per-sample delta of 1 LSB;
- maximum absolute stem error of 1 LSB (`0.000030517578125`);
- minimum SNR of `71.44439455297164` dB, diagnostic under thresholds v2;
- maximum join delta of 2 LSB (`0.00006103515625`); and
- maximum actual-versus-reference reconstruction error delta of 2 LSB.

## Evidence Files

- [`phase7-9662-auto-gpu-summary.json`](phase7-9662-auto-gpu-summary.json)
  links the six raw reports and records the comparison metrics without local
  workstation paths.
- The two device directories contain one worker, one recreation, and one
  MediaSession report each.
- [`SHA256SUMS`](SHA256SUMS) authenticates the summary and raw reports; it
  intentionally excludes this explanatory README.

## Decision

The `gpu-auto-fp32-v1` success path passes the current objective full-song,
cache, reconstruction, and completed-playback gates on both physical arm64
targets. Retain the GPU-first production `Auto` policy and its existing one-way
CPU fallback.

This evidence does not assign stable catalog maturity. Representative listening
and gesture-level UI review, the immutable non-prerelease model Release, and
the exact final decision-build rerun remain separate promotion requirements.
