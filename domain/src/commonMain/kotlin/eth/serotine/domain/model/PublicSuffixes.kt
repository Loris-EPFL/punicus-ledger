package eth.serotine.domain.model

/**
 * The registrable domain of a host, by the pinned Public Suffix List ([PUBLIC_SUFFIX_RULES], issue #252; owner's
 * decision, 2026-11-15, on PR #297): the public suffix the list's rules give, plus one label more. It is the site a
 * per-app address is made for, so that `app.uniswap.org` and `swap.uniswap.org` share one, while `a.github.io` and
 * `b.github.io`, or two ENS names under `eth.limo`, do not.
 *
 * The rules are matched as the list's own algorithm says (publicsuffix.org, *Formal algorithm*): the rule matching
 * the most labels wins, an exception rule (`!www.ck`) wins over the rules it is an exception to, a wildcard (`*.ck`)
 * stands for exactly one label, and with no rule matching the last label alone is the suffix. A host that is itself
 * a public suffix (`github.io`, `localhost`) has no registrable domain and stands for itself, as does an IPv4
 * address. The list is pinned: it is never updated without the owner, since updating it can change which site a
 * host belongs to, and so its address.
 */
object PublicSuffixes {

    private val rules: Set<String> by lazy {
        PUBLIC_SUFFIX_RULES.flatMapTo(HashSet()) { chunk -> chunk.split('\n') }
    }

    /** The pinned list's sha256, as docs/keys-and-signing.md names it. */
    const val SHA256: String = PUBLIC_SUFFIX_LIST_SHA256

    /** [host]'s site: its registrable domain, or [host] itself when it has none. */
    fun siteOf(host: AppHost): AppHost {
        val labels = host.name.split('.')
        // A dotted quad: no TLD is all digits, so the list says nothing of it.
        if (labels.last().all { it in '0'..'9' }) return host
        val suffix = suffixLength(labels)
        if (labels.size <= suffix) return host
        val site = labels.takeLast(suffix + 1).joinToString(".")
        // A tail of a valid host is a valid host.
        return (AppHost.parse(site) as AppHostResult.Host).host
    }

    /**
     * How many of [labels]' last labels its public suffix is. An exception rule prevails over every other; otherwise
     * the rule matching the most labels, the default rule `*` (the last label) when none does.
     */
    private fun suffixLength(labels: List<String>): Int {
        fun tail(start: Int) = labels.subList(start, labels.size).joinToString(".")
        val exception = labels.indices.firstOrNull { "!${tail(it)}" in rules }
        if (exception != null) return labels.size - exception - 1
        val longest = labels.indices.firstOrNull { start ->
            tail(start) in rules || (start + 1 < labels.size && "*.${tail(start + 1)}" in rules)
        }
        return if (longest != null) labels.size - longest else 1
    }
}
