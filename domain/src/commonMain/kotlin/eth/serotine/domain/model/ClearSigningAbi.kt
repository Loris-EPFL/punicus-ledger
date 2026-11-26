package eth.serotine.domain.model

/**
 * The ABI types a clear-signing descriptor reads a call as (docs/clear-signing.md, A3), parsed from the
 * human-readable fragment its format is keyed by: `transfer(address to,uint256 value)`.
 */
sealed interface AbiType {
    /** Whether its encoding sits in the tail, reached through an offset. */
    val isDynamic: Boolean

    /** `address`. */
    data object AddressType : AbiType {
        override val isDynamic = false
    }

    /** `bool`. */
    data object BoolType : AbiType {
        override val isDynamic = false
    }

    /** `uint<bits>`. */
    data class UintType(val bits: Int) : AbiType {
        override val isDynamic = false
    }

    /** `int<bits>`. */
    data class IntType(val bits: Int) : AbiType {
        override val isDynamic = false
    }

    /** `bytes<size>`, 1 to 32. */
    data class FixedBytesType(val size: Int) : AbiType {
        override val isDynamic = false
    }

    /** `bytes`. */
    data object BytesType : AbiType {
        override val isDynamic = true
    }

    /** `string`. */
    data object StringType : AbiType {
        override val isDynamic = true
    }

    /** A tuple of [members], each named or not. */
    data class TupleType(val members: List<AbiParam>) : AbiType {
        override val isDynamic: Boolean get() = members.any { it.type.isDynamic }
    }

    /** `T[]` ([length] null) or `T[n]`. */
    data class ArrayType(val element: AbiType, val length: Int?) : AbiType {
        override val isDynamic: Boolean get() = length == null || element.isDynamic
    }
}

/** One parameter of a function or member of a tuple: its [name], when the fragment gives one, and [type]. */
data class AbiParam(val name: String?, val type: AbiType)

/** A function as a descriptor's format key names it: its [name] and its [params], names included. */
data class AbiFunction(val name: String, val params: List<AbiParam>) {
    /** The canonical signature the selector hashes: `transfer(address,uint256)`. */
    val signature: String get() = "$name(${params.joinToString(",") { canonical(it.type) }})"

    /** The 4-byte selector, as lower-case hex with `0x`. */
    val selector: String get() = "0x" + AbiWords.selector(signature).toHex()

