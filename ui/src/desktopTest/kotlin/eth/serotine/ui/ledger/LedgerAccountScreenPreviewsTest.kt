package eth.serotine.ui.ledger

import eth.serotine.ui.Screens
import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every Ledger enrollment preview. */
class LedgerAccountScreenPreviewsTest {
    @Test
    fun everyPreviewRenders() = renderEach(Screens.ledgerAccount)
}
