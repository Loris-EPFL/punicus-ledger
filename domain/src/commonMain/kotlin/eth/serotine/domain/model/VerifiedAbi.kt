package eth.serotine.domain.model

/**
 * A contract on phase C's list (docs/clear-signing.md, *Phase C*): its source recompiled at build time to an
 * exact match of the code that hashes to [codeHash] on [network]. [name] is the source's own contract name and
 * [functions] its functions as compiled, their names and their parameters' its author's words: they name, they
 * do not describe.
 */
data class VerifiedContract(
    val network: Network,
    val name: String,
    val codeHash: String,
    val functions: List<AbiFunction>,
) {
    /** The function [selector] (`0x` and 8 lower-case hex digits) calls, or null when it is none of these. */
    fun function(selector: String): AbiFunction? = functions.firstOrNull { it.selector == selector }
}

/**
 * What the verified-ABI catalog holds for a code hash: a contract on the list, or an EIP-1167 minimal proxy to
 * one, which speaks for it only once its implementation's code is the one pinned (docs/clear-signing.md,
 * *Phase C*, *At build time*).
 */
sealed interface VerifiedCode {
    /** The code is [contract]'s own. */
    data class Contract(val contract: VerifiedContract) : VerifiedCode

    /** The code is a minimal proxy to [implementation], which must hold [implementation]'s pinned code. */
    data class Proxy(val implementation: Address, val contract: VerifiedContract) : VerifiedCode
}

/** A call named from its contract's verified source (docs/clear-signing.md, *Phase C*, *At runtime*), or why not. */
sealed interface VerifiedAbiResult {
    /**
     * Named: [function] of [contract], each parameter with its value as decoded from the exact bytes. [approval]
     * is set where the call grants an approval by its selector, or a `bytes` parameter starts with one's
     * ([approvalInBytes]): A3's flag, as far as an ABI lets it reach.
     */
    data class Named(
        val contract: String,
        val function: String,
        val parameters: List<NamedParameter>,
        val approval: Boolean,
        val approvalInBytes: Boolean,
    ) : VerifiedAbiResult

    /** Not named, and why: shown as before, raw and marked as not decoded. */
    data class NotNamed(val reason: NotNamedReason) : VerifiedAbiResult
}

/** A parameter of a [VerifiedAbiResult.Named]: its [name] (null where the source gives none), [type] and [raw] value. */
data class NamedParameter(val name: String?, val type: String, val raw: String)

/** Why a call is not named from a verified ABI. */
enum class NotNamedReason {
    /** A hand-written decoder or a built-in descriptor speaks for it, its "not decoded" with a reason included. */
    SPOKEN_FOR,

    /** No contract on the list has this code here, or no code could be read verified. */
    NOT_ON_LIST,

    /** The contract has no function of this selector. */
    NO_FUNCTION,

    /** The bytes are not exactly the canonical ABI encoding of the function's parameters. */
    NOT_DECODABLE,
}
