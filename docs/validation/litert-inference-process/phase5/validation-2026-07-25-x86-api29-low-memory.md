# Phase 5 API 29 x86 low-memory validation

Status: resource rejected

Tested revision: `0fa5f53be6a9bca8682e8b817c9f77d231cf3ad0`

Release decision: pure x86 remains `Unsupported`

## Device envelope

The second pure-x86 image is an API 29 `Small_Phone` AVD with four x86 CPUs.
Its AVD configuration requests 1,024 MiB RAM, but the guest exposes
2,040,200 KiB (`MemTotal`) plus 1,530,144 KiB swap. This record therefore
describes it as a roughly 2 GiB guest, not as a proven 1 GiB configuration.
`ro.config.low_ram` is absent/false.

ART nevertheless applies a 16,777,216-byte Java heap growth limit to both the
instrumented main process and the `:source_separation` process. Adding
`largeHeap` to the AndroidTest application did not change that limit and was
discarded. At the first worker failure the guest still reported about
1.24 GiB available memory. No LMKD kill preceded the failure.

An idle bound inference process used 39,310 KiB PSS and 140,052 KiB RSS in the
captured sample. It had 1,676 mapped regions and a 1,287,872,512-byte largest
free address gap. Whole-device memory and 32-bit virtual-address fragmentation
therefore did not fail before the Java heap.

## Passing controls

- Clean pinned acquisition downloaded, hashed, and installed exact 9662 after
  the AndroidTest artifact verifier changed from `readBytes()` to streaming
  SHA-256.
- `LiteRtPackagingSmokeTest` ran the APK-packaged x86 CPU runtime against the
  small add model and passed.
- Bound-service startup, acknowledged recycle, and reconnect after an
  unexpected idle-process death all passed, 3/3.
- The pinned 7,482,132-byte `libLiteRt.so` passed ELF32, x86 machine, required
  symbol, dependency, and SHA-256 verification. Its SHA-256 is
  `02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af`.
- Every generated ABI APK passed the native-library packaging verifier.

These controls separate a valid x86 LiteRT binary and IPC implementation from
the model-sized resource failure.

## Blocking failures

The direct 9662 parity test failed before LiteRT invocation while reading the
first 8,388,608-byte float32 tensor. The production-shaped 12-second worker
then failed independently inside the remote process while allocating an
8,388,624-byte tensor array. Its terminal error recorded 6,139 KiB until OOM
and a 16 MiB growth limit.

The process-session matrix adds original playback before source preflight. On
this device that main-process workload exhausted the same 16 MiB heap first.
The initial report surfaced `SourceUnavailable`; logcat showed failed 64 KiB
and 1 MiB allocations during media loading and report cleanup. A diagnostic
rerun reached zero free heap, could not construct its own exception/report,
and was terminated after the result was no longer recoverable.

The current Java tensor path necessarily retains multiple model-sized arrays:
the caller's NCHW input, the session's NHWC input, the NCHW output, and a
LiteRT output read buffer at different points in an invocation. Process
isolation prevents those arrays from sharing the music process heap, but it
cannot make a 16 MiB remote heap sufficient. It also cannot prevent the main
process from exhausting its own 16 MiB heap during playback plus preflight.

The model-switch, fault, cache-death, cache-race, and main-death matrices were
not repeated. Each requires source admission and at least one successful 9662
invocation, so running them would only reproduce the established prerequisite
failure and could not qualify their intended behavior.

## Decision

This run satisfies the second-x86-image smoke requirement, but it is negative
evidence. Pure x86 must remain fail-closed and `Unsupported`; it cannot be
promoted from the successful API 26, roughly 3 GiB AVD alone.

Future Phase 5 admission evidence must record effective Java heap capacity in
addition to total/available RAM and VA gaps. A future x86 experiment may test
a direct/native tensor-buffer pipeline or an image with a larger verified ART
heap, but no resource threshold should be weakened to make this AVD pass.

## Raw ignored evidence

Raw reports remain under ignored `build/` output. The adjacent compact JSON
retains device, runtime, result, and report-integrity fields without local
paths.

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Acquisition | 3,676 | `9c3bde8f3ddac02cc545c95343db5f504bfa7028d9ef69e4b0b6246cd48cb670` |
| Production worker | 4,200 | `41d9657b7f6fbb634a073ee4a50d9610e3b48d32a6e0f83b147cf3ca103ce85d` |
| Process-session matrix | 4,617 | `6ca3d8a570bfafb61d529237c1a191d0fdf4239e46c03ba3cbc73cecb73c8c8b` |
| Direct 9662 parity | 6,566 | `04aa711b959cb11b4d2493497be89ff01150cec586f3c4807ff2397a565ec464` |
