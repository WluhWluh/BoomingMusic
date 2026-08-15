#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "litert_abi_2_2.h"

#define POCKETFFT_CACHE_SIZE 8
#define POCKETFFT_NO_MULTITHREADING
#include "pocketfft_hdronly.h"

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr int kChannels = 2;
constexpr int kComplexChannels = 4;
constexpr int kWorkerCount = 4;

int reflectIndex(int index, int size) {
    while (index < 0 || index >= size) {
        index = index < 0 ? -index : 2 * size - index - 2;
    }
    return index;
}

class MdxPlan {
   public:
    MdxPlan(int fftSize, int hop, int frequencies, int frames, int samples)
        : nFft(fftSize),
          hopLength(hop),
          dimF(frequencies),
          dimT(frames),
          chunkSize(samples),
          trim(fftSize / 2),
          window(static_cast<size_t>(fftSize)),
          windowSum(static_cast<size_t>(samples + fftSize), 0.f),
          inverseOutput(kChannels, std::vector<float>(static_cast<size_t>(samples + fftSize))),
          fftOutput(kWorkerCount, std::vector<std::complex<float>>(static_cast<size_t>(fftSize))),
          realInput(kWorkerCount, std::vector<float>(static_cast<size_t>(fftSize))),
          realOutput(kWorkerCount, std::vector<float>(static_cast<size_t>(fftSize))),
          shape{static_cast<size_t>(fftSize)},
          stride{sizeof(std::complex<float>)},
          realStride{sizeof(float)} {
        if (nFft <= 0 || (nFft & 1) != 0 || hopLength <= 0 || dimF <= 0 || dimF > nFft / 2 + 1 ||
            dimT <= 0 || chunkSize != hopLength * (dimT - 1)) {
            throw std::invalid_argument("invalid MDX contract");
        }
        for (int index = 0; index < nFft; ++index) {
            window[static_cast<size_t>(index)] =
                .5f -
                .5f * std::cos(2.f * kPi * static_cast<float>(index) / static_cast<float>(nFft));
        }
        for (int frame = 0; frame < dimT; ++frame) {
            const int start = frame * hopLength;
            for (int sample = 0; sample < nFft; ++sample) {
                const float value = window[static_cast<size_t>(sample)];
                windowSum[static_cast<size_t>(start + sample)] += value * value;
            }
        }
    }

    size_t channelSamples() const { return static_cast<size_t>(chunkSize); }

    size_t tensorElements() const { return static_cast<size_t>(kComplexChannels) * dimF * dimT; }

    void preprocess(const float* left, const float* right, float* tensor) {
        const float* channels[kChannels]{left, right};
        runWorkers(kWorkerCount, [&](int lane) {
            auto& output = fftOutput[static_cast<size_t>(lane)];
            auto& real = realInput[static_cast<size_t>(lane)];
            const int firstFrame = lane * dimT / kWorkerCount;
            const int lastFrame = (lane + 1) * dimT / kWorkerCount;
            for (int channel = 0; channel < kChannels; ++channel) {
                for (int frame = firstFrame; frame < lastFrame; ++frame) {
                    const int frameStart = frame * hopLength - trim;
                    for (int sample = 0; sample < nFft; ++sample) {
                        const int source = reflectIndex(frameStart + sample, chunkSize);
                        const float value =
                            channels[channel][source] * window[static_cast<size_t>(sample)];
                        real[static_cast<size_t>(sample)] = value;
                    }
                    pocketfft::r2c(shape, realStride, stride, 0, pocketfft::FORWARD, real.data(),
                                   output.data(), 1.f, 1);
                    const int realChannel = channel * 2;
                    for (int frequency = 0; frequency < dimF; ++frequency) {
                        const size_t base =
                            (static_cast<size_t>(frequency) * dimT + frame) * kComplexChannels;
                        tensor[base + realChannel] = output[static_cast<size_t>(frequency)].real();
                        tensor[base + realChannel + 1] =
                            output[static_cast<size_t>(frequency)].imag();
                    }
                }
            }
        });
    }

    void postprocess(const float* tensor, float* left, float* right) {
        float* channels[kChannels]{left, right};
        runWorkers(kChannels, [&](int channel) {
            auto& input = fftOutput[static_cast<size_t>(channel)];
            auto& real = realOutput[static_cast<size_t>(channel)];
            auto& overlap = inverseOutput[static_cast<size_t>(channel)];
            std::fill(overlap.begin(), overlap.end(), 0.f);
            const int realChannel = channel * 2;
            for (int frame = 0; frame < dimT; ++frame) {
                std::fill(input.begin(), input.end(), std::complex<float>{0.f, 0.f});
                for (int frequency = 0; frequency < dimF; ++frequency) {
                    const size_t base =
                        (static_cast<size_t>(frequency) * dimT + frame) * kComplexChannels;
                    input[static_cast<size_t>(frequency)] = {tensor[base + realChannel],
                                                             tensor[base + realChannel + 1]};
                }
                pocketfft::c2r(shape, stride, realStride, 0, pocketfft::BACKWARD, input.data(),
                               real.data(), 1.f / static_cast<float>(nFft), 1);
                const int start = frame * hopLength;
                for (int sample = 0; sample < nFft; ++sample) {
                    overlap[static_cast<size_t>(start + sample)] +=
                        real[static_cast<size_t>(sample)] * window[static_cast<size_t>(sample)];
                }
            }
            for (int sample = 0; sample < chunkSize; ++sample) {
                const int source = sample + trim;
                channels[channel][sample] =
                    overlap[static_cast<size_t>(source)] / windowSum[static_cast<size_t>(source)];
            }
        });
    }

