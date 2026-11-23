package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueChoice
import eth.serotine.domain.repository.VenueReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class SwapVenuesTest {

    private class MemoryVenue : SwapVenueSetting {
        val choice = MutableStateFlow<VenueChoice>(VenueChoice.Chosen(null))
        var failSaves = false

        override fun observe(): Flow<VenueChoice> = choice

        override suspend fun choose(venue: SwapVenue): Boolean {
            if (failSaves) return false
            choice.value = VenueChoice.Chosen(venue)
            return true
        }
    }

    private val setting = MemoryVenue()
    private val observe = ObserveSwapVenue(setting)
    private val choose = ChooseSwapVenue(setting)

    @Test
    fun aSwapStartsAtUniswapUntilTheUserChoosesAndThenAtTheirLastChoice() = runTest {
        assertEquals(SwapVenue.UNISWAP, observe().first(), "the first time")
        assertTrue(choose(SwapVenue.COW))
        assertEquals(SwapVenue.COW, observe().first())
        assertTrue(choose(SwapVenue.UNISWAP))
        assertEquals(SwapVenue.UNISWAP, observe().first())
    }

    @Test
    fun aChoiceThatCannotBeReadOrWasSetAsideStartsAtUniswap() = runTest {
        setting.choice.value = VenueChoice.Unreadable
        assertEquals(SwapVenue.UNISWAP, observe().first())
        setting.choice.value = VenueChoice.Chosen(null, setAside = true)
        assertEquals(SwapVenue.UNISWAP, observe().first())
    }

    @Test
    fun aChoiceNotSavedSaysSo() = runTest {
        setting.failSaves = true
        assertFalse(choose(SwapVenue.COW))
        assertEquals(SwapVenue.UNISWAP, observe().first())
    }

    @Test
    fun cowIsOfferedOnlyWhereTheLightClientDatesFinalizedBlocks() {
        val dating = object : LightClient {
            override val datesFinalizedBlocks = true
            override fun observeStatus(network: Network): Flow<LightClientStatus> =
                MutableStateFlow(LightClientStatus.Starting)
        }
        val notDating = object : LightClient by dating {
            override val datesFinalizedBlocks: Boolean = false
        }
        assertTrue(CowOfferedOnThisDevice(dating)())
        assertFalse(CowOfferedOnThisDevice(notDating)())
    }

    @Test
    fun swapIsOfferedWhereEitherVenueIsPinnedAndOnlyThere() = runTest {
        val uniswap = object : UniswapV3Chain {
            var venue: VenueReading = VenueReading.NotPinned
            override suspend fun venue(network: Network): VenueReading = venue
            override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation = error("not read")
        }
        val cow = FakeCowChain().apply { venue = CowVenueReading.NotPinned }
        val has = HasSwapVenue(uniswap, cow)

        assertFalse(has(Network.GNOSIS), "neither pinned")
        // CoW stands without Uniswap (owner ruling, 2026-11-23); an unproven pin is still a pin.
        cow.venue = CowVenueReading.Unavailable(ReadUnavailable.SYNCING)
        assertTrue(has(Network.GNOSIS), "CoW alone")
        cow.venue = CowVenueReading.NotPinned
        uniswap.venue = VenueReading.Unavailable(ReadUnavailable.SYNCING)
        assertTrue(has(Network.GNOSIS), "Uniswap alone")
    }
}

