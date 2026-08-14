package com.mardous.booming.separation.model

import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultInjection
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultStage
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal fun publishSourceSeparationSegmentWav(
    file: File,
    pcm16: ByteArray,
    sampleRate: Int,
    channelCount: Int,
    publicationId: String,
    requireWorkspaceAvailable: () -> Unit,
) {
    require(publicationId.isNotBlank()) { "Segment publication ID is empty." }
    requireWorkspaceAvailable()
    file.parentFile?.mkdirs()
    val directory = requireNotNull(file.parentFile) {
        "Segment output has no parent directory."
    }
    requireWorkspaceAvailable()
    val temporary = File.createTempFile(
        "${file.name}.$publicationId.",
        ".tmp",
        directory,
    )
    try {
        requireWorkspaceAvailable()
        WavFileWriter(
            file = temporary,
            sampleRate = sampleRate,
            channelCount = channelCount,
            durable = true,
        ).use { writer -> writer.writePcm16(pcm16) }
        require(temporary.length() == WAV_HEADER_BYTES + pcm16.size.toLong()) {
            "Published cache segment WAV has an invalid size."
        }
        SourceSeparationCacheFaultInjection.reach(
            SourceSeparationCacheFaultStage.OutputPublish,
        )
        requireWorkspaceAvailable()
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        requireWorkspaceAvailable()
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    } finally {
        temporary.delete()
    }
}

private const val WAV_HEADER_BYTES = 44L
