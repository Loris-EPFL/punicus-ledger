package eth.serotine.domain.model

/**
 * One frame of a verified trace (docs/clear-signing.md, *Phase B*): a call, a delegate call, a static call, a
 * creation or a self-destruct the transaction made at the verified head, as the engine's `callTracer` reports
 * it with its logs. [from] is the address whose code made it (for a frame inside a delegate call, the
 * delegating contract's), [to] the address it reached (for a creation, the new contract's; null when the
 * creation failed), [value] what it carried, null where the tracer gives none. [error] is set when the frame
 * failed, [revertReason] the reason its revert gives, where it gives one. [logs] are what this frame's code
 * emitted itself, [calls] the frames it opened, in order.
 */
data class TraceFrame(
    val type: Type,
    val from: Address,
    val to: Address?,
    val value: UInt256?,
    val error: String? = null,
    val revertReason: String? = null,
    val logs: List<TraceLog> = emptyList(),
    val calls: List<TraceFrame> = emptyList(),
) {
    /** How a frame was opened, as the `callTracer` names it. */
    enum class Type {
        CALL, STATICCALL, DELEGATECALL, CALLCODE, CREATE, CREATE2, SELFDESTRUCT;

        /** Whether the frame runs its code in its caller's context, with its caller's value: moves none itself. */
        val runsInCallersContext: Boolean get() = this == DELEGATECALL || this == CALLCODE

        /** Whether the frame's [TraceFrame.value] leaves [TraceFrame.from] for [TraceFrame.to]. */
        val movesValue: Boolean get() = this == CALL || this == CREATE || this == CREATE2 || this == SELFDESTRUCT

        companion object {
            /** The type the `callTracer` names [name], in any case, or null for one it does not. */
            fun parse(name: String): Type? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    /** Whether this frame failed: then nothing it or any frame under it did counts. */
    val failed: Boolean get() = error != null
}

/**
 * A log a [TraceFrame] emitted: by the contract at [address] (the frame's own, or for a delegate call its
 * caller's), its [topics] as 32-byte lower-case hex without `0x`, its [data] the same.
 */
data class TraceLog(val address: Address, val topics: List<String>, val data: String)
