package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappSiteMemory
import eth.serotine.domain.repository.DappSessions
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.PerAppAliases
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Whether an account may be offered to a dApp, and why not when it may not (docs/walletconnect.md,
 * *Pairing and the session proposal*). An account that may not is shown greyed out, never hidden
 * (owner ruling, 2026-10-18, #55).
 */
enum class DappOffer {
    /** An account that can sign here, or a Safe, on a network that is on: it may be offered. */
    OFFERED,

    /** An account on a network the dApp did not ask for. */
    NOT_ASKED,

    /**
     * An account of the other kind of funds than the dApp requires, or than an account already chosen: a
     * session holds real funds or testnet, never both (owner ruling, 2026-11-05, on #22).
     */
    OTHER_FUNDS,

    /** An account on a network that is off: turning it on offers it. */
    NETWORK_OFF,

    /** An EOA watched with no key on this device: it can sign nothing here. */
    NO_KEY,

    /**
     * The per-app address of another site than the dApp's (issue #252): offered only to its own site, so that the two
     * cannot link the user's activity, and to a site the user linked to it, an alias, after being asked
     * ([PerAppAliases]).
     */
    OTHER_SITE,
}

/** One of the wallet's accounts, and whether a dApp may be offered it, whatever the proposal asks. */
data class DappAccountChoice(val account: Account, val offer: DappOffer) {
    /**
     * Whether [account] may be offered to [proposal]'s dApp, with the accounts [chosen] so far besides it
     * (screen D3), and the per-app address [alias] the dApp's site was linked to, if any: as [dappOffer] answers,
     * from the [offer] judged already.
     */
    fun offerTo(
        proposal: DappProposal,
        chosen: Collection<AccountId> = emptyList(),
        alias: Address? = null,
    ): DappOffer = proposalOffer(account, proposal, chosen, alias) ?: offer
}

/**
 * Why [account] may not be offered to [proposal]'s dApp beside [chosen], for what it asks; null otherwise. Another
 * site's per-app address is offered only as the site's [alias].
 */
private fun proposalOffer(
    account: Account,
    proposal: DappProposal,
    chosen: Collection<AccountId>,
    alias: Address?,
): DappOffer? {
    val kinds = (proposal.requiredNetworks + chosen.map { it.network }).map { it.testnet }.toSet()
    // Asked for at all first, of either kind: one asked for but of the other kind is said as that.
    return when {
        account.network !in proposal.askedNetworks -> DappOffer.NOT_ASKED
        kinds.any { it != account.network.testnet } -> DappOffer.OTHER_FUNDS
        account.perApp != null && account.perApp.site != siteOf(proposal) && account.address != alias ->
            DappOffer.OTHER_SITE
        else -> null
    }
}

/** The site of the host [proposal]'s dApp states ([AppHost.site]), or null when it states none. */
fun siteOf(proposal: DappProposal): AppHost? = (AppHost.ofUrl(proposal.dapp.url) as? AppHostResult.Host)?.host?.site

/** The connections, and every account of the wallet with whether a dApp may be offered it. */
data class DappOverview(
    val connections: DappConnections,
    val accounts: List<DappAccountChoice>,
    /** The account list cannot be read: nothing can be offered. */
    val accountsUnreadable: Boolean = false,
)

/** Whether [account] may be offered to any dApp, with the networks [enabled]: whatever the proposal asks. */
fun dappOffer(account: Account, enabled: Set<Network>): DappOffer = when {
    account.network !in enabled -> DappOffer.NETWORK_OFF
    // A watched Safe is offered: its owners sign for it, here or elsewhere.
    account.kind == AccountKind.EOA && account.custody == AccountCustody.Watched -> DappOffer.NO_KEY
    else -> DappOffer.OFFERED
}

/**
 * Whether [account] may be offered to [proposal]'s dApp, with the networks [enabled] and the accounts
 * [chosen] so far (screen D3): on a network it asks for, of the kind of funds it requires and the
 * [chosen] ones are, another site's per-app address only as the site's [alias], and offered at all ([dappOffer]).
 */
fun dappOffer(
    account: Account,
    enabled: Set<Network>,
    proposal: DappProposal,
    chosen: Collection<AccountId> = emptyList(),
    alias: Address? = null,
): DappOffer = proposalOffer(account, proposal, chosen, alias) ?: dappOffer(account, enabled)

/** The dApp connections with the accounts a proposal may be approved with (screens D1 and D3). */
class ObserveDappConnections(
    private val sessions: DappSessions,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
) {
    operator fun invoke(): Flow<DappOverview> =
        combine(sessions.observe(), accounts.observeAccounts(), networks.observe()) { connections, list, enabled ->
            when (list) {
                is AccountList.Loaded ->
                    DappOverview(connections, list.accounts.map { DappAccountChoice(it, dappOffer(it, enabled)) })
                is AccountList.Unreadable -> DappOverview(connections, emptyList(), accountsUnreadable = true)
            }
        }.distinctUntilChanged()
}

/** Pairs with the dApp of a `wc:` URI the user pasted (screen D2). */
class PairDapp(private val sessions: DappSessions) {
    suspend operator fun invoke(uri: String): DappPairing = sessions.pair(uri.trim())
}

/**
 * Approves a proposal with [accounts]: each must be one of the wallet's accounts the proposal's dApp may be
 * offered ([dappOffer]), on a network it asked for, all of one kind of funds with what it requires, so
 * that nothing a screen got wrong reaches the dApp ([DappApproval.InvalidAccounts]). Another site's per-app address
 * passes only as the alias the user linked the dApp's site to ([PerAppAliases]). Once approved, the accounts are
 * remembered for the site of the host the dApp states ([DappSiteMemory]; issue #252), to be preselected when a host
 * of that site connects again; a memory that cannot be saved takes nothing from the approval.
 */
class ApproveDappProposal(
    private val sessions: DappSessions,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
    private val sites: DappSiteMemory,
    private val aliases: PerAppAliases,
) {
    suspend operator fun invoke(proposal: DappProposalId, chosen: List<AccountId>): DappApproval {
        val asked = (sessions.observe().first() as? DappConnections.On)?.proposals?.find { it.id == proposal }
            ?: return DappApproval.NoSuchProposal
        val list = accounts.observeAccounts().first() as? AccountList.Loaded ?: return DappApproval.InvalidAccounts
        val enabled = networks.observe().first()
        val wallet = list.accounts.associateBy { it.id }
        // A site with an address of its own has no alias here, whatever the file says (owner, 2026-11-19, on #328).
        val site = siteOf(asked)
        val ownAddress = site != null && list.accounts.any { it.perApp?.site == site }
        val alias = site?.takeUnless { ownAddress }?.let { aliases.observe().first()[it] }
        // Each against the others chosen: one of the other kind of funds than any of them is refused.
        val valid = chosen.isNotEmpty() && chosen.toSet().size == chosen.size && chosen.all { id ->
            val account = wallet[id] ?: return@all false
            dappOffer(account, enabled, asked, chosen - id, alias) == DappOffer.OFFERED
        } && missingRequired(asked, chosen).isEmpty()
        if (!valid) return DappApproval.InvalidAccounts
        val approval = sessions.approve(proposal, chosen)
        if (approval is DappApproval.Approved) siteOf(asked)?.let { sites.remember(it, chosen) }
        return approval
    }
}

/**
 * The networks [proposal] requires that [chosen] has no account on: WalletConnect has a session cover every chain
 * its proposal requires, so it cannot be approved until each has one (screen D3 greys Approve out until then).
 */
fun missingRequired(proposal: DappProposal, chosen: Collection<AccountId>): Set<Network> =
    proposal.requiredNetworks - chosen.map { it.network }.toSet()

/** Rejects a proposal (screen D3). */
class RejectDappProposal(private val sessions: DappSessions) {
    suspend operator fun invoke(proposal: DappProposalId): Boolean = sessions.reject(proposal)
}

/** Ends a session (screen D1). */
class DisconnectDapp(private val sessions: DappSessions) {
    suspend operator fun invoke(session: DappSessionId): Boolean = sessions.disconnect(session)
}

/**
 * Keeps each session's accounts to those a dApp may still be offered ([dappOffer]), while it runs: one
 * removed from the wallet, whose key was removed, or whose network was turned off is taken out of
 * every session that sees it, the dApp told (`accountsChanged`), and a session left with none, or with none on
 * a network its dApp required ([DappSession.requiredNetworks]), is ended. A network counts as turned off only
 * once the setting has answered it as on while this runs: a setting that cannot be read is answered with
 * the build's defaults ([NetworkSettings.observe]), and a release build's default has Sepolia off, so a
 * network off from the start ends no session on that word.
 * A defaults answer of "on" counts as one (a debug build's Sepolia, say), so a later read of "off" ends
 * the sessions then, which is the stored setting. Its requests are refused all the same. The host runs it
 * beside the relay connection.
 */
class FollowDappAccounts(
    private val sessions: DappSessions,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
) {
    suspend operator fun invoke() {
        val seenOn = mutableSetOf<Network>()
        combine(sessions.observe(), accounts.observeAccounts(), networks.observe()) { connections, list, enabled ->
            Triple(connections, list, enabled)
        }
            .collect { (connections, list, enabled) ->
                val on = connections as? DappConnections.On ?: return@collect
                // An unreadable list is no news of a removal: nothing is changed on its word.
                val loaded = list as? AccountList.Loaded ?: return@collect
                // Nor is a damaged list set aside, which reads as one without its accounts.
                if (loaded.damagedListSetAside) return@collect
                seenOn += enabled
                // A network never read as on is not known to be off: it may be an unreadable setting.
                val judged = enabled + (Network.entries - seenOn)
                val held = loaded.accounts
                    .filter { dappOffer(it, judged) == DappOffer.OFFERED }
                    .map { it.id }
                    .toSet()
                for (session in on.sessions) {
                    val kept = session.accounts.filter { it in held }
                    // A network the dApp required left with no account: the session would no longer be one it
                    // agreed to, so it ends rather than shrinks.
                    val uncovered = session.requiredNetworks - kept.map { it.network }.toSet()
                    when {
                        kept.size == session.accounts.size -> Unit
                        kept.isEmpty() || uncovered.isNotEmpty() -> sessions.disconnect(session.id)
                        else -> sessions.updateAccounts(session.id, kept)
                    }
                }
            }
    }
}

/** Extends a session to seven days from now (screen D1). */
class ExtendDappSession(private val sessions: DappSessions) {
    suspend operator fun invoke(session: DappSessionId): Boolean = sessions.extend(session)
}
