package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtdemucsTrackOlaTest {
    @Test
    fun `planner freezes official stride overlap and centered tail padding`() {
        val trackSamples = HtdemucsPipelineAdapter.WINDOW_SAMPLES + 2_000
        val plans = HtdemucsTrackWindowPlanner.plans(trackSamples)

        assertEquals(257_985, HtdemucsTrackWindowPlanner.STRIDE_SAMPLES)
        assertEquals(85_995, HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES)
        assertEquals(listOf(0, 257_985), plans.map { it.offset })
        assertEquals(HtdemucsPipelineAdapter.WINDOW_SAMPLES, plans[0].actualSamples)
        assertEquals(87_995, plans[1].actualSamples)
        assertEquals((HtdemucsPipelineAdapter.WINDOW_SAMPLES - 87_995) / 2, plans[1].cropLeft)
        assertEquals(0, plans[1].padLeft)
        assertEquals(plans[1].contextStart, plans[1].sourceStart)
        assertEquals(plans[1].cropRight, plans[1].padRight)
    }

    @Test
    fun `streaming normalization matches full track normalization`() {
        val track = floatArrayOf(
            -0.75f, 0.25f, 0.5f, 1f,
            0.5f, -0.25f, 0.75f, -1f,
        )
        val accumulator = HtdemucsGlobalNormalizationAccumulator()
        accumulator.add(floatArrayOf(-0.75f, 0.25f, 0.5f, -0.25f))
        accumulator.add(floatArrayOf(0.5f, 1f, 0.75f, -1f))

        val expected = HtdemucsPipelineAdapter(contract()).use { adapter ->
            adapter.globalNormalization(track)
        }
        val actual = accumulator.finish()

        assertEquals(expected.mean, actual.mean, 1e-7f)
        assertEquals(expected.sampleStandardDeviation, actual.sampleStandardDeviation, 1e-7f)
        assertEquals(expected.divisor, actual.divisor, 1e-7f)
    }

    @Test
    fun `streaming overlap add emits bounded ordered chunks with triangular blending`() {
        val trackSamples = HtdemucsPipelineAdapter.WINDOW_SAMPLES + 2_000
        val plans = HtdemucsTrackWindowPlanner.plans(trackSamples)
        val stems = listOf("vocals", "instrumental")
        val normalization = HtdemucsGlobalNormalization(1f, 2f, 2f)
        val ola = HtdemucsStreamingOverlapAdd(stems, trackSamples, normalization)
        val first = constantWindow(stems, 2f)
        val second = constantWindow(stems, 4f)

        val ready = ola.addWindow(plans[0], first)
        val tail = ola.addWindow(plans[1], second)
        ola.finish()

        assertEquals(0, ready.startFrame)
        assertEquals(HtdemucsTrackWindowPlanner.STRIDE_SAMPLES, ready.frameCount)
        assertEquals(HtdemucsTrackWindowPlanner.STRIDE_SAMPLES, tail.startFrame)
        assertEquals(trackSamples - HtdemucsTrackWindowPlanner.STRIDE_SAMPLES, tail.frameCount)
        assertTrue(ready.planarSamples.all { it == 5f })

        val firstTailWeight = triangleWeight(HtdemucsTrackWindowPlanner.STRIDE_SAMPLES)
        val secondHeadWeight = triangleWeight(0)
        val expectedFirstTail =
            ((2f * firstTailWeight + 4f * secondHeadWeight) /
                (firstTailWeight + secondHeadWeight)) * 2f + 1f
        assertEquals(expectedFirstTail, tail.planarSamples[0], 1e-6f)
        assertTrue(tail.planarSamples.all(Float::isFinite))
    }

    @Test
    fun `immediate chunks match canonical full track overlap add`() {
        val trackSamples = HtdemucsPipelineAdapter.WINDOW_SAMPLES + 2_000
        val plans = HtdemucsTrackWindowPlanner.plans(trackSamples)
        val stems = listOf("vocals")
        val planeCount = stems.size * HtdemucsPipelineAdapter.CHANNEL_COUNT
        val normalization = HtdemucsGlobalNormalization(0.25f, 1.25f, 1.5f)
        val windows = plans.map { plan ->
            HtdemucsWindowStemSet(
                orderedStemIds = stems,
                planarSamples = FloatArray(
                    planeCount * HtdemucsPipelineAdapter.WINDOW_SAMPLES,
                ) { index ->
                    ((index * 17L + plan.index * 31L) % 997L).toFloat() / 997f - 0.5f
                },
                samplesPerStem = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            )
        }
        val ola = HtdemucsStreamingOverlapAdd(
            orderedStemIds = stems,
            trackSamples = trackSamples,
            normalization = normalization,
        )
        val chunks = plans.mapIndexed { index, plan ->
            ola.addWindow(plan, windows[index])
        }
        ola.finish()
        val actual = FloatArray(planeCount * trackSamples)
        chunks.forEach { chunk ->
            repeat(planeCount) { plane ->
                chunk.planarSamples.copyInto(
                    destination = actual,
                    destinationOffset = plane * trackSamples + chunk.startFrame,
                    startIndex = plane * chunk.frameCount,
                    endIndex = (plane + 1) * chunk.frameCount,
                )
            }
        }
        val canonical = HtdemucsStreamingPlan().overlapAdd(
            trackSamples = trackSamples,
            outputPlaneCount = planeCount,
            windowOutputs = plans.mapIndexed { index, plan ->
                HtdemucsWindowOutput(
                    offset = plan.offset,
                    planarSamples = windows[index].planarSamples,
                )
            },
        ).planarSamples
        val expected = FloatArray(canonical.size) { index ->
            canonical[index] * normalization.divisor + normalization.mean
        }

        assertEquals(plans.map { it.offset }, chunks.map { it.startFrame })
        assertEquals(
            plans.map { minOf(HtdemucsTrackWindowPlanner.STRIDE_SAMPLES, trackSamples - it.offset) },
            chunks.map { it.frameCount },
        )
        assertArrayEquals(expected, actual, 1e-6f)
    }

    private fun constantWindow(stems: List<String>, value: Float) = HtdemucsWindowStemSet(
        orderedStemIds = stems,
        planarSamples = FloatArray(
            stems.size * HtdemucsPipelineAdapter.CHANNEL_COUNT *
                HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        ) { value },
        samplesPerStem = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
    )

    private fun triangleWeight(index: Int): Float {
        val midpoint = HtdemucsPipelineAdapter.WINDOW_SAMPLES / 2
        val value = if (index < midpoint) index + 1 else HtdemucsPipelineAdapter.WINDOW_SAMPLES - index
        return value.toFloat() / midpoint
    }

    private fun contract() = SourceSeparationMultiTensorExecutableContractLoader.load(
        requireNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "source-separation/research-contracts/htdemucs-4s-official-base-fp32.json",
            ),
        ).bufferedReader().use { it.readText() },
    ).modelContract
}
