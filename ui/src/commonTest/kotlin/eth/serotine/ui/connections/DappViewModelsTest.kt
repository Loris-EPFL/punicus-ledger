package eth.serotine.ui.connections

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSession
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.usecase.ApproveDappProposal
import eth.serotine.domain.usecase.DisconnectDapp
import eth.serotine.domain.usecase.ExtendDappSession
import eth.serotine.domain.usecase.MakePerAppAddress
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.ObservePerAppChoice
import eth.serotine.domain.usecase.PairDapp
import eth.serotine.domain.usecase.PerAppMaking
import eth.serotine.domain.usecase.RejectDappProposal
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeDappRequests
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeDappSessions
import eth.serotine.ui.FakePerAppKeys
import eth.serotine.ui.MemoryDappSites
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.OffDappRequests
import eth.serotine.ui.OffDappSessions
import eth.serotine.ui.dappproposal.DappProposalViewModel
import eth.serotine.ui.pairdapp.PairDappViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ViewModels of Connected dApps (D1), Connect a dApp (D2) and a connection request (D3). */
@OptIn(ExperimentalCoroutinesApi::class)
class DappViewModelsTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun account(network: Network, last: Char, custody: AccountCustody = AccountCustody.InKeyStore) =
        Account(network, Address.of("0x" + "0".repeat(39) + last), AccountKind.EOA, "Account $last", custody)

    private val held = account(Network.SEPOLIA, '1')
    private val watched = account(Network.SEPOLIA, '2', AccountCustody.Watched)
    private val accounts = FakeAccounts(held, watched)
    private val networks = MemoryNetworkSettings(setOf(Network.SEPOLIA))
    private val sessions = FakeDappSessions()
    private val requests = FakeDappRequests()
    private val dapp = DappMetadata("Test dApp", "https://dapp.test", "A dApp")
    private val proposalId = DappProposalId("a".repeat(64), 7)
    private val keys = FakePerAppKeys()
    private val sites = MemoryDappSites()

    /**
     * A proposal from a dApp that states no web address, so that no per-app address can be made for it: these tests
     * choose among the accounts alone. The per-app tests below state one.
     */
    private fun proposal(refusal: DappRefusal? = null) = DappProposal(
        proposalId,
        dapp.copy(url = "ipfs://dapp"),
        emptySet(),
        setOf("eip155:11155111"),
        setOf("personal_sign"),
        refusal,
        1_700_000_300,
    )

    private fun key(account: Account) = DappProposalViewModel.accountKey(account.network.chainId, account.address.hex)

    private fun TestScope.observe() = ObserveDappConnections(sessions, accounts, networks)

    private fun TestScope.main() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun connectionsListProposalsAndSessionsWithTheirEnd() = runTest {
        main()
        sessions.connections.value = DappConnections.On(
            DappRelayLink.CONNECTED,
            listOf(proposal()),
            listOf(DappSession(DappSessionId("t1"), dapp, listOf(held.id), listOf("personal_sign"), 1_700_000_000 + 6 * 86_400 + 10, true)),
        )
        requests.waiting.value = listOf(
            DappRequest(
                DappRequestId(DappSessionId("t1"), 9),
                dapp,
                listOf(held.id),
                "eip155:11155111",
                DappAsk.PersonalSign(held.address, PersonalMessage.of("Hello".encodeToByteArray())!!),
                1_700_000_300,
            ),
        )
        val vm = ConnectionsViewModel(
            observe(),
            ObserveDappMessages(requests, accounts, networks, FakeTokenCatalog(), clock = { 1_700_000_000 }),
            DisconnectDapp(sessions),
            ExtendDappSession(sessions),
            clock = { 1_700_000_000 },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val state = vm.uiState.value
        assertEquals("Connected to WalletConnect's relay", state.link)
        assertEquals("${proposalId.pairing}/7", state.proposals.single().key)
        assertEquals(RequestRow("t1/9", "Test dApp", "https://dapp.test", "Asks you to sign a message · Review"), state.requests.single())
        assertEquals("On Sepolia, a test network · Sees 1 account · Ends in 6 days", state.sessions.single().line)
        assertEquals("On Ethereum and Gnosis Chain, real funds", ConnectionsViewModel.networksLine(setOf(Network.GNOSIS, Network.ETHEREUM)))
        vm.disconnect("t1")
        runCurrent()
        assertEquals(listOf(DappSessionId("t1")), sessions.disconnected)
        sessions.saves = false
        vm.extend("t1")
        runCurrent()
        assertEquals(ConnectionsViewModel.NOT_SAVED, vm.uiState.value.notice)
    }

    @Test
    fun connectionsSayWhenTheyAreOff() = runTest {
        main()
        val vm = ConnectionsViewModel(
            ObserveDappConnections(OffDappSessions, accounts, networks),
            ObserveDappMessages(OffDappRequests, accounts, networks, FakeTokenCatalog()),
            DisconnectDapp(OffDappSessions),
            ExtendDappSession(OffDappSessions),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertTrue(vm.uiState.value.off)
    }

    @Test
    fun connectWithoutALinkSaysWhatIsMissingAndAPairingEndsTheScreen() = runTest {
        main()
        val vm = PairDappViewModel(PairDapp(sessions))
        vm.connect()
        assertEquals(PairDappViewModel.PASTE_FIRST, vm.uiState.value.message)
        assertTrue(sessions.paired.isEmpty())
        sessions.pairing = DappPairing.Expired
        vm.edit("wc:old")
        vm.connect()
        runCurrent()
        assertEquals(PairDappViewModel.message(DappPairing.Expired), vm.uiState.value.message)
        assertFalse(vm.uiState.value.paired)
        sessions.pairing = DappPairing.Paired(waitingForRelay = false)
        vm.connect()
        runCurrent()
        assertTrue(vm.uiState.value.paired)
    }

    @Test
    fun aRequestOffersSepoliaAccountsWithAKeyAndExplainsTheOthers() = runTest {
        main()
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(proposal()), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(accounts, keys, sites, networks),
            MakePerAppAddress(keys, accounts, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val state = vm.uiState.value
        assertEquals("Test dApp", state.name)
        assertNull(state.accounts.first { it.key == key(held) }.unavailable)
        assertNotNull(state.accounts.first { it.key == key(watched) }.unavailable)
        assertTrue(state.approveUnavailable)

        // A greyed-out account says why, and is not chosen.
        vm.toggle(key(watched))
        assertEquals("Not offered to this dApp", vm.uiState.value.explaining?.title)
        vm.dismissExplanation()
        // Approve with nothing chosen says what is missing.
        vm.approve()
        assertEquals(DappProposalViewModel.CHOOSE_ACCOUNT, vm.uiState.value.explaining?.reason)
        vm.dismissExplanation()

        vm.toggle(key(held))
        assertFalse(vm.uiState.value.approveUnavailable)
        vm.approve()
        runCurrent()
        assertEquals(listOf(proposalId to listOf(held.id)), sessions.approved)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aProposalRequiringBothKindsListsThemWithoutSayingAChoiceDecides() = runTest {
        main()
        val wallet = FakeAccounts(held, account(Network.ETHEREUM, '1'))
        val on = MemoryNetworkSettings(setOf(Network.ETHEREUM, Network.SEPOLIA))
        val mixed = proposal(DappRefusal.MIXED_FUNDS).copy(requiredChains = setOf("eip155:1", "eip155:11155111"))
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(mixed), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            ObserveDappConnections(sessions, wallet, on),
            ApproveDappProposal(sessions, wallet, on, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(wallet, keys, sites, on),
            MakePerAppAddress(keys, wallet, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(listOf("Ethereum, real funds", "Sepolia, a test network"), vm.uiState.value.networks)
        assertNull(vm.uiState.value.networksNote, "it can only be rejected: no choice decides anything")
        assertEquals(DappProposalViewModel.refusalLine(DappRefusal.MIXED_FUNDS), vm.uiState.value.refusal)
    }

    @Test
    fun theFirstAccountChosenDecidesBetweenRealFundsAndTestnet() = runTest {
        main()
        val ethereum = account(Network.ETHEREUM, '1')
        val gnosis = account(Network.GNOSIS, '3')
        val wallet = FakeAccounts(held, ethereum, gnosis)
        val on = MemoryNetworkSettings(setOf(Network.ETHEREUM, Network.GNOSIS, Network.SEPOLIA))
        val everywhere = proposal().copy(optionalChains = setOf("eip155:1", "eip155:100", "eip155:11155111"))
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(everywhere), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            ObserveDappConnections(sessions, wallet, on),
            ApproveDappProposal(sessions, wallet, on, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(wallet, keys, sites, on),
            MakePerAppAddress(keys, wallet, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        // Nothing chosen: either kind, and the screen says the first account decides.
        assertEquals(
            listOf("Ethereum, real funds", "Gnosis Chain, real funds", "Sepolia, a test network"),
            vm.uiState.value.networks,
        )
        assertEquals(DappProposalViewModel.BOTH_KINDS, vm.uiState.value.networksNote)
        assertEquals("Gnosis Chain", vm.uiState.value.accounts.first { it.key == key(gnosis) }.network)

        // Sepolia chosen: the accounts holding real funds are greyed out, a tap saying why.
        vm.toggle(key(held))
        assertEquals(listOf("Sepolia, a test network"), vm.uiState.value.networks)
        assertNull(vm.uiState.value.networksNote)
        assertNotNull(vm.uiState.value.accounts.first { it.key == key(ethereum) }.unavailable)
        vm.toggle(key(ethereum))
        assertEquals(
            DappProposalViewModel.offerLine(eth.serotine.domain.usecase.DappOffer.OTHER_FUNDS, Network.ETHEREUM),
            vm.uiState.value.explaining?.reason,
        )
        vm.dismissExplanation()

        // Unchosen, then both real-funds accounts: one connection holding Ethereum and Gnosis Chain.
        vm.toggle(key(held))
        vm.toggle(key(ethereum))
        vm.toggle(key(gnosis))
        assertEquals(listOf("Ethereum, real funds", "Gnosis Chain, real funds"), vm.uiState.value.networks)
        assertNotNull(vm.uiState.value.accounts.first { it.key == key(held) }.unavailable)
        vm.approve()
        runCurrent()
        assertEquals(listOf(proposalId to listOf(ethereum.id, gnosis.id)), sessions.approved)
    }

    @Test
    fun approveWaitsForAnAccountOnEachNetworkTheDappRequires() = runTest {
        main()
        val ethereum = account(Network.ETHEREUM, '1')
        val gnosis = account(Network.GNOSIS, '3')
        val wallet = FakeAccounts(ethereum, gnosis)
        val on = MemoryNetworkSettings(setOf(Network.ETHEREUM, Network.GNOSIS))
        val requiring = proposal().copy(requiredChains = setOf("eip155:1", "eip155:100"), optionalChains = emptySet())
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(requiring), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            ObserveDappConnections(sessions, wallet, on),
            ApproveDappProposal(sessions, wallet, on, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(wallet, keys, sites, on),
            MakePerAppAddress(keys, wallet, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.toggle(key(ethereum))
        assertTrue(vm.uiState.value.approveUnavailable, "greyed out until Gnosis Chain has an account")
        assertEquals(DappProposalViewModel.missingLine(setOf(Network.GNOSIS)), vm.uiState.value.missingRequired)
        vm.approve()
        assertEquals(vm.uiState.value.missingRequired, vm.uiState.value.explaining?.reason, "still tappable: it says why")
        assertTrue(sessions.approved.isEmpty())
        vm.dismissExplanation()
        vm.toggle(key(gnosis))
        assertNull(vm.uiState.value.missingRequired)
        vm.approve()
        runCurrent()
        assertEquals(listOf(proposalId to listOf(ethereum.id, gnosis.id)), sessions.approved)
    }

    @Test
    fun aChosenAccountThatIsNoLongerOfferedGreysOutNoOtherAndCanBeUnchosen() = runTest {
        main()
        val ethereum = account(Network.ETHEREUM, '1')
        val wallet = FakeAccounts(held, ethereum)
        val on = MemoryNetworkSettings(setOf(Network.ETHEREUM, Network.SEPOLIA))
        val either = proposal().copy(optionalChains = setOf("eip155:1", "eip155:11155111"))
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(either), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            ObserveDappConnections(sessions, wallet, on),
            ApproveDappProposal(sessions, wallet, on, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(wallet, keys, sites, on),
            MakePerAppAddress(keys, wallet, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.toggle(key(held))
        assertNotNull(vm.uiState.value.accounts.first { it.key == key(ethereum) }.unavailable)
        // Sepolia turned off: the Sepolia account no longer counts, so Ethereum's is offered again.
        on.setEnabled(Network.SEPOLIA, false)
        runCurrent()
        assertFalse(vm.uiState.value.accounts.first { it.key == key(held) }.chosen)
        assertNull(vm.uiState.value.accounts.first { it.key == key(ethereum) }.unavailable)
        // And it can be unchosen, though greyed out: turned on again, it comes back unchosen.
        vm.toggle(key(held))
        on.setEnabled(Network.SEPOLIA, true)
        runCurrent()
        assertFalse(vm.uiState.value.accounts.first { it.key == key(held) }.chosen)
    }

    @Test
    fun aRequestThatAsksForTooMuchCanOnlyBeRejected() = runTest {
        main()
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(proposal(DappRefusal.OTHER_CHAIN)), emptyList())
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(accounts, keys, sites, networks),
            MakePerAppAddress(keys, accounts, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(DappProposalViewModel.refusalLine(DappRefusal.OTHER_CHAIN), vm.uiState.value.refusal)
        vm.toggle(key(held))
        vm.approve()
        assertEquals("Cannot be approved", vm.uiState.value.explaining?.title)
        assertTrue(sessions.approved.isEmpty())
        vm.dismissExplanation()
        vm.reject()
        runCurrent()
        assertEquals(listOf(proposalId), sessions.rejected)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aProposalNoLongerWaitingEndsTheScreen() = runTest {
        main()
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(accounts, keys, sites, networks),
            MakePerAppAddress(keys, accounts, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertTrue(vm.uiState.value.gone)
    }

    @Test
    fun aRequestThatExpiresAsApproveIsTappedSaysSoInsteadOfLeaving() = runTest {
        main()
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(proposal()), emptyList())
        sessions.approval = { _, _ ->
            sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, emptyList(), emptyList())
            eth.serotine.domain.repository.DappApproval.Expired
        }
        val vm = DappProposalViewModel(
            "${proposalId.pairing}/7",
            observe(),
            ApproveDappProposal(sessions, accounts, networks, sites),
            RejectDappProposal(sessions),
            ObservePerAppChoice(accounts, keys, sites, networks),
            MakePerAppAddress(keys, accounts, WalletChanges()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.toggle(key(held))
        vm.approve()
        runCurrent()
        val state = vm.uiState.value
        assertTrue(state.ended)
        assertFalse(state.gone)
        assertEquals("This request has expired. Connect again from the dApp.", state.notice)
    }

    @Test
    fun aSessionPastItsEndSaysItEnded() {
        assertEquals("Ended", ConnectionsViewModel.endsIn(-5))
        assertEquals("Ends in a few minutes", ConnectionsViewModel.endsIn(120))
    }

    // Per-app addresses (issue #252): a dApp stating https://app.uniswap.org, on Ethereum and Gnosis Chain.

    private val uniswap = (AppHost.parse("uniswap.org") as AppHostResult.Host).host
    private val ethereumHeld = account(Network.ETHEREUM, '1')
    private val realFunds = MemoryNetworkSettings(setOf(Network.ETHEREUM, Network.GNOSIS))

    private fun perAppProposal() = DappProposal(
        proposalId,
        DappMetadata("Uniswap", "https://app.uniswap.org/swap", ""),
        emptySet(),
        setOf("eip155:1", "eip155:100"),
        setOf("personal_sign"),
        null,
        1_700_000_300,
    )

    private fun perAppViewModel(wallet: FakeAccounts) = DappProposalViewModel(
        "${proposalId.pairing}/7",
        ObserveDappConnections(sessions, wallet, realFunds),
        ApproveDappProposal(sessions, wallet, realFunds, sites),
        RejectDappProposal(sessions),
        ObservePerAppChoice(wallet, keys, sites, realFunds),
        MakePerAppAddress(keys, wallet, WalletChanges()),
    )

    @Test
    fun aFirstConnectionOffersANewAddressForTheSiteTickedAndMakesItUnderThePassword() = runTest {
        main()
        val wallet = FakeAccounts(ethereumHeld)
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val offered = vm.uiState.value.newAddress!!
        assertEquals("New address for uniswap.org", offered.title)
        assertEquals("app.uniswap.org, site uniswap.org · On Ethereum and Gnosis Chain", offered.where)
        assertTrue(offered.chosen)
        assertNull(offered.phrase, "one phrase: nothing asked")
        assertFalse(vm.uiState.value.accounts.single().chosen)
        assertTrue(vm.uiState.value.approveUnavailable, "the password first")
        vm.approve()
        assertEquals(DappProposalViewModel.PASSWORD_FIRST, vm.uiState.value.explaining?.reason)
        vm.dismissExplanation()

        vm.onPasswordChange("correct horse")
        assertFalse(vm.uiState.value.approveUnavailable)
        vm.approve()
        runCurrent()
        val made = listOf(AccountId(Network.ETHEREUM, keys.address), AccountId(Network.GNOSIS, keys.address))
        val host = (AppHost.parse("app.uniswap.org") as AppHostResult.Host).host
        assertEquals(listOf(ethereumHeld.address to host), keys.derived)
        assertEquals(listOf(proposalId to made), sessions.approved)
        assertEquals(mapOf(uniswap to made), sites.remembered.value)
        val marked = wallet.accounts.value.filter { it.perApp != null }
        assertEquals(made, marked.map { it.id })
        assertEquals(listOf("uniswap.org", "uniswap.org"), marked.map { it.label })
        assertTrue(vm.uiState.value.done)
        assertEquals(WalletPassword.EMPTY.text, vm.uiState.value.password.text)
    }

    @Test
    fun aWrongPasswordMakesAndApprovesNothing() = runTest {
        main()
        val wallet = FakeAccounts(ethereumHeld)
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.onPasswordChange("not it")
        vm.approve()
        runCurrent()
        assertEquals(DappProposalViewModel.makingLine(PerAppMaking.WrongPassword), vm.uiState.value.notice)
        assertTrue(sessions.approved.isEmpty())
        assertEquals(listOf(ethereumHeld), wallet.accounts.value)
        assertTrue(vm.uiState.value.newAddress!!.chosen, "still chosen, to try again")
    }

    @Test
    fun theSiteConnectingAgainFindsItsAddressPreselected() = runTest {
        main()
        val mark = PerAppAccount(uniswap, AppIsolation.pathOf(uniswap), ethereumHeld.address)
        val perApp = Account(
            Network.ETHEREUM, keys.address, AccountKind.EOA, "uniswap.org", AccountCustody.InKeyStore, perApp = mark,
        )
        val onGnosis = perApp.copy(network = Network.GNOSIS)
        val wallet = FakeAccounts(ethereumHeld, perApp, onGnosis)
        sites.remembered.value = mapOf(uniswap to listOf(perApp.id, onGnosis.id))
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val state = vm.uiState.value
        assertNull(state.newAddress, "the site has its address on both networks")
        assertEquals(listOf(false, true, true), state.accounts.map { it.chosen })
        assertEquals("uniswap.org", state.accounts[1].perAppSite)
        assertFalse(state.approveUnavailable, "no password: nothing to make")
        vm.approve()
        runCurrent()
        assertEquals(listOf(proposalId to listOf(perApp.id, onGnosis.id)), sessions.approved)
        assertTrue(keys.derived.isEmpty())
    }

    @Test
    fun aSealThatSteppedDownIsSaidBeforeTheScreenLeaves() = runTest {
        main()
        keys.steppedDown = true
        val wallet = FakeAccounts(ethereumHeld)
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.onPasswordChange("correct horse")
        vm.approve()
        runCurrent()
        assertEquals(1, sessions.approved.size, "connected")
        assertEquals(DappProposalViewModel.STEPPED_DOWN, vm.uiState.value.notice)
        assertFalse(vm.uiState.value.done, "the notice is read first")
    }

    @Test
    fun theSitesAddressOnANetworkThatIsOffIsGreyedOutNotLeftOut() = runTest {
        main()
        val mark = PerAppAccount(uniswap, AppIsolation.pathOf(uniswap), ethereumHeld.address)
        val perApp = Account(
            Network.ETHEREUM, keys.address, AccountKind.EOA, "uniswap.org", AccountCustody.InKeyStore, perApp = mark,
        )
        val wallet = FakeAccounts(ethereumHeld, perApp)
        // Its address is on Ethereum; the dApp asks for Gnosis Chain too, which is off.
        realFunds.on.value = setOf(Network.ETHEREUM)
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val offered = vm.uiState.value.newAddress!!
        assertEquals(DappProposalViewModel.NO_NETWORK, offered.unavailable)
        assertFalse(offered.chosen)
        // Turned on, the row can be ticked.
        realFunds.on.value = setOf(Network.ETHEREUM, Network.GNOSIS)
        runCurrent()
        assertNull(vm.uiState.value.newAddress!!.unavailable)
    }

    @Test
    fun withoutAPhraseTheNewAddressIsGreyedOutAndSaysWhy() = runTest {
        main()
        val wallet = FakeAccounts(account(Network.ETHEREUM, '2', AccountCustody.Watched))
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val offered = vm.uiState.value.newAddress!!
        assertFalse(offered.chosen)
        assertEquals(DappProposalViewModel.NO_PHRASE, offered.unavailable)
        vm.toggle(DappProposalViewModel.NEW_KEY)
        assertEquals(DappProposalViewModel.NO_PHRASE, vm.uiState.value.explaining?.reason)
    }

    @Test
    fun withTwoPhrasesTheUserChoosesWhichOneTheAddressComesFrom() = runTest {
        main()
        val other = account(Network.ETHEREUM, '5')
        keys.phrases = mapOf(ethereumHeld.address to ethereumHeld.address, other.address to other.address)
        val wallet = FakeAccounts(ethereumHeld, other)
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals("From the recovery phrase of Account 1", vm.uiState.value.newAddress?.phrase)
        assertTrue(vm.uiState.value.newAddress!!.changeable)
        vm.choosePhrase()
        val phrases = vm.uiState.value.phrases!!
        assertEquals(listOf("Account 1", "Account 5"), phrases.map { it.name })
        assertEquals(listOf(true, false), phrases.map { it.chosen })
        vm.pickPhrase(phrases[1].key)
        assertNull(vm.uiState.value.phrases)
        assertEquals("From the recovery phrase of Account 5", vm.uiState.value.newAddress?.phrase)
        vm.onPasswordChange("correct horse")
        vm.approve()
        runCurrent()
        assertEquals(other.address, keys.derived.single().first)
    }

    @Test
    fun theSitesAddressOnMoreNetworksComesFromThePhraseItWasMadeFrom() = runTest {
        main()
        val other = account(Network.ETHEREUM, '5')
        val mark = PerAppAccount(uniswap, AppIsolation.pathOf(uniswap), other.address)
        val perApp = Account(
            Network.ETHEREUM, keys.address, AccountKind.EOA, "uniswap.org", AccountCustody.InKeyStore, perApp = mark,
        )
        keys.phrases = mapOf(
            ethereumHeld.address to ethereumHeld.address,
            other.address to other.address,
            perApp.address to other.address,
        )
        val wallet = FakeAccounts(ethereumHeld, other, perApp)
        sessions.connections.value = DappConnections.On(DappRelayLink.CONNECTED, listOf(perAppProposal()), emptyList())
        val vm = perAppViewModel(wallet)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val offered = vm.uiState.value.newAddress!!
        assertEquals("uniswap.org's address on more networks", offered.title)
        assertFalse(offered.chosen, "the site has an address: nothing new is ticked")
        assertNull(offered.phrase, "its own phrase: nothing to choose")

        vm.toggle(DappProposalViewModel.NEW_KEY)
        vm.onPasswordChange("correct horse")
        vm.toggle(DappProposalViewModel.NEW_KEY)
        assertEquals("", vm.uiState.value.password.text, "unticked, the password goes")
        vm.toggle(DappProposalViewModel.NEW_KEY)
        vm.onPasswordChange("correct horse")
        vm.approve()
        runCurrent()
        assertEquals(other.address, keys.derived.single().first)
        val marked = wallet.accounts.value.filter { it.perApp != null }
        assertEquals(listOf(Network.ETHEREUM, Network.GNOSIS), marked.map { it.network })
    }
}

