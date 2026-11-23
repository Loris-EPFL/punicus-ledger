package eth.serotine.data.history

import kotlin.test.Test
import kotlin.test.assertNull

/** [PinnedAddressIndexImporter]: the key a release pins, none so far. */
class PinnedAddressIndexImporterTest {

    @Test
    fun noImporterKeyIsPinnedInThisRelease() {
        assertNull(PinnedAddressIndexImporter.KEY, "owner's decision, 2026-11-21: none is pinned yet")
    }
}
