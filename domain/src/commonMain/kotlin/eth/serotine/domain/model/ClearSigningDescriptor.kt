package eth.serotine.domain.model

/**
 * A built-in ERC-7730 descriptor (docs/clear-signing.md, A3): how a contract's calls, or an EIP-712 message
 * type, are put in words. Its [owner] is the name its author wrote (`metadata.owner`), which the registry
 * does not prove; its words are its claim (`.claude/rules/trust.md`, *A clear-signed line*). Built in at
 * build time and pinned ([sha256] of the registry file it came from); never fetched. A plain value: the
 * adapter that reads the catalog makes it, references to the descriptor's constants already resolved.
 */
data class ClearSigningDescriptor(
    /** The registry file it came from, `registry/<entity>/calldata-….json`. */
    val source: String,
    val sha256: String,
    val owner: String,
    val kind: Kind,
    /** For an EIP-712 descriptor, the domain's `name` and `version` it was written for, when it names them. */
    val domainName: String?,
    val domainVersion: String?,
    /** The descriptor's enums by name: value (as decimal) to the name shown for it. */
    val enums: Map<String, Map<String, String>>,
    val formats: List<ClearSigningFormat>,
    /** The auditors whose attestation of this descriptor was verified (part 4), empty when there is none. */
    val attestedBy: List<Address> = emptyList(),
) {
    /** Calldata of a contract, or an EIP-712 message. */
    enum class Kind { CALLDATA, EIP712 }

    /** The format of the call [selector] (`0x` + 8 hex digits), or null. */
    fun formatForSelector(selector: String): ClearSigningFormat? =
        formats.firstOrNull { it.selector == selector.lowercase() }

    /** The format of the EIP-712 type whose type hash is [typeHash], or null. */
    fun formatForTypeHash(typeHash: String): ClearSigningFormat? =
        formats.firstOrNull { it.typeHash == typeHash.lowercase() }
}

/**
 * One format of a descriptor: a function ([function], [selector]) or an EIP-712 type ([encodeType],
 * [typeHash], [primaryType]), its [intent], an optional [interpolatedIntent] with `{path}` placeholders, and
 * the [fields] it shows, in order.
 */
data class ClearSigningFormat(
    val key: String,
    val function: AbiFunction?,
    val selector: String?,
    val encodeType: String?,
    val typeHash: String?,
    val primaryType: String?,
    val intent: String,
    val interpolatedIntent: String?,
    val fields: List<ClearSigningField>,
)

/**
 * One field of a format: a [path] into the call or the message with its [label], [format] and [visibility];
 * or a group of [children] without a path of its own, its [label] shown over them, its items gone through
 * as [iteration] says.
 */
data class ClearSigningField(
    val path: ClearSigningPath?,
    val label: String?,
    val format: FieldFormat?,
    val visibility: Visibility,
    val children: List<ClearSigningField> = emptyList(),
    val iteration: Iteration = Iteration.SEQUENTIAL,
) {
    /** How a group goes through its children's arrays (ERC-7730's `iteration`). */
    enum class Iteration {
        /** Each child over all its items, one child after the other. */
        SEQUENTIAL,

        /** Item by item: every child's first item, then every child's second; arrays of one length only. */
        BUNDLED,
    }
}

/** Whether a field is shown (ERC-7730's `visible`). A hidden field's value is still listed under "Other parameters". */
sealed interface Visibility {
    /** `always` or `optional`: shown. */
    data object Shown : Visibility

    /** `never`: not shown among the fields. */
    data object Hidden : Visibility

    /** `ifNotIn`: shown unless its value is one of [values] (decimal, or lower-case hex for bytes and addresses). */
    data class ShownUnless(val values: Set<String>) : Visibility

    /** `mustMatch`: not shown, and any value outside [values] makes the call not covered. */
    data class MustMatch(val values: Set<String>) : Visibility
}

/** How a field's value is shown (ERC-7730's field formats that the runtime implements; A3). */
sealed interface FieldFormat {
    /** As decoded: an integer in decimal, an address checksummed, bytes in hex, a string as it is. */
    data object Raw : FieldFormat

    /** An amount of the network's own currency, in wei. */
    data object Amount : FieldFormat

