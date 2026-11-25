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

        private fun params(text: String): List<AbiParam> =
            if (text.isBlank()) emptyList() else splitTop(text).map(::param)

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

    /** [arguments] (the call data after its selector) decoded as [params], or null when it is not exactly that. */
    fun decode(params: List<AbiParam>, arguments: ByteArray): AbiValue.TupleValue? = runCatching {
        val type = AbiType.TupleType(params)
        val value = Reader(arguments).tuple(type, 0, 0)
        value.takeIf { encode(type, it).contentEquals(arguments) }
    }.getOrNull()

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
        var tailOffset = types.sumOf { headSize(it) }.toLong()
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
        return (heads + tails).fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    }

    private fun headSize(type: AbiType): Int = when {
        type.isDynamic -> WORD
        type is AbiType.TupleType -> type.members.sumOf { headSize(it.type) }
        type is AbiType.ArrayType -> type.length!! * headSize(type.element)
        else -> WORD
    }

    private fun twosComplement(value: AbiValue.IntValue): UInt256 =
        if (!value.negative || value.magnitude.isZero) value.magnitude else UInt256.MAX - value.magnitude + UInt256.ONE

    private class Reader(private val data: ByteArray) {
        fun word(at: Int): ByteArray {
            require(at >= 0 && at + WORD <= data.size)
            return data.copyOfRange(at, at + WORD)
        }

        fun offset(at: Int, base: Int): Int {
            val offset = AbiWords.long(word(at)) ?: throw IllegalArgumentException("offset too large")
            require(offset <= data.size)
            return (base + offset).toInt().also { require(it <= data.size) }
        }

        fun tuple(type: AbiType.TupleType, at: Int, depth: Int): AbiValue.TupleValue {
            val values = sequence(type.members.map { it.type }, at, depth)
            return AbiValue.TupleValue(type.members.map { it.name }.zip(values))
        }

        fun sequence(types: List<AbiType>, base: Int, depth: Int): List<AbiValue> {
            require(depth <= MAX_DEPTH)
            var head = base
            return types.map { type ->
                val value = if (type.isDynamic) value(type, offset(head, base), depth + 1) else value(type, head, depth + 1)
                head += headSize(type)
                value
            }
        }

        fun value(type: AbiType, at: Int, depth: Int): AbiValue = when (type) {
            is AbiType.TupleType -> tuple(type, at, depth)
            is AbiType.ArrayType -> {
                val length = type.length ?: (AbiWords.long(word(at))?.toInt() ?: throw IllegalArgumentException("length"))
                require(length in 0..MAX_ITEMS)
                val start = if (type.length == null) at + WORD else at
                require(start + length.toLong() * minOf(headSize(type.element), WORD) <= data.size)
                AbiValue.ArrayValue(sequence(List(length) { type.element }, start, depth))
            }
            AbiType.AddressType -> AbiValue.AddressValue(AbiWords.address(word(at)) ?: throw IllegalArgumentException("address"))
            AbiType.BoolType -> AbiValue.BoolValue(
                when (AbiWords.uint(word(at))) {
                    UInt256.ZERO -> false
                    UInt256.ONE -> true
                    else -> throw IllegalArgumentException("bool")
                },
            )
            is AbiType.UintType -> AbiValue.UintValue(AbiWords.uint(word(at), type.bits) ?: throw IllegalArgumentException("uint"), type.bits)
            is AbiType.IntType -> signed(word(at), type.bits)
            is AbiType.FixedBytesType -> AbiValue.BytesValue(word(at).copyOf(type.size).toHex(), fixed = true)
            AbiType.BytesType -> AbiValue.BytesValue(dynamicBytes(at).toHex(), fixed = false)
            AbiType.StringType -> AbiValue.StringValue(dynamicBytes(at).decodeToString(throwOnInvalidSequence = true))
        }

        fun dynamicBytes(at: Int): ByteArray {
            val length = AbiWords.long(word(at)) ?: throw IllegalArgumentException("length")
            require(at + WORD + length <= data.size)
            return data.copyOfRange(at + WORD, at + WORD + length.toInt())
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
