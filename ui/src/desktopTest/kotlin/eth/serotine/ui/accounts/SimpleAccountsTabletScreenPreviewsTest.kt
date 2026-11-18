package eth.serotine.ui.accounts

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SimpleAccountsTabletScreenPreviews.kt, at the tablet's size. */
class SimpleAccountsTabletScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.simpleAccountsTablet)
}
