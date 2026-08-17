#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <initializer_list>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "litert_abi_2_2.h"

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#define POCKETFFT_CACHE_SIZE 8
#define POCKETFFT_NO_MULTITHREADING
#include "pocketfft_hdronly.h"

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr int kChannels = 2;
constexpr int kFeatures = 4;
thread_local std::string gLastError;

int reflectIndex(int index, int size) {
    while (index < 0 || index >= size) {
        index = index < 0 ? -index : 2 * size - index - 2;
    }
    return index;
}

void setLastError(const char* message) {
    gLastError = message == nullptr ? "unknown native HTDemucs failure" : message;
}

void setLastError(const std::exception& error) {
    setLastError(error.what());
}

bool hasLength(JNIEnv* env, jarray array, size_t expected) {
    return array != nullptr && static_cast<size_t>(env->GetArrayLength(array)) == expected;
}

bool readFloatArray(
        JNIEnv* env,
        jfloatArray array,
        size_t expected,
        std::vector<float>* output) {
    if (!hasLength(env, array, expected)) return false;
    output->resize(expected);
    env->GetFloatArrayRegion(array, 0, static_cast<jsize>(expected), output->data());
    return !env->ExceptionCheck();
}

bool writeFloatArray(JNIEnv* env, jfloatArray array, const std::vector<float>& input) {
    if (!hasLength(env, array, input.size())) return false;
    env->SetFloatArrayRegion(array, 0, static_cast<jsize>(input.size()), input.data());
    return !env->ExceptionCheck();
}

class HtdemucsPlan {
public:
    HtdemucsPlan(
            int sources,
            int samples,
            int frames,
            int fftSize,
            int hop,
            int frequencies,
            int outerLeft,
            int leftFrames,
            int rightFrames,
            int trim,
            int workers)
        : sourceCount(sources),
          windowSamples(samples),
          spectrumFrames(frames),
          nFft(fftSize),
          hopSize(hop),
          dimF(frequencies),
          outerPadLeft(outerLeft),
          framePadLeft(leftFrames),
          framePadRight(rightFrames),
          centerTrim(trim),
          workerCount(workers),
          outerLength(frames * hop + 2 * outerLeft),
          fullFrameCount(frames + leftFrames + rightFrames),
          reconstructedLength((fullFrameCount - 1) * hop + fftSize),
          cropStart(trim + outerLeft),
          window(static_cast<size_t>(fftSize)),
          inverseEnvelope(static_cast<size_t>(reconstructedLength), 0.f),
          shape{static_cast<size_t>(fftSize)},
          complexStride{sizeof(std::complex<float>)},
          realStride{sizeof(float)} {
        if ((sourceCount != 4 && sourceCount != 6) || windowSamples != 343980 ||
            spectrumFrames != 336 || nFft != 4096 || hopSize != 1024 || dimF != 2048 ||
            outerPadLeft != 1536 || framePadLeft != 2 || framePadRight != 2 ||
            centerTrim != 2048 || workerCount < 1 || workerCount > 8 ||
            spectrumFrames != (windowSamples + hopSize - 1) / hopSize ||
            cropStart + windowSamples > reconstructedLength) {
            throw std::invalid_argument("unsupported HTDemucs DSP contract");
        }
        for (int index = 0; index < nFft; ++index) {
            window[static_cast<size_t>(index)] = .5f - .5f * std::cos(
                2.f * kPi * static_cast<float>(index) / static_cast<float>(nFft));
        }
        for (int frame = 0; frame < fullFrameCount; ++frame) {
            const int offset = frame * hopSize;
            for (int sample = 0; sample < nFft; ++sample) {
                const float value = window[static_cast<size_t>(sample)];
                inverseEnvelope[static_cast<size_t>(offset + sample)] += value * value;
            }
        }
        for (float& value : inverseEnvelope) {
            value = value > 1e-10f ? 1.f / value : 0.f;
        }
    }

    size_t waveformInputElements() const {
        return static_cast<size_t>(kChannels) * windowSamples;
    }

    size_t spectrumInputElements() const {
        return static_cast<size_t>(kFeatures) * dimF * spectrumFrames;
    }

    size_t frequencyOutputElements() const {
        return static_cast<size_t>(sourceCount) * spectrumInputElements();
    }

    size_t waveformOutputElements() const {
        return static_cast<size_t>(sourceCount) * kChannels * windowSamples;
    }

    void preprocess(const float* waveform, float* spectrum) const {
        const float scale = 1.f / std::sqrt(static_cast<float>(nFft));
        runWorkers(kChannels, [&](int channel) {
            std::vector<float> input(static_cast<size_t>(nFft));
            std::vector<std::complex<float>> output(static_cast<size_t>(nFft / 2 + 1));
            for (int frame = 0; frame < spectrumFrames; ++frame) {
                const int outerStart = (frame + framePadLeft) * hopSize - centerTrim;
                for (int sample = 0; sample < nFft; ++sample) {
                    const int outerIndex = reflectIndex(outerStart + sample, outerLength);
                    const int rawIndex = reflectIndex(outerIndex - outerPadLeft, windowSamples);
                    input[static_cast<size_t>(sample)] =
                        waveform[static_cast<size_t>(channel) * windowSamples + rawIndex] *
                        window[static_cast<size_t>(sample)];
                }
                pocketfft::r2c(
                    shape,
                    realStride,
                    complexStride,
                    0,
                    pocketfft::FORWARD,
                    input.data(),
                    output.data(),
                    scale,
                    1);
                const int realFeature = channel * 2;
                for (int frequency = 0; frequency < dimF; ++frequency) {
                    const size_t realIndex =
                        (static_cast<size_t>(realFeature) * dimF + frequency) *
                        spectrumFrames + frame;
                    spectrum[realIndex] = output[static_cast<size_t>(frequency)].real();
                    spectrum[realIndex + static_cast<size_t>(dimF) * spectrumFrames] =
                        output[static_cast<size_t>(frequency)].imag();
                }
            }
        });
    }

