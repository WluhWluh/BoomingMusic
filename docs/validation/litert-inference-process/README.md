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
- Later phase directories use
  `<phase>/<yyyy-mm-dd>/<target>/<run-id>.json`.

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
