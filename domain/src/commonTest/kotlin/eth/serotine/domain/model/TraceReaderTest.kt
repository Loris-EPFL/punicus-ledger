package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [TraceReader]'s reading of a verified trace's effects (docs/clear-signing.md, *Phase B*, B2). */
class TraceReaderTest {

    private val user = address("aa")
    private val router = address("01")
    private val pool = address("02")
    private val token = address("03")
    private val other = address("04")
    private val weth = address("05")
    private val proxy = address("06")
    private val implementation = address("07")
    private val permit2 = TypedDataMeaning.PERMIT2

    private fun read(root: TraceFrame) = TraceReader.read(user, root, weth, permit2)

    private fun call(from: Address, to: Address, value: Long = 0, vararg calls: TraceFrame, logs: List<TraceLog> = emptyList(), error: String? = null) =
        TraceFrame(TraceFrame.Type.CALL, from, to, UInt256.of(value), error = error, logs = logs, calls = calls.toList())

    @Test
    fun theCurrencyMovesWithCallsAndCreationsButNotWithDelegateCalls() {
        val root = call(
            user, router, 5,
            call(router, pool, 3),
            TraceFrame(TraceFrame.Type.DELEGATECALL, router, implementation, UInt256.of(5)),
            call(pool, user, 2),
        )
        val effects = read(root).effects
        assertEquals(
            listOf(
                TracedMove(TracedAsset.Currency, UInt256.of(5), user, router),
                TracedMove(TracedAsset.Currency, UInt256.of(2), pool, user),
            ),
            effects.moves,
        )
        assertEquals(listOf(ReachedContract(router, false), ReachedContract(pool, false), ReachedContract(implementation, true)), effects.contracts)
    }

    @Test
    fun aFailedFrameAndEverythingUnderItDoesNotCount() {
        val root = call(
            user, router, 0,
            call(router, pool, 0, call(pool, user, 7), logs = listOf(transfer(token, user, pool, 9)), error = "execution reverted"),
            call(router, other, 0, logs = listOf(transfer(token, user, other, 1))),
        )
        assertEquals(listOf(TracedMove(TracedAsset.Fungible(token), UInt256.of(1), user, other)), read(root).effects.moves)
    }

    @Test
    fun aRevertedCallMovesNothingAndSaysWhy() {
        val root = call(user, router, 5, logs = listOf(transfer(token, user, other, 1)), error = "execution reverted")
            .copy(revertReason = "Too little received")
        val effects = read(root).effects
        assertEquals(TracedRevert("execution reverted", "Too little received"), effects.revert)
        assertEquals(emptyList(), effects.moves)
        assertEquals(listOf(ReachedContract(router, false)), effects.contracts)
    }

    @Test
    fun tokensAreToldApartByTheirEvents() {
        val nft = TraceLog(token, listOf(TRANSFER, word(user), word(other), uint(42)), "")
        val single = TraceLog(other, listOf(TRANSFER_SINGLE, word(router), word(user), word(pool)), uint(7) + uint(3))
        val batch = TraceLog(
            other, listOf(TRANSFER_BATCH, word(router), word(pool), word(user)),
            uint(64) + uint(160) + uint(2) + uint(1) + uint(2) + uint(2) + uint(10) + uint(20),
        )
        val effects = read(call(user, router, 0, logs = listOf(nft, single, batch))).effects
        assertEquals(
            listOf(
                TracedMove(TracedAsset.Nft(token, UInt256.of(42), multi = false), UInt256.ONE, user, other),
                TracedMove(TracedAsset.Nft(other, UInt256.of(7), multi = true), UInt256.of(3), user, pool),
                TracedMove(TracedAsset.Nft(other, UInt256.of(1), multi = true), UInt256.of(10), pool, user),
                TracedMove(TracedAsset.Nft(other, UInt256.of(2), multi = true), UInt256.of(20), pool, user),
            ),
            effects.moves,
        )
        assertEquals(0, effects.unreadLogs)
    }

