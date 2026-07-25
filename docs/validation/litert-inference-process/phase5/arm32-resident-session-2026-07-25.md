# Phase 5 arm32 resident-session matrix

Status: resident safety passed; `SingleUse` selected

Final validation revision: `cfa7a1ed517e94ea0fc8f926278f1b0ae59ddb6e`

Production host policy: unchanged, `InProcess`

Phase 5 arm32 host candidate: `BoundRemote + SingleUse`

## Scope

The Phase 5B host matrix gave arm32 a concrete reason to consider remote
execution: it reduced peak main-process PSS from 755.5 MiB to 150.1 MiB and
moved the large native session into a separate 32-bit address space. Phase 5C
then asked a separate question: whether that remote process should retain the
session between exact runs.

An internal `arm32ResidentProcessValidation` build flag selected
`ResidentUntilProcessExit` only for `armeabi-v7a`. The flag is false in normal
debug, CI, and release builds. It does not enable GPU residency, independent
background execution, or an application-visible setting.

Target and runtime:

- Samsung Galaxy S10 (`SM-G9730`), Android 12 / API 31
- 32-bit `armeabi-v7a`, eight visible processors, four LiteRT CPU threads
- exact 9662 TFLite contract, with KARA used only for key-change testing
- `BoundRemote`, client-bound lifetime, original-audio playback probe active

## Boundaries found

The first attempt failed before native allocation because the resident
controller still searched only for x86's effective compatibility record.
Revision `6aa8445b` made the controller require the current process ABI's exact
KnownGood CPU record. It retained x86's effective-record behavior and rejected
GPU-capable records.

The second attempt completed one full-song bookend and five completion cycles.
At the deliberate cycle-6 client rebind, Android normally destroyed the
healthy unbound service because the existing warm-retention binding was still
limited to x86 validation builds. The old PID had one healthy resident
session, 64 invocations, no active lease, about 616 MiB PSS, and a 464.2 MiB
free VA gap. There was no native crash or LMKD event. Revision `cfa7a1ed`
extended the same five-minute main-process private binding to the arm32
validation build.

Neither change creates an independent service lifetime. If the main process
dies, the private retention binding dies with it.

## Resident result

The final matrix used the 12-second WAV for 20 cycles: five completion, five
pause/resume, five pending-tail resume, and five cancellation cases. It used
the complete 273.7-second WAV before and after the cycles.

| Result | Observed | Gate |
| --- | ---: | ---: |
| Process generations before final recycle | 1 | exactly 1 |
| Native session creations | 1 | exactly 1 |
| LiteRT invocations | 141 | every cycle advances |
| PSS change, cycle 2 to 20 | -4,931,584 bytes | at most +67,108,864 |
| Mapped-region change, cycle 2 to 20 | -9 | at most +256 |
| Maximum sampled remote PSS | 664,249,344 bytes | diagnostic |
| Minimum sampled free VA gap | 486,793,216 bytes | at least 134,217,728 |
| Maximum cache lease release | 3 ms | bounded |
| Maximum native-session lease release | 755 ms | bounded |
| Unexpected Binder deaths | 0 | 0 |
| Maximum playback drift | 62 ms | at most 1,000 ms |
| Playback stop/seek/replacement events | 0 | 0 |

The deliberate cycle-6 rebind retained the same PID, process generation,
process-start ticks, session ID, and invocation count. The final explicit
recycle produced a new generation and an expected Binder death.

Both full-song bookends produced 12,070,130 frames in 48 windows through the
same session. Their WAV hashes matched exactly:

| Stem | SHA-256 |
| --- | --- |
| Vocals | `dd076ccabd01e6a4c306d5e0918883e88e93f8f3c8f3c5332fcd0be66b117f61` |
| Instrumental | `0dcbc98621430d8760da17abd516e623a7c821b7175b900b070d00a1c413c9ad` |

## Key changes

The second matrix alternated 9662 and KARA 20 times. Every old generation
rejected the new model key before another invocation or allocation, then an
acknowledged recycle created one new process and one new session.

| Result | Observed |
| --- | ---: |
| Switches / new process generations | 20 / 20 |
| Distinct new session IDs | 20 |
| Native session creations per generation | 1 |
| Invocations per generation | 3 |
| Expected / unexpected Binder deaths | 20 / 0 |
| Remote PSS range | 633,376,768-657,419,264 bytes |
| Mapped-region range | 2,642-2,735 |
| Minimum free VA gap | 486,793,216 bytes |
| Maximum mismatch cache-lease release | 1 ms |
| Maximum playback drift | 128 ms |
| Playback stop/seek/replacement events | 0 |

Downloading KARA did not replace the active 9662 selection. Ten runs used
each model, and no process ever held both native sessions.

## Decision

Resident reuse is safe within the tested arm32 scope, but it is not selected.
The experiment did not establish a latency or reliability gain over
`SingleUse`, while an idle retained generation keeps roughly 633-657 MiB PSS
for up to five minutes. The address-space benefit comes from `BoundRemote`
placement, not from retaining the session after a run.

The Phase 5 arm32 candidate is therefore `BoundRemote + SingleUse`. Whether
that host becomes `RemotePreferred` or `RemoteRequired` remains a Phase 5E
decision. Arm64, x86_64, and GPU residency do not advance: Phase 5B found no
host reliability need on the 64-bit CPU targets, and GPU lifetime still
requires its separate graphics/driver matrix.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed.

## Regression

With both validation flags disabled, all of these passed together:

- `testGithubDebugUnitTest`
- `testFdroidDebugUnitTest`
- `compileGithubDebugAndroidTestKotlin`
- `compileFdroidDebugAndroidTestKotlin`
- `compileGithubReleaseKotlin`
- `compileFdroidReleaseKotlin`

## Raw ignored evidence

The compact
[`arm32-resident-session-v1.json`](arm32-resident-session-v1.json) retains the
exact device, runtime, memory, output, decision, and report identities.

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Initial x86-record rejection | 6,600 | `dae225c31253dca861cdeeb0f40d40e86ad66fa0ce689f7c89fee892d6e464e8` |
| Rebind-retention rejection | 12,526 | `79f136fe0632c1603a77bb6304a3acb8d495dc13f7fc4dfe723960f1b5690c96` |
| Passing 20-cycle/full-song matrix | 22,946 | `c5483b21232e1524b3d5ab520c39274b1edefcf535e2f863417d2db3cd8788dc` |
| Passing 20-switch matrix | 44,061 | `63877e389134e9aed47a4c9425aca5e4f417286c3c3ae592e7422cc6bdcbc601` |