   private:
    template <class Action>
    void runWorkers(int count, Action action) {
        if (count == 1) {
            action(0);
            return;
        }
        std::vector<std::thread> threads;
        threads.reserve(static_cast<size_t>(count - 1));
        for (int lane = 1; lane < count; ++lane) threads.emplace_back(action, lane);
        action(0);
        for (auto& thread : threads) thread.join();
    }

    int nFft;
    int hopLength;
    int dimF;
    int dimT;
    int chunkSize;
    int trim;
    std::vector<float> window;
    std::vector<float> windowSum;
    std::vector<std::vector<float>> inverseOutput;
    std::vector<std::vector<std::complex<float>>> fftOutput;
    std::vector<std::vector<float>> realInput;
    std::vector<std::vector<float>> realOutput;
    pocketfft::shape_t shape;
    pocketfft::stride_t stride;
    pocketfft::stride_t realStride;
};

enum class LiteRtStage : int {
    EnvironmentCreate = 0,
    AcceleratorDiscovery = 1,
    ModelCompile = 2,
    TensorMetadata = 3,
    BufferAllocation = 4,
    InputWrite = 5,
    Invocation = 6,
    OutputRead = 7,
    OutputValidation = 8,
    Cleanup = 9,
};

class LiteRtPipelineError : public std::runtime_error {
   public:
    LiteRtPipelineError(LiteRtStage errorStage, const std::string& message)
        : std::runtime_error(message), stage(errorStage) {}

    LiteRtStage stage;
};

thread_local std::string gLiteRtLastError;
thread_local int gLiteRtLastErrorStage = static_cast<int>(LiteRtStage::EnvironmentCreate);

std::string canonicalPath(const char* path) {
    std::array<char, 4096> resolved{};
    return realpath(path, resolved.data()) == nullptr ? std::string(path)
                                                      : std::string(resolved.data());
}

class LiteRt220Api {
   public:
    using Handle = booming::litert220::Handle;
    using Status = booming::litert220::Status;
    using RankedTensorType = booming::litert220::RankedTensorType;

    explicit LiteRt220Api(const char* coreLibraryPath) {
        if (coreLibraryPath == nullptr || coreLibraryPath[0] == '\0') {
            throw LiteRtPipelineError(LiteRtStage::EnvironmentCreate,
                                      "The LiteRT core library path is empty");
        }
        expectedLibraryPath = canonicalPath(coreLibraryPath);
        library = dlopen(coreLibraryPath, RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) {
            throw LiteRtPipelineError(
                LiteRtStage::EnvironmentCreate,
                std::string("Unable to open the verified LiteRT 2.2 core: ") + dlerror());
        }

        try {
            createEnvironment = load<CreateEnvironment>("LiteRtCreateEnvironment");
            destroyEnvironment = load<DestroyHandle>("LiteRtDestroyEnvironment");
            createModelFromFile = load<CreateModelFromFile>("LiteRtCreateModelFromFile");
            destroyModel = load<DestroyHandle>("LiteRtDestroyModel");
            getNumModelSignatures = load<GetCount>("LiteRtGetNumModelSignatures");
            getModelSignature = load<GetIndexedHandle>("LiteRtGetModelSignature");
            getNumSignatureInputs = load<GetCount>("LiteRtGetNumSignatureInputs");
            getNumSignatureOutputs = load<GetCount>("LiteRtGetNumSignatureOutputs");
            getSignatureInputName = load<GetIndexedName>("LiteRtGetSignatureInputName");
            getSignatureOutputName = load<GetIndexedName>("LiteRtGetSignatureOutputName");
            getSignatureInputTensor =
                load<GetIndexedHandle>("LiteRtGetSignatureInputTensorByIndex");
            getSignatureOutputTensor =
                load<GetIndexedHandle>("LiteRtGetSignatureOutputTensorByIndex");
            getRankedTensorType = load<GetRankedTensorType>("LiteRtGetRankedTensorType");
            createOptions = load<CreateHandle>("LiteRtCreateOptions");
            destroyOptions = load<DestroyHandle>("LiteRtDestroyOptions");
            setHardwareAccelerators =
                load<SetHardwareAccelerators>("LiteRtSetOptionsHardwareAccelerators");
            addOpaqueOptions = load<AddOpaqueOptions>("LiteRtAddOpaqueOptions");
            createOpaqueOptions = load<CreateOpaqueOptions>("LiteRtCreateOpaqueOptions");
            destroyOpaqueOptions = load<DestroyHandle>("LiteRtDestroyOpaqueOptions");
            createCompiledModel = load<CreateCompiledModel>("LiteRtCreateCompiledModel");
            destroyCompiledModel = load<DestroyHandle>("LiteRtDestroyCompiledModel");
            getInputRequirements =
                load<GetBufferRequirements>("LiteRtGetCompiledModelInputBufferRequirements");
            getOutputRequirements =
                load<GetBufferRequirements>("LiteRtGetCompiledModelOutputBufferRequirements");
            createManagedBuffer =
                load<CreateManagedBuffer>("LiteRtCreateManagedTensorBufferFromRequirements");
            destroyBuffer = load<DestroyHandle>("LiteRtDestroyTensorBuffer");
            getPackedSize = load<GetPackedSize>("LiteRtGetTensorBufferPackedSize");
            lockBuffer = load<LockBuffer>("LiteRtLockTensorBuffer");
            unlockBuffer = load<UnlockBuffer>("LiteRtUnlockTensorBuffer");
            runCompiledModel = load<RunCompiledModel>("LiteRtRunCompiledModel");
            getStatusString = load<GetStatusString>("LiteRtGetStatusString");

            Dl_info symbolInfo{};
            if (dladdr(reinterpret_cast<void*>(createEnvironment), &symbolInfo) == 0 ||
                symbolInfo.dli_fname == nullptr ||
                canonicalPath(symbolInfo.dli_fname) != expectedLibraryPath) {
                throw LiteRtPipelineError(LiteRtStage::EnvironmentCreate,
                                          "LiteRT symbols did not resolve from the "
                                          "verified absolute core path");
            }
        } catch (...) {
            dlclose(library);
            library = nullptr;
            throw;
        }
    }

