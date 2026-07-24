# Phase 1 in-process host validation

Status: passed

Production-code revision tested:
`a8b59d0fc5339fd31e5173c9a39faf6b9b4644c3`

Final Phase 1 test revision before this report:
`d509102a`

Device: Samsung SM-S9310, Android 15 / API 35, arm64-v8a

Model: `UVR_MDXNET_3_9662` FP32

Artifact SHA-256:
`f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`

## Boundary result

Phase 1 inserts a runtime-neutral host after exact cache-run admission and
before the existing range executor. Model selection, source preflight, cache
lease ownership, manifest transitions, playback, foreground-service behavior,
wake locks, FLAC promotion, and hydration remain in the main process.

The host contract contains exact cache, contract, artifact, profile, source,
runtime, run, and process-generation identities. Progress, preparation,
segment, completion, pause, cancel, and failure events are typed Kotlin
serialization payloads with monotonic sequence numbers. No Binder,
`Parcelable`, Android Service, manifest component, SharedPreferences
key, or backup payload was added in Phase 1.

The engine rejects a mismatched descriptor, stale run/generation event, or
nonmonotonic event before applying it to progress or cache state. Tests also
cover live snapshots, cooperative pause/cancel, terminal closure, exact result
object passthrough, and one cache run-writer lease throughout a host run.

## Automated tests

The following commands passed:

- `./gradlew.bat :app:compileGithubDebugKotlin`
- `./gradlew.bat :app:testGithubDebugUnitTest`
- focused model-aware engine and runtime-facade tests

The full unit suite includes the cache identity/store/run coordinator, lease
registry, promotion, runtime selection, model catalog, preset, persistence,
and backup tests. Host selection and diagnostics are construction-time/runtime
data and therefore do not enter either backup allowlist.

## Exact CPU comparison

The current host path reran the frozen `coast_town_full_wav` fixture on
S25 with LiteRT CPU. Ratios use the S25 row in
`phase0/thresholds-v1.json`.

| Metric | Phase 0 | Phase 1 | Ratio/delta | Gate | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| First ready | 4,509 ms | 4,545 ms | 1.008x | at most 1.20x | passed |
| Full song | 108,096 ms | 95,280 ms | 0.881x | at most 1.15x | passed |
| Peak PSS | 992,248,832 | 996,530,176 | +4,281,344 | at most +134,217,728 | passed |
| Output frames | 12,070,130 | 12,070,130 | 0 | exact | passed |

The observed route remained `Window / WAV`. Process CPU was 312,074
ms, peak thermal status was 0, the exact cache completed, and playback
artifacts passed integrity and hydration checks.

All completed stem bytes match the pre-host S25 report:

| Stem | WAV SHA-256 | FLAC SHA-256 |
| --- | --- | --- |
| Vocals | `587eef6c2eb37de2ee9ec8c1eea50b53870ddc759f79d0598f8287412a42cef6` | `f6f78f032b06a5fa1a478c1102f82c7d050344ca82fad041fdb7f9a34ed2536a` |
| Instrumental | `ef001d330bfdb9d1173fd2311539d060f074d4710cc431c46b50266276d61692` | `17883cb3b8ea06232144bcba0491445497a63595f86d946deafe8c5a3bd6fdc1` |

## Source-format parity

The complete Phase 7 source-format corpus passed on S25 arm64 with the
production Auto backend. Every report passed its expected route, frame-count,
join-placement, finite-output, reconstruction, cache, promotion, and hydration
assertions.

| Fixture | Route | Profile | Frames | Vocals WAV SHA-256 | Instrumental WAV SHA-256 |
| --- | --- | --- | ---: | --- | --- |
| `format_aac_44100` | FullSong | - | 661500 | `f5616ac50296fa39b5f59d421fda3d0fc4374fd112c88c376abbb2e806f3fb1c` | `7d05b7c38c4bf3bcb9fc3435afb95ddfc650a24f98c74819817e10970ac473e0` |
| `format_flac_44100` | Window | FLAC 44.1 kHz | 661500 | `e37e3a18abe7ad009fd7a943e3665224e49d1981f548b23d06b5a7e5c2d08e7f` | `68edc621cd9f1a0b61e36f906586a0b51d3eb6d5d150a46e0fbbe7892daf8dc3` |
| `format_flac_48000` | FullSong | - | 661500 | `773aa6ab9af89196a434ba969599b2376c13bb213e8472d61ad1bebded69c729` | `85d506c786edd0ca3901293d6e3d84c655ae139f8acc821203e7331c6337c332` |
| `format_mp3_48000` | FullSong | - | 661500 | `a709eccbcc88de8a69c68067159e74fb620c3b8396e961c0686f0719e84658c1` | `d0f73be74485de10e74904375a2a9a94d7aa7b61b2c62637c70e102ef41bc5e5` |
| `format_mp3_gapless_44100` | Window | MP3 44.1 kHz | 661500 | `00e5bd92be4ba09939f6999ac15b46600fd560accec431c86ab253bb18e43275` | `d40bfa958cf9b62b036a1771280416b2a2c364c3fb7d2b5a45741c78b888a7c9` |
| `format_mp3_no_xing_44100` | Window | MP3 44.1 kHz no-gapless calibrated | 663551 | `9e04ed7b76bcc4d0c2dba3f80c3bf1f6ce67105af149b9bed959c2b3347cc4ae` | `8d9f409c8436d60e964d945eac99f44b18d3bc9de7b1f1960ff7fbc2e618f37f` |
| `format_vorbis_44100` | Window | Ogg Vorbis | 661500 | `42ba452bb2937537002da7f7b35e04041dd454b84e77f4bcf63c0832c142c7d8` | `d7222f0aabcd7a8187af672a0f63f816acb6f10fb7b5d866d65fb1f91273a0a3` |
| `format_wav_44100` | Window | WAV | 661500 | `f5fafdf572370c7d20815b96c9f537de9c1a623f15666bb77d4e56b04aa52b3f` | `4931ee8bb2c5fd236e4bffb61f1392e1d11215cf55e82ee05c7f6837f2c028f6` |
| `format_wav_wrong_extension` | FullSong | - | 661500 | `f5fafdf572370c7d20815b96c9f537de9c1a623f15666bb77d4e56b04aa52b3f` | `4931ee8bb2c5fd236e4bffb61f1392e1d11215cf55e82ee05c7f6837f2c028f6` |

