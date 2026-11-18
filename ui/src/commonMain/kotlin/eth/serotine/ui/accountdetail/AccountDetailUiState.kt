package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.accounts.UnreachableNetwork
import eth.serotine.ui.accounts.networkBlockedReason
import eth.serotine.ui.accounts.networkOffReason
import eth.serotine.ui.accounts.sendBlockedReason
import eth.serotine.ui.accounts.swapBlockedReason
import eth.serotine.ui.activity.ActivityOrder
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.HandoffChoice
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.perapp.PerAppRow

/** Everything the account detail screen renders (docs/wallet-ux.md, screen 4). */
sealed interface AccountDetailUiState {

    /** The account has not been read yet. */
    data object Loading : AccountDetailUiState

    /**
     * The account: its [label], its EIP-55 [address] and what the user can do with it
     * ([control]); its [total] in the display currency ("$3,250.00", or "Checking…") and what
     * the total leaves out ([totalNote]: "Testnet prices · 1 asset without a price"); its
     * [assets] — the native currency and every known token it holds — in the [sort] order;
     * [pendingAssets], how many tokens' balances are not read at all yet, each drawn as a ghost
     * row after the [assets] until it is (a row then, or none for a token not held);
     * [tokensNote], what is not known yet about the other tokens ("Checking 3 tokens"), or
     * null; [pricesNote], where the prices come from; and [message], how hiding a token
     * went, until it is said. A Safe's [safe] is what its verified storage says and its [queue]
     * the transactions waiting for its owners on this device (both null for any other account);
     * the screen shows one of its [segments] ([segment]): its Assets, its Activity, and a Safe's
     * Owners or Queue. [kindNote] says what it is
     * ("Safe", or "Not a recognised Safe" once its address no longer verifies as one) and
     * [threshold] its verified threshold of its owners ("2/3"), for its blockie. [label] is the
     * account's name, or its short address once the ENS name its name follows no longer points
     * to it, which [nameNote] then says. [activity] is what its Activity tab lists while that tab
     * is shown, its rows running the way [activityOrder] says (newest first unless the user turned
     * them round from the top bar's menu), and Loading on any other. [network] is the account's
     * (docs/networks.md, N3 and
     * N4), named beside its control; [networkOn] false while that network is off in this wallet
     * (N19), when Send and Swap wait for it to be turned on; [networkBlocked] while this version can
     * no longer follow that network and others still can, which greys out Send and Swap too;
     * [swapVenue] false where no swap venue is pinned on it (Gnosis Chain), which greys out Swap.
     * Simple mode says how fresh its values are ([freshness]: "Updated 40 s ago · pull down to
     * refresh", "Live", "Checking…", "Syncing Ethereum · 2 of 6 periods" while it catches up; null
     * while nothing is proven yet) and whether a refresh is being answered ([refreshing]), and
     * [cantCheck], a banner while its network cannot be checked and the screen shows amounts from
     * before, or its light client has no fresh head; "is still syncing" while it catches up
     * (docs/simple-ui.md, H4 and *The sync line*). [amountsHidden] while the user hides amounts with
     * the eye beside Back (docs/wallet-ux.md, *Hiding amounts*): the total, the assets and the
     * Activity already read "••••" beside their units. [nfts] is what the account holds of NFTs, below
     * its assets (docs/wallet-ux.md, *NFTs*, issue #246); null until it is first read.
     */
    data class Ready(
        val label: String,
        val address: String,
        val network: NetworkLabel,
        val kind: AccountKind,
        val control: Control,
        val total: String,
        val totalNote: String?,
        val assets: List<AssetRow> = emptyList(),
        val pendingAssets: Int = 0,
        val sort: AssetSort = AssetSort.VALUE,
        val tokensNote: String? = null,
        val pricesNote: String? = null,
        val message: String? = null,
        val safe: SafeDetails? = null,
        val segment: DetailSegment = DetailSegment.ASSETS,
        val kindNote: String? = null,
        val threshold: String? = null,
        val queue: SafeQueue? = null,
        val nameNote: String? = null,
        /**
         * The ENS names that point to the account, its primary name first ("ENS names pointing here:
         * alice.eth (primary name), pay.eth"; #274): facts about the names' records, not about who holds
         * them, which the NFTs say; null when there are none.
         */
        val ensNames: String? = null,
        val activity: ActivityUiState = ActivityUiState.Loading,
        val activityOrder: ActivityOrder = ActivityOrder.NEWEST_FIRST,
        val networkOn: Boolean = true,
        val networkBlocked: Boolean = false,
        val swapVenue: Boolean = true,
        val freshness: String? = null,
        val refreshing: Boolean = false,
        val cantCheck: UnreachableNetwork? = null,
        val amountsHidden: Boolean = false,
        val nfts: NftSection? = null,
        /**
         * Send from another wallet (issue #255), for an EOA whose key is not on this device: greyed
         * out with its reason where it cannot be done now; null where it does not apply. The asset
         * menu offers it, and so does the greyed-out Send's explanation.
         */
        val handoff: HandoffChoice? = null,
        /**
         * For a Safe that is a Gnosis Pay account (docs/gnosis-pay.md), what its Owners tab says of it
         * beside the entry that opens its own screen, where a transfer goes through its Delay module;
         * null for any other account, and before it is read.
         */
        val gnosisPay: String? = null,
        /**
         * Whether that Gnosis Pay account is controlled from here: a key held here reaches its
         * Delay by itself, as a member or through member Safes (owner's request, 2026-11-17). Its
         * badge then says Full control, and Send opens the Gnosis Pay screen, where the transfer
         * goes through the Delay; Swap stays greyed out, since only plain transfers go through it.
         */
        val gnosisPayControlled: Boolean = false,
        /**
         * For a per-app address (issue #252), what marks it as one under its name: the site it was made for, as
         * the dApp stated it, and its full path, so another wallet can find it ("Per-app address for uniswap.org ·
         * m/44'/60'/…"); null for any other account.
         */
        val perAppMark: String? = null,
        /**
         * For the account a recovery phrase is known by (docs/wallet-ux.md, 4), its Per-app tab: that phrase's
         * per-app addresses as screen D9 lists them; null for any other account, which has no such tab.
         */
        val perApp: PerAppTab? = null,
    ) : AccountDetailUiState {

        /**
         * The badge the account shows: [control], which a Safe's owners held here give, or Full
         * control for a Gnosis Pay account a key held here controls through its Delay.
         */
        val shownControl: Control
            get() = if (gnosisPayControlled) Control.FULL_CONTROL else control

        /**
         * The tabs this account has: Assets | Activity, and a Safe's Owners | Queue after them, or the Per-app tab
         * of the account a recovery phrase is known by.
         */
        val segments: List<DetailSegment>
            get() = when {
                safe != null -> listOf(
                    DetailSegment.ASSETS,
                    DetailSegment.ACTIVITY,
                    DetailSegment.OWNERS,
                    DetailSegment.QUEUE,
                )
                perApp != null -> listOf(DetailSegment.ASSETS, DetailSegment.ACTIVITY, DetailSegment.PER_APP)
                else -> listOf(DetailSegment.ASSETS, DetailSegment.ACTIVITY)
            }

        /**
         * Whether Send can be done: for an account whose key is held here, and for a Safe with
         * owners held here, whose Send proposes a Safe transaction its owners sign; never for a
         * watch-only one (docs/wallet-ux.md, *Control is a badge*). Send is shown either way,
         * greyed out when it cannot, and a tap on it says [sendReason] (owner ruling, 2026-10-18, #55).
         */
        val canSend: Boolean
            get() = sendReason == null

        /** Why Send cannot be done from this account, and how to get there; null when it can. */
        val sendReason: String?
            get() = when {
                !networkOn -> networkOffReason(network.name, label)
                networkBlocked -> networkBlockedReason(network.name)
                // Controlled here through its Delay: Send opens the Gnosis Pay screen.
                gnosisPayControlled -> null
                // A Gnosis Pay account's owner is a placeholder: its transfers go through its Delay module.
                else -> sendBlockedReason(kind, control)?.let { if (placeholderOwned) GNOSIS_PAY_SEND else it }
            }

        /**
         * Whether a greyed-out Send offers the Gnosis Pay screen as the way to send: on a Gnosis Pay
         * account whose network is on and followed, where no owner held here can sign.
         */
        val sendOffersGnosisPay: Boolean
            get() = gnosisPay != null && networkOn && !networkBlocked && sendBlockedReason(kind, control) != null

        /**
         * Whether Send goes to the Gnosis Pay screen rather than this account's own Send: a Gnosis
         * Pay account controlled from here whose owners held here cannot sign for it by themselves,
         * as on the list ([eth.serotine.ui.accounts.AccountItem.sendsThroughGnosisPay]).
         */
        val sendsThroughGnosisPay: Boolean
            get() = gnosisPayControlled && control != Control.FULL_CONTROL

        /**
         * Whether this is a Gnosis Pay account whose verified owners are all the placeholder
         * `0x…02` (docs/gnosis-pay.md): only then is "no key controls its owner" said. A Gnosis Pay
         * account is told by its Delay alone, whatever its owners are.
         */
        val placeholderOwned: Boolean
            get() = gnosisPay != null &&
                (safe as? SafeDetails.Shown)?.owners?.let { owners ->
                    owners.isNotEmpty() && owners.all { it.address == GNOSIS_PAY_PLACEHOLDER }
                } == true

        /**
         * Whether Swap can be done from this account: greyed out when it cannot (watch-only, or a
         * Safe with no owner held here), and a tap on it says [swapReason] (owner ruling,
         * 2026-10-18, #55).
         */
        val canSwap: Boolean
            get() = swapReason == null

        /** Why Swap cannot be done from this account, and how to get there; null when it can. */
        val swapReason: String?
            get() = when {
                !networkOn -> networkOffReason(network.name, label, "swapped")
                networkBlocked -> networkBlockedReason(network.name, "swapped")
                !swapVenue -> "No swap venue is pinned on ${network.name} yet, so nothing can be swapped from this " +
                    "account. Send works as before."
                else -> swapBlockedReason(kind, control)?.let { if (placeholderOwned) GNOSIS_PAY_SWAP else it }
            }

        /**
         * Why a Safe's settings (docs/wallet-ux.md, screen 28) cannot be opened, and how to get
         * there: its network is off, and nothing of it is read (N19), or this version can no longer
         * follow it, as for Send; null when they can. Safe settings is greyed out meanwhile, and a tap
         * on it says this (owner ruling, 2026-10-18, #55).
         */
        val safeSettingsReason: String?
            get() = when {
                !networkOn -> "${network.name} is off in this wallet, so $label's settings are not read and none " +
                    "of them can be changed. Turned on, it reads them again first."
                networkBlocked -> networkBlockedReason(network.name, "changed")
                else -> null
            }

        /**
         * Whether the screen shows the [assets]: on the Assets [segment], and on a Safe's Owners or
         * Queue one only for an account that has none. The top bar's menu offers the other [sort]
         * order while it does, and the other [activityOrder] while the Activity is shown.
         */
        val showsAssets: Boolean
            get() = when (segment) {
                DetailSegment.ASSETS -> true
                DetailSegment.ACTIVITY -> false
                DetailSegment.OWNERS -> safe == null
                DetailSegment.QUEUE -> queue == null
                DetailSegment.PER_APP -> perApp == null
            }
    }