    ~LiteRt220Api() {
        if (library != nullptr) dlclose(library);
    }

    LiteRt220Api(const LiteRt220Api&) = delete;
    LiteRt220Api& operator=(const LiteRt220Api&) = delete;

    void check(Status status, LiteRtStage stage, const char* operation) const {
        if (status == booming::litert220::kStatusOk) return;
        const char* detail = getStatusString == nullptr ? nullptr : getStatusString(status);
        throw LiteRtPipelineError(stage, std::string(operation) + " failed: " +
                                             (detail == nullptr ? std::to_string(status) : detail));
    }

    using CreateEnvironment = Status (*)(int, const void*, Handle*);
    using DestroyHandle = void (*)(Handle);
    using CreateModelFromFile = Status (*)(Handle, const char*, Handle*);
    using GetCount = Status (*)(Handle, size_t*);
    using GetIndexedHandle = Status (*)(Handle, size_t, Handle*);
    using GetIndexedName = Status (*)(Handle, size_t, const char**);
    using GetRankedTensorType = Status (*)(Handle, RankedTensorType*);
    using CreateHandle = Status (*)(Handle*);
    using SetHardwareAccelerators = Status (*)(Handle, int);
    using AddOpaqueOptions = Status (*)(Handle, Handle);
    using CreateOpaqueOptions = Status (*)(const char*, void*, booming::litert220::PayloadDeleter,
                                           Handle*);
    using CreateCompiledModel = Status (*)(Handle, Handle, Handle, Handle*);
    using GetBufferRequirements = Status (*)(Handle, size_t, size_t, Handle*);
    using CreateManagedBuffer = Status (*)(Handle, const RankedTensorType*, Handle, Handle*);
    using GetPackedSize = Status (*)(Handle, size_t*);
    using LockBuffer = Status (*)(Handle, void**, int);
    using UnlockBuffer = Status (*)(Handle);
    using RunCompiledModel = Status (*)(Handle, size_t, size_t, Handle*, size_t, Handle*);
    using GetStatusString = const char* (*)(Status);

    CreateEnvironment createEnvironment = nullptr;
    DestroyHandle destroyEnvironment = nullptr;
    CreateModelFromFile createModelFromFile = nullptr;
    DestroyHandle destroyModel = nullptr;
    GetCount getNumModelSignatures = nullptr;
    GetIndexedHandle getModelSignature = nullptr;
    GetCount getNumSignatureInputs = nullptr;
    GetCount getNumSignatureOutputs = nullptr;
    GetIndexedName getSignatureInputName = nullptr;
    GetIndexedName getSignatureOutputName = nullptr;
    GetIndexedHandle getSignatureInputTensor = nullptr;
    GetIndexedHandle getSignatureOutputTensor = nullptr;
    GetRankedTensorType getRankedTensorType = nullptr;
    CreateHandle createOptions = nullptr;
    DestroyHandle destroyOptions = nullptr;
    SetHardwareAccelerators setHardwareAccelerators = nullptr;
    AddOpaqueOptions addOpaqueOptions = nullptr;
    CreateOpaqueOptions createOpaqueOptions = nullptr;
    DestroyHandle destroyOpaqueOptions = nullptr;
    CreateCompiledModel createCompiledModel = nullptr;
    DestroyHandle destroyCompiledModel = nullptr;
    GetBufferRequirements getInputRequirements = nullptr;
    GetBufferRequirements getOutputRequirements = nullptr;
    CreateManagedBuffer createManagedBuffer = nullptr;
    DestroyHandle destroyBuffer = nullptr;
    GetPackedSize getPackedSize = nullptr;
    LockBuffer lockBuffer = nullptr;
    UnlockBuffer unlockBuffer = nullptr;
    RunCompiledModel runCompiledModel = nullptr;
    GetStatusString getStatusString = nullptr;

   private:
    template <typename Function>
    Function load(const char* name) {
        dlerror();
        void* symbol = dlsym(library, name);
        const char* error = dlerror();
        if (error != nullptr || symbol == nullptr) {
            throw LiteRtPipelineError(LiteRtStage::EnvironmentCreate,
                                      std::string("Missing LiteRT 2.2 symbol ") + name);
        }
        return reinterpret_cast<Function>(symbol);
    }

    void* library = nullptr;
    std::string expectedLibraryPath;
};

class ScopedLiteRtBufferLock {
   public:
    ScopedLiteRtBufferLock(LiteRt220Api& inputApi, LiteRt220Api::Handle inputBuffer, int mode,
                           LiteRtStage stage, const char* operation)
        : api(inputApi), buffer(inputBuffer), errorStage(stage) {
        api.check(api.lockBuffer(buffer, &data, mode), stage, operation);
        locked = true;
    }

    ~ScopedLiteRtBufferLock() {
        if (locked) api.unlockBuffer(buffer);
    }

    void* get() const { return data; }

    void unlock(const char* operation) {
        if (!locked) return;
        locked = false;
        api.check(api.unlockBuffer(buffer), errorStage, operation);
    }

   private:
    LiteRt220Api& api;
    LiteRt220Api::Handle buffer;
    LiteRtStage errorStage;
    void* data = nullptr;
    bool locked = false;
};

