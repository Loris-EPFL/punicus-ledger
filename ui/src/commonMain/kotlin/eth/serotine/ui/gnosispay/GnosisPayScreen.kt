package eth.serotine.ui.gnosispay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnlockFields

/** Test tag of Send through the Delay, for UI tests. */
const val GNOSIS_PAY_SEND_TAG = "gnosis-pay-send"

/** Test tag of a queued transfer's Execute, for UI tests: one per row, suffixed with its id. */
const val GNOSIS_PAY_EXECUTE_TAG = "gnosis-pay-execute"

/** Test tag of the recipient field, for UI tests. */
const val GNOSIS_PAY_TO_TAG = "gnosis-pay-to"

/** Test tag of the amount field, for UI tests. */
const val GNOSIS_PAY_AMOUNT_TAG = "gnosis-pay-amount"

/** Test tag of Review, for UI tests. */
const val GNOSIS_PAY_REVIEW_TAG = "gnosis-pay-review"

/** Test tag of the password field, for UI tests. */
const val GNOSIS_PAY_PASSWORD_TAG = "gnosis-pay-password"

/** Test tag of the button that signs, for UI tests. */
const val GNOSIS_PAY_SIGN_TAG = "gnosis-pay-sign"

/**
 * Binds [GnosisPayScreen] to its ViewModel: an account that leaves the wallet ends the screen
 * through [onGone], a transaction sent opens its status through [onSent], and a screen no longer
 * shown drops the password typed.
 */
@Composable
fun GnosisPayRoute(
    viewModel: GnosisPayViewModel,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onSent: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sent by viewModel.sent.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    val opened by rememberUpdatedState(onSent)
    LaunchedEffect(state is GnosisPayUiState.Gone) {
        if (state is GnosisPayUiState.Gone) gone()
    }
    LaunchedEffect(sent) {
        sent?.let {
            viewModel.onSentShown()
            opened(it)
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.onHidden() } }
    GnosisPayScreen(
        state = state,
        onBack = { if (!viewModel.back()) onBack() },
        actions = GnosisPayActions(
            onSend = viewModel::startSend,
            onExecute = { viewModel.execute(it) },
            onAssetChange = viewModel::onAssetChange,
            onToChange = viewModel::onToChange,
            onAmountChange = viewModel::onAmountChange,
            onReview = { viewModel.review() },
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
        ),
        modifier = modifier,
    )
}

/** What the user can do on [GnosisPayScreen]: one callback per intent. */
data class GnosisPayActions(
    val onSend: () -> Unit = {},
    val onExecute: (String) -> Unit = {},
    val onAssetChange: (String?) -> Unit = {},
    val onToChange: (String) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSign: () -> Unit = {},
    val onSignWithBiometrics: () -> Unit = {},
)

