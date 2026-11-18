package eth.serotine.ui.perapp

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Screen D9 (per-app addresses) in Android Studio's preview pane, one preview per state it draws differently. Built
// from display-ready values, never from the ViewModel. PerAppScreenPreviewsTest renders each one in
// perAppScreenPreviews, at the end. The addresses and paths are made up.

private val uniswap = PerAppRow(
    site = "uniswap.org",
    address = "0x9fA6c0b2E14d7A35c8e01B2f6D3a9c47b1e08D52",
    networks = "Ethereum, Gnosis Chain",
    phrase = "Phrase of Main",
    path = "m/44'/60'/1736218'/915403'/66032'",
    chainId = 1,
    hex = "0x9fa6c0b2e14d7a35c8e01b2f6d3a9c47b1e08d52",
)

private val safe = PerAppRow(
    site = "safe.global",
    address = "0x3C17e5d0A8b2F94c61D7e0aB35f2C8d419E6a07b",
    networks = "Ethereum",
    phrase = "Phrase of Savings",
    path = "m/44'/60'/402117'/1288'/2019573'",
    chainId = 1,
    hex = "0x3c17e5d0a8b2f94c61d7e0ab35f2c8d419e6a07b",
)

private val listed = PerAppUiState(loading = false, rows = listOf(uniswap, safe))

@Composable
private fun PerAppPreview(state: PerAppUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        PerAppScreen(
            state,
            onBack = {},
            onOpen = { _, _ -> },
            onCopy = {},
            onRestore = {},
            onCloseRestore = {},
            onHostChange = {},
            onPhraseChange = {},
            onPasswordChange = {},
            onConfirmRestore = {},
            onDismissExplanation = {},
        )
    }
}

@Preview
@Composable
private fun PerAppListedPreview() = PerAppPreview(listed)

@Preview
@Composable
private fun PerAppListedDarkPreview() = PerAppPreview(listed, darkTheme = true)

@Preview
@Composable
private fun PerAppNoneYetPreview() = PerAppPreview(PerAppUiState(loading = false, empty = PerAppViewModel.EMPTY))

@Preview
@Composable
private fun PerAppOfAPhrasePreview() = PerAppPreview(
    PerAppUiState(loading = false, title = "Per-app addresses of Main's phrase", rows = listOf(uniswap)),
)

@Preview
@Composable
private fun PerAppRestoringPreview() = PerAppPreview(
    listed.copy(
        restore = RestoreForm(
            host = "app.cow.fi",
            phrases = listOf("Phrase of Main", "Phrase of Savings"),
            password = WalletPassword("preview only"),
        ),
    ),
)

@Preview
@Composable
private fun PerAppRestoredPreview() = PerAppPreview(
    listed.copy(notice = "The address of uniswap.org is back in your accounts."),
)

@Preview
@Composable
private fun PerAppNoPhrasePreview() = PerAppPreview(
    PerAppUiState(loading = false, empty = PerAppViewModel.EMPTY, restoreBlocked = PerAppViewModel.NO_PHRASE),
)

@Preview
@Composable
private fun PerAppExplainingPreview() = PerAppPreview(
    PerAppUiState(
        loading = false,
        empty = PerAppViewModel.EMPTY,
        restoreBlocked = PerAppViewModel.NO_PHRASE,
        explaining = PerAppViewModel.NO_PHRASE,
    ),
)

@Preview
@Composable
private fun PerAppLoadingPreview() = PerAppPreview(PerAppUiState())

/** Every preview above, which PerAppScreenPreviewsTest renders: a new preview goes in here too. */
internal val perAppScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("listed") { PerAppListedPreview() },
    ScreenPreview("listed-dark") { PerAppListedDarkPreview() },
    ScreenPreview("none-yet") { PerAppNoneYetPreview() },
    ScreenPreview("of-a-phrase") { PerAppOfAPhrasePreview() },
    ScreenPreview("restoring") { PerAppRestoringPreview() },
    ScreenPreview("restored") { PerAppRestoredPreview() },
    ScreenPreview("no-phrase") { PerAppNoPhrasePreview() },
    ScreenPreview("explaining") { PerAppExplainingPreview() },
    ScreenPreview("loading") { PerAppLoadingPreview() },
)
