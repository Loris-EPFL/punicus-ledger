package eth.serotine.data.clearsigning

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSignedField
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.ClearSigningLookups
import eth.serotine.domain.model.ClearSigningToken
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedField
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.UInt256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ERC-7730 registry's own test vectors (`registry/<entity>/testsv2/<name>.tests.json`, vendored and pinned with the
 * descriptors by scripts/clear-signing) run through this release's catalog ([BuiltInClearSigningCatalog]) and
 * [ClearSignFormatter] (docs/clear-signing.md, A1, step 6). A vector of a deployment the curated list does not ship is
 * skipped and counted. Each vector must give its expected intent, owner and fields, except where Punicus differs on
 * purpose ([DIFFERENCES], each with its rule); a vector that differs for any other reason fails the test.
 */
class ClearSigningConformanceTest {

    private val registry = File(requireNotNull(System.getProperty("serotine.clearSigningRegistry")))
    private val catalog = BuiltInClearSigningCatalog()

    @Test
    fun theCatalogIsReadInFull() {
        assertEquals(11, catalog.deployments.size)
        assertTrue(catalog.deployments.values.all { it.descriptors.isNotEmpty() })
    }

    @Test
    fun aCatalogWhoseHashIsNotItsPinIsEmpty() {
        assertEquals(emptyMap(), BuiltInClearSigningCatalog(CLEAR_SIGNING_CATALOG_JSON, "00".repeat(32)).deployments)
    }

    @Test
    fun everyVectorOfAShippedDeploymentReadsAsItExpects() = runTest {
        val outcomes = mutableListOf<String>()
        var ran = 0
        var skipped = 0
        val sources = catalog.deployments.values.flatMap { it.descriptors }.map { it.source }.toSet()
        for (source in sources.sorted()) {
            val stem = File(source)
            val file = File(registry, "${stem.parent}/testsv2/${stem.nameWithoutExtension}.tests.json")
            if (!file.isFile) continue
            val suite = Json.parseToJsonElement(file.readText()).jsonObject
            val lookups = lookups(suite["dataProvider"] as? JsonObject)
            for (vector in suite.getValue("tests").jsonArray.map { it.jsonObject }) {
                val name = "${stem.name}: ${vector.getValue("description").jsonPrimitive.content}"
                val result = run(source, vector, lookups) ?: run {
                    skipped++
                    null
                } ?: continue
                ran++
                val expected = vector.getValue("expected").jsonObject
                val difference = compare(expected, result)
                if (difference != null && DIFFERENCES.none { (prefix, _) -> name.startsWith(prefix) }) {
                    outcomes += "$name\n    $difference"
                }
            }
        }
        println("clear-signing conformance: $ran vectors run, $skipped skipped (deployments not shipped)")
        assertTrue(ran >= MIN_VECTORS, "only $ran vectors ran")
        assertEquals(emptyList(), outcomes, outcomes.joinToString("\n"))
    }

    /** The vector run on its descriptor, or null when its deployment is not one this release ships. */
    private suspend fun run(source: String, vector: JsonObject, lookups: ClearSigningLookups): ClearSignResult? {
        val from = (vector["from"] as? JsonPrimitive)?.content?.let(Address::parse) ?: Address.ZERO
        vector["rawTx"]?.let { raw ->
            val tx = Transaction.decode(raw.jsonPrimitive.content.removePrefix("0x").hexBytes()) ?: return null
            val network = Network.entries.firstOrNull { it.chainId == tx.chainId } ?: return null
            val deployment = catalog.deployment(network, tx.to) ?: return null
            val descriptor = deployment.descriptors.firstOrNull { it.source == source } ?: return null
            return ClearSignFormatter.formatCall(
                descriptor, network, from, tx.to, tx.value, tx.data, lookups, delegatesToItself = deployment.delegatesToItself,
            )
        }
        val data = typedData(vector.getValue("data").jsonObject)
        val chainId = (data.domain["chainId"] as? TypedValue.Number)?.digits?.toLongOrNull() ?: return null
        val network = Network.entries.firstOrNull { it.chainId == chainId } ?: return null
        val verifier = (data.domain["verifyingContract"] as? TypedValue.Text)?.text?.let(Address::parse) ?: return null
        val deployment = catalog.deployment(network, verifier) ?: return null
        val descriptor = deployment.descriptors.firstOrNull { it.source == source } ?: return null
        return ClearSignFormatter.formatTypedData(descriptor, network, from, data, lookups)
    }

