package eth.serotine.domain.model

/**
 * A member of a Gnosis Pay account's Delay modifier ([delay]) added or removed by the account's
 * Safe ([safe]) itself, queued through that Delay and executed after its cooldown like a transfer
 * (owner ruling, 2026-11-18; docs/gnosis-pay.md, *Adding and removing a member*): a [DelayCall] of
 * the Safe to its own Delay, which only its owner, the Safe, may make. [Kind.ADD] is
 * `enableModule(member)`; [Kind.REMOVE] is `disableModule(previous, member)`, [previous] the member
 * before [member] in the Delay's linked list (the sentinel `0x…01` for the first).
 *
 * Only [add] and [remove] plan one, against the Delay's members as read verified, and [of] restores
 * one recorded before; each refuses what the ruling refuses. Never a call to any other contract:
 * [to] is always the Delay, with no value.
 */
@ConsistentCopyVisibility
data class DelayMemberChange private constructor(
    override val safe: Address,
    override val delay: Address,
    val kind: Kind,
    val member: Address,
    val previous: Address?,
) : DelayCall {

    /** Which of the two calls it is. */
    enum class Kind {
        /** `enableModule(member)`: a new member, who can queue anything for the Safe. */
        ADD,

        /** `disableModule(previous, member)`: a member no longer. */
        REMOVE,
    }

    /** The Safe calls its own Delay. */
    override val to: Address get() = delay

    /** No value. */
    override val value: UInt256 get() = UInt256.ZERO

    /** `enableModule(member)` or `disableModule(previous, member)`. */
    override val data: ByteArray
        get() = when (kind) {
            Kind.ADD -> ENABLE_MODULE + AbiWords.word(member)
            Kind.REMOVE -> DISABLE_MODULE + AbiWords.word(requireNotNull(previous)) + AbiWords.word(member)
        }

    override val hash: Digest = DelayCalls.hash(delay, UInt256.ZERO, data)

    /**
     * This change planned again against [members], the Delay's members as read now, in its own
     * order: [DelayMemberChangePlan.Planned] with an equal change when it still holds as it was
     * planned; otherwise the refusal it now meets, or (for a removal) a change with another
     * [previous]. The Safe's other modules are not checked again: they were when it was planned.
     */
    fun against(members: List<Address>): DelayMemberChangePlan = when (kind) {
        Kind.ADD -> add(safe, delay, emptyList(), members, member)
        Kind.REMOVE -> remove(safe, delay, members, member)
    }

    companion object {
        /** The head and tail of the Delay's linked list of members: the entry before the first. */
        val SENTINEL: Address = Address.of("0x" + "0".repeat(39) + "1")

        /** `enableModule(address)`: 0x610b5925. */
        val ENABLE_MODULE: ByteArray = AbiWords.selector("enableModule(address)")

        /** `disableModule(address,address)`: 0xe009cfde. */
        val DISABLE_MODULE: ByteArray = AbiWords.selector("disableModule(address,address)")

        /**
         * [member] added to the Delay at [delay] of the Safe at [safe], whose enabled modules are
         * [safeModules] and whose Delay's members are [members]; or why it is refused: an address no
         * key holds (the zero address, and `0x…01` to `0x…ff`, the sentinel and the placeholder owner
         * among them), the Safe, the Delay or another of the Safe's modules, or a member already.
         */
        fun add(
            safe: Address,
            delay: Address,
            safeModules: List<Address>,
            members: List<Address>,
            member: Address,
        ): DelayMemberChangePlan = when {
            member.isReserved() -> DelayMemberChangePlan.NoKeyAddress
            member == safe || member == delay || member in safeModules -> DelayMemberChangePlan.TheAccount
            member in members -> DelayMemberChangePlan.AlreadyMember
            else -> DelayMemberChangePlan.Planned(DelayMemberChange(safe, delay, Kind.ADD, member, null))
        }

        /**
         * [member] removed from the Delay at [delay] of the Safe at [safe], whose members are
         * [members] in the Delay's own order; or why it is refused: not a member, or the last one
         * (the Safe's one owner is a placeholder, so with no member its funds are locked for good).
         */
        fun remove(safe: Address, delay: Address, members: List<Address>, member: Address): DelayMemberChangePlan {
            val index = members.indexOf(member)
            return when {
                index < 0 -> DelayMemberChangePlan.NotAMember
                members.size == 1 -> DelayMemberChangePlan.LastMember
                else -> {
                    val previous = if (index == 0) SENTINEL else members[index - 1]
                    DelayMemberChangePlan.Planned(DelayMemberChange(safe, delay, Kind.REMOVE, member, previous))
                }
            }
        }

        /**
         * A change recorded before, restored from its fields: null when it is none [add] or
         * [remove] could have planned (an address no key holds, the Safe or the Delay as the member,
         * a removal without the member before it, an addition with one).
         */
        fun of(safe: Address, delay: Address, kind: Kind, member: Address, previous: Address?): DelayMemberChange? {
            if (member.isReserved() || member == safe || member == delay) return null
            return when (kind) {
                Kind.ADD -> if (previous == null) DelayMemberChange(safe, delay, kind, member, null) else null
                Kind.REMOVE -> if (previous != null && previous != member && previous != Address.ZERO) {
                    DelayMemberChange(safe, delay, kind, member, previous)
                } else {
                    null
                }
            }
        }

        /** The zero address and `0x…01` to `0x…ff`: precompiles, the sentinel, the placeholder owner. */
        private fun Address.isReserved(): Boolean = hex.startsWith("0x" + "0".repeat(38))
    }
}

/** What [DelayMemberChange.add] and [DelayMemberChange.remove] give. */
sealed interface DelayMemberChangePlan {

    /** [change], a change of the Delay's members it may queue. */
    data class Planned(val change: DelayMemberChange) : DelayMemberChangePlan

    /** The zero address or `0x…01` to `0x…ff`: no key holds it, and the Delay's list could break. */
    data object NoKeyAddress : DelayMemberChangePlan

    /** The Safe itself, its Delay, or another of its modules. */
    data object TheAccount : DelayMemberChangePlan

    /** A member of the Delay already. */
    data object AlreadyMember : DelayMemberChangePlan

    /** Not a member of the Delay. */
    data object NotAMember : DelayMemberChangePlan

    /** The Delay's last member: with none, nobody could move the Safe's funds ever again. */
    data object LastMember : DelayMemberChangePlan
}
