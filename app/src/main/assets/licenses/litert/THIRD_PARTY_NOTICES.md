# LiteRT provenance and third-party notices

The APK uses the classes-only LiteRT API AAR from the fixed
`downloadable-runtime-v2.1.5-bss.2-exp.2` GitHub Release. It contains no
LiteRT native library. CPU and accelerator components are delivered separately
and must be verified before the source-separation process loads them.

The API artifact is built from LiteRT `2.1.5` commit
`9d26e89d88ef8785b6a1e54ec41ac8add215a125`. The exact API source lock,
release contract, patch series, and output hashes are recorded in the
`bss-litert-android` repository and in `build-manifest.json` beside this file.

`LICENSE-LiteRT.txt` contains the LiteRT Apache License 2.0 text.
`THIRD_PARTY_LICENSES.txt` contains the third-party notices collected for the
same pinned LiteRT build. Model weights are not part of the APK or these
provenance assets.
