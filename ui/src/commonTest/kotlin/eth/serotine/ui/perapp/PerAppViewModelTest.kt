package eth.serotine.ui.perapp

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.usecase.MakePerAppAddress
import eth.serotine.domain.usecase.ObservePerAppAddresses
import eth.serotine.domain.usecase.RestorePerAppAddress
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakePerAppKeys
import eth.serotine.ui.MemoryAliases
import eth.serotine.ui.MemoryNetworkSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screen D9's ViewModel (issue #252, part 4): the list of per-app addresses and their restore. */
@OptIn(ExperimentalCoroutinesApi::class)
class PerAppViewModelTest {

    private val aliases = MemoryAliases()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun address(last: Char) = Address.of("0x" + "0".repeat(39) + last)

    private fun account(network: Network, last: Char, label: String, perApp: PerAppAccount? = null) =
        Account(network, address(last), AccountKind.EOA, label, AccountCustody.InKeyStore, perApp = perApp)

    private val main = account(Network.ETHEREUM, '1', "Main")
    private val second = account(Network.ETHEREUM, '2', "Second")
    private val uniswap = (AppHost.parse("uniswap.org") as AppHostResult.Host).host
    private val mark = PerAppAccount(uniswap, AppIsolation.pathOf(uniswap), main.address)
    private val perApp = account(Network.ETHEREUM, '6', "uniswap.org", mark)
    private val perAppGnosis = account(Network.GNOSIS, '6', "uniswap.org", mark)

    private val networks = MemoryNetworkSettings(setOf(Network.ETHEREUM, Network.GNOSIS))

