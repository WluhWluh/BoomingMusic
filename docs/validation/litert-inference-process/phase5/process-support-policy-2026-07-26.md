# Phase 5 process and support policy checkpoint

Status: policy selected; production promotion deferred; S25 GPU host evidence
still open

## Policy table

| ABI / backend | Tier | Release host | Concrete host | Session | Scope | Background owner |
| --- | --- | --- | --- | --- | --- | --- |
| arm64-v8a CPU FP32 | Supported | `InProcess` | `InProcess` | `SingleUse` | Exact 9662 host evidence; other models retain independent catalog qualification | Client-bound PlaybackService/main process |
| arm64-v8a Auto to GPU FP32 | Experimental | `InProcess` | `InProcess` | `SingleUse` | Exact 9662 `gpu-auto-fp32-v1`; eligible devices only | Client-bound PlaybackService/main process |
| armeabi-v7a Auto to CPU FP32 | Supported | `RemoteRequired` | `BoundRemote` | `SingleUse` | Exact 9662 host evidence; other models retain independent catalog qualification | Client-bound main process |
| armeabi-v7a GPU | Unsupported | None | None | None | No packaged or qualified accelerator | None |
| x86_64 Auto to CPU FP32 | Supported | `InProcess` | `InProcess` | `SingleUse` | Exact 9662 host evidence; other models retain independent catalog qualification | Client-bound PlaybackService/main process |
| x86 Auto to CPU FP32 | Experimental | `RemoteRequired` | `BoundRemote` | `ResidentUntilProcessExit` | Exact 9662 artifact/contract and qualified resource floor only | Client-bound main process |
| x86 all other models or GPU | Unsupported | None | None | None | KARA, HQ4, other catalog models, sidecars, and custom imports | None |

These are Phase 5 policy selections, not a claim that every selected row is
already enabled in normal builds. Production remains in process on non-x86,
and pure x86 remains fail-closed until Phase 9 release qualification and
promotion. The table fixes the candidate architecture so later background
work cannot silently change host and session policies while testing a
different axis.

## Decisions

### Arm32

`armeabi-v7a` selects `RemoteRequired`, not `RemotePreferred`. The in-process
oracle completed, but it placed a 755.5 MiB median peak in the 32-bit main
playback process. Bound remote reduced median main-process peak PSS to 150.1
MiB, preserved at least a 464.2 MiB remote free-address gap, completed faster
in the measured matrix, and passed cache/process-death recovery. Allowing a
runtime or release fallback to the high-pressure main-process path would undo
the reason to isolate this ABI.

The arm32 resident experiment found no setup or reliability gain and retained
roughly 633-657 MiB after work, so its selected session remains `SingleUse`.

### Arm64 and x86_64

CPU remote execution added roughly 18-25 MiB median summed PSS without a
reliability benefit. S10 GPU remote execution added 32.1 MiB median summed PSS
and moved, rather than reduced, the graphics allocation. These 64-bit targets
remain in process with single-use sessions.

Arm64 Auto remains experimental at this checkpoint because exact 9662 passed
the S10 full-song host matrix and both device fault matrices, while the S25
full-song paired host matrix was deferred. KARA GPU and FP16 remain rejected.

### Pure x86

Pure x86 selects the narrow candidate
`Experimental + RemoteRequired + ResidentUntilProcessExit + client-bound` for
exact `uvr_mdxnet_3_9662@2`, artifact SHA-256
`f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`.
Both main and remote processes must report `Runtime.maxMemory()` of at least
128 MiB before native allocation. Android API 26 or later is required.

The approximately 2 GiB API 29 guest is the smallest tested total-memory
envelope, but it needed a privileged ART-property override to expose the
qualified heap. That is evidence, not a published minimum-RAM promise. The app
can enforce the effective runtime heap; it cannot repair an inadequate device
configuration.

KARA's model-switch stress does not qualify it as a selectable x86 model. HQ4,
all additional models, sidecars, and custom imports remain unsupported on x86
and must fail before native allocation. An unverified-model flow is rejected
for this phase because an arbitrary contract can invalidate the Java tensor,
resident-session, and address-space envelope.

### Common path and background ownership

A common all-ABI remote path is rejected. It would charge every 64-bit CPU/GPU
run the second-process overhead without measured reliability benefit, while
arm32 and pure x86 have specific address-space and lifecycle reasons for
isolation.

No failed remote production host may fall back to in-process inference at
runtime. All selected remote rows remain client-bound; they do not outlive the
main process and do not imply an independent media-processing foreground
service. The branch may carry qualified `BoundRemote` isolation forward even
if Phases 6-8 later reject independent background execution.

## Remaining evidence

Phase 5E's policy checkpoint is complete. Phase 5 as a whole remains open for
the explicitly deferred S25 three-pair full-song GPU host matrix. That result
may confirm the arm64 in-process selection or reveal a device-specific issue;
it cannot silently broaden model or session scope.

No decoder, MP3 fallback threshold, overlap calibration, join placement, or
listening-derived window policy changed.

The machine-readable record is
[`process-support-policy-v1.json`](process-support-policy-v1.json).
