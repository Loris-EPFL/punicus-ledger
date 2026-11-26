package eth.serotine.ui.firstrun

import eth.serotine.domain.repository.FirstRunNotice
import eth.serotine.domain.usecase.DismissFirstRunNotice
import eth.serotine.domain.usecase.ObserveFirstRunNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private fun TestScope.viewModel(notice: MemoryNotice): FirstRunViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = FirstRunViewModel(ObserveFirstRunNotice(notice), DismissFirstRunNotice(notice))
        backgroundScope.launch { viewModel.shown.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun shownOnAFirstStartAndGoneForGoodOnOk() = runTest {
        val notice = MemoryNotice()
        val viewModel = viewModel(notice)
        assertTrue(viewModel.shown.value)

        viewModel.ok()
        runCurrent()

        assertFalse(viewModel.shown.value)
        assertTrue(notice.dismissed.value, "kept, so the next start shows nothing")
    }

    @Test
    fun notShownOnceDismissed() = runTest {
        assertFalse(viewModel(MemoryNotice(dismissed = true)).shown.value)
    }

    @Test
    fun okHidesItForThisRunEvenWhenTheDismissalCannotBeSaved() = runTest {
        val notice = MemoryNotice().apply { failSaves = true }
        val viewModel = viewModel(notice)

        viewModel.ok()
        runCurrent()

        assertFalse(viewModel.shown.value)
        assertEquals(1, notice.dismissals)
    }
}
