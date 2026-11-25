package eth.serotine.ui.accounts

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.StaleAnchorWarning
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a network's header in simple mode says, and in which colour ([NetworkSection.simpleStatus]). */
class NetworkSectionTest {

    private val synced = LightClientItem(
        "Verified at block 100 · 8 s ago",
        null,
        false,
        LightClientHealth.VERIFIED,
        simpleHeader = "Up to date",
    )
    private val section = NetworkSection(Network.GNOSIS.chainId, "Gnosis Chain", false, true, synced, emptyList())

    @Test
    fun itsLightClientsWordsAndColourWhileNothingElseBlocksIt() {
        assertEquals("Up to date", section.simpleStatus)
        assertEquals(LightClientHealth.VERIFIED, section.simpleHealth)
    }

    @Test
    fun aVersionThatCanNoLongerFollowItComesFirstInRed() {
        val blocked = section.copy(
            blocked = "This version can no longer follow Gnosis Chain.",
            staleAnchor = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)),
        )

        assertEquals("Needs an update", blocked.simpleStatus)
        assertEquals(LightClientHealth.NOT_VERIFIED, blocked.simpleHealth)
    }

    @Test
    fun aStaleCheckpointComesBeforeTheLightClientsWordsInRed() {
        val warning = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3))
        val stale = section.copy(staleAnchor = warning)

        assertEquals("Checkpoint too old", stale.simpleStatus)
        assertEquals(LightClientHealth.NOT_VERIFIED, stale.simpleHealth)
    }

    @Test
    fun anItemWithoutItsFewWordsSaysItsColoursMeaningNeverTheStripsBlock() {
        val bare = section.copy(status = synced.copy(simpleHeader = ""))

        assertEquals("Up to date", bare.simpleStatus)
    }

    @Test
    fun itsIconIsAlwaysItsOwnNetworks() {
        assertEquals(Network.SEPOLIA.chainId, section.copy(chainId = Network.SEPOLIA.chainId).network.chainId)
    }
}
