package com.mardous.booming.separation.process

import com.mardous.booming.separation.model.MdxRuntimeAbi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationArm32ResidentValidationTest {
    @Test
    fun `arm32 validation permits only armeabi v7a`() {
        MdxRuntimeAbi.values().forEach { abi ->
            assertEquals(
                abi == MdxRuntimeAbi.ArmeabiV7a,
                SourceSeparationArm32ResidentValidation.permits(abi, enabled = true),
            )
        }
        assertFalse(SourceSeparationArm32ResidentValidation.permits(null, enabled = true))
        assertFalse(
            SourceSeparationArm32ResidentValidation.permits(
                MdxRuntimeAbi.ArmeabiV7a,
                enabled = false,
            ),
        )
    }

    @Test
    fun `normal policy remains single use on every ABI`() {
        (MdxRuntimeAbi.values().map { it as MdxRuntimeAbi? } + null)
            .forEach { abi ->
                assertEquals(
                    SourceSeparationProcessSessionOwnership.SingleUse,
                    resolveRemoteSessionOwnership(
                        runtimeAbi = abi,
                        x86ValidationEnabled = false,
                        arm32ResidentValidationEnabled = false,
                    ),
                )
            }
    }

    @Test
    fun `validation policies cannot make another ABI resident`() {
        assertEquals(
            SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit,
            resolveRemoteSessionOwnership(
                runtimeAbi = MdxRuntimeAbi.X86,
                x86ValidationEnabled = true,
                arm32ResidentValidationEnabled = false,
            ),
        )
        assertEquals(
            SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit,
            resolveRemoteSessionOwnership(
                runtimeAbi = MdxRuntimeAbi.ArmeabiV7a,
                x86ValidationEnabled = false,
                arm32ResidentValidationEnabled = true,
            ),
        )
        assertTrue(
            MdxRuntimeAbi.values()
                .filterNot { it == MdxRuntimeAbi.X86 || it == MdxRuntimeAbi.ArmeabiV7a }
                .all { abi ->
                    resolveRemoteSessionOwnership(
                        runtimeAbi = abi,
                        x86ValidationEnabled = true,
                        arm32ResidentValidationEnabled = true,
                    ) == SourceSeparationProcessSessionOwnership.SingleUse
                },
        )
    }
}
