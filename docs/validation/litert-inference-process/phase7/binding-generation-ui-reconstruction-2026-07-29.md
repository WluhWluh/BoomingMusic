# Phase 7 Binding Generation and UI Reconstruction

Date: 2026-07-29

## Scope

This checkpoint covers one Phase 7D recovery boundary:

- a callback retained by an older service binding cannot enter the current
  request, observer, or reconnect buffer; and
- adopting a durable independent-run snapshot reconstructs separation state
  without replacing or seeking the song currently owned by playback.

It does not change source decoding, window boundaries, overlap, joining, model
contracts, or backend selection.

## Frozen Inputs

- App commit: `2834688d88a5344727a7e8304e220b9319fbbbcc`
- App APK SHA-256:
  `bb16897e52de00ba9a7974c2c5a89090b1b284a2ed866566bac8108a7926b44f`
- AndroidTest APK SHA-256:
  `cdb90771c6f4cf2bc25ab5b9973265c3b728073882cd2fad83c5755f3da47ffa`
- Device: Galaxy S10, API 31, `arm64-v8a`
- Build fingerprint:
  `samsung/beyond1qltezc/beyond1q:12/SP1A.210812.016/G9730ZCU8HWE2:user/release-keys`

## Results

The JVM IPC test passed with a deterministic two-binding sequence. Binding 1
accepted and drained its event. After deactivation, a late binding-1 callback
and event were rejected. Binding 2 then accepted a different run and process
identity beginning at event sequence 1. Closing the host also rejected later
callbacks. The production client now creates one callback stub and one
reconnect buffer per binding generation, and records stale callback drops in
connection diagnostics.

The complete `SourceSeparationForegroundWorkerRecoveryTest` passed on S10:

- `reconnectedRunGatesNewWorkerAndDefersTerminalCallback`
- `reconnectedSnapshotRestoresWorkerWithoutReplacingPlaybackState`

The second case restored a run for song 42 at durable event sequence 2 while
playback remained on a distinct song 84. Playback retained position `4321 ms`,
duration `12345 ms`, `isPlaying=true`, and blend `0.25`. Recovery exposed the
original run cache key and protected it, made zero runtime calls, issued no
second start, and released the reconnected session after a typed Pause event.

Verification commands completed successfully:

- `:app:testGithubDebugUnitTest` restricted to
  `SourceSeparationExecutionIpcProtocolTest`
- `:app:compileGithubDebugAndroidTestKotlin`
- both S10 recovery instrumentation cases

## Limits

The stale callback ordering is injected deterministically at the client
binding gate; Android did not naturally deliver a retired Binder callback
after the replacement binding during this checkpoint. S25 was not connected
for the final instrumentation repeat. Visible player rendering, playback
readiness, and cache-management UI recreation remain separate open checks.