    void postprocess(const float* frequency, const float* timeWaveform, float* output) const {
        std::atomic<int> nextPlane{0};
        const int planeCount = sourceCount * kChannels;
        const float inverseScale = std::sqrt(static_cast<float>(nFft)) /
            static_cast<float>(nFft);
        runWorkers(std::min(workerCount, planeCount), [&](int) {
            std::vector<std::complex<float>> input(static_cast<size_t>(nFft / 2 + 1));
            std::vector<float> inverse(static_cast<size_t>(nFft));
            std::vector<float> reconstructed(static_cast<size_t>(reconstructedLength));
            while (true) {
                const int plane = nextPlane.fetch_add(1, std::memory_order_relaxed);
                if (plane >= planeCount) return;
                const int source = plane / kChannels;
                const int channel = plane % kChannels;
                std::fill(reconstructed.begin(), reconstructed.end(), 0.f);
                for (int fullFrame = 0; fullFrame < fullFrameCount; ++fullFrame) {
                    std::fill(input.begin(), input.end(), std::complex<float>{0.f, 0.f});
                    if (fullFrame >= framePadLeft &&
                        fullFrame < spectrumFrames + framePadLeft) {
                        const int modelFrame = fullFrame - framePadLeft;
                        const int realFeature = source * kFeatures + channel * 2;
                        for (int bin = 0; bin < dimF; ++bin) {
                            const size_t realIndex =
                                (static_cast<size_t>(realFeature) * dimF + bin) *
                                spectrumFrames + modelFrame;
                            input[static_cast<size_t>(bin)] = {
                                frequency[realIndex],
                                frequency[realIndex +
                                    static_cast<size_t>(dimF) * spectrumFrames],
                            };
                        }
                    }
                    pocketfft::c2r(
                        shape,
                        complexStride,
                        realStride,
                        0,
                        pocketfft::BACKWARD,
                        input.data(),
                        inverse.data(),
                        inverseScale,
                        1);
                    const int offset = fullFrame * hopSize;
                    for (int sample = 0; sample < nFft; ++sample) {
                        reconstructed[static_cast<size_t>(offset + sample)] +=
                            inverse[static_cast<size_t>(sample)] *
                            window[static_cast<size_t>(sample)];
                    }
                }
                const size_t base = static_cast<size_t>(plane) * windowSamples;
                for (int sample = 0; sample < windowSamples; ++sample) {
                    const int index = cropStart + sample;
                    const float frequencyValue =
                        reconstructed[static_cast<size_t>(index)] *
                        inverseEnvelope[static_cast<size_t>(index)];
                    output[base + sample] = frequencyValue +
                        (timeWaveform == nullptr ? 0.f : timeWaveform[base + sample]);
                }
            }
        });
    }

private:
    template <class Action>
    void runWorkers(int count, Action action) const {
        if (count == 1) {
            action(0);
            return;
        }
        std::exception_ptr firstError;
        std::mutex errorMutex;
        auto invoke = [&](int worker) noexcept {
            try {
                action(worker);
            } catch (...) {
                std::lock_guard<std::mutex> lock(errorMutex);
                if (firstError == nullptr) firstError = std::current_exception();
            }
        };
        std::vector<std::thread> threads;
        threads.reserve(static_cast<size_t>(count - 1));
        try {
            for (int worker = 1; worker < count; ++worker) {
                threads.emplace_back(invoke, worker);
            }
        } catch (...) {
            for (auto& thread : threads) {
                if (thread.joinable()) thread.join();
            }
            throw;
        }
        invoke(0);
        for (auto& thread : threads) {
            if (thread.joinable()) thread.join();
        }
        if (firstError != nullptr) std::rethrow_exception(firstError);
    }

    int sourceCount;
    int windowSamples;
    int spectrumFrames;
    int nFft;
    int hopSize;
    int dimF;
    int outerPadLeft;
    int framePadLeft;
    int framePadRight;
    int centerTrim;
    int workerCount;
    int outerLength;
    int fullFrameCount;
    int reconstructedLength;
    int cropStart;
    std::vector<float> window;
    std::vector<float> inverseEnvelope;
    pocketfft::shape_t shape;
    pocketfft::stride_t complexStride;
    pocketfft::stride_t realStride;
};

std::string canonicalPath(const char* path) {
    std::array<char, 4096> resolved{};
    return realpath(path, resolved.data()) == nullptr ? std::string(path)
                                                      : std::string(resolved.data());
}

class HtdemucsLiteRtApi {
public:
    using Handle = booming::litert220::Handle;
    using Status = booming::litert220::Status;
    using RankedTensorType = booming::litert220::RankedTensorType;

    explicit HtdemucsLiteRtApi(const char* coreLibraryPath) {
        if (coreLibraryPath == nullptr || coreLibraryPath[0] == '\0') {
            throw std::invalid_argument("The LiteRT core library path is empty");
        }
        expectedLibraryPath = canonicalPath(coreLibraryPath);
        library = dlopen(coreLibraryPath, RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) {
            throw std::runtime_error(
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
            getInputRequirements = load<GetBufferRequirements>(
                "LiteRtGetCompiledModelInputBufferRequirements");
            getOutputRequirements = load<GetBufferRequirements>(
                "LiteRtGetCompiledModelOutputBufferRequirements");
            createManagedBuffer = load<CreateManagedBuffer>(
                "LiteRtCreateManagedTensorBufferFromRequirements");
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
                throw std::runtime_error(
                    "LiteRT symbols did not resolve from the verified absolute core path");
            }
        } catch (...) {
            dlclose(library);
            library = nullptr;
            throw;
        }
    }

    ~HtdemucsLiteRtApi() {
        if (library != nullptr) dlclose(library);
    }

    HtdemucsLiteRtApi(const HtdemucsLiteRtApi&) = delete;
    HtdemucsLiteRtApi& operator=(const HtdemucsLiteRtApi&) = delete;

    void check(Status status, const char* operation) const {
        if (status == booming::litert220::kStatusOk) return;
        const char* detail = getStatusString == nullptr ? nullptr : getStatusString(status);
        throw std::runtime_error(std::string(operation) + " failed: " +
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
            throw std::runtime_error(std::string("Missing LiteRT 2.2 symbol ") + name);
        }
        return reinterpret_cast<Function>(symbol);
    }

    void* library = nullptr;
    std::string expectedLibraryPath;
};

class HtdemucsLiteRtBufferLock {
public:
    HtdemucsLiteRtBufferLock(
            HtdemucsLiteRtApi& inputApi,
            HtdemucsLiteRtApi::Handle inputBuffer,
            int mode,
            const char* operation)
        : api(inputApi), buffer(inputBuffer) {
        api.check(api.lockBuffer(buffer, &data, mode), operation);
        locked = true;
    }

    ~HtdemucsLiteRtBufferLock() {
        if (locked) api.unlockBuffer(buffer);
    }

    HtdemucsLiteRtBufferLock(const HtdemucsLiteRtBufferLock&) = delete;
    HtdemucsLiteRtBufferLock& operator=(const HtdemucsLiteRtBufferLock&) = delete;

    void* get() const { return data; }

