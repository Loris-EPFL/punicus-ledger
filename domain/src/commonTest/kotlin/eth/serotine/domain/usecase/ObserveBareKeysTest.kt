package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ObserveBareKeysTest {

    private val key = Address.of("0x" + "11".repeat(20))

    @Test
    fun theBareKeysAreReadFromTheKeyStore() = runTest {
        val keys = FakePerAppKeys(bare = setOf(key))

        assertEquals(setOf(key), ObserveBareKeys(ListAccountRepository(), keys, WalletChanges())().first())
    }

    @Test
    fun aChangeThatLeavesTheListAsItWasIsReadAgain() = runTest {
        // Importing the phrase of an account held as a bare key replaces its envelope; the list stays as it was.
        val keys = FakePerAppKeys(bare = setOf(key))
        val changes = WalletChanges()
        val seen = mutableListOf<Set<Address>>()
        val collecting = launch { ObserveBareKeys(ListAccountRepository(), keys, changes)().take(2).toList(seen) }
        runCurrent()

        changes.serialized { keys.bare = emptySet() }
        collecting.join()

        assertEquals(listOf(setOf(key), emptySet()), seen)
    }
}
