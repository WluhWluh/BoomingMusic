# Backup contract v1

This document freezes the portable settings contract implemented by the
versioned `.bmgbak` archive flow. `BackupHelper` stages and validates the
canonical JSON payloads, emits filtered package-named XML compatibility
projections, and restores canonical JSON before falling back to legacy XML
archives. The Android system-backup rules are separate and intentionally
allowlist only the music database; they do not carry this portable settings
archive or any source-separation model data.

## Independent versions

The first format uses three independent constants:

```json
{
  "formatVersion": 1,
  "commonSettingsSchema": 1,
  "sourceSeparationSettingsSchema": 1
}
```

None of these values is derived from the application version. The manifest
also records producer package, flavor, app version, UTC generation time, and a
typed list of payload paths, sizes, and SHA-256 values.

Canonical settings payloads use package-independent paths:

```text
backup-manifest.json
settings/common.json
source_separation/settings.json
```

During Beta, filtered compatibility projections use both package names:

```text
prefs/com.mardous.booming_preferences.xml
prefs/com.wluhwluh.booming.sourcesep_preferences.xml
```

The JSON payload is authoritative. A restore must never apply it and a legacy
projection to the same setting in one operation.

## Common settings

`BackupSettingsPolicy.commonSettingsV1` is the canonical schema table. Its 117
entries cover stable settings from the rebased upstream settings screens. Each
entry records its value type, literal or named dynamic default, and schema
introduction version.

The table deliberately excludes these nine settings-screen keys:

- `backup_data`, `restore_data`, `clear_lyrics`, and `search_for_update` are UI
  commands rather than persisted user settings.
- `lastfm_login` and `listenbrainz_login` contain account state.
- `lyrics_custom_font` can identify a device-local file.
- `source_separation.panel_entry_visible` and
  `source_separation.quick_controls_visible` belong to the fork payload.

Other device-local or runtime keys that are not settings-screen preferences,
including `start_directory`, current page, queue state, update timestamps, and
login/session data, are not added implicitly.

## Source-separation settings

`BackupSettingsPolicy.sourceSeparationSettingsV1` contains exactly 15 portable
preference keys: panel and quick-control visibility, separated playback,
global blend, remember-per-song policy, automatic separation and FLAC,
Snackbar settings, both preroll values, ready-window count, and cache-cleanup
policy and limits.

The payload may additionally contain:

- one active-model reference with stable model ID, artifact SHA-256, contract
  schema version, and optional custom-profile ID; and
- portable custom profile metadata keyed by model SHA-256.

It contains no model file, installed-model record, path, content URI, or
download state. Restoring a reference does not install, download, or activate
a model.

## Explicit exclusions

The following classes are always non-backup data:

- TFLite/ONNX weights and installed-model inventory;
- partial and completed separation cache, manifests, WAV/FLAC stems, hydration
  PCM, calibration data, and cleanup metadata;
- per-song `playback-settings.json` and pending per-song blend preferences;
- downloads, workers, temporary files, and current playback state;
- timing averages, backend statistics, and other device measurements; and
- debug window-decode state, reports, and traces.

`BackupSettingsPolicy.nonBackupRules` records representative storage patterns
for every class. `BackupContractValidator` rejects unsafe ZIP paths and any
manifest payload that resembles a model weight, generated cache entry,
per-song settings file, debug artifact, hydration file, or partial download.
