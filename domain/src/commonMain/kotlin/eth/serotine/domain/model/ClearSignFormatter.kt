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

    /** The label of the group it is shown under, or null. */
    val group: String?

    /**
     * A value: [shown] as the descriptor formats it, [raw] as decoded. [unlisted] is a token (of a
     * `tokenAmount`) or an NFT collection (of an `nftName`) that is not a built-in one: shown by its address,
     * never by the name its own contract gives it. [addressType] is set when an `addressName`'s address is
     * not of the kind the descriptor expects. [token] is the token or the NFT collection the value names (a
     * `tokenAmount` other than of the network's currency, a `tokenTicker`, an `nftName`), and [recipient] the
     * address an `addressName` shows (the sender's own where it stands for the sender); [nativeCurrency] is set
     * for an `amount`, and a `tokenAmount` of the network's currency: what a verified trace is held against (docs/clear-signing.md,
     * *Phase B*).
     */
    data class Value(
        override val label: String,
        val shown: String,
        val raw: String,
        val unlisted: Address? = null,
        override val group: String? = null,
        val addressType: AddressTypeMismatch? = null,
        val token: Address? = null,
        val recipient: Address? = null,
        val nativeCurrency: Boolean = false,
    ) : ClearSignedField

    /**
     * A call nested in this one, to [callee], its [data] as raw `0x` hex (shown whatever [result] says), and
     * what its own descriptor says of it. [grantsApproval] is read from its selector, covered or not.
     */
    data class Nested(
        override val label: String,
        val callee: Address,
        val data: String,
        val result: ClearSignResult,
        val grantsApproval: Boolean,
        override val group: String? = null,
    ) : ClearSignedField
}

