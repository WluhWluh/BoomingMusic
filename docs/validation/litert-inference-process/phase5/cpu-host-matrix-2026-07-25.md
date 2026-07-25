# Phase 5 non-x86 CPU host matrix

Status: S10 arm32, S10 arm64, S25 arm64, and API 37 x86_64 passed

Production host policy: unchanged, `InProcess`

## Scope

Every matrix used the pinned `uvr_mdxnet_3_9662@2` contract, artifact SHA-256
`f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`,
the complete WAV fixture, four CPU threads, window decoding, and three cold
AB/BA/AB host pairs. Non-x86 remote runs remained `SingleUse`.

Every accepted sample produced the same finite output and per-stem WAV/FLAC
hashes within its matrix, completed a playable cache, started original audio
playback on its first attempt, recorded no unexpected playback event, and
removed all related processes after the run. Every remote sample selected the
serialized `cpu` policy, created one native session, and ended in `Empty`.

## Measurement correction

The first three complete matrices used paired report schema v1 at application
revision `f997e7ddbc5e0e38f7cf097f8e304bb6cee8d3b4`. They passed the original
runner's immediate bind-time PSS check. Their later pre-worker process samples
also remained below the frozen 96 MiB settled-idle limit, so their output,
performance, and resource results remain valid.

The initial S10 arm32 full matrix completed all six workers but was correctly
left failed because schema v1 compared the connection diagnostic's immediate
bind-time PSS with a gate that Phase 2 had defined for a sample taken after a
two-second settle. Its three immediate values were 98.61-98.72 MiB. The
independent startup test still passed its two-second 96 MiB gate, proving that
this was a field-semantics error rather than a reason to raise the limit.

Revision `eaff86c9215644194b04d7d97ebc62f2362dbfc3` introduced paired report
schema v2. It retains both startup and settled PSS, records the 2,000 ms
interval, and applies the unchanged 96 MiB limit only to the settled value.
An arm32 short smoke and the repeated full matrix passed. The full matrix
recorded 97.48-97.58 MiB at bind time and 93.77-93.85 MiB after settling.
Gate failures are now retained in the summary's `failures` array.

## Results

| Device / ABI | First ready in-process | First ready remote | Full song in-process | Full song remote | Summed peak PSS in-process | Summed peak PSS remote |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S10 / armeabi-v7a | 13,516 ms | 13,109 ms | 282,239 ms | 261,767 ms | 755.5 MiB | 777.9 MiB |
| S10 / arm64-v8a | 12,710 ms | 12,962 ms | 243,994 ms | 239,765 ms | 886.1 MiB | 911.5 MiB |
| S25 / arm64-v8a | 4,781 ms | 4,979 ms | 96,536 ms | 100,622 ms | 903.7 MiB | 921.4 MiB |
| API 37 AVD / x86_64 | 28,125 ms | 26,165 ms | 352,347 ms | 354,201 ms | 1,039.2 MiB | 1,062.5 MiB |

Bound remote changed median summed peak PSS by +22.4 MiB on arm32, +25.4 MiB
on S10 arm64, +17.7 MiB on S25, and +23.3 MiB on x86_64. It did not cross a
performance gate. Maximum playback drift was 170 ms on arm32, 140 ms on both
physical arm64 matrices, and 817 ms on the x86_64 emulator, against the frozen
1,000 ms limit.

The arm32 placement difference is material even though summed PSS increases:
median peak main-process PSS falls from 755.5 MiB to 150.1 MiB, while the
remote process carries a 640.6 MiB median peak. Its minimum remote free-address
gap was 464.2 MiB. The full-song remote median was 20,472 ms faster in this
matrix, but that result alone does not select a release policy.

## Decision

Phase 5B's non-x86 CPU host-placement matrix passes. Arm32 advances to the
Phase 5C resident-session experiment because it has a concrete address-space
reason to consider remote execution. Arm64 and x86_64 do not yet demonstrate
a reliability benefit that justifies their roughly 18-25 MiB active summed-PSS
cost, so production remains in process everywhere.

This checkpoint does not qualify pure x86, select a resident non-x86 session,
enable GPU remote execution, or enable independent background ownership. No
decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed.

## Raw ignored evidence

Raw reports remain under ignored `build/phase5-host-pairs` output.

| Summary | Bytes | SHA-256 |
| --- | ---: | --- |
| S10 arm32 v3 | 27,156 | `88f80ba885fdfb21f94a4c58adbd9febf03a709b05db10e4b1c55e25d0d80bee` |
| S10 arm64 v7 | 26,688 | `60786da98f88b66391f2ee99f2f2d54cba9c2e64cdd1fa85f7768c1dc0a17923` |
| S25 arm64 v1 | 26,655 | `95cd44e634500a2b5bbb7131af1b2e18542c39fb0fe86675dedf0d1391d070ec` |
| API 37 x86_64 v1 | 26,612 | `50cb6c3d6ab179016976233d39c46d5a5082e14bad03ba29dfcffc82f3512848` |
