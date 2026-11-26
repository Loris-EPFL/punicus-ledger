package eth.serotine.data.settings

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FileFirstRunNotice] against the [eth.serotine.domain.repository.FirstRunNotice] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileFirstRunNoticeTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-first-run-${Random.nextLong().toULong()}")
    private val file = Path(directory, "first-run.json")

    private fun TestScope.notice() = FileFirstRunNotice(file.toString(), StandardTestDispatcher(testScheduler))

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
    fun notDismissedOnAFirstStartAndDismissedForGoodOnceOk() = runTest {
        assertFalse(notice().observe().first(), "no file: a first start")

        assertTrue(notice().dismiss())

        assertTrue(notice().observe().first(), "after a restart too")
    }

    @Test
    fun aDamagedFileCountsAsDismissed() = runTest {
        write("{")

        assertTrue(notice().observe().first(), "never shown on a guess")
        assertTrue(notice().observe().first(), "nor at the next start, which finds dismissed in its place")
    }

    @Test
    fun aNewerFormatCountsAsDismissed() = runTest {
        write("""{"version":2,"dismissed":false}""")

        assertTrue(notice().observe().first())
    }
}
