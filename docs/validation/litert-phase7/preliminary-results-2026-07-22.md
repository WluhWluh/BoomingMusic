# Phase 7 Preliminary Results

These results are local, pre-promotion evidence for the 9662 FP32 contract
`uvr_mdxnet_3_9662@2` and LiteRT 2.1.5. Performance rows produced before the
v2 threshold remain historical; the later lifecycle rows use
`phase7-thresholds-v2` and runner v10. The corresponding reports are ignored
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
| Galaxy S10 arm64 | probe | `LiteRtCpu` | `GpuProbeValidation` | passed |
| Galaxy S10 arm64 | invocation after ready windows | `LiteRtCpu` | `GpuInvocation` | passed |
| Galaxy S25 arm64 | setup | `LiteRtCpu` | `GpuSetup` | passed |
| Galaxy S25 arm64 | probe | `LiteRtCpu` | `GpuProbeValidation` | passed |
| Galaxy S25 arm64 | invocation after ready windows | `LiteRtCpu` | `GpuInvocation` | passed |

The invocation-after-ready run observed a ready window before injection and
completed the full track on CPU. Cancellation is not treated as a fallback.

## Lifecycle Matrix

The sequential `single-use` lifecycle scenario passed on S10 arm64, S25 arm64,
and API 37 x86_64: pause/resume, a seek from ready into pending work, and
cancellation. Auto passed on both physical devices and retained `LiteRtGpu` in
the completed manifests. The shared-reusable mode remains diagnostic CPU
evidence and is not used for Auto promotion.

The isolated default-thread cancellation matrix also passed on S25 arm64, S10
arm64, S10 arm32, and x86_64. Measured coordinator latency was 1-9 ms against
the 30,000 ms threshold; every cache remained `Incomplete` and each run created
one CPU session.

Production model, MediaSession, and queue lifecycle evidence is:

| Target | Active-model switch | Playback-time switch | Home/background full WAV | Next-song prefetch |
| --- | --- | --- | --- | --- |
| Galaxy S10 arm64 | 9662 to KARA and KARA to 9662 passed | 9662 lease retained after KARA selection | 187474 ms; first ready 8959 ms | two windows in 12433 ms; same key completed after transition |
| Galaxy S25 arm64 | 9662 to KARA passed | 9662 lease retained after KARA selection | 41564 ms; first ready 2828 ms | two windows in 2809 ms; same key completed after transition |

The switching tests prove that an admitted run retains its original model and
contract after selection changes, while the next run creates a second exact
cache entry. Playback-time switching keeps the original read lease through
pause, seek, resume, and blend. Prefetch uses the frozen synthetic mix as the
current song and the 12-second Coast Town WAV as the next song, so content-based
cache identity cannot collapse the two rows.

An initial S25 background attempt was force-stopped by Samsung MARs after the
30-second display timeout. Runner v10 temporarily extends and then restores the
screen timeout, isolating Home/background continuation from lock-screen OEM
policy. The S10 startup also exposed a service-restoration race; the bounded
media preparation retry needed two attempts in the v10 run and then completed
normally.

Pure API 26 x86 preflight now rejects 9662, KARA, and HQ4 as `unsupported`
without any native allocator call. This preserves ordinary x86 playback while
preventing the lifecycle-unsafe source-separation route.

## Resource and Thread Matrix

One cold and three warm default-CPU repetitions passed on S25 arm64, S10
arm64, S10 arm32, and the diagnostic x86_64 AVD. The corresponding Auto matrix
passed four GPU repetitions on both physical devices. S25 GPU was consistently
faster and lower-PSS; S10 GPU timing varied around CPU while remaining roughly
280-315 MiB lower-PSS. The four-thread default is retained because three
threads were 8-10 percent slower on S10 even though they matched four threads
on S25. See
[`resource-results-2026-07-24.md`](resource-results-2026-07-24.md) for the
complete ranges and decisions.

## Open Evidence

- Rerun the final promotion rows from the frozen decision commit; current local
  reports still identify the preceding app commit.
- Complete representative listening/UI coverage on both physical devices.
- Compare the S10 GPU export with the desktop reference after freeing or
  staging sufficient local storage.
- Complete KARA CPU full-song, cancellation, playback, resource, and listening
  rows on every ABI for which it may become selectable.
- Complete HQ4 no-allocation preflight, supplemental x86 runtime failure, and
  dual-runtime size-inventory rows.
- Keep pure x86 ordinary-playback support, but mark source separation
  `unsupported` until a lifecycle-safe runtime strategy exists.
