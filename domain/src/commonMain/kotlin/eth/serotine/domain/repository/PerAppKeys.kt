package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.PerAppPath
import eth.serotine.domain.model.WalletPassword

/**
 * The key store's per-app addresses (issue #252; docs/keys-and-signing.md, *Per-app addresses*): an address of its own
 * for each site (the registrable domain of a dApp's host, [AppHost.site]), derived from a stored phrase's seed at
 * `m/44'/60'/a'/b'/c'` ([eth.serotine.domain.model.AppIsolation.pathOf]). A port of its own beside [KeyStore], so that
 * what does not make per-app addresses need not know of them. No key leaves it: the address is derived and sealed
 * inside the adapter, never shown or exported.
 */
interface PerAppKeys {

    /**
     * The per-app address of [host]'s site, of the phrase that [from]'s envelope holds: that envelope opened with
     * [password] (which checks it), its seed checked against [from], the address derived and sealed in an envelope of
     * its own, filed under it and naming its path and its phrase. An envelope already stored for that address that
     * opens under [password] at that path is kept, not sealed again: deriving it again after the phrase's restore (D9),
     * or after a connection that never reached the account list, gives the same address. Only an account whose envelope
     * holds a seed can give one.
     *
     * [host] is used as it is; a collision with a host of another site of the same phrase
     * ([eth.serotine.domain.model.AppIsolation.collision]) is the caller's to refuse before asking, since only the
     * caller knows which hosts its accounts were made for. Never throws.
     */
    suspend fun derivePerApp(from: Address, host: AppHost, password: WalletPassword): PerAppDerivation
}

/** The outcome of [PerAppKeys.derivePerApp]. */
sealed interface PerAppDerivation {

    /**
     * [address], at [path] of the phrase whose first account is [phrase], is stored with [protection]; [steppedDown] as
     * for [KeyImport.Stored].
     */
    data class Stored(
        val address: Address,
        val path: PerAppPath,
        val phrase: Address,
        val protection: KeyProtection,
        val steppedDown: Boolean = false,
    ) : PerAppDerivation

    /** This device holds no key for the account to derive from. Nothing was stored. */
    data object NoKey : PerAppDerivation

    /** That is not the wallet password. Nothing was stored. */
    data object WrongPassword : PerAppDerivation

    /**
     * The key store cannot open the account's envelope on this device (it does not work here, its hardware key is gone,
     * or the envelope is damaged). Nothing was stored.
     */
    data object Unavailable : PerAppDerivation

    /** The new envelope could not be written. Nothing was stored. */
    data object NotSaved : PerAppDerivation

    /**
     * Not enough free memory to stretch the password at [step]: opening the account's envelope
     * ([MemoryStep.CHECK_PASSWORD]), or protecting the new one. Nothing was stored.
     */
    data class NotEnoughMemory(val step: MemoryStep) : PerAppDerivation
}
