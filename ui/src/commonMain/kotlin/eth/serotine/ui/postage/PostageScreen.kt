package eth.serotine.ui.postage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.submitOnDone
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the days field, for UI tests. */
const val POSTAGE_DAYS_TAG = "postage-days"

/** Test tag of the immutable switch, for UI tests. */
const val POSTAGE_IMMUTABLE_TAG = "postage-immutable"

/** Test tag of a depth offered, suffixed with the depth, for UI tests. */
const val POSTAGE_DEPTH_TAG = "postage-depth"

/** Test tag of a payer offered, suffixed with its address, for UI tests. */
const val POSTAGE_PAYER_TAG = "postage-payer"

/** Test tag of Review, for UI tests. */
const val POSTAGE_REVIEW_TAG = "postage-review"

/** Test tag of a review's warning, for UI tests. */
const val POSTAGE_WARNING_TAG = "postage-warning"

/** Test tag of the status of a transaction on its way, for UI tests. */
const val POSTAGE_PENDING_TAG = "postage-pending"

/** Test tag of the password field, for UI tests. */
const val POSTAGE_PASSWORD_TAG = "postage-password"

/** Test tag of the button that signs, for UI tests. */
const val POSTAGE_SIGN_TAG = "postage-sign"

/**
 * Binds [PostageScreen] to its ViewModel: a node that leaves the wallet ends the screen through
 * [onGone]; the batch call sent hands its hash to [onSent], whose status takes this screen's place;
 * a transaction on its way opens its status over it through [onOpen]. A screen no longer shown drops
 * the password typed and has its quote made again when shown. [lightClient] is Gnosis Chain's: the
 * strip in Detailed view, the sync line in simple mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PostageRoute(
    viewModel: PostageViewModel,
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onSent: (hash: String) -> Unit,
    onOpen: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val item by lightClient.collectAsStateWithLifecycle()
    val sent by viewModel.sent.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    val done by rememberUpdatedState(onSent)
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(state is PostageUiState.Gone) {
        if (state is PostageUiState.Gone) gone()
    }
    LaunchedEffect(sent) {
        sent?.let {
            viewModel.onSentShown()
            done(it)
        }
    }
    LaunchedEffect(opened) {
        opened?.let {
            viewModel.onOpenedShown()
            open(it)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    // The system's back steps as the top bar's does: from a review or the wait to the form.
    val stepped = (state as? PostageUiState.Ready)?.step.let { it != null && it !is PostageStep.Compose }
    BackHandler(enabled = stepped) { if (!viewModel.back()) onBack() }
    DisposableEffect(viewModel) { onDispose { viewModel.onHidden() } }
    PostageScreen(
        state = state,
        onBack = { if (!viewModel.back()) onBack() },
        actions = PostageActions(
            onPayer = viewModel::onPayer,
            onDepth = viewModel::onDepth,
            onDays = viewModel::onDays,
            onImmutable = viewModel::onImmutable,
            onReview = viewModel::review,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onOpenPending = viewModel::openPending,
        ),
        modifier = modifier,
        lightClient = item.takeIf { LocalInterfaceMode.current == InterfaceMode.DETAILED },
        syncLine = item.takeIf { LocalInterfaceMode.current == InterfaceMode.SIMPLE },
    )
}

/** What the user can do on [PostageScreen]: one callback per intent. */
data class PostageActions(
    val onPayer: (String) -> Unit = {},
    val onDepth: (Int) -> Unit = {},
    val onDays: (String) -> Unit = {},
    val onImmutable: (Boolean) -> Unit = {},
    val onReview: () -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSign: () -> Unit = {},
    val onSignWithBiometrics: () -> Unit = {},
    val onOpenPending: (String) -> Unit = {},
)