    @Test
    fun aLogThatIsNotItsEventsEncodingIsCountedAsUnread() {
        val short = TraceLog(token, listOf(TRANSFER, word(user), word(other)), "00")
        val dirty = TraceLog(token, listOf(TRANSFER, "ff".repeat(12) + "aa".repeat(20), word(other)), uint(1))
        val batch = TraceLog(other, listOf(TRANSFER_BATCH, word(router), word(pool), word(user)), uint(64) + uint(96) + uint(1) + uint(1))
        val effects = read(call(user, router, 0, logs = listOf(short, dirty, batch))).effects
        assertEquals(3, effects.unreadLogs)
        assertEquals(emptyList(), effects.moves)
    }

    @Test
    fun wrappedCurrencyIsReadFromItsPinnedContractsDepositAndWithdrawal() {
        val deposit = TraceLog(weth, listOf(DEPOSIT, word(user)), uint(4))
        val withdrawal = TraceLog(weth, listOf(WITHDRAWAL, word(user)), uint(1))
        val impostor = TraceLog(other, listOf(DEPOSIT, word(user)), uint(100))
        val effects = read(call(user, weth, 4, logs = listOf(deposit, withdrawal, impostor))).effects
        assertEquals(
            listOf(
                TracedMove(TracedAsset.Currency, UInt256.of(4), user, weth),
                TracedMove(TracedAsset.Fungible(weth), UInt256.of(4), weth, user),
                TracedMove(TracedAsset.Fungible(weth), UInt256.of(1), user, weth),
            ),
            effects.moves,
        )
    }

    @Test
    fun anApprovalIsARightUnlessItsSpenderIsSpendingIt() {
        val granted = approval(token, user, router, 100)
        // The router's transferFrom logs the allowance it leaves: a spend, not a grant.
        val spent = approval(token, user, router, 40)
        val revoked = approval(token, user, other, 0)
        val forAll = TraceLog(other, listOf(APPROVAL_FOR_ALL, word(user), word(pool)), uint(1))
        val notForAll = TraceLog(other, listOf(APPROVAL_FOR_ALL, word(user), word(router)), uint(0))
        val someoneElses = approval(token, other, router, 5)
        val root = call(
            user, router, 0,
            call(router, token, 0, logs = listOf(spent, transfer(token, user, pool, 60))),
            logs = listOf(granted, revoked, forAll, notForAll, someoneElses),
        )
        assertEquals(
            listOf(TracedRight.Allowance(token, router, UInt256.of(100)), TracedRight.AllNfts(other, pool)),
            read(root).effects.rights,
        )
    }

    @Test
    fun aPermitRelayedByItsSpenderIsAGrant() {
        // The spender calls permit on the token: an Approval alone in a frame it opened.
        val relayed = call(user, router, 0, call(router, token, 0, logs = listOf(approval(token, user, router, 70))))
        assertEquals(listOf(TracedRight.Allowance(token, router, UInt256.of(70))), read(relayed).effects.rights)
        // Its transferFrom moves the token in the same frame: a spend.
        val spent = call(user, router, 0, call(router, token, 0, logs = listOf(approval(token, user, router, 30), transfer(token, user, router, 40))))
        assertEquals(emptyList(), read(spent).effects.rights)
    }

    @Test
    fun theNetworksOwnLogOfACurrencyTransferIsNoToken() {
        val eip7708 = transfer(TokenTransfers.ETH, user, router, 5)
        val effects = read(call(user, router, 5, logs = listOf(eip7708))).effects
        assertEquals(listOf(TracedMove(TracedAsset.Currency, UInt256.of(5), user, router)), effects.moves)
    }

