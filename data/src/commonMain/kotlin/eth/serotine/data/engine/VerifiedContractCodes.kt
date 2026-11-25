package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSigningCodeReads
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [ContractCodes] over the engine's verified reads, on [network], the one network its engine follows
 * (docs/clear-signing.md, A4): the code at an address proven against the light client's state
 * ([VerifiedChain.proveCode]) and hashed here; for a proxy pinned by a slot, that slot as a verified read at
 * the head ([VerifiedChain.storageAt]); for one pinned by a call, that call held to the block the code was
 * proven at ([VerifiedChain.callAt]); and the code the pointer names, proven in turn. Nothing is remembered:
 * a proxy can be upgraded between two reads, so each binding check reads anew. Asked only while the light
 * client can verify reads at its head, on [dispatcher]; anything not proven is no answer
 * ([ReadUnavailable.UNPROVEN]). Any other network is refused ([ReadUnavailable.NO_ENGINE]), and so is a
 * device without an engine ([chain] null).
 */
class VerifiedContractCodes internal constructor(
    private val network: Network,
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
) : ContractCodes {

    override suspend fun read(network: Network, address: Address, pin: CodePin): CodeReading {
        if (network != this.network) return CodeReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, CodeReading::Unavailable) { verified ->
            val code = verified.proveCode(address) ?: return@askVerified UNPROVEN
            val pointer = when (pin) {
                CodePin.Fixed -> null
                is CodePin.Slot -> verified.storageAt(address, pin.slot.removePrefix("0x").fromHex())
                    ?.let(::word) ?: return@askVerified UNPROVEN
                is CodePin.Call -> when (val outcome = verified.callAt(address, pin.selector.removePrefix("0x").fromHex(), code.blockNumber)) {
                    is CallOutcome.Returned -> word(outcome.hex).takeIf { outcome.blockNumber == code.blockNumber }
                    else -> null
                } ?: return@askVerified UNPROVEN
            }
            val implementationHash = pointer?.let { word ->
                val implementation = Address.of("0x" + word.takeLast(ADDRESS_HEX))
                val implementationCode = verified.proveCode(implementation) ?: return@askVerified UNPROVEN
                "0x" + codeHash(implementationCode.codeHex)
            }
            CodeReading.Read(ClearSigningCodeReads("0x" + codeHash(code.codeHex), pointer, implementationHash))
        }
    }

    /** A 32-byte word as `0x` + 64 lower-case hex digits, or null when it is not one. */
    private fun word(hex: String): String? =
        hex.removePrefix("0x").lowercase().takeIf { it.length == WORD_HEX && it.all { c -> c in HEX } }?.let { "0x$it" }

    private companion object {
        val UNPROVEN = CodeReading.Unavailable(ReadUnavailable.UNPROVEN)
        const val WORD_HEX = 64
        const val ADDRESS_HEX = 40
        const val HEX = "0123456789abcdef"
    }
}
