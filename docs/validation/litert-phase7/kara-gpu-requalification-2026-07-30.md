# KARA FP32 GPU Requalification

This diagnostic reopens KARA FP32 after manual listening found no audible QNN
difference and a review showed that KARA had never received a full-song GPU
listening pass. It does not reopen KARA FP16 or change the bundled catalog.

## Prior Rejection

KARA FP32 was not rejected for non-finite output, a Coast Town failure, or a
known audible defect. The Phase 3 window matrix reported:

- Coast Town SNR: `109.351 dB`, passed;
- synthetic-input SNR: `108.484 dB`, below the frozen `109.0 dB` floor;
- synthetic maximum error: `0.00001212`, passed its error gate;
- CPU and desktop synthetic controls: `109.201 dB` and `109.638 dB`.

The conjunctive threshold was deliberately not relaxed during Phase 3, so the
catalog retained `androidGpu=rejected`. KARA FP16 remains rejected: its earlier
SNR was only about 28-30 dB.

## Frozen Inputs

- Test implementation commit:
  `a931081f35d6ce7b77856fe97897dc6e1b9f74dc`
- App APK SHA-256:
  `042f54a4722365b4314bc5c9e0687d6e5aab5f8f7b11635bdcb3d6b44c93098c`
- AndroidTest APK SHA-256:
  `fb2487450841bd47439e52051bc4a8f642685c3a5ef9ffc79987e10aeb441e74`
- Catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- Model and contract: `uvr_mdxnet_kara@2`, schema 2, TFLite SHA-256
  `4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64`
- Source WAV: 273.699 seconds, 12,070,130 stereo frames, SHA-256
  `e845e52aeeeb69be702d3a28d756eaf7a3137dbe5338ea50fb0ac3d8c4f9bd89`
- Devices: Galaxy S25 (`SM-S9310`), Android 15/API 35, and Galaxy S10
  (`SM-G9730`), Android 12/API 31; both arm64-v8a
- Runtime: LiteRT `2.1.5-bss.2`, OpenCL FP32,
  `kernelBatchSize=1`, `commandQueueWindowSize=1`
- Runner: `phase7-runner-v58`, in-process manual full-song worker

The AndroidTest-only override requires the exact model ID, contract, artifact
hash, arm64 ABI, bounded profile, in-process worker, and no fault injection. It
changes only the matching rejected FP32 qualification to candidate in memory.
Reports are marked `diagnosticOnly`; production and catalog behavior remain
unchanged.

## Full-Song Results

Both S25 GPU runs completed 48 inference invocations with no fallback. The
second run is the final committed S25 evidence; the first verifies
repeatability. The S10 row is the matching cross-device run.

| Run | Runtime | First ready | Peak PSS | PSS delta | Thermal start/peak/end |
| --- | ---: | ---: | ---: | ---: | --- |
| GPU v1 | 40.645 s | 3.994 s | 627.5 MiB | 502.1 MiB | 0/0/0 |
| GPU v2 | 40.737 s | 3.683 s | 629.9 MiB | 505.0 MiB | 0/0/0 |
| CPU control v1 | 88.392 s | 4.654 s | 870.5 MiB | 742.2 MiB | 0/0/0 |
| CPU control v2 | 113.272 s | 10.194 s | 872.0 MiB | 739.3 MiB | 3/3/2 |
| S10 GPU | 237.819 s | 14.706 s | 572.0 MiB | 452.3 MiB | 0/0/0 |

The v1 runs were launched before the test implementation was committed, so
their report source field still names `8f1cc0ef`. Their app and AndroidTest APK
hashes are byte-identical to v2; only v2 is used as the provenance anchor.

CPU v2 began already thermally throttled and is not used as the speed baseline.
Against the thermally neutral CPU v1, bounded GPU was about 2.17x faster and
reduced peak PSS by about 241 MiB. GPU memory moved substantially into graphics
PSS: its reported peak category values were 282.2 MiB graphics and 107.5 MiB
native, versus 644.6 MiB native and no graphics PSS for CPU v2.

S10 also completed all 48 GPU invocations without fallback or thermal pressure,
and produced WAV files byte-identical to S25 GPU. Its GPU runtime was 237.819
seconds, however, versus the earlier S10 CPU range of 174.9-189.8 seconds.
KARA FP32 is therefore GPU-correct on S10 but materially slower than CPU. GPU
admission for this model cannot be treated as a universal performance win.

## Audio Results

All three GPU runs produced byte-identical stems:

| Stem | GPU SHA-256 | CPU SHA-256 |
| --- | --- | --- |
| Vocals | `36418547d3a79022ddfed7d595e790498532fb49b1ce747ccbea0e91e03ae4da` | `3c685dfd8c57451c430e34414f3a0c7e6157e473f057b4af14f43cb8ec58b2d6` |
| Instrumental | `897b3e1705a219c558034609be5ba134875a85727b1e7baf0fbd671c2ec49ff0` | `8b7b02655c81e8833a3bb35442b3fba57b87c9452977947d83e704537bce8e07` |

Every output had the exact 12,070,130-frame length and finite samples. All
samples were quantization-equivalent to desktop ORT and to S25 CPU:

| Comparison | Stem | SNR | Maximum PCM16 delta |
| --- | --- | ---: | ---: |
| GPU vs ORT | Vocals | 66.985 dB | 1 |
| GPU vs ORT | Instrumental | 81.225 dB | 1 |
| GPU vs CPU | Vocals | 95.210 dB | 1 |
| GPU vs CPU | Instrumental | 109.447 dB | 1 |

All 47 joins were checked. Relative to CPU, GPU had zero join-step delta for
vocals and at most one PCM16 step for instrumental. The GPU-versus-CPU
reconstruction-error delta was at most one PCM16 step. Full local metrics and
the named/blind listening set are under
`MusicSourceSeparation/outputs/listening/coast-town-kara-fp32-gpu`.

KARA's instrumental output reaches PCM16 full scale, but GPU and CPU had the
same peak and full-scale sample counts. This is not a GPU-specific regression.

## Decision

- The original `108.484 dB` synthetic threshold miss is not evidence of an
  audible or full-song KARA FP32 GPU defect.
- S10 and S25 bounded OpenCL FP32 pass full-song numerical, cache completion,
  FLAC promotion, hydration, memory, and thermal checks; S25 also has a repeated
  byte-identical GPU run.
- Keep KARA experimental and CPU-only in the catalog for now. Do not reinterpret
  this AndroidTest override as production admission.
- Before promotion, complete blind listening, repeated allocation and
  cancellation/fallback tests, and foreground gesture frame-time review with
  KARA active. Define a performance admission rule or explicit model-specific
  backend choice so S10-class devices do not receive a slower GPU path.
- KARA FP16 remains rejected and is outside this requalification.
