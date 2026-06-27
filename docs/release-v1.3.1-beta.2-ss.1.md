# Booming SS 1.3.1-beta.2-ss.1

Booming SS is an unofficial source-separation build based on Booming Music. It keeps the original local music player experience and adds local source separation, separated playback, stem blend controls, per-song blend memory, and separated cache management.

This is a beta release. Please expect device-specific performance differences and report Booming SS-specific issues in this repository. If the same issue also happens in the original Booming Music app without Booming SS features, please report it to the upstream Booming Music repository instead.

## Highlights

- Added on-device source separation playback for local songs.
- Added source separation model management with preset download, custom URL download, local import, deletion, and hash display.
- Added source separation cache management, automatic cleanup, and WAV-to-FLAC cache compression.
- Added automatic current-song separation and next-track pre-processing when separated playback needs buffered audio.
- Added stem blend controls, per-song blend memory, and cover lyrics quick controls.
- Added long press on cover lyrics source separation quick controls to open the source separation panel directly over the player.
- Added Booming SS-specific about page entries, repository links, bug-report guidance, and translation guidance.
- Added Booming SS localization across the existing supported languages.
- Rebased onto upstream Booming Music master at 655bb038, including recent lyrics, network, equalizer, and project-structure updates.

## Notes

- Source separation models are not bundled with the app. Download the preset model, provide a custom URL, or import a local ONNX model before using separated playback.
- Source separation can use significant CPU, battery, memory, and storage, especially during the first separation pass for a song.
- Separated playback starts only when enough separated audio is available around the current playback position.
- Cache cleanup settings can be adjusted if storage use becomes too high.

## Known Beta Risks

- Separation speed and playback smoothness depend heavily on device performance.
- Some audio formats may expose alignment or decoding edge cases.
- Very large local libraries may need more cache-management tuning.
- Translations for Booming SS-specific strings are newly added and may still need native-speaker review.

## Recommended Test Areas

- Downloading, importing, deleting, and replacing the ONNX model.
- Starting, pausing, resuming, and clearing source separation for the current song.
- Automatic next-track pre-processing during queue playback.
- Switching between original playback and separated playback while seeking.
- Stem blend controls in the source separation panel and on the lyrics overlay.
- Cache cleanup limits and completed/partial cache deletion.
- RTL and non-English UI layouts.

## Build

- Version name: `1.3.1-beta.2-ss.1`
- Version code: `1310102`
- Application ID: `com.wluhwluh.booming.sourcesep`
- Git tag: `v1.3.1-beta.2-ss.1`