    private fun TestScope.main() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.viewModel(
        accounts: FakeAccounts,
        keys: FakePerAppKeys,
        phrase: Address? = null,
        settings: MemoryNetworkSettings = networks,
        restoring: Boolean = false,
    ): PerAppViewModel {
        val viewModel = PerAppViewModel(
            phrase,
            ObservePerAppAddresses(accounts, keys, settings, aliases),
            RestorePerAppAddress(MakePerAppAddress(keys, accounts, WalletChanges(), aliases), settings),
            restoring,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun eachAddressIsListedOnceWithItsSiteNetworksPhraseAndPath() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val state = viewModel(FakeAccounts(main, perApp, perAppGnosis), keys).uiState.value
        assertEquals(PerAppViewModel.TITLE, state.title)
        val row = state.rows.single()
        assertEquals("uniswap.org", row.site)
        assertEquals("Ethereum, Gnosis Chain", row.networks)
        assertEquals("Phrase of Main", row.phrase)
        assertEquals(AppIsolation.pathOf(uniswap).toString(), row.path)
        assertEquals(Network.ETHEREUM.chainId, row.chainId)
        assertEquals(address('6').hex, row.hex)
        assertNull(row.aliases)
        assertNull(state.empty)
        assertNull(state.restoreBlocked)
    }

    @Test
    fun openedFromAPhraseItListsThatPhrasesAlone() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address, second.address to second.address))
        val accounts = FakeAccounts(main, second, perApp)
        val ofSecond = viewModel(accounts, keys, phrase = second.address).uiState.value
        assertEquals("Per-app addresses of Second's phrase", ofSecond.title)
        assertTrue(ofSecond.rows.isEmpty())
        assertEquals(PerAppViewModel.EMPTY, ofSecond.empty)
        val ofMain = viewModel(accounts, keys, phrase = main.address).uiState.value
        assertEquals(listOf("uniswap.org"), ofMain.rows.map { it.site })
    }

    @Test
    fun withoutAPhraseRestoreIsGreyedOutAndSaysWhy() = runTest {
        main()
        val watched = main.copy(custody = AccountCustody.Watched)
        val viewModel = viewModel(FakeAccounts(watched), FakePerAppKeys())
        assertEquals(PerAppViewModel.NO_PHRASE, viewModel.uiState.value.restoreBlocked)
        viewModel.openRestore()
        assertNull(viewModel.uiState.value.restore)
        assertEquals(PerAppViewModel.NO_PHRASE, viewModel.uiState.value.explaining)
        viewModel.dismissExplanation()
        assertNull(viewModel.uiState.value.explaining)
    }

    @Test
    fun withNoNetworkOnRestoreIsGreyedOut() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val viewModel = viewModel(FakeAccounts(main), keys, settings = MemoryNetworkSettings(emptySet()))
        assertEquals(PerAppViewModel.NO_NETWORK, viewModel.uiState.value.restoreBlocked)
    }

    @Test
    fun restoringAddsTheAddressAndDropsThePassword() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address), address = address('6'))
        val accounts = FakeAccounts(main)
        val viewModel = viewModel(accounts, keys)
        viewModel.openRestore()
        val form = assertNotNull(viewModel.uiState.value.restore)
        assertTrue(form.phrases.isEmpty(), "one phrase: nothing to choose")
        assertTrue(!form.canRestore)
        viewModel.onHostChange("app.uniswap.org")
        viewModel.onPasswordChange("correct horse")
        assertTrue(assertNotNull(viewModel.uiState.value.restore).canRestore)
        viewModel.restore()
        val state = viewModel.uiState.value
        assertNull(state.restore, "the form closes once restored")
        assertEquals("The address of uniswap.org is back in your accounts.", state.notice)
        assertEquals(listOf("uniswap.org"), state.rows.map { it.site })
        assertEquals("Ethereum, Gnosis Chain", state.rows.single().networks)
        val host = (AppHost.parse("app.uniswap.org") as AppHostResult.Host).host
        assertEquals(listOf(main.address to host), keys.derived)
    }

    @Test
    fun aWrongPasswordKeepsTheFormOpenWithoutIt() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val viewModel = viewModel(FakeAccounts(main), keys)
        viewModel.openRestore()
        viewModel.onHostChange("uniswap.org")
        viewModel.onPasswordChange("wrong")
        viewModel.restore()
        val form = assertNotNull(viewModel.uiState.value.restore)
        assertEquals("uniswap.org", form.host)
        assertEquals("", form.password.text)
        assertEquals("That is not the wallet password. Nothing changed.", viewModel.uiState.value.notice)
    }

    @Test
    fun aHostThatNamesNoSiteIsSaidAndNothingIsDerived() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val viewModel = viewModel(FakeAccounts(main), keys)
        viewModel.openRestore()
        viewModel.onHostChange("not a host")
        viewModel.onPasswordChange("correct horse")
        viewModel.restore()
        assertEquals(
            "That is not a DNS name, so there is no site to make an address for.",
            viewModel.uiState.value.notice,
        )
        assertTrue(keys.derived.isEmpty())
    }

    @Test
    fun withSeveralPhrasesTheFormOffersThemAndUsesTheOneChosen() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address, second.address to second.address))
        val viewModel = viewModel(FakeAccounts(main, second), keys)
        viewModel.openRestore()
        val phrases = assertNotNull(viewModel.uiState.value.restore).phrases
        assertEquals(listOf("Phrase of Main", "Phrase of Second"), phrases)
        viewModel.onPhraseChange(1)
        viewModel.onHostChange("uniswap.org")
        viewModel.onPasswordChange("correct horse")
        viewModel.restore()
        assertEquals(second.address, keys.derived.single().first)
    }

    @Test
    fun leavingTheScreenDropsThePasswordTypedAndNothingElse() = runTest {
        main()
        val viewModel = viewModel(FakeAccounts(main), FakePerAppKeys(phrases = mapOf(main.address to main.address)))
        viewModel.openRestore()
        viewModel.onHostChange("uniswap.org")
        viewModel.onPasswordChange("wrong")
        viewModel.restore()
        viewModel.onLeave()
        val state = viewModel.uiState.value
        assertEquals("uniswap.org", assertNotNull(state.restore).host)
        assertEquals("That is not the wallet password. Nothing changed.", state.notice, "what was said stays")
        viewModel.onPasswordChange("correct horse")
        viewModel.onLeave()
        assertEquals("", assertNotNull(viewModel.uiState.value.restore).password.text)
    }

    @Test
    fun anUnreadableListIsSaidNeverShownAsNone() = runTest {
        main()
        val accounts = FakeAccounts(main)
        accounts.unreadable.value = true
        val state = viewModel(accounts, FakePerAppKeys(phrases = mapOf(main.address to main.address))).uiState.value
        assertEquals(PerAppViewModel.UNREADABLE, state.empty)
        assertEquals(PerAppViewModel.UNREADABLE, state.restoreBlocked)
    }

    @Test
    fun aPhraseIsNamedByTheAccountItIsKnownBy() = runTest {
        main()
        // A further account listed before the phrase's first: the first, which carries the tab, names it.
        val further = account(Network.ETHEREUM, '2', "Savings")
        val keys = FakePerAppKeys(phrases = mapOf(further.address to main.address, main.address to main.address))
        val state = viewModel(FakeAccounts(further, main, perApp), keys, phrase = main.address).uiState.value
        assertEquals("Per-app addresses of Main's phrase", state.title)
        assertEquals("Phrase of Main", state.rows.single().phrase)
    }

    @Test
    fun theFormAskedForWhereRestoreCannotBeDoneSaysWhyAtOnce() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val viewModel = viewModel(
            FakeAccounts(main),
            keys,
            phrase = main.address,
            settings = MemoryNetworkSettings(emptySet()),
            restoring = true,
        )
        assertNull(viewModel.uiState.value.restore)
        assertEquals(PerAppViewModel.NO_NETWORK, viewModel.uiState.value.explaining)
        viewModel.dismissExplanation()
        assertNull(viewModel.uiState.value.explaining)
    }

    @Test
    fun openedFromAPerAppTabTheFormIsOpenAtOnce() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val viewModel = viewModel(FakeAccounts(main), keys, phrase = main.address, restoring = true)
        assertNotNull(viewModel.uiState.value.restore)
    }

    @Test
    fun aPhraseThatLeftIsSaidNeverSwappedForAnother() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address, second.address to second.address))
        val accounts = FakeAccounts(main, second)
        val viewModel = viewModel(accounts, keys)
        viewModel.openRestore()
        viewModel.onPhraseChange(1)
        assertEquals(1, assertNotNull(viewModel.uiState.value.restore).phrase)
        viewModel.onHostChange("uniswap.org")
        viewModel.onPasswordChange("correct horse")
        accounts.accounts.value = listOf(main)
        viewModel.restore()
        assertEquals(PerAppViewModel.CHOSEN_GONE, viewModel.uiState.value.notice)
        assertTrue(keys.derived.isEmpty())
    }

    @Test
    fun aScreenForAPhraseThatLeftSaysSo() = runTest {
        main()
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val state = viewModel(FakeAccounts(main), keys, phrase = second.address).uiState.value
        assertEquals(PerAppViewModel.OF_A_PHRASE, state.title)
        assertEquals(PerAppViewModel.PHRASE_GONE, state.restoreBlocked)
    }

    @Test
    fun aRowNamesTheSitesLinkedToIt() = runTest {
        main()
        aliases.linked.value = mapOf((AppHost.parse("uniswap.app") as AppHostResult.Host).host to address('6'))
        val keys = FakePerAppKeys(phrases = mapOf(main.address to main.address))
        val state = viewModel(FakeAccounts(main, perApp), keys).uiState.value
        assertEquals("Also seen by uniswap.app", state.rows.single().aliases)
    }
}