/**
 * A Gnosis Pay account (docs/gnosis-pay.md), as a pure function of [state]: what its Delay module
 * makes a transfer do, who controls it, the transfers queued from here with Execute, and Send
 * through the Delay; then the transfer's form and its review with the unlock that signs. An action
 * that cannot be done now is greyed out, and a tap says why (owner ruling, 2026-10-18, #55).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GnosisPayScreen(
    state: GnosisPayUiState,
    onBack: () -> Unit,
    actions: GnosisPayActions,
    modifier: Modifier = Modifier,
) {
    var unavailable by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Gnosis Pay") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    GnosisPayUiState.Loading, GnosisPayUiState.Gone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Checking the account on chain…")
                    }
                    is GnosisPayUiState.Unavailable -> Text(state.message)
                    is GnosisPayUiState.Ready -> when (val step = state.step) {
                        GnosisPayStep.Overview -> Overview(state, actions) { unavailable = it }
                        is GnosisPayStep.Compose -> Compose(state, step, actions)
                        is GnosisPayStep.Review -> Review(step, actions)
                    }
                }
            }
        }
    }
    unavailable?.let { reason -> UnavailableDialog("Not available yet", reason, onDismiss = { unavailable = null }) }
}

@Composable
private fun Overview(state: GnosisPayUiState.Ready, actions: GnosisPayActions, onUnavailable: (String) -> Unit) {
    Text(state.label, style = MaterialTheme.typography.titleLarge)
    Mono(state.address)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Controlled through its Delay module", style = MaterialTheme.typography.titleSmall)
            Mono(state.delay)
            Text(state.waits)
            Text(state.checkedAt, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Controlled by", style = MaterialTheme.typography.titleSmall)
    state.members.forEach { member ->
        Column {
            Text(
                listOfNotNull(member.label, if (member.heldHere) "key on this device" else "key not on this device")
                    .joinToString(" · "),
            )
            Mono(member.address)
        }
    }
    Button(
        onClick = { if (state.send.enabled) actions.onSend() else state.send.reason?.let(onUnavailable) },
        modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_SEND_TAG),
        enabled = true,
        colors = if (state.send.enabled) {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { Text("Send through the Delay") }
    Text("Queued from this device", style = MaterialTheme.typography.titleSmall)
    if (state.queued.isEmpty()) Text("Nothing is waiting.", style = MaterialTheme.typography.bodyMedium)
    state.queued.forEach { row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall)
                Text(row.status)
                row.execute?.let { execute ->
                    OutlinedButton(
                        onClick = { if (execute.enabled) actions.onExecute(row.id) else execute.reason?.let(onUnavailable) },
                        modifier = Modifier.testTag("$GNOSIS_PAY_EXECUTE_TAG-${row.id}"),
                    ) { Text(if (execute.enabled) "Execute" else "Execute (not yet)") }
                }
            }
        }
    }
    state.queuedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Compose(state: GnosisPayUiState.Ready, step: GnosisPayStep.Compose, actions: GnosisPayActions) {
    Text("Send from ${state.label}", style = MaterialTheme.typography.titleLarge)
    Text("Signed by ${step.sender.ifBlank { "the controlling account" }}, which pays the fees.")
    Text("Asset", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        step.assets.forEach { asset ->
            FilterChip(
                selected = asset.contract == step.asset,
                onClick = { actions.onAssetChange(asset.contract) },
                enabled = !step.quoting,
                label = { Text("${asset.symbol} · ${asset.balance}") },
            )
        }
    }
    OutlinedTextField(
        value = step.to,
        onValueChange = actions.onToChange,
        label = { Text("To (address)") },
        singleLine = true,
        enabled = !step.quoting,
        modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_TO_TAG),
    )
    OutlinedTextField(
        value = step.amount,
        onValueChange = actions.onAmountChange,
        label = { Text("Amount") },
        singleLine = true,
        enabled = !step.quoting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_AMOUNT_TAG),
    )
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = actions.onReview,
        enabled = !step.quoting,
        modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_REVIEW_TAG),
    ) { Text(if (step.quoting) "Checking on chain…" else "Review") }
}

@Composable
private fun Review(step: GnosisPayStep.Review, actions: GnosisPayActions) {
    Text(step.title, style = MaterialTheme.typography.titleLarge)
    step.lines.forEach { (label, value) ->
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value)
        }
    }
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    UnlockFields(
        unlock = step.unlock,
        signing = step.signing,
        canSign = !step.signing && step.unlock.password.text.isNotEmpty(),
        canSignWithBiometrics = !step.signing && step.unlock.biometrics,
        signLabel = "Sign and send",
        signingLabel = "Sending…",
        onPasswordChange = actions.onPasswordChange,
        onSign = actions.onSign,
        onSignWithBiometrics = actions.onSignWithBiometrics,
        passwordTag = GNOSIS_PAY_PASSWORD_TAG,
        signTag = GNOSIS_PAY_SIGN_TAG,
    )
}

/** An address or a hash, in full and in a fixed-width face, as the other screens show one. */
@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
