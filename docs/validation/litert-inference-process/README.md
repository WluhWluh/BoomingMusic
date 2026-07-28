# LiteRT inference-process validation

This directory stores committed contracts, thresholds, compact reports, and
summaries for the
[inference-process roadmap](../../litert-inference-process-roadmap.md).

The validation is intentionally split into two independent decisions:

1. whether LiteRT range execution should run in a dedicated same-UID process;
2. whether that process should own an independent Android background lifetime.

Process isolation must pass before independent background execution is tested.
No report in this directory changes the existing source-decode policy. The
Phase 7 fixture contract remains the source of truth for window/full-song
selection, MP3 handling, output frames, and join placement.

## Layout

- `phase0/`: frozen in-process baseline, protocol, report schema, and
  comparison thresholds.
- `phase1/`: in-process host-boundary parity and device-validation summary.
- `phase2/`: bound remote-process startup, IPC, output-parity, device, and
  background-semantics summary.
- `phase3/`: pure-x86 resident-session, whole-process recycle, playback, and
  fault-classification evidence.
- `phase4/`: OS-backed exact-entry ownership, durable-journal recovery,
  cache-loss, management-race, main-process-death, and arm64 qualification
  evidence.
- `phase5/`: ABI host/session policy, GPU fallback, playback contention, and
  foreground GPU UI attribution and tuning evidence.
- Later phase directories use
  `<phase>/<yyyy-mm-dd>/<target>/<run-id>.json`.

The Phase 5 GPU records include the stock-runtime
[UI contention attribution](phase5/gpu-ui-contention-2026-07-26.md) and the
follow-up [bounded OpenCL queue experiment](phase5/gpu-opencl-queue-window-2026-07-26.md).
The final packaged capability and real-model checkpoints are recorded in the
[bounded runtime capability smoke](phase5/bounded-runtime-capability-2026-07-27.md)
and [bounded 9662 invocation smoke](phase5/bounded-9662-smoke-2026-07-27.md).

Large profiler traces, PCM/stem exports, `/proc/<pid>/maps` snapshots,
and repeated-run logs remain under ignored `build/` output. A
committed summary must identify hashes for any ignored evidence used in a
decision.

## Required identity

Every committed report records the app commit and dirty-tree state, package,
flavor, ABI, API, device fingerprint, model and contract identity, process
mode and generation, concrete backend, source fixture and decode route, cache
key, foreground/wake-lock state, per-process and summed memory, timings,
process deaths, restart count, and terminal result.
