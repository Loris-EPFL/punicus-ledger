package eth.serotine.ui.mnemonicimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.PrivateKey
import eth.serotine.ui.components.NetworkChecklist
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.platform.OnStopUnlessConfigurationChange
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of the seed-phrase field, for UI tests. */
const val MNEMONIC_FIELD_TAG = "mnemonic-field"

/** Test tag of the private-key field, for UI tests. */
const val PRIVATE_KEY_FIELD_TAG = "private-key-field"

/** Test tags of the password fields, for UI tests. */
const val MNEMONIC_PASSWORD_TAG = "mnemonic-password"
const val MNEMONIC_REPEAT_TAG = "mnemonic-password-repeat"

/** Test tag of the button that adds the account, for UI tests. */
const val MNEMONIC_IMPORT_BUTTON_TAG = "mnemonic-import-button"

/**
 * Binds [MnemonicImportScreen] to its ViewModel, reports a finished import through
 * [onImported] so the caller can close the screen, and tells the ViewModel when the screen is
 * left, so it drops the words or the key: a stop that is not a configuration change (owner ruling,
 * 2026-10-18, #39; the same for a key, owner ruling, 2026-11-25).
 */
@Composable
fun MnemonicImportRoute(
    viewModel: MnemonicImportViewModel,
    onBack: () -> Unit,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: an edit reaches the field in the same frame it was typed. A collector
    // that resumes a frame later hands the field a stale value, which loses keystrokes and
    // moves the cursor while someone types fast.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    // Left, the words go; a rotation or another configuration change keeps them in the ViewModel's
    // memory, never in a saved state (owner ruling, 2026-10-18, #39).
    OnStopUnlessConfigurationChange(onStop = viewModel::onHidden)
    MnemonicImportScreen(
        state = state,
        onPhraseChange = viewModel::onPhraseChange,
        onKeyChange = viewModel::onKeyChange,
        onSourceChange = viewModel::onSourceChange,
        onPasswordChange = viewModel::onPasswordChange,
        onRepeatChange = viewModel::onRepeatChange,
        onImport = viewModel::onImport,
        onBack = onBack,
        onDone = onImported,
        modifier = modifier,
        onNetworkToggle = viewModel::onNetworkToggle,
        onTurnOnNetwork = viewModel::onTurnOnNetwork,
    )
    // A note to read first keeps the screen until the user is done with it.
    val imported = state as? MnemonicImportUiState.Imported
    if (imported != null && imported.note == null) {
        LaunchedEffect(Unit) { onImported() }
    }
}

/**
 * The import screen (docs/wallet-ux.md, screen 11) as a function of [state]: a seed phrase, or a private key in hex
 * ([onSourceChange] chooses), the wallet password and the networks; [onDone] once an import's note is read. While
 * it is shown the window is kept
 * out of screen capture where the platform allows ([protectFromScreenCapture]); where it
 * does not, the screen says so. The field's text is never saved: it lives in the
 * ViewModel's state, not in `rememberSaveable`, so it never reaches a saved-state bundle. The last
 * thing asked is which networks to add the account to (docs/networks.md, N9), ticked through
 * [onNetworkToggle], one that is off turned on through [onTurnOnNetwork].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicImportScreen(
    state: MnemonicImportUiState,
    onPhraseChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    onNetworkToggle: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
    onKeyChange: (String) -> Unit = {},
    onSourceChange: (ImportSource) -> Unit = {},
) {
    val captureBlocked = protectFromScreenCapture()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Add account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is MnemonicImportUiState.Entering -> EnteringContent(
                state = state,
                captureBlocked = captureBlocked,
                onPhraseChange = onPhraseChange,
                onKeyChange = onKeyChange,
                onSourceChange = onSourceChange,
                onPasswordChange = onPasswordChange,
                onRepeatChange = onRepeatChange,
                onImport = onImport,
                onNetworkToggle = onNetworkToggle,
                onTurnOnNetwork = onTurnOnNetwork,
                modifier = Modifier.padding(padding),
            )
            is MnemonicImportUiState.Imported -> if (state.note == null) {
                Box(Modifier.padding(padding).fillMaxSize()) {
                    Text("${state.label} added", Modifier.align(Alignment.Center))
                }
            } else {
                Column(
                    Modifier.padding(padding).fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("${state.label} added", style = MaterialTheme.typography.titleMedium)
                    Text(state.note, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun EnteringContent(
    state: MnemonicImportUiState.Entering,
    captureBlocked: Boolean,
    onPhraseChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSourceChange: (ImportSource) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onImport: () -> Unit,
    onNetworkToggle: (Long) -> Unit,
    onTurnOnNetwork: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ImportSource.entries.forEachIndexed { index, source ->
                SegmentedButton(
                    selected = state.source == source,
                    onClick = { onSourceChange(source) },
                    enabled = !state.importing,
                    shape = SegmentedButtonDefaults.itemShape(index, ImportSource.entries.size),
                    label = { Text(source.label) },
                )
            }
        }
        when (state.source) {
            ImportSource.PHRASE -> PhraseField(state, onPhraseChange, onImport)
            ImportSource.KEY -> KeyField(state, onKeyChange, onImport)
        }
        // The password fields appear once the key store has said which one is wanted.
        when (val step = state.step) {
            PasswordStep.Checking -> Unit
            PasswordStep.Unavailable -> Text(
                "The key store does not work on this device, so no account can be added here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is PasswordStep.New, is PasswordStep.Existing -> PasswordFields(
                state = state,
                setting = step is PasswordStep.New,
                onPasswordChange = onPasswordChange,
                onRepeatChange = onRepeatChange,
                onImport = onImport,
            )
        }
        // The last thing asked before the import, none ticked (docs/networks.md, N9).
        if (state.step is PasswordStep.New || state.step is PasswordStep.Existing) {
            Text("Add to which networks?", style = MaterialTheme.typography.titleSmall)
            NetworkChecklist(state.networks, enabled = !state.importing, onNetworkToggle, onTurnOnNetwork)
        }
        // Greyed out while no network is ticked, a tap saying so (owner ruling, 2026-10-18, #55).
        var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
        unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
        val waitsForNetwork = state.inputReady && state.networkReason != null
        Button(
            onClick = {
                val reason = state.networkReason
                if (reason == null) onImport() else unavailable = UnavailableNotice("Add account", reason)
            },
            enabled = state.inputReady,
            modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_IMPORT_BUTTON_TAG).unavailable(waitsForNetwork),
            colors = if (waitsForNetwork) Unavailable.buttonColors() else ButtonDefaults.buttonColors(),
        ) {
            if (state.importing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Add account")
            }
        }
        Text(
            when (state.source) {
                ImportSource.PHRASE -> "Punicus adds the first account of these words (m/44'/60'/0'/0/0). "
                ImportSource.KEY -> "Punicus adds the account of this key. A key has no seed phrase: no further " +
                    "account and no per-app address can come from it, and only this key brings the account back. "
            } + state.step.protection.orEmpty() +
                (if (state.source == ImportSource.PHRASE) " It cannot send transactions yet." else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!captureBlocked) {
            Text(
                "Screenshots and screen recordings of this window cannot be blocked here. Make sure " +
                    "nothing is recording your screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The seed-phrase field, shown in clear: the words are checked against what was written down. */
