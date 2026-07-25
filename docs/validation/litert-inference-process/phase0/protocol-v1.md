# Inference host protocol v1

Status: frozen Phase 0 contract

Protocol version: `1`

Journal schema version: `1`

## Scope

Protocol v1 transports one already-admitted model-aware range execution between
the main process and a private same-UID inference process. Model selection,
source preflight, cache run admission, playback, model management, FLAC
promotion, and hydration remain outside the protocol through Phase 3.

The transport uses versioned Binder/AIDL messages containing UTF-8 JSON.
Keeping the domain DTOs independent from Android Parcelable classes lets the
in-process host and tests use the same contract.

## Identity

Every start request includes:

- `protocolVersion`;
- unique `commandId` and `runId`;
- expected remote `processGeneration`;
- exact `cacheKey` and complete cache identity;
- immutable cache contract snapshot;
- model ID, artifact byte size and SHA-256, contract ID/schema/fingerprint,
  profile revision, pipeline ID/version, and runtime settings;
- source URI, display name, expected source fingerprint, and source
  diagnostics;
- run class and window-decode-enabled flag;
- initial playback position and ready-window count; and
- resumable segment state expressed with entry-relative paths.

The remote process resolves the installed artifact independently by immutable
identity, validates its canonical location and hash, rebuilds the execution
profile, and compares every contract/identity field before opening LiteRT.
It never reads the currently active model as a substitute.

## Commands

| Command | Meaning | Idempotence |
| --- | --- | --- |
| `start` | Start one exact range execution | duplicate command ID returns current snapshot |
| `updateControl` | Update playback position, ready-window count, pause, or cancel | latest monotonic control sequence wins |
| `snapshot` | Return complete state for one generation | read-only |
| `closeRun` | Release one completed/paused/failed run handle | duplicate is accepted |
| `requestRecycle` | Quiesce and acknowledge a dedicated-process recycle | accepted only without a live native invocation |

Only one run may execute in one Phase 2/3 inference process. A second distinct
start receives Busy.

## Events

Every event contains protocol version, run ID, process generation, and
monotonic event sequence.

| Event | Required payload |
| --- | --- |
| `accepted` | exact identity and process snapshot |
| `progress` | completed/total windows, stage, decode diagnostics, scheduler state |
| `prepared` | relative outputs, source fingerprint/format, frame counts, segment plan |
| `segmentState` | segment index and state |
| `completed` | result files, timing/runtime/decode diagnostics, final segment plan |
| `paused` | last committed segment and typed reason |
| `canceled` | typed reason |
| `failed` | stable error category and diagnostic detail |
| `recycleReady` | old generation and last closed run |

Progress may be coalesced, but Prepared, every segment transition, and one
terminal event must be delivered in order. A reconnecting client requests a
snapshot before applying incremental events.

## Binder limits

- UTF-8 request or event payload hard limit: 512 KiB.
- No model bytes, PCM, tensor, stem sample, full timing text, or profiler data.
- No callback queue may contain more than one replaceable Progress event.
- Relative entry paths are validated before resolution.
- Absolute model/cache paths are not persisted as identity.

## Control semantics

- Pause is cooperative and preserves the last atomically committed window.
- Cancel is cooperative around noninterruptible native invocation, then
  records Canceled in the main-process coordinator.
- Binder caller cancellation sends Cancel before abandoning the response wait.
- Stale generation or stale control sequence is rejected.
- Cancellation never invokes GPU-to-CPU fallback.
- Remote failure never invokes an automatic in-process host fallback.

## Phase 2 process behavior

- The remote Service is bound-only and nonsticky.
- Loss of the main-process binder requests a controlled pause/cancel.
- Current PlaybackService FGS and wake-lock semantics remain unchanged.
- Service unbind/stop is not treated as proof that the process exited.

## Phase 3 x86 behavior

- A successful same-model lease release retains one reusable LiteRT session.
- Pause/resume and a later same-model run reuse the exact session key.
- A different artifact/profile/runtime key requires process recycle, not a
  second large session in the old 32-bit process.
- Fatal native state requires recycle.
- Recycle must be acknowledged, followed by Binder death and a new generation,
  before another start is accepted.
- Sticky restart must not revive the old generation or command.

## Durable journal v1

The later independent process stores `run-journal-v1.json` inside the
exact cache entry. Phase 0 freezes these fields even though Phase 2 keeps the
main-process cache coordinator:

- schema version, run ID, command ID, process generation;
- complete immutable cache/model/source/runtime identity;
- host mode and concrete backend;
- lifecycle state and reason;
- highest accepted control and event sequence;
- last committed segment/window;
- setup/invocation/output/commit stage;
- retry count and next eligible retry time;
- created/updated timestamps; and
- terminal flag.

The journal is cache data, is not backed up, and cannot make an entry playable.
The cache manifest remains authoritative for playback.

## Recovery matrix

| Terminal/death reason | Automatic recovery allowed |
| --- | --- |
| Unexpected remote death with intact nonterminal cache | bounded later phase only |
| System low-memory kill with intact nonterminal cache | bounded later phase only |
| Main-process death in BoundRemote mode | no; controlled pause |
| User pause | no |
| User cancel | no |
| FGS timeout | no |
| Cache cleared or identity missing | no |
| Model missing/hash mismatch/contract mismatch | no |
| Unsupported ABI/backend | no |
| Repeated allocation/native fatal failure | no after retry budget |

Phase 2 and Phase 3 do not automatically restart a killed run. They prove
isolation, parity, and deterministic x86 process recycling first.

## Rollback

- `InProcess` remains the production default while BoundRemote is
  internal-only.
- Host selection is construction-time/internal test state and is excluded from
  backup.
- Supported production ABIs can return to InProcess without changing model,
  contract, or cache formats.
- Pure x86 remains unsupported. Passing Phase 3 is necessary but not
  sufficient; cache safety, memory scope, all-ABI policy, and release
  qualification remain later gates.
- A failed remote execution terminates visibly; it never retries through
  InProcess against the same cache entry.
