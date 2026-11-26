package eth.serotine.ui.firstrun

import eth.serotine.ui.Screens
import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in FirstRunDialogPreviews.kt. */
class FirstRunDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.firstRunDialog)
}
