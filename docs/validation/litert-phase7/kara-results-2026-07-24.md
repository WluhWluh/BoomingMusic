# Phase 7 KARA Objective Results

These results qualify the objective CPU behavior of KARA FP32 before the final
Phase 7 promotion commit. They do not complete the representative listening or
gesture-level UI review, and they do not change the bundled catalog tier.

The compact machine-readable record is
[`kara-objective-results-2026-07-24.json`](kara-objective-results-2026-07-24.json).

## Frozen Inputs

- App binary source commit:
  `4c680f238968a38dda26d83435b90e5e55137c7e`
- Bundled catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- Model artifact: `UVR_MDXNET_KARA_static_float32.tflite`, 29,700,460
  bytes, SHA-256
  `4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64`
- Contract and release: `uvr_mdxnet_kara@2`, schema 2,
  `v0.1.0-candidates.1`
- Runtime/report inputs: LiteRT 2.1.5, runner v11, thresholds v2,
  fixtures v2
- Full-track WAV: 273.699 seconds, 12,070,130 frames, SHA-256
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`
- AndroidTest APK SHA-256:
  `aa725b78ce9b4452fdbaa0216b57b7bcb431173b2fb714bde10816b8efa97966`

The arm64, arm32, and x86_64 app APK hashes are retained in the JSON record.
Every row used the CPU-only `cpu-default-fp32-v1` profile with four XNNPACK
threads. KARA's rejected GPU profiles were not reopened.

## Desktop Reference

The reference was regenerated from the canonical TRvlvr ONNX file at
MusicSourceSeparation revision
`58fcedc9005a9dbfb2de728b66a21de8a0a5d7b5`. The source ONNX SHA-256 is
`e3167c87333a48548413e972a286bf40bf5694001d2853861eb1435953f02d63`.
The reproducible command uses `dimF=2048`, `nFft=6144`, FP32, no denoise,
output scale `1.035`, and model-output stem `vocals`:

```powershell
.\.venv\Scripts\python.exe tools\mdx_reference.py separate `
  --model models\uvr-mdx-candidates\trvlvr-all-public-uvr-models\UVR_MDXNET_KARA.onnx `
  --input data\samples\_-_Coast_Town__decoded.wav `
  --output-dir .tmp\phase7-kara-reference-recheck `
  --limit-seconds 360 `
  --no-denoise `
  --model-output-scale 1.035 `
  --model-output-stem vocals
```

The regenerated outputs exactly matched the prior reference:

| Stem | Reference SHA-256 |
| --- | --- |
| Vocals | `c700cae15bf26dd913088e37c0ac60a34e95871f2a24f0a48ffac35d3b8ea2f3` |
| Instrumental | `7b6bdd181d9b520f6fc1535c2be234ce324ac25ea708df049027e421f06f0fed` |

## Repeated Resources

Each row contains one cold session and three warm sessions. Every run used a
fresh exact cache identity. Emulator timing and thermal values are diagnostic.

| Target | Full song (s) | First ready (s) | Peak PSS (MiB) | PSS delta (MiB) | Peak thermal |
| --- | ---: | ---: | ---: | ---: | ---: |
| Galaxy S25 arm64 | 84.1-99.9 | 4.53-4.57 | 942.9-953.3 | 739.9-754.1 | 1 |
| Galaxy S10 arm64 | 174.9-189.8 | 8.36-11.64 | 929.3-931.1 | 723.1-735.5 | 0 |
| Galaxy S10 arm32 | 222.5-230.3 | 10.37-11.11 | 797.9-800.3 | 573.2-577.7 | 0 |
| API 37 x86_64 AVD | 159.7-165.7 | 11.60-17.23 | 1,070.3-1,076.2 | 870.6-878.8 | 0 |

All 16 runs completed. No target showed progressive PSS growth across warm
sessions. KARA remains materially resource-intensive: its physical-device
peak is about 0.8-0.95 GiB, and the emulator exceeds 1 GiB. This is compatible
with a clearly warned experimental tier, not a recommended/default tier.

## Worker And Playback

The current build reran a full worker export followed by the real
`PlaybackService` MediaSession. Playback adopted the exact completed cache and
exercised pause, seek, resume, and blend commands.

| Target | Full song (s) | First ready (s) | Frames | MediaSession | Timestamp drift |
| --- | ---: | ---: | ---: | --- | ---: |
| Galaxy S25 arm64 | 84.623 | 4.425 | 12,070,130 | passed | 0 ms |
| Galaxy S10 arm64 | 187.814 | 8.274 | 12,070,130 | passed | 0 ms |
| Galaxy S10 arm32 | 237.138 | 11.366 | 12,070,130 | passed | 0 ms |
| API 37 x86_64 AVD | 139.195 | 12.840 | 12,070,130 | passed | 0 ms |

Every output was finite, used `Vocals,Instrumental` semantics, promoted to
FLAC, remained exactly addressable, and was playable through the production
MediaSession.

## Audio Equivalence

The exported PCM16 stems were compared with the regenerated desktop ORT
reference. All four targets passed thresholds v2:

- exact frame count and 48 exact segment placements;
- maximum per-sample delta: 1 LSB;
- maximum absolute stem error: 1 LSB (`0.000030517578125`);
- minimum SNR: 66.98535-66.98565 dB, diagnostic only under thresholds v2;
- maximum join delta: 2 LSB (`0.00006103515625`);
- maximum actual-versus-reference reconstruction error delta: 2 LSB.

Different CPU targets did not produce byte-identical WAV files, but every
target was quantization-equivalent to the same desktop reference. Exact file
hash equality is therefore not used as a cross-ABI requirement.

## Cancellation

The isolated production-coordinator scenario canceled after work was admitted.
Every row used one session and retained an `Incomplete` cache:

| Target | Latency | Result |
| --- | ---: | --- |
| Galaxy S25 arm64 | 0 ms | passed |
| Galaxy S10 arm64 | 4 ms | passed |
| Galaxy S10 arm32 | 4 ms | passed |
| API 37 x86_64 AVD | 11 ms | passed |

The first x86_64 attempt was killed by an emulator startup ANR before the test
entered LiteRT. Memory pressure was zero. After a normal app-process warmup,
the identical cancellation scenario passed. This is retained as emulator
harness context, not classified as an inference failure.

## Decision

- KARA's objective CPU gates pass on arm64, arm32, and diagnostic x86_64.
- Pure x86 remains unsupported and the GPU execution profiles remain rejected.
- The intended product tier remains warned, CPU-only, and experimental.
- Do not change `validation.fullSong`, release maturity, or activation policy
  until representative listening, gesture-level UI review, and the exact final
  decision-build rerun are complete.
