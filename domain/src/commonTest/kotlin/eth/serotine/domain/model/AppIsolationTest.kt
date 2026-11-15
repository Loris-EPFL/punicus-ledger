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

    // The fixed vectors of docs/keys-and-signing.md, *Per-app addresses*: host → its site → the first twelve bytes
    // of keccak256("serotine/app-isolation/v1/" ‖ site) → a, b, c. Computed independently of this code.
    @Test
    fun thePathIsTheFirst92BitsOfTheTaggedHashOfTheSite() {
        val vectors = mapOf(
            "example.com" to PerAppPath(1_336_343_364, 399_755_631, 110_655_970), // 3e9bed10be9e4b7869879e28
            "app.uniswap.org" to PerAppPath(2_078_581_563, 1_827_501_205, 1_513_768_218), // ef928cef676ba4ada3a451a0
            "walletbeat.eth.limo" to PerAppPath(2_058_966_761, 904_126_976, 98_949_316), // eae55ba5af1f10005e5d8c41
            "app.safe.global" to PerAppPath(1_227_376_802, 158_830_283, 228_636_467), // 24a122884bbc7658da0b7336
            "localhost" to PerAppPath(1_257_319_863, 1_751_139_060, 1_271_328_885), // 2bc4b6df430217a4bc6f075e
        )
        vectors.forEach { (name, path) -> assertEquals(path, AppIsolation.pathOf(host(name)), name) }
        assertEquals(AppIsolation.pathOf(host("uniswap.org")), AppIsolation.pathOf(host("app.uniswap.org")))
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
    fun theHostItselfIsNoCollisionNorIsAnotherHostOfItsSiteNorAnotherSite() {
        val example = host("example.com")
        assertNull(AppIsolation.collision(example, listOf(example, host("www.example.com"), host("app.uniswap.org"))))
        assertNull(AppIsolation.collision(example, emptyList()))
    }

    private fun site(name: String): String = host(name).site.name

    // The pinned Public Suffix List (owner's decision, 2026-11-15, on PR #297); cases of the list's own
    // tests/test_psl.txt among them.
    @Test
    fun aHostsSiteIsItsRegistrableDomain() {
        assertEquals("uniswap.org", site("app.uniswap.org"))
        assertEquals("uniswap.org", site("uniswap.org"))
        assertEquals("example.com", site("www.example.com"))
        assertEquals("example.com", site("a.b.c.example.com"))
        assertEquals("b.co.uk", site("a.b.co.uk"))
        assertEquals("safe.global", site("app.safe.global"))
        // Sites hosted for strangers on one domain stay apart.
        assertEquals("foo.github.io", site("foo.github.io"))
        assertEquals("foo.github.io", site("www.foo.github.io"))
        // Every ENS name behind the eth.limo and eth.link gateways is a site of its own (`*.eth.limo`).
        assertEquals("walletbeat.eth.limo", site("walletbeat.eth.limo"))
        assertEquals("app.walletbeat.eth.limo", site("app.walletbeat.eth.limo"))
        assertEquals("uniswap.eth.link", site("uniswap.eth.link"))
        // A wildcard stands for one label; an exception wins over it.
        assertEquals("b.c.kobe.jp", site("a.b.c.kobe.jp"))
        assertEquals("city.kobe.jp", site("www.city.kobe.jp"))
        assertEquals("www.ck", site("www.ck"))
        assertEquals("b.test.ck", site("a.b.test.ck"))
        // An internationalised suffix, in its xn-- form (公司.cn).
        assertEquals("example.xn--55qx5d.cn", site("www.example.xn--55qx5d.cn"))
        // No rule: the last label is the suffix.
        assertEquals("example.unknowntld", site("a.example.unknowntld"))
    }

    @Test
    fun aHostWithNoRegistrableDomainIsItsOwnSite() {
        assertEquals("localhost", site("localhost"))
        assertEquals("github.io", site("github.io"))
        assertEquals("com", site("com"))
        assertEquals("co.uk", site("co.uk"))
        assertEquals("192.168.1.1", site("192.168.1.1"))
        assertEquals("127.0.0.1", (AppHost.ofUrl("http://127.0.0.1:8545") as AppHostResult.Host).host.site.name)
    }
}
