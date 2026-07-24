# Phase 2 bound remote-process validation

Status: passed for the internal-only `BoundRemote` prototype

Final implementation revision tested:
`4022dcc290c7f8bdb64703a165a4b4042c75d587`

Exact paired-parity revision:
`2ef4523eee75ee1b40ae856ff68f90053df7e8df`

Production execution mode: `InProcess`

Model: `UVR_MDXNET_3_9662` FP32

Artifact SHA-256:
`f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378`

## Boundary result

Phase 2 adds a private, non-exported bound Service in the same UID and the
`:source_separation` process. The application resolves its process before
starting Koin. The inference process receives only its small preset repository
graph and returns before default-preference population, crash UI, StrictMode,
day/night, image loading, and other main-process startup work.

The main process still owns admission, the cache run-writer lease, manifest
transitions, `PlaybackService`, foreground and wake behavior, FLAC promotion,
hydration, and playback mixing. The remote process owns source decode, the
unchanged DSP and LiteRT range executor, and segment/work-file generation.
PCM and tensors remain local to that process and never cross Binder.

The AIDL surface carries strict UTF-8 JSON with a 512 KiB payload limit.
Commands and events include protocol, run, process-generation, cache, model,
contract, artifact, source, and runtime identities. Durable events remain
ordered while at most one pending progress event may be replaced. Duplicate
commands are bounded by a command ledger, and stale generations or mismatched
identities are rejected before model setup or cache writes.

The service revalidates canonical app-private model and cache roots, artifact
hash, source identity, and requested workspace. Malformed protocol input,
missing or mismatched models, unavailable sources, and invalid cache roots
return distinct admission failures. Client callback failure, binder death,
timeout, close, and rebind are retained as host lifecycle states rather than
being converted into an ordinary cooperative pause.

## Automated validation

The complete `:app:testGithubDebugUnitTest` suite and
`:app:lintGithubDebug` passed at the final revision. The unit suite covers IPC
size and identity checks, progress coalescing, command deduplication, typed
admission failures, host teardown, stale callback generations, binder death,
and exact model-aware engine ownership.

`SourceSeparationInferenceProcessDeviceTest` passed startup, close, repeated
close, rebind, remote-process kill, binder-death observation, and clean
generation replacement on all four connected targets:

| Target | Android API | Process ABI | Inference scope |
| --- | ---: | --- | --- |
| Samsung Galaxy S25 | 35 | arm64-v8a | startup and worker |
| Samsung Galaxy S10 | 31 | arm64-v8a and armeabi-v7a | startup and worker |
| Android emulator | 37 | x86_64 | startup and worker |
| Android emulator | 26 | x86 | startup only |

The worker matrix also passed same-UID `content://` MediaStore sources on S25
arm64, S10 arm64, S10 arm32, and x86_64. Model imports do not require a remote
document grant because the selected TFLite artifact is copied into app-private
storage before a run is admitted.

PowerShell validation scripts parsed and ran under PowerShell 7 with
`pwsh -NoProfile`.

## Idle startup

The frozen settled idle-remote gate is 96 MiB before LiteRT setup. The startup
test waits two seconds after binding and samples the remote PID directly. It
passed on S25 arm64, both S10 process ABIs, x86_64, and pure x86. This settled
gate is separate from the worker report's immediate bind-time memory field.

An earlier S25 failure near 103 MiB was traced to debug APK packaging rather
than the inference dependency graph. The APK contained 83,991,148 uncompressed
DEX bytes, and the remote process showed an approximately 80,960 KiB anonymous
read-only mapping with about 59,392 KiB in `AnonHugePages`. Debug variants now
package the unminified multidex payload as file-backed, uncompressed DEX with
`variant.packaging.dex.useLegacyPackaging.set(false)`. Release packaging is
unchanged.

## Exact paired parity

The S25 pair used the same application revision, clean pinned-model
acquisition, `coast_town_full_wav` fixture, model contract, Auto/GPU profile,
and exact cache identity. Each worker deleted any existing exact cache entry
before admission.

| Metric | `InProcess` | `BoundRemote` | Result |
| --- | ---: | ---: | --- |
| Backend used | LiteRtGpu | LiteRtGpu | matched |
| Decode route | Window / WAV | Window / WAV | matched |
| First ready | 3,663 ms | 3,428 ms | passed |
| Full song | 48,335 ms | 41,935 ms | passed |
| Output frames | 12,070,130 | 12,070,130 | exact |
| Peak summed PSS | 656,099,328 | 414,290,944 | recorded |
| Cache key | `469fd161...c19dd34` | `469fd161...c19dd34` | exact |
| Final event sequence | 203 | 203 | exact |
| Terminal cache state | completed/playable | completed/playable | matched |
| Hydration | passed | passed | matched |

The remote callback stream delivered 201 events instead of 203 because two
replaceable progress updates were coalesced. All durable `Accepted`,
`Prepared`, `SegmentStateChanged`, and `Completed` events retained order; the
terminal sequence remained 203. This is the bounded protocol behavior, not a
lost durable transition.

Completed output bytes are identical:

