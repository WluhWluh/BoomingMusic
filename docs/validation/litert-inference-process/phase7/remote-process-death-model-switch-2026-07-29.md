# Phase 7 remote-death model switching

Status: S25 and S10 passed; switching away from a dead run's model isolates
later work under a new model identity and cache key without reviving the
abandoned run

Product foreground-resource fix:

- `c343b53d648c8c4234bec44eac42f846018e09bd`

Product and test-harness revision:

- `591ff7b97e1e1092d4cc1766cbb5f488e9e5226e`
- S10 extension: `2eed0c0954c7ff5044fbc7c6e01022573246ccb6`

Device-test runner: `phase7-runner-v56`

Run-journal schema: 6

## Scope

This is the model-management counterpart to the focused
[remote-death cache invalidation](remote-process-death-cache-clear-2026-07-29.md)
gate. The test starts an independent manual full-song run with 9662, waits for
segment 0 to be committed, externally kills that exact inference-process
incarnation, and observes more than 30 seconds without automatic relaunch.
The old journal and entry digest must remain unchanged while its foreground
service, notification, wake lock, cache lock, and processing ownership are
released.

The test then exercises the production preset repository and runtime facade:

1. deleting active 9662 weights must be rejected;
2. KARA is explicitly selected with internal experimental confirmation;
3. the now-inactive 9662 weights are deleted;
4. the old partial cache must remain `Stale` with `ModelNotInstalled`;
5. resolving the same song must produce KARA's exact identity and a different
   cache key; and
6. an explicit start must create a sequence-1 KARA journal with no committed
   segments and no `PreviousOwnerDied` transition at the first native
   invocation barrier.

The persistent `tryGpu=true` setting remains user intent in the new journal,
so the admitted request retains the bounded OpenCL N=1 identity. KARA's exact
arm64 CPU qualification is `Supported`, while its `gpu-auto-fp32-v1`
qualification is `Unsupported` because the catalog record is rejected. The
Auto factory therefore creates its CPU-direct session before reaching the
barrier and cannot allocate a GPU session for this model. Partial manifests do
not gain a terminal `runtimeRecords` entry, so the report records the explicit
compatibility decision rather than mislabeling the admitted GPU intent as the
actual backend.

The barrier is released after requesting Pause. One KARA window completes at
the normal safe-window boundary, after which the new journal is durably
`Paused` and all foreground resources are released. The harness reinstalls the
exact backed-up 9662 artifact, selects it again, restores the original GPU
preference, and removes the temporary backup even when validation fails.

A separate S10 Compose instrumentation transaction uses the production preset
ViewModel and visible Download, Use, and experimental-confirmation actions to
install and activate KARA, then restores 9662 in cleanup. See
[main-process product state](main-process-product-state-2026-07-29.md).

## Cleanup race found

The first full device attempt exposed a race in orphaned foreground-resource
cleanup. Binder death could arrive while `/proc/<old-pid>/stat` still exposed
the killed process's start ticks. The previous one-shot check then skipped
service and notification cleanup permanently, even though the wake lock and
processing ownership had already been released.

The fix carries a confirmed-death signal from the current generation's
`DeathRecipient`, `DeadObjectException`, or `onServiceDisconnected` path. Those
generation-checked paths clean immediately; connection failures without proof
of process death still require a mismatched or missing `/proc` incarnation.
This avoids both the reaping race and a delayed cleanup task that could stop a
newly started service. A deterministic unit test covers the case where binder
death is confirmed while the old start ticks remain visible.

## Frozen inputs

- Devices: Samsung S25 (`SM-S9310`), API 35, and Samsung S10 (`SM-G9730`),
  API 31; both `arm64-v8a`
- Primary model: `uvr_mdxnet_3_9662@2`
- Primary SHA-256:
  `f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`
- Secondary model: `uvr_mdxnet_kara@2`
- Secondary SHA-256:
  `4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64`
- Model release: `v0.1.0-candidates.1`
- Fixture: `coast_town_full_mp3`
- Fixture SHA-256:
  `f66be47fc846459f8ac92543b38dab2019765b556cff98539339bbb44cabcae3`
- App APK SHA-256:
  `e9ecc335e67cc7f491035f43d6f112a99b3010e140c40395285ef3cf31c0cca7`
- Test APK SHA-256:
  `0fb82b819e8b052cbe3352caa42901139f90719e102fbf9bb7d90eb834efb0a9`
- Catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- Requested GPU profile: `gpu-opencl-bounded-fp32-v1`, LiteRT
  `2.1.5-bss.2`, OpenCL FP32, `kernelBatchSize=1`,
  `commandQueueWindowSize=1`

## Result

| Check | Result |
| --- | --- |
| No automatic relaunch | 30,514 ms; 0 relaunches and 0 unexpected presence samples |
| Active 9662 deletion | Rejected |
| Old cache after model deletion | `Stale`, `ModelNotInstalled`, 1 committed segment preserved |
| New cache identity | KARA key `a28d1df3...` differs from 9662 key `43a1ad5f...` |
| New admission | Sequence 1, 0 segments, 0 previous-owner transitions |
| Process identity | New PID, start ticks, generation, and run ID |
| KARA runtime eligibility | CPU `Supported`; GPU `Unsupported` |
| Post-barrier Pause | `Paused`, 1 KARA segment committed |
| Terminal resources | No service, notification, wake lock, cache owner, or processing owner |
| Final model state | Exact 9662 weights installed and active; KARA remains installed |

The same checks passed on S10. It observed 30,071 ms with no relaunch, rejected
active-model deletion, retained the old cache as `Stale/ModelNotInstalled`,
admitted KARA under cache key `a28d1df3...` at sequence 1 with zero previous
owner transitions, paused after one segment, and restored exact 9662 without
resuming the abandoned generation.

The old 9662 journal remained byte-for-byte unchanged through KARA admission
and Pause. Reinstalling 9662 changed only its availability from
`ModelNotInstalled` back to `InstalledExact`; it did not resume the old run.

## Evidence

Raw reports and input envelopes remain ignored build artifacts under
`build/phase7-validation/remote-death-model-switch/<device>/`.

| Run | Report SHA-256 | Input envelope SHA-256 |
| --- | --- | --- |
| `phase7-s25-remote-death-model-switch-v3` | `42a6a9e2f29314b9ccf553feef14d25fc230968e7fcf342b9c756d37ef33730c` | `7a62389a36923971a3d8621553723e10a5e9aafb0bea8b66ec1f5ca94d92f247` |
| `phase7-s10-remote-death-model-switch-v1` | `66712aa85ddd382925283a4a7c35ee8c7218dfe74b8f7eaee2a60002e2f2216c` | `67e3201ee223bb2879a7b18869ee659f263590bad00055cdf1adfe535df58491` |

## Remaining scope

- Navigate to the visible preset-management action from a recreated
  `MainActivity`; the Download, Use, and experimental-confirmation actions
  themselves have passed through the production ViewModel on S10.
- Verify app/runtime mismatch and an already-latched GPU-to-CPU fallback at
  explicit-retry time.

No source decoder, MP3 fallback boundary, window size, overlap, join placement,
or other listening-derived decode behavior changed for this work.
