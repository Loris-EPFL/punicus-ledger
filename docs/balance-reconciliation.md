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
  ones (`LastBalancesFile`). The head is not final: a check uses a proof only
  once its block is (*The log of proven balances*, below).
- **A balance at an old block: no, on a network without block access
  lists.** No peer-to-peer protocol serves proofs against old state (snap
  serves the newest blocks only), and an archive node's JSON-RPC is a provider
  (`.claude/rules/trust.md`). There the only balances a check can start from
  are the ones **this device proved, when it proved them**. Before the first
  proof of an account there is no balance to start from.
- **On a network with block access lists** (EIP-7928: Sepolia today) a
  block's BAL, committed in its header, gives an account's ETH balance after
  each transaction of any block that touched it. A token's balance it gives
  only where the token's balance storage slot is known. That makes ETH, at
  least, provable at the blocks the account was touched in.
- **Each movement in a verified row: yes, with what the engine reports.**
  Today Myotis's per-entry check reports a transaction's hash, sender,
  recipient, value, and **only the first** ERC-20 or ERC-721 `Transfer` the
  address is a party of. It reports neither the transaction's nonce, nor the
  receipt's status, nor the gas paid. Part 0 below asks Myotis for them.

## Three checks

### A. Sent transactions, by nonce (an EOA): exact, and pinned to its place

- An EOA's nonce counts the transactions it sent, and each sent transaction
  carries the nonce it used. The light client proves the nonce at the head.
