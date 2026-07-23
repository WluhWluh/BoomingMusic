package com.mardous.booming.separation.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToLong

internal class Mp4PresentationDurationReader(
    private val context: Context,
) {
    fun read(uri: Uri): Long? {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val start = descriptor.startOffset.coerceAtLeast(0L)
                    val available = descriptor.declaredLength
                        .takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it >= 0L }
                        ?: (channel.size() - start)
                    readMp4MovieDurationUs(channel, start, available)
                }
            }
        }.getOrNull()
    }
}

internal fun readMp4MovieDurationUs(
    channel: FileChannel,
    startOffset: Long = 0L,
    byteCount: Long = channel.size() - startOffset,
): Long? {
    if (startOffset < 0L || byteCount <= 0L) return null
    val endOffset = (startOffset + byteCount).coerceAtMost(channel.size())
    var position = startOffset
    while (position < endOffset) {
        val atom = readAtomHeader(channel, position, endOffset) ?: return null
        if (atom.type == MP4_ATOM_MOOV) {
            return readMovieHeaderDurationUs(channel, atom.payloadStart, atom.end)
        }
        position = atom.end
    }
    return null
}

private fun readMovieHeaderDurationUs(
    channel: FileChannel,
    startOffset: Long,
    endOffset: Long,
): Long? {
    var position = startOffset
    while (position < endOffset) {
        val atom = readAtomHeader(channel, position, endOffset) ?: return null
        if (atom.type == MP4_ATOM_MVHD) {
            return parseMovieHeaderDurationUs(channel, atom)
        }
        position = atom.end
    }
    return null
}

private fun parseMovieHeaderDurationUs(
    channel: FileChannel,
    atom: Mp4Atom,
): Long? {
    val prefixSize = minOf(atom.payloadSize, 32L).toInt()
    if (prefixSize < 20) return null
    val payload = ByteBuffer.allocate(prefixSize).order(ByteOrder.BIG_ENDIAN)
    if (!readFully(channel, payload, atom.payloadStart)) return null
    payload.flip()
    return when (val version = payload.get(0).toInt() and 0xFF) {
        0 -> {
            val timescale = payload.getInt(12).toLong() and UINT32_MASK
            val duration = payload.getInt(16).toLong() and UINT32_MASK
            durationToMicros(timescale, duration.takeUnless { it == UINT32_MASK })
        }
        1 -> {
            if (prefixSize < 32) return null
            val timescale = payload.getInt(20).toLong() and UINT32_MASK
            val duration = payload.getLong(24).takeIf { it >= 0L }
            durationToMicros(timescale, duration)
        }
        else -> error("Unsupported mvhd version: $version")
    }
}

private fun durationToMicros(timescale: Long, duration: Long?): Long? {
    if (timescale <= 0L || duration == null || duration <= 0L) return null
    return (duration.toDouble() * MICROS_PER_SECOND / timescale.toDouble())
        .roundToLong()
        .takeIf { it > 0L }
}

private fun readAtomHeader(
    channel: FileChannel,
    position: Long,
    containerEnd: Long,
): Mp4Atom? {
    if (containerEnd - position < BASIC_ATOM_HEADER_BYTES) return null
    val basic = ByteBuffer.allocate(BASIC_ATOM_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
    if (!readFully(channel, basic, position)) return null
    basic.flip()
    val size32 = basic.int.toLong() and UINT32_MASK
    val type = basic.int
    val headerSize: Long
    val atomSize = when (size32) {
        0L -> {
            headerSize = BASIC_ATOM_HEADER_BYTES.toLong()
            containerEnd - position
        }
        1L -> {
            if (containerEnd - position < LARGE_ATOM_HEADER_BYTES) return null
            val large = ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            if (!readFully(channel, large, position + BASIC_ATOM_HEADER_BYTES)) return null
            large.flip()
            headerSize = LARGE_ATOM_HEADER_BYTES
            large.long.takeIf { it >= LARGE_ATOM_HEADER_BYTES } ?: return null
        }
        else -> {
            headerSize = BASIC_ATOM_HEADER_BYTES.toLong()
            size32
        }
    }
    if (atomSize < headerSize || atomSize > containerEnd - position) return null
    return Mp4Atom(
        type = type,
        payloadStart = position + headerSize,
        end = position + atomSize,
    )
}

private fun readFully(channel: FileChannel, target: ByteBuffer, position: Long): Boolean {
    var offset = position
    while (target.hasRemaining()) {
        val read = channel.read(target, offset)
        if (read <= 0) return false
        offset += read
    }
    return true
}

private data class Mp4Atom(
    val type: Int,
    val payloadStart: Long,
    val end: Long,
) {
    val payloadSize: Long
        get() = end - payloadStart
}

private const val MP4_ATOM_MOOV = 0x6D6F6F76
private const val MP4_ATOM_MVHD = 0x6D766864
private const val BASIC_ATOM_HEADER_BYTES = 8
private const val LARGE_ATOM_HEADER_BYTES = 16L
private const val UINT32_MASK = 0xFFFF_FFFFL
private const val MICROS_PER_SECOND = 1_000_000.0
