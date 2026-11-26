package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ERC-7730's paths as docs/clear-signing.md, A3, evaluates them. */
class ClearSigningPathTest {

    private val values = ClearSigningValues(
        root = AbiValue.TupleValue(
            listOf(
                "orders" to AbiValue.ArrayValue(
                    listOf(
                        AbiValue.TupleValue(listOf("offerer" to AbiValue.UintValue(UInt256.of(1), 8))),
                        AbiValue.TupleValue(listOf("offerer" to AbiValue.UintValue(UInt256.of(2), 8))),
                    ),
                ),
                "data" to AbiValue.BytesValue("a9059cbb0000", fixed = false),
                "empty" to AbiValue.ArrayValue(emptyList()),
            ),
        ),
        container = mapOf("value" to AbiValue.UintValue(UInt256.of(5), 256)),
    )

    private fun reached(path: String) = values.evaluate(ClearSigningPath.parse(path)!!)?.map { it.location to FORMAT(it.value) }

    @Test
    fun pathsReachMembersItemsSlicesAndTheContainer() {
        assertEquals(listOf("orders.[0].offerer" to "1", "orders.[1].offerer" to "2"), reached("orders.[].offerer"))
        assertEquals(listOf("orders.[1].offerer" to "2"), reached("orders.[-1].offerer"))
        assertEquals(listOf("data.[0:4]" to "0xa9059cbb"), reached("data.[0:4]"))
        assertEquals(listOf("orders.[0].offerer" to "1"), reached("#.orders.[0].offerer"))
        assertEquals(listOf("@.value" to "5"), reached("@.value"))
        assertEquals(emptyList(), reached("empty.[]"))
    }

    @Test
    fun aPathThatReachesNothingSaysSo() {
        assertNull(reached("orders.[2].offerer"))
        assertNull(reached("orders.[].bidder"))
        assertNull(reached("data.[0:40]"))
        assertNull(reached("@.chainId"))
    }

    @Test
    fun onlyWhatTheRuntimeEvaluatesParses() {
        assertNull(ClearSigningPath.parse("$.metadata.constants.max"))
        assertNull(ClearSigningPath.parse("@.gasPrice"))
        assertNull(ClearSigningPath.parse("orders..x"))
        assertEquals("orders.[].offerer", ClearSigningPath.parse("#.orders.[].offerer").toString())
    }

    @Test
    fun theLeavesAreEveryValueWithItsLocation() {
        assertEquals(
            listOf("orders.[0].offerer", "orders.[1].offerer", "data", "empty"),
            values.leaves().map { it.location },
        )
    }

    private companion object {
        val FORMAT: (AbiValue) -> String = ClearSignFormatter::raw
    }
}
