# Swarm nodes: the chequebook and postage stamps

A Swarm node (Bee, github.com/ethersphere/bee) has an ordinary EOA on Gnosis
Chain. It pays gas in xDAI and holds xBZZ, its chequebook pays the peers that
serve it, and its postage batches ("stamps") pay for what it stores. This page
is the contract for recognising such an account and for managing its
chequebook and its batches. It records the owner's decisions of 2026-11-23,
which `.claude/rules/trust.md` (*A Swarm node's chequebook and postage
batches*) and `.claude/rules/data-sources.md` (*Swarm nodes*) carry as rules.

## The owner's decisions (2026-11-23)

1. **A node's account is watched.** Punicus imports no `swarm.key`, and no raw
   key: a node's address comes in as any address does (docs/wallet-ux.md,
   screen 10, *Watch address*).
   Where the node's address is an account held here, as when Bee was started
   with a key of this wallet, its key signs what only the node may sign.
2. **Four actions are signed here:** buying a batch, topping one up,
   diluting one, and withdrawing from the chequebook. A deposit into the
   chequebook is a plain xBZZ transfer, which Send already makes.
3. **Found in the log index, and added by hand.** A node's chequebook and
   batches are found in its verified log index where it reaches back.
   Batches bought here are remembered. The user adds a chequebook by its
   address and a batch by its id. Each is proven on chain before it is shown.
4. **The plan lands through `feature/swarm`.** The owner allowed the session
   that asked to merge the part PRs into it (the feature-branch skill: the
   answer lives in that conversation, and a later session asks again). The
   PR into `main` is the owner's.

## The contracts

Bee v2.8.3 (2026-10-15) names them in `pkg/config/chain.go`, from
`go-storage-incentives-abi` v0.9.4 and `go-sw3-abi` v0.6.9. Each was read on
Gnosis Chain on 2026-11-23. Each is pinned in `:data` by address and by the
keccak-256 of its code, as Uniswap's and CoW's contracts are (docs/swaps.md,
*Pins*). A wrong pin makes the area unavailable, never wrong.

| Contract | Address | Code (keccak-256) |
|---|---|---|
| BZZ (xBZZ), the token: a bridged-token proxy | `0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da` | `3cc6bb329fa948d79cd1e0b616a4a0cc1faff6341730042d5fa5adfef76901e5` (286 bytes) |
| xBZZ's implementation, in the proxy's EIP-1967 slot | `0xf8d1677c8a0c961938bf2f9adc3f3cfda759a9d9` | `3c42cd875bf3a7c6ef70a1e3ff64377f4e9d35aa8db0695f6724a4f94e1f5c3f` |
| PostageStamp | `0x45a1502382541Cd610CC9068e88727426b696293` | `d9078a1ac32fa871bdb11be9d933a1b94e5081882565c5c6dd8a1b1cd3057145` |
| Chequebook factory (SimpleSwapFactory) | `0xc2d5a532cf69aa9a1378737d8ccdef884b6e7420` | `892333f95702916f17b7244e64a91ff89b0e789e208d681e7d7833b909b17065` |
| Chequebook master copy (ERC20SimpleSwap) | `0x3b6a7f0bdb6663007f95dbd7fddef815f7596715` | `3e3e840e87dc578cf44143cc2924f91886cecc0b71a58e6f368b6029b7c17960` |
| A chequebook: the EIP-1167 proxy of the master copy | each node's own | `81d3de06cadb0970fc653f24cef4689243f9a3d702236370ecf4613673048145`, Bee's own `AcceptedChequebookBytecodeHashes` |

- xBZZ is upgradeable: the bridge can point its proxy at new code. So the
  implementation slot is read with a verified storage proof, and its code
  is held to the pin, before an approval is quoted. Another implementation
  makes the four actions unavailable, saying so, until a release pins it.
  xBZZ is listed like any built-in token: its `symbol()` "BZZ" and
  `decimals()` 16 are checked when it is listed.
- Gnosis Chain has had one chequebook factory. The factory's `ERC20Address()`
  is BZZ and its `master()` the master copy above.
- Three retired PostageStamps still hold state (`0x6a1a…900e`, `0xa9c8…7c9A`
  and `0x30d1…F381`). They are not read: their batches are no longer served
  by the network, and two of them are paused.
