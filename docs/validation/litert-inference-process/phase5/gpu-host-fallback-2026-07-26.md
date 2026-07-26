# Phase 5 GPU host and fallback checkpoint

Status: S10 host matrix passed; S10/S25 fault matrices passed; S25 host matrix
deferred

Release decision: retain `InProcess + SingleUse` for arm64 Auto

## Scope

The host comparison used exact 9662 FP32, LiteRT 2.1.5 Auto, the frozen Phase
5 paired-host method, and the 273.7-second Coast Town WAV. The S10 completed
three alternating cold `InProcess`/`BoundRemote` pairs. Every run used
`LiteRtGpu`, resolved `libLiteRtClGlAccelerator.so`, produced 12,070,130
frames, and retained identical WAV and FLAC hashes.

The S25 full-song paired matrix was explicitly deferred. Its five-case remote
fault matrix passed, but that is not treated as substitute host-placement
evidence.

## S10 host result

The Galaxy S10 was on AC power and remained at Android thermal status 0 for
all six samples.

| Median | In process | Bound remote | Remote change |
| --- | ---: | ---: | ---: |
| First ready | 12,799 ms | 13,394 ms | +595 ms / 1.046x |
| Full source | 196,244 ms | 198,514 ms | +2,270 ms / 1.012x |
| Summed peak PSS | 601.2 MiB | 633.3 MiB | +32.1 MiB |
| Summed peak native PSS | 131.9 MiB | 145.9 MiB | +14.0 MiB |
| Summed peak graphics PSS | 265.8 MiB | 265.8 MiB | 0 MiB |

Isolation moved the approximately 265.8 MiB graphics allocation and
`libOpenCL.so` mapping from the main process to the remote process. It did not
duplicate the graphics allocation. The additional total PSS came from the
second process's Java/native runtime and IPC host overhead.

Maximum original-playback drift was 176 ms, no unexpected playback event was
recorded, and all processes exited within 438 ms. Bound remote therefore
passes the frozen gates, but it does not demonstrate a reliability or
throughput gain that justifies replacing the arm64 in-process release path.

## Fault ordering

The Galaxy S10 on API 31 and Galaxy S25 on API 35 each passed one-shot remote
faults at GPU setup, invocation 4, output read, non-finite output validation,
and GPU cleanup.

For setup, invocation, output-read, and non-finite failures, the event record
proves this ordering:

1. create and use the GPU session as applicable;
2. close the GPU session;
3. create one CPU session;
4. finish with finite playable output;
5. close the CPU session.

The S10's worst fault sample reached first ready in 14,154 ms and completed in
21,405 ms. The S25 maxima were 5,922 ms and 8,929 ms. Maximum playback drift
was 139 ms on S10 and 140 ms on S25, with no unexpected playback event.

An injected cleanup failure is terminal within its process generation. Both
devices recorded one GPU create and one close attempt, zero CPU creates, a
`Poisoned` session, acknowledged recycle, expected Binder death, and a new
process generation. CPU fallback is forbidden until that recycle completes.
This avoids placing a second large allocator beside native state whose cleanup
cannot be trusted.

## Interpretation

Arm64 Auto remains `InProcess + SingleUse`. Known-good cleanup permits the
existing one-way GPU-to-CPU fallback. A fatal cleanup ends the in-process
request; it must never attempt same-process CPU creation. The qualified remote
path provides controlled whole-process recovery for later experiments, but
Phase 5 found no reason yet to pay its active memory cost in normal arm64
execution.

This checkpoint does not authorize GPU session residency, automatic fallback
from a failed remote host to an in-process host, or independent background
execution. It also does not complete the deferred S25 full-song host matrix.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed.

## Evidence

The compact record
[`gpu-host-fallback-v1.json`](gpu-host-fallback-v1.json) contains the exact
medians, revisions, failure ordering, device envelopes, and raw-report hashes.
Large reports remain under ignored `build/` output.

| Evidence | Bytes | SHA-256 |
| --- | ---: | --- |
| S10 full host summary | 133,163 | `cf69298f13a80c7ce827383496478de53ab89123520b355a71e97eb908f72835` |
| S10 fault summary | 5,102 | `6ca946255adc5d3849a356a4153b36115eb7fefad25bbc495a18685589e79742` |
| S25 fault summary | 5,094 | `a5452e13f9626f5a19ac0083de942cf73ec082c72d55fffa3af697cd2e0cf401` |
