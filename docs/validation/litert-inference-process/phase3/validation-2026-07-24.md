# Phase 3 single-session pure-x86 validation

Status: passed for the internal-only x86 process experiment

Final implementation and report revision tested:
`ef61357b9fd2626d7121405826d542bae5e841c2`

Production execution mode: `InProcess`

Normal pure-x86 compatibility: `Unsupported`

Validation execution mode: `BoundRemote`, exact pinned artifacts only

LiteRT: 2.1.5, CPU FP32, four threads

Target: Android API 26, 32-bit x86, six virtual processors

## Decision

One exact 9662 native session can remain resident for repeated completion,
pause/resume, pending-tail resume, cancellation, and full-song work in one
pure-x86 process incarnation. A model-key change can cross an acknowledged
self-termination boundary and create exactly one session in a fresh process.
Neither path interrupted, sought, or replaced concurrently playing original
audio.

This is candidate evidence for Phase 4, not a production enablement decision.
The normal compatibility graph still rejects pure-x86 inference before native
allocation. The override is compile-time, AndroidTest-only, unavailable to
normal debug/CI/release builds, and restricted to the exact pinned 9662 and
KARA artifact hashes.

No decoder, route selector, MP3 calibration, no-Xing threshold, overlap guard,
window placement, or fallback boundary changed in Phase 3.

## Resident matrix

The final matrix used the 12-second WAV fixture for 20 bounded worker cycles
and original-audio repeat-one playback. It used the complete 273.7-second WAV
fixture as a full-song bookend before and after those cycles.

| Result | Observed | Gate |
| --- | ---: | ---: |
| Native session creations | 1 | exactly 1 |
| LiteRT invocations | 146 | every cycle must advance |
| PSS change, cycle 2 to 20 | -4,497,408 bytes | at most +67,108,864 |
| Mapped-region change, cycle 2 to 20 | +10 | at most +256 |
| Minimum sampled free VA gap | 144,646,144 bytes | at least 134,217,728 |
| Unexpected Binder deaths | 0 | 0 |
| Playback snapshots | 7 | all continuous |
| Maximum repeat-loop position drift | 60 ms | at most 1,000 ms |
| Playback stop/seek/replacement events | 0 | 0 |

The cycle allocation was five ordinary completions, five pause/resume cases,
five pending-tail resume cases, and five cancellations. A paused client was
closed and rebound without changing process generation, process-start ticks,
session ID, or invocation count. Every cycle performed new native work; exact
cache hits were removed only after terminal state and lease release.

Both full-song bookends used the same session, produced 12,070,130 frames in
48 windows, and matched byte-for-byte:

| Stem | WAV SHA-256 |
| --- | --- |
| Vocals | `b35cfa1519cfc51305d86599619743ae6df58e4131d0270bd027fa530e9a1aef` |
| Instrumental | `cba869cdb1cc1f1be69c4f31f232026fdab9eaaae129f71574cb4e01d22fb95c` |

The final explicit recycle acknowledged token
`phase3-matrix-recycle-0001`, classified the old Binder death as expected,
and returned a different generation and process-start identity.

## Model-switch matrix

The final matrix alternated exact 9662 and KARA selections 20 times. Merely
downloading KARA did not change the active 9662 selection. On every switch the
old resident generation rejected the different session key before another
native invocation or creation, then acknowledged its own recycle. Every new
incarnation created exactly one session and completed three native
invocations.

| Result | Observed |
| --- | ---: |
| Expected Binder deaths | 20 |
| Unexpected Binder deaths | 0 |
| PSS range | 605,668,352-622,652,416 bytes |
| Mapped-region range | 1,460-1,493 |
| Minimum free VA gap | 152,772,608 bytes |
| Playback snapshots | 62 |
| Maximum repeat-loop position drift | 37 ms |
| Playback stop/seek/replacement events | 0 |

The original playback probe disabled stem playback, selected repeat-one
through `PlaybackService`'s public session command, muted output, and compared
the observed position with elapsed wall time modulo the 12-second duration.
This makes a hidden pause or seek visible even if `playWhenReady` later returns
to true.

## Fault classification

The controller unit suite directly covers failures at the state authority
that owns the native session:

| Fault | Required classification |
| --- | --- |
| Validation before acquisition | healthy |
| Native creation | poisoned |
| Native invocation | poisoned |
| Non-finite output | poisoned |
| Unexpected post-acquisition execution failure | poisoned |
| Cooperative cancellation | healthy |
| Remote callback delivery | healthy session, disconnected client |

The device fault matrix then exercised the process and Binder boundaries. An
injected callback delivery failure left a failed cache, released its writer
lease, and retained one healthy session. Rebinding the client reused that
exact generation and session, advancing from two to five native invocations.
A forced 1 ms recycle timeout classified the later death as unexpected and
recovered a fresh generation. A separate unexpected idle-process kill also
recovered a fresh generation.

A synthetic Kotlin throw in the remote process would not add native-runtime
evidence beyond the controller tests. A true native crash during an active
cache-file transition is deliberately deferred to Phase 4, where the durable
journal and OS-backed exact-entry lease can first make recovery assertions
safe. Phase 3 does not claim active-run crash recovery.

## Retention boundary

API 26 cannot keep this bound service alive through `startService()` without
introducing a foreground-service obligation, and self-binding from
`onUnbind()` did not retain the process. The experiment therefore uses one
main-process private binding for an idle healthy x86 session, with a frozen
five-minute deadline and no foreground-service state or wake lock.

The deadline currently releases that binding; it does not promise immediate
process termination. Deterministic reclamation is proven only for an explicit
acknowledged recycle. If Android kills an unbound idle process, that death is
classified as unexpected and the next bind must report a fresh incarnation.
Automatic idle-deadline and memory-pressure recycle policy remains a later
background-lifetime decision.

## Normal-build regression

After the x86 matrices, a build without the validation property passed all
three `SourceSeparationInferenceProcessDeviceTest` cases on every connected
target. The cases cover startup/close, acknowledged recycle with a fresh
incarnation, and reconnect after unexpected idle death.

| Target | API | ABI | Result |
| --- | ---: | --- | --- |
| Samsung Galaxy S10 | 31 | arm64-v8a | 3/3 passed |
| Samsung Galaxy S25 | 35 | arm64-v8a | 3/3 passed |
| Android emulator | 37 | x86_64 | 3/3 passed |
| Android emulator | 26 | x86 | 3/3 passed |

The final `:app:testGithubDebugUnitTest` suite and GitHub debug AndroidTest
compilation passed. HQ4 remains rejected before native allocation on pure x86;
the known-disqualified allocation probe was not repeated.

## Raw ignored evidence

Raw reports remain under ignored `build/` output. The committed compact JSON
files contain no host-local paths and retain the decision fields.

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Resident session and full-song bookends | 18,934 | `ea1fe89792d6cb724f00c3fee1399bad6bc641851423ab4e1a49825d39df4dc5` |
| 20-switch 9662/KARA matrix | 42,796 | `8a99f9cfd720b5968a8198f7e59af68696dc9a92fc1aee4b87a5f0c06bec4591` |
| Callback/recycle/idle-death fault matrix | 4,627 | `e161586a6207500923c35bcbd593192fe572a5add9d0caaab51b5428074eb63b` |

## Exit decision

Phase 3 passes its bounded experiment. Keep production `InProcess`, keep
`BoundRemote` internal-only, and keep pure x86 fail-closed. Carry the
single-session and acknowledged-recycle result into Phase 4 cache-safety work.
Do not generalize persistent sessions to arm32, arm64, x86_64, or GPU from
this x86-only result.