- xBZZ joins Gnosis Chain's built-in token list. No Chainlink feed is pinned
  for it and Gnosis Chain pins no Uniswap, so it shows "no price" and stays
  out of totals.
- Gnosis Chain only. Swarm's testnet runs on Sepolia. It is open, the owner's
  to decide.

## Recognition

An EOA on Gnosis Chain is a Swarm node in the wallet when it has a
chequebook, or at least one batch, proven as below. No new kind of account
exists, and no new way to add one: the account stays the EOA it is. Its own
screen gets a **Swarm node** row that opens the Swarm screen
(docs/wallet-ux.md, *Swarm nodes*). An EOA on Gnosis Chain that is not
recognised has a **Swarm node…** item in its menu: it opens the same screen,
empty, to add a chequebook or a batch by hand.

### A chequebook is proven

A candidate address is the node's chequebook when all of these hold, read
with verified calls at one block (as docs/gnosis-pay.md, *Detection*, reads
the Delay's getters):

1. Its code, proven by the light client, hashes to the pinned chequebook
   proxy.
2. The pinned factory's `deployedContracts(candidate)` is true.
3. Its `issuer()` is the node, and its `token()` is BZZ.

Anyone can deploy a chequebook that names any issuer. Such a chequebook is
the node's to withdraw from all the same, so it is shown as one, never
trusted further than that.

### A batch is proven

A batch id is the node's when the pinned PostageStamp's `batches(id)` names
the node as its owner. A batch that `remainingBalance(id)` shows at zero has
expired, and is shown as expired until the contract deletes it; a batch the
contract no longer has (`BatchDoesNotExist`) is gone, and is said to be.

### Where the candidates come from

Bee finds both in its own state store; the chain alone cannot give them
from the address. The factory's `SimpleSwapDeployed` names the chequebook in
unindexed data, and a batch id is the hash of its buyer and a random nonce.
So:

- **The node's log index.** Bee funds its chequebook and pays for its batches
  with xBZZ from the node, so each leaves a `Transfer` that names the node as
  its sender (topic 1). For each one the account's log index holds:
  - to an address other than the PostageStamp: that address is a chequebook
    candidate;
  - to the PostageStamp: the transaction's receipt, verified against its
    block's receipts root (`ReceiptTracker`), holds the PostageStamp's
    `BatchCreated` or `BatchTopUp`, and its batch id is a candidate.
  The index covers an account from the block it was added at
  (`.claude/rules/data-sources.md`, *An account's history*), so a node added after its chequebook and its
  batches were made has nothing to find there. The screen says so, and
  offers adding by hand.
- **Bought here.** A batch bought from this wallet is remembered with its id,
  worked out locally: `keccak256(abi.encode(buyer, nonce))`.
- **Added by hand.** A chequebook by its address, a batch by its id: Bee's
  API and its dashboard show both. Kept once proven; refused, saying why,
  when not.

The candidates are kept per node in an app-private file: public data, not
key material. Punicus never calls a Bee node's API, nor any other Swarm
service: the user copies what Bee shows.

## What the Swarm screen shows

Each value is a verified call at one block, named on the screen, or "not
available". A reading that could not be verified at the latest block is
shown as any older answer is: dated, marked as possibly changed since, and
never acted on. Whether it stays for an hour as the Gnosis Pay screen's
does (docs/gnosis-pay.md, *A read that did not answer*) is open, the
owner's to decide.

- **The node:** its xDAI and xBZZ, as on its own screen.
- **The chequebook:** its address, `balance()`, `liquidBalance()` (what is
  not hard-deposited to a peer), `totalPaidOut()`, and `bounced()`: once a
  cheque of it bounced, peers know, and the screen says so.
- **Each batch:** its id, depth and bucket depth, whether it is immutable,
  its theoretical capacity (`2^depth` chunks of 4 KiB), its remaining balance
  per chunk, and its time to live.
- **The time to live** is the remaining balance per chunk divided by the
  contract's `lastPrice()`, in blocks of 5 seconds, as Bee's own
  `estimateBatchTTL` works it out. The price is set by Swarm's price oracle
  and changes, so the screen says "at the current price".
- **Unpriced.** No fiat value is shown for xBZZ (above). Prices stay
  display-only in any case: the postage price is the contract's state, not
  a price feed, and what is signed is in token units.

## The four actions

Each is a transaction from a key held here on Gnosis Chain, quoted, simulated
and signed like any other (`CallQuoter`), at the normal fee level, never on a
quote over a minute old. Only to the pinned contracts, after a fresh read
proves their code. Strict in both modes: none is drafted in lenient mode for
now. Each review names what is signed in xBZZ, what it does, the fee, and
the blocks its figures were read at.

### Buy a batch

- **Who pays:** any account held here on Gnosis Chain, chosen on the form.
  It is the batch's buyer.
- **Who owns it:** the node. Bee uses only the batches its own address owns.
- **The form:** the depth, offered from 17 (Bee refuses less) to 30, each
  named with its theoretical capacity; the time to live, from which the
  amount per chunk is worked out at the verified `lastPrice()`, never below
  the contract's `minimumInitialBalancePerChunk()`; and whether the batch is
  immutable, off by default. Bucket depth 16, as Bee buys.
- **What it costs:** the amount per chunk times `2^depth`, in xBZZ, said in
  full before anything is signed.
- **Two transactions.** First `approve(PostageStamp, exactly that total)`
  from the buyer, never an unlimited allowance, reset to zero first only
  where the token needs it (as `QuoteTokenApproval` does for a swap). Then,
  once the approval is mined, `createBatch(node, amountPerChunk, depth, 16,
  nonce, immutable)` with a nonce of 32 random bytes made here. The batch id
  is remembered as it is sent.
- An allowance that already covers the total skips the approval.
- **A paused PostageStamp** greys out Buy, Top up and Dilute, saying so:
  `createBatch`, `topUp` and `increaseDepth` all revert while it is paused.

### Top up a batch

- Any account held here on Gnosis Chain pays: the contract lets anyone top
  up any batch.
- The form takes the time to add, works out the amount per chunk at the
  verified `lastPrice()`, and says the total: that amount times `2^depth`.
- Approve, then `topUp(batchId, amountPerChunk)`, as for a purchase.
- Greyed out for a batch that has expired, saying so: the contract refuses
  it too, and a new batch is the way on.
- Refused where the time left after it would still be below the contract's
  minimum of a day.

### Dilute a batch

- `increaseDepth(batchId, newDepth)`: only the batch's owner, the node, may
  send it. Offered only where the node's key is held here on Gnosis Chain;
  otherwise greyed out, saying that the node's own key signs it.
- It doubles the capacity for each step of depth and halves the time to
  live: the review shows both, before and after. No xBZZ moves.
- Greyed out for a batch that has expired, saying so.
- Refused where the time left after it would fall below the contract's
  minimum, which the simulation proves.
- An immutable batch may be diluted: the contract does not refuse it. The
  review says what immutable means: once full, the batch takes no more
  chunks, where a mutable one overwrites its oldest.

### Withdraw from the chequebook

- `withdraw(amount)`: only the issuer, the node, may send it, and the xBZZ
  always goes to the node's address. Offered only where the node's key is
  held here on Gnosis Chain; otherwise greyed out, saying so.
- Up to `liquidBalance()`, read fresh at the review.
- **The review warns:** the node may have issued cheques that are not cashed
  yet. Punicus cannot see them: they live in Bee. A cheque that cannot be
  paid in full bounces, the chequebook stays marked `bounced` for good, and
  peers may stop serving the node.

### Not part of this

- Cashing cheques. They are signed off chain and held by Bee; Punicus never
  asks a Bee node for them.
- Staking, the redistribution game, and the node's overlay address.
- Deploying a chequebook. Bee deploys its own at its first start.

## History

Each action is a `SentAction` of its own, so Activity names it ("Bought a
postage batch", "Topped up batch 0x…", "Diluted batch 0x… to depth 22",
"Withdrew from the chequebook"), and the transfer status follows it as any
transaction.

## Delivery

Through `feature/swarm`, one part per PR:

1. This page, and the rules that carry the decisions.
2. Domain and adapters: the pins, xBZZ in the token list, the verified
   reads of a chequebook and a batch, the candidates' store and their
   discovery in the log index.
3. The Swarm screen, read only: the node, the chequebook, the batches,
   adding by hand, and the entries from the account's screen.
4. The batches' transactions: buy, top up, dilute.
5. The chequebook's withdrawal, and Activity's rows.
6. `feature/swarm` into `main`, the owner's to merge.
