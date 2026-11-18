package eth.serotine.ui.gnosispay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.done
import eth.serotine.ui.components.next
import eth.serotine.ui.components.nextTo
import eth.serotine.ui.components.submitOnDone
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of New transfer (a transfer queued through the Delay), for UI tests. */
const val GNOSIS_PAY_SEND_TAG = "gnosis-pay-send"

/** Test tag of a queued transfer's Execute, for UI tests: one per row, suffixed with its id. */
const val GNOSIS_PAY_EXECUTE_TAG = "gnosis-pay-execute"

/** The tag of each key listed under Keys, reached through a member Safe, for tests. */
const val GNOSIS_PAY_KEY_TAG = "gnosis-pay-key"

/** Test tag of the recipient field, for UI tests. */
const val GNOSIS_PAY_TO_TAG = "gnosis-pay-to"

/** Test tag of the amount field, for UI tests. */
const val GNOSIS_PAY_AMOUNT_TAG = "gnosis-pay-amount"

/** Test tag of Review, for UI tests. */
const val GNOSIS_PAY_REVIEW_TAG = "gnosis-pay-review"
/** Skip expired entries: on the form, and (suffixed with the queueing's hash) on a queued transfer's row. */
const val GNOSIS_PAY_SKIP_TAG = "gnosis-pay-skip"

/** A queued transfer's cooldown bar (suffixed with the queueing's hash), for UI tests. */
const val GNOSIS_PAY_COOLDOWN_TAG = "gnosis-pay-cooldown"

/** Test tag of the password field, for UI tests. */
const val GNOSIS_PAY_PASSWORD_TAG = "gnosis-pay-password"

/** Test tag of the button that signs, for UI tests. */
const val GNOSIS_PAY_SIGN_TAG = "gnosis-pay-sign"

