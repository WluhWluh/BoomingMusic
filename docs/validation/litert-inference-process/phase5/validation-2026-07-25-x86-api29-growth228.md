# Phase 5 API 29 x86 228 MiB heap retest

Status: validation matrices passed under a temporary heap override

Application revisions tested:

- `a11678b42ef55e2db76049e92b92e9822278f71b` for the production worker and
  direct numerical probe
- `8f63c90edf8ccee4d438d341d99bbcfd9b2740e3` for the completed process,
  model-switch, fault, cache, and main-process-death matrices

Release decision: pure x86 remains `Unsupported`

## Device envelope

This retest used the same API 29 pure-x86 `Small_Phone` AVD as the adjacent
16 MiB rejection record. The guest exposes 2,040,200 KiB RAM, 1,530,144 KiB
swap, and four x86 CPUs. It is not a demonstrated 1 GiB device even though
the editable AVD `config.ini` requests `hw.ramSize=1024`.

Changing the AVD's `vm.heapSize` did not change the running framework's ART
growth limit. The effective values disagreed across layers:

| Source | RAM | Java heap |
| --- | ---: | ---: |
| AVD `config.ini` | 1,024 MiB | 228 MiB |
| generated `hardware-qemu.ini` | 2,048 MiB | 512 MiB |
| initial Android runtime | about 2 GiB | 16 MiB growth limit |

The successful retest temporarily set `dalvik.vm.heapgrowthlimit=228m` as root
and restarted the Android framework. The property was verified before every
remaining matrix. This override can disappear after a full emulator reboot,
so it is useful resource evidence but is not a reproducible release device
profile by itself.

## Worker and numerical result

The production-shaped `BoundRemote` worker completed the 12-second WAV with
exactly 529,200 finite output frames. First ready output took 13,479 ms and
the complete run took 17,565 ms. The sampled main-process PSS peak was
85.3 MiB. The resident remote session ended near 610 MiB PSS.

The direct 9662 probe also passed:

| Metric | Result |
| --- | ---: |
| First inference | 2,474 ms |
| Reused inference | 1,642 ms |
| SNR against ORT | 97.5986 dB |
| Cosine similarity | 0.999999999913 |
| Maximum absolute error | 0.0000595301 |
| Reconstruction maximum error | 0.0000000149 |
| Peak sampled PSS | 738,941 KiB |
| Peak sampled native PSS | 536,558 KiB |

The second invocation reused the same session and was byte-identical to the
first LiteRT output. The high PSS confirms that the heap correction removes
the immediate Java allocation failure but does not make 9662 a small-memory
workload.

## Resident process matrix

The first 228 MiB matrix exposed a test-only timing race after cycle 10. The
cache writer lease had already reached zero while the uninterruptible native
invocation still held the separate session lease. The test incorrectly
asserted both values at the same instant. Revision `8f63c90e` added a bounded
wait for the native lease, retaining the existing 30-second hard timeout and
recording `sessionLeaseReleaseMs`; production execution did not change.

The corrected matrix passed all 20 lifecycle cases and both full-song
bookends:

- one process generation, one resident session ID, and one native session
  creation served 156 invocations;
- completion, pause/resume, pending native tails, and cancellation all passed;
- cache leases released in at most 1 ms;
- native session leases released in at most 688 ms, including 349-688 ms for
  the five deliberately pending-tail cases;
- cycle-2 to cycle-20 PSS changed by -7.2 MiB rather than showing growth;
- maximum PSS was 664.5 MiB and the minimum largest free address gap was
  404.3 MiB;
- both full-song bookends produced the same per-stem SHA-256 values;
- original playback had zero unexpected events and at most 39 ms position
  drift.

No LMKD event or unexpected Binder death was observed.

## Model and failure matrices

Twenty alternating 9662/KARA switches passed. Downloading KARA did not change
the active 9662 selection. Each artifact mismatch used an acknowledged fresh
inference-process generation, created exactly one matching native session,
and left original playback continuous. There were 20 expected and zero
unexpected Binder deaths; maximum playback drift was 79 ms. Peak remote PSS
was 623.8 MiB and the minimum largest free address gap was 443.3 MiB.

