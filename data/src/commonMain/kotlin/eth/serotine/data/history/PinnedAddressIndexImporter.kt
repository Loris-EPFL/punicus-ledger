package eth.serotine.data.history

import eth.serotine.domain.model.Address

/**
 * The importer key of the addr1 index pinned in this release: the one key whose index the list may rest on, named by
 * the experimental setting (owner's decision, 2026-11-21, on #335: pinned in the release, never typed by the user).
 * Which key is pinned is the owner's choice, named in the PR that pins it. **None is pinned yet** (owner's decision,
 * 2026-11-21), so it is null, and the setting is greyed out saying so.
 */
object PinnedAddressIndexImporter {
    /** The key pinned in this release, or null: none is. */
    val KEY: Address? = null
}
