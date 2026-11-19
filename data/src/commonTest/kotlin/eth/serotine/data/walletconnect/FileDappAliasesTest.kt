package eth.serotine.data.walletconnect

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** [FileDappAliases] against the [eth.serotine.domain.repository.PerAppAliases] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDappAliasesTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-aliases-${Random.nextLong().toULong()}")
    private val file = Path(directory, "aliases.json")
    private val uniswap = (AppHost.parse("uniswap.org") as AppHostResult.Host).host
    private val app = (AppHost.parse("uniswap.app") as AppHostResult.Host).host
    private val address = Address.of("0x" + "11".repeat(20))
    private val other = Address.of("0x" + "22".repeat(20))

    private fun TestScope.aliases() = FileDappAliases(file.toString(), StandardTestDispatcher(testScheduler))

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

    private fun text(): String = SystemFileSystem.source(file).buffered().use { it.readString() }

    @Test
    fun anAliasIsKeptReplacedAndRemovedAndSurvivesARestart() = runTest {
        val aliases = aliases()
        assertEquals(emptyMap(), aliases.observe().first())
        assertTrue(aliases.link(app, other))
        assertTrue(aliases.link(app, address))
        assertTrue(aliases.link(uniswap, other))
        assertEquals(mapOf(app to address, uniswap to other), aliases().observe().first())
        assertEquals(
            """{"version":1,"aliases":[{"site":"uniswap.app","address":"${address.hex}"},""" +
                """{"site":"uniswap.org","address":"${other.hex}"}]}""",
            text(),
        )
        assertTrue(aliases.unlink(uniswap))
        assertTrue(aliases.unlink(uniswap), "a site with no alias: nothing to remove")
        assertEquals(mapOf(app to address), aliases().observe().first())
    }

    @Test
    fun aFileThatCannotBeUsedIsNoAlias() = runTest {
        write("""{"version":2,"aliases":[]}""")
        assertEquals(emptyMap(), aliases().observe().first(), "a newer format")
        write("""{"version":1,"aliases":[{"site":"uniswap.app","address":"0x12"}]}""")
        assertEquals(emptyMap(), aliases().observe().first(), "damaged, and set aside")
    }

    @Test
    fun aSiteThisVersionCannotReadIsPassedOver() = runTest {
        write(
            """{"version":1,"aliases":[{"site":"not a site","address":"${other.hex}"},""" +
                """{"site":"uniswap.app","address":"${address.hex}"}]}""",
        )
        assertEquals(mapOf(app to address), aliases().observe().first())
    }
}
