package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PrivateKey
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.PrivateKeys
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Adds the account of a private key typed in hex to the wallet on the networks chosen, the key stored under the wallet
 * password: the "add account from a private key" capability (owner ruling, 2026-11-25). As [ImportMnemonicAccount]
 * does: the key store first, then the account list, as one step a cancelled caller does not cut in half, under the
 * app's [WalletChanges]; each network chosen gets an [AccountKind.EOA] held in the key store, and an address watched
 * elsewhere becomes held there too, since its key is here now. Such an account has no seed: no further account and no
 * per-app address comes from it ([eth.serotine.domain.repository.PerAppKeys.bareKeys]).
 *
 * The one use case the [PrivateKey] passes through (CLAUDE.md, *Trust*): it hands the key and the [WalletPassword] to
 * the key store and keeps nothing of either.
 */
class ImportPrivateKeyAccount(
    private val keys: PrivateKeys,
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]: an import never interleaves with a removal. */
    private val changes: WalletChanges,
) {
    /**
     * Adds the account on each of [networks], which are asked before the seal and never after it (docs/networks.md,
     * *Adding accounts*); at least one.
     */
    suspend operator fun invoke(key: PrivateKey, password: WalletPassword, networks: List<Network>): AccountImport {
        require(networks.isNotEmpty()) { "an account is added on at least one network" }
        return withContext(NonCancellable) {
            changes.serialized { keys.importPrivateKey(key, password).listedOn(networks.distinct(), accounts) }
        }
    }
}