/** An `addressName` whose address is [actual], where the descriptor expects one of [expected]. */
data class AddressTypeMismatch(val expected: Set<String>, val actual: String)

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

    /** Calls nested deeper than [ClearSignFormatter.MAX_DEPTH]: not read, so not described at all. */
    data object TooDeep : NotCoveredReason

    /** A group goes through its arrays item by item (`bundled`), and they are not of one length, at [label]. */
    data class UnequalArrays(val label: String?) : NotCoveredReason

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

    /** The wallet's name for [address] (a built-in token, a pinned contract), or null. */
    fun addressName(network: Network, address: Address): String?

    /**
     * The kind of account [address] is, as a verified read finds it (`eoa`, `contract`, …), or null when it
     * cannot be told: held against an `addressName`'s expected types.
     */
    suspend fun addressKind(network: Network, address: Address): String?

    /**
     * A built-in NFT collection's name (`.claude/rules/data-sources.md`, *An account's NFTs*), or null for any
     * other: a collection's own `name()` is never shown as if it were real.
     */
    fun builtInCollectionName(network: Network, collection: Address): String?

    /**
     * A call nested at [depth] to [callee] with [data], sent by [from] with [value] (null when the bytes do not
     * say: the parent calling out), formatted with the callee's own built-in descriptor after its binding check,
     * read as its parent's are, verified at a fresh head (A3, *A nested call*; A4, step 3).
     */
    suspend fun nested(network: Network, from: Address, callee: Address, value: UInt256?, data: ByteArray, depth: Int): ClearSignResult
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

    /**
     * [data] sent from [from] to [to] with [value] (null when unknown, as for a call a contract makes) on
     * [network], as [descriptor] (a calldata one) describes it. [delegatesToItself] is the deployment's
     * reviewed declaration that it runs a call into its own address by DELEGATECALL (a multicall), so that
     * such a nested call keeps the user's sender and value.
     */
    suspend fun formatCall(
        descriptor: ClearSigningDescriptor,
        network: Network,
        from: Address,
        to: Address,
        value: UInt256?,
        data: ByteArray,
        lookups: ClearSigningLookups,
        depth: Int = 0,
        delegatesToItself: Boolean = false,
    ): ClearSignResult {
        if (depth > MAX_DEPTH) return ClearSignResult.NotCovered(NotCoveredReason.TooDeep)
        if (data.size < SELECTOR) return ClearSignResult.NotCovered(NotCoveredReason.TooShort)
        val selector = "0x" + data.copyOf(SELECTOR).toHex()
        val format = descriptor.formatForSelector(selector)
            ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(selector))
        // A format with no function to decode by is the descriptor's to answer: not decoded, never a format missing.
        val function = format.function ?: return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        val arguments = AbiDecoder.decode(function.params, data.copyOfRange(SELECTOR, data.size))
            ?: return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        val values = ClearSigningValues(
            arguments,
            buildMap {
                put("from", AbiValue.AddressValue(from))
                put("to", AbiValue.AddressValue(to))
                value?.let { put("value", AbiValue.UintValue(it, 256)) }
                put("chainId", AbiValue.UintValue(UInt256.of(network.chainId), 256))
            },
        )
        return Rendering(descriptor, format, values, network, from, to, value, lookups, depth, delegatesToItself)
            .render(grantsApproval(data))
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
        if (descriptor.domainName != null && (data.domain["name"] as? TypedValue.Text)?.text != descriptor.domainName ||
            descriptor.domainVersion != null && (data.domain["version"] as? TypedValue.Text)?.text != descriptor.domainVersion
        ) {
            return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(null))
        }
        // The caller checks the data first; held here too, so a message never reaches the formatter unchecked.
        if (Eip712.encode(data) == null) return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
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
        return Rendering(descriptor, format, values, network, signer, verifier, null, lookups, 0, false)
            .render(grantsApproval(data))
    }

    private class Refusal(val reason: NotCoveredReason) : Exception()

    private class Rendering(
        val descriptor: ClearSigningDescriptor,
        val format: ClearSigningFormat,
        val values: ClearSigningValues,
        val network: Network,
        val from: Address,
        val to: Address,
        val callValue: UInt256?,
        val lookups: ClearSigningLookups,
        val depth: Int,
        val delegatesToItself: Boolean,
    ) {
        val fields = mutableListOf<ClearSignedField>()
        val shownAt = mutableMapOf<String, String?>()
        val covered = mutableSetOf<String>()
        var approval = false

        suspend fun render(grantsApproval: Boolean): ClearSignResult = try {
            approval = grantsApproval
            for (field in format.fields) field(field, group = null)
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
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fails closed: anything not rendered exactly is not covered, never a partial display.
            ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        }

        suspend fun field(field: ClearSigningField, group: String?) {
            if (field.children.isNotEmpty()) {
                val label = field.label ?: group
                when (field.iteration) {
                    ClearSigningField.Iteration.SEQUENTIAL -> field.children.forEach { field(it, label) }
                    ClearSigningField.Iteration.BUNDLED -> bundled(field, label)
                }
                return
            }
            val path = field.path ?: throw Refusal(NotCoveredReason.NotDecodable)
            for (at in reach(path)) one(field, path, at, group)
        }

        /** A `bundled` group: each child's first item, then each child's second, its arrays of one length. */
        suspend fun bundled(group: ClearSigningField, label: String?) {
            val children = group.children.map { child ->
                if (child.children.isNotEmpty()) throw Refusal(NotCoveredReason.NotDecodable)
                val path = child.path ?: throw Refusal(NotCoveredReason.NotDecodable)
                Triple(child, path, reach(path))
            }
            val length = children.map { it.third.size }.distinct().singleOrNull()
                ?: throw Refusal(NotCoveredReason.UnequalArrays(label))
            for (i in 0 until length) for ((child, path, reached) in children) one(child, path, reached[i], label)
        }

        fun reach(path: ClearSigningPath): List<Reached> =
            values.evaluate(path) ?: throw Refusal(NotCoveredReason.PathNotFound(path.toString()))

        suspend fun one(field: ClearSigningField, path: ClearSigningPath, at: Reached, group: String?) {
            // Visibility before anything else: a `mustMatch` holds whether or not the field has a label.
            val shown = when (val visibility = field.visibility) {
                Visibility.Shown -> true
                Visibility.Hidden -> false
                is Visibility.ShownUnless -> key(at.value) !in visibility.values
                is Visibility.MustMatch ->
                    if (key(at.value) in visibility.values) false
                    else throw Refusal(NotCoveredReason.ValueOutsideDescriptor(field.label ?: path.toString()))
            }
            if (!shown) {
                // Hidden or not, an approval inside a call is flagged by what the bytes are.
                if (field.format is FieldFormat.Calldata && grantsApproval((at.value as? AbiValue.BytesValue)?.bytes ?: ByteArray(0))) {
                    approval = true
                }
                return
            }
            val label = field.label ?: throw Refusal(NotCoveredReason.NotDecodable)
            val shownField = value(label, field.format ?: FieldFormat.Raw, at, path, group)
            fields += shownField
            if (shownField is ClearSignedField.Value) {
                // A placeholder stands for one value: a path shown more than once (an array's items) fills none.
                shownAt[path.toString()] = if (path.toString() in shownAt) null else shownField.shown
            }
            covered += at.location
        }

        suspend fun value(label: String, format: FieldFormat, at: Reached, path: ClearSigningPath, group: String?): ClearSignedField {
            val value = at.value
            val raw = raw(value)
            fun shown(
                text: String,
                unlisted: Address? = null,
                mismatch: AddressTypeMismatch? = null,
                token: Address? = null,
                recipient: Address? = null,
                native: Boolean = false,
            ) = ClearSignedField.Value(label, text, raw, unlisted, group, mismatch, token, recipient, native)
            // A parameter's path that walks the same array as the field is read at the field's item (ERC-7730,
            // *Array references in parameters*).
            fun parameter(parameter: ClearSigningPath): AbiValue = single(aligned(parameter, path, at.location))
            return when (format) {
                FieldFormat.Raw -> shown(raw)
                FieldFormat.Amount -> shown("${uint(value).formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}", native = true)
                is FieldFormat.TokenAmount -> {
                    val amount = uint(value)
                    // A token the descriptor names; one that names none is no amount this app can read.
                    val token = format.tokenPath?.let { address(parameter(it)) } ?: format.token
                        ?: throw Refusal(NotCoveredReason.NotDecodable)
                    val native = token in format.nativeCurrency
                    val known = if (native) ClearSigningToken(network.nativeSymbol, NATIVE_DECIMALS) else lookups.token(network, token)
                    val named = token.takeUnless { native }
                    if (known == null) {
                        return shown("${amount.toDecimal()} (raw units) of ${checksummed(token)}", unlisted = token, token = named)
                    }
                    val threshold = format.threshold
                    val text = if (threshold != null && amount >= threshold) format.message ?: "Unlimited" else amount.formatUnits(known.decimals)
                    shown("$text ${known.symbol}", token = named, native = native)
                }
                is FieldFormat.AddressName -> {
                    val address = address(value)
                    if (address in format.senderAddress) return shown(SENDER, recipient = from)
                    val checksum = checksummed(address)
                    val name = if (format.sources.isEmpty() || "local" in format.sources) lookups.addressName(network, address) else null
                    val mismatch = if (format.types.isEmpty()) {
                        null
                    } else {
                        // A kind that could not be read is said so, never taken for the one expected.
                        val kind = lookups.addressKind(network, address) ?: KIND_NOT_VERIFIED
                        kind.takeUnless { kindMatches(it, format.types) }?.let { AddressTypeMismatch(format.types, it) }
                    }
                    shown(name?.let { "$it ($checksum)" } ?: checksum, mismatch = mismatch, recipient = address)
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
                    val callee = address(parameter(format.calleePath))
                    val data = (value as? AbiValue.BytesValue)?.bytes ?: throw Refusal(NotCoveredReason.NotDecodable)
                    // Past the depth limit the bytes are not read, so nothing may be said of the whole call.
                    if (depth + 1 > MAX_DEPTH) throw Refusal(NotCoveredReason.TooDeep)
                    // Into its own address by DELEGATECALL (declared for the deployment, reviewed): the user's call,
                    // same sender and value. Any other is the contract calling out, its value not in these bytes.
                    val self = delegatesToItself && callee == to
                    val nested = lookups.nested(network, if (self) from else to, callee, if (self) callValue else null, data, depth + 1)
                    // Past the depth limit nothing is said of the whole call, up to the request's own.
                    if ((nested as? ClearSignResult.NotCovered)?.reason == NotCoveredReason.TooDeep) throw Refusal(NotCoveredReason.TooDeep)
                    val grants = grantsApproval(data) || (nested as? ClearSignResult.ClearSigned)?.grantsApproval == true
                    if (grants) approval = true
                    ClearSignedField.Nested(label, callee, raw, nested, grants, group)
                }
                is FieldFormat.NftName -> {
                    val collection = address(parameter(format.collectionPath))
                    val id = uint(value).toDecimal()
                    val name = lookups.builtInCollectionName(network, collection)
                    if (name == null) shown("#$id of ${checksummed(collection)}", unlisted = collection, token = collection)
                    else shown("$name #$id", token = collection)
                }
                FieldFormat.TokenTicker -> {
                    val token = address(value)
                    val known = lookups.token(network, token)
                    if (known == null) shown(checksummed(token), unlisted = token, token = token) else shown(known.symbol, token = token)
                }
            }
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

    /**
     * [parameter] with each `[]` it shares with [field]'s path, step for step from the start, replaced by the
     * index [location] (where the field's value was found) has there.
     */
    internal fun aligned(parameter: ClearSigningPath, field: ClearSigningPath, location: String): ClearSigningPath {
        if (parameter.container || field.container) return parameter
        val concrete = location.split('.')
        val steps = parameter.steps.mapIndexed { i, step ->
            val shared = i < field.steps.size && parameter.steps.subList(0, i) == field.steps.subList(0, i)
            if (shared && step == ClearSigningPath.Step.All && field.steps[i] == ClearSigningPath.Step.All) {
                ClearSigningPath.Step.Index(concrete[i].removeSurrounding("[", "]").toInt())
            } else {
                step
            }
        }
        return parameter.copy(steps = steps)
    }

    /**
     * Whether an account of [kind] (`eoa`, `wallet` for a smart account, `contract`) is one of [types]: a
     * contract may be the `token` or `collection` a descriptor expects, which its code alone does not tell.
     */
    internal fun kindMatches(kind: String, types: Set<String>): Boolean =
        kind in types || kind == "contract" && types.any { it == "token" || it == "collection" }

    /** Whether [data] calls a function that grants an approval ([APPROVAL_SELECTORS]), described or not. */
    fun grantsApproval(data: ByteArray): Boolean =
        data.size >= SELECTOR && "0x" + data.copyOf(SELECTOR).toHex() in APPROVAL_SELECTORS

    /**
     * Whether [data] is a message that grants an approval, described or not: a type of [APPROVAL_TYPES], or any
     * message Permit2 verifies, whose every signature lets someone move the signer's tokens.
     */
    fun grantsApproval(data: TypedData): Boolean = data.primaryType in APPROVAL_TYPES ||
        (data.domain["verifyingContract"] as? TypedValue.Text)?.text?.let(Address::parse) == TypedDataMeaning.PERMIT2

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

    /**
     * Selectors that grant an approval, flagged whatever a descriptor says: ERC-20 `approve` and
     * `increaseAllowance`, ERC-721/1155 `setApprovalForAll`, Permit2's `approve` and its two `permit`s, ERC-2612
     * `permit`, DAI's `permit`, and Aave's `approveDelegation` and `approvePositionManager` (rights over the
     * user's debt or positions).
     */
    val APPROVAL_SELECTORS = setOf(
        "0x095ea7b3", "0x39509351", "0xa22cb465", "0x87517c45", "0x2b67b570", "0x2a2d80d1", "0xd505accf",
        "0x8fcbaf0c", "0xc04a8a10", "0xb8caa7c5",
    )

    /** EIP-712 types that grant an approval: ERC-2612's and Permit2's permits. */
    val APPROVAL_TYPES = setOf(
        "Permit", "PermitSingle", "PermitBatch", "PermitTransferFrom", "PermitBatchTransferFrom",
        "PermitWitnessTransferFrom", "PermitBatchWitnessTransferFrom",
    )

    // Every brace escaped: Android's ICU refuses a bare `}` (its java.util.regex reference, API 10 on).
    private val PLACEHOLDER = Regex("\\{([^\\{\\}]*)\\}")

    /** The [AddressTypeMismatch.actual] of an address whose kind could not be read verified. */
    const val KIND_NOT_VERIFIED = "not verified"

    /** What an `addressName` shows for one of its `senderAddress` values, as the registry's vectors write it. */
    const val SENDER = "Sender"
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
