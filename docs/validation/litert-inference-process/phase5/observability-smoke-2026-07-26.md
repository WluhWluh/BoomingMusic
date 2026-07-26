# Phase 5 timing and playback observability smoke

Status: passed on Galaxy S10 arm64

Purpose: qualify explicit counters, not select a host or change thresholds

## Method

The smoke used exact 9662 FP32, LiteRT 2.1.5 Auto, the 12-second Coast Town
WAV, and the frozen three-pair AB/BA/AB runner. Every sample started from a
cold app/process boundary and retained the existing output, memory, thermal,
process-exit, and original-playback gates.

Inference timing is measured around the shared session factory and each real
window invocation. It separates model/session setup, the first real inference,
and later invocations on the same session. The Auto factory's zero-input GPU
eligibility probe remains part of setup rather than being mislabeled as the
first song window. Remote runs additionally record elapsed time from
`bindService` admission to a validated IPC connect response.

Playback diagnostics use Media3's `onAudioUnderrun` callback directly. The
same debug observer snapshots the ExoPlayer application thread's Linux
`schedstat`, voluntary context switches, and involuntary context switches
before and after separation. Position drift remains a separate continuity
metric and is not treated as an underrun proxy.

## Result

| Median | In process | Bound remote |
| --- | ---: | ---: |
| Bind to validated connection | N/A | 952 ms |
| Model/session setup | 3,570.9 ms | 3,524.4 ms |
| First real inference | 2,400.9 ms | 2,390.4 ms |
| Reused inference mean | 2,353.3 ms | 2,347.9 ms |
| Audio underruns | 0 | 0 |
| Player run-queue wait | 226.5 ms | 274.6 ms |

Every run recorded exactly three real model invocations, comprising one first
and two reused invocations. All six contention snapshots were available. No
audio underrun occurred. Player run-queue wait ranged from 180.6 to 308.5 ms;
each sample also retained non-zero timeslice and voluntary/involuntary context
switch deltas. Maximum playback-position drift was 131 ms and no unexpected
playback event occurred. All samples stayed at thermal status 0 on AC power.

These values are diagnostic. The short fixture does not replace full-song
host evidence, and no post-result performance or underrun threshold was added
to the frozen method. The runner only requires that the explicit counters are
present and internally usable.

The playback observer is attached only in debug builds. Normal release builds
do not register the AnalyticsListener or read player-thread `/proc` files.

After the device smoke, GitHub and F-Droid debug unit tests, both AndroidTest
Kotlin compilations, and both release Kotlin compilations passed at revision
`920f0c5be7271d9b90c8ca8d558236f673b38b9d`.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed.

## Evidence

The compact record
[`observability-smoke-v1.json`](observability-smoke-v1.json) retains the exact
metrics and report identity. The ignored raw summary is 138,225 bytes with
SHA-256
`e4ac0fbda280b5f2b6ef8659937c21486e5fc0ae1abd7bbf1c6ae0bf58cd9cd3`.
