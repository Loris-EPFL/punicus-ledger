package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * The host a dApp states for itself, in the one form a per-app address is derived from (issue #252;
 * docs/keys-and-signing.md, *Per-app addresses*): lower case, ASCII, no port, no trailing dot. It is the
 * dApp's own claim (the Verify API is off, docs/walletconnect.md ruling 7), so equal hosts say nothing of
 * which site a dApp really is; they only pick the same address.
 *
 * Validated at construction ([parse]): an [AppHost] is always a DNS name of ASCII labels (letters, digits
 * and hyphens, at most 63 each, none starting or ending with a hyphen), at most 253 characters, never an
 * IPv6 address. A name with characters outside ASCII is refused rather than mapped: the ASCII form of an
 * internationalised name (`xn--…`) is accepted as it is.
 */
@JvmInline
value class AppHost private constructor(val name: String) {

    /**
     * The site this host belongs to, which its per-app address is made for: its registrable domain by the pinned
     * Public Suffix List ([PublicSuffixes]), or the host itself when it has none (owner's decision, 2026-11-15, on
     * PR #297). `app.uniswap.org` and `uniswap.org` are one site; `a.github.io` and `b.github.io` are two.
     */
    val site: AppHost get() = PublicSuffixes.siteOf(this)

    override fun toString(): String = name

    companion object {
        private const val MAX_LENGTH = 253
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /**
         * The host of [url], the URL a dApp states in its WalletConnect metadata, or why it has none. The
         * scheme must be `https` or `http`; the user part, the port, the path, the query and the fragment
         * are dropped, the host is lower-cased and one trailing dot removed. Nothing else is repaired: an
         * authority holding whitespace or a backslash (which a browser reads as `/`, so that
         * `https://evil.test\@app.example/` reaches `evil.test`) has no host.
         */
        fun ofUrl(url: String): AppHostResult {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            if (scheme != "https" && scheme != "http") return AppHostResult.NotAWebAddress
            val raw = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            if (raw.any { it == '\\' || it.isWhitespace() }) return AppHostResult.NotAName
            val authority = raw.substringAfterLast('@')
            if (authority.startsWith("[")) return AppHostResult.NotAName
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', missingDelimiterValue = "")
            if (authority.contains(':') && (port.isEmpty() || !port.all { it in '0'..'9' })) {
                return AppHostResult.NotAName
            }
            return normal(host)
        }

        /**
         * [text], a host alone (as the user types an old site's, or as a sign-in's domain names it without
         * its port), in its normal form, or why it is none.
         */
        fun parse(text: String): AppHostResult = normal(text.trim())

        /** [text] in its normal form, nothing trimmed: lower case, one trailing dot removed, checked. */
        private fun normal(text: String): AppHostResult {
            if (text.any { it.code > 0x7f }) return AppHostResult.NotAscii
            val name = text.lowercase().removeSuffix(".")
            if (name.isEmpty() || name.length > MAX_LENGTH) return AppHostResult.NotAName
            if (!name.split('.').all { LABEL.matches(it) }) return AppHostResult.NotAName
            return AppHostResult.Host(AppHost(name))
        }
    }
}

/** What [AppHost.ofUrl] and [AppHost.parse] find: a host, or why a per-app address cannot be made for it. */
sealed interface AppHostResult {

    /** The host, in its normal form. */
    data class Host(val host: AppHost) : AppHostResult

    /** The dApp stated no `https` or `http` URL. */
    data object NotAWebAddress : AppHostResult

    /** The host has characters outside ASCII: an internationalised name not in its `xn--` form. */
    data object NotAscii : AppHostResult

    /** The host is not a DNS name: empty, too long, an IPv6 address, a bad label or a bad port. */
    data object NotAName : AppHostResult
}

/**
 * The BIP-32 path of a per-app address below `m/44'/60'`: `m/44'/60'/[account]'/[change]'/[index]'`, every level
 * hardened. [account] is in `2³⁰..2³¹ - 1`, so a per-app address is never one of a phrase's ordinary accounts
 * (account 0) nor of the low accounts other derivations and account-counting wallets use; [change] and [index]
 * are in `0..2³¹ - 1`.
 */
data class PerAppPath(val account: Int, val change: Int, val index: Int) {
    init {
        require(account >= AppIsolation.ACCOUNT_FLOOR) { "a per-app account is at least 2^30" }
        require(change >= 0 && index >= 0) { "a hardened level is below 2^31" }
    }

    /** The path as BIP-32 writes it, as the account's details show it. */
    override fun toString(): String = "m/44'/60'/$account'/$change'/$index'"
}

/**
 * The path of a per-app address (issue #252; docs/keys-and-signing.md, *Per-app addresses*; the path's width,
 * owner's decision 2026-11-14 on PR #297; the site, owner's decision 2026-11-15 on PR #297): `m/44'/60'/a'/b'/c'`,
 * from the first 92 bits of `keccak256("serotine/app-isolation/v1/" ‖ site)`, the site being the host's registrable
 * domain ([AppHost.site]), read big-endian: `a` is 2³⁰ plus the first 30 bits, `b`
 * the next 31, `c` the next 31. A dApp states its host unverified and can search many, so 92 bits keep it from
 * choosing a host that lands on another site's address, and `a ≥ 2³⁰` keeps every per-app address out of the low
 * accounts.
 * The derivation does not depend on the chain or on anything stored: the same phrase and host always give the same
 * address.
 */
object AppIsolation {

    /**
     * The domain separator hashed before the host. Changing it changes every per-app address. It ends in `/`, which
     * no host holds, so no later tag can hash as this one with another host (owner's decision, 2026-11-14).
     */
    const val TAG = "serotine/app-isolation/v1/"

    /** The least account of a per-app path: 2³⁰. */
    const val ACCOUNT_FLOOR = 1 shl 30

    private const val LOW_31 = 0x7fffffffL

    /** [host]'s path: its site's ([AppHost.site]), the same for every host of that site. */
    fun pathOf(host: AppHost): PerAppPath {
        val hash = keccak256((TAG + host.site.name).encodeToByteArray())
        fun word(at: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (hash[at + i].toLong() and 0xff) }
        // The first 96 bits as three big-endian words w0 w1 w2: a takes bits 0..29, b 30..60, c 61..91.
        val w0 = word(0)
        val w1 = word(4)
        val w2 = word(8)
        val a = (w0 ushr 2).toInt()
        val b = (((w0 and 0x3) shl 29) or (w1 ushr 3)).toInt()
        val c = ((((w1 and 0x7) shl 32) or w2) ushr 4 and LOW_31).toInt()
        return PerAppPath(ACCOUNT_FLOOR or a, b, c)
    }

    /**
     * The host among [held], the hosts whose per-app addresses this device holds for one phrase, of another site
     * whose path is [host]'s, or null. Such a host is refused for [host] ("already used for another app"), never
     * shared silently: the two sites would see one address. With 92 bits of the hash this is not expected to happen;
     * the check stays so that it can never happen silently. A host of [host]'s own site among [held] is no
     * collision: one site has one address.
     */
    fun collision(host: AppHost, held: Iterable<AppHost>): AppHost? {
        val path = pathOf(host)
        val site = host.site
        return held.firstOrNull { it.site != site && pathOf(it) == path }
    }
}
