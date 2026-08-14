# Source-separation ADB debug control

The GitHub Debug build exposes a synchronous `ContentProvider` for source-separation testing. It is not present in release builds. Calls are accepted only from the app UID, Android shell UID, or root.

## Setup

Build and install the ABI-specific Debug APK, then launch the app before playback tests so Android can grant audio focus:

```powershell
.\gradlew.bat :app:assembleGithubDebug
$apk = Get-ChildItem app/build/outputs/apk/github/debug -Filter '*-github-arm64-v8a.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
adb -s 192.168.8.197:5555 install -r -d $apk.FullName

.\tools\source_separation_debug_control.ps1 -Serial 192.168.8.197:5555 -Command ui.launch
```

The default package is `com.wluhwluh.booming.sourcesep.debug`. For another Debug application ID, pass `-PackageName`.

## Calling commands

Use the helper for typed extras and JSON responses:

```powershell
$ctl = '.\tools\source_separation_debug_control.ps1'

& $ctl -Serial 192.168.8.197:5555 -Command help
& $ctl -Serial 192.168.8.197:5555 -Command state
& $ctl -Serial 192.168.8.197:5555 -Command playback.song `
    -Extra 'song_id:l:1144','play:b:true'
& $ctl -Serial 192.168.8.197:5555 -Command playback.seek `
    -Extra 'position_ms:l:30000'
```

Extra syntax is `key:type:value`. Supported types match Android's `content` utility: `b` boolean, `s` string, `i` integer, `l` long, `f` float, and `d` double.
The helper quotes every remote-shell argument, so string values may contain spaces,
apostrophes, parentheses, and non-ASCII song or model names.

The equivalent direct call is:

```powershell
adb -s 192.168.8.197:5555 shell content call `
  --uri content://com.wluhwluh.booming.sourcesep.debug.debug-control `
  --method playback.song `
  --extra song_id:l:1144 `
  --extra play:b:true
```

## Playback and separation

Playback commands:

- `playback.play`, `playback.pause`, `playback.toggle`, `playback.stop`
- `playback.next`, `playback.previous`
- `playback.seek` with `position_ms:l`
- `playback.seek_percent` with `percent:f` in the inclusive `0..100` range
- `playback.song` with one of `song_id:l`, `path:s`, or `query:s`; optional `first:b`, `play:b`, and `position_ms:l`
- `playback.queue`

Separation commands:

- `separation.output` with `enabled:b`; optional `auto_sync:b`, `expect_processing:b`, and `blend:f`. With the default transition and processing flags, this follows the same `PlayerViewModel` path as the UI when the player is open; non-default flags retain the direct playback-service diagnostic path.
- `separation.sync` with optional `allow_new_session:b`, `expect_processing:b`, and `prefer_completed:b`
- `separation.blend` with `blend:f`; optional `persist:b`
- `separation.stem_gains` with all active stems encoded as one string, for example `gains:s:drums=1,bass=0.5,other=0,vocals=1`; optional `persist:b`
- `separation.start`, `separation.resume`, `separation.pause`, `separation.cancel`
- `separation.prestart` with one of `song_id:l`, `path:s`, or `query:s`;
  optional `first:b` and `ready_windows:i` (default 2). This follows the same
  next-song preprocessing path as playback and pauses after the requested
  start-window waterline is ready.
- `separation.marker` with `marker:s`
- `separation.samples`, `separation.samples.clear`
- `separation.process.terminate` requests Debug-only termination of the shared
  inference process through its Binder validation endpoint. Use it only while
  validating remote-death recovery; the current partial cache is retained.

Mix commits use the same ViewModel path as the UI while it is present. Without a ViewModel, the provider applies the equivalent global or per-song persistence before sending one MediaSession command.

## Settings and resources

Read settings with `settings.get`. `settings.set` accepts any subset of:

```text
mix_mode:s:off|global|per_song
auto_start:b, gpu_enabled:b, window_decode:b, auto_flac:b
snackbar_progress:b, snackbar_messages:b
preroll_ms:l, ready_windows:i
auto_cleanup:b, partial_limit:i, completed_limit:i
npu_enabled:b:false
```

Inventory and mutation commands:

- `cache.list`, `cache.activate`, `cache.delete`, `cache.delete_all`, `cache.promote`, `cache.cleanup`
- `model.list`, `model.install`, `model.select`, `model.delete`
- `runtime.list`, `runtime.install`, `runtime.repair`, `runtime.activate`, `runtime.remove`
- `setup.plan`, `setup.execute`

Cache activation/deletion/promotion commands accept `cache_key:s:<key>` or `current:b:true`. `cache.activate` follows the Cache Management action contract and requires the exact installed MDX or HTDemucs artifact and executable contract carried by that cache. Model selection/deletion accepts `model_id:s:<id>` or `sha256:s:<hash>`; imported MDX custom profiles may add `profile_id:s:<id>`. Runtime mutations accept `runtime_kind:s:cpu|gpu` and optional `component_id:s:<id>` and are limited to the current process ABI. NPU is intentionally reported as unsupported.

Cache deletion follows the UI path: disable current separated output, cancel matching work, delete through the runtime facade, and notify playback. Runtime and model downloads are large; inspect inventory before mutating a device.

`setup.plan` runs the product readiness evaluator and Quick Setup planner without
mutating resources. `setup.execute` runs the same planner and executor transaction
as the UI and returns an operation ID. Both accept `mode:s:restore_recommended`,
`repair_current`, or `bootstrap_recommended`; `verify:b:true` opts into full local
payload hashing. The default trusted-metadata check matches normal product entry.
Their JSON records the active family, model ID, generation, proposed selection,
items, and final selection so cross-family preservation can be asserted directly.

## Long operations

Mutations return an `operationId`. Poll manually with `operation.get`, list recent operations with `operation.list`, or cancel one with `operation.cancel`:

```powershell
& $ctl -Serial 192.168.8.197:5555 -Command cache.cleanup -Wait
& $ctl -Serial 192.168.8.197:5555 -Command operation.get `
    -Extra 'operation_id:s:op-...'
```

`-Wait` polls until `succeeded`, `failed`, or `canceled`. Duplicate active operations for the same command and target are rejected.

## Diagnostics

`state` aggregates playback, queue, separation, worker, settings, cache, model, runtime, and operation state. It uses only local metadata and does not refresh remote catalogs or verify full payload hashes.

Export and pull a diagnostic archive:

```powershell
& $ctl -Serial 192.168.8.197:5555 -Command diagnostics.export `
    -Wait -PullTo '.artifacts/debug-diagnostics/'
```

The ZIP contains `manifest.json`, `state.json`, inference-process diagnostics, worker window samples, cache event JSONL when available, and the flushed playback-gate trace. It may contain song titles and local media paths, but it does not contain song or separated-audio bytes, model weights, or runtime binaries. The app retains the five newest exports under its external files directory.
The operation result also returns the archive byte size and SHA-256 for transfer verification.
