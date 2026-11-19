package eth.serotine.ui.perapp

import eth.serotine.domain.model.WalletPassword

/**
 * Screen D9, the per-app addresses held here (issue #252; docs/walletconnect.md, *Every per-app address is listed*),
 * as display-ready values: its [title]; its [rows], each with its site, address, networks, phrase and path; [empty],
 * what the list says while it has none; and "Restore a per-app address": its [restore] form while open, or
 * [restoreBlocked], why it is greyed out (no phrase held here, no network on). [notice] says how the last restore
 * went, and [explaining] what a tap on the greyed-out Restore says, until dismissed.
 */
data class PerAppUiState(
    val loading: Boolean = true,
    val title: String = "Per-app addresses",
    val rows: List<PerAppRow> = emptyList(),
    val empty: String? = null,
    val restore: RestoreForm? = null,
    val restoreBlocked: String? = null,
    val notice: String? = null,
    val explaining: String? = null,
)

/**
 * One per-app address: the [site] it was made for, its EIP-55 [address], the [networks] it is an account on, the
 * [phrase] it comes from (named by an account of it), and its full [path] below the seed, so another wallet can find
 * it. [chainId] and [hex] name its first account, which a tap opens.
 */
data class PerAppRow(
    val site: String,
    val address: String,
    val networks: String,
    val phrase: String,
    val path: String,
    val chainId: Long,
    val hex: String,
)

/**
 * The open form of "Restore a per-app address": the [host] typed; the [phrases] to choose from, with the [phrase]
 * chosen (an index into them), shown only when there are several; the wallet [password] typed; [busy] while the
 * address is made; and [canRestore], whether Restore may be tapped.
 */
data class RestoreForm(
    val host: String = "",
    val phrases: List<String> = emptyList(),
    val phrase: Int = 0,
    val password: WalletPassword = WalletPassword.EMPTY,
    val busy: Boolean = false,
) {
    /** Restore needs a host and the password. */
    val canRestore: Boolean get() = !busy && host.isNotBlank() && password.text.isNotEmpty()
}
