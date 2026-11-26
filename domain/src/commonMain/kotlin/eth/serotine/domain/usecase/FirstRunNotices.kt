package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.FirstRunNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Whether the first start's notice is to be shown in this run (owner's request, 2026-11-26: "when the app is started
 * for the first time have a dialog that mentions this. Dismiss on ok"), decided once, at the start, so that it never
 * comes up in the middle of a session: shown when it was never dismissed and the wallet holds no account, which is a
 * first start. An install that already holds accounts (an update from a release before the notice), or held some
 * (a list found damaged and set aside), is no first start: the notice is recorded as dismissed there, without being
 * shown. A list of accounts that cannot be read shows nothing, and records nothing.
 */
class ObserveFirstRunNotice(
    private val notice: FirstRunNotice,
    private val accounts: AccountRepository,
) {
    operator fun invoke(): Flow<Boolean> = flow {
        val (dismissed, list) = combine(notice.observe(), accounts.observeAccounts()) { d, l -> d to l }.first()
        val accountsHeld = (list as? AccountList.Loaded)?.let { it.accounts.isNotEmpty() || it.damagedListSetAside }
        if (!dismissed && accountsHeld == true) notice.dismiss()
        emit(!dismissed && accountsHeld == false)
    }
}

/** Dismisses the first start's notice for good; false when that could not be saved (it is asked again next start). */
class DismissFirstRunNotice(private val notice: FirstRunNotice) {
    suspend operator fun invoke(): Boolean = notice.dismiss()
}
