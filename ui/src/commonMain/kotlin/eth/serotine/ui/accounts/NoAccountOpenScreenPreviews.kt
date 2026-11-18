package eth.serotine.ui.accounts

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The detail pane of a wide window with no account open in it, in Android Studio's preview pane, one
// preview per state it draws differently. NoAccountOpenScreenPreviewsTest renders each one in
// noAccountOpenScreenPreviews, at the end.

@Composable
private fun NoAccountOpenPreview(walletEmpty: Boolean, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) { NoAccountOpenScreen(walletEmpty) }
}

@Preview
@Composable
private fun NoAccountOpenChoosePreview() = NoAccountOpenPreview(walletEmpty = false)

@Preview
@Composable
private fun NoAccountOpenChooseDarkPreview() = NoAccountOpenPreview(walletEmpty = false, darkTheme = true)

/** No account in the wallet yet: the list beside it says how to add one. */
@Preview
@Composable
private fun NoAccountOpenEmptyPreview() = NoAccountOpenPreview(walletEmpty = true)

/** Every preview above, which NoAccountOpenScreenPreviewsTest renders: a new preview goes in here too. */
internal val noAccountOpenScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("choose") { NoAccountOpenChoosePreview() },
    ScreenPreview("choose-dark") { NoAccountOpenChooseDarkPreview() },
    ScreenPreview("empty") { NoAccountOpenEmptyPreview() },
)
