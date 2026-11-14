package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    // keccak256("serotine/app-isolation/v1/" ‖ host) → a, b, c. Computed independently of this code.
    @Test
    fun thePathIsTheFirst92BitsOfTheTaggedHash() {
        val vectors = mapOf(
            "example.com" to PerAppPath(1_336_343_364, 399_755_631, 110_655_970), // 3e9bed10be9e4b7869879e28
            "app.uniswap.org" to PerAppPath(1_516_966_953, 1_787_332_407, 1_989_753_074), // 69ac50a7544439bf69938f21
            "walletbeat.eth.limo" to PerAppPath(2_058_966_761, 904_126_976, 98_949_316), // eae55ba5af1f10005e5d8c41
            "app.safe.global" to PerAppPath(1_181_040_959, 1_799_679_872, 2_038_531_148), // 199504ff5a277c07981844c3
            "localhost" to PerAppPath(1_257_319_863, 1_751_139_060, 1_271_328_885), // 2bc4b6df430217a4bc6f075e
        )
        vectors.forEach { (name, path) -> assertEquals(path, AppIsolation.pathOf(host(name)), name) }
        assertEquals(
            "m/44'/60'/1336343364'/399755631'/110655970'",
            AppIsolation.pathOf(host("example.com")).toString(),
        )
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
