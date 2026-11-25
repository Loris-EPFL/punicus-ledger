package eth.serotine.domain.model

/**
 * What a built-in descriptor says a call or a message does (docs/clear-signing.md, A3), or why it says
 * nothing. A [ClearSigned] holds the descriptor's words ([intent], each field's label and formatting: its
 * claim) next to the values decoded here from the exact bytes (each field's [ClearSignedField.Value.raw]),
 * and every parameter no field shows ([otherParameters]): a descriptor orders and labels, it hides nothing.
 */
sealed interface ClearSignResult {
    /** Described: by [owner] (as the descriptor states it), [attestedBy] the auditors whose attestation was verified. */
    data class ClearSigned(
        val owner: String,
        val attestedBy: List<Address>,
        val intent: String,
        /** The intent with its placeholders filled in, or null when the descriptor has none or one could not be. */
        val interpolatedIntent: String?,
        val fields: List<ClearSignedField>,
        val otherParameters: List<OtherParameter>,
        /** Whether it grants an approval (`approve`, `setApprovalForAll`, a permit), here or in a nested call. */
        val grantsApproval: Boolean,
    ) : ClearSignResult

    /** Not described, and why: shown raw and marked as not decoded. */
    data class NotCovered(val reason: NotCoveredReason) : ClearSignResult
}

/** One field of a [ClearSignResult.ClearSigned]. */
sealed interface ClearSignedField {
    /** The descriptor's [label]. */
    val label: String

    /**
     * A value: [shown] as the descriptor formats it, [raw] as decoded. [notInTokenList] is the token of a
     * `tokenAmount` that is not in the built-in list, whose amount is then shown raw with its address.
     */
    data class Value(
        override val label: String,
        val shown: String,
        val raw: String,
        val notInTokenList: Address? = null,
    ) : ClearSignedField

    /** A call nested in this one, to [callee], with what its own descriptor says of it. */
    data class Nested(override val label: String, val callee: Address, val result: ClearSignResult) : ClearSignedField
}

/** A parameter no field shows: its [location] in the call or the message, and its [raw] value. */
data class OtherParameter(val location: String, val raw: String)

/** Why a call or a message is not clear-signed (A4: each shown with the call not decoded). */
sealed interface NotCoveredReason {
    /** No built-in descriptor covers the contract, or the message type, on this network. */
    data object NoDescriptor : NotCoveredReason

    /** The descriptor has no format for this function (its [selector]) or this message type. */
    data class NoFormat(val selector: String?) : NotCoveredReason

    /** A hand-written decoder claims this function or type; its answer stands. */
    data object ClaimedByAppDecoder : NotCoveredReason

    /** Fewer than four bytes of call data: no function to match. */
    data object TooShort : NotCoveredReason

    /** The bytes are not exactly the ABI encoding of the function's parameters, or a value is not of its field's type. */
    data object NotDecodable : NotCoveredReason

    /** A path of the descriptor reaches nothing in this call. */
    data class PathNotFound(val path: String) : NotCoveredReason

    /** A value outside what the descriptor was written for (`mustMatch`), at [label]. */
    data class ValueOutsideDescriptor(val label: String) : NotCoveredReason

    /** A value its enum has no name for, at [label]. */
    data class UnknownEnumValue(val label: String) : NotCoveredReason

    /** Calls nested deeper than [ClearSignFormatter.MAX_DEPTH]. */
    data object TooDeep : NotCoveredReason

    /** The contract's code, or its proxy's implementation, is not what this release pinned. */
    data object ContractChanged : NotCoveredReason

    /** A typed-data domain without a `chainId`, which another chain could verify too. */
    data object DomainWithoutChainId : NotCoveredReason

    /** A typed-data domain without a `verifyingContract`: no code to bind to. */
    data object DomainWithoutVerifyingContract : NotCoveredReason

    /** The domain names another chain than the request's. */
    data object OtherChain : NotCoveredReason

    /** The code could not be read at a fresh verified head, so the binding could not be checked. */
    data object CodeNotProven : NotCoveredReason
}

/** A token as the built-in list names it: what a `tokenAmount` or a `tokenTicker` shows. */
data class ClearSigningToken(val symbol: String, val decimals: Int)