    companion object {
        /** [fragment] (`name(type name, …)`, tuples in parentheses) parsed, or null when it is not one. */
        fun parse(fragment: String): AbiFunction? = runCatching {
            val text = fragment.trim()
            val open = text.indexOf('(')
            require(open > 0 && text.endsWith(")"))
            val name = text.substring(0, open)
            require(IDENTIFIER.matches(name))
            AbiFunction(name, params(text.substring(open + 1, text.length - 1)))
        }.getOrNull()

        /** The canonical form of [type]: names dropped, tuples in parentheses. */
        fun canonical(type: AbiType): String = when (type) {
            AbiType.AddressType -> "address"
            AbiType.BoolType -> "bool"
            is AbiType.UintType -> "uint${type.bits}"
            is AbiType.IntType -> "int${type.bits}"
            is AbiType.FixedBytesType -> "bytes${type.size}"
            AbiType.BytesType -> "bytes"
            AbiType.StringType -> "string"
            is AbiType.TupleType -> type.members.joinToString(",", "(", ")") { canonical(it.type) }
            is AbiType.ArrayType -> canonical(type.element) + "[${type.length ?: ""}]"
        }

        /** The parameters of [text]; two of one name are refused, since a path could reach only the first. */
        private fun params(text: String): List<AbiParam> =
            (if (text.isBlank()) emptyList() else splitTop(text).map(::param)).also { params ->
                val names = params.mapNotNull { it.name }
                require(names.size == names.toSet().size) { "a name repeats" }
            }

        private fun param(text: String): AbiParam {
            var rest = text.trim().removePrefix("tuple")
            var type: AbiType
            if (rest.startsWith("(")) {
                val close = closing(rest)
                type = AbiType.TupleType(params(rest.substring(1, close)))
                rest = rest.substring(close + 1)
            } else {
                val base = BASE.find(rest)?.value ?: throw IllegalArgumentException("no type in $text")
                type = elementary(base)
                rest = rest.substring(base.length)
            }
            while (true) {
                val array = ARRAY_SUFFIX.find(rest) ?: break
                val length = array.groupValues[1]
                type = AbiType.ArrayType(type, length.takeIf { it.isNotEmpty() }?.toInt()?.also { require(it in 1..MAX_FIXED) })
                rest = rest.substring(array.value.length)
            }
            val name = rest.trim().removePrefix("indexed ").trim()
            require(name.isEmpty() || IDENTIFIER.matches(name)) { "not a name: $name" }
            return AbiParam(name.ifEmpty { null }, type)
        }

        private fun elementary(base: String): AbiType = when {
            base == "address" -> AbiType.AddressType
            base == "bool" -> AbiType.BoolType
            base == "string" -> AbiType.StringType
            base == "bytes" -> AbiType.BytesType
            base.startsWith("bytes") -> AbiType.FixedBytesType(base.removePrefix("bytes").toInt().also { require(it in 1..32) })
            base.startsWith("uint") -> AbiType.UintType(bits(base.removePrefix("uint")))
            base.startsWith("int") -> AbiType.IntType(bits(base.removePrefix("int")))
            else -> throw IllegalArgumentException("not an ABI type: $base")
        }

        private fun bits(text: String): Int =
            if (text.isEmpty()) 256 else text.toInt().also { require(it in 8..256 && it % 8 == 0) }

        private fun closing(text: String): Int {
            var depth = 0
            text.forEachIndexed { i, ch ->
                if (ch == '(') depth++
                if (ch == ')' && --depth == 0) return i
            }
            throw IllegalArgumentException("unbalanced parentheses")
        }

        private fun splitTop(text: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { i, ch ->
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
                require(depth >= 0)
            }
            require(depth == 0)
            return parts + text.substring(start)
        }

        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val BASE = Regex("^[a-z]+[0-9]*")
        private val ARRAY_SUFFIX = Regex("^\\[(\\d*)]")
        private const val MAX_FIXED = 1024
    }
}

/** A value decoded from a call's ABI encoding, or read from an EIP-712 message, under its [AbiType]. */
sealed interface AbiValue {
    /** An address. */
    data class AddressValue(val address: Address) : AbiValue

    /** A boolean. */
    data class BoolValue(val value: Boolean) : AbiValue

    /** An unsigned integer of [bits] bits. */
    data class UintValue(val value: UInt256, val bits: Int) : AbiValue

    /** A signed integer of [bits] bits: [negative], and its [magnitude]. */
    data class IntValue(val negative: Boolean, val magnitude: UInt256, val bits: Int) : AbiValue

    /** Bytes, `bytes<n>` ([fixed]) or `bytes`, as lower-case [hex] without `0x`. */
    data class BytesValue(val hex: String, val fixed: Boolean) : AbiValue {
        /** The bytes: a copy. */
        val bytes: ByteArray get() = hex.hexToBytes()
    }

    /** A string, exactly as decoded. */
    data class StringValue(val text: String) : AbiValue

    /** A tuple (or struct): its [members] in order, each with its name. */
    data class TupleValue(val members: List<Pair<String?, AbiValue>>) : AbiValue

    /** An array of [items]. */
    data class ArrayValue(val items: List<AbiValue>) : AbiValue
}

/**
 * The ABI decoding of a call's arguments, strict (docs/clear-signing.md, A3, *Strict decoding*): every
 * offset and length inside the data, every word in its type's range, and the data exactly the canonical
 * encoding of what was decoded, so trailing bytes, dirty padding and offsets that overlap or skip are all
 * refused. Nothing is read beyond [MAX_ITEMS] array items or [MAX_DEPTH] levels.
 */
