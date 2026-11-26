package eth.serotine.ui.ledger

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

private val form = LedgerAccountUiState(enabledNetworks = listOf(Network.ETHEREUM))
private val address = Eip55.format(Address.of("0x" + "12ab".repeat(10)))
private val confirmed = form.copy(step = LedgerEnrollmentStep.Confirmed(address))

@Composable
private fun LedgerPreview(state: LedgerAccountUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        LedgerAccountScreen(state, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview
@Composable
private fun LedgerEditingPreview() = LedgerPreview(form)

@Preview
@Composable
private fun LedgerInvalidPathPreview() = LedgerPreview(form.copy(path = "m/44'/1'", pathError = LEDGER_PATH_ERROR))

@Preview
@Composable
private fun LedgerConfirmingPreview() = LedgerPreview(form.copy(step = LedgerEnrollmentStep.Confirming))

@Preview
@Composable
private fun LedgerDisconnectedPreview() = LedgerPreview(
    form.copy(message = ledgerProblemText(LedgerProblem.NOT_CONNECTED)),
)

@Preview
@Composable
private fun LedgerRejectedPreview() = LedgerPreview(form.copy(message = ledgerProblemText(LedgerProblem.REJECTED)))

@Preview
@Composable
private fun LedgerConfirmedPreview() = LedgerPreview(confirmed)

@Preview
@Composable
private fun LedgerSepoliaPreview() = LedgerPreview(
    confirmed.copy(network = Network.SEPOLIA, enabledNetworks = ledgerNetworks), darkTheme = true,
)

@Preview
@Composable
private fun LedgerNetworkOffPreview() = LedgerPreview(confirmed.copy(enabledNetworks = emptyList()))

@Preview
@Composable
private fun LedgerEnablingPreview() = LedgerPreview(form.copy(step = LedgerEnrollmentStep.Enabling))

@Preview
@Composable
private fun LedgerSavingPreview() = LedgerPreview(form.copy(step = LedgerEnrollmentStep.Saving(address)))

@Preview
@Composable
private fun LedgerAddedPreview() = LedgerPreview(form.copy(step = LedgerEnrollmentStep.Added))

internal val ledgerAccountScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("editing") { LedgerEditingPreview() },
    ScreenPreview("invalid-path") { LedgerInvalidPathPreview() },
    ScreenPreview("confirming") { LedgerConfirmingPreview() },
    ScreenPreview("disconnected") { LedgerDisconnectedPreview() },
    ScreenPreview("rejected") { LedgerRejectedPreview() },
    ScreenPreview("confirmed") { LedgerConfirmedPreview() },
    ScreenPreview("sepolia-dark") { LedgerSepoliaPreview() },
    ScreenPreview("network-off") { LedgerNetworkOffPreview() },
    ScreenPreview("enabling") { LedgerEnablingPreview() },
    ScreenPreview("saving") { LedgerSavingPreview() },
    ScreenPreview("added") { LedgerAddedPreview() },
)