/** What the formatter looks up while it formats; the use case supplies it (A3: lookups through ports). */
interface ClearSigningLookups {
    /** [token] on [network] as the built-in list pins it, or null: the list alone (`.claude/rules/trust.md`). */
    fun token(network: Network, token: Address): ClearSigningToken?

    /** The wallet's name for [address] (an account, a built-in token, a pinned contract), or null. */
    fun addressName(network: Network, address: Address): String?

    /** An NFT collection's `name()` read in a verified call, or null. */
    suspend fun collectionName(network: Network, collection: Address): String?

    /**
     * A call nested at [depth] to [callee] with [data] and [value], formatted with the callee's own built-in
     * descriptor after its binding check, at the same block as its parent (A3, *A nested call*).
     */
    suspend fun nested(network: Network, from: Address, callee: Address, value: UInt256, data: ByteArray, depth: Int): ClearSignResult
}

/**
 * Puts a call or a typed-data message in a descriptor's words (docs/clear-signing.md, A3): a pure function of
 * the descriptor, the request and the [ClearSigningLookups]. The binding to verified code (A4) is the
 * caller's: it passes a descriptor only once [ClearSigningBinding] holds. Fails closed: anything it cannot
 * render exactly is [ClearSignResult.NotCovered], never a partial display.
 */
object ClearSignFormatter {
    /** The deepest a nested call is formatted. */
    const val MAX_DEPTH = 3

