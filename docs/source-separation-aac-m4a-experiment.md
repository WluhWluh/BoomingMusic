# AAC-LC/M4A separated-cache experiment

Status: experimental, branch `experiment/source-separation-aac-fast-seek`.

This branch evaluates a completed-song AAC-LC/M4A artifact beside the existing
indexed FLAC artifact. The two promoted formats are mutually exclusive within
one cache entry. AAC is still playback-only. The lossless WAV remains available
while an older playback session leases it, then follows the same deferred,
lease-aware cleanup path as FLAC after the promoted source is adopted.

## Profile

- Android platform `MediaCodec` AAC-LC encoder and decoder.
- 44.1 kHz, stereo, 16-bit PCM input.
- 160,000 bit/s target bitrate, matching the BandBuddy reference profile.
- One persistent encoder per stem for the complete song; never one encoder per
  inference window.
- MPEG-4/M4A container with `audio/mp4a-latm` track.
- The default encoder preserves the non-negative platform PTS timeline
  (`timestampOffsetFrames=0`). A separate 2,048-frame BandBuddy comparison
  constant exists, but is not the default because negative/edit-list behavior
  differed across Android devices.
- `KEY_ENCODER_DELAY` and `KEY_ENCODER_PADDING` are recorded when the platform
  exposes them; otherwise they remain unknown rather than being asserted as
  2,048/0. The encoded sample table and a complete decoder probe are also
  checked before promotion is marked validated.

## Playback policy

AAC stems use the same bounded stem playback worker as WAV and FLAC. Each source
keeps a `MediaExtractor` and `MediaCodec` alive across 4,096-frame blocks.

On seek the source flushes an already-primed decoder and seeks to the previous
AAC sync sample. Initial open avoids an unnecessary pre-input flush.
It publishes the first decoded block immediately from that physical anchor;
the logical cursor remains at the requested transport position. This deliberately
prioritizes fast resume over exact seek. The source rejects an anchor farther
than the manifest's `maxAnchorOffsetFrames` (currently 4,096 frames, 92.9 ms at
44.1 kHz), and emits the requested/anchor offset to the debug trace. The
1,024-frame `seekQuantumFrames` is packet granularity, not the maximum bound.
WAV/FLAC exact-seek behavior is unchanged.

The experiment deliberately keeps `SEEK_TO_PREVIOUS_SYNC`. On these M4A files
each AAC sample is approximately 1,024 frames, so `CLOSEST_SYNC` or
`NEXT_SYNC` changes only the sign of a sub-packet offset; it does not remove
the decoder flush or first-packet decode. They remain opt-in diagnostic ideas,
not the product fast-resume policy.

The manifest's separated output frame count is the logical frame-count authority.
M4A duration is diagnostic only. A short final decoder block is zero-padded only
at the declared song tail; an unexpected mid-song short read must fail the AAC
source and allow the caller to fall back to FLAC or a still-leased WAV sibling.

## Promotion transaction

1. Validate the completed WAV cache.
2. Encode every stem into `promotion-staging/*.m4a`.
3. Validate each M4A container and track geometry.
4. Atomically copy all M4A files into `completed/`.
5. Write one manifest update covering the complete AAC stem set.
6. Remove staging only after the manifest is durable.
7. Register the completed WAV stems for cleanup; active playback leases may defer
   deletion until the AAC source has been adopted.

An incomplete or canceled promotion leaves the WAV cache authoritative and does
not register it for compression cleanup. A cache cannot contain a mixture of
FLAC and AAC promoted stems.

## Device gate

The synthetic gate is implemented in
`app/src/androidTest/.../SourceSeparationAacMediaCodecDeviceTest.kt` and its
reports are checked in as the original baseline and the post-fallback rerun:

- `validation/aac-device-gate-2026-08-18-s25.json`
- `validation/aac-device-gate-2026-08-18-s10.json`
- `validation/aac-device-gate-2026-08-18-s25-rerun.json`
- `validation/aac-device-gate-2026-08-18-s10-rerun.json`

