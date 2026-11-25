package eth.serotine.domain.repository

import eth.serotine.domain.model.PrivateKey
import eth.serotine.domain.model.WalletPassword

/**
 * The key store's import of a bare private key typed in hex (owner ruling, 2026-11-25; docs/keys-and-signing.md,
 * *Importing a private key*). A port of its own beside [KeyStore], so that what does not import keys need not know of
 * it. Implemented by the key adapter, the one place the key may exist once it has left the import screen's path; what
 * comes back up is an address and a verdict, never a key.
 */
interface PrivateKeys {

    /**
     * Imports [key]: reads it as 64 hex digits after an optional `0x` (spaces around it ignored), checks that it is a
     * secp256k1 private key (not zero, below the curve's order), and seals it in an envelope of its own, filed under
     * its address, under [password] and this device's hardware secret where there is one, as an imported seed is
     * sealed. The envelope says it holds a bare key: it has no seed, so no further account and no per-app address can
     * come from it ([PerAppKeys.bareKeys]). The wallet password is set by the first import and checked by every later
     * one, as for [KeyStore.importMnemonic]. An envelope already stored for that address is kept when it opens under
     * [password]; one of a recovery phrase holds more than the key, and stays. The key exists only inside this call,
     * as bytes zeroed before it returns. [KeyImport.Invalid] carries a
     * [eth.serotine.domain.model.PrivateKeyProblem]. Never throws.
     */
    suspend fun importPrivateKey(key: PrivateKey, password: WalletPassword): KeyImport
}
