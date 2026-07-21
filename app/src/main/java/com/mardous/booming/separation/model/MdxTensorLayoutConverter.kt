package com.mardous.booming.separation.model

internal object MdxTensorLayoutConverter {
    fun nchwToNhwc(
        source: FloatArray,
        destination: FloatArray,
        batch: Int,
        channels: Int,
        height: Int,
        width: Int,
    ) {
        requireCompatibleArrays(source, destination, batch, channels, height, width)
        for (batchIndex in 0 until batch) {
            for (heightIndex in 0 until height) {
                for (widthIndex in 0 until width) {
                    for (channelIndex in 0 until channels) {
                        val nchwIndex = (
                            (batchIndex * channels + channelIndex) * height + heightIndex
                            ) * width + widthIndex
                        val nhwcIndex = (
                            (batchIndex * height + heightIndex) * width + widthIndex
                            ) * channels + channelIndex
                        destination[nhwcIndex] = source[nchwIndex]
                    }
                }
            }
        }
    }

    fun nhwcToNchw(
        source: FloatArray,
        destination: FloatArray,
        batch: Int,
        channels: Int,
        height: Int,
        width: Int,
    ) {
        requireCompatibleArrays(source, destination, batch, channels, height, width)
        for (batchIndex in 0 until batch) {
            for (heightIndex in 0 until height) {
                for (widthIndex in 0 until width) {
                    for (channelIndex in 0 until channels) {
                        val nhwcIndex = (
                            (batchIndex * height + heightIndex) * width + widthIndex
                            ) * channels + channelIndex
                        val nchwIndex = (
                            (batchIndex * channels + channelIndex) * height + heightIndex
                            ) * width + widthIndex
                        destination[nchwIndex] = source[nhwcIndex]
                    }
                }
            }
        }
    }

    private fun requireCompatibleArrays(
        source: FloatArray,
        destination: FloatArray,
        batch: Int,
        channels: Int,
        height: Int,
        width: Int,
    ) {
        require(batch > 0 && channels > 0 && height > 0 && width > 0) {
            "Tensor dimensions must be positive."
        }
        val elementCount = Math.multiplyExact(
            Math.multiplyExact(batch, channels),
            Math.multiplyExact(height, width),
        )
        require(source.size == elementCount) {
            "Expected $elementCount source elements, got ${source.size}."
        }
        require(destination.size == elementCount) {
            "Expected $elementCount destination elements, got ${destination.size}."
        }
        require(source !== destination) { "In-place tensor layout conversion is not supported." }
    }
}
