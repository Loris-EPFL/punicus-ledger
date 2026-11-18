package eth.serotine.ui.accounts

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in AccountsTabletScreenPreviews.kt, at the tablet's size. */
class AccountsTabletScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.accountsTablet)
}
