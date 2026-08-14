/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming

import android.os.Build
import androidx.preference.PreferenceManager
import androidx.room.Room
import com.mardous.booming.coil.CustomArtistImageManager
import com.mardous.booming.coil.CustomPlaylistImageManager
import com.mardous.booming.core.BoomingDatabase
import com.mardous.booming.core.audio.AudioOutputObserver
import com.mardous.booming.data.local.EditTarget
import com.mardous.booming.data.local.MediaStoreWriter
import com.mardous.booming.data.local.repository.AlbumRepository
import com.mardous.booming.data.local.repository.ArtistRepository
import com.mardous.booming.data.local.repository.GenreRepository
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.local.repository.NetworkRepository
import com.mardous.booming.data.local.repository.NetworkRepositoryImpl
import com.mardous.booming.data.local.repository.PlaylistRepository
import com.mardous.booming.data.local.repository.RealAlbumRepository
import com.mardous.booming.data.local.repository.RealArtistRepository
import com.mardous.booming.data.local.repository.RealGenreRepository
import com.mardous.booming.data.local.repository.RealLyricsRepository
import com.mardous.booming.data.local.repository.RealPlaylistRepository
import com.mardous.booming.data.local.repository.RealRepository
import com.mardous.booming.data.local.repository.RealSearchRepository
import com.mardous.booming.data.local.repository.RealSmartRepository
import com.mardous.booming.data.local.repository.RealSongRepository
import com.mardous.booming.data.local.repository.RealSpecialRepository
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.local.repository.SearchRepository
import com.mardous.booming.data.local.repository.SmartRepository
import com.mardous.booming.data.local.repository.SongRepository
import com.mardous.booming.data.local.repository.SpecialRepository
import com.mardous.booming.data.model.Genre
import com.mardous.booming.data.remote.deezer.DeezerService
import com.mardous.booming.data.remote.github.GitHubService
import com.mardous.booming.data.remote.jsonHttpClient
import com.mardous.booming.data.remote.lastfm.LastFmService
import com.mardous.booming.data.remote.listenbrainz.ListenBrainzService
import com.mardous.booming.data.remote.lyrics.LyricsDownloadService
import com.mardous.booming.data.remote.provideOkHttp
import com.mardous.booming.playback.SleepTimer
import com.mardous.booming.playback.equalizer.EqualizerManager
import com.mardous.booming.playback.processor.BalanceAudioProcessor
import com.mardous.booming.playback.processor.ReplayGainAudioProcessor
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.separation.AndroidSourceSeparationModelAwarePreflightResolver
import com.mardous.booming.separation.AndroidSourceSeparationRuntimeCompatibilityResolver
import com.mardous.booming.separation.DefaultSourceSeparationRuntimeFacade
import com.mardous.booming.separation.HtdemucsSourceSeparationEngine
import com.mardous.booming.separation.HtdemucsSourceSeparationRangeExecutor
import com.mardous.booming.separation.SourceSeparationModelAwareEngine
import com.mardous.booming.separation.SourceSeparationMultiStemProductFacade
import com.mardous.booming.separation.SourceSeparationMultiStemExecutionHost
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheEntryLeaseRegistry
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationMultiStemCacheAvailabilityProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationPresetCacheAvailabilityProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationProductCacheAvailabilityProvider
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackResolver
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.SourceSeparationExecutionSelectionResolver
import com.mardous.booming.separation.SourceSeparationCacheModelActivator
import com.mardous.booming.separation.SourceSeparationSelectionGenerationStore
import com.mardous.booming.separation.SourceSeparationModelMixSettingsStore
import com.mardous.booming.separation.SourceSeparationModelAwarePreflightResolver
import com.mardous.booming.separation.SourceSeparationMultiStemContractMemo
import com.mardous.booming.separation.SourceSeparationMultiStemRuntimeResolver
import com.mardous.booming.separation.SourceSeparationSourcePreflightMemo
import com.mardous.booming.separation.cache.v2.resolveTrustedActiveCacheModelResolution
import com.mardous.booming.separation.model.preset.AndroidSourceSeparationPresetStructuralInspector
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetImportCoordinator
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.process.createSourceSeparationProcessGeneration
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationMultiStemExecutionHost
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeCatalogLoader
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeLayout
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeProcessController
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeCatalogLoader
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipHandoff
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationIndependentRunRecovery
import com.mardous.booming.separation.process.ipc.SourceSeparationIndependentRunRecoveryClient
import com.mardous.booming.separation.process.ipc.SourceSeparationMultiStemIndependentRunRecovery
import com.mardous.booming.separation.process.ipc.SourceSeparationMultiStemIndependentRunRecoveryClient
import com.mardous.booming.separation.setup.LocalSeparationReadinessEvaluator
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupExecutor
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupModelInstaller
import com.mardous.booming.ui.screen.equalizer.EqualizerViewModel
import com.mardous.booming.ui.screen.info.InfoViewModel
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.library.albums.AlbumDetailViewModel
import com.mardous.booming.ui.screen.library.artists.ArtistDetailViewModel
import com.mardous.booming.ui.screen.library.folders.FolderDetailViewModel
import com.mardous.booming.ui.screen.library.genres.GenreDetailViewModel
import com.mardous.booming.ui.screen.library.playlists.PlaylistDetailViewModel
import com.mardous.booming.ui.screen.library.search.SearchViewModel
import com.mardous.booming.ui.screen.library.years.YearDetailViewModel
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationModelAwareCacheManagementViewModel
import com.mardous.booming.ui.screen.player.SourceSeparationPresetManagementViewModel
import com.mardous.booming.ui.screen.player.SourceSeparationQuickSetupViewModel
import com.mardous.booming.ui.screen.player.SourceSeparationRuntimeManagementViewModel
import com.mardous.booming.ui.screen.sleeptimer.SleepTimerViewModel
import com.mardous.booming.ui.screen.tageditor.TagEditorViewModel
import com.mardous.booming.ui.screen.update.UpdateViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val networkModule = module {
    factory {
        jsonHttpClient(okHttpClient = get())
    }
    factory {
        provideOkHttp(context = get())
    }
    single {
        GitHubService(context = androidContext(), client = get())
    }
    single {
        DeezerService(client = get())
    }
    single {
        LastFmService(client = get())
    }
    single {
        ListenBrainzService(client = get())
    }
    single {
        LyricsDownloadService(client = get())
    }
}