    void unlock(const char* operation) {
        if (!locked) return;
        locked = false;
        api.check(api.unlockBuffer(buffer), operation);
    }

private:
    HtdemucsLiteRtApi& api;
    HtdemucsLiteRtApi::Handle buffer;
    void* data = nullptr;
    bool locked = false;
};

class HtdemucsLiteRtManagedPipeline {
public:
    HtdemucsLiteRtManagedPipeline(
            const char* coreLibraryPath,
            const char* modelPath,
            int sourceCount,
            int windowSamples,
            int spectrumFrames,
            int fftSize,
            int hopSize,
            int dimF,
            int outerPadLeft,
            int framePadLeft,
            int framePadRight,
            int centerTrim,
            int cpuThreads,
            int workerCount)
        : api(coreLibraryPath),
          dsp(sourceCount, windowSamples, spectrumFrames, fftSize, hopSize, dimF,
              outerPadLeft, framePadLeft, framePadRight, centerTrim, workerCount),
          sourceCount(sourceCount),
          windowSamples(windowSamples),
          spectrumFrames(spectrumFrames),
          dimF(dimF),
          combinedOutput(dsp.waveformOutputElements()) {
        if (cpuThreads < 1 || cpuThreads > 16) {
            throw std::invalid_argument("HTDemucs CPU thread count is outside 1..16");
        }
        try {
            initialize(modelPath, cpuThreads);
        } catch (...) {
            cleanup();
            throw;
        }
    }

    ~HtdemucsLiteRtManagedPipeline() { cleanup(); }

    size_t waveformInputElements() const { return dsp.waveformInputElements(); }
    size_t spectrumInputElements() const { return dsp.spectrumInputElements(); }
    size_t frequencyOutputElements() const { return dsp.frequencyOutputElements(); }
    size_t waveformOutputElements() const { return dsp.waveformOutputElements(); }

    void writeWaveformInput(const float* waveform) {
        requireState(State::Empty, "HTDemucs direct pipeline is not empty");
        requireFinite(waveform, waveformInputElements(), "HTDemucs waveform input");
        writeBuffer(inputBuffers[0], waveform, waveformInputElements());
    }

    void preprocessManagedInputs() {
        requireState(State::Empty, "HTDemucs direct pipeline is not empty");
        HtdemucsLiteRtBufferLock waveform(
            api, inputBuffers[0], booming::litert220::kLockModeRead,
            "LiteRtLockTensorBuffer(waveform-read)");
        HtdemucsLiteRtBufferLock spectrum(
            api, inputBuffers[1], booming::litert220::kLockModeWrite,
            "LiteRtLockTensorBuffer(spectrum-write)");
        dsp.preprocess(static_cast<const float*>(waveform.get()),
                       static_cast<float*>(spectrum.get()));
        requireFinite(static_cast<const float*>(spectrum.get()), spectrumInputElements(),
                      "HTDemucs native STFT output");
        spectrum.unlock("LiteRtUnlockTensorBuffer(spectrum-write)");
        waveform.unlock("LiteRtUnlockTensorBuffer(waveform-read)");
        state = State::Prepared;
    }

    void writeInputs(const float* waveform, const float* spectrum) {
        requireState(State::Empty, "HTDemucs direct pipeline is not empty");
        requireFinite(waveform, waveformInputElements(), "HTDemucs waveform input");
        requireFinite(spectrum, spectrumInputElements(), "HTDemucs spectrum input");
        writeBuffer(inputBuffers[0], waveform, waveformInputElements());
        writeBuffer(inputBuffers[1], spectrum, spectrumInputElements());
        state = State::Prepared;
    }

    void run() {
        requireState(State::Prepared, "HTDemucs direct pipeline input is not prepared");
        state = State::Running;
        try {
            api.check(api.runCompiledModel(
                compiledModel, 0, inputBuffers.size(), inputBuffers.data(),
                outputBuffers.size(), outputBuffers.data()),
                "LiteRtRunCompiledModel");
            state = State::Ready;
        } catch (...) {
            state = State::Empty;
            throw;
        }
    }

    void readOutputs(float* frequency, float* waveform) {
        requireState(State::Ready, "HTDemucs direct pipeline output is not ready");
        HtdemucsLiteRtBufferLock frequencyLock(
            api, outputBuffers[0], booming::litert220::kLockModeRead,
            "LiteRtLockTensorBuffer(frequency-read)");
        HtdemucsLiteRtBufferLock waveformLock(
            api, outputBuffers[1], booming::litert220::kLockModeRead,
            "LiteRtLockTensorBuffer(time-read)");
        const auto* frequencySource = static_cast<const float*>(frequencyLock.get());
        const auto* waveformSource = static_cast<const float*>(waveformLock.get());
        requireFinite(frequencySource, frequencyOutputElements(), "HTDemucs frequency output");
        requireFinite(waveformSource, waveformOutputElements(), "HTDemucs waveform output");
        std::memcpy(frequency, frequencySource, frequencyOutputElements() * sizeof(float));
        std::memcpy(waveform, waveformSource, waveformOutputElements() * sizeof(float));
        waveformLock.unlock("LiteRtUnlockTensorBuffer(time-read)");
        frequencyLock.unlock("LiteRtUnlockTensorBuffer(frequency-read)");
    }

    const std::vector<float>& postprocessOutputs() {
        requireState(State::Ready, "HTDemucs direct pipeline output is not ready");
        try {
            HtdemucsLiteRtBufferLock frequency(
                api, outputBuffers[0], booming::litert220::kLockModeRead,
                "LiteRtLockTensorBuffer(frequency-read)");
            HtdemucsLiteRtBufferLock waveform(
                api, outputBuffers[1], booming::litert220::kLockModeRead,
                "LiteRtLockTensorBuffer(time-read)");
            const auto* frequencySource = static_cast<const float*>(frequency.get());
            const auto* waveformSource = static_cast<const float*>(waveform.get());
            requireFinite(frequencySource, frequencyOutputElements(),
                          "HTDemucs frequency output");
            requireFinite(waveformSource, waveformOutputElements(),
                          "HTDemucs waveform output");
            dsp.postprocess(frequencySource, waveformSource, combinedOutput.data());
            requireFinite(combinedOutput.data(), combinedOutput.size(),
                          "HTDemucs combined output");
            waveform.unlock("LiteRtUnlockTensorBuffer(time-read)");
            frequency.unlock("LiteRtUnlockTensorBuffer(frequency-read)");
            state = State::Empty;
            return combinedOutput;
        } catch (...) {
            state = State::Empty;
            throw;
        }
    }

