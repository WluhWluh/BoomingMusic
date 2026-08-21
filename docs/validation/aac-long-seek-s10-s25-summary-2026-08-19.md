# Long-song AAC Seek: S10 And S25

Date: 2026-08-19
Fixture: six AAC-LC stereo stems, 44.1 kHz, 210.023 seconds, 160 kbps per stem
Profile: `previous` AAC sync, 1,024-frame seek block, six-way first-block parallelism, four seeded access units
Matrix: four directions, warm 8 samples per direction, cold 4 samples per direction

## Results

Values are first non-zero PCM after the seek. P50/P95 are in milliseconds.

| Device | Direction | Warm P50/P95 | Cold P50/P95 | Underruns | Low-water events |
| --- | --- | ---: | ---: | ---: | ---: |
| S10 / SM-G9730 | +5 s future | 87 / 116 | 97 / 100 | 0 | 0 |
| S10 / SM-G9730 | +60 s future | 77 / 115 | 93 / 98 | 0 | 0 |
| S10 / SM-G9730 | -5 s past | 102 / 129 | 55 / 103 | 0 | 0 |
| S10 / SM-G9730 | -60 s past | 105 / 116 | 62 / 71 | 0 | 0 |
| S25 / SM-S9310 | +5 s future | 11 / 12 | 18 / 19 | 0 | 0 |
| S25 / SM-S9310 | +60 s future | 11 / 12 | 21 / 23 | 0 | 0 |
| S25 / SM-S9310 | -5 s past | 11 / 13 | 23 / 26 | 0 | 0 |
| S25 / SM-S9310 | -60 s past | 13 / 14 | 20 / 27 | 0 | 0 |

The resume-waterline P50/P95 matched first-PCM P50/P95 in every row. This is expected for the current one-block seek gate: the first successful data-plane block also satisfies the resume waterline.

## Anchor Behavior

All 48 S10 and all 48 S25 seek samples produced six anchors with zero inter-stem spread. The four target positions map to the same AAC sync offsets on both devices:

| Target direction | Anchor offset at 44.1 kHz |
| --- | ---: |
| +5 s future | -798 frames, about -18.1 ms |
| +60 s future | -442 frames, about -10.0 ms |
| -5 s past | -118 frames, about -2.7 ms |
| -60 s past | -474 frames, about -10.7 ms |

These are intentional `previous` sync anchors, not device-specific timing errors. No sample exceeded the existing 4,096-frame offset bound.

## Interpretation

- Seek distance is not a measurable latency driver in this matrix. Moving 5 seconds or 60 seconds in either direction still lands on a nearby AAC sync point and has similar recovery cost.
- S25 has a large decoder/data-plane margin: approximately 11–14 ms warm P50 and 12–14 ms warm P95. Cold P95 stayed below 27 ms.
- S10 is the limiting device: approximately 77–105 ms warm P50 by direction, with a worst observed warm P95 of 129 ms and cold P95 of 103 ms.
- The S10 near-past warm row was the slowest median, but the cold row was faster than the other cold directions. Treat this as scheduling variance, not a stable direction penalty.
- All samples had zero engine underruns and zero low-water events. This is strong evidence for the AAC decoder/data-plane path, but not a complete UI or speaker-output qualification.

For product budgeting, reserve at least a 150 ms S10 data-plane resume budget before adding MediaSession dispatch, mixer notification, audio output, and UI scheduling. S25 can use a substantially smaller data-plane budget, but the same outer playback policy can remain shared.

## Evidence

- [S10 raw report](aac-long-s10-seek-report-2026-08-19.json)
- [S10 detailed notes](aac-long-s10-seek-report-2026-08-19.md)
- [S25 raw report](aac-long-s25-seek-report-2026-08-19.json)
- Test: `SourceSeparationAacLongSongSeekDeviceTest#longSongSixStemSeekDistanceMatrix`

The raw reports include exact device fingerprints, codec names, frame geometry, per-stem SHA-256, every sample, anchor traces, and phase timings. The fixture remains an ignored build artifact and is not shipped in the application.
