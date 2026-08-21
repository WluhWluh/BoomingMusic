# S25 Booming SS AAC Application Seek

Date: 2026-08-19
Device: Samsung SM-S9310 (Android 15)
Path: `PlaybackService` -> `MediaController` -> `SourceSeparationMixAudioProcessor` -> six AAC decoders
Fixture: 210.023 s, 44.1 kHz, six AAC-LC 160 kbps stems
Mode: direct continuous `MediaController.seekTo()` while playback was active; four repetitions per direction

## Results

| seek | AAC first output p50/p95 | mixed output ready p50/p95 | playing position advanced p50/p95 |
| --- | ---: | ---: | ---: |
| +5 s | 10 / 14 ms | 24 / 33 ms | 94 / 114 ms |
| +60 s | 12 / 28 ms | 18 / 35 ms | 81 / 87 ms |
| -5 s | 7 / 8 ms | 16 / 30 ms | 70 / 81 ms |
| -60 s | 6 / 6 ms | 15 / 17 ms | 90 / 94 ms |

All 16 samples completed. The data plane reported `underruns=0` and `lowWaterEvents=0`. Controller positions were within about 50 ms of the requested targets after the measured advance point.

The earlier pause-before-seek smoke also completed successfully, with application resume observations around 105-138 ms. The repeated continuous run is the stronger result for normal playback.

## Interpretation

The AAC decoder and seek-anchor path are not producing a hundreds-of-milliseconds delay on S25: decoder first output was 5-28 ms. The additional time is in mixer preroll, Media3 scheduling, and audio-output progression, measured here at 67-114 ms for position advancement.

This test intentionally starts at the `MediaController` boundary. It does not include the UI seek bar, gesture settling/debouncing, view-model command dispatch, or a physical Bluetooth/audio-route change. A user-observed delay of several hundred milliseconds therefore still warrants a separate UI-to-controller trace and an AudioTrack/render-thread timestamp before changing AAC seek parameters.

Raw reports:

- `aac-long-s25-product-continuous-r4-seek-2026-08-19.json`
- `aac-long-s25-product-continuous-seek-2026-08-19.json`
