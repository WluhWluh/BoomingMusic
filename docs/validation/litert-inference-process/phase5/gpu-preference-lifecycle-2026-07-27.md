# Phase 5F GPU preference lifecycle

Status: preference persistence and portable restore checks passed

## Product contract

`source_separation.try_gpu` has a missing-key default of `true`. A stored value
is read at run admission, while a backup restore writes the key only when the
selected source-separation payload actually contains it. Schema v1 and
upstream backups therefore do not overwrite the destination device's current
GPU preference.

The portable preference writer used by `BackupHelper` is now an internal,
stateless extension in the backup package. The production restore path and the
device test invoke the same implementation.

## Automated checks

`BackupGpuPreferenceDeviceTest` passed on the API 35 Samsung S25:

- a missing key resolves through `DEFAULT_SOURCE_SEPARATION_TRY_GPU=true`;
- an explicitly committed `false` remains present when the preference file is
  reopened;
- a schema v2 restore applies both `false` and `true` values;
- a schema v1 restore containing another source-separation setting leaves an
  existing `false` GPU value unchanged; and
- an isolated restart probe writes `false` plus a marker and reads both back.

The restart probe was also executed as two separate instrumentation commands.
The target package was force-stopped after the writer completed and before the
reader started. The reader passed, then removed the isolated test preference
file contents. It did not modify the application's default preference file.

The existing `BackupContractV1Test` and `BackupCodecTest` suites also passed.
They continue to enforce the schema-2 introduction point, strict type checks,
and the rule that schema v1 decoding does not synthesize the GPU key.

## Limits

This verifies storage and restore semantics, not Compose rendering or an
in-flight backend transition. Active and paused run immutability is covered by
the engine/journal tests; foreground, background, and screen-off UI toggles
remain open for the later device lifecycle matrix.