    /** The account is not in the wallet (removed): the screen leaves. */
    data object Gone : AccountDetailUiState

    /** The stored list cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : AccountDetailUiState
}

/**
 * What an account's detail shows, in the order of its tabs (docs/wallet-ux.md, screens 4 and 16:
 * Assets | Activity, and a Safe's Owners | Queue, or the Per-app tab of a phrase's account).
 */
enum class DetailSegment {
    /** Its assets. */
    ASSETS,

    /** Its Activity (docs/wallet-ux.md, screen 25). */
    ACTIVITY,

    /** Its owners, threshold, modules and guard. */
    OWNERS,

    /** Its transactions waiting for its owners on this device. */
    QUEUE,

    /** The per-app addresses of the recovery phrase it is known by (issue #252; docs/wallet-ux.md, 4). */
    PER_APP,
}

/**
 * An account's Per-app tab (issue #252; docs/wallet-ux.md, 4): the per-app addresses of the recovery phrase it is
 * known by, as screen D9 lists them ([rows]), [empty] while there are none, and the [phrase] (the address that names
 * it, in lower case) that "Restore a per-app address" opens D9 with; [restoreBlocked] is why that Restore is greyed
 * out (no network on), said on a tap, or null.
 */
data class PerAppTab(
    val phrase: String,
    val rows: List<PerAppRow>,
    val empty: String?,
    val restoreBlocked: String? = null,
)

/**
 * A Safe's transactions waiting for its owners on this device (docs/wallet-ux.md, screen 16,
 * Queue), display-ready.
 */
sealed interface SafeQueue {