    /** [data] sent from [from] to [to] with [value] on [network], as [descriptor] (a calldata one) describes it. */
    suspend fun formatCall(
        descriptor: ClearSigningDescriptor,
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
        lookups: ClearSigningLookups,
        depth: Int = 0,
    ): ClearSignResult {
        if (depth > MAX_DEPTH) return ClearSignResult.NotCovered(NotCoveredReason.TooDeep)
        if (data.size < SELECTOR) return ClearSignResult.NotCovered(NotCoveredReason.TooShort)
        val selector = "0x" + data.copyOf(SELECTOR).toHex()
        val format = descriptor.formatForSelector(selector)
            ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(selector))
        val function = format.function ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(selector))
        val arguments = AbiDecoder.decode(function.params, data.copyOfRange(SELECTOR, data.size))
            ?: return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        val values = ClearSigningValues(
            arguments,
            mapOf(
                "from" to AbiValue.AddressValue(from),
                "to" to AbiValue.AddressValue(to),
                "value" to AbiValue.UintValue(value, 256),
                "chainId" to AbiValue.UintValue(UInt256.of(network.chainId), 256),
            ),
        )
        val approval = selector in APPROVAL_SELECTORS
        return Rendering(descriptor, format, values, network, from, to, lookups, depth).render(approval)
    }

    /**
     * [data] (checked by [Eip712.encode] beforehand) as [descriptor] (an EIP-712 one) describes it, signed by
     * [signer] on [network]. Its domain must name the network and the contract the descriptor is bound to.
     */
    suspend fun formatTypedData(
        descriptor: ClearSigningDescriptor,
        network: Network,
        signer: Address,
        data: TypedData,
        lookups: ClearSigningLookups,
    ): ClearSignResult {
        val chainId = data.domain["chainId"] ?: return ClearSignResult.NotCovered(NotCoveredReason.DomainWithoutChainId)
        if (runCatching { Eip712.unsignedOf(chainId) }.getOrNull() != UInt256.of(network.chainId)) {
            return ClearSignResult.NotCovered(NotCoveredReason.OtherChain)
        }
        val verifier = (data.domain["verifyingContract"] as? TypedValue.Text)?.text?.let(Address::parse)
            ?: return ClearSignResult.NotCovered(NotCoveredReason.DomainWithoutVerifyingContract)
        val typeHash = "0x" + keccak256(Eip712.encodeType(data.types, data.primaryType).encodeToByteArray()).toHex()
        val format = descriptor.formatForTypeHash(typeHash)
            ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(null))
        if (descriptor.domainName != null && (data.domain["name"] as? TypedValue.Text)?.text != descriptor.domainName) {
            return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(null))
        }
        val message = runCatching { TypedMessage.struct(data.types, data.primaryType, data.message, 0) }.getOrNull()
            ?: return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        val values = ClearSigningValues(
            message,
            mapOf(
                "from" to AbiValue.AddressValue(signer),
                "to" to AbiValue.AddressValue(verifier),
                "chainId" to AbiValue.UintValue(UInt256.of(network.chainId), 256),
            ),
        )
        val approval = data.primaryType in APPROVAL_TYPES
        return Rendering(descriptor, format, values, network, signer, verifier, lookups, 0).render(approval)
    }

    private class Refusal(val reason: NotCoveredReason) : Exception()

    private class Rendering(
        val descriptor: ClearSigningDescriptor,
        val format: ClearSigningFormat,
        val values: ClearSigningValues,
        val network: Network,
        val from: Address,
        val to: Address,
        val lookups: ClearSigningLookups,
        val depth: Int,
    ) {
        val fields = mutableListOf<ClearSignedField>()
        val shownAt = mutableMapOf<String, String>()
        val covered = mutableSetOf<String>()
        var approval = false

        suspend fun render(grantsApproval: Boolean): ClearSignResult = try {
            approval = grantsApproval
            for (field in format.fields) field(field)
            ClearSignResult.ClearSigned(
                owner = descriptor.owner,
                attestedBy = descriptor.attestedBy,
                intent = format.intent,
                interpolatedIntent = format.interpolatedIntent?.let(::interpolate),
                fields = fields.toList(),
                otherParameters = values.leaves()
                    .filter { leaf -> covered.none { leaf.location == it || leaf.location.startsWith("$it.") } }
                    .map { OtherParameter(it.location, raw(it.value)) },
                grantsApproval = approval,
            )
        } catch (refusal: Refusal) {
            ClearSignResult.NotCovered(refusal.reason)
        }

        suspend fun field(field: ClearSigningField) {
            field.children.forEach { field(it) }
            val path = field.path ?: return
            val label = field.label ?: return
            val reached = values.evaluate(path) ?: throw Refusal(NotCoveredReason.PathNotFound(path.toString()))
            for (at in reached) {
                val shown = when (val visibility = field.visibility) {
                    Visibility.Shown -> true
                    Visibility.Hidden -> false
                    is Visibility.ShownUnless -> key(at.value) !in visibility.values
                    is Visibility.MustMatch ->
                        if (key(at.value) in visibility.values) false
                        else throw Refusal(NotCoveredReason.ValueOutsideDescriptor(label))
                }
                if (!shown) continue
                fields += value(label, field.format ?: FieldFormat.Raw, at.value)
                shownAt.putIfAbsent(path.toString(), (fields.last() as? ClearSignedField.Value)?.shown ?: "")
                covered += at.location
            }
        }

        suspend fun value(label: String, format: FieldFormat, value: AbiValue): ClearSignedField {
            val raw = raw(value)
            fun shown(text: String, unlisted: Address? = null) = ClearSignedField.Value(label, text, raw, unlisted)
            return when (format) {
                FieldFormat.Raw -> shown(raw)
                FieldFormat.Amount -> shown("${uint(value).formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}")
                is FieldFormat.TokenAmount -> tokenAmount(label, format, value, raw)
                is FieldFormat.AddressName -> {
                    val address = address(value).let { if (it in format.senderAddress) from else it }
                    val checksum = checksummed(address)
                    shown(lookups.addressName(network, address)?.let { "$it ($checksum)" } ?: checksum)
                }
                is FieldFormat.Date -> shown(
                    if (format.blockHeight) "block ${uint(value).toDecimal()}"
                    else utc(uint(value).toLongOrNull() ?: throw Refusal(NotCoveredReason.NotDecodable)),
                )
                FieldFormat.Duration -> shown(duration(uint(value).toLongOrNull() ?: throw Refusal(NotCoveredReason.NotDecodable)))
                is FieldFormat.Unit -> shown(unit(uint(value), format))
                is FieldFormat.Enum -> shown(
                    descriptor.enums[format.name]?.get(uint(value).toDecimal())
                        ?: throw Refusal(NotCoveredReason.UnknownEnumValue(label)),
                )
                FieldFormat.ChainId -> shown(
                    uint(value).toLongOrNull()?.let { id -> Network.entries.firstOrNull { it.chainId == id }?.displayName }
                        ?: uint(value).toDecimal(),
                )
                is FieldFormat.Calldata -> {
                    val callee = address(single(format.calleePath))
                    val data = (value as? AbiValue.BytesValue)?.bytes ?: throw Refusal(NotCoveredReason.NotDecodable)
                    val nested = if (depth + 1 > MAX_DEPTH) ClearSignResult.NotCovered(NotCoveredReason.TooDeep)
                    else lookups.nested(network, to, callee, UInt256.ZERO, data, depth + 1)
                    if ((nested as? ClearSignResult.ClearSigned)?.grantsApproval == true) approval = true
                    ClearSignedField.Nested(label, callee, nested)
                }
                is FieldFormat.NftName -> {
                    val collection = address(single(format.collectionPath))
                    val id = uint(value).toDecimal()
                    shown(lookups.collectionName(network, collection)?.let { "$it #$id" } ?: "#$id of ${checksummed(collection)}")
                }
                FieldFormat.TokenTicker -> shown(lookups.token(network, address(value))?.symbol ?: checksummed(address(value)))
            }
        }

        fun tokenAmount(label: String, format: FieldFormat.TokenAmount, value: AbiValue, raw: String): ClearSignedField.Value {
            val amount = uint(value)
            val token = format.tokenPath?.let { address(single(it)) } ?: format.token ?: to
            val native = token in format.nativeCurrency
            val known = if (native) ClearSigningToken(network.nativeSymbol, NATIVE_DECIMALS) else lookups.token(network, token)
                ?: return ClearSignedField.Value(label, "${amount.toDecimal()} (raw units) of ${checksummed(token)}", raw, token)
            val threshold = format.threshold
            val text = if (threshold != null && amount >= threshold) format.message ?: "Unlimited" else amount.formatUnits(known.decimals)
            return ClearSignedField.Value(label, "$text ${known.symbol}", raw)
        }

        fun single(path: ClearSigningPath): AbiValue =
            values.evaluate(path)?.singleOrNull()?.value ?: throw Refusal(NotCoveredReason.PathNotFound(path.toString()))

        fun interpolate(template: String): String? {
            var missing = false
            val text = PLACEHOLDER.replace(template) { match ->
                val path = ClearSigningPath.parse(match.groupValues[1])?.toString()
                shownAt[path] ?: run {
                    missing = true
                    ""
                }
            }
            return text.takeUnless { missing }
        }
    }

    /** A value as decoded: an integer in decimal, an address checksummed, bytes in hex, a string escaped. */
    fun raw(value: AbiValue): String = when (value) {
        is AbiValue.AddressValue -> checksummed(value.address)
        is AbiValue.BoolValue -> value.value.toString()
        is AbiValue.UintValue -> value.value.toDecimal()
        is AbiValue.IntValue -> (if (value.negative && !value.magnitude.isZero) "-" else "") + value.magnitude.toDecimal()
        is AbiValue.BytesValue -> "0x" + value.hex
        is AbiValue.StringValue -> ShownText.escaped(value.text)
        is AbiValue.TupleValue -> value.members.joinToString(", ", "(", ")") { raw(it.second) }
        is AbiValue.ArrayValue -> value.items.joinToString(", ", "[", "]") { raw(it) }
    }

    /** The key a visibility rule lists a value by: decimal for integers, lower-case hex for addresses and bytes. */
    fun key(value: AbiValue): String = when (value) {
        is AbiValue.AddressValue -> value.address.hex
        is AbiValue.BytesValue -> "0x" + value.hex
        is AbiValue.StringValue -> value.text
        else -> raw(value)
    }

    /** [seconds] since 1970 in UTC, as the registry's vectors write a date: `2026-05-28 20:26:40Z`. */
    fun utc(seconds: Long): String {
        val days = floorDiv(seconds, SECONDS_PER_DAY)
        val time = floorMod(seconds, SECONDS_PER_DAY)
        // Howard Hinnant's civil_from_days.
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "$year-${two(month)}-${two(day)} ${two(time / 3600)}:${two(time / 60 % 60)}:${two(time % 60)}Z"
    }

    /** [seconds] as `h:mm:ss`, the hours unbounded: `5951:00:00`. */
    fun duration(seconds: Long): String =
        "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"

    /**
     * [value] scaled by 10^-decimals and followed by its base; with a prefix, the largest SI prefix (k, M, …)
     * that leaves at least 1: 2500000 with no decimals and base "B" is "2.5MB".
     */
    private fun unit(value: UInt256, format: FieldFormat.Unit): String {
        var decimals = format.decimals
        var prefix = ""
        if (format.prefix) {
            for (si in SI_PREFIXES) {
                val next = decimals + 3
                if (next > MAX_DECIMALS || value < tenTo(next)) break
                decimals = next
                prefix = si
            }
        }
        return value.formatUnits(decimals) + prefix + format.base
    }

    private fun tenTo(exponent: Int): UInt256 {
        var out = UInt256.ONE
        repeat(exponent) { out *= UInt256.of(10) }
        return out
    }

    private fun floorDiv(a: Long, b: Long): Long = (a / b).let { if (a % b != 0L && (a < 0) != (b < 0)) it - 1 else it }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    private fun uint(value: AbiValue): UInt256 = (value as? AbiValue.UintValue)?.value ?: throw Refusal(NotCoveredReason.NotDecodable)

    private fun address(value: AbiValue): Address =
        (value as? AbiValue.AddressValue)?.address ?: throw Refusal(NotCoveredReason.NotDecodable)

    /** Selectors that grant an approval: ERC-20 `approve`/`increaseAllowance`, `setApprovalForAll`, Permit2's `approve`, `permit`. */
    val APPROVAL_SELECTORS = setOf("0x095ea7b3", "0x39509351", "0xa22cb465", "0x87517c45", "0xd505accf", "0x2b67b570", "0x2a2d80d1")

    /** EIP-712 types that grant an approval: ERC-2612's and Permit2's permits. */
    val APPROVAL_TYPES = setOf("Permit", "PermitSingle", "PermitBatch", "PermitTransferFrom", "PermitBatchTransferFrom")

    private val PLACEHOLDER = Regex("\\{([^{}]*)}")
    private val SI_PREFIXES = listOf("k", "M", "G", "T", "P", "E")
    private const val MAX_DECIMALS = 77
    private const val SELECTOR = 4
    private const val NATIVE_DECIMALS = 18
    private const val SECONDS_PER_DAY = 86_400L
}

