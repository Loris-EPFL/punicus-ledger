package eth.serotine.ui.ledger

import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel

internal val ledgerNetworks = Network.entries
private val ledgerNetworkLabels = ledgerNetworks.associateWith { NetworkLabel.of(it) }

/** The Ledger enrollment form and the current device or save step. */
data class LedgerAccountUiState internal constructor(
    val network: Network = Network.ETHEREUM,
    val enabledNetworks: List<Network>? = null,
    val path: String = LedgerPath.DEFAULT.value,
    val pathError: String? = null,
    val message: String? = null,
    val step: LedgerEnrollmentStep = LedgerEnrollmentStep.Editing,
) {
    internal val busy: Boolean
        get() = step == LedgerEnrollmentStep.Confirming || step == LedgerEnrollmentStep.Enabling ||
            step is LedgerEnrollmentStep.Saving || step == LedgerEnrollmentStep.Added

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

/** The device confirmation and local account storage steps. */
sealed interface LedgerEnrollmentStep {
    data object Editing : LedgerEnrollmentStep
    data object Confirming : LedgerEnrollmentStep
    data object Enabling : LedgerEnrollmentStep
    data class Confirmed(val address: String) : LedgerEnrollmentStep
    data class Saving(val address: String) : LedgerEnrollmentStep
    data object Added : LedgerEnrollmentStep
}
