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
    NoAccountOpenScreen(walletEmpty = (state as? AccountsUiState.Ready)?.accounts?.isEmpty() == true, modifier)
}

/**
 * The detail pane on a wide window while no account is open in it (docs/wallet-ux.md, *Navigation*):
 * the Accounts list is beside it, and choosing a row there opens that account here. Says how, or,
 * while the wallet holds no account ([walletEmpty]), that one has to be added first. Shows nothing
 * about the chain: the list beside it does.
 */
@Composable
fun NoAccountOpenScreen(walletEmpty: Boolean, modifier: Modifier = Modifier) {
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
                Text(
                    if (walletEmpty) NO_ACCOUNT_YET_HINT else NO_ACCOUNT_OPEN_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
