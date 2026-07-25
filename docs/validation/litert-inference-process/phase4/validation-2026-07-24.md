# Phase 4 process-safe cache validation

Status: passed for the internal `BoundRemote` experiment

Final implementation and report revision tested:
`963ebb7fa16a774402b9980c698fb36d39271cb8`

Production execution mode: `InProcess`

Normal pure-x86 compatibility: `Unsupported`

Validation execution mode: `BoundRemote`, exact pinned artifacts only

## Decision

Phase 4 establishes one process-safe mutation authority for each exact cache
entry. A remote run owns the kernel-backed writer lease, durable run journal,
segment publication, and terminal commit. Main-process deletion, pruning,
playback-settings mutation, promotion, hydration, and cleanup cannot become a
second writer while that lease is active. Read-only inspection consumes only
atomically published state.

FLAC promotion and hydration remain main-process operations after the run
writer releases the entry. The FLAC handoff matrix proves that promotion owns
one exclusive lease and is unaffected by death of an idle inference process.

An active run treats disappearance of its cache entry or lock path as
`CacheUnavailable`, stops further decode/DSP/native/output work, and does not
recreate the deleted root. Native-process or main-process death releases the
kernel lock. A later explicit run resumes from the durable journal, records
`PreviousOwnerDied`, validates committed segment integrity, and never creates
a duplicate committed segment.

This result does not enable automatic restart or independent background
execution. `BoundRemote` remains internal-only, production remains
`InProcess`, and pure x86 remains fail-closed pending later policy and release
qualification.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed in Phase 4.

## Ownership and journal

- The OS file lock is authoritative. Owner JSON records run ID, process
  generation, PID, purpose, and timestamp only for diagnostics.
- The lock file descriptor remains open through the last durable terminal
  transition. Metadata age never grants ownership.
- The journal freezes exact source/model/contract/cache identity, schema,
  process generation, lifecycle, contiguous transition sequence, last
  committed window, and per-stem integrity.
- Each segment is closed and synced, integrity-checked, atomically published,
  and only then recorded as `SegmentReady`.
- Startup replay ignores orphan staging output, rejects missing or altered
  committed files, and cannot make an incomplete entry playable.
- Pause, user cancel, incompatibility, foreground timeout, cache loss, failure,
  completion, and prior-owner death remain distinct transitions.
- Loss of the controlling client stops the remote range executor at its next
  boundary. It is not treated as authorization to continue independently.

## Active-run death matrix

The API 26 pure-x86 matrix killed the inference process at six boundaries,
three times each, then explicitly resumed the same exact entry. It also
deleted the complete cache root during three active runs.

| Stage | Repetitions | Lock release | Final journal | Segments |
| --- | ---: | ---: | ---: | ---: |
| Decode | 3 | 11-19 ms | 13 | 3 unique |
| DSP | 3 | 13-17 ms | 13 | 3 unique |
| Native invocation | 3 | 10-20 ms | 13 | 3 unique |
| Output publish | 3 | 14-17 ms | 13 | 3 unique |
| Journal commit | 3 | 9-14 ms | 12 | 3 unique |
| Terminal commit | 3 | 9-11 ms | 10 | 3 unique |
| Cache root cleared | 3 | typed outcome | `CacheUnavailable` | not recreated |

Original repeat-one playback produced 40 snapshots, zero unexpected events,
and at most 97 ms position drift. One same-item automatic transition was
expected because the five-minute matrix exceeded the source duration.

Representative arm64 matrices repeated DSP death, active native-invocation
death, journal-commit death, and cache clearing:

| Target | API | Lock release | Playback drift | Violations |
| --- | ---: | ---: | ---: | ---: |
| Samsung Galaxy S10 arm64 | 31 | 8-11 ms | 160 ms | 0 |
| Samsung Galaxy S25 arm64 | 35 | 1-3 ms | 148 ms | 0 |

The locked API 35 device test temporarily used the application's existing
ignore-audio-focus preference only for its muted continuity probe, then
restored the absent preference key. Decode, playback state, elapsed-position,
media-item, discontinuity, and replacement assertions remained active.

## Management races

The following matrix passed three times on the final x86 build:

- While main-process FLAC promotion held the exact entry, inspection remained
  read-only, deletion returned `Busy`, pruning deleted zero entries, blend
  mutation failed, and hydration returned `Busy`.
- Killing the idle inference process did not interrupt FLAC promotion. Every
  promoted cache passed full integrity validation.
- While a 9662 run was admitted and blocked in remote DSP, KARA became active
  and the installed 9662 weights were deleted. The admitted run retained its
  immutable 9662 identity and completed normally.
- The completed 9662 cache remained playable without installed model weights;
  KARA resolved to a different exact cache key; the test restored and
  reactivated 9662 afterward.
- Original playback had zero unexpected events and at most 113 ms position
  drift across the three runs.

## Main-process death

Three runs killed the main instrumentation process while the remote process
held an active exact-entry lease. The old remote exited with its client, and
the kernel lock became available in 9-13 ms. Explicit recovery used a new main
PID, remote PID, and process generation in every run.

Each journal stayed at sequence 3 between death and recovery, then reached
sequence 13 with exactly three unique committed segments. Every replay
recorded `PreviousOwnerDied`; every final cache was valid and playable.

## Host regression

The final revision passed `testGithubDebugUnitTest` and
`testFdroidDebugUnitTest`. GitHub and F-Droid debug AndroidTest Kotlin
compilation and both release Kotlin compilations also passed. The unit suites
include focused kernel-lock, cache-loss, journal replay, committed-segment
integrity, mutation arbitration, process-session, and model-identity tests.

## Raw ignored evidence

Raw reports remain under ignored `build/` output. The compact JSON files in
this directory retain the decision fields without host-local paths.

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| x86 21-case death/cache-clear matrix | 25,135 | `3b2cc588f21c2dbb0c134bf8b78dbe241c4aff8e5041a4451051ff33a0e8aab4` |
| S10 arm64 representative matrix | 8,317 | `e7d47ad5f2047a16768d88e917a837880e54132c991cc5b223cbaba336084502` |
| S25 arm64 representative matrix | 8,302 | `5fc8f0f5a1a0f0fd1dc4ae6238d16c68a0f13e3b88fa9981ca712ac7d80bfa99` |
| FLAC/model race run 1 | 6,384 | `338214fc39db1476c1bfab286c7a6cdcbe2a29d14de611c68d7f0cb4c387ab5c` |
| FLAC/model race run 2 | 6,387 | `96fb1f2dc06b3f887f75d3196ca65dc0e72353b81130ec0336b7195941b7ed86` |
| FLAC/model race run 3 | 6,384 | `d5aa9cc194381ab9aa44cd1e549a45e5ae547fa8981bdd9b69993e478aaafef0` |
| Main-process death run 1 | 4,508 | `3e4120c8c2cfd85d42c556c3adc1b6dece2cbe3b50525b225017b4383311753a` |
| Main-process death run 2 | 4,506 | `964b04585191fee2fc10beb5d382d92ef0603d27c31d42ada35727bfa83490de` |
| Main-process death run 3 | 4,507 | `325d8cdeefddbcea122aa7752f1364d835219ec56257a69a7d7ecb5b4071199c` |

## Exit decision

Phase 4 passes. Process death and management races did not corrupt, splice,
misidentify, or prematurely promote an exact cache. Recovery is safe from a
durable boundary and original playback remains independent. Phase 5 may now
compare host placement and session policy on each ABI, but it must continue to
keep background lifetime and automatic restart disabled.
