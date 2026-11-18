package eth.serotine.ui.gnosispay

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.UnlockStep

/**
 * A Gnosis Pay account's screen (docs/gnosis-pay.md), display-ready: what its Delay module lets
 * this wallet do, the transfers queued through it from here and where each stands, and the steps
 * of a transfer through it.
 */
sealed interface GnosisPayUiState {

    /** Not read yet. */
    data object Loading : GnosisPayUiState

    /** Nothing to show: the account is no Gnosis Pay account, or cannot be read now, as [message] says. */
    data class Unavailable(val message: String) : GnosisPayUiState

    /** The account left the wallet: the screen leaves. */
    data object Gone : GnosisPayUiState

    /**
     * The account [label] at [address] (EIP-55), its Delay module at [delay], what its Delay
     * makes a transfer wait ([waits]: "A transfer waits 3 min, then can be executed for 30 min"),
     * where that was verified ([checkedAt]), its [members], the [keys] at the end of the ways down
     * from them through member Safes with [controlNote] saying what was not followed or not read,
     * Send ([send]: greyed out with its reason when it cannot be done here), the transfers queued
     * from here ([queued]) with [queuedNote] saying what could not be read, and the [step] the
     * screen is at.
     */
    data class Ready(
        val label: String,
        val address: String,
        val delay: String,
        val waits: String,
        val checkedAt: String,
        val members: List<MemberRow>,
        val keys: List<KeyRow> = emptyList(),
        val controlNote: String? = null,
        val send: ActionItem,
        val queued: List<QueuedRow>,
        val queuedNote: String?,
        val step: GnosisPayStep,
    ) : GnosisPayUiState
}

/**
 * One member of the Delay: its EIP-55 [address], its name in the wallet ([label]), whether a key on
 * this device controls the account through it ([heldHere]), and [what] it is when it is not proven a
 * key ("A Safe: 1 of its 9 owners signs", "Not verified yet"); null for a key; and Remove ([remove]:
 * greyed out with its reason when it cannot be done here, the last member's above all), where the
 * screen offers it (the Safe's settings; owner ruling, 2026-11-18), null where it does not.
 */
data class MemberRow(
    val address: String,
    val label: String?,
    val heldHere: Boolean,
    val what: String? = null,
    val remove: ActionItem? = null,
)

/**
 * A key at the end of a way down from a member (owner's request, 2026-11-17): its EIP-55 [address],
 * its name in the wallet ([label]), whether its key is on this device ([heldHere]), the Safes it
 * goes [through] ("Through Safe 0xdbF0…C6FB"; null for a member itself), and whether it acts [alone]
 * or only with co-owners.
 */
data class KeyRow(
    val address: String,
    val label: String?,
    val heldHere: Boolean,
    val through: String?,
    val alone: Boolean,
)

/**
 * An action shown either way: [enabled], or greyed out with the [reason] a tap on it says (owner
 * ruling, 2026-10-18, #55).
 */
data class ActionItem(val enabled: Boolean, val reason: String?)

/**
 * A call queued from here: its [id] (the queueing's transaction hash), what it does ([title]:
 * "50 EURe to 0x1234…abcd", "Add member 0x1234…abcd"), where it stands ([status]), Execute
 * ([execute]; null where it does not apply, greyed out with its reason where it cannot be done
 * now), and Skip expired entries ([skip]) while an expired entry ahead of it blocks it; null
 * otherwise; and [cooldownLeft], what is left of its cooldown while it waits it out, from 1 when
 * queued to 0 when it may be executed (null otherwise), drawn as a bar that drains.
 */
data class QueuedRow(
    val id: String,
    val title: String,
    val status: String,
    val execute: ActionItem?,
    val skip: ActionItem? = null,
    val cooldownLeft: Float? = null,
)

/** Where the screen is in a transfer through the Delay. */
sealed interface GnosisPayStep {

    /**
     * The account, its queued transfers, and Send; [error] why the last Execute or change of members
     * was not quoted, and [checking] while a removal of a member is checked on chain.
     */
    data class Overview(val error: String? = null, val checking: String? = null) : GnosisPayStep

    /**
     * The transfer form: the Safe's [assets] to choose from, the [asset] chosen (its contract, or
     * null for the network's currency), the recipient [to] and the [amount] as typed, the [sender]
     * (the member whose key signs, by name), [error] what is wrong with them, [quoting] while
     * Review checks them on chain, and [skip] when the Delay's queue holds an expired entry ahead:
     * then "Skip expired entries" is offered beside the error.
     */
    data class Compose(
        val assets: List<AssetItem>,
        val asset: String?,
        val to: String,
        val amount: String,
        val sender: String,
        val error: String? = null,
        val quoting: Boolean = false,
        val skip: Boolean = false,
    ) : GnosisPayStep

    /**
     * The form that adds a member to the Delay (owner ruling, 2026-11-18): the [member]'s address as
     * typed, what a member can do ([explains], in the account's own figures), the [sender] (the key
     * that signs, by name), [error] what is wrong, [quoting] while Review checks it on chain, and
     * [skip] when an expired entry blocking the Delay's queue is what refused it: Skip expired entries
     * is then offered, as on the transfer form.
     */
    data class AddMember(
        val member: String,
        val explains: String,
        val sender: String,
        val error: String? = null,
        val quoting: Boolean = false,
        val skip: Boolean = false,
    ) : GnosisPayStep

    /**
     * The review of the member's transaction: its [title] ("Queue 50 EURe to 0x1234…abcd", "Execute
     * …"), its [lines] (label to value), the [fee] level it was quoted at, the [unlock] that signs it,
     * [signing] while it does (or while the quote is made again), [error] why the last try sent
     * nothing, [checking] while the quote is made again, [note] why it was (a stale one), null
     * when it was not, and [warning] what a change of the Delay's members puts at stake (a new member can
     * move all the funds; a removal can end this device's control), null for a transfer.
     */
    data class Review(
        val title: String,
        val lines: List<Pair<String, String>>,
        val fee: FeeLevel,
        val unlock: UnlockStep,
        val signing: Boolean = false,
        val error: String? = null,
        val note: String? = null,
        val checking: Boolean = false,
        val warning: String? = null,
    ) : GnosisPayStep
}

/**
 * One asset of the Safe to send: its [contract] (null: the network's currency), [symbol] and
 * [balance] as read (with its symbol), and [warning] when it is a token the user added whose
 * symbol is a built-in token's.
 */
data class AssetItem(val contract: String?, val symbol: String, val balance: String, val warning: String? = null)
