// Minimal public LiteRT 2.2 C ABI declarations used through dlsym.
// Derived from LiteRT v2.2.0 under the Apache License 2.0.
#ifndef BOOMING_SS_LITERT_ABI_2_2_H_
#define BOOMING_SS_LITERT_ABI_2_2_H_

#include <cstddef>
#include <cstdint>

namespace booming::litert220 {

using Handle = void*;
using Status = int;
using ParamIndex = size_t;
using PayloadDeleter = void (*)(void*);

constexpr Status kStatusOk = 0;
constexpr int kElementTypeFloat32 = 1;
constexpr int kAcceleratorCpu = 1;
constexpr int kAcceleratorGpu = 2;
constexpr int kLockModeRead = 0;
constexpr int kLockModeWrite = 1;
constexpr size_t kTensorMaxRank = 8;

struct Layout {
    unsigned int rank : 7;
    unsigned int hasStrides : 1;
    int32_t dimensions[kTensorMaxRank];
    uint32_t strides[kTensorMaxRank];
};

struct RankedTensorType {
    int elementType;
    Layout layout;
};

static_assert(sizeof(Layout) == 68, "LiteRT 2.2 Layout ABI mismatch");
static_assert(offsetof(Layout, dimensions) == 4, "LiteRT 2.2 dimensions offset mismatch");
static_assert(offsetof(Layout, strides) == 36, "LiteRT 2.2 strides offset mismatch");
static_assert(sizeof(RankedTensorType) == 72, "LiteRT 2.2 ranked tensor ABI mismatch");
static_assert(offsetof(RankedTensorType, layout) == 4, "LiteRT 2.2 layout offset mismatch");

}  // namespace booming::litert220

#endif  // BOOMING_SS_LITERT_ABI_2_2_H_
