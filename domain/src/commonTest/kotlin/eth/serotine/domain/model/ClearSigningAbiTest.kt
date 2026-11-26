package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The strict ABI decoding of docs/clear-signing.md, A3: exactly the canonical encoding, or nothing. */
class ClearSigningAbiTest {

    @Test
    fun aFragmentIsParsedWithItsNamesAndHashesToItsSelector() {
        val function = AbiFunction.parse(
            "exactInputSingle((address tokenIn, address tokenOut, uint24 fee, address recipient, uint256 amountIn, " +
                "uint256 amountOutMinimum, uint160 sqrtPriceLimitX96) params)",
        )!!
        assertEquals("exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))", function.signature)
        assertEquals("0x04e45aaf", function.selector)
        val params = function.params.single()
        assertEquals("params", params.name)
        assertEquals("tokenIn", (params.type as AbiType.TupleType).members.first().name)
        assertEquals("0xa9059cbb", AbiFunction.parse("transfer(address to,uint256 value)")!!.selector)
        assertEquals("f(uint256[],(bytes32,string)[2])", AbiFunction.parse("f(uint[] a,(bytes32 b,string c)[2] d)")!!.signature)
        assertNull(AbiFunction.parse("f(uint7 x)"))
        assertNull(AbiFunction.parse("f(address to"))
    }

    private val mixed = AbiFunction.parse("f(address a,bytes b,string s,(uint8 x,bool y)[] t,int16 n)")!!.params

    private val mixedValue = AbiValue.TupleValue(
        listOf(
            "a" to AbiValue.AddressValue(Address.of("0x" + "12".repeat(20))),
            "b" to AbiValue.BytesValue("deadbeef", fixed = false),
            "s" to AbiValue.StringValue("héllo"),
            "t" to AbiValue.ArrayValue(
                listOf(
                    AbiValue.TupleValue(listOf("x" to AbiValue.UintValue(UInt256.of(7), 8), "y" to AbiValue.BoolValue(true))),
                    AbiValue.TupleValue(listOf("x" to AbiValue.UintValue(UInt256.of(255), 8), "y" to AbiValue.BoolValue(false))),
                ),
            ),
            "n" to AbiValue.IntValue(negative = true, magnitude = UInt256.of(2), bits = 16),
        ),
    )

    @Test
    fun dynamicTypesRoundTrip() {
        val encoded = AbiDecoder.encode(AbiType.TupleType(mixed), mixedValue)
        assertEquals(mixedValue, AbiDecoder.decode(mixed, encoded))
        // int16 -2 is sign-extended over the whole word.
        assertEquals("ff".repeat(31) + "fe", encoded.copyOfRange(4 * 32, 5 * 32).toHex())
    }

    @Test
    fun anythingButTheCanonicalEncodingIsRefused() {
        val encoded = AbiDecoder.encode(AbiType.TupleType(mixed), mixedValue)
        assertNull(AbiDecoder.decode(mixed, encoded + byteArrayOf(0)), "a trailing byte")
        assertNull(AbiDecoder.decode(mixed, encoded + ByteArray(32)), "a trailing word")
        assertNull(AbiDecoder.decode(mixed, encoded.copyOf(encoded.size - 1)), "cut short")
        assertNull(AbiDecoder.decode(mixed, encoded.copyOf().also { it[0] = 1 }), "an address with dirty upper bytes")
        // An offset that points past the data.
        assertNull(AbiDecoder.decode(mixed, encoded.copyOf().also { it[63] = 0x7f }))
    }

    @Test
    fun eachWordMustBeInItsTypesRange() {
        val bool = AbiFunction.parse("f(bool b)")!!.params
        assertNotNull(AbiDecoder.decode(bool, UInt256.ONE.toBytes32()))
        assertNull(AbiDecoder.decode(bool, UInt256.of(2).toBytes32()))
        val uint8 = AbiFunction.parse("f(uint8 x)")!!.params
        assertNull(AbiDecoder.decode(uint8, UInt256.of(256).toBytes32()))
        val int8 = AbiFunction.parse("f(int8 x)")!!.params
        assertNotNull(AbiDecoder.decode(int8, ByteArray(32) { 0xff.toByte() }).also {
            assertEquals(AbiValue.IntValue(true, UInt256.ONE, 8), it!!.members.single().second)
        })
        // 128 in int8's word is out of range (its sign is not extended).
        assertNull(AbiDecoder.decode(int8, UInt256.of(128).toBytes32()))
        val fixed = AbiFunction.parse("f(bytes2 x)")!!.params
        assertNull(AbiDecoder.decode(fixed, ByteArray(32).also { it[0] = 1; it[5] = 1 }), "bytes2 with dirty padding")
    }

    @Test
    fun offsetsThatShareBytesAreRefusedWithoutReadingThemAgain() {
        // multicall(bytes[]) with every element's offset at one 64 KiB blob: refused, and fast.
        val params = AbiFunction.parse("multicall(bytes[] data)")!!.params
        val items = 1024
        val blob = UInt256.of(65_536).toBytes32() + ByteArray(65_536)
        val head = UInt256.of(32).toBytes32() + UInt256.of(items.toLong()).toBytes32() +
            (0 until items).fold(ByteArray(0)) { acc, _ -> acc + UInt256.of(items * 32L).toBytes32() }
        assertNull(AbiDecoder.decode(params, head + blob))
    }

    @Test
    fun aNameThatRepeatsIsRefused() {
        assertNull(AbiFunction.parse("f(uint256 amount,uint256 amount)"))
        assertNull(AbiFunction.parse("f((uint256 a,bool a) t)"))
    }

    @Test
    fun anArrayLongerThanTheDataIsRefusedBeforeItIsRead() {
        val array = AbiFunction.parse("f(uint256[] x)")!!.params
        val data = UInt256.of(32).toBytes32() + UInt256.of(1_000_000).toBytes32()
        assertNull(AbiDecoder.decode(array, data))
    }
}
