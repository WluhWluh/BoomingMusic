# Phase 5 S25 GPU UI contention tuning

Status: explicit OpenCL control passed; OpenCL low priority and OpenGL rejected

Release decision: do not use either tested tuning profile to mitigate foreground
UI stalls

## Scope

This experiment followed the earlier S25 Perfetto attribution that identified
shared Adreno queue contention between LiteRT OpenCL inference and HWUI Vulkan.
It tested the two controls exposed by the stock LiteRT 2.1.5 Kotlin AAR that
could plausibly change queue behavior:

- an explicit OpenGL FP32 backend;
- explicit OpenCL FP32 with `GpuOptions.Priority.LOW`.

Explicit OpenCL FP32 with inherited priority was the control. All runs used the
exact 9662 FP32 artifact, the 273.7-second Coast Town WAV, in-process single-use
execution, and the same twenty alternating 500 ms foreground swipes. No decode,
window fallback, overlap, join, cache, or playback policy changed.

The test profiles were committed at `d3b0fffe99814280ae46e5407b84b021cc847dd7`.
The selected APK SHA-256 is
`0f300d542ca2ab70d5c51b7db5c991b4c7136235165374f464e5a2a4e56c44b3`.

## Tensor gate

Each profile first ran two invocations against the same frozen synthetic input
and ORT output. All three profiles delegated all 183 nodes and passed the FP32
numerical gate.

| Profile | Setup | First | Reused | SNR vs ORT | Maximum error |
| --- | ---: | ---: | ---: | ---: | ---: |
| Explicit OpenCL | 877 ms | 424 ms | 348 ms | 93.833 dB | 0.0000668764 |
| OpenCL low priority | 872 ms | 1,593 ms | 1,532 ms | 93.833 dB | 0.0000668764 |
| OpenGL | 456 ms | 987 ms | 708 ms | 94.454 dB | 0.0000566840 |

Low priority preserved output but made one isolated invocation approximately
4.4 times slower than explicit OpenCL. OpenGL was approximately twice as slow.

## Foreground result

The marked interval begins after delegate setup and four warm-up swipes. Frame
rate is actual app surface frames divided by the marked interval. The interval
became longer for the rejected profiles because the injected swipe operations
themselves waited behind multi-hundred-millisecond UI stalls.

| Profile | Interval | FPS | Frames over 200 ms | Maximum frame | Long GPU waits | Mean long wait | Maximum wait |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Explicit OpenCL | 15.376 s | 64.908 | 80 | 255.088 ms | 27 | 238.079 ms | 248.677 ms |
| OpenCL low priority | 35.201 s | 18.721 | 60 | 1,412.090 ms | 20 | 1,377.882 ms | 1,409.399 ms |
| OpenGL, thermal 0 | 18.735 s | 23.112 | 59 | 662.650 ms | 20 | 604.277 ms | 655.822 ms |

Every long frame in every profile overlapped an app `GPU completion` fence
wait. Explicit OpenCL reproduced the earlier automatic-profile result within
normal run variance, proving that the diagnostic injection did not create the
problem. Low priority did not preempt an active inference submission on this
Adreno driver. It reduced inference throughput and enlarged one uninterrupted
fence wait from about 240 ms to about 1.4 seconds. OpenGL likewise retained
shared-GPU exclusion and enlarged the wait to about 0.65 seconds.

The selected OpenCL runs stayed at Android thermal status 0. The selected
OpenGL trace also stayed at status 0. A later clean-commit OpenGL repeat reached
status 2 and independently reproduced a 651.317 ms maximum fence wait, so it is
corroborating evidence rather than the primary performance sample.

## Whole-song cost

| Profile | First ready | Full song | Peak PSS | Peak graphics PSS |
| --- | ---: | ---: | ---: | ---: |
| Explicit OpenCL | 4,397 ms | 31,825 ms | 813.0 MiB | 381.8 MiB |
| OpenCL low priority | 5,643 ms | 86,066 ms | 810.7 MiB | 387.2 MiB |
| OpenGL, thermal 0 | 4,681 ms | 48,443 ms | 1,236.2 MiB | 842.4 MiB |

OpenGL is rejected on both responsiveness and active memory. Low priority is
rejected on responsiveness and throughput despite similar peak PSS.

## Stock AAR boundary

LiteRT 2.1.5 contains a native OpenCL option named `kernel_batch_size`; its
current value is logged as `-1`. The C API exposes
`LrtSetGpuAcceleratorRuntimeOptionsKernelBatchSize`, and describes it as the
number of kernels per flush. The stock Kotlin AAR cannot set it:

- `CompiledModel.GpuOptions` exposes priority and backend, but no kernel batch
  field;
- its GPU option key enum ends at key 14;
- the corresponding JNI switch ends at
  `numStepsOfCommandBufferPreparations` and rejects unknown keys;
- `Environment.Option` exposes only compiler/dispatch library directories and
  a system runtime handle.

`numStepsOfCommandBufferPreparations` is not command batching. LiteRT documents
it for preparing WebGPU or Vulkan command buffers; the Kotlin/JNI path has no
supported Vulkan backend and marks WebGPU unsupported. It cannot tune the
OpenCL path measured here.

Testing an actual smaller OpenCL flush therefore requires a custom LiteRT AAR
that extends both Kotlin and JNI, or an upstream API addition. That experiment
must use versioned profiles, repeat the tensor gate, and test several bounded
batch sizes before any product policy changes.

## Decision

- Keep automatic/default FP32 as the only current GPU profile. It still has a
  foreground interaction defect and must not be described as UI-safe.
- Do not promote forced OpenGL or OpenCL low priority.
- Process isolation remains irrelevant to this specific contention because it
  does not isolate the hardware queue.
- The next delegate-level experiment is OpenCL kernel batching through a
  custom diagnostic AAR. If it cannot bound a fence wait below one display
  frame without unacceptable throughput loss, use CPU while MainActivity is
  visible and reserve GPU for background or screen-off execution.

## Evidence

The compact record [`gpu-ui-contention-v1.json`](gpu-ui-contention-v1.json)
contains the selected metrics and ignored-artifact hashes. Perfetto Trace
Processor v57.2 had SHA-256
`100334b6091596fbc97f872556849a5747bf47a7f7190c485ba8cea8d2409c7b`.

The explicit OpenCL trace has no trace-health error. The low-priority trace
lost one unrelated Android log record; all six host markers, scheduling,
FrameTimeline, and fence slices are present. A prior trace of the identical APK
had no error and reproduced approximately 1.38-second waits. The selected
thermal-0 OpenGL trace is error-free. Its report carried the pre-commit source
label, but its APK hash exactly matches the clean `d3b0fffe` identity report;
the later commit-labelled repeat corroborates the same stall.
