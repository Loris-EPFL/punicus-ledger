package eth.serotine.ui.accounts

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in NoAccountOpenScreenPreviews.kt. */
class NoAccountOpenScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.noAccountOpen)
}
