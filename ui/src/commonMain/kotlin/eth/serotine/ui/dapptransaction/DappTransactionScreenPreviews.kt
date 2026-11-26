package eth.serotine.ui.dapptransaction

import androidx.compose.runtime.Composable
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.dapprequest.DappRequestViewModel
import eth.serotine.ui.dapprequest.SignInField
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A dApp's transaction in Android Studio's preview pane, one preview per state it draws differently.
// DappTransactionScreenPreviewsTest renders each one in dappTransactionScreenPreviews, at the end.

private const val ACCOUNT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val RECIPIENT = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

private val quote = listOf(
    SignInField("Fee, at most", "0.00168 ETH (testnet)"),
    SignInField("Left in the account, at least", "0.49732 ETH (testnet)"),
    SignInField("Verified run", "Runs without reverting at block 7,412,903"),
    SignInField("Gas limit", "80000"),
    SignInField("Nonce", "12"),
)

private val payment = DappTransactionUiState(
    loading = false,
    name = "Example dApp",
    url = "https://app.example.org",
    accountName = "Main",
    accountAddress = ACCOUNT,
    to = RECIPIENT,
    value = "0.5 ETH (testnet)",
    meaning = "A payment of its value to the recipient.",
    quote = quote,
    waits = DappRequestViewModel.waitsLine(240),
)

private val tokenTransfer = payment.copy(
    to = USDC,
    value = "0 ETH (testnet)",
    meaning = "A transfer of 25 USDC to $RECIPIENT.",
    meaningFields = listOf(
        SignInField("Token", "USDC · $USDC"),
        SignInField("Recipient", RECIPIENT),
        SignInField("Amount", "25 USDC"),
    ),
    dataHex = "a9059cbb000000000000000000000000fb6916095ca1df60bb79ce92ce3ea74c37c5d359" +
        "0000000000000000000000000000000000000000000000000000000001312d00",
    dataSize = "68 bytes",
)

private val notDecoded = payment.copy(
    to = "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD",
    value = "0.01 ETH (testnet)",
    meaning = null,
    meaningFields = listOf(SignInField("Function", "0x3593564c")),
    dataHex = "3593564c0000000000000000000000000000000000000000000000000000000000000060" +
        "0000000000000000000000000000000000000000000000000000000000000a0",
    dataSize = "100 bytes",
    warnings = listOf(DappTransactionViewModel.NOT_DECODED),
)

private const val POOL = "0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2"
private const val WETH = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
private const val DESCRIBED_BY = "Described by Aave DAO, ${DappTransactionViewModel.DESCRIBED_BY}"

/** A call the app does not decode, described by the Aave v3 Pool's built-in descriptor. */
private val described = notDecoded.copy(
    to = POOL,
    value = "0 ETH",
    network = "On Ethereum, real funds",
    meaningFields = emptyList(),
    warnings = emptyList(),
    quote = listOf(
        SignInField("Fee, at most", "0.00412 ETH"),
        SignInField("Left in the account, at least", "2.31877 ETH"),
        SignInField("Verified run", "Runs without reverting at block 26,494,404"),
        SignInField("Gas limit", "260000"),
        SignInField("Nonce", "41"),
    ),
    dataHex = "617ba037000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" +
        "00000000000000000000000000000000000000000000000014d1120d7b160000" +
        "0000000000000000000000005aaeb6053f3e94c9b9a09f33669435e7ef1beaed" +
        "0000000000000000000000000000000000000000000000000000000000000000",
    dataSize = "132 bytes",
    described = ClearSignedSection(
        intent = "Supply",
        describedBy = DESCRIBED_BY,
        rows = listOf(
            ClearSignedRow(0, "Amount to supply", "1.5 WETH", raw = "1500000000000000000", note = null),
            ClearSignedRow(0, "Collateral recipient", ACCOUNT, raw = null, note = null),
        ),
        otherParameters = listOf(
            SignInField("asset", WETH),
            SignInField("referralCode", "0"),
        ),
    ),
)

