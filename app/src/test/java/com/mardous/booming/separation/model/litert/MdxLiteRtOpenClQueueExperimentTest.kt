package com.mardous.booming.separation.model.litert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MdxLiteRtOpenClQueueExperimentTest {
    @Test
    fun defaultBuildLeavesNativeExperimentDisabled() {
        assertFalse(MdxLiteRtOpenClQueueExperiment.isEnabled())
        assertNull(MdxLiteRtOpenClQueueExperiment.kernelBatchSize())

        MdxLiteRtOpenClQueueExperiment.reset()
        MdxLiteRtOpenClQueueExperiment.beginInference()
        MdxLiteRtOpenClQueueExperiment.endInference()
    }
}
