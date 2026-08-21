# S10 Long-song AAC Seek Matrix

Date: 2026-08-19
Device: Samsung `SM-G9730`, API 31, `arm64-v8a`
Codec: `c2.android.aac.decoder` (`audio/mp4a-latm`)
Test APK: current `githubDebugAndroidTest` build on `experiment/source-separation-aac-fast-seek`

## Scope

This is a playback data-plane test using six real AAC-LC stem files, each 210.023 seconds, 44.1 kHz stereo. The source stems were rendered previously from one six-stem separation and encoded at 160 kbps AAC-LC. The test uses the existing `SourceSeparationStemPlaybackEngine` and the product AAC profile: `previous` sync, 1,024-frame seek block, six-way first-block parallelism, and four seeded AAC access units.

It does not run HTDemucs inference, and it does not claim a full UI, AudioTrack/speaker, foreground/background, or MediaSession black-box qualification. It measures the decoder and separation playback data plane that supplies those layers.

## Seek Matrix

The midpoint was 105.011 s. Each case seeks from that point to the indicated relative position. Warm runs use one persistent six-decoder session; cold runs recreate the six sources and engine for every sample.

| Direction | Delta | Warm first PCM P50/P95 | Cold first PCM P50/P95 | Warm samples | Cold samples |
| --- | ---: | ---: | ---: | ---: | ---: |
| Near future | +5 s | 87 / 116 ms | 97 / 100 ms | 8 | 4 |
| Far future | +60 s | 77 / 115 ms | 93 / 98 ms | 8 | 4 |
| Near past | -5 s | 102 / 129 ms | 55 / 103 ms | 8 | 4 |
| Far past | -60 s | 105 / 116 ms | 62 / 71 ms | 8 | 4 |

The resume-waterline latency was identical to first-PCM latency in every aggregate row because the engine's one-block seek waterline was reached by the first successful data-plane read.

## Correctness And Stability

- All 48 seeks produced PCM within the five-second guard.
- All 48 seeks reached the resume waterline.
- Added underruns: `0` in every case.
- Added low-water events: `0` in every case.
- All six stems selected the same physical AAC anchor for each seek; inter-stem anchor spread was `0` frames in every sample.
- Anchor offsets were all negative, as expected for `previous` sync. Across the matrix they ranged from approximately `-118` to `-798` frames (`-2.7` to `-18.1` ms at 44.1 kHz).
- The offset depends on the AAC sync point, not on the distance travelled. The far seeks did not show a distance-proportional penalty.

## Interpretation

For S10, seek distance itself is not the dominant cost once the AAC extractor jumps to a sync point. The practical data-plane target for this profile is roughly 80-130 ms P95 in the tested long-song conditions. Near-past warm seeks were the slowest row in this run, but the cold samples were not slower than the other directions, so this should be treated as scheduling variance rather than a directional rule.

The current product policy can therefore keep accepting an intentional previous-anchor offset instead of decoding from the exact requested frame. The measured maximum offset remains well inside the existing 4,096-frame bound. Audible resume still depends on the outer mixer/output notification path, which was not included in this direct engine matrix.

## Reproduction

Instrumentation method:

`com.mardous.booming.separation.audio.SourceSeparationAacLongSongSeekDeviceTest#longSongSixStemSeekDistanceMatrix`

Raw JSON, including device fingerprint, per-stem SHA-256, every sample, anchor trace, and codec phase timings:

`aac-long-s10-seek-report-2026-08-19.json`

The six fixture hashes are recorded in that JSON. The fixture is intentionally ignored under `build/aac-long-s10-fixture`; it is not a product asset.
