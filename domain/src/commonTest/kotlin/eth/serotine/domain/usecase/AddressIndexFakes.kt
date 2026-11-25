package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.AddressIndexArchive
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.KeptAddressIndex
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReaderSettings
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.AddressIndexSetting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

/**
 * An [AddressIndexHistory] as the port's contract says one behaves, serving [served] only: [pages] by the cursor
 * asked (null: the newest page), [pageRefusal] in place of any page when set, and each check answered by [check],
 * given the entries of the call and how many checks came before it, after [checkTime] (virtual time in tests). Every
 * call is recorded.
 */
internal class FakeAddressIndexHistory(
    private val served: Set<Network> = setOf(Network.ETHEREUM),
) : AddressIndexHistory {
    val pages = mutableMapOf<AddressIndexCursor?, AddressIndexPage>()
    var pageRefusal: AddressIndexRefusal? = null
    var check: (entries: List<AddressIndexEntry>, before: Int) -> AddressIndexCheck = { entries, _ ->
        AddressIndexCheck.Verdicts(entries.map { AddressIndexVerdict.Unverified(it) })
    }
    var checkTime: Duration = Duration.ZERO

    /** How long the check of these entries takes, where a test wants one block slower than the others. */
    var checkTimeOf: (List<AddressIndexEntry>) -> Duration = { checkTime }
    var pageTime: Duration = Duration.ZERO
    val queries = mutableListOf<Triple<Network, Address, AddressIndexQuery>>()
    val checks = mutableListOf<List<AddressIndexEntry>>()

    /** What [hasReader] answers: a reader, unless a test says this build has none. */
    var reader: Boolean = true

    private val answered = MutableStateFlow(true)

    /** Whether [hasReader] answers yet: false holds it, as an engine whose Ethereum light client does not run. */
    var readerAnswered: Boolean
        get() = answered.value
        set(value) {
            answered.value = value
        }

    override suspend fun hasReader(): Boolean {
        answered.first { it }
        return reader
    }

    override suspend fun page(network: Network, address: Address, query: AddressIndexQuery): AddressIndexReading {
        queries += Triple(network, address, query)
        delay(pageTime)
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
        delay(checkTimeOf(entries))
        if (network !in served) return AddressIndexCheck.Refused(AddressIndexRefusal.NetworkNotServed(network))
        return check(entries, before)
    }
}

/** An [AddressIndexSetting] in memory, as the port's contract says one behaves; [failSaves] makes every save fail. */
internal class MemoryAddressIndexSetting : AddressIndexSetting {
    val choice = MutableStateFlow<AddressIndexChoice>(AddressIndexChoice.NotChosen)
    var failSaves = false

    override fun observe(): Flow<AddressIndexChoice> = choice

    override suspend fun trust(importer: Address?): Boolean {
        if (failSaves || choice.value is AddressIndexChoice.Unreadable) return false
        choice.value = AddressIndexChoice.Chosen(importer)
        return true
    }
}

/** An [AddressIndexReaderSettings] that records what it was told. */
internal class RecordingReaderSettings : AddressIndexReaderSettings {
    val applied = mutableListOf<Address?>()

    override suspend fun apply(importer: Address?): Boolean {
        applied += importer
        return true
    }
}

/** An [AddressIndexArchive] in memory, as the port's contract says one behaves; [failWrites] makes every write fail. */
internal class MemoryAddressIndexArchive : AddressIndexArchive {
    val kept = mutableMapOf<AccountId, KeptAddressIndex>()
    var failWrites = false

    override suspend fun read(account: AccountId): KeptAddressIndex? = kept[account]

    override suspend fun write(account: AccountId, kept: KeptAddressIndex): Boolean {
        if (failWrites) return false
        this.kept[account] = kept
        return true
    }

    override suspend fun forget(account: AccountId): Boolean {
        if (failWrites) return false
        kept -= account
        return true
    }
}
