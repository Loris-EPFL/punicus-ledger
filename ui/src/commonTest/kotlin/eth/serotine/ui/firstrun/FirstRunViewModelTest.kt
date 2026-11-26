package eth.serotine.ui.firstrun

import eth.serotine.ui.FakeAccounts
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Account
import eth.serotine.domain.repository.FirstRunNotice
import eth.serotine.domain.usecase.DismissFirstRunNotice
import eth.serotine.domain.usecase.ObserveFirstRunNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FirstRunViewModel]: the first start's notice, once, until OK (owner's request, 2026-11-26). */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {

    private class MemoryNotice(dismissed: Boolean = false) : FirstRunNotice {
        val dismissed = MutableStateFlow(dismissed)
        var failSaves = false
        var dismissals = 0

        override fun observe(): Flow<Boolean> = dismissed

        override suspend fun dismiss(): Boolean {
            dismissals++
            if (failSaves) return false
            dismissed.value = true
            return true
        }
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val alice = Account(
        Network.ETHEREUM,
        Address.of("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
        AccountKind.EOA,
        "Alice",
        AccountCustody.Watched,
    )

    private fun TestScope.viewModel(notice: MemoryNotice, accounts: FakeAccounts = FakeAccounts()): FirstRunViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = FirstRunViewModel(ObserveFirstRunNotice(notice, accounts), DismissFirstRunNotice(notice))
        runCurrent()
        return viewModel
    }

    private val FirstRunViewModel.shown: Boolean
        get() = uiState.value is FirstRunUiState.Shown

    @Test
    fun shownOnAFirstStartAndGoneForGoodOnOk() = runTest {
        val notice = MemoryNotice()
        val viewModel = viewModel(notice)
        assertTrue(viewModel.shown)

        viewModel.ok()
        runCurrent()

        assertFalse(viewModel.shown)
        assertTrue(notice.dismissed.value, "kept, so the next start shows nothing")
    }

    @Test
    fun notShownOnceDismissed() = runTest {
        assertFalse(viewModel(MemoryNotice(dismissed = true)).shown)
    }

    @Test
    fun anInstallThatHoldsAccountsIsNoFirstStartAndRecordsItQuietly() = runTest {
        val notice = MemoryNotice()

        val viewModel = viewModel(notice, FakeAccounts(alice))

        assertFalse(viewModel.shown, "an update from a release before the notice")
        assertTrue(notice.dismissed.value, "recorded, so it is never shown on this install")
    }

    @Test
    fun decidedOnceAtTheStartNeverInTheMiddleOfASession() = runTest {
        val notice = MemoryNotice(dismissed = true)
        val viewModel = viewModel(notice)

        notice.dismissed.value = false
        runCurrent()

        assertFalse(viewModel.shown)
    }

    @Test
    fun okHidesItForThisRunEvenWhenTheDismissalCannotBeSaved() = runTest {
        val notice = MemoryNotice().apply { failSaves = true }
        val viewModel = viewModel(notice)

        viewModel.ok()
        runCurrent()

        assertFalse(viewModel.shown)
        assertEquals(1, notice.dismissals)
    }
}
