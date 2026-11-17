package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

// Who controls a Gnosis Pay account's Delay (docs/gnosis-pay.md, *Control through a member Safe*;
// owner's request, 2026-11-17): its members, and for a member that is a Safe, that Safe's owners,
// followed down to the keys at the end, as the light client proves each address's kind.

/**
 * What an address that can act for a Delay is, as far as control goes: a key, a Safe whose owners
 * act for it, or something Punicus does not follow. Every Safe in it was proven one by the light
 * client ([AddressKind.Safe]); a key is an address with no code, one delegated (EIP-7702), or one
 * whose key the wallet holds, which needs no read.
 */
sealed interface ControlNode {
    /** The address it is. */
    val address: Address

    /** An account a key controls: no code, or an EIP-7702 delegation, or one whose key is held here. */
    data class Key(override val address: Address) : ControlNode

    /**
     * A Safe, its [owners] read in its on-chain order and the [threshold] of their signatures it
     * needs: one owner acts for it alone only when that is 1.
     */
    data class Safe(override val address: Address, val threshold: Int, val owners: List<ControlNode>) : ControlNode

    /** Code that is no recognised Safe: a passkey signer, a module, a contract. Not followed. */
    data class OtherContract(override val address: Address) : ControlNode

    /**
     * A Safe past [DelayControl.MAX_SAFES] Safes down, one already on the way to it (a Safe that
     * owns itself, or a cycle), or one older than 1.3.0, which does not revert when the call it
     * executes fails, so a run that succeeds would not prove the queueing: not followed.
     */
    data class NotFollowed(override val address: Address) : ControlNode

    /**
     * An address no key controls: `0x…01` to `0x…ff`, where account-kit's placeholder owner `0x…02`
     * and Safe's sentinel are; never read.
     */
    data class Placeholder(override val address: Address) : ControlNode

    /** Not read yet ([reason] null), or no verified answer can be had right now, for [reason]. */
    data class Unread(override val address: Address, val reason: ReadUnavailable? = null) : ControlNode
}

/**
 * A key that can act for a Delay: [key], reached through the Safes in [via], the Delay's member
 * first and the Safe [key] owns last (empty: [key] is a member itself). [alone] when every Safe in
 * [via] needs one signature: [key] then queues a transfer by itself; otherwise only with its
 * co-owners, which Punicus does not gather for a Delay.
 */
data class DelayController(val key: Address, val via: List<Address>, val alone: Boolean)

/**
 * Who controls a Delay: each of its [members], followed down to keys ([ControlNode]), in the
 * Delay's own order.
 */
data class DelayControl(val members: List<ControlNode>) {

    /**
     * Every key at the end of a way down from a member, with the Safes it goes through: one entry
     * per way, so a key that owns two Safes on the way has two. In the members' order, then the
     * owners'.
     */
    val controllers: List<DelayController> by lazy {
        buildList { members.forEach { walk(it, emptyList(), alone = true) } }.distinct()
    }

    /** The members' addresses, in the Delay's order. */
    val memberAddresses: List<Address>
        get() = members.map { it.address }

    /** Whether every address on the way down was read: false while one is not, or cannot be. */
    val complete: Boolean by lazy { members.none { it.hasUnread() } }

    /** The addresses on the way down that are neither a key nor a Safe followed: contracts, Safes too deep. */
    val notFollowed: List<Address> by lazy {
        buildList { members.forEach { collectNotFollowed(it) } }.distinct()
    }

    private fun MutableList<DelayController>.walk(node: ControlNode, via: List<Address>, alone: Boolean) {
        when (node) {
            is ControlNode.Key -> add(DelayController(node.address, via, alone))
            is ControlNode.Safe -> node.owners.forEach { walk(it, via + node.address, alone && node.threshold == 1) }
            else -> Unit
        }
    }

    private fun ControlNode.hasUnread(): Boolean = when (this) {
        is ControlNode.Unread -> true
        is ControlNode.Safe -> owners.any { it.hasUnread() }
        else -> false
    }

    private fun MutableList<Address>.collectNotFollowed(node: ControlNode) {
        when (node) {
            is ControlNode.OtherContract, is ControlNode.NotFollowed -> add(node.address)
            is ControlNode.Safe -> node.owners.forEach { collectNotFollowed(it) }
            else -> Unit
        }
    }

