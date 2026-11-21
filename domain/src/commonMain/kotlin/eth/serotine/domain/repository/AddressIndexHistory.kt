package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network

/**
 * The port for an address's history from the addr1 address index on Swarm (issue #334; owner ruling, 2026-11-21,
 * `.claude/rules/data-sources.md`, *The history may also take the addr1 index from Swarm*): pages of candidate
 * entries, newest first, and the engine's check of each against verified chain data. :data implements it over the
 * Myotis engine's `myotis_getAddressHistory` and `myotis_verifyAddressHistoryEntries` (Myotis #899), which read the
 * index through the engine's own Swarm light node and check every chunk; this port adds no Swarm client, gateway or
 * HTTP. An entry of a page is a candidate only: what is shown is a [AddressIndexVerdict.Verified] of it.
 *
 * Every call names its [Network]; a network no adapter serves is refused ([AddressIndexRefusal.NetworkNotServed]),
 * never answered from another one. A refusal is never an empty page. Never throws, but for the caller's errors each
 * call names.
 */
interface AddressIndexHistory {

    /**
     * One page of [address]'s history, newest first: [network] is the chain whose index is read, [address] the
     * address whose entries it lists, and [query] which page and which entries (each of its fields applied or the
     * call refused). [AddressIndexReading.Page] with the page, or [AddressIndexReading.Refused] and why. Its entries
     * are candidates: a verdict the engine kept for one is not taken from here (see [verify]).
     */
    suspend fun page(network: Network, address: Address, query: AddressIndexQuery): AddressIndexReading

    /**
     * The engine's check of [entries] (1 to [MAX_CHECKED] of them, as pages returned them; more, or none, is the
     * caller's error and throws) of [address]'s history on [network], the chain whose verified blocks and receipts
     * they are checked against:
     * [AddressIndexCheck.Verdicts] with one verdict per entry asked, in any order, or [AddressIndexCheck.Refused]
     * and why. An entry the answer has no verdict for is the adapter's to report as
     * [AddressIndexVerdict.Unavailable]; a caller treats a missing one as such. Suspends for up to about 90 seconds,
     * the engine's bound on one check.
     */
    suspend fun verify(network: Network, address: Address, entries: List<AddressIndexEntry>): AddressIndexCheck

    companion object {
        /** The most entries one [verify] call checks: the engine's bound. */
        const val MAX_CHECKED: Int = 50

        /** The most entries one page holds: the engine's bound. */
        const val MAX_PAGE: Int = 500

        /** The entries a page holds when the query names no limit: the engine's default. */
        const val DEFAULT_PAGE: Int = 50
    }
}

/**
 * What [AddressIndexHistory.page] reads, each field applied or the call refused, never ignored: the page below
 * [cursor] (a page's [AddressIndexPage.next]; null: the newest page); at most [limit] entries (1 to
 * [AddressIndexHistory.MAX_PAGE]); only entries with one of [roles] (empty: every role); only blocks [fromBlock] to
 * [toBlock] (null: from the index's start, up to its top). The gaps a page names are clipped to that range.
 */
data class AddressIndexQuery(
    val cursor: AddressIndexCursor? = null,
    val limit: Int = AddressIndexHistory.DEFAULT_PAGE,
    val roles: Set<AddressIndexRole> = emptySet(),
    val fromBlock: Long? = null,
    val toBlock: Long? = null,
) {
    init {
        require(limit in 1..AddressIndexHistory.MAX_PAGE) { "a page holds 1 to 500 entries" }
        require(fromBlock == null || fromBlock >= 0) { "a block number is never negative" }
        require(toBlock == null || toBlock >= (fromBlock ?: 0)) { "a range from block $fromBlock to $toBlock" }
    }
}

/**
 * One page of an address's addr1 history: its [entries], newest first (block, then slot, descending), each a
 * candidate; what the index covers and the key it rests on ([coverage]); and [next], the cursor of the page below,
 * null when the page reaches the end of what the index covers in the range asked.
 */
data class AddressIndexPage(
    val coverage: AddressIndexCoverage,
    val entries: List<AddressIndexEntry>,
    val next: AddressIndexCursor?,
) {
    init {
        require(entries.zipWithNext().all { (a, b) -> a.block > b.block || (a.block == b.block && a.slot > b.slot) }) {
            "a page lists its entries newest first, each once"
        }
    }
}

/** What [AddressIndexHistory.page] answers. */
sealed interface AddressIndexReading {

    /** The [page] asked for. */
    data class Page(val page: AddressIndexPage) : AddressIndexReading

    /** No page, for [reason]. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexReading
}

/** What [AddressIndexHistory.verify] answers. */
sealed interface AddressIndexCheck {

    /** A verdict for the entries asked ([verdicts]: at most one each). */
    data class Verdicts(val verdicts: List<AddressIndexVerdict>) : AddressIndexCheck

    /** Nothing checked, for [reason]. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexCheck
}

/**
 * Why the addr1 history was not read or checked, as a screen states it. Every one but [NotNow] is lasting: asking
 * again changes nothing until what it names changes (the engine's permanent `-32602`). [NotNow] may pass (the
 * engine's retryable `-32000`).
 */
sealed interface AddressIndexRefusal {

    /** Whether asking again, later, may answer. */
    val retryable: Boolean
        get() = this is NotNow

    /** This build's engine has no Swarm reader: the history is not available here (Android, iOS, a release). */
    data object NotInThisBuild : AddressIndexRefusal

    /** No importer key is set: the experimental setting names none, so no index is trusted. */
    data object NoImporter : AddressIndexRefusal

    /** Tor routing is on: the Swarm reader and the block reads of the check do not go through Tor. */
    data object TorRouting : AddressIndexRefusal

    /** No adapter serves [network]'s addr1 history here. */
    data class NetworkNotServed(val network: Network) : AddressIndexRefusal

    /**
     * The engine refused this request for [reason], its words: a cursor or an argument it does not take. Asking the
     * same again changes nothing.
     */
    data class Rejected(val reason: String) : AddressIndexRefusal

    /**
     * No answer right now, for [reason]: the light client is still syncing, Swarm did not deliver what the read
     * needs ([ReadUnavailable.UNPROVEN]), or no engine runs for the network. Never [ReadUnavailable.REFUSED], which
     * is lasting and one of the reasons above.
     */
    data class NotNow(val reason: ReadUnavailable) : AddressIndexRefusal {
        init {
            require(reason != ReadUnavailable.REFUSED) { "a lasting refusal is not a passing one" }
        }
    }
}