- **But not only those, since EIP-7702.** The nonce also goes up when:
  - another account's type-4 transaction carries an authorization this
    account signed;
  - a transaction authorizes its own sender, which adds 2;
  - code the account delegates to runs `CREATE` or `CREATE2`.

  So check A is **not made over a range in which the account was delegated at
  any time**: a delegation proven at the head (`0xef0100…`), or any
  authorization of the account in the range (the index's `AUTH` role). An
  account that delegated and later cleared it has empty code again, yet code it
  delegated to may have run `CREATE` in between, each a nonce with no entry
  (D9).
- **An authorization listed is not an authorization applied.** EIP-7702 skips
  a tuple whose nonce, chain id or authority's code does not fit, and the
  transaction is mined all the same. The index's `AUTH` role says only that
  the account is an authority in it. So an authorization never counts as
  listing a nonce, and never starts the range.
- **Where the sent transactions come from.** The log index holds logs only, so
  it never sees a plain send, and its rows carry no nonce. The sources are the
  addr1 index's rows (a `FROM` entry) and the transactions sent from here that
  were mined; a dropped or replaced one does not count. So check A runs only
  where the addr1 index is on (the desktop and Android, its setting on) and
  claims to cover the range checked. On iOS it would flag every send made
  from another wallet.
- **Check:** the nonces of the sent transactions so listed must run without a
  gap from the first one listed to the newest one listed.
- **A gap is claimed only where nothing else explains it.** Between the one
  with nonce n (block X) and the one with nonce n + k (block Y), k − 1
  transactions are not listed. That is said as missing only when:
  - no entry between X and Y was left out (the engine could not verify it, or
    could not read its block this time: such an entry has no nonce, but the
    index holds it);
  - no coverage gap of the index lies between X and Y;
  - both rows are final (below).

  Otherwise the row says that entries there could not be verified, and names
  no one.
- **Above the newest one listed**, the difference to the nonce proven at the
  head reads "not listed yet" while the index has not reached the head (its
  head gap): a send from another wallet in those blocks is not in it yet.
- **Below the first one listed**, the count alone is known: the account sent
  n transactions the list does not hold. It is shown only where the index
  claims to cover the account from nonce 0 (its first transaction). Otherwise
  an account with a past older than the index would always show a count.
- It needs the transaction's nonce in the verified answer (Part 0). It is
  free otherwise: the nonce at the head is proven already, and counted only
  once its block is final.
- It says nothing of what came **in**: no counter counts that.

### B. Token balances between two proofs: exact for a standard token

- A standard ERC-20 balance changes only through `Transfer` logs. Between
  two proofs of it, at blocks a and b: balance(b) − balance(a) = Σ received −
  Σ sent, over the `Transfer`s naming the account in blocks a + 1 to b.
- **Not every token in the built-in list is standard in this.** WETH9 and
  WXDAI change a balance through `Deposit` and `Withdrawal`, with no
  `Transfer`, and every wrap the app offers is one. USDT's `issue`, `redeem`
  and `destroyBlackFunds` change balances without a `Transfer` too. Such a
  token is checked with those events decoded (the wrapped tokens), or marked
  not reconcilable (D6).
- **A bracket is checked only where the log index covers it.** The first proof
  of a balance is made when the account is added, and the log index walks from
  that block on ("since added"), reading every `Transfer` that names the
  account. But its walk can lag: a heavy download waits off a metered
  connection, and blocks that passed while the app was closed are covered only
  once it caught up. So a bracket from block a to b is checked only once the
  coverage the engine reports holds blocks a + 1 to b. Until then nothing is
  shown, never a warning. Within its coverage, a discrepancy points at the
  log index, the engine or the token, never at the importer. Check B cannot
  reach the history before the account was added. Only check A can, since a
  nonce counts every transaction ever sent.
- **The place is the bracket** between the two proofs. Where the app ran
  between them, proving the balance at every head (Detailed view), that is a
  few blocks; in simple mode, which proves on opening a screen
  (docs/simple-ui.md), it is the time between two openings.
- **Not reconcilable:** a token whose balance changes without a `Transfer`.
  That covers rebasing tokens such as stETH or aTokens, and a contract that
  mints or burns without the event. They are marked in the built-in list and
  never checked. An added token cannot be known to be standard. Whether it is
  checked is D10.
- It needs nothing from Myotis: the log index reads every log. It needs a log
  of proven balances, below. Where an addr1 row repeats a transfer the log
  index holds, the transaction is counted once, as the Activity already lists
  it once.

### C. The network's currency: exact only where the network has block access lists

- ETH also moves through what no listed row says:
  - **the fee** of every transaction the account sent (gas used × effective
    gas price, from the receipt);
  - **internal transfers**: ETH a contract call sends, such as a DEX's
    payout or an unwrap. The index records them only as `TRACE`, which the
    engine never verifies;
  - **validator withdrawals and fee-recipient tips**, for a block the account
    is named by.
- **EIP-7708 alone is not enough.** It logs ETH transfers, internal ones
  included, but not the fees, the validator withdrawals or the tips paid to a
  block's fee recipient. The log index cannot find those blocks either: a
  withdrawal or a fee recipient is in no bloom. Fees also take blob gas
  (blob gas used × blob gas price).
- **Where the network has block access lists** (EIP-7928: Sepolia today), the
  BAL of each block gives the account's ETH balance after each transaction,
  committed in the header. That makes check C exact, whatever moved the ETH:
  every block's change is proven, so a listed transaction explains it or it
  is a gap, pinned to its transaction.
- **Which blocks touched the account comes from the engine's address-activity
  follower** (`AddressActivity`, its `account` kind, Myotis #691), not from
  the per-entry check of the addr1 index. That check serves only the networks
  whose index a release pins a key for (Ethereum and Gnosis Chain since
  2026-11-26), on the desktop and Android, and answers only for entries the
  index holds, while
  a gap is a block no entry names. The follower reports, for a watched
  account, every block that touched it, and the blocks it could not check (its
  gaps), while the app runs.
- **So check C covers only the blocks the follower checked.** Across a gap in
  its coverage (the app was closed, the follower lagged) nothing is checked,
  never a warning.
- **Withdrawals and fee-recipient tips are named, not flagged.** No
  transaction credits a withdrawal, and a fee recipient is credited in every
  transaction of a block it proposed, so no listed row explains them. The
  block says what they are: the withdrawals are in its body, the fee recipient
  in its header. The check counts them as explained, each with a row of its
  own in the detail ("Validator withdrawal", "Fees received as the block's fee
  recipient"). An address that receives them would otherwise get a warning at
  every sweep.
- **Elsewhere (Ethereum and Gnosis Chain today)** an internal transfer, a
  withdrawal or a tip looks like a missing transaction, so a warning there
  would mostly be noise. The proposal: no ETH check on a network without block
  access lists (D3).
- It needs, from the follower, each touched block's BAL balances for the
  account, verified against its header, and the block's withdrawals and fee
  recipient (Part 0). Myotis already carries balances forward by those BALs.
  Receipts' gas then serves the detail ("of which 0.0021 ETH in fees"), not
  the check.

## The log of proven balances

- **New:** a local log per account and asset of (block, block hash, amount,
  head time). An entry is written at the first proof and whenever a proven
  balance changes; a proof that repeats the last amount writes nothing.
- **Reorganisations.** A balance is proven at a head that is not final, and its
  block can be replaced. A transfer can then move from block N to N + 1: the
  log would show it in one bracket and the list in the next, "+50 not
  explained" followed by "−50 not explained", with nothing missing. So a
  bracket is checked only once both its ends are finalized and still canonical
  (the hash kept is the finalized block's), over rows that are final, as
  docs/swaps.md proves a CoW order's end at a finalized block. An end that
  cannot be confirmed so (the app was closed before its block was finalized)
  is dropped, never checked. The same holds for check A's rows and nonce.
- It is public chain data, an app-private file like `LastBalancesFile`. But it
  is also a timeline of when this device looked at which account, so it stays
  out of backups and device transfers, and is never logged. Bounded, oldest
  dropped first (D4).
- **On a network with block access lists** (EIP-7928: Sepolia today) the
  engine carries a balance forward by the BALs of every block in between
  (Myotis 0.1.20, `verifyMethod` `blockAccessList`). There an ETH change is
  known to its transaction (check C). A token's change narrows that way only
  where its balance slot is known.

## How it is shown

- **In Activity, in its place.** A warning row between the rows, where its
  bracket falls by block and date. Detailed view: "Not explained by the
  activity listed: +0.42 USDC between block 21,140,002 (Nov 3, 14:02) and
  block 21,140,090 (14:20)". Simple mode: "Some activity may be missing here:
  your USDC grew by 0.42 more between 14:02 and 14:20 than the activity
  listed shows." The figure is net: 1.00 in and 0.58 out read alike, and the
  text says "grew" or "fell", never "came in". For a nonce gap: "2
  transactions sent from this account between these two are not listed",
  with no amount.
- **A tap opens what it rests on**, per check:
  - a balance (B, C): the two proven balances with their blocks, the sum of
    each direction listed between them, and what may explain it: for a token,
    a contract that moves balances without a `Transfer` (the likeliest cause);
    for ETH, a movement the block access lists show that no row says;
  - a nonce gap (A): the two listed transactions around it, with their nonces
    and blocks, the count not listed, and the range the index claims to cover.
- **Colour:** the caution colour (tertiary), not the error colour. The
  importer is warned against only for a nonce gap claimed missing (check A),
  in a range its index claims to cover (D5).
- **Never in totals, never acted on.** A warning changes no balance, no total
  and no review.

## Rule changes (the owner's)

- `.claude/rules/data-sources.md`: a section *The list's completeness is
  checked against what the light client proved*. It covers the three checks,
  that the figures are verified only, that a gap is the list's, and the log of
  proven balances and what it is.
- `.claude/rules/trust.md`, *The addr1 history*: a line that a provable gap
  is shown as one, beside the contradicted-entry warning. That section comes
  with `feature/addr1-activity`; it is not on `main` yet.
- docs/privacy-policy.md, in Part 2: the new local log, what it holds and that
  it stays on the device.
- docs/wallet-ux.md, screens 25 and 26, with the parts that show the checks.
- No change to the sources: nothing new is fetched.

## Decisions for the owner, each with a proposed answer

1. **D1: which checks, in which order.** Proposed: B (tokens) first, since it
   needs nothing from Myotis; then A (nonces), the one check that reaches the
   importer's past; then C (ETH) on networks with block access lists.
2. **D2: the addr1 index's rows count.** Proposed: yes, each transaction once.
   A row is verified, and check A needs the sent transactions it lists. For
   checks B and C they only repeat what the log index read.
3. **D3: ETH on Ethereum and Gnosis Chain.** Proposed: no check there until
   the network has block access lists (check C). Internal transfers verified
   alone (EIP-7708) are not enough. A warning that is mostly noise teaches the
   user to ignore warnings.
4. **D4: the log of proven balances.**
   - Proposed size: 10,000 entries in all, oldest dropped first.
   - Proposed lifetime: kept until the account is removed.
5. **D5: who is warned against.** Proposed: the importer for a nonce gap
   claimed missing (check A): in a range its index claims to cover, with no
   entry left out and no coverage gap there, between rows that are final. Then
   the gap is an entry its index lacks. Checks B and C name no one.
6. **D6: tokens that change balances without a `Transfer`.** Proposed:
   - a flag in the built-in token list, pinned in the release by the PR that
     sets it (whether a token rebases cannot be proven on chain), that marks a
     token as not reconcilable: rebasing tokens, and USDT;
   - the wrapped tokens (WETH9, WXDAI) checked with `Deposit` and
     `Withdrawal` decoded.
7. **D7: a Safe.** A Safe has its own nonce, and its executions are its own
   events in the log index. Proposed: check A for a Safe's nonce later, after
   the EOA.
8. **D8: a threshold.** Proposed: none. A discrepancy between two finalized,
   canonical ends is exact, so every one is shown. An amount too small to show
   reads "<0.0001", as elsewhere.
9. **D9: an account delegated by EIP-7702.** Proposed: no check A over a range
   in which the account was delegated at any time (a delegation at the head,
   or any authorization of it in the range), said in the detail. Code it
   delegated to may have created contracts, each a nonce with no entry.
10. **D10: added tokens.** Proposed: not checked by default. An added
    rebasing token would warn for ever, the noise D3 argues against.

## Delivery: `feature/balance-reconciliation`

It builds on the addr1 history: its rows, its rules and the Activity's rows.
`feature/balance-reconciliation` is cut from `main` before the first part is
coded (`.claude/skills/feature-branch/SKILL.md`), and `main` is merged into it
once `feature/addr1-activity` has landed there. Part 1 records the decisions
before any code lands (`.claude/rules/instructions.md`); Part 2 follows it in
the branch. Part 0 runs in Myotis meanwhile.

Parts, each its own PR into the feature branch, through the Workflow:

0. **Myotis** (a Myotis PR and release, then a bump in Punicus):
   - the address-activity follower reports, for each touched block of a
     watched account, its BAL balances after each transaction, the block's
     withdrawals to the account, and whether the account is its fee recipient
     (check C);
   - the verified answer of `verifyAddressHistoryEntries` gains:
     - the transaction's `nonce`;
     - the receipt's `status`, `gasUsed` and `effectiveGasPrice` (receipts
       fetched for a transaction entry, not only for its log roles);
     - **every** `Transfer` the address is a party of, so that a swap's row
       says both sides (only the first is reported today);
     - the block's `timestamp`.

   The timestamp also dates the addr1 rows, and `status` settles the "outcome
   not checked" values (#350).
1. **The rules and this page**, as the owner decides them.
2. **The log of proven balances**: a port in `:domain`, a file adapter in
   `:data`, fed where balances are proven today. No screen yet.
3. **Check B, tokens**: the use case and the warning rows in Activity and its
   detail, with previews.
4. **Check A, nonces**: after Part 0's bump, on the desktop and Android with
   the addr1 index on.
5. **Check C, ETH**: on networks with block access lists, over the blocks the
   address-activity follower checked, after Part 0's bump.
6. The final PR, `feature/balance-reconciliation` → `main`.

Parts 2 and 3 need nothing from Myotis and can start once the owner has decided
D1, D4, D6 and D10, recorded by Part 1. Part 0 can run in Myotis alongside
them.
