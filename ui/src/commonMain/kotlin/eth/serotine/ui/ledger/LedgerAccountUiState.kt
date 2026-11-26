package eth.serotine.ui.ledger

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel

internal val ledgerNetworks = Network.entries
internal const val LEDGER_ACCOUNTS_PER_PAGE = 5
private val ledgerNetworkLabels = ledgerNetworks.associateWith { NetworkLabel.of(it) }

/** The Ledger enrollment form and the current device or save step. */
@ConsistentCopyVisibility
data class LedgerAccountUiState internal constructor(
    val network: Network = Network.ETHEREUM,
    val enabledNetworks: List<Network>? = null,
    val path: String = LedgerPath.DEFAULT.value,
    internal val customPath: Boolean = false,
    internal val selectedAccount: LedgerAddressChoice? = null,
    internal val accountPage: LedgerAccountPage? = null,
    val pathError: String? = null,
    val message: String? = null,
    val step: LedgerEnrollmentStep = LedgerEnrollmentStep.Editing,
) {
    internal val busy: Boolean
        get() = accountPage?.loading == true || step == LedgerEnrollmentStep.Confirming ||
            step == LedgerEnrollmentStep.Enabling || step is LedgerEnrollmentStep.Saving ||
            step == LedgerEnrollmentStep.Added

    internal val accountLabel: String
        get() = selectedAccount?.label ?: "Account 1"

    internal val choices: List<NetworkChip>
        get() = ledgerNetworks.map { choice ->
            val off = enabledNetworks?.let { choice !in it } == true
            NetworkChip(
                network = ledgerNetworkLabels.getValue(choice),
                selected = network == choice,
                reason = when {
                    busy -> "Wait for the current step to finish before changing networks."
                    enabledNetworks == null -> "Punicus is reading which networks are on."
                    off -> "${choice.displayName} is off in this wallet. Turn it on to add the account."
                    else -> null
                },
                off = off && !busy,
            )
        }

    internal val addReason: String?
        get() = when {
            enabledNetworks == null -> "Punicus is reading which networks are on."
            network !in enabledNetworks -> "${network.displayName} is off in this wallet. Turn it on to add the account."
            else -> null
        }
}

/** One address read over USB, not yet confirmed on the device. */
internal data class LedgerAddressChoice(val index: Int, val address: Address) {
    val label = "Account ${index.toLong() + 1}"
    val displayAddress = Eip55.format(address)
}

/** A page of consecutive Ledger Live paths; null addresses while the device is being read. */
internal data class LedgerAccountPage(val start: Int, val accounts: List<LedgerAddressChoice>? = null) {
    val loading: Boolean get() = accounts == null
    val previous: Int? get() = if (start > 0) start - LEDGER_ACCOUNTS_PER_PAGE else null
    val next: Int?
        get() = if (start <= Int.MAX_VALUE - LEDGER_ACCOUNTS_PER_PAGE) start + LEDGER_ACCOUNTS_PER_PAGE else null
}

/** The device confirmation and local account storage steps. */
sealed interface LedgerEnrollmentStep {
    data object Editing : LedgerEnrollmentStep
    data object Confirming : LedgerEnrollmentStep
    data object Enabling : LedgerEnrollmentStep
    data class Confirmed(val address: String) : LedgerEnrollmentStep
    data class Saving(val address: String) : LedgerEnrollmentStep
    data object Added : LedgerEnrollmentStep
}
