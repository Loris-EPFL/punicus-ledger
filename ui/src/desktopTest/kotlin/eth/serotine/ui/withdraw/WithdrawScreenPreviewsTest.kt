package eth.serotine.ui.withdraw

import eth.serotine.ui.Screens
import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in WithdrawScreenPreviews.kt. */
class WithdrawScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.withdraw)
}
