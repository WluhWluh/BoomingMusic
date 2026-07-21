package com.mardous.booming.separation.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MdxTensorLayoutConverterTest {
    @Test
    fun `NCHW and NHWC round trip preserves a non symmetric tensor`() {
        val nchw = FloatArray(2 * 3 * 2 * 4) { index -> index.toFloat() + 0.25f }
        val nhwc = FloatArray(nchw.size)
        val roundTrip = FloatArray(nchw.size)

        MdxTensorLayoutConverter.nchwToNhwc(
            source = nchw,
            destination = nhwc,
            batch = 2,
            channels = 3,
            height = 2,
            width = 4,
        )
        MdxTensorLayoutConverter.nhwcToNchw(
            source = nhwc,
            destination = roundTrip,
            batch = 2,
            channels = 3,
            height = 2,
            width = 4,
        )

        assertArrayEquals(nchw, roundTrip, 0f)
    }

    @Test
    fun `layout conversion rejects wrong element counts and in place use`() {
        assertThrows(IllegalArgumentException::class.java) {
            MdxTensorLayoutConverter.nchwToNhwc(
                source = FloatArray(7),
                destination = FloatArray(8),
                batch = 1,
                channels = 2,
                height = 2,
                width = 2,
            )
        }
        val values = FloatArray(8)
        assertThrows(IllegalArgumentException::class.java) {
            MdxTensorLayoutConverter.nhwcToNchw(
                source = values,
                destination = values,
                batch = 1,
                channels = 2,
                height = 2,
                width = 2,
            )
        }
    }
}
