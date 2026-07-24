# Phase 0 lifecycle and ownership baseline

Status: frozen before the process-host refactor

Baseline app revision: `c948e9d8`

## Current ownership

| Concern | Current owner | Lifetime and consequence |
| --- | --- | --- |
| Run admission and scheduling | `SourceSeparationForegroundWorkerCoordinator` singleton | Koin singleton in the main app process |
| Worker coroutine | coordinator `SupervisorJob + Dispatchers.IO` | independent from Activity and PlaybackService coroutine scopes, but dies with the app process |
| Exact model/source resolution | `DefaultSourceSeparationRuntimeFacade` | main process, frozen before one engine call |
| Cache run lease | `SourceSeparationCacheEntryLeaseRegistry` | in-memory only; valid only while all mutations are in one process |
| Cache manifest transitions | `SourceSeparationCacheRunCoordinator` | main process; writes Running/Completed/Canceled/Failed atomically |
| Source decode, DSP, LiteRT, and segment files | `MdxRangeSeparator` | main process through the production range executor |
| LiteRT session | `SingleUseMdxInferenceSessionProvider` | created and closed for each range execution |
| GPU-to-CPU fallback | `MdxLiteRtAutoInferenceSessionFactory` | one-way within the same range execution |
| Playback readiness and mixing | `PlaybackService` | main process and MediaSession lifetime |
| Processing FGS type | `PlaybackService` | API 35+, only while playback is waiting for cache |
| Processing wake lock | `PlaybackService` | only while playback is waiting for cache; renewed by the processing lease |
| Completion callback | `PlayerViewModel` when attached | UI refresh, prune request, cleanup request, and optional FLAC promotion |
| FLAC promotion | `PlayerViewModel` through runtime facade | ViewModel coroutine; not part of the range worker's durable lifetime |
| Hydration | `PlaybackService`/UI runtime calls | playback-side concern and remains in the main process |

## Lifecycle observations

| Event | Current behavior |
| --- | --- |
| Activity destroyed | Coordinator singleton and worker can continue while the app process remains; ViewModel callbacks detach |
| UI sent Home | Phase 7 S10/S25 service reports completed successfully with process importance 125 |
| PlaybackService alive | Its foreground media-playback process indirectly protects the in-process worker |
| Playback waits for cache | PlaybackService additionally claims `mediaProcessing` and holds a partial wake lock |
| PlaybackService destroyed | Coordinator scope is not directly canceled, but no independent processing FGS protects a remaining full-song run |
| Recents removed | PlaybackService follows `STOP_WHEN_CLOSED_FROM_RECENTS`; there is no separate inference-service policy |
| Main process killed | Coordinator, cache lease, LiteRT session, and run all die together |
| Native/runtime exception | Engine records Failed, releases its run lease, and original playback remains available |
| Pause | Engine records the current manifest as resumable Running and closes the single-use session |
| Cancel | Engine records Canceled, resets a Running segment to Queued, and releases the lease |
| Process restart | Cache-store recovery removes unsafe staging and resets noncommitted segment state; completed entries remain exact-identity playable |

## Single-process assumptions

- `SourceSeparationCacheEntryLeaseRegistry` is an in-memory reader /
  run-writer / exclusive lock.
- Coordinator worker, pending request, active request, pause/cancel flags,
  playback position, and callback references are in memory.
- Koin creates one source-separation repository graph per process; there is no
  cross-process singleton.
- SharedPreferences is read by the coordinator and model repository without a
  multi-process coherence protocol.
- Completion callbacks assume the main process and usually a PlayerViewModel
  still exist.
- The run object contains a live in-memory lease and absolute File objects.
- `stopSelf()` or unbinding a Service does not guarantee process
  termination or a fresh 32-bit address space.

## Baseline background evidence

The committed Phase 7 objective reports remain the numerical and behavioral
oracle:

- S10 arm64 background Auto run: first ready 8,037 ms, full song 162,018 ms,
  Home after ready, exact cache completed and playable.
- S25 arm64 background Auto run: first ready 2,845 ms, full song 51,369 ms,
  Home after ready, exact cache completed and playable.
- S10/S25 prefetch: exactly two ready windows, stopped before full completion,
  and retained exact cache identity across transition.
- S10 arm32, S10 arm64, S25 arm64, and x86_64 sequential lifecycle: four
  single-use sessions each; pause/resume, seek, and cancellation passed.
- Pure x86: same-session inference and cancellation pass, but production-shaped
  session recreation is unsafe and remains fail-closed.

## Phase 2 ownership target

Bound-remote mode changes only the range-execution owner:

- Main process keeps admission, exact cache lease, manifest state, playback,
  scheduling, current FGS/wake-lock behavior, FLAC promotion, and hydration.
- Dedicated process owns source decode, DSP, LiteRT session, and writes range
  work/segment files under the already leased exact cache entry.
- Main process remains authoritative for prepared/segment/terminal manifest
  transitions until the later cross-process cache phase.
- Binder death pauses or fails the current run and releases the main-process
  lease. It does not authorize an in-process retry.

This boundary allows process and x86 allocator experiments without silently
changing background behavior or requiring cross-process cache ownership first.