    /** Null when [result] reads as [expected] says; otherwise what differs. */
    private fun compare(expected: JsonObject, result: ClearSignResult): String? {
        if (result !is ClearSignResult.ClearSigned) return "not covered: ${(result as ClearSignResult.NotCovered).reason}"
        val intent = expected.getValue("intent").jsonPrimitive.content
        if (intent != result.intent) return "intent '${result.intent}', expected '$intent'"
        val owner = expected.getValue("owner").jsonPrimitive.content
        if (owner != result.owner) return "owner '${result.owner}', expected '$owner'"
        (expected["interpolatedIntent"] as? JsonPrimitive)?.content?.let {
            if (it != result.interpolatedIntent) return "interpolated intent '${result.interpolatedIntent}', expected '$it'"
        }
        val want = expected.getValue("fields").jsonArray.map { it.jsonObject }
        if (want.size != result.fields.size) {
            return "fields ${result.fields.map { it.label to shown(it) }}, expected ${want.map { it["label"]?.jsonPrimitive?.content to it["value"] }}"
        }
        for ((field, got) in want.zip(result.fields)) {
            val label = field.getValue("label").jsonPrimitive.content
            if (label != got.label) return "label '${got.label}', expected '$label'"
            when (val value = field.getValue("value")) {
                is JsonPrimitive -> if (value.content != shown(got)) return "$label: '${shown(got)}', expected '${value.content}'"
                is JsonObject -> {
                    val nested = (got as? ClearSignedField.Nested)?.result ?: return "$label: not a nested call"
                    compare(value, nested)?.let { return "$label > $it" }
                }
                else -> return "$label: an expected value of another shape"
            }
        }
        return null
    }

    private fun shown(field: ClearSignedField): String = when (field) {
        is ClearSignedField.Value -> field.shown
        is ClearSignedField.Nested -> field.data
    }

