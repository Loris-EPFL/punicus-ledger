package eth.serotine.domain.usecase

import eth.serotine.domain.repository.KeptAddressIndex
import eth.serotine.domain.model.AddressIndexCoverage
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /** The first status once the engine said whether it has a reader. */
    private suspend fun Flow<AddressIndexStatus>.answered() = first { it.reader != null }
    private fun turn(pinned: Address?) = TurnAddressIndex(index, setting, pinned)

    @Test
    fun onByDefaultForThePinnedKeyWithAReaderAndAnExplicitOffStaysOff() = runTest {
        val fresh = status(fixtureImporter)().answered()
        assertTrue(fresh.on, "on by default (owner's decision, 2026-11-22)")
        assertEquals(emptySet(), fresh.missing)

        assertEquals(AddressIndexSwitch.Done, turn(fixtureImporter)(on = false))
        assertEquals(AddressIndexChoice.Chosen(null), setting.choice.value, "an explicit off is stored")
        assertFalse(status(fixtureImporter)().answered().on)
        assertFalse(status(otherImporter)().answered().on, "an explicit off stays off whatever key is pinned")

        assertEquals(AddressIndexSwitch.Done, turn(fixtureImporter)(on = true))
        assertEquals(AddressIndexChoice.Chosen(fixtureImporter), setting.choice.value, "the key accepted is kept")
        assertTrue(status(fixtureImporter)().answered().on)
    }

    @Test
    fun withNoKeyPinnedItCannotBeTurnedOnAndIsGreyedOutForThat() = runTest {
        val status = status(pinned = null)().answered()

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
            status(pinned = null)().answered().missing,
        )
        assertEquals(setOf(AddressIndexMissing.NO_READER), status(fixtureImporter)().answered().missing)
        assertEquals(
            AddressIndexSwitch.Unavailable(setOf(AddressIndexMissing.NO_READER)),
            turn(fixtureImporter)(on = true),
        )
        assertEquals(AddressIndexSwitch.Done, turn(fixtureImporter)(on = false), "off can always be saved")
    }

    @Test
    fun aChoiceToTurnItOnForAnotherKeyDoesNotCarryOverAndTheDefaultHolds() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(otherImporter)

        val status = status(fixtureImporter)().answered()

        assertTrue(status.on, "no choice for the key pinned now: on by default")
        assertEquals(otherImporter, status.trustsAnotherKey)
    }

    @Test
    fun aChoiceFoundDamagedIsOffSinceItMayHaveBeenAnOff() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(importer = null, setAside = true)

        val status = status(fixtureImporter)().answered()

        assertFalse(status.on)
        assertTrue(status.setAside)
    }

    @Test
    fun aChoiceThatCannotBeReadIsOffAndCannotBeChanged() = runTest {
        setting.choice.value = AddressIndexChoice.Unreadable

        val status = status(fixtureImporter)().answered()

        assertFalse(status.on)
        assertEquals(setOf(AddressIndexMissing.CHOICE_UNREADABLE), status.missing)
        assertEquals(AddressIndexSwitch.NotSaved, turn(fixtureImporter)(on = true))
    }

    @Test
    fun aSwitchThatCannotBeSavedChangesNothing() = runTest {
        setting.failSaves = true

        assertEquals(AddressIndexSwitch.NotSaved, turn(fixtureImporter)(on = false))
        assertTrue(status(fixtureImporter)().answered().on, "still the default")
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
        assertTrue(index.checks.isEmpty(), "nothing fetched")
        // Without a reader the engine refuses the check itself (NotInThisBuild): the use case does not wait for its
        // word on the reader first.
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

    @Test
    fun aDetailCheckRefusedForTheCheckpointAlarmDeletesTheKeptList() = runTest {
        turnedOn()
        val archive = MemoryAddressIndexArchive()
        val coverage = AddressIndexCoverage(fixtureImporter, 1, 1L..9L, null, null, emptyList())
        val none = emptyList<AddressIndexEntry>()
        archive.kept[account] = KeptAddressIndex(coverage, 9, emptyList(), none, none, none, none, emptyList(), null)
        index.check = { _, _ -> AddressIndexCheck.Refused(AddressIndexRefusal.CheckpointAlarm("table alarm")) }

        CheckAddressIndexEntry(index, setting, fixtureImporter, archive)(account, entry)

        assertNull(archive.kept[account], "the alarm withdraws the trust the kept rows rest on")
    }

    @Test
    fun theEngineReadsTheIndexOnlyWhileTheSettingIsOnForThePinnedKey() = runTest {
        val reader = RecordingReaderSettings()
        val follow = backgroundScope.launch { FollowAddressIndexSetting(setting, fixtureImporter, reader)() }
        testScheduler.runCurrent()
        assertEquals(listOf<Address?>(fixtureImporter), reader.applied, "on by default: the pinned key")

        turn(fixtureImporter)(on = false)
        testScheduler.runCurrent()
        assertEquals(listOf(fixtureImporter, null), reader.applied, "an explicit off: no importer")

        turn(fixtureImporter)(on = true)
        testScheduler.runCurrent()
        assertEquals(listOf(fixtureImporter, null, fixtureImporter), reader.applied)
        follow.cancel()
    }

    @Test
    fun withNoKeyPinnedTheEngineIsToldNone() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(fixtureImporter)
        val unpinned = RecordingReaderSettings()
        val follow = backgroundScope.launch { FollowAddressIndexSetting(setting, null, unpinned)() }
        testScheduler.runCurrent()

        assertEquals(listOf<Address?>(null), unpinned.applied, "no key pinned, whatever the user once chose")
        follow.cancel()
    }

    @Test
    fun theEngineIsToldTheSettingWithoutWaitingForItsWordOnTheReader() = runTest {
        index.readerAnswered = false
        val reader = RecordingReaderSettings()
        val follow = backgroundScope.launch { FollowAddressIndexSetting(setting, fixtureImporter, reader)() }
        testScheduler.runCurrent()

        assertEquals(listOf<Address?>(fixtureImporter), reader.applied, "applied before the engine said anything")
        follow.cancel()
    }

    @Test
    fun untilTheEngineSaysTheSettingIsGreyedOutNotLeftOut() = runTest {
        index.readerAnswered = false
        val statuses = mutableListOf<AddressIndexStatus>()
        val observe = backgroundScope.launch { status(fixtureImporter)().collect { statuses += it } }
        testScheduler.runCurrent()

        val waiting = statuses.single()
        assertNull(waiting.reader)
        assertFalse(waiting.on)
        assertTrue(waiting.wanted, "on by default, once the engine says it reads Swarm")
        assertEquals(setOf(AddressIndexMissing.READER_NOT_KNOWN), waiting.missing)

        index.readerAnswered = true
        testScheduler.runCurrent()
        assertTrue(statuses.last().on, "on once the engine said it reads Swarm")
        observe.cancel()
    }

    @Test
    fun aCheckDoesNotWaitForTheEnginesWordOnTheReader() = runTest {
        index.readerAnswered = false
        index.check = { _, _ -> AddressIndexCheck.Refused(AddressIndexRefusal.NotInThisBuild) }

        val refused = CheckAddressIndexEntry(index, setting, fixtureImporter)(account, entry)

        assertEquals(AddressIndexEntryCheck.Refused(AddressIndexRefusal.NotInThisBuild), refused, "the engine's own")
    }
}
