package eth.serotine.ui.ledger

import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import eth.serotine.domain.usecase.EnrollLedgerAccount
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerAccountViewModelTest {
    @Test
    fun choosingAnAddressFromTheSecondPageOnlySavesItAfterDeviceConfirmationAndAdd() = runTest {
        withPicker { vm, device, accounts ->
            vm.browseAccounts()
            runCurrent()
            vm.browsePage(5)
            runCurrent()

            val page = vm.uiState.value.accountPage!!
            assertEquals("Account 6", page.accounts!!.first().label)
            assertEquals(Eip55.format(addresses[5]), page.accounts.first().displayAddress)
            assertEquals("m/44'/60'/5'/0/0", device.requestedPaths.first().value)
            vm.chooseAccount(5)
            vm.add()
            assertTrue(device.confirmedPaths.isEmpty())
            assertTrue(accounts.accounts.value.isEmpty())

            device.approval = LedgerResult.Ok(addresses[5])
            vm.confirm()
            runCurrent()
            assertEquals("m/44'/60'/5'/0/0", device.confirmedPaths.single().value)
            assertIs<LedgerEnrollmentStep.Confirmed>(vm.uiState.value.step)
            assertTrue(accounts.accounts.value.isEmpty())

            vm.add()
            runCurrent()
            val saved = accounts.accounts.value.single()
            assertEquals(addresses[5], saved.address)
            assertEquals(AccountCustody.Ledger(device.confirmedPaths.single()), saved.custody)
        }
    }

    @Test
    fun replacingTheLedgerAfterBrowsingCannotEnrollAnAddressTheUserDidNotPick() = runTest {
        withPicker { vm, device, accounts ->
            vm.browseAccounts()
            runCurrent()
            vm.chooseAccount(1)
            device.approval = LedgerResult.Ok(addresses[0])
            vm.confirm()
            runCurrent()
            vm.add()
            runCurrent()

            assertEquals(LedgerEnrollmentStep.Editing, vm.uiState.value.step)
            assertTrue(accounts.accounts.value.isEmpty())
        }
    }

    @Test
    fun aCustomPathCanStillBeConfirmedAndAddedWithoutBrowsing() = runTest {
        withPicker { vm, device, accounts ->
            vm.useCustomPath(true)
            vm.editPath("m/44'/60'/0'/4")
            device.approval = LedgerResult.Ok(addresses[0])
            vm.confirm()
            runCurrent()
            vm.add()
            runCurrent()

            val custody = assertIs<AccountCustody.Ledger>(accounts.accounts.value.single().custody)
            assertEquals("m/44'/60'/0'/4", custody.path.value)
            assertTrue(device.requestedPaths.isEmpty())
        }
    }

    private suspend fun TestScope.withPicker(
        test: suspend (LedgerAccountViewModel, Device, FakeAccounts) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val accounts = FakeAccounts()
        val device = Device()
        val networks = MemoryNetworkSettings(setOf(Network.ETHEREUM))
        val vm = LedgerAccountViewModel(
            EnrollLedgerAccount(device, accounts, WalletChanges()),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
        )
        try {
            runCurrent()
            test(vm, device, accounts)
        } finally {
            vm.close()
            Dispatchers.resetMain()
        }
    }

    private class Device : LedgerDevice {
        var requestedPaths = emptyList<LedgerPath>()
        val confirmedPaths = mutableListOf<LedgerPath>()
        var approval: LedgerResult<Address> = LedgerResult.Failed(LedgerProblem.REJECTED)

        override suspend fun readAddresses(paths: List<LedgerPath>): LedgerResult<List<Address>> {
            requestedPaths = paths
            return LedgerResult.Ok(paths.map { path ->
                val account = path.value.split('/')[3].removeSuffix("'").toInt()
                addresses[account]
            })
        }

        override suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address> {
            confirmedPaths += path
            return approval
        }

        override suspend fun signTransaction(path: LedgerPath, expectedAddress: Address, transaction: Eip1559Transaction) =
            error("Enrollment does not sign transactions")
    }

    private companion object {
        val addresses = List(10) { index -> Address.of("0x" + "12a$index".repeat(10)) }
    }
}
