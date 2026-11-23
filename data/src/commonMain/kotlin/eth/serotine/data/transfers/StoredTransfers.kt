package eth.serotine.data.transfers

import eth.serotine.data.safe.WireRequest
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.DelayCall
import eth.serotine.domain.model.DelayMemberChange
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// How a sent transaction is stored: the one form FileSentTransfers and FileTransactionHistory
// both keep it in, so the two files never describe the same transaction two ways.

private const val APPROVAL = "approval"
private const val EXECUTION = "execution"
private const val WRAP = "wrap"
private const val UNWRAP = "unwrap"
private const val QUEUE = "queue"
private const val ADD_MEMBER = "add"
private const val REMOVE_MEMBER = "remove"
private const val SKIP = "skip"

/**
 * One transaction, sent on the network of [chain] (absent in a record written before networks:
 * Sepolia, the only one there was; one of a network this build does not know does not decode):
 * a transfer's [to], [token] (null: the native currency) and [amount], a
 * Safe transaction's approval or execution ([safe]), a Safe's deployment ([deployment]), a
 * token approval ([approval]), a swap ([swap]), a wrap or unwrap ([wrap]), a CoW order's
 * cancellation on chain ([cancellation]), a dApp's transaction ([dappCall]) or a Gnosis Pay
 * account's transfer or member change queued or executed through its Delay ([delay]), or a Swarm
 * node's postage batch bought, topped up or diluted ([postage], format 13) — exactly one of the ten,
 * else the file is damaged.
 */
@Serializable
internal class StoredTransfer(
    val account: String,
    val nonce: Long,
    val raw: String,
    val to: String? = null,
    val token: StoredToken? = null,
    val amount: String? = null,
    val safe: StoredSafeCall? = null,
    val deployment: StoredDeployment? = null,
    val approval: StoredApproval? = null,
    val swap: StoredSwap? = null,
    val wrap: StoredWrap? = null,
    val cancellation: StoredCowCancellation? = null,
    val dappCall: StoredDappCall? = null,
    val delay: StoredDelayCall? = null,
    val postage: StoredPostageCall? = null,
    val maxCost: String,
    val sentAt: Long,
    val maybeSent: Boolean = false,
    val chain: Long? = null,
) {
    fun toTransfer() = SentTransfer(
        network = if (chain == null) Network.SEPOLIA else requireNotNull(Network.ofChainId(chain)) { "a chain" },
        account = Address.of(account),
        nonce = nonce,
        signed = SignedTransfer(raw.hexBytes()),
        action = action(),
        maxCost = UInt256.of(maxCost),
        sentAt = sentAt,
        maybeSent = maybeSent,
    )

    private fun action(): SentAction {
        val kinds = listOfNotNull(deployment, safe, approval, swap, wrap, cancellation, dappCall, delay, postage)
        require(kinds.size <= 1) { "more than one kind of transaction" }
        val transferFields = to != null || token != null || amount != null
        require(kinds.isEmpty() || !transferFields) { "a transfer and another kind at once" }
        deployment?.let { return it.toAction() }
        safe?.let { return it.toAction() }
        approval?.let { return it.toAction() }
        swap?.let { return it.toAction() }
        wrap?.let { return it.toAction() }
        cancellation?.let { return it.toAction() }
        dappCall?.let { return it.toAction() }
        delay?.let { return it.toAction() }
        postage?.let { return it.toAction() }
        return SentAction.Transfer(
            to = Address.of(requireNotNull(to) { "a transfer without a recipient" }),
            asset = token?.let { TransferAsset.Erc20Token(it.toToken()) } ?: TransferAsset.Native,
            amount = UInt256.of(requireNotNull(amount) { "a transfer without an amount" }),
        )
    }
}

/**
 * A Gnosis Pay account's call through its Delay, queued ([kind] `queue`) or executed
 * (`execution`), from the Safe [safe] through its Delay [delay]: either a transfer of [amount] of
 * [token] (null: the Safe's currency) to [recipient], or (format 12) a change of the Delay's
 * members ([change] `add` or `remove`) of [member], with [previous], the member before it, for a
 * removal. Exactly one of the two, else the entry is damaged; so is a stored transfer that is no
 * plain transfer the Delay may queue, or a change [DelayMemberChange.of] would not restore. A build
 * before format 12 passes a change over: it has no [recipient]. Or the Delay's skip of expired
 * entries (`skip`, format 11): [recipient] is then the Delay and [amount] zero, neither read.
 */
