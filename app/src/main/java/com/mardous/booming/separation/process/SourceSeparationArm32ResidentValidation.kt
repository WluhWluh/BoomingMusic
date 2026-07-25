package com.mardous.booming.separation.process

import com.mardous.booming.BuildConfig
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride

internal object SourceSeparationResidentProcessValidation {
    val buildEnabled: Boolean
        get() = MdxX86ProcessValidationOverride.buildEnabled ||
            SourceSeparationArm32ResidentValidation.buildEnabled
}

/** Compile-time-only session policy for the Phase 5 arm32 resident experiment. */
internal object SourceSeparationArm32ResidentValidation {
    val buildEnabled: Boolean
        get() = BuildConfig.ARM32_RESIDENT_PROCESS_VALIDATION

    fun permits(
        runtimeAbi: MdxRuntimeAbi?,
        enabled: Boolean = buildEnabled,
    ): Boolean = enabled && runtimeAbi == MdxRuntimeAbi.ArmeabiV7a
}

internal fun resolveRemoteSessionOwnership(
    runtimeAbi: MdxRuntimeAbi?,
    x86ValidationEnabled: Boolean,
    arm32ResidentValidationEnabled: Boolean,
): SourceSeparationProcessSessionOwnership =
    if ((x86ValidationEnabled && runtimeAbi == MdxRuntimeAbi.X86) ||
        SourceSeparationArm32ResidentValidation.permits(
            runtimeAbi = runtimeAbi,
            enabled = arm32ResidentValidationEnabled,
        )
    ) {
        SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit
    } else {
        SourceSeparationProcessSessionOwnership.SingleUse
    }
