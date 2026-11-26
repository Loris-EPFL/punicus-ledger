package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiDecoder
import eth.serotine.domain.model.AbiFunction
import eth.serotine.domain.model.AbiValue
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.NamedParameter
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.NotNamedReason
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.VerifiedAbiResult
import eth.serotine.domain.model.VerifiedCode
import eth.serotine.domain.model.VerifiedContract
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.VerifiedAbiCatalog
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Names a call from its contract's verified source (docs/clear-signing.md, *Phase C*, *At runtime*), or says why
 * not. In order, failing closed at each step:
 *
 * 1. It speaks only where nothing else does: no hand-written decoder claims the call and no built-in descriptor
 *    has a format for it ([description] not covered for want of a descriptor or a format). A "not decoded" given
 *    with a reason (a changed contract, a value outside a descriptor) stands.
 * 2. The code at `to`, read verified at a fresh head ([codes]), hashes to a contract on the list ([catalog]) on
 *    that network; for an EIP-1167 minimal proxy, its implementation's code, read the same way, is the one
 *    pinned. Code that cannot be read within [READ_TIMEOUT_MS] is no lookup.
 * 3. The selector is one of that contract's functions, and the bytes are exactly the canonical ABI encoding of
 *    its parameters (A3's decoder).
 *
 * The names are its author's words and are shown as such. An approval is flagged by the call's selector, where a
 * `bytes` value starts with one's, or where the call, or a `bytes` value, is a MultiSend batch with one among its
 * entries; a delegate call inside it is flagged the same way, and by a parameter its source names `operation`.
 * A batch not read exactly counts as holding both: for a flag, failing closed is raising it.
 */
class NameVerifiedCall(
    private val catalog: VerifiedAbiCatalog,
    private val codes: ContractCodes,
) {
    /** [data] sent to [to] on [network], named, where [description] (phase A's answer for it) leaves room. */
    suspend fun call(network: Network, to: Address, data: ByteArray, description: ClearSignResult): VerifiedAbiResult {
        if (!leavesRoom(description)) return notNamed(NotNamedReason.SPOKEN_FOR)
        if (data.size < SELECTOR) return notNamed(NotNamedReason.NOT_DECODABLE)
        val found = withTimeoutOrNull(READ_TIMEOUT_MS) { contractAt(network, to) } ?: return notNamed(NotNamedReason.NOT_ON_LIST)
        val (contract, proxyOf) = found
        val function = contract.function("0x" + data.copyOf(SELECTOR).toHex())
            ?: return notNamed(NotNamedReason.NO_FUNCTION)
        val decoded = AbiDecoder.decode(function.params, data.copyOfRange(SELECTOR, data.size))
            ?: return notNamed(NotNamedReason.NOT_DECODABLE)
        val values = decoded.members.map { it.second }
        // The call may itself be a MultiSend batch: its own entries are read as one held in a `bytes` value is.
        val inBytes = holdsApproval(data) || values.any(::holdsApproval)
        val delegate = holdsDelegateCall(data) || values.any(::holdsDelegateCall) ||
            function.params.zip(values).any { (param, value) ->
                param.name == "operation" && (value as? AbiValue.UintValue)?.value?.let { it != UInt256.ZERO } == true
            }
        return VerifiedAbiResult.Named(
            contract = contract.name,
            function = function.name,
            parameters = function.params.zip(values) { param, value ->
                NamedParameter(param.name, AbiFunction.canonical(param.type), ClearSignFormatter.raw(value))
            },
            approval = ClearSignFormatter.grantsApproval(data) || inBytes,
            approvalInBytes = inBytes,
            delegateCall = delegate,
            proxyOf = proxyOf,
        )
    }

    /** Whether phase A leaves the call to phase C: not covered for want of a descriptor or of a format only. */
    private fun leavesRoom(description: ClearSignResult): Boolean {
        val reason = (description as? ClearSignResult.NotCovered)?.reason ?: return false
        return reason == NotCoveredReason.NoDescriptor || reason is NotCoveredReason.NoFormat
    }

    /**
     * The listed contract whose verified code is at [address] on [network], and the implementation where it is
     * reached through a proxy, whose own code is checked. The caller bounds both reads with one deadline.
     */
    private suspend fun contractAt(network: Network, address: Address): Pair<VerifiedContract, Address?>? {
        val code = codeHash(network, address) ?: return null
        return when (val found = catalog.byCodeHash(network, code)) {
            is VerifiedCode.Contract -> found.contract to null
            // The address alone is not the code: the implementation's own must be the pinned one.
            is VerifiedCode.Proxy ->
                (found.contract to found.implementation).takeIf { codeHash(network, found.implementation) == found.contract.codeHash }
            null -> null
        }
    }

    private suspend fun codeHash(network: Network, address: Address): String? =
        (codes.read(network, address, CodePin.Fixed) as? CodeReading.Read)?.reads?.codeHash?.lowercase()

    /**
     * Whether [value], or any `bytes` within it, is a call that grants an approval (A3's list), or a MultiSend
     * batch (`multiSend(bytes)`, its entries packed) with one among its entries.
     */
    private fun holdsApproval(value: AbiValue): Boolean = anyBytes(value, ::holdsApproval)

    /** Whether [value], or any `bytes` within it, is a MultiSend batch with a delegate call among its entries. */
    private fun holdsDelegateCall(value: AbiValue): Boolean = anyBytes(value, ::holdsDelegateCall)

    /**
     * Whether the call [bytes] grants an approval, or is a MultiSend batch with one. A batch that cannot be read
     * exactly (padding MultiSend skips, trailing calldata) still runs on chain: it counts as holding one.
     */
    private fun holdsApproval(bytes: ByteArray): Boolean = ClearSignFormatter.grantsApproval(bytes) ||
        isBatch(bytes) && (batch(bytes)?.any { ClearSignFormatter.grantsApproval(it.data) } ?: true)

    /** Whether the call [bytes] is a MultiSend batch with a delegate call among its entries, or one not read exactly. */
    private fun holdsDelegateCall(bytes: ByteArray): Boolean = isBatch(bytes) && (batch(bytes)?.any { it.operation != 0 } ?: true)

    private fun isBatch(bytes: ByteArray): Boolean =
        bytes.size >= SELECTOR && "0x" + bytes.copyOf(SELECTOR).toHex() == MULTI_SEND

    private fun anyBytes(value: AbiValue, test: (ByteArray) -> Boolean): Boolean = when (value) {
        is AbiValue.BytesValue -> !value.fixed && test(value.bytes)
        is AbiValue.TupleValue -> value.members.any { anyBytes(it.second, test) }
        is AbiValue.ArrayValue -> value.items.any { anyBytes(it, test) }
        else -> false
    }

    private class Entry(val operation: Int, val data: ByteArray)

    /**
     * The entries of [bytes] when it is a call of `multiSend(bytes)`: each packed as its operation (1 byte), its
     * target (20), its value (32), its data's length (32) and its data; null when it is not one, or not exactly.
     */
    private fun batch(bytes: ByteArray): List<Entry>? {
        if (!isBatch(bytes)) return null
        val packed = (AbiDecoder.decode(MULTI_SEND_PARAMS, bytes.copyOfRange(SELECTOR, bytes.size))
            ?.members?.singleOrNull()?.second as? AbiValue.BytesValue)?.bytes ?: return null
        val entries = mutableListOf<Entry>()
        var at = 0
        while (at < packed.size) {
            if (packed.size - at < ENTRY_HEAD) return null
            val length = UInt256.fromBigEndian(packed.copyOfRange(at + 53, at + ENTRY_HEAD)).toLongOrNull() ?: return null
            if (length > packed.size - at - ENTRY_HEAD) return null
            val start = at + ENTRY_HEAD
            entries += Entry(packed[at].toInt() and 0xff, packed.copyOfRange(start, start + length.toInt()))
            at = start + length.toInt()
        }
        return entries
    }

    private fun notNamed(reason: NotNamedReason) = VerifiedAbiResult.NotNamed(reason)

    private companion object {
        const val SELECTOR = 4

        /** `multiSend(bytes)`, MultiSend's and MultiSendCallOnly's one function. */
        const val MULTI_SEND = "0x8d80ff0a"
        val MULTI_SEND_PARAMS = AbiFunction.parse("multiSend(bytes transactions)")!!.params

        /** A packed entry's head: operation (1), target (20), value (32), data length (32). */
        const val ENTRY_HEAD = 85

        /** As long as a description's binding read waits (DescribeClearSigning). */
        const val READ_TIMEOUT_MS = 30_000L
    }
}
