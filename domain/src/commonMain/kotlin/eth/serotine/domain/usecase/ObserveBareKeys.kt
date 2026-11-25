package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.PerAppKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The accounts held here whose key was imported bare, in hex ([PerAppKeys.bareKeys]; owner ruling, 2026-11-25): no
 * seed, so New account from the same phrase is greyed out for them, a tap saying why. Read again whenever the account
 * list changes, and after every change of the wallet's keys ([WalletChanges.ended]): importing the phrase of an account
 * held as a bare key replaces its envelope and leaves the list as it was. Never with the unlock. Empty when the
 * envelopes cannot be read: the action is then offered, and the key store refuses it.
 */
class ObserveBareKeys(
    private val accounts: AccountRepository,
    private val keys: PerAppKeys,
    /** The app's one [WalletChanges]. */
    private val changes: WalletChanges,
) {
    operator fun invoke(): Flow<Set<Address>> =
        combine(accounts.observeAccounts(), changes.ended) { _, _ -> }.map { keys.bareKeys() }.distinctUntilChanged()
}
