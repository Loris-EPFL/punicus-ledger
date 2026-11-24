package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexHeadGap
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What [EngineAddressIndexHistory] needs of the engine: its state, and its address history
 * (`io.myotis.api.VerifiedReads`'s `supportsAddressHistory`, `addressHistoryJson` and `verifyAddressHistoryJson`;
 * Myotis #899, `docs/guides/address-history.md`), JSON in and out as the engine speaks it.
 */
internal interface AddressHistoryChain {

    /** The engine's state right now. May call into the engine (its status over JNA), so it is asked on the
     * adapter's dispatcher, and may throw. */
    fun snapshot(): EngineSnapshot

    /** Whether the engine serves the address history at all; null while no running light client can be asked. */
    fun supportsAddressHistory(): Boolean?

    /** One page of [address]'s history (`addressHistoryJson`); an `{"error"}` object, or null: no answer. */
    fun addressHistory(address: String, optionsJson: String): String?

    /** The check of [entriesJson] (`verifyAddressHistoryJson`): a JSON array, an `{"error"}` object, or null. */
    fun verifyAddressHistory(address: String, entriesJson: String): String?
}

/**
 * [AddressIndexHistory] over the Myotis engine's address history (issue #334; `.claude/rules/data-sources.md`, *The
 * history may also take the addr1 index from Swarm*): the engine reads the addr1 index through its own Swarm light
 * node, checks every chunk, and checks each entry against verified chain data. This adapter adds no Swarm client, no
 * gateway and no HTTP. It serves [served] alone (the index is mainnet's); another network is refused
 * ([AddressIndexRefusal.NetworkNotServed]).
 *
 * The engine's answers are read strictly, every one of them mapped to the port's terms or refused, never thrown:
 * - **A refusal** is the engine's `{"error","code":-32602}` (lasting) or an `{"error"}` without that code
 *   (passing, the engine's `-32000`): a build without the Swarm reader, or a phone, is
 *   [AddressIndexRefusal.NotInThisBuild]; no importer key set is [AddressIndexRefusal.SettingOff]; Tor routing is
 *   [AddressIndexRefusal.TorRouting]; the checkpoint table's alarm (a message starting "checkpoint table alarm:")
 *   is [AddressIndexRefusal.CheckpointAlarm]; any other lasting one is [AddressIndexRefusal.Rejected], with the
 *   engine's words. A passing one is [AddressIndexRefusal.NotNow]: syncing while the light client is not synced,
 *   else Swarm or the peers did not deliver ([ReadUnavailable.UNPROVEN]).
 * - **A page** is taken whole or not at all: an entry, a range or a gap that does not read as the engine documents
 *   it, or a page out of order, refuses the read ([AddressIndexRefusal.Rejected]). The `state` of a page's entries
 *   (a verdict the engine kept) is not read: a verdict is only ever taken from the check.
 * - **A verdict** is the engine's per entry: verified (a row, which needs every role but `TRACE` proven, else it is
 *   unverified), contradicted, unverified, or unavailable. One whose reason is the checkpoint table's alarm refuses
 *   the whole check. A verified transaction's outcome is not reported by the engine, so
 *   [AddressIndexTransaction.succeeded] stays null; the EIP-7708 log of an ETH transfer is no token's transfer, and
 *   is not taken as one (its ether is the transaction's value).
 *
 * The light client's state decides what is asked: none on this device is [AddressIndexRefusal.NoEngine], a network
 * turned off or not started yet is passing, a stale checkpoint is [AddressIndexRefusal.StaleAnchor]. Calls block on
 * the engine (a check up to about 90 s), so they run on [dispatcher]. [hasReader] waits, every [readerPoll], for a
 * running light client to ask, and answers false at once on a device with no engine; the engine's answer is kept, so
 * that every later ask is answered at once, whatever the light client does meanwhile.
 */
class EngineAddressIndexHistory internal constructor(
    private val served: Network,
    private val chain: AddressHistoryChain?,
    private val dispatcher: CoroutineDispatcher,
    private val readerPoll: Duration,
) : AddressIndexHistory {

    /** The engine's word on its reader, once it gave one: a build's engine cannot change it within a run. */
    @Volatile
    private var reader: Boolean? = null

    override suspend fun hasReader(): Boolean {
        reader?.let { return it }
        val chain = chain ?: return false
        while (true) {
            val known = withContext(dispatcher) {
                try {
                    chain.supportsAddressHistory()
                } catch (e: Throwable) {
                    // Throwable: the call crosses JNA. A failed call is no answer: asked again at the next poll.
                    null
                }
            }
            if (known != null) {
                reader = known
                return known
            }
            if (snapshotOf(chain) == EngineSnapshot.Down) return false
            delay(readerPoll)
        }
    }

    override suspend fun page(network: Network, address: Address, query: AddressIndexQuery): AddressIndexReading {
        if (network != served) return AddressIndexReading.Refused(AddressIndexRefusal.NetworkNotServed(network))
        val chain = chain ?: return AddressIndexReading.Refused(AddressIndexRefusal.NoEngine)
        stateRefusal(snapshotOf(chain))?.let { return AddressIndexReading.Refused(it) }
        val answer = withContext(dispatcher) {
            try {
                chain.addressHistory(address.hex, optionsOf(query))
            } catch (e: Throwable) {
                null
            }
        } ?: return AddressIndexReading.Refused(noAnswer(chain))
        val json = parse(answer) ?: return AddressIndexReading.Refused(malformed("page"))
        refusalOf(json, chain)?.let { return AddressIndexReading.Refused(it) }
        val page = pageOf(json as? JsonObject) ?: return AddressIndexReading.Refused(malformed("page"))
        return AddressIndexReading.Page(page)
    }

    override suspend fun verify(
        network: Network,
        address: Address,
        entries: List<AddressIndexEntry>,
    ): AddressIndexCheck {
        require(entries.size in 1..AddressIndexHistory.MAX_CHECKED) {
            "1 to ${AddressIndexHistory.MAX_CHECKED} entries"
        }
        if (network != served) return AddressIndexCheck.Refused(AddressIndexRefusal.NetworkNotServed(network))
        val chain = chain ?: return AddressIndexCheck.Refused(AddressIndexRefusal.NoEngine)
        stateRefusal(snapshotOf(chain))?.let { return AddressIndexCheck.Refused(it) }
        val answer = withContext(dispatcher) {
            try {
                chain.verifyAddressHistory(address.hex, entriesOf(entries))
            } catch (e: Throwable) {
                null
            }
        } ?: return AddressIndexCheck.Refused(noAnswer(chain))
        val json = parse(answer) ?: return AddressIndexCheck.Refused(malformed("check"))
        refusalOf(json, chain)?.let { return AddressIndexCheck.Refused(it) }
        val array = json as? JsonArray ?: return AddressIndexCheck.Refused(malformed("check"))
        val verdicts = array.mapNotNull { element ->
            val verdict = element as? JsonObject ?: return AddressIndexCheck.Refused(malformed("check"))
            // The alarm withdraws the trust every deep verdict rests on: the whole check is refused.
            val reason = text(verdict["reason"])
            if (reason != null && reason.startsWith(ALARM_PREFIX)) {
                return AddressIndexCheck.Refused(AddressIndexRefusal.CheckpointAlarm(reason))
            }
            // A verdict that does not read is no verdict: its entry is answered below as unavailable.
            verdictOf(verdict)
        }
        // One verdict per entry asked (the port's rule): an entry with none that reads is unavailable, and says why.
        val answered = verdicts.filter { verdict -> entries.any { it.isOf(verdict) } }
        val missing = entries.filter { asked -> answered.none { asked.isOf(it) } }
        return AddressIndexCheck.Verdicts(
            answered + missing.map { AddressIndexVerdict.Unavailable(it, NO_VERDICT_READ) },
        )
    }

    /** Whether [verdict] is this entry's: the same block, slot and roles. */
    private fun AddressIndexEntry.isOf(verdict: AddressIndexVerdict): Boolean =
        block == verdict.entry.block && slot == verdict.entry.slot && roles == verdict.entry.roles

    /** Why nothing is asked of the engine in [snapshot]'s state; null when it is asked. */
    private fun stateRefusal(snapshot: EngineSnapshot): AddressIndexRefusal? = when (snapshot) {
        EngineSnapshot.Down, EngineSnapshot.Failed -> AddressIndexRefusal.NoEngine
        is EngineSnapshot.Stopped -> AddressIndexRefusal.NotNow(ReadUnavailable.NETWORK_OFF)
        EngineSnapshot.Booting -> AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        is EngineSnapshot.Running ->
            AddressIndexRefusal.StaleAnchor.takeIf { snapshot.phase == EngineSnapshot.Phase.STALE_ANCHOR }
    }

    /**
     * The engine's state, asked on [dispatcher] (the status crosses JNA); a call that throws is read as a light client
     * that failed ([EngineSnapshot.Failed], so [AddressIndexRefusal.NoEngine]), never thrown on.
     */
    private suspend fun snapshotOf(chain: AddressHistoryChain): EngineSnapshot = withContext(dispatcher) {
        try {
            chain.snapshot()
        } catch (e: Throwable) {
            EngineSnapshot.Failed
        }
    }

    /** No answer at all: the light client went away during the call, or the engine failed in it. */
    private suspend fun noAnswer(chain: AddressHistoryChain): AddressIndexRefusal =
        stateRefusal(snapshotOf(chain)) ?: AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN)

    /** The engine's `{"error"}` answer as a refusal; null when [json] is no error. */
    private suspend fun refusalOf(json: JsonElement, chain: AddressHistoryChain): AddressIndexRefusal? {
        val error = json as? JsonObject ?: return null
        val message = text(error["error"]) ?: return null
        val lasting = (error["code"] as? JsonPrimitive)?.intOrNull == INVALID_PARAMS
        return when {
            message.startsWith(ALARM_PREFIX) -> AddressIndexRefusal.CheckpointAlarm(message)
            !lasting -> {
                val synced = (snapshotOf(chain) as? EngineSnapshot.Running)?.phase == EngineSnapshot.Phase.SYNCED
                AddressIndexRefusal.NotNow(if (synced) ReadUnavailable.UNPROVEN else ReadUnavailable.SYNCING)
            }
            NOT_IN_THIS_BUILD.any { message.contains(it) } -> AddressIndexRefusal.NotInThisBuild
            message.startsWith(NO_IMPORTER) -> AddressIndexRefusal.SettingOff
            message.contains(TOR) -> AddressIndexRefusal.TorRouting
            else -> AddressIndexRefusal.Rejected(message)
        }
    }

    private fun malformed(what: String) = AddressIndexRefusal.Rejected("the engine's $what did not read as documented")

    internal companion object {
        /** Why an entry asked is unavailable when the engine's answer holds no verdict of it that reads. */
        const val NO_VERDICT_READ = "the engine's answer held no verdict of it that read as documented"

        /** JSON-RPC's invalid params: the engine's lasting refusal. */
        const val INVALID_PARAMS = -32602

        /** How the engine's checkpoint table alarm starts (Myotis's `deep::ALARM_PREFIX`). */
        const val ALARM_PREFIX = "checkpoint table alarm:"

        /**
         * The engine's words for a build without the reader: the API's default (`ADDRESS_HISTORY_REFUSED`), a Rust
         * engine built without `swarm`, and a phone (Myotis's `addrhist::build_refusal`).
         */
        val NOT_IN_THIS_BUILD = listOf(
            "needs the Rust engine built with its Swarm reader",
            "needs the Swarm reader, which this build does not include",
            "not available on mobile",
        )

        /** The engine's refusal without an importer key (`addrhist::refusal`). */
        const val NO_IMPORTER = "no importer key is set for the address history"

        /** The engine's refusals while Tor routing is on (`addrhist::refusal`, `verify_json`). */
        const val TOR = "while Tor routing is on"

        private val JSON = Json

        private val HASH = Regex("^0x[0-9a-f]{64}$")

        /** [text] as JSON, or null when it is none. */
        fun parse(text: String): JsonElement? = try {
            JSON.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** A non-negative JSON number, or null. */
        private fun number(element: JsonElement?): Long? =
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }

        /** [query] as the engine's options: every field it names, so that each is applied or the call refused. */
        fun optionsOf(query: AddressIndexQuery): String = buildJsonObject {
            query.cursor?.let { put("cursor", it.token) }
            put("limit", query.limit)
            if (query.roles.isNotEmpty()) putJsonArray("roles") { query.roles.forEach { add(wireName(it)) } }
            query.fromBlock?.let { put("fromBlock", it) }
            query.toBlock?.let { put("toBlock", it) }
        }.toString()

        /** [entries] as the check takes them: block, slot and roles only. */
        fun entriesOf(entries: List<AddressIndexEntry>): String = buildJsonArray {
            entries.forEach { entry ->
                addJsonObject {
                    put("block", entry.block)
                    put("slot", entry.slot)
                    putJsonArray("roles") { entry.roles.sortedBy { it.ordinal }.forEach { add(wireName(it)) } }
                }
            }
        }.toString()

        private fun wireName(role: AddressIndexRole): String = role.name.lowercase()

        /** The roles [element] names, or null when it is not a list of known role names. */
        private fun rolesOf(element: JsonElement?): Set<AddressIndexRole>? {
            val array = element as? JsonArray ?: return null
            return array.mapTo(LinkedHashSet()) { name ->
                val wire = text(name) ?: return null
                AddressIndexRole.entries.firstOrNull { wireName(it) == wire } ?: return null
            }
        }

        /** A `{"fromBlock","toBlock"}` range of one block at least, or null. */
        private fun rangeOf(element: JsonElement?): LongRange? {
            val range = element as? JsonObject ?: return null
            val from = number(range["fromBlock"]) ?: return null
            val to = number(range["toBlock"]) ?: return null
            return (from..to).takeIf { !it.isEmpty() }
        }

        /** The page [json] holds, or null when any part of it does not read as documented. */
        fun pageOf(json: JsonObject?): AddressIndexPage? {
            json ?: return null
            return try {
                val importer = text(json["importer"])?.let(Address::parse) ?: return null
                val headUpdate = number(json["headUpdate"]) ?: return null
                val coveredFrom = number(json["coveredFrom"]) ?: return null
                val coveredTo = number(json["coveredTo"]) ?: return null
                val headGap = when (val gap = json["headGap"]) {
                    null, JsonNull -> null
                    is JsonObject -> AddressIndexHeadGap(
                        number(gap["fromBlock"]) ?: return null,
                        when (val to = gap["toBlock"]) {
                            null, JsonNull -> null
                            else -> number(to) ?: return null
                        },
                    )
                    else -> return null
                }
                val tailGap = when (val gap = json["tailGap"]) {
                    null, JsonNull -> null
                    else -> rangeOf(gap) ?: return null
                }
                val gaps = (json["gaps"] as? JsonArray ?: return null).map { rangeOf(it) ?: return null }
                val entries = (json["entries"] as? JsonArray ?: return null).map { element ->
                    val entry = element as? JsonObject ?: return null
                    AddressIndexEntry(
                        number(entry["block"]) ?: return null,
                        number(entry["slot"])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
                        rolesOf(entry["roles"])?.takeIf { it.isNotEmpty() } ?: return null,
                    )
                }
                val next = when (val cursor = json["next"]) {
                    null, JsonNull -> null
                    else -> text(cursor)?.takeIf { it.isNotBlank() }?.let(::AddressIndexCursor) ?: return null
                }
                // An index that covers nothing yet names no range of its own.
                val covered = (coveredFrom..coveredTo).takeIf { !it.isEmpty() }
                AddressIndexPage(
                    AddressIndexCoverage(importer, headUpdate, covered, headGap, tailGap, gaps),
                    entries,
                    next,
                )
            } catch (e: IllegalArgumentException) {
                // A range or an order the port's types refuse: the page is not what the engine documents.
                null
            }
        }

        /** The engine's verdict [json] of one entry; null when it does not read as documented. */
        fun verdictOf(json: JsonObject): AddressIndexVerdict? {
            val entry = try {
                AddressIndexEntry(
                    number(json["block"]) ?: return null,
                    number(json["slot"])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
                    rolesOf(json["roles"])?.takeIf { it.isNotEmpty() } ?: return null,
                )
            } catch (e: IllegalArgumentException) {
                return null
            }
            val reason = text(json["reason"])
            return when (text(json["state"])) {
                "unavailable" -> AddressIndexVerdict.Unavailable(entry, reason ?: "the engine gave no reason")
                "unverified" -> AddressIndexVerdict.Unverified(entry, reason)
                "contradicted" -> {
                    val blockHash = text(json["blockHash"])?.lowercase()?.takeIf(HASH::matches) ?: return null
                    val final = (json["final"] as? JsonPrimitive)?.booleanOrNull ?: return null
                    val contradicted = rolesOf(json["contradicted"]) ?: return null
                    try {
                        AddressIndexVerdict.Contradicted(entry, blockHash, final, contradicted)
                    } catch (e: IllegalArgumentException) {
                        // A contradiction of no role, or of one it was not asked about, is not one.
                        AddressIndexVerdict.Unverified(entry, "the engine's contradiction did not read as one")
                    }
                }
                "verified" -> verifiedOf(entry, json)
                else -> null
            }
        }

        /** A verified verdict as a row; unverified when the engine left a role but `TRACE` unchecked. */
        private fun verifiedOf(entry: AddressIndexEntry, json: JsonObject): AddressIndexVerdict? {
            val blockHash = text(json["blockHash"])?.lowercase()?.takeIf(HASH::matches) ?: return null
            val final = (json["final"] as? JsonPrimitive)?.booleanOrNull ?: return null
            val confirmed = rolesOf(json["confirmed"]) ?: return null
            val unchecked = rolesOf(json["unchecked"]) ?: return null
            // A verified verdict that also names a contradicted role is no row: it does not read as one.
            when (val contradicted = json["contradicted"]) {
                null, JsonNull -> Unit
                else -> if (rolesOf(contradicted)?.isEmpty() != true) {
                    return AddressIndexVerdict.Unverified(entry, "the engine's verdict named a contradicted role")
                }
            }
            val transaction = if (entry.slot == 0) null else transactionOf(json) ?: return null
            return try {
                val row = AddressIndexRow(entry, blockHash, final, confirmed, unchecked, transaction)
                AddressIndexVerdict.Verified(row)
            } catch (e: IllegalArgumentException) {
                // Every role but TRACE must be proven for a row (AddressIndexRow's rule).
                AddressIndexVerdict.Unverified(entry, "the engine left a role of it unchecked")
            }
        }

        /** A verified transaction entry's transaction, or null when it does not read. */
        private fun transactionOf(json: JsonObject): AddressIndexTransaction? {
            val hash = text(json["txHash"])?.lowercase()?.takeIf(HASH::matches) ?: return null
            val from = text(json["from"])?.let(Address::parse) ?: return null
            val to = when (val recipient = json["to"]) {
                null, JsonNull -> null
                else -> text(recipient)?.let(Address::parse) ?: return null
            }
            val value = text(json["value"])?.let(::quantityOf)?.toWei() ?: return null
            val transfer = when (val logged = json["transfer"]) {
                null, JsonNull -> null
                is JsonObject -> transferOf(logged) ?: return null
                else -> return null
            }
            return AddressIndexTransaction(
                hash,
                from,
                to,
                value,
                // The protocol's log of an ETH transfer is no token's: the ether it moves is the value.
                transfer?.takeUnless { it.isEth },
                succeeded = null,
            )
        }

        private fun transferOf(json: JsonObject): TokenTransfer? {
            val token = text(json["token"])?.let(Address::parse) ?: return null
            val from = text(json["from"])?.let(Address::parse) ?: return null
            val to = text(json["to"])?.let(Address::parse) ?: return null
            val amount = text(json["amount"])
            val tokenId = text(json["tokenId"])
            return when {
                amount != null && tokenId == null ->
                    TokenTransfer.Fungible(token, from, to, quantityOf(amount) ?: return null)
                tokenId != null && amount == null ->
                    TokenTransfer.Nft(token, from, to, quantityOf(tokenId) ?: return null)
                else -> null
            }
        }

        /** A `0x` hex quantity as a number, or null when it is not one (at most 32 bytes). */
        fun quantityOf(text: String): UInt256? {
            if (!text.startsWith("0x") || text.length < 3 || text.length > 66) return null
            val digits = text.substring(2)
            if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val even = digits.padStart(digits.length + digits.length % 2, '0')
            return UInt256.fromBigEndian(even.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
    }
}