@Serializable
internal class StoredDelayCall(
    val kind: String,
    val safe: String,
    val delay: String,
    val recipient: String? = null,
    val token: StoredToken? = null,
    val amount: String? = null,
    val change: String? = null,
    val member: String? = null,
    val previous: String? = null,
) {
    fun toAction(): SentAction {
        if (kind == SKIP) {
            require(change == null && member == null && previous == null) { "a skip with a member's fields" }
            return SentAction.DelaySkip(Address.of(safe), Address.of(delay))
        }
        val call: DelayCall = if (change == null) {
            require(member == null && previous == null) { "a transfer with a member's fields" }
            val plan = DelayTransfer.of(
                Address.of(safe),
                Address.of(delay),
                emptyList(),
                token.asset(),
                Address.of(requireNotNull(recipient) { "a transfer without a recipient" }),
                UInt256.of(requireNotNull(amount) { "a transfer without an amount" }),
            )
            requireNotNull((plan as? DelayTransferPlan.Planned)?.transfer) { "no plain transfer" }
        } else {
            require(recipient == null && token == null && amount == null) { "a member change with a transfer's fields" }
            val changeKind = when (change) {
                ADD_MEMBER -> DelayMemberChange.Kind.ADD
                REMOVE_MEMBER -> DelayMemberChange.Kind.REMOVE
                else -> throw IllegalArgumentException("a member change of an unknown kind")
            }
            requireNotNull(
                DelayMemberChange.of(
                    Address.of(safe),
                    Address.of(delay),
                    changeKind,
                    Address.of(requireNotNull(member) { "a member change without a member" }),
                    previous?.let(Address::of),
                ),
            ) { "no member change the Delay may queue" }
        }
        return when (kind) {
            QUEUE -> SentAction.DelayQueue(call)
            EXECUTION -> SentAction.DelayExecution(call)
            else -> throw IllegalArgumentException("a Delay call of an unknown kind")
        }
    }
}

/** [call] as stored, queued or executed ([kind]). */
private fun delayCall(kind: String, call: DelayCall) = when (call) {
    is DelayTransfer -> StoredDelayCall(
        kind,
        call.safe.hex,
        call.delay.hex,
        recipient = call.recipient.hex,
        token = call.asset.storedToken(),
        amount = call.amount.toDecimal(),
    )
    is DelayMemberChange -> StoredDelayCall(
        kind,
        call.safe.hex,
        call.delay.hex,
        change = when (call.kind) {
            DelayMemberChange.Kind.ADD -> ADD_MEMBER
            DelayMemberChange.Kind.REMOVE -> REMOVE_MEMBER
        },
        member = call.member.hex,
        previous = call.previous?.hex,
    )
}

/** What a token transfer needs shown again: its bundled icon's name too, not its price. */
@Serializable
internal class StoredToken(
    val address: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val icon: String? = null,
) {
    fun toToken() = Token(Address.of(address), symbol, name, decimals, icon)
}

/** A token approval: [spender] may take [amount] of [token]. */
@Serializable
internal class StoredApproval(val token: StoredToken, val spender: String, val amount: String) {
    fun toAction() = SentAction.TokenApproval(token.toToken(), Address.of(spender), UInt256.of(amount))
}

/**
 * A swap through [router]: [amountIn] of [sell] for at least [minimumOut] of [buy], each asset
 * a token, or the native currency (null); [weth] the WETH9 the router unwraps ETH bought from.
 */
@Serializable
internal class StoredSwap(
    val router: String,
    val sell: StoredToken? = null,
    val amountIn: String,
    val buy: StoredToken? = null,
    val minimumOut: String,
    val weth: String? = null,
) {
    fun toAction() = SentAction.Swap(
        router = Address.of(router),
        sell = sell.asset(),
        amountIn = UInt256.of(amountIn),
        buy = buy.asset(),
        minimumOut = UInt256.of(minimumOut),
        weth = weth?.let(Address::of),
    )
}

/** A wrap ([WRAP]) or an unwrap ([UNWRAP]) of [amount] through the WETH9 [weth]. */
@Serializable
internal class StoredWrap(val kind: String, val weth: StoredToken, val amount: String) {
    fun toAction(): SentAction = when (kind) {
        WRAP -> SentAction.Wrap(weth.toToken(), UInt256.of(amount))
        UNWRAP -> SentAction.Unwrap(weth.toToken(), UInt256.of(amount))
        else -> throw IllegalArgumentException("a wrap of no kind")
    }
}

/**
 * A dApp's transaction: its recipient [to], its [value] in wei, the [dapp] it was sent for (its claim),
 * and the request it answers: request [id] on the session [topic].
 */
