# Balance reconciliation: a warning where the Activity list cannot add up

Drawn 2026-11-24 at the owner's request. **A proposal: nothing on this page is
decided yet.** The rules under `.claude/` stand until the owner rules; the
rulings then go into `.claude/rules/data-sources.md` and `trust.md` with the
first part, as *Rule changes* below lists.

## The request

The owner's words (2026-11-24), lightly edited:

> Since we cannot verify completeness: what about a warning if there is a
> discrepancy between the initial balances and the current balances after
> adding up all incoming and outgoing transactions? We could even point out
> where and when a transaction is missing and how much it was.

The context is the addr1 history (#334, `feature/addr1-activity`). An entry
of the index is shown only once the engine verified it, but **that no entry
is missing rests on the importer's word** (owner ruling, 2026-11-21, #334).
This page proposes checks that turn a missing entry into something the app
can prove, from verified figures alone.

## The one rule this design rests on: only verified figures, and the gap is the list's

- **Every number a check uses is verified**: balances and nonces the light
  client proved, and movements from verified rows (the log index's, the addr1
  index's, the transactions sent from here). Nothing is estimated, and no
  other source is asked.
- **What the checks can reach.** The importer's completeness of the past, before
  this device first proved the account, is tested only by the nonce (check A),
  and only for what the account sent. Balances (checks B and C) test the list
  from the first proof on, where the log index already reads every log. So
  they catch a gap of the log index, of the engine, or of a token that changes
  balances without a log. What the account received before it was added stays
  on the importer's word, as now.
- **A discrepancy is a fact about the list, never about the chain.** "The
  verified balance moved by 0.42 ETH more than the listed activity explains"
  is provable. "A transaction is missing" is the likely reading, and the text
  says it that way. It never says that a balance is wrong.
- **A check that cannot be made is not shown**, nor is one whose figures are
  not all verified. No check rather than a guessed one. A check that passes
  says so only where the user looks for it (the detail), never as a promise
  of completeness.
- Why: the checks add no trust. They catch what the trusted parts already
  prove, and they point at gaps the importer's word left open.

## What can be proven, and what cannot

- **A balance and a nonce at the head: yes.** The light client proves them
  (`VerifiedNativeBalances`, the token balances), and the app keeps the last
  ones (`LastBalancesFile`).
- **A balance at an old block: no.** No peer-to-peer protocol serves proofs
  against old state (snap serves the newest blocks only), and an archive
  node's JSON-RPC is a provider (`.claude/rules/trust.md`). So the only
  balances a check can start from are the ones **this device proved, when it
  proved them**. Before the first proof of an account there is no balance to
  start from.
- **Each movement in a verified row: yes, with what the engine reports.**
  Today Myotis's per-entry check reports a transaction's hash, sender,
  recipient, value, and **only the first** ERC-20 or ERC-721 `Transfer` the
  address is a party of. It reports neither the transaction's nonce, nor the
  receipt's status, nor the gas paid. Part 0 below asks Myotis for them.

## Three checks

### A. Sent transactions, by nonce (an EOA): exact, and pinned to its place

- An EOA's nonce counts the transactions it sent, and each sent transaction
  carries the nonce it used. The light client proves the nonce at the head.
- **Check:** the nonces of the sent transactions the list holds (from here,
  from the log index, from the addr1 index) must run without a gap from the
  first one listed to the proven nonce less one.
- **Each gap is a missing sent transaction, pinned exactly:** after the one
  with nonce n, in block X at its time, and before the one with nonce n + k,
  in block Y. That makes k − 1 transactions, each of an amount not known.
- **Below the first one listed**, the count alone is known: the account sent
  n transactions the list does not hold. When the index covers the account
  from its first transaction, that count must be 0.
- It needs the transaction's nonce in the verified answer (Part 0). It is
  free otherwise: the nonce at the head is proven already.
- It says nothing of what came **in**: no counter counts that.

### B. Token balances between two proofs: exact for a standard token

- A standard ERC-20 balance changes only through `Transfer` logs. Between
  two proofs of it, at blocks a and b: balance(b) − balance(a) = Σ received −
  Σ sent, over the `Transfer`s naming the account in blocks a + 1 to b.
- **A bracket always lies within the log index's coverage.** The first proof
  of a balance is made when the account is added, and the log index walks from
  that block on ("since added"), reading every `Transfer` that names the
  account. So a discrepancy points at the log index, the engine or the token,
  never at the importer. Check B cannot reach the history before the account
  was added. Only check A can, since a nonce counts every transaction ever
  sent.
- **The place is the bracket** between the two proofs. Where the app ran
  between them, proving the balance at every head (Detailed view), that is a
  few blocks; in simple mode, which proves on opening a screen
  (docs/simple-ui.md), it is the time between two openings.
- **Not reconcilable:** a token whose balance changes without a `Transfer`.
  That covers rebasing tokens such as stETH or aTokens, and a contract that
  mints or burns without the event. They are marked in the built-in list and
  never checked. An added token is checked, and its warning says that its
  contract may simply not log every change.
- It needs nothing from Myotis: the log index reads every log. It needs a log
  of proven balances, below. Where an addr1 row repeats a transfer the log
  index holds, the transaction is counted once, as the Activity already lists
  it once.

### C. The network's currency between two proofs: exact only where the network logs it

- ETH also moves through what no listed row says:
  - **the fee** of every transaction the account sent (gas used × effective
    gas price, from the receipt);
  - **internal transfers**: ETH a contract call sends, such as a DEX's
    payout or an unwrap. The index records them only as `TRACE`, which the
    engine never verifies;
  - **validator withdrawals and fee-recipient tips**, for a block the account
    is named by.
- **Where the network logs every ETH transfer** (EIP-7708: Sepolia today),
  internal transfers are logs like any other, and the check is as exact as
  for a token, the fees subtracted.
- **Elsewhere (Ethereum and Gnosis Chain today)** an internal transfer looks
  like a missing transaction, so a warning there would mostly be noise. The
  proposal: no ETH check on a network without EIP-7708, until the engine
  reports internal transfers verified (D3).
- It needs each sent transaction's receipt status and gas from the engine
  (Part 0), for the transactions sent from here too.

## The log of proven balances

- **New:** a local log per account and asset of (block, amount, head time).
  An entry is written at the first proof and whenever a proven balance
  changes; a proof that repeats the last amount writes nothing.
- It is public chain data, an app-private file like `LastBalancesFile`. But it
  is also a timeline of when this device looked at which account, so it stays
  out of backups and device transfers, and is never logged. Bounded, oldest
  dropped first (D4).
- **On a network with block access lists** (EIP-7928: Sepolia today) the
  engine carries a balance forward by the BALs of every block in between
  (Myotis 0.1.20, `verifyMethod` `blockAccessList`). There a change is known
  to its block, and the bracket is that one block.

## How it is shown

- **In Activity, in its place.** A warning row between the rows, where its
  bracket falls by block and date. Detailed view: "Not explained by the
  activity listed: +0.42 USDC between block 21,140,002 (Nov 3, 14:02) and
  block 21,140,090 (14:20)". Simple mode: "Some activity is missing here:
  +0.42 USDC came in between 14:02 and 14:20." For a nonce gap: "2 transactions
  sent from this account between these two are not listed", with no amount.
- **A tap opens what it rests on**: the two proven balances with their blocks,
  the sum of each direction listed between them, and what may explain it:
  - an entry the addr1 index left out, counted;
  - a range the index does not cover;
  - for ETH, an internal transfer.
- **Colour:** the caution colour (tertiary), not the error colour. The
  importer is warned against only for a nonce gap in a range its index claims
  to cover, before the log index's coverage (D5).
- **Never in totals, never acted on.** A warning changes no balance, no total
  and no review.

## Rule changes (the owner's)

- `.claude/rules/data-sources.md`: a section *The list's completeness is
  checked against what the light client proved*. It covers the three checks,
  that the figures are verified only, that a gap is the list's, and the log of
  proven balances and what it is.
- `.claude/rules/trust.md`, *The addr1 history*: a line that a provable gap
  is shown as one, beside the contradicted-entry warning.
- docs/wallet-ux.md, screens 25 and 26, and docs/privacy-policy.md (the new
  local log) with the parts that land them.
- No change to the sources: nothing new is fetched.

## Decisions for the owner, each with a proposed answer

1. **D1: which checks, in which order.** Proposed: B (tokens) first, since it
   needs nothing from Myotis; then A (nonces), the one check that reaches the
   importer's past; then C (ETH) on EIP-7708 networks.
2. **D2: the addr1 index's rows count.** Proposed: yes, each transaction once.
   A row is verified, and check A needs the sent transactions it lists. For
   checks B and C they only repeat what the log index read.
3. **D3: ETH on Ethereum and Gnosis Chain.** Proposed: no check there until
   the engine reports internal transfers verified. A warning that is mostly
   noise teaches the user to ignore warnings.
4. **D4: the log of proven balances.**
   - Proposed size: 10,000 entries in all, oldest dropped first.
   - Proposed lifetime: kept until the account is removed.
5. **D5: who is warned against.** Proposed: the importer only for a nonce gap
   (check A) in a range the addr1 index claims to cover, before the log
   index's coverage. Anywhere else the warning names no one.
6. **D6: rebasing tokens.** Proposed: a flag in the built-in token list,
   proven like the rest of an entry, that marks a token as not reconcilable.
7. **D7: a Safe.** A Safe has its own nonce, and its executions are its own
   events in the log index. Proposed: check A for a Safe's nonce later, after
   the EOA.
8. **D8: a threshold.** Proposed: none. Every discrepancy is shown, since it
   is exact. An amount too small to show reads "<0.0001", as elsewhere.

## Delivery: `feature/balance-reconciliation`

Parts, each its own PR into the feature branch, through the Workflow:

0. **Myotis** (a Myotis PR and release, then a bump in Punicus): the verified
   answer of `verifyAddressHistoryEntries` gains:
   - the transaction's `nonce`;
   - the receipt's `status`, `gasUsed` and `effectiveGasPrice` (receipts
     fetched for a transaction entry, not only for its log roles);
   - **every** `Transfer` the address is a party of, so that a swap's row says
     both sides (only the first is reported today);
   - the block's `timestamp`.

   The last also dates the addr1 rows, and `status` settles the "outcome not
   checked" values (#350).
1. **The rules and this page**, as the owner decides them.
2. **The log of proven balances**: a port in `:domain`, a file adapter in
   `:data`, fed where balances are proven today. No screen yet.
3. **Check B, tokens**: the use case and the warning rows in Activity and its
   detail, with previews.
4. **Check A, nonces**: after Part 0's bump.
5. **Check C, ETH**: on EIP-7708 networks, after Part 0's bump.
6. The final PR, `feature/balance-reconciliation` → `main`.

Parts 2 and 3 need nothing from Myotis and can start once the owner has decided
D1, D4 and D6. Part 0 can run in Myotis alongside them.
