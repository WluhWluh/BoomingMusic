# KARA and HQ4 Product Runtime Experiment

This report records the first production-UI admission smoke after KARA and HQ4
were made selectable experimental presets. It is product-path evidence, not a
promotion of either model to recommended status and not an FP16 or NPU result.

## Frozen Identity

- App commit: `d96c54bf34b2f6c02e487e72c515fbc3e17fe44b`
- App variant: `githubDebug`, arm64-v8a split
- APK SHA-256:
  `734ace4571bde499c847c8b3f92136580b7ed200231175da0c527f4c9031f4a5`
- Bundled catalog revision:
  `WluhWluh/bss-tflite@98357ac19db6e53e16f0658428663b5d146c8fc6`
- Bundled catalog SHA-256:
  `12c47be09380f423d9b80abb91e3e42f6d6b265db13bceb9d0b93917e7694414`
- Device: Samsung Galaxy S25 `SM-S9310`, Android 15/API 35, arm64-v8a
- CPU runtime: LiteRT `2.1.5-bss.2`
- GPU runtime: bounded OpenCL FP32, profile
  `gpu-opencl-bounded-fp32-v1`, command queue window `1`
- Source: a 208.248-second stereo MP3, 44.1 kHz, 36 model windows
- Execution: manual full-song action through the production model manager,
  runtime manager, source-separation panel, worker, and independent inference
  process

## Results

| Model and route | Worker runtime | Delegation evidence | Result |
| --- | ---: | --- | --- |
| HQ4, GPU enabled | 70.050 s | `LITERT_CL`, 183/183 nodes, one partition | Completed 36/36 |
| HQ4, GPU disabled | 179.685 s | `TfLiteXNNPackDelegate`, 183/183 nodes, one partition | Completed 36/36 |
| KARA, GPU enabled | 34.873 s | `LITERT_CL`, 183/183 nodes, one partition | Completed 36/36 |

The HQ4 CPU run reached a sampled inference-process PSS of `1,286,039 KiB`
and RSS of `1,422,896 KiB`. This was a live sample rather than a measured peak,
but it confirms that HQ4 remains a high-memory experiment. The S25 completed
the run without process death or fallback.

The runtime-manager switch persisted `source_separation.gpu_enabled=false`.
The following clean HQ4 cache run recorded `tryGpu=false`, created the direct
CPU session, and emitted no OpenCL delegation. Re-enabling the switch produced
the KARA GPU run above. This verifies that HQ4 does not require GPU and that the
user can select its CPU path explicitly.

The model manager also verified the intended interaction contract:

- HQ4 downloaded and installed from the pinned preset catalog;
- KARA and HQ4 were both shown under Experimental;
- selecting either model required the experimental-model confirmation dialog;
- switching models retained the completed model-specific caches;
- the final device state was restored to 9662 with GPU enabled.

## Test Conditions and Limits

The MP3 overlap probe reproduced its previously known mismatch and disabled
window decoding for the app session. All three runs then used the existing
full-song decode fallback. This report does not change or requalify the MP3
window-decoding thresholds.

The device had exhausted Android's cumulative six-hour `mediaProcessing`
foreground-service allowance during earlier validation. The first HQ4 start
was correctly paused by the system timeout. For these bounded test runs,
`media_processing_fgs_timeout_duration` was temporarily changed from the
default `21600000` to `86400000` milliseconds and deleted after completion;
the device was verified back at the default value. This changes only the test
service allowance, not LiteRT, model, or backend behavior.

The following gates remain open:

- HQ4 real-song listening, repeated allocation, cancellation, process-death,
  foreground UI contention, and S10 product-path runs;
- HQ4 memory behavior on devices with less available RAM;
- KARA foreground UI and repeated product-session checks;
- a model- and device-aware performance policy, because the existing S10
  requalification found KARA GPU slower than KARA CPU;
- all FP16 and NPU routes, which remain rejected or unimplemented.

## Decision

Keep KARA and HQ4 selectable only as explicitly confirmed experimental models.
Allow bounded FP32 GPU on eligible arm64 devices. Allow HQ4 direct CPU execution
when the user disables GPU, with normal GPU-to-CPU fallback retained. Do not
promote either model to the recommended Quick Setup slot from this evidence.