    void discard() {
        if (state == State::Running) {
            throw std::runtime_error("Cannot discard HTDemucs while inference is running");
        }
        state = State::Empty;
    }

private:
    enum class State { Empty, Prepared, Running, Ready };

    void requireState(State expected, const char* message) const {
        if (state != expected) throw std::runtime_error(message);
    }

    static void requireFinite(const float* values, size_t count, const char* label) {
        if (values == nullptr) throw std::invalid_argument(std::string(label) + " is null");
        for (size_t index = 0; index < count; ++index) {
            if (!std::isfinite(values[index])) {
                throw std::runtime_error(std::string(label) +
                                         " contains a non-finite value at index " +
                                         std::to_string(index));
            }
        }
    }

    void initialize(const char* modelPath, int cpuThreads) {
        api.check(api.createEnvironment(0, nullptr, &environment), "LiteRtCreateEnvironment");
        api.check(api.createModelFromFile(environment, modelPath, &model),
                  "LiteRtCreateModelFromFile");
        validateModelContract();

        api.check(api.createOptions(&options), "LiteRtCreateOptions");
        api.check(api.setHardwareAccelerators(options, booming::litert220::kAcceleratorCpu),
                  "LiteRtSetOptionsHardwareAccelerators(CPU)");
        addOpaqueToml("xnnpack", "num_threads = " + std::to_string(cpuThreads) + "\n");
        api.check(api.createCompiledModel(environment, model, options, &compiledModel),
                  "LiteRtCreateCompiledModel");
        api.destroyOptions(options);
        options = nullptr;

        for (size_t index = 0; index < inputBuffers.size(); ++index) {
            inputBuffers[index] = createBuffer(true, index, inputTensorTypes[index],
                                                inputElementCounts[index]);
        }
        for (size_t index = 0; index < outputBuffers.size(); ++index) {
            outputBuffers[index] = createBuffer(false, index, outputTensorTypes[index],
                                                 outputElementCounts[index]);
        }
    }

    void validateModelContract() {
        size_t signatureCount = 0;
        api.check(api.getNumModelSignatures(model, &signatureCount),
                  "LiteRtGetNumModelSignatures");
        if (signatureCount == 0) {
            throw std::runtime_error("HTDemucs model exposes no LiteRT signature");
        }
        api.check(api.getModelSignature(model, 0, &signature), "LiteRtGetModelSignature");
        size_t inputCount = 0;
        size_t outputCount = 0;
        api.check(api.getNumSignatureInputs(signature, &inputCount),
                  "LiteRtGetNumSignatureInputs");
        api.check(api.getNumSignatureOutputs(signature, &outputCount),
                  "LiteRtGetNumSignatureOutputs");
        if (inputCount != inputBuffers.size() || outputCount != outputBuffers.size()) {
            throw std::runtime_error(
                "HTDemucs model must expose exactly two signature inputs and outputs");
        }

        const std::array<const char*, 2> expectedInputs{"args_0", "args_1"};
        const std::array<const char*, 2> expectedOutputs{"output_0", "output_1"};
        for (size_t index = 0; index < inputCount; ++index) {
            const char* name = nullptr;
            api.check(api.getSignatureInputName(signature, index, &name),
                      "LiteRtGetSignatureInputName");
            if (name == nullptr || std::strcmp(name, expectedInputs[index]) != 0) {
                throw std::runtime_error(
                    "HTDemucs signature input names do not match the product contract");
            }
            HtdemucsLiteRtApi::Handle tensor = nullptr;
            api.check(api.getSignatureInputTensor(signature, index, &tensor),
                      "LiteRtGetSignatureInputTensorByIndex");
            api.check(api.getRankedTensorType(tensor, &inputTensorTypes[index]),
                      "LiteRtGetRankedTensorType(input)");
        }
        for (size_t index = 0; index < outputCount; ++index) {
            const char* name = nullptr;
            api.check(api.getSignatureOutputName(signature, index, &name),
                      "LiteRtGetSignatureOutputName");
            if (name == nullptr || std::strcmp(name, expectedOutputs[index]) != 0) {
                throw std::runtime_error(
                    "HTDemucs signature output names do not match the product contract");
            }
            HtdemucsLiteRtApi::Handle tensor = nullptr;
            api.check(api.getSignatureOutputTensor(signature, index, &tensor),
                      "LiteRtGetSignatureOutputTensorByIndex");
            api.check(api.getRankedTensorType(tensor, &outputTensorTypes[index]),
                      "LiteRtGetRankedTensorType(output)");
        }

        inputElementCounts = {waveformInputElements(), spectrumInputElements()};
        outputElementCounts = {frequencyOutputElements(), waveformOutputElements()};
        validateTensorType(inputTensorTypes[0], {1, kChannels, windowSamples}, "waveform input");
        validateTensorType(inputTensorTypes[1],
                           {1, kFeatures, dimF, spectrumFrames}, "spectrum input");
        validateTensorType(outputTensorTypes[0],
                           {1, sourceCount, kFeatures, dimF, spectrumFrames},
                           "frequency output");
        validateTensorType(outputTensorTypes[1],
                           {1, sourceCount, kChannels, windowSamples}, "waveform output");
    }

    static void validateTensorType(
            const HtdemucsLiteRtApi::RankedTensorType& type,
            std::initializer_list<int32_t> expected,
            const char* label) {
        if (type.elementType != booming::litert220::kElementTypeFloat32 ||
            type.layout.rank != expected.size() || type.layout.hasStrides != 0) {
            throw std::runtime_error(std::string("HTDemucs ") + label +
                                     " is not contiguous ranked FP32");
        }
        size_t index = 0;
        for (int32_t dimension : expected) {
            if (type.layout.dimensions[index++] != dimension) {
                throw std::runtime_error(std::string("HTDemucs ") + label +
                                         " shape does not match the product contract");
            }
        }
    }

    void addOpaqueToml(const char* identifier, const std::string& toml) {
        void* payload = std::malloc(toml.size() + 1);
        if (payload == nullptr) throw std::bad_alloc();
        std::memcpy(payload, toml.c_str(), toml.size() + 1);
        HtdemucsLiteRtApi::Handle opaqueOptions = nullptr;
        const auto createStatus = api.createOpaqueOptions(
            identifier, payload, +[](void* value) { std::free(value); }, &opaqueOptions);
        if (createStatus != booming::litert220::kStatusOk) std::free(payload);
        api.check(createStatus, "LiteRtCreateOpaqueOptions");
        const auto addStatus = api.addOpaqueOptions(options, opaqueOptions);
        if (addStatus != booming::litert220::kStatusOk) {
            api.destroyOpaqueOptions(opaqueOptions);
        }
        api.check(addStatus, "LiteRtAddOpaqueOptions");
    }