@Serializable
internal class StoredDappCall(val to: String, val value: String, val dapp: String, val topic: String, val id: Long) {
    fun toAction() =
        SentAction.DappCall(Address.of(to), UInt256.of(value), dapp, DappRequestId(DappSessionId(topic), id))
}

/** A CoW order's cancellation on chain: the order [uid], at the settlement contract [settlement]. */
@Serializable
internal class StoredCowCancellation(val settlement: String, val uid: String) {
    fun toAction() = SentAction.CowCancellation(Address.of(settlement), CowOrderUid.of(uid))
}

/** A Safe transaction's [kind] of call ([APPROVAL] or [EXECUTION]) and its [request]. */
@Serializable
internal class StoredSafeCall(val kind: String, val request: WireRequest) {
    fun toAction(): SentAction {
        require(request.v == WireRequest.VERSION) { "a request of another version" }
        val network = requireNotNull(Network.ofChainId(request.chainId)) { "a chain" }
        val decoded = requireNotNull(request.toRequest(network)) { "a request out of range" }
        return when (kind) {
            APPROVAL -> SentAction.SafeApproval(decoded)
            EXECUTION -> SentAction.SafeExecution(decoded)
            else -> throw IllegalArgumentException("a Safe call of no kind")
        }
    }
}

/**
 * A Safe's deployment: the [safe] it creates, of [version], with [owners] and [threshold], listed
 * as [label], and how its listing ended ([listed], a [SafeListing] name; null: not yet).
 */
@Serializable
internal class StoredDeployment(
    val safe: String,
    val owners: List<String>,
    val threshold: Int,
    val version: String,
    val label: String,
    val listed: String? = null,
) {
    fun toAction() = SentAction.SafeDeployment(
        safe = Address.of(safe),
        owners = owners.map(Address::of),
        threshold = threshold,
        version = version,
        label = label,
        listed = listed?.let { name -> requireNotNull(SafeListing.entries.firstOrNull { it.name == name }) },
    )
}

/** The transaction [entry] stores, or null when it does not decode: passed over, the rest read. */
internal fun decodedTransfer(entry: JsonElement): SentTransfer? = try {
    Json.decodeFromJsonElement(StoredTransfer.serializer(), entry).toTransfer()
} catch (e: IllegalArgumentException) {
    // A SerializationException (a field missing or of another type), or a field out of
    // range (an address, an amount, hex) as the stored types refuse it.
    null
}

/** The form [transfer] is stored in. */
internal fun storedTransfer(transfer: SentTransfer): StoredTransfer {
    val action = transfer.action
    val send = action as? SentAction.Transfer
    return StoredTransfer(
        account = transfer.account.hex,
        nonce = transfer.nonce,
        raw = transfer.signed.raw.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        to = send?.to?.hex,
        token = send?.asset?.storedToken(),
        amount = send?.amount?.toDecimal(),
        safe = when (action) {
            is SentAction.SafeApproval -> safeCall(APPROVAL, action.request)
            is SentAction.SafeExecution -> safeCall(EXECUTION, action.request)
            is SentAction.Transfer, is SentAction.SafeDeployment, is SentAction.TokenApproval,
            is SentAction.Swap, is SentAction.Wrap, is SentAction.Unwrap, is SentAction.CowCancellation,
            is SentAction.DappCall, is SentAction.DelayQueue, is SentAction.DelayExecution,
            is SentAction.DelaySkip, is SentAction.BatchPurchase, is SentAction.BatchTopUp, is SentAction.BatchDilution,
            -> null
        },
        delay = when (action) {
            is SentAction.DelayQueue -> delayCall(QUEUE, action.call)
            is SentAction.DelayExecution -> delayCall(EXECUTION, action.call)
            // No transfer: the Delay alone, as recipient, and nothing moved.
            is SentAction.DelaySkip ->
                StoredDelayCall(SKIP, action.safe.hex, action.delay.hex, action.delay.hex, amount = "0")
            else -> null
        },
        dappCall = (action as? SentAction.DappCall)?.let {
            StoredDappCall(it.to.hex, it.value.toDecimal(), it.dapp, it.request.session.topic, it.request.id)
        },
        approval = (action as? SentAction.TokenApproval)?.let {
            StoredApproval(stored(it.token), it.spender.hex, it.amount.toDecimal())
        },
        swap = (action as? SentAction.Swap)?.let {
            StoredSwap(
                it.router.hex,
                it.sell.storedToken(),
                it.amountIn.toDecimal(),
                it.buy.storedToken(),
                it.minimumOut.toDecimal(),
                it.weth?.hex,
            )
        },
        wrap = when (action) {
            is SentAction.Wrap -> StoredWrap(WRAP, stored(action.weth), action.amount.toDecimal())
            is SentAction.Unwrap -> StoredWrap(UNWRAP, stored(action.weth), action.amount.toDecimal())
            else -> null
        },
        postage = when (action) {
            is SentAction.BatchPurchase -> StoredPostageCall(
                PURCHASE,
                action.stamp.hex,
                action.id.hex,
                action.depth,
                perChunk = action.perChunk.toDecimal(),
                owner = action.owner.hex,
                immutable = action.immutable,
                token = stored(action.token),
            )
            is SentAction.BatchTopUp -> StoredPostageCall(
                TOP_UP,
                action.stamp.hex,
                action.id.hex,
                action.depth,
                perChunk = action.perChunk.toDecimal(),
                token = stored(action.token),
            )
            is SentAction.BatchDilution -> StoredPostageCall(DILUTION, action.stamp.hex, action.id.hex, action.depth)
            else -> null
        },
        cancellation = (action as? SentAction.CowCancellation)?.let {
            StoredCowCancellation(it.settlement.hex, it.uid.hex)
        },
        deployment = (action as? SentAction.SafeDeployment)?.let {
            StoredDeployment(
                it.safe.hex,
                it.owners.map(Address::hex),
                it.threshold,
                it.version,
                it.label,
                it.listed?.name,
            )
        },
        maxCost = transfer.maxCost.toDecimal(),
        sentAt = transfer.sentAt,
        maybeSent = transfer.maybeSent,
        chain = transfer.network.chainId,
    )
}

