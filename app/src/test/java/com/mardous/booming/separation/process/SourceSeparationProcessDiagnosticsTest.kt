package com.mardous.booming.separation.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSeparationProcessDiagnosticsTest {
    @Test
    fun `proc stat parser preserves start identity when command contains spaces`() {
        val stat = "123 (source separation) S " +
            (1..18).joinToString(" ") + " 424242 99 100"

        assertEquals(424242L, SourceSeparationProcParser.parseProcessStartTicks(stat))
        assertNull(SourceSeparationProcParser.parseProcessStartTicks("malformed"))
    }

    @Test
    fun `status and smaps fields are converted from KiB to bytes`() {
        val status = """
            Name: test
            VmPeak: 4096 kB
            VmSize: 2048 kB
            Threads: 7
        """.trimIndent()
        val smaps = """
            AnonHugePages:  8 kB
            AnonHugePages: 16 kB
        """.trimIndent()

        assertEquals(
            4_096L * 1_024L,
            SourceSeparationProcParser.parseStatusKilobytes(status, "VmPeak"),
        )
        assertEquals(7, SourceSeparationProcParser.parseStatusCount(status, "Threads"))
        assertEquals(
            24L * 1_024L,
            SourceSeparationProcParser.sumKilobyteFields(smaps, "AnonHugePages"),
        )
    }

    @Test
    fun `maps parser measures only gaps between mapped regions`() {
        val maps = listOf(
            "00001000-00002000 r--p 00000000 00:00 0",
            "00004000-00005000 rw-p 00000000 00:00 0",
            "00005800-00006000 rw-p 00000000 00:00 0",
        )

        assertEquals(
            0x2000L,
            SourceSeparationProcParser.largestMappedAddressGapBytes(maps),
        )
        assertNull(SourceSeparationProcParser.largestMappedAddressGapBytes(maps.take(1)))
    }

    @Test
    fun `maps parser retains native library names without device paths`() {
        val maps = listOf(
            "7000-8000 r-xp 00000000 00:00 0 /data/app/pkg/lib/arm64/libLiteRt.so",
            "8000-9000 r--p 00000000 00:00 0 /data/app/pkg/lib/arm64/libLiteRt.so",
            "9000-a000 r-xp 00000000 00:00 0 /vendor/lib64/libOpenCL.so (deleted)",
            "a000-b000 rw-p 00000000 00:00 0 [anon:dalvik-main space]",
            "b000-c000 r-xp 00000000 00:00 0 /vendor/lib64/libgpu_driver.so.1",
        )

        assertEquals(
            listOf("libLiteRt.so", "libOpenCL.so", "libgpu_driver.so.1"),
            SourceSeparationProcParser.mappedNativeLibraryNames(maps),
        )
    }
}