    HtdemucsLiteRtApi::Handle createBuffer(
            bool input,
            size_t index,
            const HtdemucsLiteRtApi::RankedTensorType& tensorType,
            size_t elementCount) {
        HtdemucsLiteRtApi::Handle requirements = nullptr;
        auto getRequirements = input ? api.getInputRequirements : api.getOutputRequirements;
        api.check(getRequirements(compiledModel, 0, index, &requirements),
                  input ? "LiteRtGetCompiledModelInputBufferRequirements"
                        : "LiteRtGetCompiledModelOutputBufferRequirements");
        HtdemucsLiteRtApi::Handle buffer = nullptr;
        api.check(api.createManagedBuffer(environment, &tensorType, requirements, &buffer),
                  "LiteRtCreateManagedTensorBufferFromRequirements");
        size_t packedSize = 0;
        api.check(api.getPackedSize(buffer, &packedSize), "LiteRtGetTensorBufferPackedSize");
        const size_t expectedSize = elementCount * sizeof(float);
        if (packedSize != expectedSize) {
            api.destroyBuffer(buffer);
            throw std::runtime_error(
                std::string(input ? "Input" : "Output") +
                " managed buffer packed size does not match the HTDemucs tensor");
        }
        return buffer;
    }

    void writeBuffer(HtdemucsLiteRtApi::Handle buffer, const float* source, size_t elements) {
        HtdemucsLiteRtBufferLock destination(
            api, buffer, booming::litert220::kLockModeWrite,
            "LiteRtLockTensorBuffer(write)");
        std::memcpy(destination.get(), source, elements * sizeof(float));
        destination.unlock("LiteRtUnlockTensorBuffer(write)");
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
        inputBuffers.fill(nullptr);
        outputBuffers.fill(nullptr);
        compiledModel = nullptr;
        options = nullptr;
        model = nullptr;
        environment = nullptr;
    }

    HtdemucsLiteRtApi api;
    HtdemucsPlan dsp;
    int sourceCount;
    int windowSamples;
    int spectrumFrames;
    int dimF;
    std::vector<float> combinedOutput;
    State state = State::Empty;
    HtdemucsLiteRtApi::Handle environment = nullptr;
    HtdemucsLiteRtApi::Handle model = nullptr;
    HtdemucsLiteRtApi::Handle signature = nullptr;
    HtdemucsLiteRtApi::Handle options = nullptr;
    HtdemucsLiteRtApi::Handle compiledModel = nullptr;
    std::array<HtdemucsLiteRtApi::RankedTensorType, 2> inputTensorTypes{};
    std::array<HtdemucsLiteRtApi::RankedTensorType, 2> outputTensorTypes{};
    std::array<size_t, 2> inputElementCounts{};
    std::array<size_t, 2> outputElementCounts{};
    std::array<HtdemucsLiteRtApi::Handle, 2> inputBuffers{};
    std::array<HtdemucsLiteRtApi::Handle, 2> outputBuffers{};
};

class HtdemucsOlaState {
public:
    explicit HtdemucsOlaState(int sources)
        : sourceCount(sources),
          planeCount(sources * kChannels),
          carry(static_cast<size_t>(planeCount) * kOverlapSamples, 0.f),
          carryWeight(static_cast<size_t>(kOverlapSamples), 0.f) {
        if (sourceCount != 4 && sourceCount != 6) {
            throw std::invalid_argument(
                "native HTDemucs OLA supports only four or six stems");
        }
    }

    int process(
            const float* combined,
            int actualSamples,
            int cropLeft,
            bool hasNext,
            float scale,
            float mean,
            float* output) {
#pragma clang fp contract(off)
        if (combined == nullptr || output == nullptr || actualSamples < 1 ||
            actualSamples > kWindowSamples || cropLeft < 0 ||
            cropLeft + actualSamples > kWindowSamples || !std::isfinite(scale) ||
            scale <= 0.f || !std::isfinite(mean)) {
            throw std::invalid_argument("invalid native HTDemucs OLA window");
        }
        const int finalizedFrames = hasNext ? kStrideSamples : actualSamples;
        const int nextLength = hasNext ? actualSamples - kStrideSamples : 0;
        if (finalizedFrames > actualSamples || nextLength < 0 ||
            nextLength > kOverlapSamples) {
            throw std::invalid_argument("invalid native HTDemucs OLA overlap");
        }
        for (int plane = 0; plane < planeCount; ++plane) {
            const size_t sourceBase =
                static_cast<size_t>(plane) * kWindowSamples + cropLeft;
            const size_t outputBase = static_cast<size_t>(plane) * kWindowSamples;
            const size_t carryBase = static_cast<size_t>(plane) * kOverlapSamples;
            for (int frame = 0; frame < finalizedFrames; ++frame) {
                const float weight = triangleWeight(frame);
                float numerator = combined[sourceBase + static_cast<size_t>(frame)] * weight;
                float denominator = weight;
                if (frame < carryLength) {
                    numerator += carry[carryBase + static_cast<size_t>(frame)];
                    denominator += carryWeight[static_cast<size_t>(frame)];
                }
                if (!(denominator > 0.f)) {
                    throw std::runtime_error(
                        "native HTDemucs OLA denominator is not positive");
                }
                const float value = (numerator / denominator) * scale + mean;
                if (!std::isfinite(value)) {
                    throw std::runtime_error(
                        "native HTDemucs OLA produced non-finite output");
                }
                output[outputBase + static_cast<size_t>(frame)] = value;
            }
            std::fill(
                output + outputBase + finalizedFrames,
                output + outputBase + kWindowSamples,
                0.f);
            if (hasNext) {
                for (int frame = 0; frame < nextLength; ++frame) {
                    const int activeFrame = kStrideSamples + frame;
                    const float weight = triangleWeight(activeFrame);
                    carry[carryBase + static_cast<size_t>(frame)] =
                        combined[sourceBase + static_cast<size_t>(activeFrame)] * weight;
                }
                std::fill(
                    carry.begin() + static_cast<ptrdiff_t>(carryBase + nextLength),
                    carry.begin() + static_cast<ptrdiff_t>(carryBase + kOverlapSamples),
                    0.f);
            }
        }
        if (hasNext) {
            for (int frame = 0; frame < nextLength; ++frame) {
                carryWeight[static_cast<size_t>(frame)] =
                    triangleWeight(kStrideSamples + frame);
            }
            std::fill(carryWeight.begin() + nextLength, carryWeight.end(), 0.f);
            carryLength = nextLength;
        } else {
            reset();
        }
        return finalizedFrames;
    }