    @Test
    fun aDelegateCallKeepsItsCallersSender() {
        // A proxied token: the spender calls the proxy, which delegates to its implementation, which logs.
        val spent = approval(proxy, user, router, 40)
        val root = call(
            user, router, 0,
            call(router, proxy, 0, TraceFrame(TraceFrame.Type.DELEGATECALL, proxy, implementation, null, logs = listOf(spent, transfer(proxy, user, router, 40)))),
        )
        assertEquals(emptyList(), read(root).effects.rights)
        assertEquals(listOf(ReachedContract(router, false), ReachedContract(proxy, false), ReachedContract(implementation, true)), read(root).effects.contracts)
    }

    @Test
    fun permit2sRightsCountFromThePinnedPermit2Only() {
        val approve = TraceLog(permit2, listOf(PERMIT2_APPROVAL, word(user), word(token), word(router)), uint(50) + uint(1_800_000_000))
        val permit = TraceLog(permit2, listOf(PERMIT2_PERMIT, word(user), word(other), word(router)), uint(60) + uint(1_900_000_000) + uint(3))
        val impostor = TraceLog(other, listOf(PERMIT2_APPROVAL, word(user), word(token), word(router)), uint(50) + uint(1))
        val rights = read(call(user, permit2, 0, logs = listOf(approve, permit, impostor))).effects.rights
        assertEquals(
            listOf(
                TracedRight.Permit2(permit2, router, token, UInt256.of(50), 1_800_000_000),
                TracedRight.Permit2(permit2, router, other, UInt256.of(60), 1_900_000_000),
            ),
            rights,
        )
    }

    @Test
    fun theMovesBetweenOthersAreKeptForTheRecipientCheck() {
        val root = call(
            user, router, 0,
            logs = listOf(transfer(token, user, pool, 10), transfer(other, pool, router, 5), transfer(other, router, user, 5)),
        )
        val reading = read(root)
        assertEquals(3, reading.allMoves.size)
        assertEquals(2, reading.effects.moves.size)
        assertNull(reading.effects.revert)
    }

    @Test
    fun theEventTopicsAreTheStandardOnes() {
        assertEquals("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", TRANSFER)
        assertEquals("8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925", APPROVAL)
    }

    @Test
    fun precompilesAreNotContractsShown() {
        val root = call(user, router, 0, TraceFrame(TraceFrame.Type.STATICCALL, router, Address.of("0x" + "0".repeat(39) + "1"), null))
        assertEquals(listOf(ReachedContract(router, false)), read(root).effects.contracts)
    }

    companion object {
        fun address(byte: String) = Address.of("0x" + byte.repeat(20))
        fun word(address: Address) = "0".repeat(24) + address.hex.removePrefix("0x")
        fun uint(value: Long) = UInt256.of(value).toBytes32().toHex()
        fun topic(signature: String) = keccak256(signature.encodeToByteArray()).toHex()

        fun transfer(token: Address, from: Address, to: Address, amount: Long) =
            TraceLog(token, listOf(TRANSFER, word(from), word(to)), uint(amount))

        fun approval(token: Address, owner: Address, spender: Address, amount: Long) =
            TraceLog(token, listOf(APPROVAL, word(owner), word(spender)), uint(amount))

        val TRANSFER = topic("Transfer(address,address,uint256)")
        val APPROVAL = topic("Approval(address,address,uint256)")
        val APPROVAL_FOR_ALL = topic("ApprovalForAll(address,address,bool)")
        val TRANSFER_SINGLE = topic("TransferSingle(address,address,address,uint256,uint256)")
        val TRANSFER_BATCH = topic("TransferBatch(address,address,address,uint256[],uint256[])")
        val DEPOSIT = topic("Deposit(address,uint256)")
        val WITHDRAWAL = topic("Withdrawal(address,uint256)")
        val PERMIT2_APPROVAL = topic("Approval(address,address,address,uint160,uint48)")
        val PERMIT2_PERMIT = topic("Permit(address,address,address,uint160,uint48,uint48)")
    }
}
