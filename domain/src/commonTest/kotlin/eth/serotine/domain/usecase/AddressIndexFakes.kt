package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal

/**
 * An [AddressIndexHistory] as the port's contract says one behaves, serving [served] only: [pages] by the cursor
 * asked (null: the newest page), [pageRefusal] in place of any page when set, and each check answered by [check],
 * given the entries of the call and how many checks came before it. Every call is recorded.
 */
internal class FakeAddressIndexHistory(
    private val served: Set<Network> = setOf(Network.ETHEREUM),
) : AddressIndexHistory {
    val pages = mutableMapOf<AddressIndexCursor?, AddressIndexPage>()
    var pageRefusal: AddressIndexRefusal? = null
    var check: (entries: List<AddressIndexEntry>, before: Int) -> AddressIndexCheck = { entries, _ ->
        AddressIndexCheck.Verdicts(entries.map { AddressIndexVerdict.Unverified(it) })
    }
    val queries = mutableListOf<Triple<Network, Address, AddressIndexQuery>>()
    val checks = mutableListOf<List<AddressIndexEntry>>()

    override suspend fun page(network: Network, address: Address, query: AddressIndexQuery): AddressIndexReading {
        queries += Triple(network, address, query)
        if (network !in served) return AddressIndexReading.Refused(AddressIndexRefusal.NetworkNotServed(network))
        pageRefusal?.let { return AddressIndexReading.Refused(it) }
        val page = pages[query.cursor]
            ?: return AddressIndexReading.Refused(AddressIndexRefusal.Rejected("no such cursor"))
        return AddressIndexReading.Page(page.copy(entries = page.entries.take(query.limit)))
    }

    override suspend fun verify(
        network: Network,
        address: Address,
        entries: List<AddressIndexEntry>,
    ): AddressIndexCheck {
        require(entries.size in 1..AddressIndexHistory.MAX_CHECKED) { "1 to 50 entries" }
        val before = checks.size
        checks += entries
        if (network !in served) return AddressIndexCheck.Refused(AddressIndexRefusal.NetworkNotServed(network))
        return check(entries, before)
    }
}