object AbiDecoder {
    /** The most items an array may hold. */
    const val MAX_ITEMS = 1024

    /** The most levels of tuples and arrays. */
    const val MAX_DEPTH = 16

    /**
     * [arguments] (the call data after its selector) decoded as [params], or null when it is not exactly that.
     * Each dynamic value must sit at the canonical next position of its sequence's tail, so no two offsets
     * share bytes and what is decoded is never larger than [arguments].
     */
    fun decode(params: List<AbiParam>, arguments: ByteArray): AbiValue.TupleValue? = try {
        val type = AbiType.TupleType(params)
        val (value, end) = Reader(arguments).value(type, 0, 0)
        (value as AbiValue.TupleValue).takeIf { end == arguments.size && encode(type, it).contentEquals(arguments) }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: CharacterCodingException) {
        null
    } catch (_: ClassCastException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /** The canonical ABI encoding of [value] as [type]. */
    fun encode(type: AbiType, value: AbiValue): ByteArray = when (type) {
        is AbiType.TupleType -> {
            val members = (value as AbiValue.TupleValue).members.map { it.second }
            sequence(type.members.map { it.type }, members)
        }
        is AbiType.ArrayType -> {
            val items = (value as AbiValue.ArrayValue).items
            val body = sequence(List(items.size) { type.element }, items)
            if (type.length == null) AbiWords.word(items.size.toLong()) + body else body
        }
        AbiType.AddressType -> AbiWords.word((value as AbiValue.AddressValue).address)
        AbiType.BoolType -> AbiWords.word(if ((value as AbiValue.BoolValue).value) 1L else 0L)
        is AbiType.UintType -> (value as AbiValue.UintValue).value.toBytes32()
        is AbiType.IntType -> twosComplement(value as AbiValue.IntValue).toBytes32()
        is AbiType.FixedBytesType -> (value as AbiValue.BytesValue).bytes.let { it + ByteArray(32 - it.size) }
        AbiType.BytesType -> AbiWords.dynamic((value as AbiValue.BytesValue).bytes)
        AbiType.StringType -> AbiWords.dynamic((value as AbiValue.StringValue).text.encodeToByteArray())
    }

    private fun sequence(types: List<AbiType>, values: List<AbiValue>): ByteArray {
        val heads = mutableListOf<ByteArray>()
        val tails = mutableListOf<ByteArray>()
        var tailOffset = types.sumOf { headSize(it) }
        types.zip(values).forEach { (type, value) ->
            val encoded = encode(type, value)
            if (type.isDynamic) {
                heads += AbiWords.word(tailOffset)
                tails += encoded
                tailOffset += encoded.size
            } else {
                heads += encoded
            }
        }
        return concat(heads + tails)
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** The bytes [type] takes in its sequence's head; refused past [Int.MAX_VALUE] (a deep fixed array). */
    private fun headSize(type: AbiType): Long = when {
        type.isDynamic -> WORD.toLong()
        type is AbiType.TupleType -> type.members.sumOf { headSize(it.type) }
        type is AbiType.ArrayType -> type.length!! * headSize(type.element)
        else -> WORD.toLong()
    }.also { require(it <= Int.MAX_VALUE) }

    private fun twosComplement(value: AbiValue.IntValue): UInt256 =
        if (!value.negative || value.magnitude.isZero) value.magnitude else UInt256.MAX - value.magnitude + UInt256.ONE

    /** Reads values, each with the position just past it. */
    private class Reader(private val data: ByteArray) {
        fun word(at: Int): ByteArray {
            require(at >= 0 && at + WORD <= data.size)
            return data.copyOfRange(at, at + WORD)
        }

        fun sequence(types: List<AbiType>, base: Int, depth: Int): Pair<List<AbiValue>, Int> {
            require(depth <= MAX_DEPTH)
            var head = base.toLong()
            var tail = base + types.sumOf { headSize(it) }
            require(tail <= data.size)
            val values = types.map { type ->
                val value = if (type.isDynamic) {
                    val offset = AbiWords.long(word(head.toInt())) ?: throw IllegalArgumentException("offset")
                    // The canonical tail: each dynamic value right after the one before it, none shared.
                    require(base + offset == tail)
                    val (value, end) = value(type, tail.toInt(), depth + 1)
                    tail = end.toLong()
                    value
                } else {
                    value(type, head.toInt(), depth + 1).first
                }
                head += headSize(type)
                value
            }
            return values to tail.toInt()
        }

        fun value(type: AbiType, at: Int, depth: Int): Pair<AbiValue, Int> = when (type) {
            is AbiType.TupleType -> {
                val (values, end) = sequence(type.members.map { it.type }, at, depth)
                AbiValue.TupleValue(type.members.map { it.name }.zip(values)) to end
            }
            is AbiType.ArrayType -> {
                val length = type.length ?: (AbiWords.long(word(at))?.takeIf { it <= MAX_ITEMS }?.toInt()
                    ?: throw IllegalArgumentException("length"))
                require(length in 0..MAX_ITEMS)
                val start = if (type.length == null) at + WORD else at
                val (items, end) = sequence(List(length) { type.element }, start, depth)
                AbiValue.ArrayValue(items) to end
            }
            AbiType.AddressType -> AbiValue.AddressValue(AbiWords.address(word(at)) ?: throw IllegalArgumentException("address")) to at + WORD
            AbiType.BoolType -> AbiValue.BoolValue(
                when (AbiWords.uint(word(at))) {
                    UInt256.ZERO -> false
                    UInt256.ONE -> true
                    else -> throw IllegalArgumentException("bool")
                },
            ) to at + WORD
            is AbiType.UintType ->
                AbiValue.UintValue(AbiWords.uint(word(at), type.bits) ?: throw IllegalArgumentException("uint"), type.bits) to at + WORD
            is AbiType.IntType -> signed(word(at), type.bits) to at + WORD
            is AbiType.FixedBytesType -> AbiValue.BytesValue(word(at).copyOf(type.size).toHex(), fixed = true) to at + WORD
            AbiType.BytesType -> dynamicBytes(at).let { (bytes, end) -> AbiValue.BytesValue(bytes.toHex(), fixed = false) to end }
            AbiType.StringType ->
                dynamicBytes(at).let { (bytes, end) -> AbiValue.StringValue(bytes.decodeToString(throwOnInvalidSequence = true)) to end }
        }

        fun dynamicBytes(at: Int): Pair<ByteArray, Int> {
            val length = AbiWords.long(word(at)) ?: throw IllegalArgumentException("length")
            val end = at + WORD + length + AbiWords.padding((length % WORD).toInt())
            require(end <= data.size)
            return data.copyOfRange(at + WORD, at + WORD + length.toInt()) to end.toInt()
        }

        fun signed(word: ByteArray, bits: Int): AbiValue.IntValue {
            val raw = AbiWords.uint(word)
            val negative = word[0] < 0
            val magnitude = if (negative) UInt256.MAX - raw + UInt256.ONE else raw
            val value = AbiValue.IntValue(negative, magnitude, bits)
            // In range: its two's complement over `bits` sign-extends to this word (checked by re-encoding).
            require(fitsSigned(value))
            return value
        }

        private fun fitsSigned(value: AbiValue.IntValue): Boolean {
            val limit = powerOfTwo(value.bits - 1)
            return if (value.negative) value.magnitude <= limit else value.magnitude < limit
        }
    }

    /** 2^[exponent], for an exponent below 256. */
    internal fun powerOfTwo(exponent: Int): UInt256 =
        UInt256.fromBigEndian(ByteArray(WORD).also { it[WORD - 1 - exponent / 8] = (1 shl (exponent % 8)).toByte() })

    private const val WORD = 32
}