    private fun lookups(provider: JsonObject?): ClearSigningLookups {
        val tokens = (provider?.get("tokens") as? JsonObject)?.entries?.associate { (address, token) ->
            Address.of(address) to ClearSigningToken(
                token.jsonObject.getValue("symbol").jsonPrimitive.content,
                token.jsonObject.getValue("decimals").jsonPrimitive.content.toInt(),
            )
        } ?: emptyMap()
        val names = (provider?.get("addressNames") as? JsonObject)?.entries?.associate { (address, name) ->
            Address.of(address) to name.jsonPrimitive.content
        } ?: emptyMap()
        val collections = (provider?.get("nftCollectionNames") as? JsonObject)?.entries?.associate { (address, name) ->
            Address.of(address) to name.jsonPrimitive.content
        } ?: emptyMap()
        return object : ClearSigningLookups {
            override fun token(network: Network, token: Address) = tokens[token]
            override fun addressName(network: Network, address: Address) = names[address]
            override suspend fun addressKind(network: Network, address: Address): String? = null

            // The registry's vectors take a collection's name from a trusted provider; standing in for the
            // built-in collections here.
            override fun builtInCollectionName(network: Network, collection: Address) = collections[collection]

            override suspend fun nested(
                network: Network,
                from: Address,
                callee: Address,
                value: UInt256?,
                data: ByteArray,
                depth: Int,
            ): ClearSignResult {
                val deployment = catalog.deployment(network, callee)
                    ?: return ClearSignResult.NotCovered(eth.serotine.domain.model.NotCoveredReason.NoDescriptor)
                val selector = "0x" + data.copyOf(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                val descriptor = deployment.descriptors.firstOrNull {
                    it.kind == ClearSigningDescriptor.Kind.CALLDATA && it.formatForSelector(selector) != null
                } ?: return ClearSignResult.NotCovered(eth.serotine.domain.model.NotCoveredReason.NoFormat(selector))
                return ClearSignFormatter.formatCall(
                    descriptor, network, from, callee, value, data, this, depth, deployment.delegatesToItself,
                )
            }
        }
    }

    private fun typedData(json: JsonObject): TypedData = TypedData(
        types = json.getValue("types").jsonObject.mapValues { (_, fields) ->
            fields.jsonArray.map { TypedField(it.jsonObject.getValue("name").jsonPrimitive.content, it.jsonObject.getValue("type").jsonPrimitive.content) }
        },
        primaryType = json.getValue("primaryType").jsonPrimitive.content,
        domain = (value(json.getValue("domain")) as TypedValue.Struct).fields,
        message = (value(json.getValue("message")) as TypedValue.Struct).fields,
    )

    private fun value(json: JsonElement): TypedValue = when (json) {
        is JsonObject -> TypedValue.Struct(json.mapValues { value(it.value) })
        is JsonArray -> TypedValue.Items(json.map(::value))
        is JsonPrimitive -> when {
            json.isString -> TypedValue.Text(json.content)
            json.content == "true" || json.content == "false" -> TypedValue.Bool(json.content == "true")
            else -> TypedValue.Number(json.content)
        }
    }

    /** What a raw transaction of the vectors sends: legacy (EIP-155), EIP-2930 or EIP-1559, its RLP read here. */
    private data class Transaction(val chainId: Long, val to: Address, val value: UInt256, val data: ByteArray) {
        companion object {
            fun decode(raw: ByteArray): Transaction? {
                val typed = raw.isNotEmpty() && raw[0].toInt() in 0..0x7f
                val items = Rlp(if (typed) raw.copyOfRange(1, raw.size) else raw).list() ?: return null
                fun bytes(i: Int) = items[i] as? ByteArray ?: ByteArray(0)
                fun long(i: Int) = bytes(i).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
                val (chainId, to, value, data) = when {
                    !typed -> listOf((long(6) - 35) / 2, bytes(3), bytes(4), bytes(5))
                    raw[0].toInt() == 1 -> listOf(long(0), bytes(4), bytes(5), bytes(6))
                    raw[0].toInt() == 2 -> listOf(long(0), bytes(5), bytes(6), bytes(7))
                    else -> return null
                }
                val address = Address.parse("0x" + (to as ByteArray).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
                    ?: return null
                return Transaction(chainId as Long, address, UInt256.fromBigEndian(value as ByteArray), data as ByteArray)
            }
        }
    }

    /** Just enough RLP to read a transaction: byte strings and lists. */
    private class Rlp(private val bytes: ByteArray) {
        private var at = 0

        fun list(): List<Any>? = (item() as? List<*>)?.filterNotNull()

        private fun item(): Any? {
            val prefix = bytes[at].toInt() and 0xff
            return when {
                prefix < 0x80 -> byteArrayOf(bytes[at++])
                prefix <= 0xb7 -> take(1, prefix - 0x80)
                prefix <= 0xbf -> take(1 + prefix - 0xb7, length(at + 1, prefix - 0xb7))
                prefix <= 0xf7 -> items(1, prefix - 0xc0)
                else -> items(1 + prefix - 0xf7, length(at + 1, prefix - 0xf7))
            }
        }

        private fun length(from: Int, size: Int): Int = (0 until size).fold(0) { acc, i -> (acc shl 8) or (bytes[from + i].toInt() and 0xff) }

        private fun take(header: Int, length: Int): ByteArray {
            val start = at + header
            at = start + length
            return bytes.copyOfRange(start, at)
        }

        private fun items(header: Int, length: Int): List<Any?> {
            at += header
            val end = at + length
            val out = mutableListOf<Any?>()
            while (at < end) out += item()
            return out
        }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /** Fewer vectors than this run means the catalog or the vectors went missing. */
        const val MIN_VECTORS = 30

        /** Vectors Punicus reads otherwise on purpose: the start of their name, and the rule that makes it so. */
        val DIFFERENCES: List<Pair<String, String>> = listOf(
            // Its call data is `deposit()` and one byte more (`d0e30db076`): Solidity ignores trailing bytes, the
            // strict decoding refuses them (docs/clear-signing.md, A3, *Strict decoding*).
            "calldata-weth.json: Wrap - chain 1" to "trailing bytes are refused",
        )
    }
}
