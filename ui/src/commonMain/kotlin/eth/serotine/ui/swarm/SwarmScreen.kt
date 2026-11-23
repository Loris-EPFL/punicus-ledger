package eth.serotine.ui.swarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.flow.StateFlow

/** Test tag of a chequebook's row, for UI tests. */
const val SWARM_CHEQUEBOOK_TAG = "swarm-chequebook"

/** Test tag of a batch's row, for UI tests. */
const val SWARM_BATCH_TAG = "swarm-batch"

/** Test tag of the add form's field, for UI tests. */
const val SWARM_ADD_FIELD_TAG = "swarm-add-field"

/** Test tag of the add form's confirming button, for UI tests. */
const val SWARM_ADD_CONFIRM_TAG = "swarm-add-confirm"

/**
 * Binds [SwarmScreen] to its ViewModel: an account that leaves the wallet ends the screen through
 * [onGone]. [lightClient] is Gnosis Chain's: the strip above the screen in Detailed view, the sync
 * line in simple mode.
 */
@Composable
fun SwarmRoute(
    viewModel: SwarmViewModel,
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item by lightClient.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(state is SwarmUiState.Gone) {
        if (state is SwarmUiState.Gone) gone()
    }
    SwarmScreen(
        state = state,
        onBack = onBack,
        actions = SwarmActions(
            onStartAdd = viewModel::startAdd,
            onAddText = viewModel::onAddText,
            onConfirmAdd = viewModel::confirmAdd,
            onCancelAdd = viewModel::cancelAdd,
            onRemoveChequebook = viewModel::removeChequebook,
            onRemoveBatch = viewModel::removeBatch,
        ),
        modifier = modifier,
        lightClient = item.takeIf { LocalInterfaceMode.current == InterfaceMode.DETAILED },
        syncLine = item.takeIf { LocalInterfaceMode.current == InterfaceMode.SIMPLE },
    )
}

/** What the user can do on [SwarmScreen]: one callback per intent. */
data class SwarmActions(
    val onStartAdd: (AddKind) -> Unit = {},
    val onAddText: (String) -> Unit = {},
    val onConfirmAdd: () -> Unit = {},
    val onCancelAdd: () -> Unit = {},
    val onRemoveChequebook: (String) -> Unit = {},
    val onRemoveBatch: (String) -> Unit = {},
)

/**
 * A Swarm node (docs/wallet-ux.md, *Swarm nodes*), as a pure function of [state]: its chequebooks
 * and postage batches, each as the light client proves it, with its block, and adding one by hand.
 * The same in both views for now: simple mode draws this screen too. Under Gnosis Chain's light
 * client strip ([lightClient], Detailed view) or its sync line ([syncLine], simple mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwarmScreen(
    state: SwarmUiState,
    onBack: () -> Unit,
    actions: SwarmActions,
    modifier: Modifier = Modifier,
    lightClient: LightClientItem? = null,
    syncLine: LightClientItem? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Swarm node") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        UnderLightClientStripIfAny(lightClient, Modifier.padding(padding), syncLine) {
            Column(
                Modifier.align(Alignment.TopCenter)
                    .widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    SwarmUiState.Loading, SwarmUiState.Gone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(SwarmTexts.NOT_READ)
                    }
                    is SwarmUiState.Unavailable -> Text(state.message)
                    is SwarmUiState.Ready -> Ready(state, actions)
                }
            }
        }
    }
    (state as? SwarmUiState.Ready)?.adding?.let { form -> AddDialog(form, actions) }
}

@Composable
private fun Ready(state: SwarmUiState.Ready, actions: SwarmActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountAvatar(state.address)
        Column {
            Text(state.label, style = MaterialTheme.typography.titleLarge)
            Mono(state.address)
        }
    }
    state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("Chequebook", style = MaterialTheme.typography.titleMedium)
    state.chequebooks.forEach { row ->
        Card(Modifier.fillMaxWidth().testTag(SWARM_CHEQUEBOOK_TAG)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mono(row.address)
                row.lines.forEach { Text(it) }
                row.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(row.status, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { actions.onRemoveChequebook(row.address) }) { Text("Remove") }
            }
        }
    }
    state.chequebookNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    OutlinedButton(onClick = { actions.onStartAdd(AddKind.CHEQUEBOOK) }) { Text("Add chequebook") }

    Text("Postage batches", style = MaterialTheme.typography.titleMedium)
    state.batches.forEach { row ->
        Card(Modifier.fillMaxWidth().testTag(SWARM_BATCH_TAG)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mono(row.shortId)
                row.capacity?.let { Text(it) }
                if (row.immutable) Text("Immutable")
                row.timeLeft?.let { Text(it) }
                Text(row.status, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { actions.onRemoveBatch(row.id) }) { Text("Remove") }
            }
        }
    }
    state.batchNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    OutlinedButton(onClick = { actions.onStartAdd(AddKind.BATCH) }) { Text("Add a batch by id") }
}

@Composable
private fun AddDialog(form: AddForm, actions: SwarmActions) {
    val title = when (form.kind) {
        AddKind.CHEQUEBOOK -> "Add chequebook"
        AddKind.BATCH -> "Add a batch by id"
    }
    AlertDialog(
        onDismissRequest = actions.onCancelAdd,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (form.kind) {
                        AddKind.CHEQUEBOOK ->
                            "The chequebook's address, as Bee shows it. It is kept once the light client " +
                                "proves it this node's."
                        AddKind.BATCH ->
                            "The batch's id, as Bee shows it. It is kept once the light client proves this " +
                                "node owns it."
                    },
                )
                OutlinedTextField(
                    value = form.text,
                    onValueChange = actions.onAddText,
                    label = { Text(if (form.kind == AddKind.CHEQUEBOOK) "Address" else "Batch id") },
                    singleLine = true,
                    enabled = !form.checking,
                    isError = form.error != null,
                    supportingText = form.error?.let { error -> { Text(error) } },
                    modifier = Modifier.fillMaxWidth().testTag(SWARM_ADD_FIELD_TAG),
                )
                if (form.checking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text("Checking it on chain…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmAdd,
                enabled = !form.checking,
                modifier = Modifier.testTag(SWARM_ADD_CONFIRM_TAG),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = actions.onCancelAdd) { Text("Cancel") } },
    )
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
