# Phase 5 API 29 x86 memory matrix

Status: 12 of 12 production-shaped workers passed

Application revisions:

- `70d323f50d4d1d405125b5f358835922be0e2879` for the 2/3/4 GiB matrix
- `383b556bdb2cf69dd9012470da60d46e52604f12` for the 128 MiB ART floor

Release decision: pure x86 remains `Unsupported` pending Phase 5E

## Method

`run_phase5_x86_memory_matrix.ps1` launched the API 29 `Small_Phone` AVD with
`-no-snapshot` and explicit 2,048, 3,072, then 4,096 MiB RAM requests. The
three launches produced different kernel boot IDs. Each boot then used the
previously documented root `stop`/`setprop`/`start` sequence and recorded the
property before and after the framework restart.

Each memory configuration ran three exact 9662 workers. Every worker
force-stopped all old application processes first, then created a distinct
main PID, remote PID, process generation, and resident native session. The
test retained the installed model, deleted only the exact source/model cache
entry, separated the 12-second WAV fixture, promoted both stems, and played
the original source silently in repeat-one throughout the run.

The report fix in `52fae6c2` makes remote peak PSS use periodic and final
`smaps` diagnostics as well as `ActivityManager`. All accepted samples use
`phase7-runner-v22`; earlier API 29 values that reported roughly 45 MiB remote
PSS are not used for this decision.

## RAM matrix

| Requested RAM | Guest `MemTotal` | App/remote max heap | Median first ready | Median complete | Max summed PSS | Min free VA gap | Max playback drift |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2,048 MiB | 1,992.4 MiB | 512 MiB | 13,285 ms | 19,157 ms | 740.0 MiB | 490.9 MiB | 63 ms |
| 3,072 MiB | 2,997.4 MiB | 576 MiB | 14,264 ms | 20,103 ms | 744.7 MiB | 459.0 MiB | 198 ms |
| 4,096 MiB | 3,940.0 MiB | 576 MiB | 13,379 ms | 19,283 ms | 739.9 MiB | 451.9 MiB | 206 ms |

The 32-bit guest therefore does expose nearly 4 GiB. Its 4 GiB request loses
about 156 MiB to the guest/platform memory map, but it is not truncated to a
3 GiB ceiling. Increasing guest RAM did not reduce active PSS or improve
steady-state throughput materially; it primarily increased whole-device
headroom.

All nine runs ended with one `Resident` session and one native session
creation. They produced 529,200 finite frames and identical stem WAV hashes.
No LMKD event, unexpected Binder death, unexpected playback event, frame
change, or output-identity change occurred.

## ART floor

The original API 29 image exposed a 16 MiB growth limit and failed before
inference while ExoPlayer allocated Java buffers. Merely observing the
2/3/4 GiB runs at 512-576 MiB would not validate the frozen 128 MiB admission
floor.

A fourth cold boot therefore fixed both `dalvik.vm.heapgrowthlimit` and
`dalvik.vm.heapsize` to `128m`. All three new process generations reported
exactly 134,217,728 bytes from `Runtime.maxMemory()` in both the main and
remote processes and passed:

| Metric | 128 MiB result |
| --- | ---: |
| Median first ready | 13,974 ms |
| Median complete fixture | 20,291 ms |
| Maximum summed PSS | 736.7 MiB |
| Minimum largest free VA gap | 1,029.3 MiB |
| Maximum playback drift | 118 ms |

The much larger PSS than the Java limit is expected: model weights, XNNPACK,
and tensor backing dominate native mappings. Reducing ART's reserved address
space increased the largest free 32-bit VA gap. This result validates 128 MiB
as the admission floor for exact 9662 and the current Java tensor pipeline on
this environment. It does not qualify lower heaps, HQ4, KARA as a separate
support promise, or unknown imported models.

## Decision

The resource prerequisite now passes: exact 9662 can run in
`BoundRemote + ResidentUntilProcessExit` across the requested RAM matrix, and
the 128 MiB effective runtime-heap floor has a direct passing sample. Combined
with the earlier API 26 lifecycle, API 29 process/cache/recovery, model-switch,
and fault matrices, pure x86 is technically viable as a narrow Phase 5E
candidate.

Normal builds remain fail-closed. The tested API 29 image still needs a
privileged property override to create a realistic ART envelope, which an
application cannot perform. A future release decision must gate the feature
on the application's observed `Runtime.maxMemory()`, require remote resident
execution, pin exact qualified model identities, and reject inadequate
devices before native allocation. Unknown models and HQ4 remain outside that
candidate scope.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed.

## Raw ignored evidence

The compact record
[`x86-api29-memory-matrix-v1.json`](x86-api29-memory-matrix-v1.json) contains
the boot IDs, exact byte values, output hashes, and all worker-report hashes.
Large reports remain under ignored `build/` output.

| Evidence | Bytes | SHA-256 |
| --- | ---: | --- |
| 2/3/4 GiB summary | 11,564 | `65dff1fcbcea2583d889555aa734618cf2f0655ef15b1a0ae9cfd2af4e5b27ed` |
| 128 MiB floor summary | 4,278 | `4fdfa92e5bbe54002ff6a21fe1c31c2bfd779c6aed7f1b0e8f3ea9dd27d250ba` |
| 2 GiB environment | 1,583 | `1d892735cf67db293e1cb48252fe2adb61e5065f6337cb5b92160bbbe1718ad7` |
| 3 GiB environment | 1,583 | `d61e2384015bbb6754f8b2ef8684c599667aabae2e461a4a346226ef1e29e6ff` |
| 4 GiB environment | 1,584 | `0ed39e1b6fad202cece862845b3c11780d31387f1a685aada75ae16b27abc8a3` |
| 128 MiB floor environment | 1,717 | `b187c54ca2e7e081d601759e96f49ac26d976015fa44251ca4e97c7e26b7e057` |
