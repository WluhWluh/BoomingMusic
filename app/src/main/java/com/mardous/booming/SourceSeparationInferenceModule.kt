package com.mardous.booming

import androidx.preference.PreferenceManager
import com.mardous.booming.separation.HtdemucsSourceSeparationEngine
import com.mardous.booming.separation.HtdemucsSourceSeparationRangeExecutor
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationMultiStemCacheAvailabilityProvider
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.process.createSourceSeparationProcessGeneration
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

private val sourceSeparationInferenceModule = module {
    single {
        PreferenceManager.getDefaultSharedPreferences(androidContext())
    }
    single {
        SourceSeparationPresetRepository(
            context = androidContext(),
            preferences = get(),
        )
    }
    single {
        SourceSeparationCacheStore(
            AndroidSourceSeparationCacheRootProvider(androidContext()).resolveRoot(),
        ).also(SourceSeparationCacheStore::recover)
    }
    single {
        SourceSeparationModelAwareCacheRepository(
            store = get(),
            modelAvailability = SourceSeparationMultiStemCacheAvailabilityProvider(
                get<SourceSeparationMultiStemReleaseInstaller>(),
            ),
        )
    }
    single { SourceSeparationCacheRunCoordinator(store = get(), repository = get()) }
    single {
        HtdemucsSourceSeparationEngine(
            coordinator = get(),
            rangeExecutor = HtdemucsSourceSeparationRangeExecutor(androidContext()),
            processGeneration = createSourceSeparationProcessGeneration(),
            ownerPid = android.os.Process.myPid(),
        )
    }
}

val sourceSeparationInferenceModules = listOf(
    sourceSeparationDeliveryModule,
    sourceSeparationInferenceModule,
)
