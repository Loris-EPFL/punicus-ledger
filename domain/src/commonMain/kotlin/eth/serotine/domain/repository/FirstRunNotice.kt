package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The port for whether the user has dismissed the notice shown at the app's first start (owner's request,
 * 2026-11-26): what to expect while Punicus finds its first peers. :data keeps it in a file, so it is shown once,
 * not at every start. Never throws.
 */
interface FirstRunNotice {
    /**
     * Whether the notice was dismissed, re-emitted on each change. A choice that cannot be read counts as dismissed:
     * the notice is help, never something a start should keep repeating.
     */
    fun observe(): Flow<Boolean>

    /** Records that the user dismissed the notice; false when that could not be saved. */
    suspend fun dismiss(): Boolean

    companion object {
        /** The notice of a wallet that shows none (a test's): dismissed already. */
        val None: FirstRunNotice = object : FirstRunNotice {
            override fun observe(): Flow<Boolean> = flowOf(true)

            override suspend fun dismiss(): Boolean = true
        }
    }
}
