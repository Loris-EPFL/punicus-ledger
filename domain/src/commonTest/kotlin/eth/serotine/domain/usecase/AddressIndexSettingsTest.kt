package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The addr1 history's setting ([ObserveAddressIndexStatus], [TurnAddressIndex]) and [CheckAddressIndexEntry]. */
class AddressIndexSettingsTest {

    /** A made-up importer key, a test fixture: no release pins it. */
    private val fixtureImporter = Address.of("0x" + "1e".repeat(20))
    private val otherImporter = Address.of("0x" + "2e".repeat(20))
    private val index = FakeAddressIndexHistory()
    private val setting = MemoryAddressIndexSetting()

    private fun status(pinned: Address?) = ObserveAddressIndexStatus(index, setting, pinned)
    private fun turn(pinned: Address?) = TurnAddressIndex(index, setting, pinned)

    @Test
    fun onByDefaultForThePinnedKeyWithAReaderAndAnExplicitOffStaysOff() = runTest {
        val fresh = status(fixtureImporter)().first()
        assertTrue(fresh.on, "on by default (owner's decision, 2026-11-22)")
        assertEquals(emptySet(), fresh.missing)

        assertEquals(AddressIndexSwitch.Done, turn(fixtureImporter)(on = false))
        assertEquals(AddressIndexChoice.Chosen(null), setting.choice.value, "an explicit off is stored")
        assertFalse(status(fixtureImporter)().first().on)
        assertFalse(status(otherImporter)().first().on, "an explicit off stays off whatever key is pinned")

        assertEquals(AddressIndexSwitch.Done, turn(fixtureImporter)(on = true))
        assertEquals(AddressIndexChoice.Chosen(fixtureImporter), setting.choice.value, "the key accepted is kept")
        assertTrue(status(fixtureImporter)().first().on)
    }

    @Test
    fun withNoKeyPinnedItCannotBeTurnedOnAndIsGreyedOutForThat() = runTest {
        val status = status(pinned = null)().first()

        assertFalse(status.on)
        assertEquals(setOf(AddressIndexMissing.NO_KEY_PINNED), status.missing)
        assertEquals(AddressIndexSwitch.Unavailable(setOf(AddressIndexMissing.NO_KEY_PINNED)), turn(null)(on = true))
        assertEquals(AddressIndexChoice.NotChosen, setting.choice.value, "nothing saved")
    }

    @Test
    fun anEngineWithoutASwarmReaderGreysItOutTooAndBothReasonsAreSaid() = runTest {
        index.reader = false

        assertEquals(
            setOf(AddressIndexMissing.NO_KEY_PINNED, AddressIndexMissing.NO_READER),
            status(pinned = null)().first().missing,
        )
        assertEquals(setOf(AddressIndexMissing.NO_READER), status(fixtureImporter)().first().missing)
        assertEquals(
            AddressIndexSwitch.Unavailable(setOf(AddressIndexMissing.NO_READER)),
            turn(fixtureImporter)(on = true),
        )
        assertEquals(AddressIndexSwitch.Done, turn(fixtureImporter)(on = false), "off can always be saved")
    }

    @Test
    fun aChoiceToTurnItOnForAnotherKeyDoesNotCarryOverAndTheDefaultHolds() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(otherImporter)

        val status = status(fixtureImporter)().first()

