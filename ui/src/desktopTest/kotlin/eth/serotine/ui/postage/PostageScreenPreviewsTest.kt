package eth.serotine.ui.postage

import eth.serotine.ui.Screens
import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in PostageScreenPreviews.kt. */
class PostageScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.postage)
}