class MdxLiteRtManagedPipeline {
   public:
    MdxLiteRtManagedPipeline(const char* coreLibraryPath, const char* modelPath,
                             const char* expectedInputName, const char* expectedOutputName,
                             int fftSize, int hop, int frequencies, int frames, int samples,
                             int cpuThreads, bool useBoundedGpu, int requestedSlotCount)
        : api(coreLibraryPath),
          dsp(fftSize, hop, frequencies, frames, samples),
          dimF(frequencies),
          dimT(frames),
          boundedGpu(useBoundedGpu),
          slotCount(requestedSlotCount),
          slotStates(static_cast<size_t>(requestedSlotCount), SlotState::Empty) {
        if (cpuThreads < 1 || cpuThreads > 16) {
            throw LiteRtPipelineError(LiteRtStage::ModelCompile,
                                      "CPU thread count is outside 1..16");
        }
        if (slotCount < 1 || slotCount > 2) {
            throw LiteRtPipelineError(LiteRtStage::BufferAllocation,
                                      "Managed buffer slot count is outside 1..2");
        }
        try {
            initialize(modelPath, expectedInputName, expectedOutputName, cpuThreads);
        } catch (...) {
            cleanup();
            throw;
        }
    }

    ~MdxLiteRtManagedPipeline() { cleanup(); }

    size_t channelSamples() const { return dsp.channelSamples(); }
    size_t tensorElements() const { return dsp.tensorElements(); }

    void writeTensorNchw(int slot, const float* input) {
        auto stateLock = lockSlotForPrepare(slot);
        ScopedLiteRtBufferLock bufferLock(api, inputBuffers[static_cast<size_t>(slot)],
                                          booming::litert220::kLockModeWrite,
                                          LiteRtStage::InputWrite, "LiteRtLockTensorBuffer(input)");
        auto* destination = static_cast<float*>(bufferLock.get());
        for (int channel = 0; channel < kComplexChannels; ++channel) {
            for (int frequency = 0; frequency < dimF; ++frequency) {
                for (int frame = 0; frame < dimT; ++frame) {
                    const size_t nchw =
                        (static_cast<size_t>(channel) * dimF + frequency) * dimT + frame;
                    const size_t nhwc =
                        (static_cast<size_t>(frequency) * dimT + frame) * kComplexChannels +
                        channel;
                    destination[nhwc] = input[nchw];
                }
            }
        }
        requireFinite(destination, tensorElements(), LiteRtStage::InputWrite, "MDX input");
        bufferLock.unlock("LiteRtUnlockTensorBuffer(input)");
        slotStates[static_cast<size_t>(slot)] = SlotState::Prepared;
    }

    void preprocessWaveform(int slot, const float* left, const float* right) {
        auto stateLock = lockSlotForPrepare(slot);
        requireFinite(left, channelSamples(), LiteRtStage::InputWrite, "left waveform");
        requireFinite(right, channelSamples(), LiteRtStage::InputWrite, "right waveform");
        ScopedLiteRtBufferLock bufferLock(api, inputBuffers[static_cast<size_t>(slot)],
                                          booming::litert220::kLockModeWrite,
                                          LiteRtStage::InputWrite, "LiteRtLockTensorBuffer(input)");
        auto* destination = static_cast<float*>(bufferLock.get());
        dsp.preprocess(left, right, destination);
        requireFinite(destination, tensorElements(), LiteRtStage::InputWrite, "native STFT output");
        bufferLock.unlock("LiteRtUnlockTensorBuffer(input)");
        slotStates[static_cast<size_t>(slot)] = SlotState::Prepared;
    }

    void run(int slot) {
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            checkSlot(slot);
            if (slotStates[static_cast<size_t>(slot)] != SlotState::Prepared) {
                throw LiteRtPipelineError(LiteRtStage::Invocation,
                                          "Managed buffer slot is not prepared");
            }
            if (modelInvocationInFlight) {
                throw LiteRtPipelineError(LiteRtStage::Invocation,
                                          "Concurrent LiteRT model invocation is not allowed");
            }
            slotStates[static_cast<size_t>(slot)] = SlotState::InFlight;
            modelInvocationInFlight = true;
        }