| Stem | WAV SHA-256 | FLAC SHA-256 |
| --- | --- | --- |
| Vocals | `bc9b564be8b05a828c890c0ae6cfcb03bcfb40104df7fecf4d3a9e9d57eb2c91` | `fc36b957e7e3f274a82bcc450a8a8342c13ad2c820352e54b3dff75f2b5efa9b` |
| Instrumental | `b5ec4830cc968b9633299053b6ac1b53348d4e2eafccd78a9e4ce33561e19a1d` | `e5727252ab9b42cc072e4e8e4ea7978d2a3f331a1449112032fc7adf91a536b4` |

Manifest comparison also matched identity, contract snapshot, completed state,
segment plan and joins, output shape and integrity, decode route, and runtime
profile. Expected run-local song IDs, internal source paths, timestamps, and
elapsed measurements differ, so the serialized manifests are not asserted to
be byte-identical.

A final full-song `BoundRemote` rerun at revision `4022dcc2` reproduced the
same cache key, route, 12,070,130 frames, all four output hashes, durable event
order, completed cache, promotion, and hydration. It reached first-ready in
3,482 ms, completed in 35,447 ms, and recorded 419,319,808 bytes peak summed
PSS.

## Device worker matrix

The final revision ran a bounded 529,200-frame Window/WAV source on each ABI
that currently supports inference:

| Target | ABI / backend | First ready | Total | Peak summed PSS | Result |
| --- | --- | ---: | ---: | ---: | --- |
| S25 | arm64-v8a / GPU | 3,481 ms | 4,488 ms | 371,016,704 | passed |
| S10 | arm64-v8a / GPU | 15,621 ms | 20,910 ms | 606,779,392 | passed |
| S10 | armeabi-v7a / CPU | 9,667 ms | 13,633 ms | 794,059,776 | passed |
| API 37 emulator | x86_64 / CPU | 20,594 ms | 26,680 ms | 1,055,556,608 | passed |

Pure x86 service startup and lifecycle passed, but inference remains
fail-closed. Its reusable-session and recycle strategy is Phase 3 work.

## Background behavior

The S25 Home/background run became ready in 3,094 ms and completed the full
song in 44,855 ms. Main-process importance was 125 before and after Home, the
instrumentation process remained present, the exact cache completed, and the
remote stream reached terminal sequence 203.

The bounded screen-off run observed the display turning off and observed
remote events advancing during the 60-second screen-off interval. It did not
finish while the screen was off. After the device was woken, the same run
completed at 78,151 ms with a playable cache and terminal sequence 203. This
matches the existing background limitation: Phase 2 does not give the remote
service an independent foreground-service or wake-lock lifetime.

`PlaybackService.onTaskRemoved()` and the production dependency graph were not
changed, and production still constructs `InProcess`. Therefore current
user-facing recents-removal behavior is unchanged. A dynamic recents-removal
test with the internal `BoundRemote` host was not run; the setting-on/setting-off
matrix remains explicitly scheduled for Phase 7 and Phase 8 before any remote
mode can become production.

No source decoder, window route selector, MP3 calibration, no-Xing handling,
overlap guard, threshold, or fallback boundary changed in Phase 2.

## Raw ignored evidence

Generated reports and exported stems remain under ignored `build/` output.
Selected decision inputs:

| Report | Bytes | SHA-256 |
| --- | ---: | --- |
| Paired S25 in-process full song | 25,653 | `80f0cf5259975057cc8889d00fb4e0a796d08905f36df0c60fa20c1749981bed` |
| Paired S25 bound full song | 25,312 | `616c16f0b6f2f7e71caadf8459e00961abfeb4667343c0e22cc7c475b6d62e69` |
| Final S25 bound full song | 25,096 | `3d3554ccc472e013d3e9765ce5f9015907e640b6ce4a9a55fca949e0bed28286` |
| Final S25 bound short run | 10,236 | `3a287d23ef636a9227b8b10b18b13427eec24d2cd72e4e991ecdd96e67a61c1b` |
| Final S10 arm64 bound short run | 9,978 | `a6a40524716f2942ac8f4df57482a9377d64850b276f58e9b3e3ad260ab22381` |
| Final S10 arm32 bound short run | 9,790 | `c880d3175c96665b54af3a5b95a4ecbb516fa4397557ba027a36ea59b713dcc6` |
| Final x86_64 bound short run | 10,226 | `bccdc530812a90027045eb0d9d843b507de77d2aa41cc8401da47fb5d231a871` |
| S25 Home/background run | 19,335 | `f977523254dd3d961dcf1a5077a5f247a97e052f2b3b65175e21433347126408` |
| S25 bounded screen-off run | 19,476 | `10dcbcb9d3abcfa58340375b7b59fc54b9ca97119c18731139e74bad0ffc9ba0` |

## Exit decision

Phase 2 passes as an internal process-isolation prototype. Bound execution
reproduces in-process output and current background semantics while keeping
cache and playback ownership in the main process. Production remains
`InProcess` on every ABI. No independent background lifetime is claimed,
dynamic recents behavior remains a later-phase gate, and pure x86 inference
remains unsupported pending Phase 3.
