package eth.serotine.ui.withdraw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.done
import eth.serotine.ui.components.submitOnDone
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the amount field, for UI tests. */
const val WITHDRAW_AMOUNT_TAG = "withdraw-amount"

/** Test tag of All, which fills in what is free to withdraw, for UI tests. */
const val WITHDRAW_ALL_TAG = "withdraw-all"

/** Test tag of Review, for UI tests. */
const val WITHDRAW_REVIEW_TAG = "withdraw-review"

/** Test tag of a review's warning, for UI tests. */
const val WITHDRAW_WARNING_TAG = "withdraw-warning"

/** Test tag of the password field, for UI tests. */
const val WITHDRAW_PASSWORD_TAG = "withdraw-password"

/** Test tag of the button that signs, for UI tests. */
const val WITHDRAW_SIGN_TAG = "withdraw-sign"

/**
 * Binds [WithdrawScreen] to its ViewModel: a node that leaves the wallet ends the screen through
 * [onGone]; the withdrawal sent hands its hash to [onSent], whose status takes this screen's place.
 * A screen no longer shown drops the password typed and has its quote made again when shown.
 * [lightClient] is Gnosis Chain's: the strip in Detailed view, the sync line in simple mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WithdrawRoute(
    viewModel: WithdrawViewModel,
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onSent: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val item by lightClient.collectAsStateWithLifecycle()
    val sent by viewModel.sent.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    val done by rememberUpdatedState(onSent)
    LaunchedEffect(state is WithdrawUiState.Gone) {
        if (state is WithdrawUiState.Gone) gone()
    }
    LaunchedEffect(sent) {
        sent?.let {
            viewModel.onSentShown()
            done(it)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val reviewing = (state as? WithdrawUiState.Ready)?.step is WithdrawStep.Review
    BackHandler(enabled = reviewing) { if (!viewModel.back()) onBack() }
    DisposableEffect(viewModel) { onDispose { viewModel.onHidden() } }
    WithdrawScreen(
        state = state,
        onBack = { if (!viewModel.back()) onBack() },
        actions = WithdrawActions(
            onAmount = viewModel::onAmount,
            onReview = viewModel::review,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
        ),
        modifier = modifier,
        lightClient = item.takeIf { LocalInterfaceMode.current == InterfaceMode.DETAILED },
        syncLine = item.takeIf { LocalInterfaceMode.current == InterfaceMode.SIMPLE },
    )
}

/** What the user can do on [WithdrawScreen]: one callback per intent. */
data class WithdrawActions(
    val onAmount: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSign: () -> Unit = {},
    val onSignWithBiometrics: () -> Unit = {},
)

/**
 * A Swarm node's xBZZ withdrawn from its chequebook (docs/swarm.md, *Withdraw from the
 * chequebook*), as a pure function of [state]: the amount, then the review with its warnings and
 * the unlock that signs it. Under Gnosis Chain's light client strip ([lightClient], Detailed view)
 * or its sync line ([syncLine], simple mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithdrawScreen(
    state: WithdrawUiState,
    onBack: () -> Unit,
    actions: WithdrawActions,
    modifier: Modifier = Modifier,
    lightClient: LightClientItem? = null,
    syncLine: LightClientItem? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(WithdrawTexts.TITLE) },
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
                    WithdrawUiState.Loading, WithdrawUiState.Gone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Checking on chain…")
                    }
                    is WithdrawUiState.Unavailable -> Text(state.message)
                    is WithdrawUiState.Ready -> {
                        Text(state.node, style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Mono(state.chequebook)
                                state.lines.forEach { Text(it) }
                            }
                        }
                        when (val step = state.step) {
                            is WithdrawStep.Compose -> Compose(step, actions)
                            is WithdrawStep.Review -> Review(step, actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Compose(step: WithdrawStep.Compose, actions: WithdrawActions) {
    Text("The xBZZ goes to the node's own address: only the node, the chequebook's issuer, may withdraw.")
    OutlinedTextField(
        value = step.amount,
        onValueChange = actions.onAmount,
        label = { Text("Amount, in BZZ") },
        singleLine = true,
        enabled = !step.quoting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal).done(),
        keyboardActions = submitOnDone(!step.quoting, actions.onReview),
        modifier = Modifier.fillMaxWidth().testTag(WITHDRAW_AMOUNT_TAG),
    )
    step.all?.let { all ->
        TextButton(
            onClick = { actions.onAmount(all) },
            enabled = !step.quoting,
            modifier = Modifier.testTag(WITHDRAW_ALL_TAG),
        ) { Text("All that is free: $all BZZ") }
    }
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = actions.onReview,
        enabled = !step.quoting,
        modifier = Modifier.fillMaxWidth().testTag(WITHDRAW_REVIEW_TAG),
    ) { Text(if (step.quoting) "Checking on chain…" else "Review") }
}

@Composable
private fun Review(step: WithdrawStep.Review, actions: WithdrawActions) {
    step.warnings.forEach {
        Card(Modifier.fillMaxWidth().testTag(WITHDRAW_WARNING_TAG)) {
            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
    }
    step.lines.forEach { (label, value) ->
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (value.startsWith("0x") && !value.contains(' ')) Mono(value) else Text(value)
        }
    }
    step.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    UnlockFields(
        unlock = step.unlock,
        signing = step.signing,
        canSign = !step.signing && step.unlock.password.text.isNotEmpty(),
        canSignWithBiometrics = !step.signing && step.unlock.biometrics,
        signLabel = "Sign and send",
        signingLabel = if (step.checking) "Checking on chain…" else "Sending…",
        onPasswordChange = actions.onPasswordChange,
        onSign = actions.onSign,
        onSignWithBiometrics = actions.onSignWithBiometrics,
        passwordTag = WITHDRAW_PASSWORD_TAG,
        signTag = WITHDRAW_SIGN_TAG,
    )
}

/** An address or a hash, in full and in a fixed-width face. */
@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
