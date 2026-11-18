package eth.serotine.ui

import androidx.compose.runtime.Composable

/**
 * One preview of a screen, named for the screenshot it renders to.
 *
 * [name] is the file name under the screen's folder in `docs/screenshots/<screen>/<name>.png`
 * (lowercase words joined by hyphens; the dark twin of a state ends in `-dark`), so the same
 * state is at the same path in every commit, whatever its preview function is called. It is
 * unique within its screen, which `ScreensTest` checks. [device] is the window it is drawn in: a
 * phone's, unless the preview shows a layout of a wide window. [content] is the preview itself, as
 * `@Preview` draws it in Android Studio and as the previews test renders it headless.
 */
internal class ScreenPreview(
    val name: String,
    val device: PreviewDevice = PreviewDevice.PHONE,
    val content: @Composable () -> Unit,
)

/**
 * The window a [ScreenPreview] is rendered in, in dp (the render's density is 1, so also in pixels).
 * A `@Preview` of a device other than the phone names the same size, so Android Studio draws it as
 * the screenshot shows it.
 */
internal enum class PreviewDevice(val widthDp: Int, val heightDp: Int) {
    /** A phone, upright: what most of the app's users hold, and every screen's default. */
    PHONE(412, 892),

    /**
     * A tablet, held sideways (a Pixel Tablet's 1280 × 800 dp): wide enough for the layouts of a
     * wide window, the tabs in a rail and the Accounts list beside the account it opens.
     */
    TABLET(TABLET_WIDTH_DP, TABLET_HEIGHT_DP),
}

/** [PreviewDevice.TABLET]'s width, for a `@Preview`'s `widthDp`, which takes a constant. */
internal const val TABLET_WIDTH_DP = 1280

/** [PreviewDevice.TABLET]'s height, for a `@Preview`'s `heightDp`. */
internal const val TABLET_HEIGHT_DP = 800