        assertTrue(status.on, "no choice for the key pinned now: on by default")
        assertEquals(otherImporter, status.trustsAnotherKey)
    }

    @Test
    fun aChoiceFoundDamagedIsOffSinceItMayHaveBeenAnOff() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(importer = null, setAside = true)

        val status = status(fixtureImporter)().first()

        assertFalse(status.on)
        assertTrue(status.setAside)
    }

    @Test
    fun aChoiceThatCannotBeReadIsOffAndCannotBeChanged() = runTest {
        setting.choice.value = AddressIndexChoice.Unreadable

        val status = status(fixtureImporter)().first()

        assertFalse(status.on)
        assertEquals(setOf(AddressIndexMissing.CHOICE_UNREADABLE), status.missing)
        assertEquals(AddressIndexSwitch.NotSaved, turn(fixtureImporter)(on = true))
    }

    @Test
    fun aSwitchThatCannotBeSavedChangesNothing() = runTest {
        setting.failSaves = true

        assertEquals(AddressIndexSwitch.NotSaved, turn(fixtureImporter)(on = false))
        assertTrue(status(fixtureImporter)().first().on, "still the default")
    }

    private val account = AccountId(Network.ETHEREUM, Address.of("0x" + "11".repeat(20)))
    private val entry = AddressIndexEntry(21_000_000, 3, setOf(AddressIndexRole.FROM, AddressIndexRole.TRACE))

    private fun verified(of: AddressIndexEntry) = AddressIndexVerdict.Verified(
        AddressIndexRow(
            entry = of,
            blockHash = "0x" + "bb".repeat(32),
            final = true,
            confirmed = of.roles - AddressIndexRole.TRACE,
            unchecked = of.roles intersect setOf(AddressIndexRole.TRACE),
            transaction = AddressIndexTransaction("0x" + "cc".repeat(32), account.address, null, Wei.of("0"), null),
        ),
    )

    /** The setting on for the fixture's key, as the detail's check needs it. */
    private fun turnedOn() {
        setting.choice.value = AddressIndexChoice.Chosen(fixtureImporter)
    }

    @Test
    fun theDetailChecksNothingWhileTheSettingIsNotOnForItsKey() = runTest {
        val check = CheckAddressIndexEntry(index, setting, fixtureImporter)

        setting.choice.value = AddressIndexChoice.Chosen(null)
        assertEquals(AddressIndexEntryCheck.Refused(AddressIndexRefusal.SettingOff), check(account, entry), "off")
        setting.choice.value = AddressIndexChoice.Unreadable
        assertEquals(AddressIndexEntryCheck.Refused(AddressIndexRefusal.SettingOff), check(account, entry), "unread")
        turnedOn()
        index.reader = false
        assertEquals(AddressIndexEntryCheck.Refused(AddressIndexRefusal.SettingOff), check(account, entry), "no reader")
        assertTrue(index.checks.isEmpty(), "nothing fetched")
    }

    @Test
    fun theDetailChecksItsEntryAgainAndTakesOnlyTheVerdictOfThatEntry() = runTest {
        turnedOn()
        index.check = { entries, _ -> AddressIndexCheck.Verdicts(entries.map(::verified)) }
        val check = CheckAddressIndexEntry(index, setting, fixtureImporter)

        val checked = assertIs<AddressIndexEntryCheck.Checked>(check(account, entry))

        assertIs<AddressIndexVerdict.Verified>(checked.verdict)
        assertEquals(listOf(entry), index.checks.single())
        assertEquals(fixtureImporter, check.importer)
    }

    @Test
    fun aVerdictForOtherRolesIsNoVerdict() = runTest {
        turnedOn()
        val other = entry.copy(roles = setOf(AddressIndexRole.TO))
        index.check = { _, _ -> AddressIndexCheck.Verdicts(listOf(verified(other))) }

        val answer = CheckAddressIndexEntry(index, setting, fixtureImporter)(account, entry)

        val checked = assertIs<AddressIndexEntryCheck.Checked>(answer)

        assertIs<AddressIndexVerdict.Unavailable>(checked.verdict)
    }

    @Test
    fun aTraceOnlyEntryIsNeverSentToTheCheck() = runTest {
        turnedOn()
        val trace = AddressIndexEntry(21_000_000, 4, setOf(AddressIndexRole.TRACE))

        val answer = CheckAddressIndexEntry(index, setting, fixtureImporter)(account, trace)

        val checked = assertIs<AddressIndexEntryCheck.Checked>(answer)

        assertIs<AddressIndexVerdict.Unverified>(checked.verdict)
        assertTrue(index.checks.isEmpty())
    }

    @Test
    fun aRefusedCheckIsARefusalNeverAVerdict() = runTest {
        turnedOn()
        index.check = { _, _ -> AddressIndexCheck.Refused(AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)) }

        val refused = CheckAddressIndexEntry(index, setting, fixtureImporter)(account, entry)

        assertEquals(AddressIndexEntryCheck.Refused(AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)), refused)
        assertNull((refused as? AddressIndexEntryCheck.Checked)?.verdict)
    }
}