    /** Not read yet. */
    data object Checking : SafeQueue

    /**
     * The [rows], in nonce order from the Safe's verified nonce on; [note], what to know about them
     * (that the Safe cannot be verified right now, so neither its nonce nor the signatures are
     * checked), or null.
     */
    data class Shown(val rows: List<QueueRow>, val note: String? = null) : SafeQueue

    /** The queue cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : SafeQueue
}

/**
 * One queued transaction: its [hash] (`0x` and 64 lower-case hex digits, what opens it), what it
 * does as decoded here ([action], "Send 40 USDC"), the address its row's blockie is of ([about],
 * null when it names none), where it stands in the Safe's order ([nonce], "Nonce 12 · next"),
 * its owners' signatures this device holds against the threshold ([signatures], "1 of 2
 * signatures on this device"), [refusal] when this wallet will not sign it ("Not signed here: a
 * delegate call"), and [warning], what to know before anyone signs it (a token that only takes a
 * built-in token's symbol, a module enabled, a guard removed, a fallback handler set or removed);
 * each null when there is none.
 */
data class QueueRow(
    val hash: String,
    val action: String,
    val about: String?,
    val nonce: String,
    val signatures: String,
    val refusal: String? = null,
    val warning: String? = null,
    /** Where the row goes among the others: its Safe nonce. */
    val order: Long = 0,
)

/** A Safe's owners and what else acts on it, display-ready (docs/wallet-ux.md, screen 16). */
sealed interface SafeDetails {