The first continuous corpus command reported that instrumentation had crashed
after the AAC 44.1 kHz and FLAC 48 kHz reports were already written as passed.
Each was rerun after a clean acquisition. Both independent runs exited with
`OK (1 test)` and reproduced the same route and both WAV hashes. This
is recorded as a runner teardown anomaly, not silently treated as a clean
first run.

No source decoder, MP3 calibration, no-Xing handling, overlap guard, threshold,
or fallback-boundary file changed in Phase 1.

## Scheduling, background, and playback

The S25 prefetch scenario passed with exactly two requested and two ready
windows, stopped before full completion, retained the same next-song cache
identity across transition, and completed that cache after transition.

The S25 background scenario passed after sending Home once output became
playback-ready. Process importance remained 125 before and after Home, source
preparation occurred once, the exact cache completed, and the original
instrumentation process was retained. These values match the frozen Phase 0
background semantics.

A paired worker preserving the MediaStore row completed the full Coast Town
fixture, and the MediaSession playback test then adopted, played, paused, and
released the exact completed cache successfully.

Phase 1 adds no Service or process declaration and does not change
`PlaybackService`, the foreground worker coordinator, processing
leases, or wake-lock code. Consequently the standalone screen-off limitation
and absence of a standalone `SourceSeparationProcessing` wake lock
remain exactly as recorded in Phase 0. Independent CPU lifetime is still
deferred; Phase 1 does not claim to solve it.

## Raw ignored evidence

Generated reports remain under
`build/litert-inference-process-phase1`. Selected decision inputs:

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Exact S25 CPU WAV worker | 11,005 | `9481c1a96f58f4cfadeb0b14ec99f989b26b004c403b4a5710740ea16f8fc030` |
| S25 background | 4,166 | `7898599c6f422cf540ed696f9caeb0f1f1122ea5fc1a387bae34b54fefeb2e7f` |
| S25 MediaSession playback | 4,350 | `2d6505a1d529ab6a51cf74cdadfce7e09587424c8e2aa02476fdd2585a69c4e1` |
| S25 prefetch | 4,675 | `d455b9567562e5dac2ba26a0540179dc168b00ba31f9f17b7d37ff965387c21a` |
| Fresh AAC 44.1 kHz rerun | 7,928 | `585c9f1ba92a6eb62190e4823e7136fd38e3141262af0005d788c6fdcc3322cf` |
| Fresh FLAC 48 kHz rerun | 7,907 | `30efa542dc870a167578c8f4b69b7ea4af45695e0e5086b63e14e1604503188f` |

The nine corpus worker report hashes are:

| Fixture | Report SHA-256 |
| --- | --- |
| `format_aac_44100` | `6868e0af5c25a36773c88b9bc59ad8fd6ed8efe646de00a8285f05bf367c1a6b` |
| `format_flac_44100` | `e2bc93195184004d3b64edefb8c8d522babbc59348ba5013b6893389a570ab68` |
| `format_flac_48000` | `340383521dd23f767db3bcf8aa82e48912fd0b2fdbaa0a391f2b56c1cd8f76db` |
| `format_mp3_48000` | `7ef38cc4e467f59cccf8eb45bcd6e9ec9e23a262b0f539f001b72681a74af8ac` |
| `format_mp3_gapless_44100` | `e731728079ccd97f34a988efcc2cf5ff77366e88fa822a0eed05a04aedad1658` |
| `format_mp3_no_xing_44100` | `93a3c2737479a9b24d6667cc67d10a6f2542be7f7e52fd0b978b91301c861708` |
| `format_vorbis_44100` | `3f90d0b9bf92512602601a1077b5edabcaaa7991bad42a5941518d2722ceaee0` |
| `format_wav_44100` | `098f9a42fab2426badf270745f36cf8383adba20524d596978b6ccfe4d4ae90a` |
| `format_wav_wrong_extension` | `ca0a4af7d0e591e80e1cc9d18db33005b2f825d56af83dc53e612b375e7d1de0` |

## Exit decision

Phase 1 passes. Production remains `InProcess` and uses the original
`MdxSourceSeparationModelAwareRangeExecutor`. The host refactor did not
change output bytes, source routes, cache ownership, playback behavior, memory
policy, foreground policy, or background policy. Phase 2 may now add an
internal bound-remote prototype behind this boundary.
