package eth.serotine.ui.swarm

import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.SwarmAddOutcome
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped

/**
 * The words of the Swarm node screen (docs/wallet-ux.md, *Swarm nodes*), and the figures it shows
 * that are not chain state.
 */
internal object SwarmTexts {

    /** xBZZ's decimals: its smallest unit, PLUR, is 10^-16 xBZZ. */
    private const val BZZ_DECIMALS = 16

    /** An amount of PLUR, in BZZ: the token's own `symbol()`, as the account's screen lists it. */
    fun bzz(plur: UInt256): String = formatUnits(plur.toDecimal(), BZZ_DECIMALS, "BZZ")

    /**
     * Swarm's published effective volume per batch depth, for unencrypted data without erasure
     * coding: what a batch can be expected to hold before one of its buckets fills, at a failure
     * rate of 0.1% or less. From docs.ethswarm.org, *Postage stamps*, *Effective Utilisation
     * Tables*, "Unencrypted - NONE", read on 2026-11-23. Display only: it reaches no signature.
     */
    private val EFFECTIVE = mapOf(
        17 to "44.70 kB", 18 to "6.66 MB", 19 to "112.06 MB", 20 to "687.62 MB", 21 to "2.60 GB",
        22 to "7.73 GB", 23 to "19.94 GB", 24 to "47.06 GB", 25 to "105.51 GB", 26 to "227.98 GB",
        27 to "476.68 GB", 28 to "993.65 GB", 29 to "2.04 TB", 30 to "4.17 TB", 31 to "8.45 TB",
        32 to "17.07 TB", 33 to "34.36 TB", 34 to "69.04 TB", 35 to "138.54 TB", 36 to "277.72 TB",
        37 to "556.35 TB", 38 to "1.11 PB", 39 to "2.23 PB", 40 to "4.46 PB", 41 to "8.93 PB",
    )

    /** The same table's theoretical volume per depth: `2^depth` chunks of 4 KiB, in its units. */
    private val THEORETICAL = mapOf(
        17 to "536.87 MB", 18 to "1.07 GB", 19 to "2.15 GB", 20 to "4.29 GB", 21 to "8.59 GB",
        22 to "17.18 GB", 23 to "34.36 GB", 24 to "68.72 GB", 25 to "137.44 GB", 26 to "274.88 GB",
        27 to "549.76 GB", 28 to "1.10 TB", 29 to "2.20 TB", 30 to "4.40 TB", 31 to "8.80 TB",
        32 to "17.59 TB", 33 to "35.18 TB", 34 to "70.37 TB", 35 to "140.74 TB", 36 to "281.47 TB",
        37 to "562.95 TB", 38 to "1.13 PB", 39 to "2.25 PB", 40 to "4.50 PB", 41 to "9.01 PB",
    )

    /**
     * What a batch of [depth] holds: Swarm's effective figure beside the theoretical one, called
     * theoretical (docs/swarm.md, *What the Swarm screen shows*); the chunk count where the table
     * has no row.
     */
    fun capacity(depth: Int): String {
        val effective = EFFECTIVE[depth]
        val theoretical = THEORETICAL[depth]
        return if (effective != null && theoretical != null) {
            "Depth $depth · $effective of $theoretical theoretical"
        } else {
            "Depth $depth · up to 2^$depth chunks, theoretical"
        }
    }

    /** What a batch has left, at the price its reading was made at (Bee's `estimateBatchTTL`). */
    fun timeLeft(batch: PostageBatch): String {
        if (batch.expired) return "Expired"
        val seconds = batch.secondsLeft
            ?: return if (batch.lastPrice.isZero) "Time left unknown: the price is zero" else FAR_AHEAD
        val days = seconds / DAY
        return when {
            days >= 2 -> "About ${grouped(days)} days left at the current price"
            seconds >= 2 * HOUR -> "About ${seconds / HOUR} hours left at the current price"
            else -> "Less than two hours left at the current price"
        }
    }

    /** Where a reading verified at [block] stands: held as last verified ([held]) or current. */
    fun verified(block: Long, held: Boolean): String = if (held) {
        "Last verified by the light client at block ${grouped(block)}. It could not be verified at the latest block: " +
            "it may have changed since."
    } else {
        "Verified at block ${grouped(block)}"
    }

