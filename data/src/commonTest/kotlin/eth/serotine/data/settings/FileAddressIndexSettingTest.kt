package eth.serotine.data.settings

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressIndexChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileAddressIndexSettingTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-addr1-${Random.nextLong().toULong()}")
    private val file = Path(directory, "address-index.json")

    /** A made-up importer key, a test fixture: no release pins it. */
    private val fixtureImporter = Address.of("0x" + "1e".repeat(20))

    private fun TestScope.setting() = FileAddressIndexSetting(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun write(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    @Test
    fun noChoiceUntilOneIsMadeAndBothAKeyAndAnOffSurviveARestart() = runTest {
        assertEquals(AddressIndexChoice.NotChosen, setting().observe().first(), "no file: no choice, on by default")

        assertTrue(setting().trust(setOf(fixtureImporter)))
        assertEquals(AddressIndexChoice.Chosen(setOf(fixtureImporter)), setting().observe().first())

        assertTrue(setting().trust(null))
        assertEquals(AddressIndexChoice.Chosen(importers = null), setting().observe().first())
    }

    @Test
    fun aNewerFormatIsUnreadableAndCannotBeOverwritten() = runTest {
        write("""{"version":3,"importers":null}""")
        val setting = setting()

        assertEquals(AddressIndexChoice.Unreadable, setting.observe().first())
        assertFalse(setting.trust(null))
    }

    @Test
    fun anOffOfFormat1StaysAnOff() = runTest {
        write("""{"version":1,"importer":null}""")

        assertEquals(AddressIndexChoice.Chosen(importers = null), setting().observe().first(), "not set aside")
    }

    @Test
    fun aChoiceOfFormat1NamesItsOneKeyAndIsWrittenBackAsFormat2() = runTest {
        val key = Address.of("0x" + "1e".repeat(20))
        val gnosisKey = Address.of("0x" + "9e".repeat(20))
        write("""{"version":1,"importer":"${key.hex}"}""")
        val setting = setting()

        assertEquals(AddressIndexChoice.Chosen(setOf(key)), setting.observe().first())

        assertTrue(setting.trust(setOf(key, gnosisKey)))
        assertEquals(AddressIndexChoice.Chosen(setOf(key, gnosisKey)), setting().observe().first(), "after a restart")
    }

    @Test
    fun aKeyThatIsNoAddressIsSetAsideAsOffAndSaidUntilAnotherChoiceIsMade() = runTest {
        write("""{"version":1,"importer":"0x1234"}""")
        val setting = setting()

        assertEquals(AddressIndexChoice.Chosen(importers = null, setAside = true), setting.observe().first())
        assertTrue(setting.trust(null))
        assertEquals(AddressIndexChoice.Chosen(importers = null), setting.observe().first())
    }

    @Test
    fun aDamagedChoiceIsWrittenAsOffSoThatARestartReadsOffNotTheDefault() = runTest {
        write("""{"version":1,"importer":"0x1234"}""")
        val first = setting()
        assertEquals(AddressIndexChoice.Chosen(importers = null, setAside = true), first.observe().first())
        // The off is written as the damaged choice is read.
        testScheduler.advanceUntilIdle()

        assertEquals(
            AddressIndexChoice.Chosen(importers = null, setAside = true),
            setting().observe().first(),
            "after a restart: off, never the default, and still said",
        )
        assertTrue(setting().trust(null))
        assertEquals(
            AddressIndexChoice.Chosen(importers = null),
            setting().observe().first(),
            "a choice made: not said",
        )
    }
}
