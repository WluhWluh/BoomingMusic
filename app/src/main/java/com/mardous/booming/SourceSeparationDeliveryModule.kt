package com.mardous.booming

import com.mardous.booming.separation.delivery.GitHubModelDeliveryProvider
import com.mardous.booming.separation.delivery.GitHubProductCapabilityPolicy
import com.mardous.booming.separation.delivery.GitHubRuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import com.mardous.booming.separation.delivery.ProductCapabilityPolicy
import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemModelStore
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalogDownloader
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalogRepository
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val sourceSeparationDeliveryModule = module {
    single { GitHubRuntimeDeliveryProvider() } bind RuntimeDeliveryProvider::class
    single { GitHubModelDeliveryProvider() } bind ModelDeliveryProvider::class
    single { GitHubProductCapabilityPolicy } bind ProductCapabilityPolicy::class
    single {
        SourceSeparationReleaseCatalogDownloader(get<ModelDeliveryProvider>())
    }
    single {
        SourceSeparationReleaseCatalogRepository(
            rootDirectory = File(
                androidContext().filesDir,
                "source-separation/multistem-catalog-v1",
            ),
            downloader = get(),
        )
    }
    single {
        SourceSeparationMultiStemReleaseInstaller(
            catalogRepository = get(),
            modelRootDirectory = File(
                androidContext().filesDir,
                SourceSeparationMultiStemModelStore.MODEL_ROOT_DIRECTORY,
            ),
            provider = get(),
        )
    }
}
