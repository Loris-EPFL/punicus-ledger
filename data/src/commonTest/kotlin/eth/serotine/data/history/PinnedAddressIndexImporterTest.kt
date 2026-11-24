package eth.serotine.data.history

import kotlin.test.Test
import kotlin.test.assertEquals

/** [PinnedAddressIndexImporter]: the key a release pins. */
class PinnedAddressIndexImporterTest {

    @Test
    fun theReleasePinsTheOwnersImporterKey() {
        assertEquals(
            "0xb11b5f07feb04076c21092a64eb827f418087241",
            PinnedAddressIndexImporter.KEY?.hex,
            "owner's choice, 2026-11-24: the key of the one-year mainnet index",
        )
    }
}