private val mainModule = module {
    single {
        androidContext().contentResolver
    }
    single {
        PreferenceManager.getDefaultSharedPreferences(androidContext())
    }
    single { SourceSeparationSelectionGenerationStore(get()) }
    single {
        SourceSeparationRuntimeCatalogLoader.load(androidContext())
    }
    single {
        SourceSeparationRuntimeStore(
            root = SourceSeparationRuntimeLayout.runtimeRoot(androidContext()),
            catalog = get(),
            provider = get(),
            androidApi = Build.VERSION.SDK_INT,
        )
    }
    single {
        SourceSeparationGpuRuntimeCatalogLoader.load(androidContext())
    }
    single {
        SourceSeparationGpuRuntimeStore(
            root = SourceSeparationRuntimeLayout.runtimeRoot(androidContext()),
            catalog = get(),
            provider = get(),
            androidApi = Build.VERSION.SDK_INT,
        )
    }
    single {
        SleepTimer(context = androidContext())
    }
    single {
        BalanceAudioProcessor()
    }
    single {
        ReplayGainAudioProcessor()
    }
    single {
        SourceSeparationMixAudioProcessor()
    }
    single {
        EqualizerManager(
            context = androidContext(),
            balanceProcessor = get(),
            replayGainProcessor = get(),
            audioOutputObserver = get()
        )
    }
    single {
        MediaStoreWriter(context = androidContext(), contentResolver = get())
    }
    single {
        CustomArtistImageManager(context = androidContext())
    }
    single {
        CustomPlaylistImageManager(context = androidContext())
    }
    single {
        AudioOutputObserver(context = androidContext())
    }
    single {
        SourceSeparationPresetRepository(
            context = androidContext(),
            preferences = get(),
        )
    }
    single {
        SourceSeparationPresetDownloader(repository = get())
    }
    single { SourceSeparationMultiStemPlaybackSelectionStore(get(), get()) }
    single {
        SourceSeparationQuickSetupModelInstaller(
            repository = get(),
            provider = get(),
        )
    }
    single {
        LocalSeparationReadinessEvaluator(
            runtimeStore = get(),
            gpuRuntimeStore = get(),
            presetRepository = get(),
            multiStemSelectionStore = get(),
            multiStemInstaller = get(),
            preferences = get(),
        )
    }
    single {
        SourceSeparationQuickSetupExecutor(
            runtimeStore = get(),
            gpuRuntimeStore = get(),
            presetRepository = get(),
            multiStemSelectionStore = get(),
            multiStemInstaller = get(),
            modelInstaller = get(),
            preferences = get(),
            readinessEvaluator = { get<LocalSeparationReadinessEvaluator>().evaluate() },
            processController = get(),
        )
    }
    single {
        SourceSeparationPresetImportCoordinator(
            repository = get(),
            stagingDirectory = java.io.File(
                androidContext().cacheDir,
                SourceSeparationPresetImportCoordinator.STAGING_DIRECTORY,
            ),
            structuralInspector = AndroidSourceSeparationPresetStructuralInspector,
        )
    }
    single {
        SourceSeparationCacheStore(
            AndroidSourceSeparationCacheRootProvider(androidContext()).resolveRoot(),
        ).also(SourceSeparationCacheStore::recover)
    }
    single {
        SourceSeparationIndependentRunRecoveryClient(
            context = androidContext(),
            store = get(),
        )
    } bind SourceSeparationIndependentRunRecovery::class
    single {
        SourceSeparationMultiStemIndependentRunRecoveryClient(
            context = androidContext(),
            store = get(),
        )
    } bind SourceSeparationMultiStemIndependentRunRecovery::class
    single { SourceSeparationCacheEntryLeaseRegistry() }
    single {
        SourceSeparationProductCacheAvailabilityProvider(
            preset = SourceSeparationPresetCacheAvailabilityProvider(get()),
            multiStem = SourceSeparationMultiStemCacheAvailabilityProvider(
                get<SourceSeparationMultiStemReleaseInstaller>(),
            ),
        )
    }
    single {
        SourceSeparationExecutionSelectionResolver(
            presetRepository = get(),
            multiStemSelectionStore = get(),
            multiStemInstaller = get(),
        )
    }
    single { SourceSeparationModelMixSettingsStore(get()) }
    single<SourceSeparationModelAwarePreflightResolver> {
        AndroidSourceSeparationModelAwarePreflightResolver(androidContext())
    }
    single { SourceSeparationSourcePreflightMemo() }
    single { SourceSeparationMultiStemContractMemo() }
    single<SourceSeparationMultiStemRuntimeResolver> {
        SourceSeparationMultiStemPlaybackResolver(
            selection = get(),
            installer = get(),
            preflightResolver = get(),
            sourcePreflightMemo = get(),
            contractMemo = get(),
        )
    }
    single {
        SourceSeparationModelAwareCacheRepository(
            store = get(),
            leases = get(),
            modelAvailability = get<SourceSeparationProductCacheAvailabilityProvider>(),
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
    single<SourceSeparationMultiStemExecutionHost> {
        get<BoundRemoteSourceSeparationMultiStemExecutionHost>()
    }
    single {
        BoundRemoteSourceSeparationMultiStemExecutionHost(androidContext())
    }
    single {
        SourceSeparationMultiStemProductFacade(
            installer = get<SourceSeparationMultiStemReleaseInstaller>(),
            executionHost = get(),
            preflightResolver = get(),
            sourcePreflightMemo = get(),
            contractMemo = get(),
        )
    }
    single { SourceSeparationCacheFlacPromoter(store = get(), repository = get()) }
    single {
        SourceSeparationProcessingOwnershipHandoff(
            android.os.SystemClock::elapsedRealtimeNanos,
        )
    }
    single {
        BoundRemoteSourceSeparationExecutionHost(androidContext())
    }
    single {
        SourceSeparationRuntimeProcessController(
            executionHost = get(),
            runtimeStore = get(),
        )
    }
    single {
        SourceSeparationModelAwareEngine.createBoundRemotePrototype(
            context = androidContext(),
            presetRepository = get(),
            coordinator = get(),
            executionHost = get<BoundRemoteSourceSeparationExecutionHost>(),
        )
    }
    single {
        val presetRepository = get<SourceSeparationPresetRepository>()
        DefaultSourceSeparationRuntimeFacade(
            activeModelResolver = presetRepository::resolveTrustedActiveCacheModelResolution,
            multiStemPlaybackResolver = get(),
            multiStemExecutor = get<SourceSeparationMultiStemProductFacade>(),
            compatibilityResolver = AndroidSourceSeparationRuntimeCompatibilityResolver,
            preflightResolver = get(),
            sourcePreflightMemo = get(),
            engine = get(),
            manualFullSongEngineFactory = {
                SourceSeparationModelAwareEngine.createIndependentForegroundPrototype(
                    context = androidContext(),
                    presetRepository = presetRepository,
                    coordinator = get(),
                    processingOwnershipLease =
                        get<SourceSeparationProcessingOwnershipHandoff>().createLease(),
                )
            },
            cacheRepository = get(),
            runCoordinator = get(),
            flacPromoter = get(),
        )
    } bind SourceSeparationRuntimeFacade::class
}

private val roomModule = module {
    single {
        Room.databaseBuilder(androidContext(), BoomingDatabase::class.java, "music_database.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    factory {
        get<BoomingDatabase>().playlistDao()
    }

    factory {
        get<BoomingDatabase>().playCountDao()
    }

    factory {
        get<BoomingDatabase>().historyDao()
    }

    factory {
        get<BoomingDatabase>().queueDao()
    }

    factory {
        get<BoomingDatabase>().inclExclDao()
    }

    factory {
        get<BoomingDatabase>().lyricsDao()
    }
}

private val dataModule = module {
    single {
        RealRepository(
            context = androidContext(),
            songRepository = get(),
            albumRepository = get(),
            artistRepository = get(),
            genreRepository = get(),
            smartRepository = get(),
            specialRepository = get(),
            playlistRepository = get(),
            searchRepository = get(),
            networkRepository = get()
        )
    } bind Repository::class

    single {
        RealSongRepository(context = get(), inclExclDao = get())
    } bind SongRepository::class

    single {
        RealAlbumRepository(songRepository = get())
    } bind AlbumRepository::class

    single {
        RealArtistRepository(songRepository = get(), albumRepository = get())
    } bind ArtistRepository::class

    single {
        RealPlaylistRepository(
            context = androidContext(),
            songRepository = get(),
            playlistDao = get()
        )
    } bind PlaylistRepository::class

    single {
        RealGenreRepository(contentResolver = get(), songRepository = get())
    } bind GenreRepository::class

    single {
        RealSearchRepository(
            albumRepository = get(),
            songRepository = get(),
            artistRepository = get(),
            playlistRepository = get(),
            genreRepository = get(),
            specialRepository = get()
        )
    } bind SearchRepository::class

    single {
        RealSmartRepository(
            context = androidContext(),
            songRepository = get(),
            albumRepository = get(),
            artistRepository = get(),
            historyDao = get(),
            playCountDao = get()
        )
    } bind SmartRepository::class

    single {
        RealSpecialRepository(songRepository = get())
    } bind SpecialRepository::class

    single {
        RealLyricsRepository(
            context = androidContext(),
            preferences = get(),
            lyricsDownloadService = get(),
            lyricsDao = get()
        )
    } bind LyricsRepository::class

    single {
        NetworkRepositoryImpl(
            context = androidContext(),
            preferences = get(),
            lastFmService = get(),
            listenBrainzService = get(),
            deezerService = get()
        )
    } bind NetworkRepository::class
}

private val viewModule = module {
    single {
        val presetRepository = get<SourceSeparationPresetRepository>()
        SourceSeparationForegroundWorkerCoordinator(
            context = androidContext(),
            preferences = get(),
            sourceSeparationRuntime = get(),
            sourceSeparationMixSettings = get(),
            independentRunRecovery = get(),
            multiStemIndependentRunRecovery = get(),
            multiStemSelectionFlow = get<SourceSeparationMultiStemPlaybackSelectionStore>()
                .selectionFlow,
            activeSelectionFlow = presetRepository.activeSelectionFlow,
            executionSelectionFlow =
                get<SourceSeparationExecutionSelectionResolver>().selectionFlow,
        )
    }

    single {
        val worker = get<SourceSeparationForegroundWorkerCoordinator>()
        SourceSeparationCacheModelActivator(
            presetRepository = get(),
            multiStemInstaller = get(),
            multiStemSelectionStore = get(),
            executionSelectionResolver = get(),
            pauseForModelSupersession = worker::pauseForActiveModelSupersession,
        )
    }

    viewModel {
        LibraryViewModel(repository = get(), inclExclDao = get(), customPlaylistImageManager = get())
    }

    viewModel {
        PlayerViewModel(
            appContext = androidContext(),
            preferences = get(),
            repository = get(),
            sourceSeparationRuntime = get(),
            sourceSeparationForegroundWorkerCoordinator = get(),
            sourceSeparationMultiStemSelectionStore = get(),
            sourceSeparationMultiStemInstaller = get(),
            sourceSeparationMixSettings = get(),
            localSeparationPathReadiness = {
                get<LocalSeparationReadinessEvaluator>()
                    .evaluate(verifyPayloadHashes = false)
                    .isRunnable
            },
        )
    }

    viewModel {
        val worker = get<SourceSeparationForegroundWorkerCoordinator>()
        SourceSeparationPresetManagementViewModel(
            contentResolver = get(),
            repository = get(),
            downloader = get(),
            importCoordinator = get(),
            modelArtifactInUse = worker::isModelArtifactInUse,
            multiStemInstaller = get(),
            multiStemSelectionStore = get(),
            pauseForModelSupersession = worker::pauseForActiveModelSupersession,
        )
    }

    viewModel {
        SourceSeparationModelAwareCacheManagementViewModel(
            runtime = get(),
            modelActivator = get(),
        )
    }

    viewModel {
        SourceSeparationRuntimeManagementViewModel(
            store = get(),
            gpuStore = get(),
            processController = get(),
            preferences = get(),
        )
    }

    viewModel {
        SourceSeparationQuickSetupViewModel(
            readinessEvaluator = get(),
            executor = get(),
        )
    }

    viewModel {
        EqualizerViewModel(
            contentResolver = get(),
            equalizerManager = get(),
            audioOutputObserver = get(),
            mediaStoreWriter = get()
        )
    }

    viewModel {
        SleepTimerViewModel(
            application = androidApplication(),
            sleepTimer = get()
        )
    }

    viewModel { (albumId: Long) ->
        AlbumDetailViewModel(
            application = androidApplication(),
            repository = get(),
            albumId = albumId
        )
    }

    viewModel { (artistId: Long, artistName: String?) ->
        ArtistDetailViewModel(
            application = androidApplication(),
            repository = get(),
            artistId = artistId,
            artistName = artistName
        )
    }

    viewModel { (playlistId: Long) ->
        PlaylistDetailViewModel(playlistRepository = get(), playlistId = playlistId)
    }

    viewModel { (genre: Genre) ->
        GenreDetailViewModel(repository = get(), genre = genre)
    }

    viewModel { (year: Int) ->
        YearDetailViewModel(repository = get(), year = year)
    }

    viewModel { (path: String) ->
        FolderDetailViewModel(repository = get(), folderPath = path)
    }

    viewModel {
        SearchViewModel(repository = get())
    }

    viewModel { (target: EditTarget) ->
        TagEditorViewModel(
            repository = get(),
            customArtistImageManager = get(),
            target = target
        )
    }

    viewModel {
        LyricsViewModel(application = androidApplication(), preferences = get(), repository = get())
    }

    viewModel {
        InfoViewModel(repository = get())
    }

    viewModel {
        UpdateViewModel(updateService = get())
    }
}

val appModules = listOf(
    networkModule,
    sourceSeparationDeliveryModule,
    mainModule,
    roomModule,
    dataModule,
    viewModule,
)
