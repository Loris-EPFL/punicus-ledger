package eth.serotine.data.clearsigning

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.FieldFormat
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The generated catalog read into the domain's model (docs/clear-signing.md, A1, *What part 2 ships*). */
class BuiltInClearSigningCatalogTest {

    private val catalog = BuiltInClearSigningCatalog()

    @Test
    fun eachShippedDeploymentIsThereWithItsPins() {
        assertEquals(11, catalog.deployments.size)
        val pool = assertNotNull(catalog.deployment(Network.ETHEREUM, Address.of("0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2")))
        val pin = assertIs<CodePin.Slot>(pool.codePin)
        assertEquals("0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc", pin.slot)
        assertTrue(pool.delegatesToItself, "its multicall, declared by hand")
        val router = assertNotNull(catalog.deployment(Network.ETHEREUM, Address.of("0x68b3465833fb72A70ecDF485E0e4C7bD8665Fc45")))
        assertEquals(CodePin.Fixed, router.codePin)
        assertTrue(router.delegatesToItself)
        val weth = assertNotNull(catalog.deployment(Network.ETHEREUM, Address.of("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")))
        assertEquals(false, weth.delegatesToItself)
        assertNull(catalog.deployment(Network.GNOSIS, weth.address), "one network's deployment is not another's")
    }

    @Test
    fun aDescriptorsReferencesAreResolved() {
        val wsteth = assertNotNull(catalog.deployment(Network.ETHEREUM, Address.of("0x7f39C581F595B53c5cb19bD0b3f8dA6c935E2Ca0")))
        val formats = wsteth.descriptors.single().formats
        val amounts = formats.flatMap { it.fields }.mapNotNull { it.format as? FieldFormat.TokenAmount }
        assertTrue(amounts.any { it.token != null }, "a constant token")
        assertTrue(amounts.any { it.threshold != null }, "a constant threshold")
        val permit2 = assertNotNull(catalog.deployment(Network.SEPOLIA, Address.of("0x000000000022D473030F116dDEE9F6B43aC78BA3")))
        val typed = permit2.descriptors.single { it.kind == ClearSigningDescriptor.Kind.EIP712 }
        assertEquals("Permit2", typed.domainName)
        assertEquals(3, typed.formats.size)
        val router = assertNotNull(catalog.deployment(Network.ETHEREUM, Address.of("0x68b3465833fb72A70ecDF485E0e4C7bD8665Fc45")))
        assertTrue(router.descriptors.single().formats.flatMap { it.fields }.any { it.visibility is Visibility.MustMatch })
    }

    @Test
    fun textThatIsNotTheGeneratedCatalogGivesNone() {
        assertEquals(emptyMap(), BuiltInClearSigningCatalog(listOf("{}"), "00".repeat(32)).deployments)
        assertEquals(emptyMap(), BuiltInClearSigningCatalog(CLEAR_SIGNING_CATALOG_JSON.map { it.replace("Uniswap", "Uniswaq") }, CLEAR_SIGNING_CATALOG_SHA256).deployments)
    }
}
