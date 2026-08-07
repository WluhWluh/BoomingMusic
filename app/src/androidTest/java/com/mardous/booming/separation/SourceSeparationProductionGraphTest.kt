package com.mardous.booming.separation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SourceSeparationProductionGraphTest {
    @Test
    fun applicationGraphExposesTheTfliteRuntimeFacade() {
        val koin = GlobalContext.get()

        assertNotNull(koin.get<SourceSeparationRuntimeFacade>())
        assertNotNull(koin.get<SourceSeparationMultiStemProductFacade>())
    }
}