    /**
     * An amount of a token: the token at [tokenPath], or [token], or else the called contract; one of
     * [nativeCurrency] means the network's own currency. At or above [threshold] the amount is shown as
     * [message] ("Unlimited" when none).
     */
    data class TokenAmount(
        val tokenPath: ClearSigningPath?,
        val token: Address?,
        val nativeCurrency: Set<Address>,
        val threshold: UInt256?,
        val message: String?,
    ) : FieldFormat

    /**
     * An address, by the wallet's name for it where [sources] allow one (`local`: the wallet's own names;
     * ENS is not looked up); one of [senderAddress] stands for the sender. [types] is the kind of account the
     * descriptor expects (`eoa`, `contract`, …), which the lookup holds the address against.
     */
    data class AddressName(val senderAddress: Set<Address>, val types: Set<String>, val sources: Set<String>) : FieldFormat

    /** A point in time: a Unix timestamp, or a block height when [blockHeight]. */
    data class Date(val blockHeight: Boolean) : FieldFormat

    /** A length of time in seconds, shown `h:mm:ss`. */
    data object Duration : FieldFormat

    /** A number scaled by 10^-[decimals] and followed by [base]; with [prefix], an SI prefix (k, M, …) too. */
    data class Unit(val base: String, val decimals: Int, val prefix: Boolean) : FieldFormat

    /** A value named by the descriptor's enum [name]. */
    data class Enum(val name: String) : FieldFormat

    /** A chain id, by the network's name where it is one served here. */
    data object ChainId : FieldFormat

    /** A call nested in this one, its callee at [calleePath], formatted with its own descriptor. */
    data class Calldata(val calleePath: ClearSigningPath) : FieldFormat

    /** An NFT's id, with its collection at [collectionPath]. */
    data class NftName(val collectionPath: ClearSigningPath) : FieldFormat

    /** A token's address, shown by its symbol. */
    data object TokenTicker : FieldFormat
}

/**
 * A built-in deployment a descriptor applies to: [address] on [network], its runtime code pinned by
 * [codeHash], and how that code can change ([codePin]). A descriptor binds to it only while every pin
 * holds at the verified head (A4; [ClearSigningBinding]).
 */
data class ClearSigningDeployment(
    val network: Network,
    val name: String,
    val address: Address,
    val codeHash: String,
    val codePin: CodePin,
    val descriptors: List<ClearSigningDescriptor>,
)

/** How a deployment's code can change, as pinned at build time (docs/clear-signing.md, A1, step 4). */
sealed interface CodePin {
    /** It cannot: no proxy. */
    data object Fixed : CodePin

    /** A proxy whose [slot] holds [implementation], whose code hashes to [implementationCodeHash]. */
    data class Slot(val slot: String, val implementation: Address, val implementationCodeHash: String) : CodePin

    /** A proxy whose call [selector] returns [returns] (the implementation's word), its code [implementationCodeHash]. */
    data class Call(val selector: String, val returns: String, val implementationCodeHash: String) : CodePin
}

/** What the verified reads at one block found for a deployment, which [ClearSigningBinding] holds against its pins. */
data class ClearSigningCodeReads(
    /** The keccak256 of the code at the deployment's address. */
    val codeHash: String,
    /** The word its proxy pointer holds (the slot's or the call's), or null for a [CodePin.Fixed]. */
    val pointer: String?,
    /** The keccak256 of the code at the implementation the pointer names, or null. */
    val implementationCodeHash: String?,
)

/** The binding check of A4: a descriptor applies only while the code at the address is the code it was pinned for. */
object ClearSigningBinding {
    /** Null when every pin of [deployment] holds against [reads]; otherwise why it does not apply. */
    fun check(deployment: ClearSigningDeployment, reads: ClearSigningCodeReads): NotCoveredReason? {
        if (reads.codeHash.lowercase() != deployment.codeHash) return NotCoveredReason.ContractChanged
        return when (val pin = deployment.codePin) {
            CodePin.Fixed -> null
            is CodePin.Slot -> NotCoveredReason.ContractChanged.takeUnless {
                reads.pointer?.lowercase() == "0x" + "0".repeat(24) + pin.implementation.hex.removePrefix("0x") &&
                    reads.implementationCodeHash?.lowercase() == pin.implementationCodeHash
            }
            is CodePin.Call -> NotCoveredReason.ContractChanged.takeUnless {
                reads.pointer?.lowercase() == pin.returns && reads.implementationCodeHash?.lowercase() == pin.implementationCodeHash
            }
        }
    }
}