private val describedNested = described.copy(
    described = ClearSignedSection(
        intent = "Multicall",
        describedBy = DESCRIBED_BY,
        rows = listOf(
            ClearSignedRow(0, "Call", "Supply, described by Aave DAO", raw = "0x617ba037…0000", note = null),
            ClearSignedRow(1, "Amount to supply", "1.5 WETH", raw = "1500000000000000000", note = null),
            ClearSignedRow(1, "Collateral recipient", ACCOUNT, raw = null, note = null),
            ClearSignedRow(1, "Other parameter · asset", WETH, raw = null, note = null),
            ClearSignedRow(1, "Other parameter · referralCode", "0", raw = null, note = null),
            ClearSignedRow(
                0, "Call", "Borrow, described by Aave DAO", raw = "0xa415bcad…0000",
                note = null,
            ),
            ClearSignedRow(1, "Amount to borrow", "500 USDC", raw = "500000000", note = null),
            ClearSignedRow(1, "Interest rate mode", "variable", raw = "2", note = null),
        ),
        otherParameters = emptyList(),
    ),
)

private val describedPartly = describedNested.copy(
    warnings = listOf(DappTransactionViewModel.PARTLY_NOT_DECODED),
    described = describedNested.described!!.copy(
        rows = describedNested.described!!.rows.take(5) + ClearSignedRow(
            0, "Call", "A call to $POOL, not decoded", raw = "0xab9c4b5d…0000",
            note = DappTransactionViewModel.NESTED_NOT_DECODED,
        ),
        partly = true,
    ),
)

private val describedApproval = described.copy(
    to = "0x000000000022D473030F116dDEE9F6B43aC78BA3",
    warnings = listOf(DappTransactionViewModel.APPROVAL_NOTE),
    described = ClearSignedSection(
        intent = "Approve",
        describedBy = "Described by Uniswap, ${DappTransactionViewModel.DESCRIBED_BY}",
        rows = listOf(
            ClearSignedRow(0, "Spender", RECIPIENT, raw = null, note = null),
            ClearSignedRow(0, "Amount", "Unlimited WETH", raw = "1461501637330902918203684832716283019655932542975", note = null),
            ClearSignedRow(0, "Expiration", "2026-12-31 00:00:00Z", raw = "1798675200", note = null),
        ),
        otherParameters = listOf(SignInField("token", WETH)),
    ),
)

private val describedUnlisted = described.copy(
    described = described.described!!.copy(
        rows = listOf(
            ClearSignedRow(
                0, "Amount to supply", "1500000 (raw units) of 0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD",
                raw = "1500000",
                note = "Not a built-in one: shown by its address, never by the name it gives itself.",
            ),
            ClearSignedRow(0, "Collateral recipient", ACCOUNT, raw = null, note = null),
        ),
        otherParameters = listOf(SignInField("asset", "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"), SignInField("referralCode", "0")),
    ),
)

private val describing = described.copy(described = null, warnings = listOf(DappTransactionViewModel.NOT_DECODED), describing = true)

private val codeNotChecked = described.copy(
    described = null,
    warnings = listOf(DappTransactionViewModel.NOT_DECODED),
    meaningFields = listOf(SignInField("Function", "0x617ba037")),
    notDescribed = DappTransactionViewModel.CODE_NOT_CHECKED,
)

private val notAsDescribed = described.copy(
    described = null,
    warnings = listOf(DappTransactionViewModel.NOT_DECODED),
    meaningFields = listOf(SignInField("Function", "0x617ba037")),
    notDescribed = DappTransactionViewModel.NOT_AS_DESCRIBED,
)

private val contractChanged = described.copy(
    described = null,
    warnings = listOf(DappTransactionViewModel.NOT_DECODED),
    meaningFields = listOf(SignInField("Function", "0x617ba037")),
    notDescribed = DappTransactionViewModel.CONTRACT_CHANGED,
)

private const val SAFE = "0x7a3B9c1E5d2F4a6B8c0D1e2F3a4B5c6D7e8F9a0B"
private const val OWNER = "0x2B5AD5c4795c026514f8317c7a215E218DcCD6cF"

private val fromSafe = payment.copy(
    accountName = "Team Safe",
    accountAddress = SAFE,
    fromSafe = true,
    quote = listOf(
        SignInField("Safe transaction", "Nonce 4, behind 1 queued; 2 of 3 owners sign it"),
        SignInField("Left in the Safe, at least", "1.25 ETH (testnet)"),
        SignInField("Verified run", "Runs from the Safe without reverting at block 7,412,903"),
        SignInField("Signs here as", OWNER),
        SignInField("Fee", "Paid by the account that executes it, quoted when it is executed"),
    ),
)