    companion object {
        /** How many Safes deep a way down from a member is followed: a member Safe, its owner Safe, and one more. */
        const val MAX_SAFES = 3
    }
}

/**
 * Reads who controls a Delay whose members are [members] ([DelayControl]), on [network]: each
 * address's kind as the light client proves it ([AddressKinds]), a Safe's owners read the same way
 * in turn, at most [DelayControl.MAX_SAFES] Safes deep. An address in [keys], an account whose key
 * the wallet holds, is a key without a read. Each address is said [ControlNode.Unread] until its
 * kind is read, so the whole is told at once and filled in as the reads land; a Safe's owners are
 * read again only when its owners or threshold change, not at every nonce.
 */
internal class DelayControlReader(private val kinds: AddressKinds) {

    fun observe(network: Network, members: List<Address>, keys: Set<Address>): Flow<DelayControl> =
        combineAll(members.map { node(network, it, emptyList(), keys) }).map { DelayControl(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun node(network: Network, address: Address, path: List<Address>, keys: Set<Address>): Flow<ControlNode> {
        if (address in keys) return flowOf(ControlNode.Key(address))
        if (address.isPlaceholder()) return flowOf(ControlNode.Placeholder(address))
        return kinds.observeKind(network, address)
            .map { Shape.of(it) }
            .distinctUntilChanged()
            .flatMapLatest { shape ->
                when (shape) {
                    Shape.Key -> flowOf(ControlNode.Key(address))
                    Shape.Contract -> flowOf(ControlNode.OtherContract(address))
                    is Shape.Unread -> flowOf(ControlNode.Unread(address, shape.reason))
                    Shape.OldSafe -> flowOf(ControlNode.NotFollowed(address))
                    is Shape.Safe -> if (address in path || path.size >= DelayControl.MAX_SAFES) {
                        flowOf(ControlNode.NotFollowed(address))
                    } else {
                        combineAll(shape.owners.map { node(network, it, path + address, keys) })
                            .map { owners -> ControlNode.Safe(address, shape.threshold, owners) }
                    }
                }
            }
            .onStart { emit(ControlNode.Unread(address)) }
            .distinctUntilChanged()
    }

    /** What of an address's kind decides control: a Safe's owners and threshold, not its nonce. */
    private sealed interface Shape {
        data object Key : Shape
        data object Contract : Shape
        data class Safe(val owners: List<Address>, val threshold: Int) : Shape

        /** A Safe before 1.3.0: no `GS013`, so its `execTransaction` returns false where a newer one reverts. */
        data object OldSafe : Shape
        data class Unread(val reason: ReadUnavailable?) : Shape

        companion object {
            fun of(reading: KindReading): Shape = when (reading) {
                KindReading.Syncing -> Unread(null)
                is KindReading.Unavailable -> Unread(reading.reason)
                is KindReading.Verified -> when (val kind = reading.kind) {
                    AddressKind.Eoa, is AddressKind.DelegatedEoa -> Key
                    is AddressKind.Safe -> if (kind.state.version in REVERTING_VERSIONS) {
                        Safe(kind.state.owners, kind.state.threshold)
                    } else {
                        OldSafe
                    }
                    AddressKind.Contract -> Contract
                }
            }
        }
    }
}

/**
 * The Safe releases a way down goes through: from 1.3.0 on, `execTransaction` with `safeTxGas` and
 * `gasPrice` 0 reverts when its call fails (`GS013`), so a run that succeeds through them proves the
 * call at the end succeeded. The releases [SafeState] pins from 1.3.0 on.
 */
private val REVERTING_VERSIONS = setOf("1.3.0", "1.4.1", "1.5.0")

/** Whether this is `0x…01` to `0x…ff`: precompiles, Safe's sentinel and account-kit's placeholder owner. */
private fun Address.isPlaceholder(): Boolean = hex.startsWith("0x" + "0".repeat(38))

/** Each flow's latest value, together; one empty list at once for none. */
private inline fun <reified T> combineAll(flows: List<Flow<T>>): Flow<List<T>> =
    if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
