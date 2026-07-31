import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val LITERT_API_VERSION = "2.1.5-bss.2-downloadable-loader"
private val LITERT_API_AAR_SHA256 =
    "a68b51546f268b6db0b64bec3d1d95389ba44a48c59beaa1769794682c94b4f9"
private val LITERT_API_AAR_URL =
    "https://github.com/WluhWluh/bss-litert-android/releases/download/" +
        "downloadable-runtime-v2.1.5-bss.2-exp.2/" +
        "litert-api-2.1.5-bss.2-downloadable-loader.aar"

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun materializeLiteRtApi(
    gradleUserHome: File,
    offline: Boolean,
): File {
    val cacheDirectory = File(
        gradleUserHome,
        "caches/booming-ss/litert/api/$LITERT_API_VERSION",
    )
    val artifact = File(
        cacheDirectory,
        "litert-api-$LITERT_API_VERSION.aar",
    )
    if (artifact.isFile && artifact.sha256() == LITERT_API_AAR_SHA256) {
        return artifact
    }
    if (offline) {
        error(
            "The checksum-verified Booming SS LiteRT API AAR is not cached. " +
                "Run Gradle once without --offline."
        )
    }
    check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
        "Unable to create the Booming SS LiteRT cache."
    }
    val lockFile = File(cacheDirectory, "materialize.lock")
    RandomAccessFile(lockFile, "rw").channel.use { channel ->
        channel.lock().use {
            if (artifact.isFile && artifact.sha256() == LITERT_API_AAR_SHA256) {
                return artifact
            }
            val temporary = File(cacheDirectory, "${artifact.name}.part")
            try {
                val connection = URI(LITERT_API_AAR_URL).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "Booming-SS-Gradle")
                }
                connection.getInputStream().buffered().use { input ->
                    temporary.outputStream().buffered().use(input::copyTo)
                }
                val actualSha256 = temporary.sha256()
                check(actualSha256 == LITERT_API_AAR_SHA256) {
                    "Booming SS LiteRT API AAR SHA-256 mismatch: $actualSha256"
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        artifact.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        artifact.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                temporary.delete()
            }
        }
    }
    check(artifact.isFile && artifact.sha256() == LITERT_API_AAR_SHA256) {
        "Unable to materialize the checksum-verified Booming SS LiteRT API AAR."
    }
    return artifact
}

val liteRtApiAar = materializeLiteRtApi(
    gradle.gradleUserHomeDir,
    gradle.startParameter.isOffline,
)
gradle.extensions.extraProperties.set(
    "boomingSsLiteRtApiAar",
    liteRtApiAar.absolutePath,
)

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "BoomingMusic"
include(":app")
