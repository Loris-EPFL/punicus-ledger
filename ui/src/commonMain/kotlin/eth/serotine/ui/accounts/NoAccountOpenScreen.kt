package eth.serotine.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.SerotineIcons

/** The pane beside the list that no account's detail fills yet. */
const val NO_ACCOUNT_OPEN_TAG = "no-account-open"

/** What the pane says while the wallet has accounts and none is open. */
internal const val NO_ACCOUNT_OPEN_HINT =
    "Choose an account in the list. Its balances, its activity and what it can do show here."

/** What the pane says while the wallet has no account: the list beside it says how to add one. */
internal const val NO_ACCOUNT_YET_HINT =
    "Once you add an account with the + button, choose it in the list to see it here."

/**
 * Binds [NoAccountOpenScreen] to the Accounts list's own [viewModel], drawn in the pane beside it:
 * whether the wallet holds an account decides what the pane says.
 */
@Composable
fun NoAccountOpenRoute(viewModel: AccountsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NoAccountOpenScreen(noAccountOpenHint(state), modifier)
}

/**
 * What the pane beside the list says under its title, by what the list [state] holds: how to open an
 * account, or that one is added first. Nothing while the list is read, or cannot be: the list beside
 * it says so, and there is nothing to choose yet.
 */
internal fun noAccountOpenHint(state: AccountsUiState): String? = when (state) {
    is AccountsUiState.Ready -> if (state.accounts.isEmpty()) NO_ACCOUNT_YET_HINT else NO_ACCOUNT_OPEN_HINT
    is AccountsUiState.Loading, is AccountsUiState.ListUnavailable -> null
}

/**
 * The detail pane on a wide window while no account is open in it (docs/wallet-ux.md, *Navigation*):
 * the Accounts list is beside it, and choosing a row there opens that account here. Its title, and
 * under it the display-ready [hint] when there is one ([noAccountOpenHint]). Shows nothing about the
 * chain: the list beside it does.
 */
@Composable
fun NoAccountOpenScreen(hint: String?, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize().testTag(NO_ACCOUNT_OPEN_TAG), color = MaterialTheme.colorScheme.surface) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 360.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    SerotineIcons.Wallet,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("No account open", style = MaterialTheme.typography.titleMedium)
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