The fault matrix passed callback-delivery failure, a forced 1 ms recycle
timeout, and unexpected idle-process death. Callback failure became a typed
failed cache state, released its lease, and retried on the same healthy
session. Recycle timeout and idle death both recovered through a new process
generation.

## Cache and process death

The complete 21-case cache matrix killed the remote process three times at
each of Decode, DSP, NativeInvocation, OutputPublish, JournalCommit, and
TerminalCommit, then cleared the cache during three active runs. Kernel locks
released in 6-14 ms. Every death case recovered from its durable journal to
three unique committed segments; cache clearing returned `CacheUnavailable`
and did not recreate the deleted root. Full-MP3 original playback had zero
unexpected events and at most 59 ms position drift.

The cache-race matrix passed FLAC handoff during idle remote death and model
management during an admitted run. Switching active selection to KARA and
deleting installed 9662 weights did not alter the admitted 9662 identity. Its
completed cache remained playable without the weights, and the test restored
9662 afterward. Original playback drift was at most 65 ms.

The main-process-death scenario used a new main PID, remote PID, and process
generation after recovery. The old kernel lock released in 5 ms. Journal
sequence remained 3 between death and explicit recovery, then reached 13 with
three completed segments and a `PreviousOwnerDied` transition. The final cache
was valid and playable.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed in this retest.

## Host regression

At revision `8f63c90e`, the following gates passed together:

- `testGithubDebugUnitTest`
- `testFdroidDebugUnitTest`
- `compileGithubDebugAndroidTestKotlin`
- `compileFdroidDebugAndroidTestKotlin`
- `compileGithubReleaseKotlin`
- `compileFdroidReleaseKotlin`

## Decision

This result changes the diagnosis but not the release tier. The prior failure
was caused by the effective 16 MiB ART heap, not by the x86 LiteRT binary,
model operators, Binder protocol, LMKD, or the available virtual-address gap.
With a 228 MiB growth limit, the same roughly 2 GiB guest can run the complete
9662 process, lifecycle, cache, and recovery suite.

Pure x86 nevertheless remains fail-closed and `Unsupported` because the
passing heap was applied by a transient privileged property override, only
one guest-memory configuration has passed, and no runtime heap admission floor
has been release-qualified. Promotion requires reproducible cold-boot results
across the planned RAM matrix and an admission decision based on effective
runtime capacity rather than AVD metadata. KARA has model-switch evidence but
not a separate support-tier qualification here; HQ4 remains rejected.

## Raw ignored evidence

Raw reports remain under ignored `build/` output. The adjacent compact JSON
retains device, runtime, result, and report-integrity fields without local
paths.

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Production worker | 11,937 | `91ff768f43dcf20dfb4bd5b4347469641da982927b25fdf6645f1c76f9cc1606` |
| Direct 9662 parity | 16,604 | `859351a72f278f226996e40b514f3ae22c095117eb6aa7f6b58a7f09c3791fd8` |
| Initial process matrix (test assertion failure) | 12,594 | `d0db2b5a2e59d5f25fcda3503976a0e5676f66395f836df7248a2f9ba6598246` |
| Corrected 20-cycle process matrix | 19,816 | `f62df22f44491ae09fbe911867620a1fa9dc401865249521cf82c7e1c84fa5ac` |
| 20-switch 9662/KARA matrix | 42,752 | `69653a02bd62244f861ffaf9cf0801df4be5702da509ce7e3adef72e7f606ac0` |
| Process fault matrix | 4,613 | `e1df140d1e77984c4faf5de654bcec76daca313cbd232c910812743d3d1257cd` |
| 21-case cache matrix | 25,029 | `b0541765ba41f5114893c7a2b31e56f8dfbaa44d319d2c7daff978757c41185a` |
| FLAC/model cache-race matrix | 6,377 | `4095833cf00b535c563a392b636b1137e4f663aa7a59aff433aae5f0565b6007` |
| Main-process-death recovery | 4,521 | `76f98d864d26d2e077f77c6e6454eb54b5988dcb322e608ca8e742741dc8d81f` |
