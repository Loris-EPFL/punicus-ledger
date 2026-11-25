package eth.serotine.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One change to the wallet's keys and accounts at a time. The use cases that touch the key
 * store and the account list together ([ImportMnemonicAccount], [RemoveAccount]) each run
 * under it, so neither can interleave with the other: otherwise a removal could delete a key
 * an import has just stored for the same address, and leave an account whose key is gone.
 * The composition root makes one for the whole app and hands the same one to every such use
 * case; two instances would not serialize anything.
 *
 * [ended] counts the changes that have ended, so what reads the key store's envelopes without the unlock (the phrases,
 * the bare keys) reads them again after one: a change may replace an envelope and leave the account list as it was,
 * as importing the phrase of an account held as a bare key does.
 */
class WalletChanges {
    private val mutex = Mutex()
    private val count = MutableStateFlow(0L)

    /** How many changes have ended, each one counted once it has, however it ended. */
    val ended: StateFlow<Long> = count.asStateFlow()

    /** Runs [change] once no other change is running. */
    suspend fun <T> serialized(change: suspend () -> T): T = mutex.withLock {
        try {
            change()
        } finally {
            count.update { it + 1 }
        }
    }
}
