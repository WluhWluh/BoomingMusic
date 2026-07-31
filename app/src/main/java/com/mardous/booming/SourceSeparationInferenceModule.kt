package com.mardous.booming

import androidx.preference.PreferenceManager
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
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
}

val sourceSeparationInferenceModules = listOf(
    sourceSeparationDeliveryModule,
    sourceSeparationInferenceModule,
)
