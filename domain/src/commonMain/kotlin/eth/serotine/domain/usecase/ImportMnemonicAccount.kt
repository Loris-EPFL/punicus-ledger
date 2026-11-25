package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.ImportProblem
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.MemoryStep
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Adds the first account of a BIP-39 mnemonic to the wallet on the networks chosen, its seed stored
 * under the wallet password: the "add account from a seed phrase" capability. The key store first,
 * then the account list, so an account never appears whose key was not stored; and the two
 * as one step that a cancelled caller does not cut in half, since a stored key whose
 * account was never added would set the wallet password behind the user's back. The key is
 * stored once, and each network chosen gets an account of its own (docs/networks.md, N8 and N9):
 * an [AccountKind.EOA] held in the key store, all named "Account N", N one more than the number of
 * accounts, raised past any such name already taken. An address watched on some networks becomes
 * held there, chosen or not, since its key is here now. One of the two use cases the [Mnemonic] passes
 * through (CLAUDE.md, *Trust*): it hands the words and the [WalletPassword] to the key store
 * and keeps nothing of either.
 */
class ImportMnemonicAccount(
    private val keys: KeyStore,
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]: an import never interleaves with a removal. */
    private val changes: WalletChanges,
) {
    /**
     * Adds the account on each of [networks], which are asked before the seal and never after it
     * (docs/networks.md, *Adding accounts*); at least one.
     */
    suspend operator fun invoke(mnemonic: Mnemonic, password: WalletPassword, networks: List<Network>): AccountImport {
        require(networks.isNotEmpty()) { "an account is added on at least one network" }
        return withContext(NonCancellable) {
            changes.serialized { keys.importMnemonic(mnemonic, password).listedOn(networks.distinct(), accounts) }
        }
    }
}

/**
 * What a key store's import became once its account is listed: a stored key's address held on each of [networks],
 * and wherever else it is watched ([addHeld]); any other answer said as it is, with nothing listed. The step
 * [ImportMnemonicAccount] and [ImportPrivateKeyAccount] share, inside their [WalletChanges].
 */
internal suspend fun KeyImport.listedOn(networks: List<Network>, accounts: AccountRepository): AccountImport =
    when (this) {
        is KeyImport.Invalid -> AccountImport.Invalid(problem)
        KeyImport.WrongPassword -> AccountImport.WrongPassword
        KeyImport.PasswordTooShort -> AccountImport.PasswordTooShort
        KeyImport.Unavailable -> AccountImport.Unavailable
        KeyImport.NotSaved -> AccountImport.KeyNotSaved
        is KeyImport.NotEnoughMemory -> AccountImport.NotEnoughMemory(step)
        is KeyImport.Stored -> when (val added = accounts.addHeld(address, networks)) {
            is HeldAdd.Added -> AccountImport.Added(added.account, protection, steppedDown)
            is HeldAdd.AlreadyHeld -> AccountImport.AlreadyAdded(added.account, steppedDown)
            is HeldAdd.NowHeld -> AccountImport.KeyAdded(added.account, steppedDown)
            HeldAdd.ListNotSaved -> AccountImport.ListNotSaved
        }
    }

/** The outcome of [ImportMnemonicAccount] and [ImportPrivateKeyAccount]. */
sealed interface AccountImport {

    /**
     * [account] is new and in the wallet, its seed stored with [protection]; [steppedDown] when
     * its envelope was sealed at a lighter password cost than this device's own
     * ([KeyImport.Stored.steppedDown]), which the user is told.
     */
    data class Added(val account: Account, val protection: KeyProtection, val steppedDown: Boolean = false) :
        AccountImport

    /**
     * The words' first account, or the key's account, was in the wallet already, as [account]; its seed or key is
     * stored now (an account added before keys were kept gets its key this way); [steppedDown] as for [Added].
     */
    data class AlreadyAdded(val account: Account, val steppedDown: Boolean = false) : AccountImport

    /**
     * The words' first account, or the key's, was in the wallet as a watched address, [account]; its key is
     * stored now, and the account is held in the key store from here on; [steppedDown] as for
     * [Added].
     */
    data class KeyAdded(val account: Account, val steppedDown: Boolean = false) : AccountImport

    /**
     * The words are not a BIP-39 mnemonic, or the key not a private key, for the reason [problem] gives; nothing
     * changed.
     */
    data class Invalid(val problem: ImportProblem) : AccountImport

    /** A wallet password is set, and this is not it; nothing changed. */
    data object WrongPassword : AccountImport

    /** This import would set the wallet password, and it is too short; nothing changed. */
    data object PasswordTooShort : AccountImport

    /** The key store cannot run on this device; nothing changed. */
    data object Unavailable : AccountImport

    /** The seed, or the key, could not be stored; nothing changed. */
    data object KeyNotSaved : AccountImport

    /** Not enough free memory at [step], to check the password or to protect the seed; nothing changed. */
    data class NotEnoughMemory(val step: MemoryStep) : AccountImport

    /**
     * The seed, or the key, is stored (and with it the wallet password, if this import set it), but the
     * account list could not be read or saved, so the account is not in it. Importing the
     * same words, or the same key, again adds it.
     */
    data object ListNotSaved : AccountImport
}
