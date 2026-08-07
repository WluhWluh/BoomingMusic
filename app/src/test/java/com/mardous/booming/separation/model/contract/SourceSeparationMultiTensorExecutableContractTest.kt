package com.mardous.booming.separation.model.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationMultiTensorExecutableContractTest {
    @Test
    fun `loads the frozen CPU-only candidate batch`() {
        val contracts = FROZEN_ASSETS.map(::loadAsset)

        assertEquals(
            listOf(
                "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
                "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
                "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
            ),
            contracts.map { it.modelContract.modelId },
        )
        assertEquals(
            listOf(OFFICIAL_6_SHA, OFFICIAL_4_SHA, GUITAR_FT_SHA),
            contracts.map { it.artifact.sha256 },
        )
        contracts.forEach { contract ->
            assertEquals(listOf(MultiTensorExecutableBackend.Cpu), contract.allowedBackends)
            assertEquals(MultiTensorFixtureRole.entries.toSet(), contract.fixtures.map { it.role }.toSet())
            assertEquals(
                contract.modelContract.stemContract.stems.map { it.stemId },
                contract.modelContract.tensorContract.outputBindings.first().stemIds,
            )
        }
    }

    @Test
    fun `freezes official four and six stem order separately`() {
        val officialSix = loadAsset(FROZEN_ASSETS[0])
        val officialFour = loadAsset(FROZEN_ASSETS[1])

        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            officialSix.modelContract.stemContract.stems.map { it.stemId },
        )
        assertEquals(
            listOf("drums", "bass", "other", "vocals"),
            officialFour.modelContract.stemContract.stems.map { it.stemId },
        )
        assertEquals(4170, officialSix.flatBuffer.outputs[0].tensorIndex)
        assertEquals(4215, officialFour.flatBuffer.outputs[0].tensorIndex)
        assertSourceRevision(officialSix, "adefossez/HTDemucs-6s", OFFICIAL_6_REVISION)
        assertSourceRevision(officialFour, "adefossez/HTDemucs", OFFICIAL_4_REVISION)
    }

    @Test
    fun `guitar ft preserves author base dataset and conversion notices`() {
        val contract = loadAsset(FROZEN_ASSETS[2])
        val notices = contract.notices.associateBy { it.noticeId }

        assertEquals("Apache-2.0", notices.getValue("author-model").licenseId)
        assertEquals(GUITAR_FT_REVISION, notices.getValue("author-model").sourceRevision)
        assertEquals("MIT", notices.getValue("base-model").licenseId)
        assertEquals("CC-BY-NC-SA-4.0", notices.getValue("training-data").licenseId)
        assertTrue(notices.getValue("conversion").statement.contains("open-source, free"))
        assertEquals(
            "22293a8c86f4bf6f83efc0f137e3efef51574ec329e290306ca5a5c61465b868",
            contract.provenance.sources.single { it.role == "model-card" }.sha256,
        )
    }

    @Test
    fun `rejects a backend or fixture mutation`() {
        val baseline = loadAsset(FROZEN_ASSETS[0])

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorExecutableContractValidator.validate(
                baseline.copy(allowedBackends = emptyList()),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorExecutableContractValidator.validate(
                baseline.copy(
                    fixtures = baseline.fixtures.map { fixture ->
                        if (fixture.role == MultiTensorFixtureRole.CombinedGolden) {
                            fixture.copy(shape = fixture.shape.dropLast(1) + 343_979)
                        } else {
                            fixture
                        }
                    },
                ),
            )
        }
    }

    @Test
    fun `rejects missing guitar ft training disclosure`() {
        val baseline = loadAsset(FROZEN_ASSETS[2])

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorExecutableContractValidator.validate(
                baseline.copy(
                    notices = baseline.notices.filterNot { it.noticeId == "training-data" },
                ),
            )
        }
    }

    @Test
    fun `rejects an unpinned model source repository`() {
        val baseline = loadAsset(FROZEN_ASSETS[0])
        val source = baseline.provenance.sources.first()

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiTensorExecutableContractValidator.validate(
                baseline.copy(
                    provenance = baseline.provenance.copy(
                        sources = listOf(source.copy(revision = null)) +
                            baseline.provenance.sources.drop(1),
                    ),
                ),
            )
        }
    }

    private fun loadAsset(name: String): SourceSeparationMultiTensorExecutableContract {
        val path = "source-separation/research-contracts/$name"
        val serialized = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing executable contract asset $path"
        }.bufferedReader().use { it.readText() }
        return SourceSeparationMultiTensorExecutableContractLoader.load(serialized)
    }

    private fun assertSourceRevision(
        contract: SourceSeparationMultiTensorExecutableContract,
        repository: String,
        revision: String,
    ) {
        contract.provenance.sources.forEach { source ->
            assertEquals(repository, source.repository)
            assertEquals(revision, source.revision)
        }
    }

    private companion object {
        const val OFFICIAL_6_SHA =
            "8b19e919dd17c6a93d862ca9b1158ed72f09feb4c52745819346369506ba4ed7"
        const val OFFICIAL_4_SHA =
            "9855718072ee819bacacdb6b670bd6257feca172bf27ac1d72dff994cdbeed81"
        const val GUITAR_FT_SHA =
            "ab632a5a024033d557eabb716f8829230532e8e5b4cd7ba146812a301f89b9a5"
        const val GUITAR_FT_REVISION = "163ec83135ee06e6f10cb8cd94d2ecef8f3f34ad"
        const val OFFICIAL_6_REVISION = "053e1404489b3dc58bf718224fac4b7316de8c93"
        const val OFFICIAL_4_REVISION = "bf35a81b663819a8255c8fefee17f9d812b786b5"
        val FROZEN_ASSETS = listOf(
            "htdemucs-6s-official-fp32.json",
            "htdemucs-4s-official-base-fp32.json",
            "htdemucs-6s-guitar-ft-fp32.json",
        )
    }
}
