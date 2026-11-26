package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/** A canonical Ledger Ethereum derivation path, with 3–5 BIP-32 indices under `m/44'/60'`. */
@JvmInline
value class LedgerPath private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val ETHEREUM_PREFIX = "44'/60'/"

        /** The first account in Ledger Live's Ethereum derivation scheme. */
        val DEFAULT = LedgerPath("m/44'/60'/0'/0/0")

        /** Reads decimal uint31 indices with optional apostrophes for hardening, and normalizes the `m/` prefix. */
        fun parse(value: String): LedgerPath? {
            val path = value.trim().removePrefix("m/")
            if (!path.startsWith(ETHEREUM_PREFIX)) return null
            val tail = path.substring(ETHEREUM_PREFIX.length).split('/', limit = 4)
            if (tail.size !in 1..3) return null
            val canonical = StringBuilder("m/$ETHEREUM_PREFIX")
            for ((position, component) in tail.withIndex()) {
                val hardened = component.endsWith('\'')
                val digits = if (hardened) component.dropLast(1) else component
                if (digits.length !in 1..10 || digits.any { it !in '0'..'9' }) return null
                val index = digits.toIntOrNull() ?: return null
                if (position != 0) canonical.append('/')
                canonical.append(index)
                if (hardened) canonical.append('\'')
            }
            return LedgerPath(canonical.toString())
        }
    }
}
