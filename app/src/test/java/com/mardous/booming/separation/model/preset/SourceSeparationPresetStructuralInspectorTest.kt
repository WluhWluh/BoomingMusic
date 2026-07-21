package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationPresetStructuralInspectorTest {
    @Test
    fun `pure x86 bypasses LiteRT structural inspection`() {
        val inspection = AndroidSourceSeparationPresetStructuralInspector.inspect(
            modelFile = File("missing-model.tflite"),
            platform = MdxRuntimePlatform(26, MdxRuntimeAbi.X86),
        )

        assertTrue(inspection is SourceSeparationPresetStructuralInspection.Unavailable)
    }
}