@Composable
private fun DappTransactionPreview(state: DappTransactionUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DappTransactionScreen(
            state,
            onPasswordChange = {},
            onSend = {},
            onSendWithBiometrics = {},
            onRetry = {},
            onRequote = {},
            onRefuse = {},
            onDismissExplanation = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun DappTransactionPaymentPreview() = DappTransactionPreview(payment)

@Preview
@Composable
private fun DappTransactionPaymentDarkPreview() = DappTransactionPreview(payment, darkTheme = true)

// A payment on Gnosis Chain, in a connection holding real funds: xDAI, no testnet mark.
private val onGnosis = payment.copy(
    network = DappRequestViewModel.onLine(eth.serotine.domain.model.Network.GNOSIS),
    value = "0.5 XDAI",
    quote = listOf(
        SignInField("Fee, at most", "0.0000462 XDAI"),
        SignInField("Left in the account, at least", "12.4999538 XDAI"),
        SignInField("Verified run", "Runs without reverting at block 42,918,330"),
        SignInField("Gas limit", "21000"),
        SignInField("Nonce", "3"),
    ),
)

@Preview
@Composable
private fun DappTransactionRealFundsPreview() = DappTransactionPreview(onGnosis)

@Preview
@Composable
private fun DappTransactionRealFundsDarkPreview() = DappTransactionPreview(onGnosis, darkTheme = true)

@Preview
@Composable
private fun DappTransactionTokenTransferPreview() = DappTransactionPreview(tokenTransfer)

@Preview
@Composable
private fun DappTransactionApprovalPreview() = DappTransactionPreview(
    tokenTransfer.copy(
        meaning = "An approval: lets 0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD take up to an unlimited amount of " +
            "USDC from this account.",
        meaningFields = listOf(
            SignInField("Token", "USDC · $USDC"),
            SignInField("Spender", "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"),
            SignInField("Amount", "an unlimited amount of USDC"),
        ),
        warnings = listOf(DappTransactionViewModel.APPROVAL_NOTE),
    ),
)

@Preview
@Composable
private fun DappTransactionNotDecodedPreview() = DappTransactionPreview(notDecoded)

@Preview
@Composable
private fun DappTransactionDescribedPreview() = DappTransactionPreview(described)

@Preview
@Composable
private fun DappTransactionDescribedDarkPreview() = DappTransactionPreview(described, darkTheme = true)

@Preview
@Composable
private fun DappTransactionDescribedNestedPreview() = DappTransactionPreview(describedNested)

@Preview
@Composable
private fun DappTransactionDescribedPartlyPreview() = DappTransactionPreview(describedPartly)

@Preview
@Composable
private fun DappTransactionDescribedApprovalPreview() = DappTransactionPreview(describedApproval)

@Preview
@Composable
private fun DappTransactionDescribedUnlistedPreview() = DappTransactionPreview(describedUnlisted)

@Preview
@Composable
private fun DappTransactionDescribingPreview() = DappTransactionPreview(describing)

@Preview
@Composable
private fun DappTransactionContractChangedPreview() = DappTransactionPreview(contractChanged)

@Preview
@Composable
private fun DappTransactionCodeNotCheckedPreview() = DappTransactionPreview(codeNotChecked)

@Preview
@Composable
private fun DappTransactionNotAsDescribedPreview() = DappTransactionPreview(notAsDescribed)

@Preview
@Composable
private fun DappTransactionQuotingPreview() = DappTransactionPreview(payment.copy(quote = emptyList(), quoting = true))

@Preview
@Composable
private fun DappTransactionNotQuotedPreview() = DappTransactionPreview(
    payment.copy(
        quote = emptyList(),
        notQuoted = "Not enough ETH in this account for the most the fee can be and the value.",
    ),
)

@Preview
@Composable
private fun DappTransactionRefusedPreview() = DappTransactionPreview(
    payment.copy(
        accountName = "Watched",
        quote = emptyList(),
        refusal = DappTransactionViewModel.refusalLine(DappSignRefusal.NO_KEY),
    ),
)

@Preview
@Composable
private fun DappTransactionSafePreview() = DappTransactionPreview(fromSafe)

@Preview
@Composable
private fun DappTransactionSafeDarkPreview() = DappTransactionPreview(fromSafe, darkTheme = true)

@Preview
@Composable
private fun DappTransactionSafeNoOwnerPreview() = DappTransactionPreview(
    fromSafe.copy(quote = emptyList(), notQuoted = DappTransactionViewModel.NO_OWNER_HERE),
)

@Preview
@Composable
private fun DappTransactionSafeSigningPreview() = DappTransactionPreview(fromSafe.copy(busy = true))

@Preview
@Composable
private fun DappTransactionSafeQueuedPreview() = DappTransactionPreview(
    fromSafe.copy(quote = emptyList(), queued = "0x8f3c1b2a4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8"),
)

@Preview
@Composable
private fun DappTransactionSendingPreview() = DappTransactionPreview(payment.copy(busy = true))

@Preview
@Composable
private fun DappTransactionNotSentPreview() = DappTransactionPreview(
    payment.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now. Try again sends the same signed transaction, " +
                "not a new one.",
            canRetry = true,
        ),
        // No peer answered: it may be on its way, so Refuse is greyed out.
        refuseUnavailable = true,
    ),
)

