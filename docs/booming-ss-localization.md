# Booming SS Localization

Booming SS keeps fork-specific strings in `strings_booming_ss.xml` files.
Do not edit upstream-owned strings or fill upstream translation gaps as part of
Booming SS localization work.

## Scope

- Source of truth: `app/src/main/res/values/strings_booming_ss.xml`
- Localized files: `app/src/main/res/values-*/strings_booming_ss.xml`
- Fork-owned keys currently use these prefixes:
  - `action_source_separation`
  - `source_separation_`
  - `about_booming_ss_`

## Terminology

- `source separation`: use a precise local term for audio-source separation.
  In Simplified Chinese, use `音源分离`.
- `separated playback`: playback using separated stems, not a generic
  separation action.
- `stem blend`: the mix ratio between vocals and instrumental stems.
- Keep technical terms such as `ONNX`, `FLAC`, `WAV`, `PCM`, `SHA-256`, and
  URL labels recognizable unless the locale already has a clear project
  convention.

## Checks

Run this before committing localization updates:

```powershell
.\tools\check_booming_ss_l10n.ps1
```

The check script validates that localized Booming SS files only contain
fork-owned keys and that format placeholders match the default English file.
