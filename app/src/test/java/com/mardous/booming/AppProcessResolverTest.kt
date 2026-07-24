package com.mardous.booming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessResolverTest {
    @Test
    fun `classifies the application and source separation processes exactly`() {
        val main = AppProcessResolver.classify(PACKAGE_NAME, PACKAGE_NAME)
        val inference = AppProcessResolver.classify(
            PACKAGE_NAME,
            PACKAGE_NAME + AppProcessResolver.SOURCE_SEPARATION_PROCESS_SUFFIX,
        )

        assertEquals(AppProcessKind.Main, main.kind)
        assertFalse(main.isSourceSeparationProcess)
        assertEquals(AppProcessKind.SourceSeparation, inference.kind)
        assertTrue(inference.isSourceSeparationProcess)
    }

    @Test
    fun `does not mistake another private process for inference`() {
        val identity = AppProcessResolver.classify(
            PACKAGE_NAME,
            "$PACKAGE_NAME:worker",
        )

        assertEquals(AppProcessKind.Other, identity.kind)
        assertFalse(identity.isSourceSeparationProcess)
    }

    private companion object {
        const val PACKAGE_NAME = "com.wluhwluh.booming.sourcesep.debug"
    }
}
