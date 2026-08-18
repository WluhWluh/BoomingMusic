package com.mardous.booming.separation.model.litert

import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.BuildConfig
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.AndroidSourceSeparationModelAwarePreflightResolver
import com.mardous.booming.separation.SourceSeparationModelAwareEngineResult
import com.mardous.booming.separation.SourceSeparationModelAwareSongInput
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSong
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.resolveTrustedActiveCacheModel
import com.mardous.booming.separation.delivery.ProductCapabilityPolicy
import com.mardous.booming.separation.delivery.SourceSeparationProductCapability
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.process.SourceSeparationExecutionHostMode
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeCatalog
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get

@RunWith(AndroidJUnit4::class)
class MdxLiteRtX86GithubProductDeviceTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun downloadsAndExecutesStandardGithubProductPath() {
        stage("test-start")
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_RUN_GATE) == "true")
        assumeTrue(currentProcessAbi() == MdxRuntimeAbi.X86.androidName)
        assertFalse(
            "The product gate must not depend on the x86 resident-session experiment.",
            BuildConfig.X86_PROCESS_VALIDATION,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val policy = get<ProductCapabilityPolicy>(ProductCapabilityPolicy::class.java)
        assertEquals("github", policy.channelId)
        assertTrue(policy.supports(SourceSeparationProductCapability.CpuExecution))
        assertTrue(policy.supportsCpuRuntimeAbi(MdxRuntimeAbi.X86.androidName))

        val runtimeCatalog = get<SourceSeparationRuntimeCatalog>(
            SourceSeparationRuntimeCatalog::class.java,
        )
        val runtimeEntry = requireNotNull(runtimeCatalog.entryForAbi(MdxRuntimeAbi.X86.androidName))
        stage("runtime-download-start:${runtimeEntry.componentId}")
        val runtimeStore = get<SourceSeparationRuntimeStore>(SourceSeparationRuntimeStore::class.java)
        val installedRuntime = runtimeStore.install(runtimeEntry.componentId)
        stage("runtime-download-complete:${installedRuntime.state}")
        assertEquals(SourceSeparationRuntimeState.Installed, installedRuntime.state)
        assertEquals(
            EXPECTED_RUNTIME_ARTIFACT,
            installedRuntime.installation?.identity?.runtimeArtifactVersion,
        )

        val repository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        val installedModel = get<SourceSeparationPresetDownloader>(
            SourceSeparationPresetDownloader::class.java,
        ).download(MODEL_ID) { progress ->
            if (progress.downloadedBytes == 0L ||
                progress.downloadedBytes == progress.totalBytes
            ) {
                stage(
                    "model-download:${progress.downloadedBytes}/${progress.totalBytes}",
                )
            }
        }
        stage("model-download-complete:${installedModel.byteSize}")
        repository.activate(
            sha256 = installedModel.sha256,
            platform = MdxRuntimePlatform(
                androidApi = Build.VERSION.SDK_INT,
                runtimeAbi = MdxRuntimeAbi.X86,
            ),
            scope = SourceSeparationPresetSelectionScope.User,
            experimentalConfirmed = true,
        )
        val model = requireNotNull(repository.resolveTrustedActiveCacheModel())
        assertEquals(MODEL_ID, model.contract.modelId)
        assertEquals(installedModel.sha256, model.artifact.sha256)

        val sourceFile = File(context.noBackupFilesDir, SOURCE_FILE_NAME)
        val runtimeFacade = get<SourceSeparationRuntimeFacade>(
            SourceSeparationRuntimeFacade::class.java,
        )
        var runtimeSong: SourceSeparationRuntimeSong? = null
        try {
            writeFixtureWav(sourceFile)
            val sourceUri = Uri.fromFile(sourceFile).toString()
            val song = fixtureSong(sourceFile)
            val input = SourceSeparationModelAwareSongInput(
                sourceUri = sourceUri,
                displayName = sourceFile.name,
                song = SourceSeparationCacheSongLocator(
                    songId = song.id,
                    mediaUri = sourceUri,
                    filePath = sourceFile.absolutePath,
                    title = song.title,
                    artist = song.artistName,
                    album = song.albumName,
                ),
                sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                    fileSize = sourceFile.length(),
                    rawDateModified = sourceFile.lastModified() / 1_000L,
                    durationMs = SOURCE_DURATION_MS,
                ),
            )
            val preflight = AndroidSourceSeparationModelAwarePreflightResolver(context)
                .resolve(sourceUri) { false }
            runtimeSong = SourceSeparationRuntimeSong(
                song = song,
                model = model,
                input = input,
                preflight = preflight,
            )
            stage("separation-start")
            runtimeFacade.entries()
                .filter { it.cacheKey == runtimeSong.cacheKey }
                .forEach { runtimeFacade.delete(it.cacheKey) }

            val rawResult = runtimeFacade.separate(
                song = runtimeSong,
                tryGpu = false,
                windowDecodeEnabled = true,
            )
            stage("separation-returned:${rawResult::class.java.simpleName}")
            val result = rawResult as? SourceSeparationModelAwareEngineResult.Completed
                ?: error("The x86 product path did not complete a new cache: $rawResult")

            assertEquals(SourceSeparationCacheManifestState.Completed, result.manifest.state)
            assertEquals(MODEL_ID, result.manifest.identity.modelId)
            assertEquals(2, result.manifest.output?.stems?.size)
            assertEquals(SourceSeparationExecutionHostMode.BoundRemote, result.hostDiagnostics.mode)
            assertEquals(MdxInferenceBackend.LiteRtCpu.name, result.hostDiagnostics.backend)
            assertTrue(result.manifest.runtimeRecords.isNotEmpty())
            assertTrue(
                result.manifest.runtimeRecords.all {
                    it.backend == MdxInferenceBackend.LiteRtCpu.name
                },
            )
            assertTrue(
                runtimeFacade.cacheStatus(runtimeSong) is
                    SourceSeparationModelAwareCacheStatus.Completed,
            )
            runtimeFacade.openCompletedCache(runtimeSong.cacheKey).use { playback ->
                assertNotNull(playback)
                assertEquals(2, playback?.stemFiles?.size)
                assertTrue(playback?.stemFiles?.all(File::isFile) == true)
            }
            stage("cache-open-complete")
            Log.i(
                TAG,
                "x86 GitHub product gate passed through the bound remote process; " +
                    "ci=${BuildConfig.IS_CI_BUILD}, model=${model.contract.modelId}",
            )
        } finally {
            runtimeSong?.let { song ->
                runtimeFacade.entries()
                    .filter { it.cacheKey == song.cacheKey }
                    .forEach { runtimeFacade.delete(it.cacheKey) }
            }
            sourceFile.delete()
        }
    }

    private fun stage(name: String) {
        Log.i(TAG, "stage=$name elapsedMs=${SystemClock.elapsedRealtime() - testStartMs}")
    }

    private val testStartMs = SystemClock.elapsedRealtime()

    private fun writeFixtureWav(file: File) {
        require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val frameCount = SAMPLE_RATE * SOURCE_DURATION_MS.toInt() / 1_000
        val dataSize = frameCount * CHANNEL_COUNT * PCM16_BYTES
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(36 + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(CHANNEL_COUNT)
            output.writeLittleEndianInt(SAMPLE_RATE)
            output.writeLittleEndianInt(SAMPLE_RATE * CHANNEL_COUNT * PCM16_BYTES)
            output.writeLittleEndianShort(CHANNEL_COUNT * PCM16_BYTES)
            output.writeLittleEndianShort(PCM16_BYTES * 8)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(dataSize)
            repeat(frameCount) { frame ->
                repeat(CHANNEL_COUNT) { channel ->
                    val sample = (
                        0.18 * sin(2.0 * PI * (220 + channel * 37) * frame / SAMPLE_RATE) +
                            0.03 * sin(frame * 0.013)
                        )
                    output.writeLittleEndianShort(
                        (sample * Short.MAX_VALUE).roundToInt().coerceIn(
                            Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt(),
                        ),
                    )
                }
            }
        }
    }

    private fun BufferedOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private fun BufferedOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun fixtureSong(sourceFile: File) = Song(
        id = SONG_ID,
        data = sourceFile.absolutePath,
        title = "x86 GitHub product gate",
        trackNumber = 1,
        year = 2026,
        size = sourceFile.length(),
        duration = SOURCE_DURATION_MS,
        dateAdded = System.currentTimeMillis() / 1_000L,
        rawDateModified = sourceFile.lastModified() / 1_000L,
        albumId = -1L,
        albumName = "Booming SS validation",
        artistId = -1L,
        artistName = "Booming SS",
        albumArtistName = "Booming SS",
        genreName = null,
    )

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return requireNotNull(abis.firstOrNull())
    }

    private companion object {
        const val TAG = "BssX86ProductGate"
        const val ARG_RUN_GATE = "runX86GithubProductGate"
        const val MODEL_ID = "uvr_mdxnet_3_9662"
        const val EXPECTED_RUNTIME_ARTIFACT = "2.2.0-bss.2"
        const val SOURCE_FILE_NAME = "x86-github-product-gate.wav"
        const val SOURCE_DURATION_MS = 2_000L
        const val SONG_ID = 86_220L
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2
        const val PCM16_BYTES = 2
        const val TEST_TIMEOUT_MS = 30 * 60 * 1_000L
    }
}
