# LiteRT provenance and third-party notices

The APK uses the classes-only LiteRT API AAR from the fixed
`downloadable-runtime-v2.2.0-bss.2-exp.1` GitHub Release. It contains no
LiteRT native library. CPU and accelerator components are delivered separately
and must be verified before the source-separation process loads them.

The API artifact is built from LiteRT `2.2.0` commit
`145c7523ff08d5e57ab5c582141775eea47da9c7`. The exact API source lock,
release contract, patch series, and output hashes are recorded in the
`bss-litert-android` repository and in `build-manifest.json` beside this file.

`LICENSE-LiteRT.txt` contains the LiteRT Apache License 2.0 text.
`THIRD_PARTY_LICENSES.txt` contains the third-party notices collected for the
same pinned LiteRT build. Model weights are not part of the APK or these
provenance assets.
