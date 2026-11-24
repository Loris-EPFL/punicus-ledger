package eth.serotine.ui.swarm

import eth.serotine.ui.Screens
import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SwarmScreenPreviews.kt. */
class SwarmScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.swarm)
}
