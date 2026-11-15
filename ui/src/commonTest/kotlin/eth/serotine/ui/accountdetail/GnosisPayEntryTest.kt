package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.GnosisPayReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a Safe's Owners tab says of it as a Gnosis Pay account ([gnosisPayEntry]), for each reading. */
class GnosisPayEntryTest {

    private val delay = Address.of("0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7")
    private val member = Address.of("0xa52bddbc4145f89b3740266a74f2d81c358677aa")
    private val owner = Account(Network.GNOSIS, member, AccountKind.EOA, "Card owner", AccountCustody.InKeyStore)

    private fun found(generation: DelayGeneration = DelayGeneration.CURRENT, held: List<Account> = listOf(owner)) =
        GnosisPayReading.Found(
            DelayModule(delay, generation, 180, 1_800, 0, 0, listOf(member)),
            41,
            membersHeld = held,
            membersHeldElsewhere = emptyList(),
            safeModules = listOf(delay),
        )

    @Test
    fun aMemberHeldHereSaysTransfersGoThroughTheDelay() {
        assertEquals(
            "Gnosis Pay account. Its controlling key is on this device: transfers go through its Delay module.",
            gnosisPayEntry(found()),
        )
    }

    @Test
    fun noMemberHeldHereSaysItCannotSendFromHereYet() {
        assertEquals(
            "Gnosis Pay account. Controlled through its Delay module, by a key that cannot send from here yet.",
            gnosisPayEntry(found(held = emptyList())),
        )
    }

    @Test
    fun aRetiredModuleSaysNothingIsSentThroughIt() {
        assertEquals(
            "Gnosis Pay account. Its Delay module runs retired code, so Punicus sends nothing through it.",
            gnosisPayEntry(found(DelayGeneration.RETIRED)),
        )
    }

    @Test
    fun noGnosisPayAccountOrNoAnswerYetSaysNothing() {
        assertNull(gnosisPayEntry(GnosisPayReading.Checking))
        assertNull(gnosisPayEntry(GnosisPayReading.NotGnosisPay))
        assertNull(gnosisPayEntry(GnosisPayReading.Unavailable(ReadUnavailable.SYNCING)))
    }
}
