# Phase 7 Download-Only Candidate Catalog

This evidence set audits every published TFLite candidate that has no reviewed
Booming SS contract. It proves immutable acquisition, structural Android
loading, and enforced non-activation. It does not qualify inference, DSP, stem
semantics, resources, playback, or model quality.

## Frozen Identity

- Booming SS app commit:
  `b01b7c0e577b5d04c61ea370fc51b674aee19131`
- Arm64 app APK SHA-256:
  `499b3c1f193a9a6988254185245d99fb1cf9db13e3f374335740758ce69eeaa1`
- AndroidTest APK SHA-256:
  `89ff3ed8712ac4508870b562704b3b81d7b8bca23cd97f88fa1ce90121dab7d3`
- Bundled catalog and `bss-tflite` catalog SHA-256:
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`
- `bss-tflite` catalog revision:
  `28d9a076c8a44980085a059e6224768ae77f9c8a`
- Complete conversion manifest SHA-256:
  `31b4817cd9c66ec928031d089fa3e2d8a94b47d83fec6e679fd027768f01a9bc`
- LiteRT: 2.1.5
- Device: Galaxy S25, Android 15 / API 35, arm64-v8a

The online `v0.1.0-candidates.1` Release manifest was downloaded during the
run. Its SHA-256 is
`d749a07b9c2755bdd6b29f268bfcbc109a7acf6d554b8f838053ff4d1a248cbc`.
It records 30 artifacts totaling 1,461,964,976 bytes and exactly these three
reviewed sidecars:

- `UVR_MDXNET_3_9662_static_float32.tflite.json`
- `UVR_MDXNET_KARA_static_float32.tflite.json`
- `UVR-MDX-NET-Inst_HQ_4_static_float32.tflite.json`

The remaining 27 artifacts have no reviewed contract or sidecar. The matrix
contains 19 `blocked-until-reviewed-contract` entries and eight
`download-only-generic-stem` entries.

## Device Procedure

Each candidate used a separate instrumentation process and a freshly cleared
app-data directory:

1. Resolve the exact immutable Release URL from the bundled catalog.
2. Download through the production preset downloader.
3. Verify file name, byte size, and SHA-256 against the bundled catalog,
   conversion manifest, and online Release manifest.
4. Verify the `TFL3` FlatBuffer identifier.
5. Compile with the app-packaged LiteRT CPU runtime and inspect input/output
   names, static NHWC shapes, float32 dtype, and buffer requirements.
6. Do not create input or output buffers and do not invoke the model.
7. Require user activation to fail with `DownloadOnly`.
8. Delete the inactive model and require zero installed models afterward.

The host pulled each report before clearing app data for the next row. An
instrumentation crash or missing report fails the aggregate matrix.

## Results

All 27 rows passed in 334.6 seconds wall time. They downloaded 1,343,506,784
bytes from the exact GitHub Release URLs; all 27 used the direct URL and none
used the mirror fallback. Aggregate device download time was 280.2 seconds.
LiteRT compile-and-inspect time ranged from 37 to 105 ms.

| Input/output NHWC shape | Models |
| --- | ---: |
| `1x2048x128x4` | 1 |
| `1x2048x256x4` | 8 |
| `1x2048x512x4` | 4 |
| `1x2560x256x4` | 2 |
| `1x3072x256x4` | 11 |
| `1x3072x512x4` | 1 |

Every row reported float32 input/output tensors, adequate buffer requirements,
`buffersAllocated=false`, `DownloadOnly`, successful deletion, and an installed
model count of zero. The script also cleared app data after the matrix; the app
data directory was absent when checked afterward.

## Evidence Files

- [`phase7-candidate-catalog-summary.json`](phase7-candidate-catalog-summary.json)
  is the aggregate result and links all 27 per-model reports.
- [`phase7-candidate-catalog-metadata.json`](phase7-candidate-catalog-metadata.json)
  records the pre-device catalog, Release, sidecar, and tensor reconciliation.
- [`release-manifest-v1.json`](release-manifest-v1.json) is the exact online
  Release manifest retrieved by the run.
- [`phase7-candidate-catalog-setup-identity.json`](phase7-candidate-catalog-setup-identity.json)
  and its input envelope bind the device process and APK identities.
- [`192.168.8.197_42509`](192.168.8.197_42509/) contains the 27 device reports.
- [`SHA256SUMS`](SHA256SUMS) authenticates every evidence file in this
  directory except this explanatory README.

## Decision

- Keep all 27 candidates download-only.
- A structural compile does not change `androidCpu`, `fullSong`, release
  maturity, activation policy, or runtime qualifications.
- The eight target-stem candidates remain blocked on neutral stem labels and
  generic cache/playback UI.
- The other 19 candidates require a reviewed model contract and independent
  numerical, full-song, resource, playback, and listening evidence before any
  activation can be reconsidered.