/**
 * A Swarm node's postage batch call (format 13, docs/swarm.md): bought ([kind] `purchase`, with
 * its [owner], [perChunk], [immutable] and the [token] it pulls, xBZZ), topped up (`top-up`, with
 * [perChunk] and [token]) or diluted
 * (`dilution`), of the batch [id] at the PostageStamp [stamp], at [depth] (the new one for a
 * dilution). A field its kind does not have, or one it lacks, makes the entry damaged.
 */
@Serializable
internal class StoredPostageCall(
    val kind: String,
    val stamp: String,
    val id: String,
    val depth: Int,
    val perChunk: String? = null,
    val owner: String? = null,
    val immutable: Boolean? = null,
    val token: StoredToken? = null,
) {
    fun toAction(): SentAction {
        val stamp = Address.of(stamp)
        val id = BatchId.of(id)
        return when (kind) {
            PURCHASE -> SentAction.BatchPurchase(
                requireNotNull(token) { "a purchase without its token" }.toToken(),
                stamp,
                Address.of(requireNotNull(owner) { "a purchase without its owner" }),
                id,
                depth,
                UInt256.of(requireNotNull(perChunk) { "a purchase without its amount" }),
                requireNotNull(immutable) { "a purchase without whether it is immutable" },
            )
            TOP_UP -> {
                require(owner == null && immutable == null) { "a top-up with a purchase's fields" }
                val amount = requireNotNull(perChunk) { "a top-up without its amount" }
                val pulled = requireNotNull(token) { "a top-up without its token" }.toToken()
                SentAction.BatchTopUp(pulled, stamp, id, depth, UInt256.of(amount))
            }
            DILUTION -> {
                require(owner == null && immutable == null && perChunk == null && token == null) {
                    "a dilution with an amount"
                }
                SentAction.BatchDilution(stamp, id, depth)
            }
            else -> throw IllegalArgumentException("a postage call of no known kind")
        }
    }
}

private const val PURCHASE = "purchase"
private const val TOP_UP = "top-up"
private const val DILUTION = "dilution"

/** A Safe transaction's call of [kind] as stored, its [request] in the form a signing request travels in. */
private fun safeCall(kind: String, request: SigningRequest) = StoredSafeCall(kind, WireRequest.of(request))

/** [token] as stored. */
internal fun stored(token: Token) = StoredToken(token.address.hex, token.symbol, token.name, token.decimals, token.icon)

/** The token this asset is, as stored; null for the native currency. */
internal fun TransferAsset.storedToken(): StoredToken? = (this as? TransferAsset.Erc20Token)?.token?.let(::stored)

/** The asset this stored token is: the native currency for none. */
internal fun StoredToken?.asset(): TransferAsset = this?.let { TransferAsset.Erc20Token(it.toToken()) }
    ?: TransferAsset.Native

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) { "not lower-case hex" }
    return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
