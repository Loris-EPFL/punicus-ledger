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
 * The names are its author's words and are shown as such; an approval is flagged by the call's selector, or where
 * a `bytes` value starts with one's, the only way an ABI lets A3's flag reach inside a call.
 */
class NameVerifiedCall(
    private val catalog: VerifiedAbiCatalog,
    private val codes: ContractCodes,
) {
    /** [data] sent to [to] on [network], named, where [description] (phase A's answer for it) leaves room. */
    suspend fun call(network: Network, to: Address, data: ByteArray, description: ClearSignResult): VerifiedAbiResult {
        if (!leavesRoom(description)) return notNamed(NotNamedReason.SPOKEN_FOR)
        if (data.size < SELECTOR) return notNamed(NotNamedReason.NOT_DECODABLE)
        val contract = contractAt(network, to) ?: return notNamed(NotNamedReason.NOT_ON_LIST)
        val function = contract.function("0x" + data.copyOf(SELECTOR).toHex())
            ?: return notNamed(NotNamedReason.NO_FUNCTION)
        val decoded = AbiDecoder.decode(function.params, data.copyOfRange(SELECTOR, data.size))
            ?: return notNamed(NotNamedReason.NOT_DECODABLE)
        val values = decoded.members.map { it.second }
        val inBytes = values.any(::startsWithApproval)
        return VerifiedAbiResult.Named(
            contract = contract.name,
            function = function.name,
            parameters = function.params.zip(values) { param, value ->
                NamedParameter(param.name, AbiFunction.canonical(param.type), ClearSignFormatter.raw(value))
            },
            approval = ClearSignFormatter.grantsApproval(data) || inBytes,
            approvalInBytes = inBytes,
        )
    }

    /** Whether phase A leaves the call to phase C: not covered for want of a descriptor or of a format only. */
    private fun leavesRoom(description: ClearSignResult): Boolean {
        val reason = (description as? ClearSignResult.NotCovered)?.reason ?: return false
        return reason == NotCoveredReason.NoDescriptor || reason is NotCoveredReason.NoFormat
    }

    /** The listed contract whose verified code is at [address] on [network], its proxy's implementation checked. */
    private suspend fun contractAt(network: Network, address: Address): VerifiedContract? {
        val code = codeHash(network, address) ?: return null
        return when (val found = catalog.byCodeHash(network, code)) {
            is VerifiedCode.Contract -> found.contract
            // The address alone is not the code: the implementation's own must be the pinned one.
            is VerifiedCode.Proxy -> found.contract.takeIf { codeHash(network, found.implementation) == it.codeHash }
            null -> null
        }
    }

    private suspend fun codeHash(network: Network, address: Address): String? =
        (withTimeoutOrNull(READ_TIMEOUT_MS) { codes.read(network, address, CodePin.Fixed) } as? CodeReading.Read)
            ?.reads?.codeHash

    /** Whether [value], or any `bytes` within it, starts with a selector that grants an approval (A3's list). */
    private fun startsWithApproval(value: AbiValue): Boolean = when (value) {
        is AbiValue.BytesValue -> !value.fixed && value.hex.length >= SELECTOR * 2 &&
            "0x" + value.hex.substring(0, SELECTOR * 2) in ClearSignFormatter.APPROVAL_SELECTORS
        is AbiValue.TupleValue -> value.members.any { startsWithApproval(it.second) }
        is AbiValue.ArrayValue -> value.items.any(::startsWithApproval)
        else -> false
    }

    private fun notNamed(reason: NotNamedReason) = VerifiedAbiResult.NotNamed(reason)

    private companion object {
        const val SELECTOR = 4

        /** As long as a description's binding read waits (DescribeClearSigning). */
        const val READ_TIMEOUT_MS = 30_000L
    }
}
