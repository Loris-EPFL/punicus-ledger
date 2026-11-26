package eth.serotine.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.NetworkChips
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableFix
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedgerAccountScreen(
    state: LedgerAccountUiState,
    onPath: (String) -> Unit,
    onNetwork: (Long) -> Unit,
    onTurnOn: (Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { UnavailableDialog(it, onDismiss = { unavailable = null }) }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Add Ledger account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                when (state.step) {
                    LedgerEnrollmentStep.Editing -> {
                        val title = "Confirm address on Ledger"
                        val reason = state.pathError
                        LedgerAction(title, reason != null) {
                            if (reason == null) onConfirm() else unavailable = UnavailableNotice(title, reason)
                        }
                    }
                    LedgerEnrollmentStep.Confirming -> {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    }
                    is LedgerEnrollmentStep.Confirmed -> {
                        val title = "Add account"
                        val reason = state.addReason
                        LedgerAction(title, reason != null) {
                            if (reason == null) {
                                onAdd()
                            } else {
                                val fixes = if (state.enabledNetworks != null) {
                                    listOf(UnavailableFix("Turn on ${state.network.displayName}") {
                                        onTurnOn(state.network.chainId)
                                    })
                                } else {
                                    emptyList()
                                }
                                unavailable = UnavailableNotice(title, reason, fixes)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect one Ledger by USB, unlock it, and open the Ethereum app.")
            NetworkChips(state.choices, action = "Add account on", onChoose = onNetwork, onTurnOn = onTurnOn)
            if (state.network.testnet) {
                Text("Sepolia is a test network. Use test ETH only.", color = MaterialTheme.colorScheme.tertiary)
            }
            OutlinedTextField(
                value = state.path,
                onValueChange = onPath,
                modifier = Modifier.fillMaxWidth(),
                readOnly = state.busy,
                singleLine = true,
                label = { Text("Derivation path") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                isError = state.pathError != null,
                supportingText = state.pathError?.let { error -> { Text(error) } },
            )
            when (val step = state.step) {
                LedgerEnrollmentStep.Editing -> Unit
                LedgerEnrollmentStep.Confirming -> Progress("Review and approve the address on your Ledger.")
                LedgerEnrollmentStep.Enabling -> Progress("Turning on network…")
                is LedgerEnrollmentStep.Confirmed -> ConfirmedAddress(step.address)
                is LedgerEnrollmentStep.Saving -> {
                    ConfirmedAddress(step.address)
                    Progress("Saving account…")
                }
                LedgerEnrollmentStep.Added -> Text("Account added.")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Progress(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(message)
    }
}

@Composable
private fun ConfirmedAddress(address: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compare this full address with the one shown on your Ledger before adding it.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Blockie(address)
            SelectionContainer { Text(address, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun LedgerAction(title: String, blocked: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().unavailable(blocked),
        colors = if (blocked) Unavailable.buttonColors() else ButtonDefaults.buttonColors(),
    ) { Text(title) }
}
