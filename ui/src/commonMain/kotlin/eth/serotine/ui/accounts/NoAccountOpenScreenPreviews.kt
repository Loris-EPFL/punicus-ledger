package eth.serotine.ui.accounts

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The detail pane of a wide window with no account open in it, in Android Studio's preview pane, one
// preview per state it draws differently. NoAccountOpenScreenPreviewsTest renders each one in
// noAccountOpenScreenPreviews, at the end.

@Composable
private fun NoAccountOpenPreview(hint: String?, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) { NoAccountOpenScreen(hint) }
}

@Preview
@Composable
private fun NoAccountOpenChoosePreview() = NoAccountOpenPreview(NO_ACCOUNT_OPEN_HINT)

@Preview
@Composable
private fun NoAccountOpenChooseDarkPreview() = NoAccountOpenPreview(NO_ACCOUNT_OPEN_HINT, darkTheme = true)

/** No account in the wallet yet: the list beside it says how to add one. */
@Preview
@Composable
private fun NoAccountOpenEmptyPreview() = NoAccountOpenPreview(NO_ACCOUNT_YET_HINT)

/** The list being read, or not readable: nothing to choose yet, the title alone. */
@Preview
@Composable
private fun NoAccountOpenNoListPreview() = NoAccountOpenPreview(hint = null)

/** Every preview above, which NoAccountOpenScreenPreviewsTest renders: a new preview goes in here too. */
internal val noAccountOpenScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("choose") { NoAccountOpenChoosePreview() },
    ScreenPreview("choose-dark") { NoAccountOpenChooseDarkPreview() },
    ScreenPreview("empty") { NoAccountOpenEmptyPreview() },
    ScreenPreview("no-list") { NoAccountOpenNoListPreview() },
)