        auto input = inputBuffers[static_cast<size_t>(slot)];
        auto output = outputBuffers[static_cast<size_t>(slot)];
        try {
            api.check(api.runCompiledModel(compiledModel, 0, 1, &input, 1, &output),
                      LiteRtStage::Invocation, "LiteRtRunCompiledModel");
            std::lock_guard<std::mutex> lock(stateMutex);
            modelInvocationInFlight = false;
            slotStates[static_cast<size_t>(slot)] = SlotState::Ready;
        } catch (...) {
            std::lock_guard<std::mutex> lock(stateMutex);
            modelInvocationInFlight = false;
            slotStates[static_cast<size_t>(slot)] = SlotState::Empty;
            throw;
        }
    }

    void readTensorNchw(int slot, float* output) {
        auto stateLock = lockSlotForRead(slot);
        ScopedLiteRtBufferLock bufferLock(
            api, outputBuffers[static_cast<size_t>(slot)], booming::litert220::kLockModeRead,
            LiteRtStage::OutputRead, "LiteRtLockTensorBuffer(output)");
        const auto* source = static_cast<const float*>(bufferLock.get());
        requireFinite(source, tensorElements(), LiteRtStage::OutputValidation, "MDX output");
        for (int channel = 0; channel < kComplexChannels; ++channel) {
            for (int frequency = 0; frequency < dimF; ++frequency) {
                for (int frame = 0; frame < dimT; ++frame) {
                    const size_t nchw =
                        (static_cast<size_t>(channel) * dimF + frequency) * dimT + frame;
                    const size_t nhwc =
                        (static_cast<size_t>(frequency) * dimT + frame) * kComplexChannels +
                        channel;
                    output[nchw] = source[nhwc];
                }
            }
        }
        bufferLock.unlock("LiteRtUnlockTensorBuffer(output)");
        slotStates[static_cast<size_t>(slot)] = SlotState::Empty;
    }

    void postprocessWaveform(int slot, float* left, float* right) {
        auto stateLock = lockSlotForRead(slot);
        ScopedLiteRtBufferLock bufferLock(
            api, outputBuffers[static_cast<size_t>(slot)], booming::litert220::kLockModeRead,
            LiteRtStage::OutputRead, "LiteRtLockTensorBuffer(output)");
        const auto* source = static_cast<const float*>(bufferLock.get());
        requireFinite(source, tensorElements(), LiteRtStage::OutputValidation, "MDX output");
        dsp.postprocess(source, left, right);
        requireFinite(left, channelSamples(), LiteRtStage::OutputValidation, "left iSTFT output");
        requireFinite(right, channelSamples(), LiteRtStage::OutputValidation, "right iSTFT output");
        bufferLock.unlock("LiteRtUnlockTensorBuffer(output)");
        slotStates[static_cast<size_t>(slot)] = SlotState::Empty;
    }

    void discard(int slot) {
        std::lock_guard<std::mutex> lock(stateMutex);
        checkSlot(slot);
        if (slotStates[static_cast<size_t>(slot)] == SlotState::InFlight) {
            throw LiteRtPipelineError(
                LiteRtStage::Cleanup,
                "Cannot discard a managed buffer slot while inference is in flight");
        }
        slotStates[static_cast<size_t>(slot)] = SlotState::Empty;
    }

    bool canDestroy() {
        std::lock_guard<std::mutex> lock(stateMutex);
        return !modelInvocationInFlight;
    }

    bool invocationInFlight() {
        std::lock_guard<std::mutex> lock(stateMutex);
        return modelInvocationInFlight;
    }

   private:
    enum class SlotState { Empty, Prepared, InFlight, Ready };

    std::unique_lock<std::mutex> lockSlotForPrepare(int slot) {
        std::unique_lock<std::mutex> lock(stateMutex);
        checkSlot(slot);
        if (slotStates[static_cast<size_t>(slot)] != SlotState::Empty) {
            throw LiteRtPipelineError(LiteRtStage::InputWrite, "Managed buffer slot is not empty");
        }
        return lock;
    }

    std::unique_lock<std::mutex> lockSlotForRead(int slot) {
        std::unique_lock<std::mutex> lock(stateMutex);
        checkSlot(slot);
        if (slotStates[static_cast<size_t>(slot)] != SlotState::Ready) {
            throw LiteRtPipelineError(LiteRtStage::OutputRead,
                                      "Managed buffer slot output is not ready");
        }
        return lock;
    }

    void checkSlot(int slot) const {
        if (slot < 0 || slot >= slotCount) {
            throw LiteRtPipelineError(LiteRtStage::BufferAllocation,
                                      "Managed buffer slot is outside the configured range");
        }
    }

    static void requireFinite(const float* values, size_t count, LiteRtStage stage,
                              const char* label) {
        for (size_t index = 0; index < count; ++index) {
            if (!std::isfinite(values[index])) {
                throw LiteRtPipelineError(stage, std::string(label) +
                                                     " contains a non-finite value at index " +
                                                     std::to_string(index));
            }
        }
    }

    void initialize(const char* modelPath, const char* expectedInputName,
                    const char* expectedOutputName, int cpuThreads) {
        api.check(api.createEnvironment(0, nullptr, &environment), LiteRtStage::EnvironmentCreate,
                  "LiteRtCreateEnvironment");
        api.check(api.createModelFromFile(environment, modelPath, &model),
                  LiteRtStage::ModelCompile, "LiteRtCreateModelFromFile");
        validateModelContract(expectedInputName, expectedOutputName);

        api.check(api.createOptions(&options), LiteRtStage::ModelCompile, "LiteRtCreateOptions");
        api.check(
            api.setHardwareAccelerators(options, boundedGpu ? booming::litert220::kAcceleratorGpu
                                                            : booming::litert220::kAcceleratorCpu),
            LiteRtStage::AcceleratorDiscovery, "LiteRtSetOptionsHardwareAccelerators");
        const std::string identifier = boundedGpu ? "gpu_options" : "xnnpack";
        const std::string toml = boundedGpu ? "backend = 1\nprecision = 2\nkernel_batch_size = 1\n"
                                              "num_steps_of_command_buffer_preparations = 1\n"
                                            : "num_threads = " + std::to_string(cpuThreads) + "\n";
        addOpaqueToml(identifier.c_str(), toml);

        api.check(api.createCompiledModel(environment, model, options, &compiledModel),
                  LiteRtStage::ModelCompile, "LiteRtCreateCompiledModel");
        api.destroyOptions(options);
        options = nullptr;

        inputBuffers.reserve(static_cast<size_t>(slotCount));
        outputBuffers.reserve(static_cast<size_t>(slotCount));
        for (int slot = 0; slot < slotCount; ++slot) {
            inputBuffers.push_back(createBuffer(true));
            outputBuffers.push_back(createBuffer(false));
        }
    }

    void validateModelContract(const char* expectedInputName, const char* expectedOutputName) {
        size_t signatureCount = 0;
        api.check(api.getNumModelSignatures(model, &signatureCount), LiteRtStage::TensorMetadata,
                  "LiteRtGetNumModelSignatures");
        if (signatureCount == 0) {
            throw LiteRtPipelineError(LiteRtStage::TensorMetadata,
                                      "MDX model exposes no LiteRT signature");
        }
        api.check(api.getModelSignature(model, 0, &signature), LiteRtStage::TensorMetadata,
                  "LiteRtGetModelSignature");
        size_t inputCount = 0;
        size_t outputCount = 0;
        api.check(api.getNumSignatureInputs(signature, &inputCount), LiteRtStage::TensorMetadata,
                  "LiteRtGetNumSignatureInputs");
        api.check(api.getNumSignatureOutputs(signature, &outputCount), LiteRtStage::TensorMetadata,
                  "LiteRtGetNumSignatureOutputs");
        if (inputCount != 1 || outputCount != 1) {
            throw LiteRtPipelineError(
                LiteRtStage::TensorMetadata,
                "MDX model must expose exactly one signature input and output");
        }

        const char* inputName = nullptr;
        const char* outputName = nullptr;
        api.check(api.getSignatureInputName(signature, 0, &inputName), LiteRtStage::TensorMetadata,
                  "LiteRtGetSignatureInputName");
        api.check(api.getSignatureOutputName(signature, 0, &outputName),
                  LiteRtStage::TensorMetadata, "LiteRtGetSignatureOutputName");
        if (inputName == nullptr || expectedInputName == nullptr ||
            std::strcmp(inputName, expectedInputName) != 0 || outputName == nullptr ||
            expectedOutputName == nullptr || std::strcmp(outputName, expectedOutputName) != 0) {
            throw LiteRtPipelineError(
                LiteRtStage::TensorMetadata,
                "MDX signature tensor names do not match the product contract");
        }

        api.check(api.getSignatureInputTensor(signature, 0, &inputTensor),
                  LiteRtStage::TensorMetadata, "LiteRtGetSignatureInputTensorByIndex");
        api.check(api.getSignatureOutputTensor(signature, 0, &outputTensor),
                  LiteRtStage::TensorMetadata, "LiteRtGetSignatureOutputTensorByIndex");
        api.check(api.getRankedTensorType(inputTensor, &inputTensorType),
                  LiteRtStage::TensorMetadata, "LiteRtGetRankedTensorType(input)");
        api.check(api.getRankedTensorType(outputTensor, &outputTensorType),
                  LiteRtStage::TensorMetadata, "LiteRtGetRankedTensorType(output)");
        validateTensorType(inputTensorType, "input");
        validateTensorType(outputTensorType, "output");
    }

    void validateTensorType(const LiteRt220Api::RankedTensorType& type, const char* label) const {
        const std::array<int32_t, 4> expected{1, dimF, dimT, kComplexChannels};
        if (type.elementType != booming::litert220::kElementTypeFloat32 ||
            type.layout.rank != expected.size() || type.layout.hasStrides != 0) {
            throw LiteRtPipelineError(
                LiteRtStage::TensorMetadata,
                std::string("MDX ") + label + " tensor is not contiguous ranked FP32 NHWC");
        }
        for (size_t index = 0; index < expected.size(); ++index) {
            if (type.layout.dimensions[index] != expected[index]) {
                throw LiteRtPipelineError(LiteRtStage::TensorMetadata,
                                          std::string("MDX ") + label +
                                              " tensor shape does not match the product contract");
            }
        }
    }

    void addOpaqueToml(const char* identifier, const std::string& toml) {
        void* payload = std::malloc(toml.size() + 1);
        if (payload == nullptr) throw std::bad_alloc();
        std::memcpy(payload, toml.c_str(), toml.size() + 1);
        LiteRt220Api::Handle opaqueOptions = nullptr;
        const auto createStatus = api.createOpaqueOptions(
            identifier, payload, +[](void* value) { std::free(value); }, &opaqueOptions);
        if (createStatus != booming::litert220::kStatusOk) std::free(payload);
        api.check(createStatus, LiteRtStage::ModelCompile, "LiteRtCreateOpaqueOptions");
        const auto addStatus = api.addOpaqueOptions(options, opaqueOptions);
        if (addStatus != booming::litert220::kStatusOk) api.destroyOpaqueOptions(opaqueOptions);
        api.check(addStatus, LiteRtStage::ModelCompile, "LiteRtAddOpaqueOptions");
    }

    LiteRt220Api::Handle createBuffer(bool input) {
        LiteRt220Api::Handle requirements = nullptr;
        auto getRequirements = input ? api.getInputRequirements : api.getOutputRequirements;
        api.check(getRequirements(compiledModel, 0, 0, &requirements),
                  LiteRtStage::BufferAllocation,
                  input ? "LiteRtGetCompiledModelInputBufferRequirements"
                        : "LiteRtGetCompiledModelOutputBufferRequirements");
        LiteRt220Api::Handle buffer = nullptr;
        const auto& tensorType = input ? inputTensorType : outputTensorType;
        api.check(api.createManagedBuffer(environment, &tensorType, requirements, &buffer),
                  LiteRtStage::BufferAllocation, "LiteRtCreateManagedTensorBufferFromRequirements");
        size_t packedSize = 0;
        api.check(api.getPackedSize(buffer, &packedSize), LiteRtStage::BufferAllocation,
                  "LiteRtGetTensorBufferPackedSize");
        const size_t expectedSize = tensorElements() * sizeof(float);
        if (packedSize != expectedSize) {
            api.destroyBuffer(buffer);
            throw LiteRtPipelineError(
                LiteRtStage::BufferAllocation,
                std::string(input ? "Input" : "Output") +
                    " managed buffer packed size does not match the product tensor");
        }
        return buffer;
    }

    void cleanup() noexcept {
        for (auto buffer : inputBuffers)
            if (buffer != nullptr) api.destroyBuffer(buffer);
        for (auto buffer : outputBuffers)
            if (buffer != nullptr) api.destroyBuffer(buffer);
        if (compiledModel != nullptr) api.destroyCompiledModel(compiledModel);
        if (options != nullptr) api.destroyOptions(options);
        if (model != nullptr) api.destroyModel(model);
        if (environment != nullptr) api.destroyEnvironment(environment);
        inputBuffers.clear();
        outputBuffers.clear();
        compiledModel = nullptr;
        options = nullptr;
        model = nullptr;
        environment = nullptr;
    }

    LiteRt220Api api;
    MdxPlan dsp;
    int dimF;
    int dimT;
    bool boundedGpu;
    int slotCount;
    std::mutex stateMutex;
    std::vector<SlotState> slotStates;
    bool modelInvocationInFlight = false;
    LiteRt220Api::Handle environment = nullptr;
    LiteRt220Api::Handle model = nullptr;
    LiteRt220Api::Handle signature = nullptr;
    LiteRt220Api::Handle inputTensor = nullptr;
    LiteRt220Api::Handle outputTensor = nullptr;
    LiteRt220Api::RankedTensorType inputTensorType{};
    LiteRt220Api::RankedTensorType outputTensorType{};
    LiteRt220Api::Handle options = nullptr;
    LiteRt220Api::Handle compiledModel = nullptr;
    std::vector<LiteRt220Api::Handle> inputBuffers;
    std::vector<LiteRt220Api::Handle> outputBuffers;
};