@Preview
@Composable
private fun DappTransactionExplainingPreview() = DappTransactionPreview(
    payment.copy(
        quote = emptyList(),
        notQuoted = "Not enough ETH in this account for the most the fee can be and the value.",
        explaining = TransactionExplanation(
            "Cannot be sent",
            "Not enough ETH in this account for the most the fee can be and the value.",
        ),
    ),
)

@Preview
@Composable
private fun DappTransactionEndedPreview() = DappTransactionPreview(
    DappTransactionUiState(loading = false, ended = true, notice = DappTransactionViewModel.EXPIRED),
)

@Preview
@Composable
private fun DappTransactionLoadingPreview() = DappTransactionPreview(DappTransactionUiState())

/** Every preview above, which DappTransactionScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappTransactionScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("payment") { DappTransactionPaymentPreview() },
    ScreenPreview("payment-dark") { DappTransactionPaymentDarkPreview() },
    ScreenPreview("token-transfer") { DappTransactionTokenTransferPreview() },
    ScreenPreview("approval") { DappTransactionApprovalPreview() },
    ScreenPreview("not-decoded") { DappTransactionNotDecodedPreview() },
    ScreenPreview("described") { DappTransactionDescribedPreview() },
    ScreenPreview("described-dark") { DappTransactionDescribedDarkPreview() },
    ScreenPreview("described-nested") { DappTransactionDescribedNestedPreview() },
    ScreenPreview("described-partly") { DappTransactionDescribedPartlyPreview() },
    ScreenPreview("described-approval") { DappTransactionDescribedApprovalPreview() },
    ScreenPreview("described-unlisted") { DappTransactionDescribedUnlistedPreview() },
    ScreenPreview("describing") { DappTransactionDescribingPreview() },
    ScreenPreview("contract-changed") { DappTransactionContractChangedPreview() },
    ScreenPreview("code-not-checked") { DappTransactionCodeNotCheckedPreview() },
    ScreenPreview("not-as-described") { DappTransactionNotAsDescribedPreview() },
    ScreenPreview("quoting") { DappTransactionQuotingPreview() },
    ScreenPreview("not-quoted") { DappTransactionNotQuotedPreview() },
    ScreenPreview("refused") { DappTransactionRefusedPreview() },
    ScreenPreview("safe") { DappTransactionSafePreview() },
    ScreenPreview("safe-dark") { DappTransactionSafeDarkPreview() },
    ScreenPreview("safe-no-owner") { DappTransactionSafeNoOwnerPreview() },
    ScreenPreview("safe-signing") { DappTransactionSafeSigningPreview() },
    ScreenPreview("safe-queued") { DappTransactionSafeQueuedPreview() },
    ScreenPreview("sending") { DappTransactionSendingPreview() },
    ScreenPreview("not-sent") { DappTransactionNotSentPreview() },
    ScreenPreview("explaining") { DappTransactionExplainingPreview() },
    ScreenPreview("ended") { DappTransactionEndedPreview() },
    ScreenPreview("loading") { DappTransactionLoadingPreview() },
    ScreenPreview("real-funds") { DappTransactionRealFundsPreview() },
    ScreenPreview("real-funds-dark") { DappTransactionRealFundsDarkPreview() },
)
