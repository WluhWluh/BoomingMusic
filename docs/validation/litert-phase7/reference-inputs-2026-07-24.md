# Phase 7 Reference Inputs

Status: the Phase 7 fixture and desktop-reference hashes are frozen in
[`reference-inputs-v1.json`](reference-inputs-v1.json).

The manifest binds `phase7-fixtures-v2` to the exact companion-repository
revision, FFmpeg build, 9662 ONNX source artifact, reference scripts, DSP
parameters, canonical PCM inputs, and output stem hashes. Reference audio and
model weights remain outside this repository.

## Coverage

Three PCM fixtures have direct desktop ORT references:

- the 273.699-second representative full track;
- the existing 12-second vocal-entry fixture; and
- the 3-second synthetic mixture used as the independent queue/prefetch
  control.

The nine generated source-format fixtures each have a canonical 44.1 kHz,
stereo, PCM16 decode and matching 9662 vocals/instrumental reference. The set
covers WAV, FLAC, Vorbis, gapless and no-Xing MP3, AAC, 48 kHz resampling, and
the wrong-extension fallback. Every entry records source duration, sample rate,
channels, codec/container, redistribution status, expected output frame count,
and stem semantics.

The representative full-song numerical gate uses the PCM WAV fixture. The
local MP3 copy remains a worker/resource source, but it is not substituted for
that stable PCM reference.

## Reproduction

Check out
[`WluhWluh/MusicSourceSeparation`](https://github.com/WluhWluh/MusicSourceSeparation)
at revision `58fcedc9005a9dbfb2de728b66a21de8a0a5d7b5`, regenerate the source-format
corpus, and verify its hashes against `fixtures-v2.json`.

For each format fixture, decode and fit the frozen presentation timeline with
the pinned FFmpeg 8.1.1 build:

```text
ffmpeg -hide_banner -loglevel error -nostdin -y -i <source> \
  -map_metadata -1 -vn \
  -af aresample=44100,apad,atrim=end_sample=<expectedOutputFrameCount> \
  -ar 44100 -ac 2 -c:a pcm_s16le <canonical-input.wav>
```

Run the companion desktop pipeline with the canonical 9662 ONNX SHA-256
`e02220e80d8253f4c2209f8924298b2b686bbdf2868b788ff5500fb9bd94aadc`,
`dimF=2048`, `nFft=6144`, no denoise, output scale `1.035`, and model-output
stem `vocals`. The exact command template and script hashes are stored in the
JSON manifest.

## Interpretation

The PCM fixtures are the numerical parity oracles governed by Phase 7
thresholds v2. Canonical FFmpeg decodes of compressed formats are reproducible
desktop anchors, but they are not byte-equivalence oracles for Android
MediaCodec behavior.

The exploratory S25 exports showed why that distinction matters: codec,
presentation-timeline, local-window, and resampling choices can all contribute
to a direct desktop delta. Those observations do not change the existing
window-decode policy. Any later policy adjustment requires a separately scoped
same-device window-versus-full-song study plus representative listening; it
must not be inferred from the FFmpeg comparison alone.