MdxPlan* fromHandle(jlong handle) {
    return reinterpret_cast<MdxPlan*>(static_cast<intptr_t>(handle));
}

MdxLiteRtManagedPipeline* managedPipelineFromHandle(jlong handle) {
    return reinterpret_cast<MdxLiteRtManagedPipeline*>(static_cast<intptr_t>(handle));
}

bool copyFromJava(JNIEnv* env, jfloatArray source, std::vector<float>& destination) {
    if (source == nullptr ||
        static_cast<size_t>(env->GetArrayLength(source)) != destination.size()) {
        return false;
    }
    env->GetFloatArrayRegion(source, 0, static_cast<jsize>(destination.size()), destination.data());
    return !env->ExceptionCheck();
}

bool copyToJava(JNIEnv* env, const std::vector<float>& source, jfloatArray destination) {
    if (destination == nullptr ||
        static_cast<size_t>(env->GetArrayLength(destination)) != source.size()) {
        return false;
    }
    env->SetFloatArrayRegion(destination, 0, static_cast<jsize>(source.size()), source.data());
    return !env->ExceptionCheck();
}

class UtfChars {
   public:
    UtfChars(JNIEnv* inputEnv, jstring inputValue)
        : env(inputEnv),
          value(inputValue),
          chars(inputValue == nullptr ? nullptr : env->GetStringUTFChars(inputValue, nullptr)) {}

