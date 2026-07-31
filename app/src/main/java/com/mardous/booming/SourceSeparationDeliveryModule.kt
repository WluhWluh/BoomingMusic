package com.mardous.booming

import com.mardous.booming.separation.delivery.GitHubModelDeliveryProvider
import com.mardous.booming.separation.delivery.GitHubProductCapabilityPolicy
import com.mardous.booming.separation.delivery.GitHubRuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import com.mardous.booming.separation.delivery.ProductCapabilityPolicy
import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import org.koin.dsl.bind
import org.koin.dsl.module

val sourceSeparationDeliveryModule = module {
    single { GitHubRuntimeDeliveryProvider() } bind RuntimeDeliveryProvider::class
    single { GitHubModelDeliveryProvider() } bind ModelDeliveryProvider::class
    single { GitHubProductCapabilityPolicy } bind ProductCapabilityPolicy::class
}
