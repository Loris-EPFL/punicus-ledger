package eth.serotine.ui.mnemonicimport

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.PrivateKey
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.NO_NETWORK_TICKED
import eth.serotine.ui.components.NetworkChoice
import eth.serotine.ui.components.PasswordStep

/** What the import screen adds an account from: a seed phrase, or a private key in hex. */
enum class ImportSource(val label: String) {
    PHRASE("Seed phrase"),
    KEY("Private key"),
}

/**
 * The one secret being typed on the import screen: the words, or a key, never both, so that a state cannot hold the
 * one asked for and keep the other.
 */
sealed interface Typed {
    /** Which choice this is. */
    val source: ImportSource

    /** The words of a seed phrase, as typed. */
    data class Phrase(val phrase: Mnemonic = Mnemonic.EMPTY) : Typed {
        override val source: ImportSource get() = ImportSource.PHRASE
    }

    /** A private key in hex, as typed. */
    data class Key(val key: PrivateKey = PrivateKey.EMPTY) : Typed {
        override val source: ImportSource get() = ImportSource.KEY
    }

    companion object {
        /** Nothing typed yet for [source]. */
        fun empty(source: ImportSource): Typed = when (source) {
            ImportSource.PHRASE -> Phrase()
            ImportSource.KEY -> Key()
        }
    }
}

/**
 * Everything the import screen renders: the dedicated UiState of the mnemonic exception (owner ruling, 2026-10-15)
 * and of the private-key exception (owner ruling, 2026-11-25; CLAUDE.md, *Trust*). It is the only UiState that ever
 * holds a [Mnemonic], a [PrivateKey] or a [WalletPassword], and its ViewModel clears them when the screen is not shown.
 */
sealed interface MnemonicImportUiState {

    /**
     * The words or the key, and the password, being typed: [typed] is the one asked for, [source] which it is.
     * [step] is which password the key store wants.
     * [error] is why the last attempt was refused, about the words, the key or the import as a whole,
     * and [passwordError] when it was about the password; both stay until something is
     * edited. [importing] is true while an attempt runs. [networks] are the networks to add the
     * account to, the last thing asked, none ticked until the user ticks one (docs/networks.md, N9).
     */
    data class Entering(
        val typed: Typed = Typed.Phrase(),
        val password: WalletPassword = WalletPassword.EMPTY,
        val repeat: WalletPassword = WalletPassword.EMPTY,
        val step: PasswordStep = PasswordStep.Checking,
        val importing: Boolean = false,
        val error: String? = null,
        val passwordError: String? = null,
        val networks: List<NetworkChoice> = emptyList(),
    ) : MnemonicImportUiState {

        /** The words being typed: [phrase] asked for, the rest as for the primary constructor. */
        constructor(
            phrase: Mnemonic,
            password: WalletPassword = WalletPassword.EMPTY,
            repeat: WalletPassword = WalletPassword.EMPTY,
            step: PasswordStep = PasswordStep.Checking,
            importing: Boolean = false,
            error: String? = null,
            passwordError: String? = null,
            networks: List<NetworkChoice> = emptyList(),
        ) : this(Typed.Phrase(phrase), password, repeat, step, importing, error, passwordError, networks)

        /** Which secret is asked for. */
        val source: ImportSource get() = typed.source

        /** The words typed; empty while a key is asked for. */
        val phrase: Mnemonic get() = (typed as? Typed.Phrase)?.phrase ?: Mnemonic.EMPTY

        /** The key typed; empty while the words are asked for. */
        val key: PrivateKey get() = (typed as? Typed.Key)?.key ?: PrivateKey.EMPTY

        /** The number of words, counted as the key adapter will; once per state, not per read. */
        val wordCount: Int = phrase.words.size

        /** The key's digits, counted as the key adapter will read them. */
        val keyDigits: Int = key.digits

        /** What is wrong with the password, display-ready, or null. */
        val passwordHint: String?
            get() = passwordError ?: when {
                step !is PasswordStep.New -> null
                password.text.isNotEmpty() && !password.isLongEnough ->
                    "At least ${WalletPassword.MIN_LENGTH} characters"
                repeat.text.isNotEmpty() && repeat.text != password.text -> "The two passwords differ"
                else -> null
            }

        /**
         * True for a BIP-39 word count, or a key of [PrivateKey.DIGITS] digits, and a usable password, while no attempt
         * runs.
         */
        val inputReady: Boolean
            get() = !importing && secretReady && when (step) {
                PasswordStep.Checking, PasswordStep.Unavailable -> false
                is PasswordStep.New -> password.isLongEnough && repeat.text == password.text
                is PasswordStep.Existing -> password.text.isNotEmpty()
            }

        /**
         * Why the import cannot be done yet with the words and the password ready: no network
         * ticked, which the greyed-out button says on a tap (owner ruling, 2026-10-18, #55); null
         * when one is.
         */
        val networkReason: String?
            get() = NO_NETWORK_TICKED.takeIf { networks.none { it.chosen && it.on } }

        private val secretReady: Boolean
            get() = when (typed) {
                is Typed.Phrase -> wordCount in Mnemonic.WORD_COUNTS
                is Typed.Key -> keyDigits == PrivateKey.DIGITS
            }

        /** The words or the key, the password and a network ticked, while no attempt runs. */
        val canImport: Boolean
            get() = inputReady && networkReason == null
    }

    /**
     * The account named [label] was added. The words or the key, and the password, are gone; the screen
     * closes, unless there is a [note] to read first (its key protected at a lighter cost than
     * this device's own), which stays until the user is done.
     */
    data class Imported(val label: String, val note: String? = null) : MnemonicImportUiState
}