The 44.1 kHz, 3-second ramp/impulse gate passed on both devices before and
after the runtime fallback change. The post-change run found the
same platform codec pair (`c2.android.aac.encoder` and
`c2.android.aac.decoder`) and these seek timings:

| device | warm one-source P95 | cold reopen P95 | six-source serial P95 | decoded-length delta |
| --- | ---: | ---: | ---: | ---: |
| S25 / SM-S9310 / API 35 | 34 ms | 29 ms | 201 ms | +2,551 frames |
| S10 / SM-G9730 / API 31 | 44 ms | 48 ms | 254 ms | -521 frames |

The length delta is bounded by the logical manifest frame count and tail
padding policy; it is not treated as a timing failure. The synthetic trace saw
previous-sync offsets of 0 to -773 frames (0 to -17.5 ms), within the 1,024
frame AAC packet quantum. The rerun used APK SHA-256
`A4D11E8B286194E3F83277856A9CE07F162D762009847A8EBABFC7F88F4434D9` and
androidTest APK SHA-256
`211DCBFBE2DEB2FC38915F317B6E88F9038F1F503CF391602189FF090E0FC1BE`.
These are short synthetic measurements, not a three-minute product
qualification.

## Fast-resume experiment

The playback engine now treats AAC as an explicitly parallel-capable source.
After a seek, the first AAC packet-sized block (1,024 frames by default) is
read concurrently, then the worker returns to 4,096-frame steady-state blocks.
This keeps the first mixed PCM notification early without forcing the normal
audio thread to consume a long sequence of small blocks. The current default
is six independent source workers. If a platform codec rejects concurrent
flush/decode, all sources are re-anchored and the block is retried serially.
WAV/FLAC factories do not opt into this path.

During that short seek epoch, an AAC-only mixer may wait up to 75 ms in 1 ms
slices for the next block when the first packet has already been consumed.
This prevents Media3 from dropping a larger clock buffer while the six
decoders are filling. The wait is disabled as soon as the seek waterline is
granted and is never used for steady-state, WAV, or FLAC reads. A partial first
read retains the unconsumed Media3 input buffer and is traced as
`mixed-engine-partial`.
The seek-ready waterline remains one full 4,096-frame steady-state block even
though the first published block is 1,024 frames; this avoids repeatedly
entering the partial path on slower devices such as S10.

The engine now preserves the `Ready` state after a non-zero partial AAC seek
read while the seek epoch is still pending. Previously that read immediately
changed `Ready` back to `Buffering`, which could make PlaybackService pause
again after the first mixed PCM block had already been produced. A zero-frame
read still enters `Buffering`, so the existing bounded wait and underflow path
remain intact. This change is limited to factories that explicitly opt into
partial AAC seek reads; WAV and FLAC behavior is unchanged.

The decoder's first post-seek decode now uses a bounded seed burst of at most
four AAC access units before waiting for PCM. Once that seed is exhausted, the
source returns to one non-blocking input dequeue per output poll; a decoder that
needs deeper priming is therefore still fed without an unbounded burst. The
steady output poll timeout remains 10 ms. The source trace records
`extractorSeekUs`, `codecFlushUs`, `firstInputPtsUs`, and `queuedInputs` so a
device report can distinguish container seek, flush, input starvation, and
codec output scheduling. This changes neither the AAC sync mode (`PREVIOUS`),
the 1,024-frame first block, nor PCM ordering.

The synthetic gate rejected a separate 2 ms first-output poll experiment: it
did not improve S10 and made S25 tail latency worse. The bounded seed burst was
therefore retained as the only decoder change. On the same platform codec pair
(`c2.android.aac.decoder`), the seed-burst gate measured:

| device | six-source serial P50/P95 | workers=6 P50/P95 | processor first-mixed P50/P95 |
| --- | ---: | ---: | ---: |
| S25 / SM-S9310 / API 35 | 15 / 22 ms | 10 / 21 ms | 15 / 20 ms |
| S10 / SM-G9730 / API 31 | 90 / 111 ms | 90 / 111 ms | 103 / 138 ms |