/**
 * Binds [GnosisPayScreen] to its ViewModel: an account that leaves the wallet ends the screen
 * through [onGone], a transaction sent opens its status through [onSent], and a screen no longer
 * shown drops the password typed and has its quote made again when it is shown. [lightClient] is
 * Gnosis Chain's: the strip above the screen in Detailed view, the sync line in simple mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GnosisPayRoute(
    viewModel: GnosisPayViewModel,
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onSent: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on Send, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val item by lightClient.collectAsStateWithLifecycle()
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
    // The password goes when the screen is no longer shown: the app in the background, or the screen left.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    // The system's back steps as the top bar's does: from the form or the review to the overview,
    // and it waits while a signature runs (the ViewModel holds it), never leaving the screen under it.
    val stepped = (state as? GnosisPayUiState.Ready)?.step.let { it != null && it !is GnosisPayStep.Overview }
    BackHandler(enabled = stepped) { viewModel.back() }
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
            onSkipExpired = { viewModel.skipExpired() },
        ),
        modifier = modifier,
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        lightClient = item.takeIf { LocalInterfaceMode.current == InterfaceMode.DETAILED },
        syncLine = item.takeIf { LocalInterfaceMode.current == InterfaceMode.SIMPLE },
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
    val onSkipExpired: () -> Unit = {},
)

/**
 * A Gnosis Pay account (docs/gnosis-pay.md), as a pure function of [state]: what its Delay module
 * makes a transfer do, who controls it, the transfers queued from here with Execute, and Send
 * through the Delay; then the transfer's form and its review with the unlock that signs. An action
 * that cannot be done now is greyed out, and a tap says why (owner ruling, 2026-10-18, #55).
 * Under Gnosis Chain's light client strip ([lightClient], Detailed view) or its sync line
 * ([syncLine], simple mode), as every screen that reads the chain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GnosisPayScreen(
    state: GnosisPayUiState,
    onBack: () -> Unit,
    actions: GnosisPayActions,
    modifier: Modifier = Modifier,
    lightClient: LightClientItem? = null,
    syncLine: LightClientItem? = null,
) {
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Gnosis Pay") },
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
                    GnosisPayUiState.Loading, GnosisPayUiState.Gone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Checking the account on chain…")
                    }
                    is GnosisPayUiState.Unavailable -> Text(state.message)
                    is GnosisPayUiState.Ready -> when (val step = state.step) {
                        is GnosisPayStep.Overview -> Overview(state, step, actions) { unavailable = it }
                        is GnosisPayStep.Compose -> Compose(state, step, actions)
                        is GnosisPayStep.Review -> Review(step, actions)
                    }
                }
            }
        }
    }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
}

@Composable
private fun Overview(
    state: GnosisPayUiState.Ready,
    step: GnosisPayStep.Overview,
    actions: GnosisPayActions,
    onUnavailable: (UnavailableNotice) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountAvatar(state.address)
        Column {
            Text(state.label, style = MaterialTheme.typography.titleLarge)
            Mono(state.address)
        }
    }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountAvatar(member.address, size = 32.dp)
            Column {
                Text(
                    labelled(
                        member.label,
                        when {
                            member.what == null && member.heldHere -> "key on this device"
                            member.what == null -> "key not on this device"
                            member.heldHere -> "a key on this device controls it"
                            else -> null
                        },
                    ).ifEmpty { "Member" },
                )
                Mono(member.address)
                member.what?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    // The keys at the end of the ways down, where a member is a Safe (owner's request, 2026-11-17).
    if (state.keys.any { it.through != null }) {
        Text("Keys", style = MaterialTheme.typography.titleSmall)
        state.keys.filter { it.through != null }.forEach { key ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag(GNOSIS_PAY_KEY_TAG),
            ) {
                AccountAvatar(key.address, size = 32.dp)
                Column {
                    Text(
                        labelled(
                            key.label,
                            if (key.heldHere) "key on this device" else "key not on this device",
                            "only with co-owners".takeIf { !key.alone },
                        ),
                    )
                    Mono(key.address)
                    key.through?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    state.controlNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    val sendLabel = "New transfer"
    Button(
        onClick = {
            if (state.send.enabled) {
                actions.onSend()
            } else {
                state.send.reason?.let { onUnavailable(UnavailableNotice(sendLabel, it)) }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_SEND_TAG).unavailable(!state.send.enabled),
        colors = if (state.send.enabled) ButtonDefaults.buttonColors() else Unavailable.buttonColors(),
    ) { Text(sendLabel) }
    Text("Queued from this device", style = MaterialTheme.typography.titleSmall)
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.queued.isEmpty()) Text("Nothing is waiting.", style = MaterialTheme.typography.bodyMedium)
    state.queued.forEach { row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall)
                Text(row.status)
                // The cooldown draining to zero: Execute comes when it is empty.
                row.cooldownLeft?.let { left ->
                    LinearProgressIndicator(
                        progress = { left },
                        modifier = Modifier.fillMaxWidth().testTag("$GNOSIS_PAY_COOLDOWN_TAG-${row.id}"),
                    )
                }
                row.execute?.let { execute ->
                    OutlinedButton(
                        onClick = {
                            if (execute.enabled) {
                                actions.onExecute(row.id)
                            } else {
                                execute.reason?.let { onUnavailable(UnavailableNotice("Execute ${row.title}", it)) }
                            }
                        },
                        modifier = Modifier.testTag("$GNOSIS_PAY_EXECUTE_TAG-${row.id}").unavailable(!execute.enabled),
                        colors = if (execute.enabled) {
                            ButtonDefaults.outlinedButtonColors()
                        } else {
                            ButtonDefaults.outlinedButtonColors(contentColor = Unavailable.contentColor())
                        },
                    ) { Text("Execute") }
                }
                row.skip?.let { skip ->
                    OutlinedButton(
                        onClick = {
                            if (skip.enabled) {
                                actions.onSkipExpired()
                            } else {
                                skip.reason?.let { onUnavailable(UnavailableNotice("Skip expired entries", it)) }
                            }
                        },
                        modifier = Modifier.testTag("$GNOSIS_PAY_SKIP_TAG-${row.id}").unavailable(!skip.enabled),
                        colors = if (skip.enabled) {
                            ButtonDefaults.outlinedButtonColors()
                        } else {
                            ButtonDefaults.outlinedButtonColors(contentColor = Unavailable.contentColor())
                        },
                    ) { Text("Skip expired entries") }
                }
            }
        }
    }
    state.queuedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Compose(state: GnosisPayUiState.Ready, step: GnosisPayStep.Compose, actions: GnosisPayActions) {
    val amountField = remember { FocusRequester() }
    Text("Send from ${state.label}", style = MaterialTheme.typography.titleLarge)
    Text("Signed by ${step.sender.ifBlank { "the controlling account" }}, which pays the fees.")
    Text("Asset", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        step.assets.forEach { asset ->
            FilterChip(
                selected = asset.contract == step.asset,
                onClick = { actions.onAssetChange(asset.contract) },
                enabled = !step.quoting,
                // The balance carries its symbol already.
                label = { Text(asset.balance) },
            )
        }
    }
    step.assets.firstOrNull { it.contract == step.asset }?.warning?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = step.to,
        onValueChange = actions.onToChange,
        label = { Text("To (address)") },
        singleLine = true,
        enabled = !step.quoting,
        keyboardOptions = KeyboardOptions().next(),
        keyboardActions = nextTo(amountField),
        modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_TO_TAG),
    )
    OutlinedTextField(
        value = step.amount,
        onValueChange = actions.onAmountChange,
        label = { Text("Amount") },
        singleLine = true,
        enabled = !step.quoting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal).done(),
        keyboardActions = submitOnDone(!step.quoting, actions.onReview),
        modifier = Modifier.fillMaxWidth().focusRequester(amountField).testTag(GNOSIS_PAY_AMOUNT_TAG),
    )
    step.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    // An expired entry blocks the Delay's queue: its skip moves nothing, and lets the transfer through.
    if (step.skip) {
        OutlinedButton(
            onClick = actions.onSkipExpired,
            enabled = !step.quoting,
            modifier = Modifier.fillMaxWidth().testTag(GNOSIS_PAY_SKIP_TAG),
        ) { Text("Skip expired entries") }
    }
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
        passwordTag = GNOSIS_PAY_PASSWORD_TAG,
        signTag = GNOSIS_PAY_SIGN_TAG,
    )
}

/** An address or a hash, in full and in a fixed-width face, as the other screens show one. */
@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

/**
 * A row's line: the account's own [label] as it is named, then [notes]; with no label, the first
 * note starts the sentence, capitalised. A name ("alice.eth") is never changed.
 */
private fun labelled(label: String?, vararg notes: String?): String {
    val rest = notes.filterNotNull()
    return if (label != null) {
        (listOf(label) + rest).joinToString(" · ")
    } else {
        rest.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}