    ~UtfChars() {
        if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    }

    const char* get() const { return chars; }

   private:
    JNIEnv* env;
    jstring value;
    const char* chars;
};

void clearLiteRtError() {
    gLiteRtLastError.clear();
    gLiteRtLastErrorStage = static_cast<int>(LiteRtStage::EnvironmentCreate);
}

void setLiteRtError(const LiteRtPipelineError& error) {
    gLiteRtLastError = error.what();
    gLiteRtLastErrorStage = static_cast<int>(error.stage);
}

void setLiteRtError(const std::exception& error, LiteRtStage stage) {
    gLiteRtLastError = error.what();
    gLiteRtLastErrorStage = static_cast<int>(stage);
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mardous_booming_separation_model_NativeMdxDsp_nativeCreate(JNIEnv*, jobject, jint nFft,
                                                                    jint hopLength, jint dimF,
                                                                    jint dimT, jint chunkSize) {
    try {
        return reinterpret_cast<jlong>(new MdxPlan(nFft, hopLength, dimF, dimT, chunkSize));
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_NativeMdxDsp_nativePreprocess(JNIEnv* env, jobject,
                                                                        jlong handle,
                                                                        jfloatArray leftArray,
                                                                        jfloatArray rightArray,
                                                                        jfloatArray tensorArray) {
    if (handle == 0 || leftArray == nullptr || rightArray == nullptr || tensorArray == nullptr) {
        return JNI_FALSE;
    }
    try {
        auto* plan = fromHandle(handle);
        std::vector<float> left(plan->channelSamples());
        std::vector<float> right(plan->channelSamples());
        std::vector<float> tensor(plan->tensorElements());
        if (!copyFromJava(env, leftArray, left) || !copyFromJava(env, rightArray, right)) {
            return JNI_FALSE;
        }
        plan->preprocess(left.data(), right.data(), tensor.data());
        return copyToJava(env, tensor, tensorArray) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_NativeMdxDsp_nativePostprocess(JNIEnv* env, jobject,
                                                                         jlong handle,
                                                                         jfloatArray tensorArray,
                                                                         jfloatArray leftArray,
                                                                         jfloatArray rightArray) {
    if (handle == 0 || tensorArray == nullptr || leftArray == nullptr || rightArray == nullptr) {
        return JNI_FALSE;
    }
    try {
        auto* plan = fromHandle(handle);
        std::vector<float> tensor(plan->tensorElements());
        std::vector<float> left(plan->channelSamples());
        std::vector<float> right(plan->channelSamples());
        if (!copyFromJava(env, tensorArray, tensor)) return JNI_FALSE;
        plan->postprocess(tensor.data(), left.data(), right.data());
        return copyToJava(env, left, leftArray) && copyToJava(env, right, rightArray) ? JNI_TRUE
                                                                                      : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mardous_booming_separation_model_NativeMdxDsp_nativeDestroy(JNIEnv*, jobject,
                                                                     jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeCreate(
    JNIEnv* env, jobject, jstring coreLibraryPath, jstring modelPath, jstring inputName,
    jstring outputName, jint nFft, jint hopLength, jint dimF, jint dimT, jint chunkSize,
    jint cpuThreads, jboolean boundedGpu, jint slotCount) {
    UtfChars core(env, coreLibraryPath);
    UtfChars model(env, modelPath);
    UtfChars input(env, inputName);
    UtfChars output(env, outputName);
    if (core.get() == nullptr || model.get() == nullptr || input.get() == nullptr ||
        output.get() == nullptr) {
        return 0;
    }
    try {
        auto* pipeline = new MdxLiteRtManagedPipeline(
            core.get(), model.get(), input.get(), output.get(), nFft, hopLength, dimF, dimT,
            chunkSize, cpuThreads, boundedGpu == JNI_TRUE, slotCount);
        clearLiteRtError();
        return reinterpret_cast<jlong>(pipeline);
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::ModelCompile);
    } catch (...) {
        gLiteRtLastError = "Unknown native LiteRT pipeline creation failure";
        gLiteRtLastErrorStage = static_cast<int>(LiteRtStage::ModelCompile);
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeWriteTensorNchw(
    JNIEnv* env, jobject, jlong handle, jint slot, jfloatArray tensorArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = managedPipelineFromHandle(handle);
    try {
        std::vector<float> tensor(pipeline->tensorElements());
        if (!copyFromJava(env, tensorArray, tensor)) {
            throw LiteRtPipelineError(LiteRtStage::InputWrite, "Invalid MDX input tensor array");
        }
        pipeline->writeTensorNchw(slot, tensor.data());
        clearLiteRtError();
        return JNI_TRUE;
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::InputWrite);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativePreprocessWaveform(
    JNIEnv* env, jobject, jlong handle, jint slot, jfloatArray leftArray, jfloatArray rightArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = managedPipelineFromHandle(handle);
    try {
        std::vector<float> left(pipeline->channelSamples());
        std::vector<float> right(pipeline->channelSamples());
        if (!copyFromJava(env, leftArray, left) || !copyFromJava(env, rightArray, right)) {
            throw LiteRtPipelineError(LiteRtStage::InputWrite, "Invalid MDX waveform arrays");
        }
        pipeline->preprocessWaveform(slot, left.data(), right.data());
        clearLiteRtError();
        return JNI_TRUE;
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::InputWrite);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeRun(JNIEnv*,
                                                                                    jobject,
                                                                                    jlong handle,
                                                                                    jint slot) {
    if (handle == 0) return JNI_FALSE;
    try {
        managedPipelineFromHandle(handle)->run(slot);
        clearLiteRtError();
        return JNI_TRUE;
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::Invocation);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeReadTensorNchw(
    JNIEnv* env, jobject, jlong handle, jint slot, jfloatArray tensorArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = managedPipelineFromHandle(handle);
    try {
        std::vector<float> tensor(pipeline->tensorElements());
        pipeline->readTensorNchw(slot, tensor.data());
        if (!copyToJava(env, tensor, tensorArray)) {
            throw LiteRtPipelineError(LiteRtStage::OutputRead, "Invalid MDX output tensor array");
        }
        clearLiteRtError();
        return JNI_TRUE;
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::OutputRead);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativePostprocessWaveform(
    JNIEnv* env, jobject, jlong handle, jint slot, jfloatArray leftArray, jfloatArray rightArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = managedPipelineFromHandle(handle);
    try {
        std::vector<float> left(pipeline->channelSamples());
        std::vector<float> right(pipeline->channelSamples());
        pipeline->postprocessWaveform(slot, left.data(), right.data());
        if (!copyToJava(env, left, leftArray) || !copyToJava(env, right, rightArray)) {
            throw LiteRtPipelineError(LiteRtStage::OutputRead,
                                      "Invalid MDX waveform output arrays");
        }
        clearLiteRtError();
        return JNI_TRUE;
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::OutputRead);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeDiscard(
    JNIEnv*, jobject, jlong handle, jint slot) {
    if (handle == 0) return JNI_FALSE;
    try {
        managedPipelineFromHandle(handle)->discard(slot);
        clearLiteRtError();
        return JNI_TRUE;
    } catch (const LiteRtPipelineError& error) {
        setLiteRtError(error);
    } catch (const std::exception& error) {
        setLiteRtError(error, LiteRtStage::Cleanup);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeIsInvocationInFlight(
    JNIEnv*, jobject, jlong handle) {
    return handle != 0 && managedPipelineFromHandle(handle)->invocationInFlight() ? JNI_TRUE
                                                                                  : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return JNI_TRUE;
    auto* pipeline = managedPipelineFromHandle(handle);
    if (!pipeline->canDestroy()) {
        gLiteRtLastError = "Cannot destroy the native LiteRT pipeline while inference is in flight";
        gLiteRtLastErrorStage = static_cast<int>(LiteRtStage::Cleanup);
        return JNI_FALSE;
    }
    delete pipeline;
    clearLiteRtError();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeLastError(
    JNIEnv* env, jobject) {
    return env->NewStringUTF(gLiteRtLastError.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mardous_booming_separation_model_litert_MdxLiteRtManagedPipeline_nativeLastErrorStage(
    JNIEnv*, jobject) {
    return gLiteRtLastErrorStage;
}
