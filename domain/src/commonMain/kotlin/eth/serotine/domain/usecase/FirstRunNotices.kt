package eth.serotine.domain.usecase

import eth.serotine.domain.repository.FirstRunNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether the first start's notice is to be shown (owner's request, 2026-11-26: "when the app is started for the
 * first time have a dialog that mentions this. Dismiss on ok"): until the user dismisses it, once.
 */
class ObserveFirstRunNotice(private val notice: FirstRunNotice) {
    operator fun invoke(): Flow<Boolean> = notice.observe().map { dismissed -> !dismissed }.distinctUntilChanged()
}

/** Dismisses the first start's notice for good; false when that could not be saved (it is asked again next start). */
class DismissFirstRunNotice(private val notice: FirstRunNotice) {
    suspend operator fun invoke(): Boolean = notice.dismiss()
}