    void reset() {
        std::fill(carry.begin(), carry.end(), 0.f);
        std::fill(carryWeight.begin(), carryWeight.end(), 0.f);
        carryLength = 0;
    }

    int currentCarryLength() const {
        return carryLength;
    }

    size_t windowElements() const {
        return static_cast<size_t>(planeCount) * kWindowSamples;
    }

private:
    static constexpr int kWindowSamples = 343980;
    static constexpr int kStrideSamples = 257985;
    static constexpr int kOverlapSamples = 85995;

    static float triangleWeight(int index) {
        const int half = kWindowSamples / 2;
        const int numerator = index < half ? index + 1 : kWindowSamples - index;
        return static_cast<float>(numerator) / static_cast<float>(half);
    }

    int sourceCount;
    int planeCount;
    int carryLength = 0;
    std::vector<float> carry;
    std::vector<float> carryWeight;
};

int16_t quantizePcm16(float value) {
    const float clipped = std::max(-1.0f, std::min(1.0f, value));
    return static_cast<int16_t>(std::floor(clipped * 32767.0f + 0.5f));
}

#if defined(__aarch64__)
int16x4_t quantizeNeon(float32x4_t values) {
    const float32x4_t minimum = vdupq_n_f32(-1.0f);
    const float32x4_t maximum = vdupq_n_f32(1.0f);
    const float32x4_t scale = vdupq_n_f32(32767.0f);
    const float32x4_t half = vdupq_n_f32(0.5f);
    values = vmaxq_f32(minimum, vminq_f32(maximum, values));
    values = vrndmq_f32(vaddq_f32(vmulq_f32(values, scale), half));
    return vqmovn_s32(vcvtq_s32_f32(values));
}

void encodePlanar(
        const float* left,
        const float* right,
        int16_t* output,
        int frames) {
    int frame = 0;
    for (; frame + 4 <= frames; frame += 4) {
        const int16x4_t leftPcm = quantizeNeon(vld1q_f32(left + frame));
        const int16x4_t rightPcm = quantizeNeon(vld1q_f32(right + frame));
        const int16x4x2_t interleaved = vzip_s16(leftPcm, rightPcm);
        vst1_s16(output + frame * 2, interleaved.val[0]);
        vst1_s16(output + frame * 2 + 4, interleaved.val[1]);
    }
    for (; frame < frames; ++frame) {
        output[frame * 2] = quantizePcm16(left[frame]);
        output[frame * 2 + 1] = quantizePcm16(right[frame]);
    }
}
#else
void encodePlanar(
        const float* left,
        const float* right,
        int16_t* output,
        int frames) {
    for (int frame = 0; frame < frames; ++frame) {
        output[frame * 2] = quantizePcm16(left[frame]);
        output[frame * 2 + 1] = quantizePcm16(right[frame]);
    }
}
#endif

HtdemucsPlan* planFromHandle(jlong handle) {
    return reinterpret_cast<HtdemucsPlan*>(static_cast<intptr_t>(handle));
}

HtdemucsOlaState* olaFromHandle(jlong handle) {
    return reinterpret_cast<HtdemucsOlaState*>(static_cast<intptr_t>(handle));
}

HtdemucsLiteRtManagedPipeline* directPipelineFromHandle(jlong handle) {
    return reinterpret_cast<HtdemucsLiteRtManagedPipeline*>(static_cast<intptr_t>(handle));
}

class ScopedJniFloats {
public:
    ScopedJniFloats(JNIEnv* inputEnv, jfloatArray inputArray, jint inputReleaseMode)
        : env(inputEnv), array(inputArray), releaseMode(inputReleaseMode) {
        data = array == nullptr ? nullptr : env->GetFloatArrayElements(array, nullptr);
    }

    ~ScopedJniFloats() {
        if (data != nullptr) env->ReleaseFloatArrayElements(array, data, releaseMode);
    }

    ScopedJniFloats(const ScopedJniFloats&) = delete;
    ScopedJniFloats& operator=(const ScopedJniFloats&) = delete;

    jfloat* get() const { return data; }

private:
    JNIEnv* env;
    jfloatArray array;
    jint releaseMode;
    jfloat* data = nullptr;
};

class ScopedCriticalFloats {
public:
    ScopedCriticalFloats(JNIEnv* inputEnv, jfloatArray inputArray, jint inputReleaseMode)
        : env(inputEnv), array(inputArray), releaseMode(inputReleaseMode) {
        data = array == nullptr
            ? nullptr
            : static_cast<jfloat*>(env->GetPrimitiveArrayCritical(array, nullptr));
    }

    ~ScopedCriticalFloats() {
        if (data != nullptr) env->ReleasePrimitiveArrayCritical(array, data, releaseMode);
    }

    ScopedCriticalFloats(const ScopedCriticalFloats&) = delete;
    ScopedCriticalFloats& operator=(const ScopedCriticalFloats&) = delete;

    jfloat* get() const { return data; }

private:
    JNIEnv* env;
    jfloatArray array;
    jint releaseMode;
    jfloat* data = nullptr;
};

class ScopedCriticalBytes {
public:
    ScopedCriticalBytes(JNIEnv* inputEnv, jbyteArray inputArray, jint inputReleaseMode)
        : env(inputEnv), array(inputArray), releaseMode(inputReleaseMode) {
        data = array == nullptr
            ? nullptr
            : static_cast<jbyte*>(env->GetPrimitiveArrayCritical(array, nullptr));
    }

    ~ScopedCriticalBytes() {
        if (data != nullptr) env->ReleasePrimitiveArrayCritical(array, data, releaseMode);
    }

    ScopedCriticalBytes(const ScopedCriticalBytes&) = delete;
    ScopedCriticalBytes& operator=(const ScopedCriticalBytes&) = delete;

