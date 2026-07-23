# Phase 7 Preliminary Results

These results are local, pre-promotion evidence for the 9662 FP32 contract
`uvr_mdxnet_3_9662@2` and LiteRT 2.1.5. The corresponding reports are ignored
working artifacts. They are identified here by runner ID so the evidence can
be reproduced without storing audio in this repository.

## CPU Matrix

| Target | Profile | Full song | Peak PSS delta | Result |
| --- | --- | ---: | ---: | --- |
| Galaxy S10 arm64 | `cpu-default-fp32-v1` | 228180 ms | 781.5 MiB | passed |
| Galaxy S10 arm32 | `cpu-default-fp32-v1` | 242556 ms | 569.9 MiB | passed |
| Galaxy S25 arm64 | `cpu-default-fp32-v1` | 123740 ms | 748.0 MiB | passed |
| API 26 pure x86 | `cpu-default-fp32-v1` | 156108 ms | 565.5 MiB | worker passed; repeated-session lifecycle rejected |
| API 37 x86_64 | `cpu-default-fp32-v1` | 175562 ms | 869.9 MiB | passed |

The XNNPACK transient diagnostic reduced peak PSS on the supported arm64 and
x86_64 targets, but it remains a diagnostic profile. Representative full-song
results were 199472 ms / 414.0 MiB on S10 arm64, 96164 ms / 454.5 MiB on S25
arm64, and 201949 ms / 618.5 MiB on x86_64. The default CPU thread formula
remains provisional; neighboring-count measurements are retained for review.

## Auto GPU Matrix

| Target | Profile | Backend used | Full song | Peak PSS delta | Result |
| --- | --- | --- | ---: | ---: | --- |
| Galaxy S10 arm64 | `gpu-auto-fp32-v1` | `LiteRtGpu` | 205229 ms | 411.2 MiB | passed |
| Galaxy S25 arm64 | `gpu-auto-fp32-v1` | `LiteRtGpu` | 37059 ms | 504.5 MiB | passed |

The S25 run `s25-arm64-9662-auto-full-wav-v1` was compared with the desktop
ORT reference. Frame counts were exact; every PCM16 sample was within one LSB;
the largest measured join difference was two LSB. The S25 MediaSession run
`s25-arm64-9662-auto-full-wav-playback-v1` adopted the exact completed cache,
paused, sought, resumed, and applied blend commands successfully.

These successful GPU rows are preliminary. They use threshold revision v1 and
must be rerun with v2 after the final app/catalog decision commit.

## Fallback Matrix

The production worker uses the same cache identity before and after fallback.
Each run proved one GPU session was closed before the CPU session was created.

| Target | Failpoint | Backend used | Fallback stage | Result |
| --- | --- | --- | --- | --- |
| Galaxy S10 arm64 | setup | `LiteRtCpu` | `GpuSetup` | passed |
| Galaxy S25 arm64 | probe | `LiteRtCpu` | `GpuProbeValidation` | passed |
| Galaxy S25 arm64 | invocation after ready windows | `LiteRtCpu` | `GpuInvocation` | passed |

The invocation-after-ready run observed a ready window before injection and
completed the full track on CPU. Cancellation is not treated as a fallback.

## Lifecycle Matrix

The sequential `single-use` lifecycle scenario passed on both S10 arm64 and
S25 arm64 with Auto: pause/resume, a seek from ready into pending work, and
cancellation. The S25 report is
`s25-arm64-9662-auto-lifecycle-short-v2`; its retained completed manifest
records `LiteRtGpu`. The shared-reusable mode remains diagnostic CPU evidence
and is not used for Auto promotion.

## Open Evidence

- Rerun all promotion rows with `phase7-thresholds-v2`.
- Complete three warm repetitions, thermal/resource measurements, and playback
  coverage on both physical devices.
- Compare the S10 GPU export with the desktop reference after freeing or
  staging sufficient local storage.
- Test KARA, model switching, background continuation, next-song prefetch,
  and the source-format corpus.
- Keep pure x86 ordinary-playback support, but mark source separation
  `unsupported` until a lifecycle-safe runtime strategy exists.
