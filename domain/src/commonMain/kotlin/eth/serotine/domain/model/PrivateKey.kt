package eth.serotine.domain.model

/**
 * An EOA's private key as the user types it in hex to add its account: the ONE type it travels as, which is the
 * condition of the private-key exception to the key-material rule (owner ruling, 2026-11-25; CLAUDE.md, *Trust*;
 * docs/keys-and-signing.md, *Importing a private key*). One way only: from the import screen's UiState, through its
 * ViewModel and [eth.serotine.domain.usecase.ImportPrivateKeyAccount], into the key adapter, and nowhere else, so a
 * search for this type finds the whole path. Nothing ever hands one back: the key is never shown or exported.
 *
 * It holds [text] exactly as typed, because the input field shows it back. Whether it is a key (64 hex digits, an
 * optional `0x`, a valid secp256k1 scalar) is the key adapter's decision: a [PrivateKey] is whatever was typed.
 * [digits] only counts, for the field's hint.
 *
 * [toString] is redacted and equality is identity, so neither a log line nor the generated `toString` of a state that
 * holds one can print the key.
 */
class PrivateKey(val text: String) {

    /**
     * The number of characters the key adapter reads as digits: [text] trimmed, without a leading `0x` or `0X`.
     * Counts characters, hex or not.
     */
    val digits: Int
        get() = text.trim().let { if (it.startsWith("0x") || it.startsWith("0X")) it.drop(2) else it }.length

    override fun toString(): String = "PrivateKey(redacted)"

    companion object {
        /** Nothing typed yet. */
        val EMPTY: PrivateKey = PrivateKey("")

        /** A secp256k1 private key's 32 bytes, in hex. */
        const val DIGITS: Int = 64
    }
}

/** Why typed text is not something the key adapter could import: a seed phrase's or a private key's problem. */
sealed interface ImportProblem

/** Why a [PrivateKey] is not a secp256k1 private key, as the key adapter reports it. */
sealed interface PrivateKeyProblem : ImportProblem {

    /** [count] digits after an optional `0x`, not [PrivateKey.DIGITS]. */
    data class Length(val count: Int) : PrivateKeyProblem

    /** The character at [position] (1-based, after an optional `0x`) is not a hex digit. */
    data class NotHex(val position: Int) : PrivateKeyProblem

    /** 64 hex digits, but zero or not below the curve's order: no key has that value. */
    data object OutOfRange : PrivateKeyProblem
}
