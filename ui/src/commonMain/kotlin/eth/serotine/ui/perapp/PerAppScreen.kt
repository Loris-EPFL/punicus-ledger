package eth.serotine.ui.perapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.platform.protectFromScreenCapture
import eth.serotine.ui.platform.rememberCopyText

/** Test tag of a per-app address's row, followed by its address, for UI tests. */
const val PER_APP_ROW_TAG = "per-app-row-"

/** Test tag of "Restore a per-app address", for UI tests. */
const val PER_APP_RESTORE_TAG = "per-app-restore"

/** Test tag of the restore form's host field, for UI tests. */
const val PER_APP_HOST_TAG = "per-app-host"

/** Test tag of the restore form's password field, for UI tests. */
const val PER_APP_PASSWORD_TAG = "per-app-password"

/** Test tag of the restore form's Restore button, for UI tests. */
const val PER_APP_CONFIRM_TAG = "per-app-confirm"

/** Binds [PerAppScreen] to its ViewModel; [onOpen] opens an address's account by its chain id and address. */
@Composable
fun PerAppRoute(
    viewModel: PerAppViewModel,
    onBack: () -> Unit,
    onOpen: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Leaving the screen, the app put in the background included, drops a typed password.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onLeave() }
    PerAppScreen(
        state,
        onBack = onBack,
        onOpen = onOpen,
        onCopy = rememberCopyText(),
        onRestore = viewModel::openRestore,
        onCloseRestore = viewModel::closeRestore,
        onHostChange = viewModel::onHostChange,
        onPhraseChange = viewModel::onPhraseChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmRestore = viewModel::restore,
        onDismissExplanation = viewModel::dismissExplanation,
        modifier = modifier,
    )
}

/**
 * Screen D9 (issue #252; docs/walletconnect.md, *Every per-app address is listed*) as a pure function of [state]:
 * every per-app address held here, each with its site, its address and Copy, its networks, its phrase and its full
 * path, a tap opening its account ([onOpen]); and "Restore a per-app address", greyed out with its reason where it
 * cannot be done, whose form takes a host or a site, a phrase where there are several, and the wallet password. The
 * private key is never shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppScreen(
    state: PerAppUiState,
    onBack: () -> Unit,
    onOpen: (Long, String) -> Unit,
    onCopy: (String) -> Unit,
    onRestore: () -> Unit,
    onCloseRestore: () -> Unit,
    onHostChange: (String) -> Unit,
    onPhraseChange: (Int) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissExplanation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(state.title) },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Each site a dApp connects from gets an address of its own, so no other site can tell it is yours. " +
                    "A site is what the dApp states, not verified.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.empty?.let { Text(it, Modifier.padding(16.dp)) }
            state.rows.forEach { row -> PerAppAddressRow(row, onOpen, onCopy) }
            state.notice?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            }
            val form = state.restore
            if (form == null) {
                val greyed = state.restoreBlocked != null
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.padding(16.dp).fillMaxWidth().unavailable(greyed)
                        .testTag(PER_APP_RESTORE_TAG),
                    colors = if (greyed) {
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = Unavailable.contentColor(),
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) { Text("Restore a per-app address") }
            } else {
                RestoreSection(form, onCloseRestore, onHostChange, onPhraseChange, onPasswordChange, onConfirmRestore)
            }
            Text(
                "The private key of a per-app address is never shown. Another wallet finds the address from the " +
                    "same recovery phrase at its path.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.explaining?.let { UnavailableDialog("Restore a per-app address", it, onDismissExplanation) }
}

/** One per-app address, as D9 and an account's Per-app tab list it. */
@Composable
fun PerAppAddressRow(row: PerAppRow, onOpen: (Long, String) -> Unit, onCopy: (String) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onOpen(row.chainId, row.hex) }.testTag(PER_APP_ROW_TAG + row.hex),
        headlineContent = { Text(row.site) },
        supportingContent = {
            Column {
                Text(row.address, style = MaterialTheme.typography.bodySmall)
                Text("${row.networks} · ${row.phrase}", style = MaterialTheme.typography.bodySmall)
                Text(row.path, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            IconButton(onClick = { onCopy(row.address) }) {
                Icon(SerotineIcons.ContentCopy, contentDescription = "Copy address", Modifier.size(18.dp))
            }
        },
    )
}

/** The open form of "Restore a per-app address". The window is kept out of capture while it shows. */
@Composable
private fun RestoreSection(
    form: RestoreForm,
    onClose: () -> Unit,
    onHostChange: (String) -> Unit,
    onPhraseChange: (Int) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Text(
        "Restore a per-app address",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        "Makes a site's address again from your recovery phrase, without waiting for the site to connect: after " +
            "restoring the phrase, say.",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = form.host,
        onValueChange = onHostChange,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().testTag(PER_APP_HOST_TAG),
        enabled = !form.busy,
        label = { Text("Host or site") },
        placeholder = { Text("app.uniswap.org") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
    )
    form.phrases.forEachIndexed { index, name ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !form.busy) { onPhraseChange(index) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = index == form.phrase, onClick = { onPhraseChange(index) }, enabled = !form.busy)
            Text(name)
        }
    }
    val captureBlocked = protectFromScreenCapture()
    OutlinedTextField(
        value = form.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .testTag(PER_APP_PASSWORD_TAG),
        enabled = !form.busy,
        label = { Text("Wallet password") },
        supportingText = { Text("Making the address takes it.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
    )
    if (!captureBlocked) {
        Text(
            "Screenshots and screen recordings of this window cannot be blocked here. Make sure nothing is " +
                "recording your screen.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(Modifier.padding(16.dp)) {
        TextButton(onClick = onClose, enabled = !form.busy) { Text("Cancel") }
        Button(
            onClick = onConfirm,
            enabled = form.canRestore,
            modifier = Modifier.padding(start = 8.dp).testTag(PER_APP_CONFIRM_TAG),
        ) { Text(if (form.busy) "Restoring…" else "Restore") }
    }
}
