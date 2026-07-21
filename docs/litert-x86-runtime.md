# LiteRT 2.1.5 supplemental x86 runtime

Booming SS consumes the official
`com.google.ai.edge.litert:litert:2.1.5` AAR for `armeabi-v7a`, `arm64-v8a`,
and `x86_64`. The official artifact has no 32-bit Android `x86` library, so the
app vendors one reviewed CPU-only binary from the companion
[`bss-litert-android`](https://github.com/WluhWluh/bss-litert-android)
repository.

## Pinned artifact

- Release:
  [`v2.1.5-bss.1`](https://github.com/WluhWluh/bss-litert-android/releases/tag/v2.1.5-bss.1)
- Release asset: `libLiteRt-2.1.5-bss.1-android-x86.so`
- App path: `app/src/main/jniLibs/x86/libLiteRt.so`
- Size: 7,482,132 bytes
- SHA-256:
  `02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af`
- LiteRT source: `2.1.5` commit
  `9d26e89d88ef8785b6a1e54ec41ac8add215a125`
- Build target: `//litert/kotlin:LiteRt`, CPU-only, Android API 23
- Toolchain: Bazel 7.7.0, Android NDK 25.1.8937393, and
  `rules_android_ndk` 0.1.3

The app copies neither the supplemental AAR nor another x86 runtime. A future
official LiteRT release that adds x86 must produce a duplicate-file packaging
failure and trigger an explicit migration review; do not add `pickFirst` for
`libLiteRt.so`.

## Verification

`tools/verify_litert_native.py` checks the committed file's size and SHA-256,
ELF32/i386 identity, exact dynamic dependency allowlist, required LiteRT JNI/C
exports, and absence of GPU dependencies. `tools/verify_litert_apks.py` checks
the ABI split and universal APK inventories after assembly.

The producer Release also contains the source build log, checksums, GitHub
provenance, API 26 x86 smoke result, and UVR validation report. The exact build
manifest, LiteRT license, and resolved third-party licenses included in the
app are under `app/src/main/assets/licenses/litert`.
