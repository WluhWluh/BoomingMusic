# HQ4 No-Allocation Preflight

These five reports verify the current `uvr_mdxnet_inst_hq_4@2` contract against
the bundled catalog without staging or opening the 56.3 MiB model artifact.
They are narrow compatibility/resource-gate evidence, not full-song promotion
reports.

All reports share:

- app commit `4c680f238968a38dda26d83435b90e5e55137c7e`;
- catalog SHA-256
  `a553f227588313578321c07c73ff99654eff7795727d825d16b191aa0f879e1f`;
- AndroidTest APK SHA-256
  `aa725b78ce9b4452fdbaa0216b57b7bcb431173b2fb714bde10816b8efa97966`;
- LiteRT 2.1.5 and contract schema 2;
- `nativeAllocatorCalls: 0`, `modelFileExists: false`, and no mapped LiteRT
  runtime entry.

The arm64 and x86_64 rows terminate as catalog `rejected`; arm32 and pure x86
terminate as `unsupported`. The JSON files retain device fingerprints, exact
app APK hashes, memory snapshots, runtime inventory, and the compatibility
exception. `SHA256SUMS` covers the five reports.

Reproduce one row after building and installing the matching ABI split with:

```powershell
.\tools\run_litert_cpu_validation.ps1 `
  -Serial <serial> `
  -ProcessAbi <abi> `
  -Backend cpu `
  -ModelId uvr_mdxnet_inst_hq_4 `
  -PreflightOnly `
  -SkipBuild `
  -SkipInstall
```

The API 37 x86_64 AVD had two excluded `BIND APPLICATION ANR` attempts while
its system load was above 20. Both stopped in `App.onCreate()` before the test
method, with zero PSS recorded by `ApplicationExitInfo`. A no-snapshot cold
boot and idle wait reduced load below 3; the retained `api37-x86_64.json` then
passed in one invocation. This startup behavior is not classified as an HQ4 or
LiteRT failure.
