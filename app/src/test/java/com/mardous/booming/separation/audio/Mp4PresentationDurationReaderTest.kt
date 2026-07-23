package com.mardous.booming.separation.audio

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4PresentationDurationReaderTest {

    @Test
    fun `reads version zero movie presentation duration`() {
        val file = temporaryMp4(movieHeader(version = 0, timescale = 44_100, duration = 661_500))

        val durationUs = FileInputStream(file).channel.use(::readMp4MovieDurationUs)

        assertEquals(15_000_000L, durationUs)
        file.delete()
    }

    @Test
    fun `reads version one movie presentation duration after another top-level atom`() {
        val file = temporaryMp4(
            atom("free", byteArrayOf(1, 2, 3, 4)) +
                movieHeader(version = 1, timescale = 1_000, duration = 15_000),
        )

        val durationUs = FileInputStream(file).channel.use(::readMp4MovieDurationUs)

        assertEquals(15_000_000L, durationUs)
        file.delete()
    }

    @Test
    fun `rejects a truncated movie atom`() {
        val file = temporaryMp4(byteArrayOf(0, 0, 0, 32) + "moov".encodeToByteArray())

        val durationUs = FileInputStream(file).channel.use(::readMp4MovieDurationUs)

        assertNull(durationUs)
        file.delete()
    }

    private fun movieHeader(version: Int, timescale: Int, duration: Long): ByteArray {
        val payload = if (version == 0) {
            ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
                put(version.toByte())
                put(byteArrayOf(0, 0, 0))
                putInt(0)
                putInt(0)
                putInt(timescale)
                putInt(duration.toInt())
            }.array()
        } else {
            ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
                put(version.toByte())
                put(byteArrayOf(0, 0, 0))
                putLong(0L)
                putLong(0L)
                putInt(timescale)
                putLong(duration)
            }.array()
        }
        return atom("moov", atom("mvhd", payload))
    }

    private fun atom(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(8 + payload.size)
            put(type.encodeToByteArray())
            put(payload)
        }.array()
    }

    private fun temporaryMp4(bytes: ByteArray): File {
        return File.createTempFile("phase7-duration-", ".m4a").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
    }
}