The final reports are `validation/aac-device-gate-s25-input-seed4-final.json`
and `validation/aac-device-gate-s10-input-seed4-final.json`. Both passed the
synthetic codec gate; each also ran a four-position PCM parity check against a
seed-burst-1 decoder and recorded `pcmBytesEqual=true`. The traces include the
phase timings and bounded-seed metadata. Earlier intermediate runs remain as
`validation/aac-device-gate-*-input-seed4.json`. A 2 ms comparison is retained only as
`validation/aac-device-gate-*-input-seed4-poll2ms.json` and is not a product
profile.

The product-sized 30-second fixture was then run with ten extra seeks. The
seed burst preserved zero seek underruns and zero WAV fallback while reducing
first mixed PCM latency relative to the grace-only run:

| device | grace-only P50/P95 | seed-burst P50/P95 | data-plane ready P50/P95 |
| --- | ---: | ---: | ---: |
| S25 / SM-S9310 | 36 / 48 ms | 18 / 46 ms | 61 / 181 ms |
| S10 / SM-G9730 | 160 / 188 ms | 104 / 159 ms | 324 / 545 ms |

Evidence is in `validation/aac-fast-s25-r19-input-seed4.json` and
`validation/aac-fast-s10-r19-input-seed4.json`; the grace comparison is in the
corresponding `r18-grace-default` reports. The S25 P95 data-plane value has a
single 181 ms sample in this ten-seek run, so it should not be read as a
guarantee of renderer start time. The first-mixed value is the relevant fast
resume signal; all runs remained thermal status 0 and had `seekUnderruns=0`.

The debug-only `aac_seek_block_frames` setting permits an explicit 512-frame
first block experiment. On the same 30-second six-stem cache, with
`aac_seek_ready_frames` set to 512, 512 was not a useful profile:

| device | first mixed P50/P95 | data-plane/player-ready P50/P95 | seek underruns |
| --- | ---: | ---: | ---: |
| S25 / SM-S9310 | 37 / 46 ms | 108 / 111 ms | 0 |
| S10 / SM-G9730 | 174 / 183 ms | 2,830 / 4,344 ms | 0 |

The corresponding 1,024-frame first-block run measured 38 / 42 ms mixed and
108 / 112 ms data-plane/player-ready on S25, and 143 / 161 ms mixed and
1,546 / 2,348 ms on S10. The 512-frame profile therefore remains an explicit
diagnostic setting, not a product recommendation. After the experiment both
devices were restored to `aac_seek_ready_frames=0`,
`aac_seek_block_frames=1024`, and `aac_seek_mode=previous`.

The complete six-stem reports are
`validation/aac-fast-s25-r16-ready1024-block1024.json`,
`validation/aac-fast-s10-r16-ready1024-block1024.json`,
`validation/aac-fast-s25-r17-ready512-block512.json`, and
`validation/aac-fast-s10-r17-ready512-block512.json`. All four runs completed
with zero seek underruns and thermal status 0 throughout the short matrix.

When all stems in a prepared session are AAC, the mixer also disables the
otherwise conservative 400 ms mixed-output notification preroll. The existing
120 ms output unmute delay remains, so this changes the earliest audible
resume without changing lossless seek behavior or the normal WAV/FLAC policy.

The production-sized synthetic gate compares serial and bounded parallel
first-block latency on the same six persistent AAC sources:

| device | serial P95 | workers=2 | workers=3 | workers=6 |
| --- | ---: | ---: | ---: | ---: |
| S25 / SM-S9310 / API 35 | 237 ms | 136 ms | 103 ms | 63 ms |
| S10 / SM-G9730 / API 31 | 349 ms | 232 ms | 189 ms | 140 ms |

After the serial-retry fallback, default-six change, and zero-preroll AAC mixer
path, the final APK rerun was S25 `261 / 110 / 67 ms` and S10
`356 / 194 / 145 ms` (serial / workers=3 / workers=6). The complete processor
path from seek through the first mixed PCM block was 78 ms P95 on S25 and
149 ms P95 on S10; all 25 warmup/measured iterations per device emitted their
mixed-output notification on the first block. The variation is expected from
a short synthetic codec run; the relative improvement remained present.

