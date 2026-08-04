package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.StemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceSeparationWindowResultTest {

    @Test
    fun `two four and six stem windows preserve declared order`() {
        for (stemCount in listOf(2, 4, 6)) {
            val result = window(stemCount)

            assertEquals(stemCount, result.stems.size)
            assertEquals(
                (0 until stemCount).map { StemId("stem_$it") },
                result.stems.map { it.stemId },
            )
        }
    }

    @Test
    fun `window rejects missing order duplicate IDs and unequal PCM geometry`() {
        val valid = window(4)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(stems = valid.stems.mapIndexed { index, stem ->
                if (index == 3) stem.copy(order = 2) else stem
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(stems = valid.stems.mapIndexed { index, stem ->
                if (index == 3) stem.copy(stemId = valid.stems.first().stemId) else stem
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(stems = valid.stems.mapIndexed { index, stem ->
                if (index == 3) stem.copy(pcm16 = ByteArray(2)) else stem
            })
        }
    }

    private fun window(stemCount: Int): SourceSeparationWindowResult =
        SourceSeparationWindowResult(
            frameCount = 8,
            channelCount = 2,
            stems = (0 until stemCount).map { order ->
                SourceSeparationStemChunk(
                    stemId = StemId("stem_$order"),
                    order = order,
                    pcm16 = ByteArray(32) { (it + order).toByte() },
                )
            },
        )
}
