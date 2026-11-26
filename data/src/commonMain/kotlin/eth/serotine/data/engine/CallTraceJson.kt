package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TraceFrame
import eth.serotine.domain.model.TraceLog
import eth.serotine.domain.model.UInt256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the engine's `callTracer` answer, with its logs (geth's shape: `type`, `from`, `to`, `value`, `error`,
 * `revertReason`, `logs`, `calls`), into the domain's [TraceFrame]. Fails closed: a frame of no known type, an
 * address or a number that is not one, or a log that is not a log makes the whole trace no answer (null),
 * never a trace with a frame left out. A frame's `to` may be absent only where a creation failed.
 */
internal object CallTraceJson {

    /** The trace [json] spells, or null when it is not a `callTracer` answer this reads exactly. */
    fun parse(json: String): TraceFrame? {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return frame(root, depth = 0)
    }

    private fun frame(element: JsonElement, depth: Int): TraceFrame? {
        if (depth > MAX_DEPTH) return null
        val obj = element as? JsonObject ?: return null
        val type = TraceFrame.Type.parse(obj.text("type") ?: return null) ?: return null
        val from = Address.parse(obj.text("from") ?: return null) ?: return null
        val to = when (val raw = obj["to"]) {
            null, JsonNull -> if (type == TraceFrame.Type.CREATE || type == TraceFrame.Type.CREATE2) null else return null
            else -> Address.parse((raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null) ?: return null
        }
        val value = when (val raw = obj["value"]) {
            null, JsonNull -> null
            else -> quantity((raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null) ?: return null
        }
        val logs = (obj.array("logs") ?: return null).map { log(it) ?: return null }
        val calls = (obj.array("calls") ?: return null).map { frame(it, depth + 1) ?: return null }
        return TraceFrame(
            type = type,
            from = from,
            to = to,
            value = value,
            error = obj.text("error"),
            revertReason = obj.text("revertReason"),
            logs = logs,
            calls = calls,
        )
    }

    private fun log(element: JsonElement): TraceLog? {
        val obj = element as? JsonObject ?: return null
        val address = Address.parse(obj.text("address") ?: return null) ?: return null
        val topics = (obj.array("topics") ?: return null).map { topic ->
            val text = (topic as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            hex(text)?.takeIf { it.length == WORD_HEX } ?: return null
        }
        val data = hex(obj.text("data") ?: "0x") ?: return null
        return TraceLog(address, topics, data)
    }

    /** A JSON-RPC quantity (`0x` and hex digits, no more than 256 bits), or null. */
    private fun quantity(text: String): UInt256? {
        val digits = text.removePrefix("0x").takeIf { text.startsWith("0x") && it.isNotEmpty() } ?: return null
        if (digits.length > WORD_HEX || !digits.all(::isHexDigit)) return null
        return UInt256.fromBigEndian(digits.padStart(WORD_HEX, '0').fromHex())
    }

    /** `0x` and an even number of hex digits, lower-cased, without the `0x`; or null. */
    private fun hex(text: String): String? {
        val digits = text.removePrefix("0x").takeIf { text.startsWith("0x") } ?: return null
        return digits.lowercase().takeIf { it.length % 2 == 0 && it.all(::isHexDigit) }
    }

    private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** The string member [key], or null when it is absent, null or no string. */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The array member [key]: empty when absent or null, null when it is something else. */
    private fun JsonObject.array(key: String): List<JsonElement>? = when (val raw = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> raw
        else -> null
    }

    private const val WORD_HEX = 64

    /** The EVM's call depth limit, and a margin: no real trace is deeper. */
    private const val MAX_DEPTH = 1_100
}