    /** Not read yet. */
    data object Checking : SafeDetails

    /**
     * Verified at the block [checkedAt] names: its [title] ("Safe 1.4.1 · nonce 12"), who must
     * sign ([signers]: "2 of 3 owners must sign"), its [owners], its [modules] — each can move
     * its funds without the owners — and its [guard] and [moduleGuard] (null: none).
     */
    data class Shown(
        val title: String,
        val signers: String,
        val owners: List<OwnerRow>,
        val modules: List<String>,
        val guard: String?,
        val checkedAt: String,
        val moduleGuard: String? = null,
    ) : SafeDetails

    /** Nothing verified to show right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : SafeDetails
}

/**
 * One owner: its EIP-55 [address], its name in the wallet ([label], null: not in it), and
 * whether its key is held here ([heldHere]).
 */
data class OwnerRow(val address: String, val label: String?, val heldHere: Boolean)

/** How the asset rows are ordered (docs/wallet-ux.md, screen 4: Sort by value / by name). */
enum class AssetSort {
    /** Highest value first; the assets without a price follow the priced ones, by name. */
    VALUE,

    /** By symbol. */
    NAME,
}

/**
 * One asset row (docs/wallet-ux.md, screen 4): its [symbol] and [name], the [amount] held —
 * verified when [verified], otherwise why there is none, or the amount last verified while
 * no head can be read at, with [lastKnown] saying so ("Last verified Oct 19, 2026, 2:32 PM ·
 * may have changed since"; null otherwise) — the block it was verified at
 * ([verifiedAt], "block 11,932,144"; null when not verified, last known or not named), its [value] in
 * the display currency, or why there is none ("No price"; null while there is no verified
 * amount to value), the bundled [icon] key (null: a monogram), a token's EIP-55 [contract]
 * (null for the native currency), which its long-press menu copies and hides, and
 * [warning], set when an added token's symbol is a built-in token's, which it is not. Simple mode
 * says how old an amount last verified is instead of when ([ageNote]: "3 h ago · may have changed",
 * "Ethereum is off · 3 h ago").
 */
data class AssetRow(
    val symbol: String,
    val name: String,
    val amount: String,
    val icon: String?,
    val verified: Boolean,
    val verifiedAt: String? = null,
    val value: String? = null,
    val contract: String? = null,
    val warning: String? = null,
    val lastKnown: String? = null,
    val ageNote: String? = null,
)

/**
 * An account's NFTs below its assets (docs/wallet-ux.md, *NFTs*; owner rulings, 2026-11-05, #246),
 * display-ready: the built-in collections' NFTs, [shown] openly; the unlisted collections' NFTs,
 * [unlisted], under a row that is collapsed until the user opens it ([unlistedOpen]); [empty], what
 * the section says while it lists none ("No NFTs found", "Checking NFTs…"), or null; and [note], what
 * it covers and what it does not ("Received since block 9,000 · 2 not checked yet"), which simple mode
 * says without a block ([simpleNote]). *Add an NFT* (by contract and token id, for one received before
 * the history starts) is greyed out while [addReason] says why it cannot be done, and [adding] is its
 * dialog while open.
 */
data class NftSection(
    val shown: List<NftRow> = emptyList(),
    val unlisted: List<NftRow> = emptyList(),
    val unlistedOpen: Boolean = false,
    val empty: String? = null,
    val note: String? = null,
    val simpleNote: String? = null,
    val addReason: String? = null,
    val adding: AddNftForm? = null,
)

/**
 * The *Add an NFT* dialog: [busy] while the account's holding is proven, and [error], why the last try
 * added nothing ("This account does not hold token 4021 of that contract."), or null.
 */
data class AddNftForm(val busy: Boolean = false, val error: String? = null)

/**
 * One NFT: its collection's [title] (a built-in collection's name, or what an unlisted contract says it
 * is), its token id ([tokenId], "#1234", shortened when long: "#982531…4107"), how many the account
 * holds when more than one ([amount], "×3", shortened as the id is), the collection's EIP-55
 * [contract], and the block it was proven held at ([provenAt], "block 9,500"; null where the engine
 * did not name it, and in simple mode, which shows no block). For an ENS token the [title] is the name
 * it stands for ("multisig.eth", or its hash, "[27a0e0…0f90].eth", where no label could be checked),
 * [tokenId] the collection's name, and [detail] whether it is wrapped and when it expires ("Wrapped ·
 * expires Mar 3, 2032, 10:00 AM"); null for any other NFT.
 */
data class NftRow(
    val title: String,
    val tokenId: String,
    val amount: String?,
    val contract: String,
    val provenAt: String? = null,
    val detail: String? = null,
    /** The NFT's key ("contract:id:standard") when the user added it, which Remove takes; null otherwise. */
    val addedKey: String? = null,
)

/**
 * Why Send is greyed out on a Gnosis Pay account with no owner held here (docs/gnosis-pay.md): its
 * one owner is a placeholder no key controls, so a transfer goes through its Delay module, on its
 * own screen, which the explanation offers.
 */
internal const val GNOSIS_PAY_SEND = "This is a Gnosis Pay account: its owner is a placeholder that no key " +
    "controls, so no owner can sign for it. Its transfers go through its Delay module, from the Gnosis Pay screen."

/** The owner account-kit leaves on a Gnosis Pay account, as [eth.serotine.ui.components.Eip55.format] gives it. */
internal const val GNOSIS_PAY_PLACEHOLDER = "0x0000000000000000000000000000000000000002"

/** Why Swap is greyed out on a Gnosis Pay account: only plain transfers go through its Delay module. */
internal const val GNOSIS_PAY_SWAP = "This is a Gnosis Pay account: its owner is a placeholder that no key " +
    "controls, and only plain transfers go through its Delay module, so nothing is swapped from it here."
