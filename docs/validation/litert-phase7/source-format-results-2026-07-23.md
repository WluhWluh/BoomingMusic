# Phase 7 source-format validation

Status: the production-worker source-format gate passed on 2026-07-23.

This matrix used `phase7-fixtures-v2`, `phase7-runner-v11`, LiteRT 2.1.5,
`UVR_MDXNET_3_9662` FP32, and the generated corpus from
`WluhWluh/MusicSourceSeparation@58fcedc9005a9dbfb2de728b66a21de8a0a5d7b5`.
The model artifact SHA-256 was
`f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`.

Each row ran through the normal model-aware foreground worker and v2 cache,
then verified the exact source fingerprint identity, final decode route,
output sample rate and frame count, contiguous segment joins, completed-cache
playability, FLAC promotion, hydration, and cache integrity. Raw reports and
audio remain under ignored `build/phase7-validation` output.

## Targets

| Target | Android | Backend | Result | Diagnostic worker range |
| --- | ---: | --- | --- | ---: |
| Galaxy S25 arm64 | 35 | production Auto, concrete GPU | 9/9 passed | 4.2-32.3 s |
| Galaxy S10 arm64 | 31 | production Auto, concrete GPU | 9/9 passed | 15.1-18.5 s |
| Galaxy S10 armeabi-v7a | 31 | test-only CPU baseline | 9/9 passed | 17.4-20.6 s |
| API 37 x86_64 emulator | 37 | test-only CPU baseline | 9/9 passed | 10.7-15.9 s |

Pure x86 was not run: its production policy is ordinary playback only, and
source separation fails closed before native allocation.

## Decode routes

| Fixture | Phone/arm route | Output frames | Fallback |
| --- | --- | ---: | --- |
| WAV 44.1 kHz | Window / WAV | 661,500 | none |
| FLAC 44.1 kHz | Window / FLAC 44.1 kHz | 661,500 | none |
| Ogg Vorbis 44.1 kHz | Window / Ogg Vorbis | 661,500 | none |
| MP3 44.1 kHz with gapless metadata | Window / MP3 metadata-quantized | 661,500 | none |
| MP3 44.1 kHz without Xing metadata | Window / no-gapless calibrated | 663,551 | none |
| AAC/M4A 44.1 kHz | Full song | 661,500 | unsupported MIME profile |
| FLAC 48 kHz | Full song | 661,500 | non-44.1 kHz FLAC |
| MP3 48 kHz | Full song | 661,500 | non-44.1 kHz MP3 |
| WAV content with `.wave` suffix | Full song | 661,500 | WAV MIME without `.wav` name |

All rows used 44.1 kHz stereo output and joins at frames 254,976 and 509,952.
The no-Xing MP3 intentionally preserves its 15.0465-second encoded timeline:
without delay/padding metadata there is no authoritative shorter presentation
timeline to recover.

The API 37 x86_64 extractor reports both FLAC fixtures as `audio/raw`.
Therefore those two rows use the safe full-song path and the raw-MIME filename
fallback instead of claiming FLAC window support. This ABI-specific route is
encoded explicitly in the fixture contract; phone and arm32 expectations stay
strictly on `audio/flac`.

## Fixes found

- Gapless MP3 window output originally retained 2,052 encoder-delay/padding
  frames. Source frame counts now remove complete platform gapless metadata.
- AAC behavior differed across API levels. API 31 omitted gapless metadata,
  API 35 exposed an encoded duration plus `delay=1024`, and API 37 exposed an
  already-trimmed duration plus the same delay. The decoder now reads the MP4
  `moov/mvhd` presentation timeline and stores an explicit presentation frame
  count, avoiding both missing and duplicate trim.
- Full-song decode now fits resampled PCM to the inspected presentation frame
  count, removing four-frame codec-buffer tails while retaining exact output
  duration.
- Runner v11 fixes repeated Samsung instrumentation user selection, Base64-
  transports expected strings containing spaces, and stages source files in
  app-private storage before MediaStore registration.

The initial graph was committed at `564c6123`. Product fixes were committed as
`2fb2cc32`, `3a61d358`, and `370147ba`; the final ABI expectation contract is
`d0d69919`. Every affected AAC row and both x86_64 FLAC rows were rerun against
`d0d69919`. Unaffected phone rows retain their `564c6123` reports, and
unaffected x86_64 rows retain `3a61d358`; later changes are MIME-specific AAC
handling or host-side expectation selection and do not execute in those rows.

## Remaining scope

This completes the format-route worker gate, not all Phase 7 audio evidence.
Per-format desktop ORT stem references and full digital stem comparisons have
not been generated, so the broader fixture-freeze item remains open. The
representative full-track reference, listening/UI pass, repeated resource and
thermal matrix, and final catalog decision also remain separate gates.