/**
 * A postage batch bought, topped up or diluted for a Swarm node (docs/swarm.md, *The four actions*),
 * as a pure function of [state]: the form, the review of each transaction with the unlock that signs
 * it, and the wait for an approval between the two. Under Gnosis Chain's light client strip
 * ([lightClient], Detailed view) or its sync line ([syncLine], simple mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostageScreen(
    state: PostageUiState,
    onBack: () -> Unit,
    actions: PostageActions,
    modifier: Modifier = Modifier,
    lightClient: LightClientItem? = null,
    syncLine: LightClientItem? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text((state as? PostageUiState.Ready)?.title ?: "Postage batch") },
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
                    PostageUiState.Loading, PostageUiState.Gone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Checking on chain…")
                    }
                    is PostageUiState.Unavailable -> Text(state.message)
                    is PostageUiState.Ready -> {
                        Text(state.node, style = MaterialTheme.typography.titleMedium)
                        if (state.batch.isNotEmpty()) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    state.batch.forEach { Text(it) }
                                }
                            }
                        }
                        when (val step = state.step) {
                            is PostageStep.Compose -> Compose(step, actions)
                            is PostageStep.Review -> Review(step, actions)
                            is PostageStep.Awaiting -> Awaiting(step, actions)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Compose(step: PostageStep.Compose, actions: PostageActions) {
    Text(step.explains)
    if (step.payers.isNotEmpty()) {
        Text("Paid by", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            step.payers.forEach { payer ->
                FilterChip(
                    selected = payer.address == step.payer,
                    onClick = { actions.onPayer(payer.address) },
                    enabled = !step.quoting,
                    label = { Text("${payer.label} · ${shortAddress(payer.address)}") },
                    modifier = Modifier.testTag("$POSTAGE_PAYER_TAG-${payer.address}"),
                )
            }
        }
    }
    step.signer?.let { Text(it) }
    if (step.depths.isNotEmpty()) {
        Text("Depth", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            step.depths.forEach { depth ->
                FilterChip(
                    selected = depth.depth == step.depth,
                    onClick = { actions.onDepth(depth.depth) },
                    enabled = !step.quoting,
                    label = { Text(depth.depth.toString()) },
                    modifier = Modifier.testTag("$POSTAGE_DEPTH_TAG-${depth.depth}"),
                )
            }
        }
        step.depths.firstOrNull { it.depth == step.depth }?.let {
            Text(it.label, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (step.days != null) {
        OutlinedTextField(
            value = step.days,
            onValueChange = actions.onDays,
            label = { Text(step.daysLabel.orEmpty()) },
            supportingText = { Text("At the price verified when you review") },
            singleLine = true,
            enabled = !step.quoting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number).done(),
            keyboardActions = submitOnDone(!step.quoting, actions.onReview),
            modifier = Modifier.fillMaxWidth().testTag(POSTAGE_DAYS_TAG),
        )
    }
    if (step.immutable != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Immutable")
                Text(
                    "Once full, an immutable batch takes no more chunks; a mutable one overwrites its oldest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = step.immutable,
                onCheckedChange = actions.onImmutable,
                enabled = !step.quoting,
                modifier = Modifier.testTag(POSTAGE_IMMUTABLE_TAG),
            )
        }
    }
    step.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    step.pending?.let { hash ->
        OutlinedButton(
            onClick = { actions.onOpenPending(hash) },
            modifier = Modifier.fillMaxWidth().testTag(POSTAGE_PENDING_TAG),
        ) { Text("Open the transaction on its way") }
    }
    Button(
        onClick = actions.onReview,
        enabled = !step.quoting,
        modifier = Modifier.fillMaxWidth().testTag(POSTAGE_REVIEW_TAG),
    ) { Text(if (step.quoting) "Checking on chain…" else "Review") }
}

@Composable
private fun Review(step: PostageStep.Review, actions: PostageActions) {
    step.position?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    Text(step.title, style = MaterialTheme.typography.titleLarge)
    step.warnings.forEach {
        Card(Modifier.fillMaxWidth().testTag(POSTAGE_WARNING_TAG)) {
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
        passwordTag = POSTAGE_PASSWORD_TAG,
        signTag = POSTAGE_SIGN_TAG,
    )
}

@Composable
private fun Awaiting(step: PostageStep.Awaiting, actions: PostageActions) {
    Text(PostageTexts.position(1), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.padding(4.dp))
        Text(step.message, Modifier.weight(1f))
    }
    OutlinedButton(
        onClick = { actions.onOpenPending(step.hash) },
        modifier = Modifier.fillMaxWidth().testTag(POSTAGE_PENDING_TAG),
    ) { Text("Open the approval") }
}

/** An address or a hash, in full and in a fixed-width face. */
@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