@Composable
private fun PhraseField(state: MnemonicImportUiState.Entering, onPhraseChange: (String) -> Unit, onImport: () -> Unit) {
    Text(
        "Enter the seed phrase of the account to add: its 12 to 24 words, separated by spaces.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.phrase.text,
        onValueChange = onPhraseChange,
        modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_FIELD_TAG),
        enabled = !state.importing,
        label = { Text("Seed phrase") },
        supportingText = { Text(state.error ?: wordCountLabel(state.wordCount)) },
        isError = state.error != null,
        // A password field to the keyboard: no suggestions, no autocorrect, and nothing
        // learned into the keyboard's dictionary — on the keyboards that honour it.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onImport() }),
        minLines = 3,
    )
}

/**
 * The private-key field: 64 hex digits, an optional 0x, masked as a password is, since a key shown on screen is a key
 * anyone looking can take. Its hint counts the digits.
 */
@Composable
private fun KeyField(state: MnemonicImportUiState.Entering, onKeyChange: (String) -> Unit, onImport: () -> Unit) {
    Text(
        "Enter the private key of the account to add: ${PrivateKey.DIGITS} hex digits, with or without 0x in front.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.key.text,
        onValueChange = onKeyChange,
        modifier = Modifier.fillMaxWidth().testTag(PRIVATE_KEY_FIELD_TAG),
        enabled = !state.importing,
        label = { Text("Private key") },
        supportingText = { Text(state.error ?: "${state.keyDigits} of ${PrivateKey.DIGITS} digits") },
        isError = state.error != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onImport() }),
    )
}

@Composable
private fun PasswordFields(
    state: MnemonicImportUiState.Entering,
    setting: Boolean,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedTextField(
        value = state.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_PASSWORD_TAG),
        enabled = !state.importing,
        label = { Text(if (setting) "New wallet password" else "Wallet password") },
        supportingText = (state.passwordHint ?: if (setting) "Protects every key this wallet stores" else null)
            ?.let { hint -> { Text(hint) } },
        isError = state.passwordHint != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = if (setting) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onImport() }),
    )
    if (setting) {
        OutlinedTextField(
            value = state.repeat.text,
            onValueChange = onRepeatChange,
            modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_REPEAT_TAG),
            enabled = !state.importing,
            label = { Text("Repeat the password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onImport() }),
        )
    }
}

private val PasswordStep.protection: String?
    get() = when (this) {
        is PasswordStep.New -> protection
        is PasswordStep.Existing -> protection
        PasswordStep.Checking, PasswordStep.Unavailable -> null
    }

private fun wordCountLabel(count: Int): String = when (count) {
    0 -> "No words yet"
    1 -> "1 word"
    else -> "$count words"
}
