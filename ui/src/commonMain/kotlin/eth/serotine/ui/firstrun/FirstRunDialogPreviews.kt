package eth.serotine.ui.firstrun

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The first start's notice in Android Studio's preview pane: it draws one state, in both themes.
// FirstRunDialogPreviewsTest renders each one in firstRunDialogPreviews, at the end.

@Composable
private fun FirstRunPreview(darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        FirstRunDialog(FirstRunNoticeText.DEFAULT, onOk = {})
    }
}

@Preview
@Composable
private fun FirstRunNoticePreview() = FirstRunPreview()

@Preview
@Composable
private fun FirstRunNoticeDarkPreview() = FirstRunPreview(darkTheme = true)

/** Every preview above, which FirstRunDialogPreviewsTest renders: a new preview goes in here too. */
internal val firstRunDialogPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("notice") { FirstRunNoticePreview() },
    ScreenPreview("notice-dark") { FirstRunNoticeDarkPreview() },
)