/** An EIP-712 message read into an [AbiValue] tree, by the types the dApp sent and [Eip712.encode] checked. */
internal object TypedMessage {
    fun struct(types: Map<String, List<TypedField>>, type: String, fields: Map<String, TypedValue>, depth: Int): AbiValue.TupleValue {
        require(depth <= AbiDecoder.MAX_DEPTH)
        return AbiValue.TupleValue(types.getValue(type).map { it.name to value(types, it.type, fields.getValue(it.name), depth + 1) })
    }

    private fun value(types: Map<String, List<TypedField>>, type: String, value: TypedValue, depth: Int): AbiValue {
        if (type.endsWith("]")) {
            val element = type.substring(0, type.lastIndexOf('['))
            return AbiValue.ArrayValue((value as TypedValue.Items).items.map { value(types, element, it, depth + 1) })
        }
        if (type in types) return struct(types, type, (value as TypedValue.Struct).fields, depth)
        return when {
            type == "address" -> AbiValue.AddressValue(Address.of((value as TypedValue.Text).text))
            type == "bool" -> AbiValue.BoolValue((value as TypedValue.Bool).value)
            type == "string" -> AbiValue.StringValue((value as TypedValue.Text).text)
            type == "bytes" -> AbiValue.BytesValue((value as TypedValue.Text).text.removePrefix("0x").lowercase(), fixed = false)
            type.startsWith("bytes") -> AbiValue.BytesValue((value as TypedValue.Text).text.removePrefix("0x").lowercase(), fixed = true)
            type.startsWith("uint") -> AbiValue.UintValue(Eip712.unsignedOf(value), type.removePrefix("uint").ifEmpty { "256" }.toInt())
            type.startsWith("int") -> {
                val text = when (value) {
                    is TypedValue.Number -> value.digits
                    is TypedValue.Text -> value.text
                    else -> throw IllegalArgumentException("not an integer")
                }
                val negative = text.startsWith("-")
                AbiValue.IntValue(negative, Eip712.unsignedOf(TypedValue.Text(text.removePrefix("-"))), type.removePrefix("int").ifEmpty { "256" }.toInt())
            }
            else -> throw IllegalArgumentException("unknown type $type")
        }
    }
}
