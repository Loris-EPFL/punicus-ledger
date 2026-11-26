package eth.serotine.ui.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers

/** The shared enrollment screen, exposed by a host only when a Ledger device port is wired. */
@Composable
fun LedgerAccountRoute(
    viewModel: LedgerAccountViewModel,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    LaunchedEffect(state.step) {
        if (state.step == LedgerEnrollmentStep.Added) onAdded()
    }
    LedgerAccountScreen(
        state = state,
        onPath = viewModel::editPath,
        onNetwork = viewModel::chooseNetwork,
        onTurnOn = viewModel::turnOnNetwork,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancelConfirmation,
        onAdd = viewModel::add,
        onBack = {
            viewModel.close()
            onBack()
        },
    )
}
