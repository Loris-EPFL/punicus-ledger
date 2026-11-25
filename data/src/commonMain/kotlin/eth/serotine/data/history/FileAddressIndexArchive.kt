package eth.serotine.data.history

import eth.serotine.data.files.NewerContent
import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexHeadGap
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressIndexArchive
import eth.serotine.domain.repository.KeptAddressIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AddressIndexArchive] kept in [directory], one file per account (`address-index-<chain id>-<address>.json`, a
 * [VersionedJsonFile] each), so that one account's list is read and written alone, and forgetting it deletes its
 * file. One operation at a time. Public data only: blocks,
 * transactions and the importer's key, as the engine verified them. A file that cannot be used is handled as
 * [VersionedJsonFile] says: a newer format, or one that cannot be read, reads as nothing kept and is never written
 * over; a damaged one is set aside, and the list read from the index again. Anything a value of the domain would
 * refuse (a row of a block not final, a hash of the wrong shape) damages the file.
 */
class FileAddressIndexArchive(
    private val directory: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AddressIndexArchive {

    private val lock = Mutex()
    private val files = mutableMapOf<AccountId, VersionedJsonFile<Kept>>()

    override suspend fun read(account: AccountId): KeptAddressIndex? = lock.withLock {
        fileOf(account).change(notSaved = null) { Decision(it.kept) }
    }

    override suspend fun write(account: AccountId, kept: KeptAddressIndex): Boolean = lock.withLock {
        fileOf(account).change(notSaved = false) { Decision(true, Kept(kept)) }
    }

    /**
     * Deletes the account's file, and with it every file named after it (a copy of a damaged one set aside, a write's
     * leftover), so that nothing in [directory] names the address any more.
     */
    override suspend fun forget(account: AccountId): Boolean = lock.withLock {
        files -= account
        val prefix = Path(pathOf(account)).name
        withContext(dispatcher) {
            val dir = Path(directory)
            try {
                if (SystemFileSystem.exists(dir)) {
                    SystemFileSystem.list(dir).filter { it.name.startsWith(prefix) }.forEach {
                        SystemFileSystem.delete(it, mustExist = false)
                    }
                }
                true
            } catch (e: Exception) {
                println("Punicus: could not delete an addr1 history (${e::class.simpleName})")
                false
            }
        }
    }

    private fun pathOf(account: AccountId): String =
        "$directory/address-index-${account.network.chainId}-${account.address.hex}.json"

    /** The account's file; under [lock]. */
    private fun fileOf(account: AccountId): VersionedJsonFile<Kept> =
        files.getOrPut(account) {
            VersionedJsonFile(
                path = pathOf(account),
                dispatcher = dispatcher,
                what = "addr1 history",
                formatVersion = FORMAT_VERSION,
                empty = Kept(null),
                decode = { _, text -> Kept(JSON.decodeFromString(StoredFile.serializer(), text).kept?.toKept()) },
                encode = { file ->
                    val stored = StoredFile(FORMAT_VERSION, file.kept?.let(::StoredKept))
                    JSON.encodeToString(StoredFile.serializer(), stored)
                },
            )
        }

    /** What one file holds: the account's list, or none ([kept] null: nothing kept, or forgotten). */
    private class Kept(val kept: KeptAddressIndex?)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredFile(val version: Int, val kept: StoredKept? = null)

    @Serializable
    private class StoredKept(
        val coverage: StoredCoverage,
        val top: Long,
        val rows: List<StoredRow>,
        val recheck: List<StoredEntry>,
        val unverified: List<StoredEntry>,
        val contradicted: List<StoredEntry>,
        val traceOnly: List<StoredEntry>,
        val unread: List<StoredRange>,
        val next: String? = null,
    ) {
        constructor(kept: KeptAddressIndex) : this(
            StoredCoverage(kept.coverage),
            kept.top,
            kept.rows.map(::StoredRow),
            kept.recheck.map(::StoredEntry),
            kept.unverified.map(::StoredEntry),
            kept.contradicted.map(::StoredEntry),
            kept.traceOnly.map(::StoredEntry),
            kept.unread.map(::StoredRange),
            kept.next?.token,
        )

        fun toKept(): KeptAddressIndex = KeptAddressIndex(
            coverage = coverage.toCoverage(),
            top = top,
            rows = rows.map { it.toRow() },
            recheck = recheck.map { it.toEntry() },
            unverified = unverified.map { it.toEntry() },
            contradicted = contradicted.map { it.toEntry() },
            traceOnly = traceOnly.map { it.toEntry() },
            unread = unread.map { it.toRange() },
            next = next?.let(::AddressIndexCursor),
        )
    }

    @Serializable
    private class StoredCoverage(
        val importer: String,
        val headUpdate: Long,
        val covered: StoredRange? = null,
        val headGapFrom: Long? = null,
        val headGapTo: Long? = null,
        val tailGap: StoredRange? = null,
        val gaps: List<StoredRange> = emptyList(),
    ) {
        constructor(coverage: AddressIndexCoverage) : this(
            coverage.importer.hex,
            coverage.headUpdate,
            coverage.covered?.let(::StoredRange),
            coverage.headGap?.fromBlock,
            coverage.headGap?.toBlock,
            coverage.tailGap?.let(::StoredRange),
            coverage.gaps.map(::StoredRange),
        )

        fun toCoverage(): AddressIndexCoverage = AddressIndexCoverage(
            importer = Address.of(importer),
            headUpdate = headUpdate,
            covered = covered?.toRange(),
            headGap = headGapFrom?.let { AddressIndexHeadGap(it, headGapTo) },
            tailGap = tailGap?.toRange(),
            gaps = gaps.map { it.toRange() },
        )
    }

    @Serializable
    private class StoredRange(val from: Long, val to: Long) {
        constructor(range: LongRange) : this(range.first, range.last)

        fun toRange(): LongRange = from..to
    }

    @Serializable
    private class StoredEntry(val block: Long, val slot: Int, val roles: List<String>) {
        constructor(entry: AddressIndexEntry) : this(entry.block, entry.slot, entry.roles.map { it.name })

        fun toEntry(): AddressIndexEntry = AddressIndexEntry(block, slot, roles.mapTo(HashSet(), ::roleOf))
    }

    @Serializable
    private class StoredRow(
        val entry: StoredEntry,
        val blockHash: String,
        val final: Boolean,
        val confirmed: List<String>,
        val unchecked: List<String>,
        val transaction: StoredTransaction? = null,
    ) {
        constructor(row: AddressIndexRow) : this(
            StoredEntry(row.entry),
            row.blockHash,
            row.final,
            row.confirmed.map { it.name },
            row.unchecked.map { it.name },
            row.transaction?.let(::StoredTransaction),
        )

        fun toRow(): AddressIndexRow = AddressIndexRow(
            entry = entry.toEntry(),
            blockHash = blockHash,
            final = final,
            confirmed = confirmed.mapTo(HashSet(), ::roleOf),
            unchecked = unchecked.mapTo(HashSet(), ::roleOf),
            transaction = transaction?.toTransaction(),
        )
    }

    @Serializable
    private class StoredTransaction(
        val hash: String,
        val from: String,
        val to: String? = null,
        val value: String,
        val transfer: StoredTransfer? = null,
        val succeeded: Boolean? = null,
    ) {
        constructor(transaction: AddressIndexTransaction) : this(
            transaction.hash,
            transaction.from.hex,
            transaction.to?.hex,
            transaction.value.decimal,
            transaction.transfer?.let(::StoredTransfer),
            transaction.succeeded,
        )

        fun toTransaction(): AddressIndexTransaction = AddressIndexTransaction(
            hash = hash,
            from = Address.of(from),
            to = to?.let(Address::of),
            value = Wei.of(value),
            transfer = transfer?.toTransfer(),
            succeeded = succeeded,
        )
    }

    /** A token's transfer: [kind] "fungible" with [amount], or "nft" with the token id in [amount]. */
    @Serializable
    private class StoredTransfer(
        val kind: String,
        val token: String,
        val from: String,
        val to: String,
        val amount: String,
    ) {
        constructor(transfer: TokenTransfer) : this(
            kind = when (transfer) {
                is TokenTransfer.Fungible -> FUNGIBLE
                is TokenTransfer.Nft -> NFT
            },
            token = transfer.token.hex,
            from = transfer.from.hex,
            to = transfer.to.hex,
            amount = when (transfer) {
                is TokenTransfer.Fungible -> transfer.amount.toDecimal()
                is TokenTransfer.Nft -> transfer.tokenId.toDecimal()
            },
        )

        fun toTransfer(): TokenTransfer = when (kind) {
            FUNGIBLE -> TokenTransfer.Fungible(Address.of(token), Address.of(from), Address.of(to), UInt256.of(amount))
            NFT -> TokenTransfer.Nft(Address.of(token), Address.of(from), Address.of(to), UInt256.of(amount))
            else -> throw NewerContent("a token transfer of a kind this version does not know")
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val FUNGIBLE = "fungible"
        const val NFT = "nft"
        val JSON = Json { encodeDefaults = true }

        /** The role named [name]; one this version does not know was written by a newer one. */
        fun roleOf(name: String): AddressIndexRole = AddressIndexRole.entries.firstOrNull { it.name == name }
            ?: throw NewerContent("an addr1 role this version does not know")
    }
}
