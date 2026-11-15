package eth.serotine.ui.gnosispay

import eth.serotine.ui.Screens
import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in GnosisPayScreenPreviews.kt. */
class GnosisPayScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.gnosisPay)
}
