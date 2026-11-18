package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.PrimaryName
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SeenEnsName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** [ObserveAccountNames] over fakes (issue #274). */
class ObserveAccountNamesTest {

    private val mine = Address.of("0x51fd612218249b9f4cf76cbc80258bcc75d79f0d")
    private val other = Address.of("0x" + "22".repeat(20))
    private val account = AccountId(Network.ETHEREUM, mine)
    private val seen = MemorySeenEnsNames()

    private class Names : NameResolver {
        val to = mutableMapOf<String, NameResolution>()
        var primary: PrimaryName = PrimaryName.None

        override suspend fun resolve(network: Network, name: String): NameResolution =
            to[name] ?: NameResolution.NoAddress

        override suspend fun primaryName(network: Network, address: Address): PrimaryName = primary
    }

    @Test
    fun thePrimaryNameAndTheNamesStillPointingHereAreShownOnce() = runTest {
        val names = Names()
        names.primary = PrimaryName.Named("daily.eth", 1)
        names.to["daily.eth"] = NameResolution.Resolved("daily.eth", mine, 1)
        names.to["pay.eth"] = NameResolution.Resolved("pay.eth", mine, 1)
        names.to["moved.eth"] = NameResolution.Resolved("moved.eth", other, 1)
        listOf("daily.eth", "moved.eth", "pay.eth", "gone.eth").forEach { seen.remember(account, SeenEnsName(it, 1)) }

        val shown = ObserveAccountNames(names, seen)(account).first { it.pointing.isNotEmpty() }

        assertEquals(AccountNames("daily.eth", listOf("pay.eth"), checked = true), shown)
    }

    @Test
    fun aNameWithNoVerifiedAnswerIsLeftOutAndTheListIsNotChecked() = runTest {
        val names = Names()
        names.primary = PrimaryName.Unavailable(ReadUnavailable.UNPROVEN)
        names.to["pay.eth"] = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        seen.remember(account, SeenEnsName("pay.eth", 1))

        // Every reading stays the same, so the one value emitted is the last.
        val shown = ObserveAccountNames(names, seen, retryDelay = 1.minutes)(account).first()

        assertEquals(AccountNames(null, emptyList(), checked = false), shown)
    }

    @Test
    fun anAccountOnANetworkWithoutEnsHasNoNames() = runTest {
        val names = Names()
        names.primary = PrimaryName.Named("daily.eth", 1)
        val gnosis = AccountId(Network.GNOSIS, mine)

        assertEquals(AccountNames.NONE, ObserveAccountNames(names, seen)(gnosis).first())
    }
}
