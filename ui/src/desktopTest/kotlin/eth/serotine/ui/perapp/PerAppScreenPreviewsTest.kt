package eth.serotine.ui.perapp

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in PerAppScreenPreviews.kt. */
class PerAppScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.perApp)
}
