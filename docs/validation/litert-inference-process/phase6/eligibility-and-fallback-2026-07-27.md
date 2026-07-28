# Phase 6A run eligibility and fallback checkpoint

Status: implementation complete; independent foreground execution remains
disabled

Final implementation revision tested:
`1ae10d5fd063026fa44a899527c299768c7d737d`

Production execution mode: `InProcess`

Experimental isolated execution mode: `BoundRemote`

Execution protocol: 8

Run-journal schema: 5

## Decision

Phase 6A freezes why an inference run exists before adding a second foreground
service. The same run class and background policy now survive admission, IPC,
host diagnostics, and the durable cache journal:

| Run class | Background policy | Initial independent-FGS eligibility |
| --- | --- | --- |
| `ManualFullSong` | `IndependentForegroundEligible` | Eligible only after an explicit user command |
| `PlaybackDemandWindow` | `PlaybackServiceOwned` | Not eligible; remains owned by playback intent |
| `NextSongPrefetch` | `ClientBound` | Not eligible; loss of the live client decision stops it |

Request merging keeps the strongest intent in the order manual, playback
demand, then prefetch. A running cache entry cannot change class. A paused
prefetch entry may be explicitly readmitted as playback-demand work, and the
journal retains both decisions.

Model download, model activation/deletion, cache management, FLAC promotion,
and hydration are not inference run classes. They remain under their existing
owners. Host and foreground-lifetime selection remains an internal
implementation choice; no corresponding preference was added to user settings
or backup payloads.

## Frozen GPU state

Protocol 8 and journal schema 5 keep these concepts separate:

- `tryGpu` is the user intent frozen at admission;
- the admitted runtime identity is the exact bounded GPU profile and custom
  LiteRT artifact identity;
- `backendPolicy` is the effective backend for the current attempt;
- `gpuFallbackLatch` records the first accepted one-way transition to CPU.

The range executor observes structured runtime diagnostics after session
acquisition and after every inference. The first non-null fallback stage is
emitted synchronously and durably journaled before PCM conversion or a later
segment-ready publication. An identical duplicate is idempotent; a conflicting
latch is rejected.

After pause, process death, or explicit restart, a latched run keeps
`tryGpu=true` and the exact bounded runtime identity for auditability but is
reconstructed with `backendPolicy=Cpu`. It cannot retry GPU, use the stock
unbounded runtime, or reinterpret a later preference change. CPU-only runs
cannot carry a GPU fallback latch.

## Verification

The GitHub debug suite passed:

```text
./gradlew :app:testGithubDebugUnitTest \
  :app:compileGithubDebugAndroidTestKotlin
```

Result: 265 unit tests, zero failures, zero errors, zero skipped; AndroidTest
Kotlin compilation passed.

Focused tests cover:

- all three run classes across admission, IPC, diagnostics, and journal state;
- priority-preserving request merges and paused-prefetch readmission;
- strict protocol-8 serialization of the typed fallback event;
- one durable fallback transition despite duplicate identical notifications;
- rejection of a conflicting latch;
- pause and restart on CPU without losing `tryGpu` or the exact bounded-AAR
  identity; and
- rejection of a resumed request that omits or changes a previously persisted
  fallback latch.

## Open validation

This checkpoint does not claim that an independent media-processing foreground
service exists or that background continuation is enabled. Phase 6B-6D still
must implement and test notification ownership, foreground-service deadlines,
wake-lock transfer, screen-off execution, timeout handling, and process death.

Real-device fallback injection with the final bounded AAR, repeated full-song
S10/S25 pairs, foreground interaction traces, thermal coverage, and a
vendor-diverse GPU matrix remain release gates. The existing short 9662 S10 and
S25 smokes prove the admitted bounded runtime can execute but did not trigger
fallback.

No decoder, source-window strategy, MP3 fallback threshold, overlap
calibration, join placement, or listening-derived policy changed in Phase 6A.