The direct source-only 2,048-frame loop in the final run measured 204 ms on
S25 and 263 ms on S10 for six serial sources; it is not interchangeable with
the engine's 4,096-frame seek-ready metric. Evidence is in
`validation/aac-fast-seek-engine-s25-final.json` and
`validation/aac-fast-seek-engine-s10-final.json` (the earlier
`aac-fast-seek-engine-s25.json`/`s10.json` files retain the first matrix
run). Both final runs used
`c2.android.aac.decoder`, completed 20 measured seeks per worker setting, and
returned a passing instrumentation result. They are still synthetic and short;
no claim about long-song thermal stability or every vendor codec follows from
these numbers. The pre-product synthetic gate APK SHA-256 is
`0AA746EF76AA9CB2DC2646B0B02A026A0952AD27F79100BBEC5E13E35B503D92`.
The current bounded-seed fast-resume source APK SHA-256 is
`8FF7B2547892480E285E17E6D44E380102D2622B9F37DC29111B96FCB3DB47AE`;
the current androidTest APK SHA-256 is
`5C80D01F57DF84FDC624C1783D521C941A57B32FFB213600D82BB71A30D1C91D`.

The product-sized fast-resume checks used the same 12-second separated cache,
160 kbps M4A promotion, and 20 deterministic extra seeks:

| device | status | first mixed PCM (initial) | extra-seek first mixed P50/P95 | seek underruns | anchor offsets |
| --- | --- | ---: | ---: | ---: | ---: |
| S25 / SM-S9310 / API 35 | passed (pre-final waterline rerun) | 39 ms | 37 / 48 ms | 0 | -6 .. -965 frames |
| S10 / SM-G9730 / API 31 | passed (final waterline) | 104 ms | 146 / 166 ms | 0 | -6 .. -965 frames |

The reports are `validation/aac-fast-s25-product-r15.json` and
`validation/aac-fast-s10-product-r7.json`. S10 required the pinned arm64 CPU
LiteRT runtime to be installed through the normal runtime installer before the
product service could bind. These are short seek-resume measurements, not a
continuous thermal qualification.
The S10 run includes the final "fill one steady-state block before switching"
waterline rule; the S25 device became temporarily unavailable before that
last threshold-only rerun, so its row is the immediately preceding dynamic
first-block build and should be repeated before release qualification.

For subsequent S25 and S10 gates, record:

- actual encoder and decoder `codec.name`;
- output MIME, profile, bitrate, sample rate and channels;
- encoded duration, decoder lead and tail padding;
- seek-to-ready latency (cold and warm), P50/P95/P99;
- requested frame, physical anchor frame and offset in frames/ms;
- six-stem anchor spread;
- 0 s, 1 s, middle, and tail seeks;
- 100 repeated warm seeks and 20 cold close/reopen cycles;
- 3-minute continuous playback, underruns, PSS/native memory and file descriptors.

The AAC result is playback-only and must not replace FLAC/WAV for numerical
parity, export, or regression fixtures. If an AAC source fails during open,
seek, flush, or a block read while a WAV playback session still holds its
lease, the source switches at that frame boundary to the sibling WAV when its
geometry matches. The switch is traced as `fallback format=wav`. After the
promoted AAC session is adopted, the WAV is eligible for the normal lease-aware
cleanup path; a later AAC failure follows the normal separated playback
failure/recovery path rather than retaining a second full WAV copy indefinitely.

## Follow-up phases

1. Run promotion and completed-cache playback against a real six-stem cache.
2. Calibrate first-output PTS/priming and six-stem spread on real separated
   audio, not only synthetic PCM.
3. Add a three-minute continuous playback gate with underrun, PSS/native
   memory, file descriptor, thermal, cancellation, and process-recreation data.
4. Keep the BandBuddy 2,048-frame timestamp profile as a diagnostic comparison;
   do not enable it as a product default without a device-specific timeline
   proof.
