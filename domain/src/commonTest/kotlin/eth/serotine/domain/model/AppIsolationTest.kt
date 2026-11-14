package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AppIsolationTest {

    private fun host(text: String): AppHost = (AppHost.parse(text) as AppHostResult.Host).host

    private fun ofUrl(url: String): String? = (AppHost.ofUrl(url) as? AppHostResult.Host)?.host?.name

    @Test
    fun theHostOfAStatedUrlIsLowerCasedWithoutPortUserPathOrTrailingDot() {
        assertEquals("app.uniswap.org", ofUrl("https://app.uniswap.org"))
        assertEquals("app.uniswap.org", ofUrl("HTTPS://App.Uniswap.ORG/#/swap?x=1"))
        assertEquals("app.uniswap.org", ofUrl("https://app.uniswap.org:8443/path"))
        assertEquals("app.uniswap.org", ofUrl("https://app.uniswap.org./"))
        assertEquals("localhost", ofUrl("http://localhost:3000"))
        // The user part is dropped: the host is what comes after the last '@', the site actually reached.
        assertEquals("evil.test", ofUrl("https://app.uniswap.org@evil.test/"))
        // The ASCII form of an internationalised name is a name like any other.
        assertEquals("xn--mnchen-3ya.de", ofUrl("https://xn--mnchen-3ya.de"))
    }

    @Test
    fun aUrlWithoutAWebSchemeOrAPlainNameHasNoHost() {
        assertEquals(AppHostResult.NotAWebAddress, AppHost.ofUrl("app.uniswap.org"))
        assertEquals(AppHostResult.NotAWebAddress, AppHost.ofUrl("ipfs://bafy"))
        assertEquals(AppHostResult.NotAWebAddress, AppHost.ofUrl(""))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://[::1]:8080"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example.com:"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example.com:80x"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://exa_mple.com"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://-example.com"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example..com"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://exa%6dple.com"))
        // Whitespace and backslashes in the authority are refused, not repaired: a browser reads `\` as `/`,
        // so this URL reaches evil.test, and dropping the part before the '@' would make it app.uniswap.org.
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://evil.test\\@app.uniswap.org/"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https:// example.com /x"))
        assertEquals(AppHostResult.NotAName, AppHost.ofUrl("https://example.com\t/x"))
        assertEquals(AppHostResult.NotAscii, AppHost.ofUrl("https://münchen.de"))
        // A Cyrillic 'а' that looks like a Latin one is refused, not mapped.
        assertEquals(AppHostResult.NotAscii, AppHost.ofUrl("https://аpp.uniswap.org"))
    }

    @Test
    fun aTypedHostIsReadTheSameWay() {
        assertEquals(host("app.safe.global"), host(" App.Safe.Global. "))
        assertEquals(AppHostResult.NotAName, AppHost.parse("app.safe.global:443"))
        assertEquals(AppHostResult.NotAName, AppHost.parse("a".repeat(64) + ".com"))
        // The longest label and the longest name are accepted.
        assertEquals("a".repeat(63) + ".com", host("a".repeat(63) + ".com").name)
        val longest = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61) // 3 × 64 + 61 = 253
        assertEquals(253, host(longest).name.length)
        assertEquals(AppHostResult.NotAName, AppHost.parse(longest + "a"))
        assertEquals(AppHostResult.NotAName, AppHost.parse(("a".repeat(63) + ".").repeat(4) + "com"))
    }

    // The fixed vectors of docs/keys-and-signing.md, *Per-app addresses*: host → the first twelve bytes of
    // keccak256("serotine/app-isolation/v1" ‖ host) → a, b, c. Computed independently of this code.
    @Test
    fun thePathIsTheFirst92BitsOfTheTaggedHash() {
        val vectors = mapOf(
            "example.com" to PerAppPath(1_172_550_437, 1_970_329_353, 15_099_968), // 178ecc97ab86b8480e668405
            "app.uniswap.org" to PerAppPath(2_025_759_771, 1_047_566_855, 367_116_372), // e2fa906df384d0395e1c054b
            // 48342a871fd98a461175fb2f
            "walletbeat.eth.limo" to PerAppPath(1_376_586_401, 1_677_406_536, 1_628_921_778),
            "app.safe.global" to PerAppPath(1_389_081_365, 600_412_453, 798_229_191), // 4b2ecc551e4c892af9402c72
            "localhost" to PerAppPath(1_327_406_733, 1_934_306_193, 1_863_016_393), // 3c7a7a379a595c8ef0b5fc99
        )
        vectors.forEach { (name, path) -> assertEquals(path, AppIsolation.pathOf(host(name)), name) }
        assertEquals(
            "m/44'/60'/1172550437'/1970329353'/15099968'",
            AppIsolation.pathOf(host("example.com")).toString(),
        )
    }

    // Hosts searched for to hit a 31-bit account (review of PR #297) no longer land anywhere chosen.
    @Test
    fun aHostSearchedForA31BitAccountNoLongerReachesIt() {
        // 48342a87…, walletbeat.eth.limo's first four bytes: the same a, another b and c.
        val searched = AppIsolation.pathOf(host("h324768444.test"))
        assertEquals(PerAppPath(1_376_586_401, 2_063_453_745, 766_961_056), searched)
        assertNotEquals(AppIsolation.pathOf(host("walletbeat.eth.limo")), searched)
        // 00000000…: a is the floor, 2^30, never a low account.
        assertEquals(PerAppPath(1 shl 30, 482_569_315, 1_982_029_818), AppIsolation.pathOf(host("h995859629.test")))
    }

    @Test
    fun aPathOutsideItsRangeCannotBeMade() {
        assertFailsWith<IllegalArgumentException> { PerAppPath((1 shl 30) - 1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { PerAppPath(1 shl 30, -1, 0) }
        assertFailsWith<IllegalArgumentException> { PerAppPath(1 shl 30, 0, -1) }
    }

    @Test
    fun theHostItselfIsNoCollisionNorIsAnotherHost() {
        val example = host("example.com")
        assertNull(AppIsolation.collision(example, listOf(example, host("app.uniswap.org"))))
        assertNull(AppIsolation.collision(example, emptyList()))
    }
}
