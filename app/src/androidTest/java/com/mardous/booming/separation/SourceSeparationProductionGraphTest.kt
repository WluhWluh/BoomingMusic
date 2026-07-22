package com.mardous.booming.separation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mardous.booming.separation.model.SourceSeparationModelRepository
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.error.NoDefinitionFoundException

@RunWith(AndroidJUnit4::class)
class SourceSeparationProductionGraphTest {
    @Test
    fun applicationGraphExposesFacadeWithoutLegacyRuntimeDefinitions() {
        val koin = GlobalContext.get()

        assertNotNull(koin.get<SourceSeparationRuntimeFacade>())
        assertThrows(NoDefinitionFoundException::class.java) {
            koin.get<SourceSeparationEngine>()
        }
        assertThrows(NoDefinitionFoundException::class.java) {
            koin.get<SourceSeparationModelRepository>()
        }
    }
}
