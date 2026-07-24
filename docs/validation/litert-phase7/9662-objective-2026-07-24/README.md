# Phase 7 9662 Objective Qualification

This evidence set covers the objective full-song, playback, cache, and
lifecycle gates for the sole recommended/default candidate. Representative
listening and gesture-level UI review remain open, so this result does not
change catalog maturity or activation policy.

## Frozen Identity

- Booming SS app commit:
  `b01b7c0e577b5d04c61ea370fc51b674aee19131`
- AndroidTest APK SHA-256:
  `89ff3ed8712ac4508870b562704b3b81d7b8bca23cd97f88fa1ce90121dab7d3`
- App APK SHA-256: arm64
  `499b3c1f193a9a6988254185245d99fb1cf9db13e3f374335740758ce69eeaa1`,
  arm32 `5ffae498b66fc8a424e3fbd39c04a35ef1727587717aa2a9fe1944d74ec96489`,
  x86_64 `fc9ad0450753ec5e1e1c54c4377e881d7f0eddad03a4f856cbe066f1bedd7ff0`
- Bundled catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- Model artifact SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Contract and Release: `uvr_mdxnet_3_9662@2`, schema 2,
  `v0.1.0-candidates.1`
- Runtime/report inputs: LiteRT 2.1.5, runner v11, thresholds v2,
  fixtures v2
- Full-track fixture: 273.699 seconds, 12,070,130 frames, SHA-256
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`

All 24 raw reports share the same app, test APK, catalog, artifact, contract,
runtime, threshold, and fixture identities.

## Desktop Reference

The desktop reference was regenerated from canonical ONNX SHA-256
`e02220e80d8253f4c2209f8924298b2b686bbdf2868b788ff5500fb9bd94aadc`
at MusicSourceSeparation revision
`58fcedc9005a9dbfb2de728b66a21de8a0a5d7b5`. The command uses the contract's
`dimF=2048`, `nFft=6144`, FP32, no denoise, output scale `1.035`, and
model-output stem `vocals`:

```powershell
.\.venv\Scripts\python.exe tools\mdx_reference.py separate `
  --model models\uvr-mdx-candidates\trvlvr-all-public-uvr-models\UVR_MDXNET_3_9662.onnx `
  --input data\samples\_-_Coast_Town__decoded.wav `
  --output-dir .tmp\phase7-9662-reference-recheck `
  --limit-seconds 360 `
  --no-denoise `
  --model-output-scale 1.035 `
  --model-output-stem vocals
```

Regeneration exactly matched the existing reference:

| Stem | Reference SHA-256 |
| --- | --- |
| Vocals | `6d1fbc52b977bed2d8f21bee930d2231e8de3ad5e36fdecee10a30dd81ac34b2` |
| Instrumental | `0203b18a0b78c39bad7705732620aaf3bc37b87cc0c420b5f8566e2969f96e3b` |

## Full Song And Playback

Each target performed clean acquisition and explicit `Use`, a production
worker run with WAV-to-FLAC promotion and audio export, process recreation with
hydrated PCM checks, and a real `PlaybackService` MediaSession run covering
pause, seek, resume, and blend.

| Target | First ready | Full song | Frames | Recreation / hydration | MediaSession / drift |
| --- | ---: | ---: | ---: | --- | --- |
| Galaxy S25 arm64 | 4.509 s | 108.096 s | 12,070,130 | passed | passed / 0 ms |
| Galaxy S10 arm64 | 9.487 s | 194.694 s | 12,070,130 | passed | passed / 0 ms |
| Galaxy S10 arm32 | 11.379 s | 252.809 s | 12,070,130 | passed | passed / 0 ms |
| API 37 x86_64 AVD | 22.693 s | 202.540 s | 12,070,130 | passed | passed / 0 ms |

Every worker used one `LiteRtCpu` runtime record, produced finite
`Vocals,Instrumental` output, retained exact cache identity, and remained
playable after process recreation. Emulator timing is diagnostic only.

## Audio Equivalence

All four exported PCM16 stem pairs passed thresholds v2 against the regenerated
desktop reference:

- exact frame count and 48 exact segment placements;
- maximum per-sample delta: 1 LSB;
- maximum absolute stem error: 1 LSB (`0.000030517578125`);
- minimum SNR: 71.44432-71.44513 dB, diagnostic only under thresholds v2;
- maximum join delta: 2 LSB (`0.00006103515625`);
- maximum actual-versus-reference reconstruction error delta: 2 LSB.

Different CPU targets need not produce byte-identical WAV files. Each must be
quantization-equivalent to the same independently regenerated reference, which
all four rows satisfy.

## Sequential Lifecycle

The full 273.7-second fixture was used so the tail seek was still pending after
the first ready window. Each row used production-shaped single-use sessions:

| Target | Sessions | Pause state | Resume | Pending seek / result | Cancel latency / state |
| --- | ---: | --- | --- | --- | --- |
| Galaxy S25 arm64 | 4 | `Incomplete` | `Completed` | yes / `Completed` | 1 ms / `Incomplete` |
| Galaxy S10 arm64 | 4 | `Incomplete` | `Completed` | yes / `Completed` | 1 ms / `Incomplete` |
| Galaxy S10 arm32 | 4 | `Incomplete` | `Completed` | yes / `Completed` | 1 ms / `Incomplete` |
| API 37 x86_64 AVD | 4 | `Incomplete` | `Completed` | yes / `Completed` | 0 ms / `Incomplete` |

No row published a completed cache after cancellation or created an extra
session beyond the four expected scenario sessions.

## Service Lifecycle

The production `LiteRtAuto` service graph is qualified only on arm64 physical
devices. Both phones used `LiteRtGpu` without fallback.

| Target | Background first ready / full song | Home after ready | Prefetch windows | Stopped partial | Same key completed after transition |
| --- | --- | --- | ---: | --- | --- |
| Galaxy S25 arm64 | 2.845 s / 51.369 s | yes | 2 | yes | yes |
| Galaxy S10 arm64 | 8.037 s / 162.018 s | yes | 2 | yes | yes |

The S10 background path required the bounded second media-preparation attempt;
S25 required one. Both completed under the admitted cache identity after Home.
The prefetch fixtures use different content hashes, so identity retention
cannot be a false pass caused by duplicate audio.

## Evidence Files

- [`phase7-9662-objective-summary.json`](phase7-9662-objective-summary.json)
  is the compact machine-readable result and links every raw report.
- [`full-song`](full-song/) contains acquisition, worker, recreation, and
  MediaSession reports for each target.
- [`lifecycle`](lifecycle/) contains the four full-fixture sequential reports.
- [`service`](service/) contains the two background and two prefetch reports.
- [`SHA256SUMS`](SHA256SUMS) authenticates the summary and all 24 raw reports;
  it intentionally excludes this explanatory README.

## Decision

- 9662 FP32 passes the current objective CPU gates on arm64 and arm32, with
  x86_64 retained as emulator ABI evidence.
- Production arm64 Auto background and prefetch behavior pass on S10 and S25.
- Pure x86 remains ordinary-playback-only and source separation unsupported.
- Do not assign stable maturity or user activation until representative
  listening, the full gesture-level UI pass, and the exact final decision-build
  rerun are complete.