    jbyte* get() const { return data; }

private:
    JNIEnv* env;
    jbyteArray array;
    jint releaseMode;
    jbyte* data = nullptr;
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeCreate(
        JNIEnv* env,
        jobject,
        jstring coreLibraryPath,
        jstring modelPath,
        jint sourceCount,
        jint windowSamples,
        jint spectrumFrames,
        jint fftSize,
        jint hopSize,
        jint dimF,
        jint outerPadLeft,
        jint framePadLeft,
        jint framePadRight,
        jint centerTrim,
        jint cpuThreads,
        jint workerCount) {
    const char* core = env->GetStringUTFChars(coreLibraryPath, nullptr);
    if (core == nullptr) return 0;
    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    if (model == nullptr) {
        env->ReleaseStringUTFChars(coreLibraryPath, core);
        return 0;
    }
    try {
        auto* pipeline = new HtdemucsLiteRtManagedPipeline(
            core, model, sourceCount, windowSamples, spectrumFrames, fftSize, hopSize, dimF,
            outerPadLeft, framePadLeft, framePadRight, centerTrim, cpuThreads, workerCount);
        env->ReleaseStringUTFChars(modelPath, model);
        env->ReleaseStringUTFChars(coreLibraryPath, core);
        gLastError.clear();
        return reinterpret_cast<jlong>(pipeline);
    } catch (const std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, model);
        env->ReleaseStringUTFChars(coreLibraryPath, core);
        setLastError(error);
        return 0;
    } catch (...) {
        env->ReleaseStringUTFChars(modelPath, model);
        env->ReleaseStringUTFChars(coreLibraryPath, core);
        setLastError("Unknown native LiteRT HTDemucs creation failure.");
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeWriteInputs(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray waveformArray,
        jfloatArray spectrumArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = directPipelineFromHandle(handle);
    if (!hasLength(env, waveformArray, pipeline->waveformInputElements()) ||
        !hasLength(env, spectrumArray, pipeline->spectrumInputElements())) {
        setLastError("Native LiteRT HTDemucs input arrays differ from the frozen contract.");
        return JNI_FALSE;
    }
    try {
        ScopedJniFloats waveform(env, waveformArray, JNI_ABORT);
        ScopedJniFloats spectrum(env, spectrumArray, JNI_ABORT);
        if (waveform.get() == nullptr || spectrum.get() == nullptr) {
            setLastError("Native LiteRT HTDemucs could not access its input arrays.");
            return JNI_FALSE;
        }
        pipeline->writeInputs(waveform.get(), spectrum.get());
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    } catch (...) {
        setLastError("Unknown native LiteRT HTDemucs input failure.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativePreprocessAndWriteInput(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray waveformArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = directPipelineFromHandle(handle);
    if (!hasLength(env, waveformArray, pipeline->waveformInputElements())) {
        setLastError("Native LiteRT HTDemucs waveform differs from the frozen contract.");
        return JNI_FALSE;
    }
    try {
        {
            ScopedJniFloats waveform(env, waveformArray, JNI_ABORT);
            if (waveform.get() == nullptr) {
                setLastError("Native LiteRT HTDemucs could not access its waveform input.");
                return JNI_FALSE;
            }
            pipeline->writeWaveformInput(waveform.get());
        }
        pipeline->preprocessManagedInputs();
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        try {
            pipeline->discard();
        } catch (...) {
        }
        setLastError(error);
        return JNI_FALSE;
    } catch (...) {
        try {
            pipeline->discard();
        } catch (...) {
        }
        setLastError("Unknown native LiteRT HTDemucs preprocess failure.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeRun(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    try {
        directPipelineFromHandle(handle)->run();
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    } catch (...) {
        setLastError("Unknown native LiteRT HTDemucs invocation failure.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeReadOutputs(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray frequencyArray,
        jfloatArray waveformArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = directPipelineFromHandle(handle);
    if (!hasLength(env, frequencyArray, pipeline->frequencyOutputElements()) ||
        !hasLength(env, waveformArray, pipeline->waveformOutputElements())) {
        setLastError("Native LiteRT HTDemucs output arrays differ from the frozen contract.");
        return JNI_FALSE;
    }
    try {
        ScopedJniFloats frequency(env, frequencyArray, 0);
        ScopedJniFloats waveform(env, waveformArray, 0);
        if (frequency.get() == nullptr || waveform.get() == nullptr) {
            setLastError("Native LiteRT HTDemucs could not access diagnostic outputs.");
            return JNI_FALSE;
        }
        pipeline->readOutputs(frequency.get(), waveform.get());
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    } catch (...) {
        setLastError("Unknown native LiteRT HTDemucs output-read failure.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativePostprocessOutputs(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray outputArray) {
    if (handle == 0) return JNI_FALSE;
    auto* pipeline = directPipelineFromHandle(handle);
    if (!hasLength(env, outputArray, pipeline->waveformOutputElements())) {
        setLastError("Native LiteRT HTDemucs combined output differs from the frozen contract.");
        return JNI_FALSE;
    }
    try {
        const auto& output = pipeline->postprocessOutputs();
        env->SetFloatArrayRegion(outputArray, 0, static_cast<jsize>(output.size()), output.data());
        if (env->ExceptionCheck()) return JNI_FALSE;
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    } catch (...) {
        setLastError("Unknown native LiteRT HTDemucs postprocess failure.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeDiscard(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    try {
        directPipelineFromHandle(handle)->discard();
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    } catch (...) {
        setLastError("Unknown native LiteRT HTDemucs discard failure.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    delete directPipelineFromHandle(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mardous_booming_separation_model_litert_HtdemucsLiteRtDirectPipeline_nativeLastError(
        JNIEnv* env, jobject) {
    return env->NewStringUTF(gLastError.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsDsp_nativeCreate(
        JNIEnv*,
        jobject,
        jint sourceCount,
        jint windowSamples,
        jint spectrumFrames,
        jint fftSize,
        jint hopSize,
        jint dimF,
        jint outerPadLeft,
        jint framePadLeft,
        jint framePadRight,
        jint centerTrim,
        jint workerCount) {
    try {
        auto* plan = new HtdemucsPlan(
            sourceCount,
            windowSamples,
            spectrumFrames,
            fftSize,
            hopSize,
            dimF,
            outerPadLeft,
            framePadLeft,
            framePadRight,
            centerTrim,
            workerCount);
        gLastError.clear();
        return reinterpret_cast<jlong>(plan);
    } catch (const std::exception& error) {
        setLastError(error);
        return 0;
    } catch (...) {
        setLastError("unknown native HTDemucs plan creation failure");
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsDsp_nativePreprocess(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray waveformArray,
        jfloatArray spectrumArray) {
    if (handle == 0) return JNI_FALSE;
    const auto* plan = planFromHandle(handle);
    try {
        std::vector<float> waveform;
        std::vector<float> spectrum(plan->spectrumInputElements());
        if (!readFloatArray(env, waveformArray, plan->waveformInputElements(), &waveform)) {
            setLastError("native HTDemucs waveform input differs from the frozen contract");
            return JNI_FALSE;
        }
        plan->preprocess(waveform.data(), spectrum.data());
        if (!writeFloatArray(env, spectrumArray, spectrum)) {
            setLastError("native HTDemucs spectrum output differs from the frozen contract");
            return JNI_FALSE;
        }
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsDsp_nativePostprocess(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray frequencyArray,
        jfloatArray timeArray,
        jfloatArray outputArray) {
    if (handle == 0) return JNI_FALSE;
    const auto* plan = planFromHandle(handle);
    try {
        std::vector<float> frequency;
        std::vector<float> time;
        std::vector<float> output(plan->waveformOutputElements());
        if (!readFloatArray(
                env,
                frequencyArray,
                plan->frequencyOutputElements(),
                &frequency)) {
            setLastError("native HTDemucs frequency output differs from the frozen contract");
            return JNI_FALSE;
        }
        const float* timeData = nullptr;
        if (timeArray != nullptr) {
            if (!readFloatArray(env, timeArray, plan->waveformOutputElements(), &time)) {
                setLastError("native HTDemucs waveform output differs from the frozen contract");
                return JNI_FALSE;
            }
            timeData = time.data();
        }
        plan->postprocess(frequency.data(), timeData, output.data());
        if (!writeFloatArray(env, outputArray, output)) {
            setLastError("native HTDemucs combined output differs from the frozen contract");
            return JNI_FALSE;
        }
        gLastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        setLastError(error);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsDsp_nativeDestroy(
        JNIEnv*,
        jobject,
        jlong handle) {
    delete planFromHandle(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsDsp_nativeLastError(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF(gLastError.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsOlaState_nativeCreate(
        JNIEnv*,
        jobject,
        jint sourceCount) {
    try {
        auto* state = new HtdemucsOlaState(sourceCount);
        gLastError.clear();
        return reinterpret_cast<jlong>(state);
    } catch (const std::exception& error) {
        setLastError(error);
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsOlaState_nativeProcess(
        JNIEnv* env,
        jobject,
        jlong handle,
        jfloatArray combinedArray,
        jint actualSamples,
        jint cropLeft,
        jboolean hasNext,
        jfloat scale,
        jfloat mean,
        jfloatArray outputArray) {
    if (handle == 0) return -1;
    auto* state = olaFromHandle(handle);
    if (!hasLength(env, combinedArray, state->windowElements()) ||
        !hasLength(env, outputArray, state->windowElements())) {
        setLastError("native HTDemucs OLA arrays do not match the frozen contract");
        return -1;
    }
    try {
        ScopedCriticalFloats combined(env, combinedArray, JNI_ABORT);
        if (combined.get() == nullptr) {
            setLastError("native HTDemucs OLA could not pin its input array");
            return -1;
        }
        ScopedCriticalFloats output(env, outputArray, 0);
        if (output.get() == nullptr) {
            setLastError("native HTDemucs OLA could not pin its output array");
            return -1;
        }
        const int finalized = state->process(
            combined.get(),
            actualSamples,
            cropLeft,
            hasNext == JNI_TRUE,
            scale,
            mean,
            output.get());
        gLastError.clear();
        return finalized;
    } catch (const std::exception& error) {
        state->reset();
        setLastError(error);
        return -1;
    } catch (...) {
        state->reset();
        setLastError("Unknown native HTDemucs OLA failure.");
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsOlaState_nativeCarryLength(
        JNIEnv*,
        jobject,
        jlong handle) {
    return handle == 0 ? -1 : olaFromHandle(handle)->currentCarryLength();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsOlaState_nativeReset(
        JNIEnv*,
        jobject,
        jlong handle) {
    if (handle != 0) olaFromHandle(handle)->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsOlaState_nativeDestroy(
        JNIEnv*,
        jobject,
        jlong handle) {
    delete olaFromHandle(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsOlaState_nativeLastError(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF(gLastError.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsPcm16_nativeEncodePlanar(
        JNIEnv* env,
        jobject,
        jfloatArray inputArray,
        jint leftOffset,
        jint rightOffset,
        jint frameCount,
        jbyteArray outputArray) {
    if (inputArray == nullptr || outputArray == nullptr || leftOffset < 0 ||
        rightOffset < 0 || frameCount < 0) {
        jclass type = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(type, "HTDemucs planar PCM arguments are invalid.");
        return 0;
    }
    try {
        const jsize inputSize = env->GetArrayLength(inputArray);
        const jsize outputSize = env->GetArrayLength(outputArray);
        const int64_t leftEnd = static_cast<int64_t>(leftOffset) + frameCount;
        const int64_t rightEnd = static_cast<int64_t>(rightOffset) + frameCount;
        const int64_t outputBytes = static_cast<int64_t>(frameCount) * 4;
        if (leftEnd > inputSize || rightEnd > inputSize || outputBytes > outputSize) {
            jclass type = env->FindClass("java/lang/IllegalArgumentException");
            env->ThrowNew(type, "HTDemucs planar PCM buffers are too small.");
            return 0;
        }
        {
            ScopedCriticalFloats input(env, inputArray, JNI_ABORT);
            if (input.get() != nullptr) {
                ScopedCriticalBytes output(env, outputArray, 0);
                if (output.get() != nullptr) {
                    encodePlanar(
                        input.get() + leftOffset,
                        input.get() + rightOffset,
                        reinterpret_cast<int16_t*>(output.get()),
                        frameCount);
                    return static_cast<jint>(outputBytes);
                }
            }
        }
        if (!env->ExceptionCheck()) {
            jclass type = env->FindClass("java/lang/OutOfMemoryError");
            if (type != nullptr && !env->ExceptionCheck()) {
                env->ThrowNew(type, "Could not pin native HTDemucs PCM buffers.");
            }
        }
        return 0;
    } catch (const std::exception& error) {
        jclass type = env->FindClass("java/lang/RuntimeException");
        if (type != nullptr && !env->ExceptionCheck()) env->ThrowNew(type, error.what());
        return 0;
    } catch (...) {
        jclass type = env->FindClass("java/lang/RuntimeException");
        if (type != nullptr && !env->ExceptionCheck()) {
            env->ThrowNew(type, "Unknown native HTDemucs PCM failure.");
        }
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mardous_booming_separation_model_NativeHtdemucsPcm16_nativeImplementationId(
        JNIEnv* env,
        jobject) {
#if defined(__aarch64__)
    return env->NewStringUTF("neon-aarch64-critical-array-v3");
#else
    return env->NewStringUTF("scalar-native-critical-array-v3");
#endif
}