    /** A [statement] verified at [block], held as last verified ([held]) or current, with where it stands. */
    fun stated(statement: String, block: Long, held: Boolean): String =
        // A held reading's text ends with its own full stop.
        if (held) "$statement ${verified(block, held = true)}" else "$statement ${verified(block, held = false)}."

    const val NETWORK_OFF =
        "Gnosis Chain is off in this wallet, so nothing can be checked on chain. Turned on, a chequebook or " +
            "a batch can be added here."
    const val NETWORK_NOT_TURNED_ON = "Gnosis Chain could not be turned on: try again in Settings."
    const val NOT_REMOVED = "It could not be removed: the chequebooks and batches kept could not be saved."

    const val NOT_READ = "Checking on chain…"
    const val SYNCING = "Waiting for the light client to sync…"

    /** Why a reading is not available, for [reason]. */
    fun unavailable(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.NO_ENGINE -> "Not available: no light client runs for Gnosis Chain here."
        ReadUnavailable.NETWORK_OFF -> "Not available: Gnosis Chain is turned off."
        ReadUnavailable.STALE_ANCHOR -> "Not available: the light client's checkpoint is too old to sync from."
        ReadUnavailable.SYNCING -> SYNCING
        ReadUnavailable.UNPROVEN -> "Not verified this time: it is read again shortly."
        ReadUnavailable.REFUSED -> "Not available: the light client cannot read this here."
    }

    const val NO_LONGER_A_CHEQUEBOOK = "No chequebook of Bee's factory at this address."
    const val BOUNCED = "A cheque of this chequebook bounced: peers know, and may stop serving the node."

    fun othersChequebook(issuer: String) = "This chequebook is $issuer's, not this node's."

    const val GONE = "The postage contract holds no batch of this id: it expired and was deleted."

    fun othersBatch(owner: String) = "This batch is owned by $owner, not by this node."

    const val NO_CHEQUEBOOK =
        "No chequebook found. The history of this account starts when it was added here; add the " +
            "chequebook by its address, as Bee shows it."
    const val NO_BATCH =
        "No postage batch found. Batches this wallet paid for are found by themselves; add others by " +
            "their id, as Bee shows it."
    const val SET_ASIDE =
        "The chequebooks and batches kept for this node could not be read and were set aside; add them again."
    const val UNREADABLE = "The chequebooks and batches kept for this node cannot be read right now."
    const val NOT_SWARM = "Only an account on Gnosis Chain that is no Safe can be a Swarm node."
    const val LIST_UNAVAILABLE = "The wallet's account list cannot be read right now."

    /** What adding by hand said, as the form shows it. */
    fun added(outcome: SwarmAddOutcome): String? = when (outcome) {
        SwarmAddOutcome.Added, SwarmAddOutcome.AlreadyAdded -> null
        SwarmAddOutcome.Dismissed -> "It was removed before."
        SwarmAddOutcome.NotASwarmAccount -> NOT_SWARM
        SwarmAddOutcome.NotAChequebook -> "This address holds no chequebook of Bee's factory."
        is SwarmAddOutcome.OthersChequebook ->
            "This chequebook's issuer is ${Eip55.format(outcome.issuer)}, not this node."
        SwarmAddOutcome.NoSuchBatch -> "The postage contract holds no batch of this id."
        is SwarmAddOutcome.OthersBatch -> "This batch is owned by ${Eip55.format(outcome.owner)}, not this node."
        is SwarmAddOutcome.NotVerified ->
            "It could not be verified right now, so nothing was kept. " + unavailable(outcome.reason)
        SwarmAddOutcome.NotSaved -> "It is this node's, but it could not be saved."
    }

    const val NOT_AN_ADDRESS = "Not an address: 0x and 40 hex digits."
    const val CHECKSUM = "This address's checksum does not match: check it was copied in full."
    const val NOT_A_BATCH_ID = "Not a batch id: 64 hex digits, with or without 0x."

    private const val FAR_AHEAD = "Far more than a lifetime left"
    private const val HOUR = 3600L
    private const val DAY = 24 * HOUR
}
