---
description: "Data sources. The embedded engine is the only source of chain data; the three networks; Chainlink prices; the built-in token list; the built-in clear-signing descriptors; Safe co-signing without a service; history from the log index, and verified addr1 rows from Swarm on the desktop and Android; an account's NFTs, on chain only; swaps on Uniswap and CoW; CoW's order API; dApp connections over WalletConnect."
paths:
  - "domain/**/*"
  - "data/**/*"
  - "ui/**/*"
  - "android-app/**/*"
  - "desktop-app/**/*"
  - "ios-app/**/*"
  - "native/**/*"
  - "*.kts"
  - "docs/**/*"
---

# Data sources

Loaded for every code module and for `docs/`. docs/wallet-ux.md applies these
rulings to the screens.

## The engine is the only source of chain data

The embedded Myotis engine (devp2p/libp2p inside it). Calling a node over
HTTP may be used for debugging only. It is never a production path (the
Myotis rule, carried over).

## Networks: Ethereum and Gnosis Chain beside Sepolia, one chain per account

(Owner ruling, 2026-10-22, accepting docs/networks.md. It replaces "Sepolia
only" of 2026-10-15.)

- The networks are the values of `eth.serotine.domain.model.Network`:
  Ethereum (chain id 1), Gnosis Chain (100) and Sepolia (11155111). The parts
  of `feature/networks` brought them (docs/networks.md, *Delivery*).
- **There is no current network.** Each account belongs to one chain and is
  named by its network and address (`AccountId`). Every chain port takes the
  network with each call. A network no adapter serves is refused, never
  answered from another one. A screen takes its network from the account it
  shows.
- The same EOA on two networks is two accounts that share one key. The key
  is deleted only with the last account at that address held here.
- **Each network can be turned off** (Settings → Networks). A network that is
  off runs no light client. Its accounts stay listed, their actions greyed
  out with "Turn on" as the way to get them.
- Defaults: a release build starts with Ethereum and Gnosis Chain on and
  Sepolia off; a debug build with Sepolia alone. Each network gets its
  build's default the first time a start finds it unrecorded. The user's
  choice stands from then on.
- **Sepolia keeps every testnet warning** and is never added to a total with
  real funds.
- The plan's open questions are decided as docs/networks.md proposes (the
  same ruling):
  - no names on Gnosis Chain, which watches and sends to addresses only;
  - xDAI priced by Chainlink's xDAI/USD feed on Gnosis, or DAI/USD read on
    Gnosis where there is none;
  - the update screen only when every network with accounts is blocked; a
    network blocked alone turns its own section red;
  - the display currencies: any network with accounts can price, said per
    section where one cannot;
  - SafeL2 1.4.1 created on Gnosis Chain, the L1 singleton on Ethereum and
    Sepolia;
  - Swap greyed out on Gnosis Chain until a venue is pinned there (both
    are since 2026-11-23, at the owner's request);
  - the network step starts with nothing ticked;
  - Sepolia off after an update from the Sepolia-only release;
  - Receive kept on a network that is off, saying arrivals show once it is
    on;
  - no extra real-funds notice beyond the network step's "Real funds" line;
  - the built-in token lists of Ethereum and Gnosis Chain from the plan's
    candidates, each entry proven on its chain by the PR that pins it.
- Adding a network beyond these three is the owner's decision and is recorded
  here first.

## Checkpoint servers: a fresh checkpoint, only at a stale checkpoint and on the user's tap

(Owner's request, 2026-11-14, applying Myotis's carve-out of 2026-11-10:
"at a `STALE_ANCHOR` park and only on the user's request, a fresh checkpoint
that every answering checkpoint-sync server names".)

- The engine reads one HTTPS source beside its peers: the network's public
  checkpoint-sync servers (Checkpointz), at pinned URLs, through its own HTTP
  client. Punicus adds no client and no server of its own.
- Only while a network is parked on a checkpoint past the weak-subjectivity
  bound, and only when the user taps *Fetch a fresh checkpoint*
  (`.claude/rules/trust.md`). No other path calls it.
- The servers see the device's IP address and that it asks for a checkpoint
  of that network. They learn no account. The privacy policy says so.
- What they answer is a trust anchor, never chain state: no balance, nonce
  or transaction comes from it. Every read after it is verified against it
  as usual.

## Prices come from Chainlink aggregators, read through the light client

(Owner ruling, 2026-10-16.)

- A price is a verified `eth_call` of a pinned aggregator's
  `latestRoundData()` and `decimals()`: on-chain state like any other. There
  is no HTTP price API.
- On Sepolia these are Chainlink's Sepolia feeds, and every fiat value says
  "testnet prices".
- A token without a feed shows "no price" and is left out of every total,
  unless the pool fallback below prices it.
- **Prices are display-only.** An amount is entered, reviewed and signed in
  token units, never derived from a price. The light client proves what the
  aggregator says, not that the oracles are right. That is why a price never
  reaches a signature.
- One exception: the fallback to a pool's average, below (owner ruling,
  2026-10-29, #205). It excepts Chainlink-only prices, not display-only.

## A Uniswap pool's average prices a token where no feed is pinned

(Owner ruling, 2026-10-29, #205. It narrows *Prices come from Chainlink
aggregators*: on the screens, in the totals and on the home-screen widget,
labelled and counted in totals, over a 30-minute window, and never in place
of a stale feed.)

- A pinned Chainlink feed always wins. Where a price id has one on the
  network, it prices the token everywhere. A feed that is stale is shown as
  stale. It is never replaced by a pool's average.
- Only where the network pins no feed for a price id, a Uniswap v3
  time-weighted average price (TWAP) of a pinned pool prices it. Today that
  is Ethereum, for COW and SAFE. Gnosis Chain pins no Uniswap pool for prices,
  and Sepolia has no market.
- The average is a verified call: `observe` of a pinned pool. The pool is a
  pool only when the pinned factory's `getPool(tokenA, tokenB, fee)` returns
  it in a verified call, as for swaps (docs/swaps.md, *Pins*). There is no
  HTTP price API.
- It is quoted in WETH. USD is the pool average times Chainlink's ETH/USD.
  Another display currency divides by that currency's pinned USD feed. Those
  feeds are read with every check a feed-priced value has: a stale feed on
  the path makes the value stale, said as for any feed, and an unproven one
  makes it no price.
- The window is 30 minutes. The reading is no price when any of these holds:
  - the pool holds fewer than two observations, or its oldest is younger
    than the window. An idle pool's `observe` extrapolates its last tick, so
    without this check its "average" is just its spot price;
  - its liquidity is below a pinned floor;
  - the average and the pool's current tick differ by more than a pinned
    band.
- Every value priced this way says so: "Uniswap 30-min average" beside an
  asset's value, and on the widget's line that names its network and dates
  it.
- **It counts in totals.** A total that includes one says so under it
  ("includes Uniswap averages"), as a Sepolia total says "testnet prices".
- The widget's 24-hour change is the whole path a day back, as for a
  feed-priced rate (docs/wallet-ux.md, *Only verified rates, dated*): the
  pool's average a day back (`observe` at the verified head), and each feed
  on the path from its own round history. It is "24h —" when the pool's
  history does not reach a day back, or when a feed on the path has no
  verified, fresh round there.
- The pools, their liquidity floor and the divergence band are pinned by
  the PR that codes this, each proven on chain.
- One price id shows one source at a time. A feed pinned later replaces the
  average with no prompt.
- The average is display-only, like every price: it never reaches a
  signature, and a swap's amounts come from its verified quote, never from
  it.
- Why: Chainlink has no feed for these tokens on Ethereum. A pool is one
  venue and can be moved at a cost, so it is the fallback and is labelled as
  one. The light client proves what the pool says, not that its price is
  right.

## The token list is built in

(Owner ruling, 2026-10-16.)

- A pinned list of popular tokens per `Network`: address, symbol, name,
  decimals and a bundled icon. Every entry is checked against the contract's
  own `symbol()` and `decimals()` when it is listed.
- The user adds more by address in Settings. Their metadata is read from the
  contract and shown as the contract's claim, with a monogram icon.
- No remote token list and no icon CDN: one would learn which tokens the user
  holds.

## The clear-signing descriptors are built in

(Owner's decisions, 2026-11-25, #240; docs/clear-signing.md is the contract.)

- **ERC-7730 descriptors from the registry**
  (ethereum/clear-signing-erc7730-registry) say in words what a call or a
  typed-data message does. They are pinned, checked and converted at build
  time, and ship in every build.
- **The app never fetches a descriptor**: not from clearsigning.org, not from
  GitHub, not from a CDN. A fetch at signing time would tell a server which
  contract the user is about to use.
- **Pinned twice.** Each file by the registry's commit id and its sha256;
  each deployment by the keccak256 of its runtime code, and by how that code
  can change (a proxy's slot or a forwarding call, with the implementation
  it names), read through Myotis when the pin is made, never from an
  RPC provider. Only a curated list of deployments on Ethereum, Gnosis Chain
  and Sepolia ships (docs/clear-signing.md, *The first list*), whatever else
  a descriptor names.
- **A descriptor the runtime cannot render fails the build**: an unsupported
  schema major, or any key or value the runtime does not implement, or the
  registry's own test vectors not met. So does a deployment whose way of
  changing its code cannot be pinned. Nothing is dropped silently.
- **Every shipped descriptor is reviewed in our PR.** An auditor attestation
  from the registry's `sigs/` is shown where one exists, once a later part
  checks its signature and hash; until then none is shown. None is required.
- **A refresh is a step of every release PR**, through a PR that moves the
  pins, its diff reviewed like code. Never at runtime.
- **Phase B's verified trace is the engine's own `debug_traceCall`**, a
  verified read like any other (owner's decisions, 2026-11-26, #240; *The
  engine is the only source of chain data*, above).
- **Descriptors of our own** for contracts Punicus pins and the registry
  lacks (Swarm's, the Gnosis Pay Delay module) come in a later part,
  contributed upstream.

## Verified ABIs are built in: from Sourcify, recompiled at build time

(Owner's decisions, 2026-11-26, #240: question 8, the first list, exact
matches only, MultiSend; docs/clear-signing.md, *Phase C*, is the contract.)

- **For contracts no descriptor covers**, a curated list's functions and
  their parameters' names ship in every build.
- **From Sourcify's repository, at build time only.** lib-sourcify recompiles
  each contract, and its runtime code must match before anything is pinned:
  Sourcify's verdict is reproduced, not trusted.
- **Exact matches only**: a partial match binds no names to the code, and its
  contract stays not decoded until an exact match exists.
- **A contract on no list stays not decoded**, beside its verified trace.
  Nothing is fetched at signing time.
- **The first list**: Safe's MultiSend and libraries, the Gnosis Pay Delay
  module, Swarm's postage stamp and chequebook, CoW Protocol's settlement,
  Uniswap's Universal Router. MultiSend is named on the delegate call shown
  and refused, nothing more.
- How it is applied, not part of the decisions (the owner's to approve on
  #240): sources and compilers vendored and pinned by sha256, one entry per
  network keyed by its on-chain code hash, a minimal proxy's entry held to
  its implementation's pin, a refresh in every release PR
  (docs/clear-signing.md, *Phase C*).

## An account's NFTs: found in its verified history, proven held, read on chain only

(Owner rulings, 2026-11-05, #246.)

- **Found in the log index, proven at the head.** An NFT is found among the
  ERC-721 `Transfer`s and ERC-1155 `TransferSingle`/`TransferBatch`es the
  engine's log index holds for the account. It is shown only once a verified
  `ownerOf` names the account, or a verified `balanceOf` is above zero. A log
  alone never makes an NFT held.
- **On-chain metadata only.** A collection's `name()` and `symbol()` and a
  token's id, read with verified calls. An image or metadata is shown only
  when `tokenURI`/`uri` is an on-chain `data:` URI. IPFS and HTTP are not
  fetched: the screen says the image is not on chain.
- **Unasked NFTs are collapsed.** The built-in collections (ENS's registrar
  and NameWrapper, per network) are shown openly. Any other collection sits
  under a collapsed "Unlisted collections" row, marked unconfirmed: anyone can
  send an NFT to any address.
- **Older NFTs are added by contract and id.** The index covers the account
  from its start only. The list says so, and offers "Add an NFT": a contract
  and a token id, kept once the account is proven to hold it.
- **ENS names are also found by name** (owner ruling, 2026-11-15, #274, on
  #299; it amends the line above for ENS names):
  - Every verified resolution that lands on one of the user's accounts keeps
    that name for the account as a candidate. A name that lands on an
    address not in the wallet is not kept.
  - The tokens a candidate can be (the .eth registrar's, id = labelhash; the
    NameWrapper's, id = namehash) are worked out locally by hashing. They are
    checked like an added NFT and listed only while a verified call shows
    them held.
  - "Add an NFT" also takes an ENS name alone. It is kept once a token of it
    is proven held.
  - A name that merely points to the account, held or not, is never listed
    as an NFT. The account shows it separately, as a name pointing here.
- ENS tokens are decoded on chain (the NameWrapper's `names`, the registrar's
  labels from logs, or a name kept above whose hash is the token id), never
  through ENS's metadata service.
- **ENSv2 too** (owner ruling, 2026-11-06, #246): on Sepolia, which runs on
  ENSv2, its .eth registry is a built-in collection, pinned by its code hash.
  A label comes from its label store and is checked by hashing. A redeploy
  shows its NFTs as unlisted until the pin is updated.
- NFTs have no price and never count in a total.

## Safe co-signing uses no off-chain service

(Owner ruling, 2026-10-16.)

- A signing request moves between devices as a QR code or as text. It carries
  the raw `SafeTx` fields and the signatures collected so far, never a
  description of them.
- The co-signer decodes it itself and checks it against the chain: the Safe
  (verified as a Safe), its nonce and owners, and the `SafeTx` hash
  recomputed locally. A signer that is not a current owner is refused.
- An owner may approve on chain with `approveHash` instead. Once the
  threshold is met, any owner executes it.
- Any account whose key is on this device may send that execution and pay
  for it, owner or not. An owner that executes counts as its own
  confirmation (owner ruling, 2026-10-18, #56).
- The Safe Transaction Service stays unwired.

## An account's history comes from the engine's verified log index

(Owner rulings, 2026-10-20, #85; docs/embedding-myotis.md, *History*.)

- The history is a Safe's own events and every log naming an account as a
  topic, walked back over verified headers.
- An account not created here is walked from the block it was added at,
  never further back ("since added"), and the history says so.
- One widening: on the desktop and Android, Activity may also take verified
  rows of the addr1 index, below. "Since added" binds the log index's walk, not those
  rows (owner ruling, 2026-11-21, #334).
- The walk fetches receipts only for the blocks whose bloom flags an account.
  That tells the serving peer which blocks matter to this device. Accepted
  as is: the log index makes that trade-off for any watch-list.

## The history may also take the addr1 index from Swarm: verified rows only, on the desktop and Android

(Owner ruling, 2026-11-21, #334. It widens *An account's history comes from
the engine's verified log index*, as #334 proposed, with verified rows only.
The owner's decisions of the same day, on #335, settle its scope, the key,
contradicted entries and what others learn.)

- **The source is the engine's address history**, nothing else: the calls
  of Myotis #899 (`myotis_getAddressHistory` and the per-entry check, through
  `io.myotis.api`). The engine reads the addr1 address index from Swarm
  through its own embedded light node, checks every chunk, and checks each
  entry against verified chain data (Myotis's carve-out, 2026-11-19, and its
  rule D1, 2026-11-20). Punicus adds no Swarm client, no gateway and no HTTP.
- **Activity only** (owner's decision, 2026-11-21, on #335). The rows reach
  the account's *Activity* list and its row detail, nothing else. NFTs, the
  recipient check's planted-sender test (`CheckRecipient`) and CoW orders'
  ends keep reading the log index alone.
- **Verified rows only.** An addr1 entry becomes a row only once the engine
  verified it. An entry that is unverified, contradicted or unavailable is
  never shown as a row. An entry whose only role is `TRACE` is never
  verified by the engine, so it is never shown.
- **Completeness rests on the importer.** The index is signed by an importer
  key, and that key alone vouches that no entry is missing. The list names
  the key, says that entries may be missing, and states every range the
  index does not cover: the newest blocks it has not reached yet, where its
  coverage starts, and the gaps inside it.
- **The importer key is pinned in the release** (owner's decision,
  2026-11-21, on #335). The user does not type one in. Which key is pinned is
  the owner's choice, named in the PR that pins it.
- **A new key starts from the default** (owner's decision, 2026-11-22, #334).
  When a release pins another key, only an explicit off carries over: a
  choice to turn the setting on, made for the key pinned before, does not,
  and the key pinned now is on by default, as on a fresh install.
- **A contradicted entry is a warning against the key** (owner's decision,
  2026-11-21, on #335). The chain proved that the key signed something false.
  The row stays out; the list counts contradicted entries apart from the ones
  it could not verify, and warns in plain words that the importer's index
  contradicts the chain. What follows beyond that (no longer trusting the
  key, reporting it) is open (*Open decisions*, below).
- **What others learn** (owner's decision, 2026-11-21, on #335: accepted).
  A lookup makes the engine's Swarm node fetch the account's part of the
  index from Swarm peers: they see the device's IP address and which part it
  asks for. That node does not go through Tor. The per-entry check fetches
  old blocks and their receipts from archive peers: they learn which past
  blocks matter to this device, older ones than the log index's walk
  included. The privacy policy says so, and how to turn the setting off
  (owner's decision, 2026-11-22, #334, amending "before the setting can be
  turned on" of 2026-11-21).
- **On the desktop and on Android, behind an experimental setting**, on by
  default, that names the pinned key. The user may turn it off, and a choice
  to keep it off stays (owner's decision, 2026-11-22, #334, amending "off by
  default" of 2026-11-21). Android takes it as the desktop does (owner's
  decisions, 2026-11-24: "make it work on Android ASAP", and 2026-11-25:
  "Enable addr1 on android and desktop", amending "Android and iOS have no
  such setting" of 2026-11-21); iOS has no such setting. With no key pinned,
  or no Swarm reader, it reads nothing. The engine's Swarm reader ships from
  Myotis 0.1.23 on, in its desktop natives and its Android AAR (Myotis #920),
  so both read the index. An engine without the reader (iOS, a build without
  its `swarm` feature) refuses with the permanent `-32602`, as every engine
  does while Tor routing is on. There the setting is greyed out, a tap saying
  why (`ui.md`, the greyed-out rule), and the history stays the log index's.
- **On Android it reads on any connection, mobile data included** (owner's
  decision, 2026-11-25, on #367). A page of the index (up to 20 entries, each checked
  against old blocks and their receipts) starts only when the user opens or
  scrolls an account's Activity, a read the user waits on, so it is no heavy
  download in the sense of the ruling of 2026-10-20 and is not held off a
  metered connection. An opening with a list kept from before also reads
  every entry newer than it, page by page, however many pages that is, with
  no scroll in between (below); that too reads on any connection, mobile
  data included (owner's decision, 2026-11-25, on #374). A page started
  finishes after the user leaves, and the newer pages still to come start
  too; once the app's light clients pause (a minute in the background) the
  engine refuses every read and stops its Swarm node (Myotis v0.1.23,
  `host.rs`, `pause` and `snapshot_reader_evm`). Turning the setting off
  stops it.
- **A panic in the reader ends the app** (recorded at the owner's request,
  2026-11-24). Myotis accepted for now that a panic inside `ant`, its
  embedded Swarm node, ends the host process (2026-11-24,
  freedom-hq/ant#271). On the desktop and on Android the host process is the
  wallet, outbox sends and dApp sessions included; the owner accepts that
  for Android too, with the setting on by default (owner's decision,
  2026-11-25, on #367). The node runs only for a read, so with the setting
  off it never runs. The changelog says so.
- **What was read is kept between runs** (owner's request, 2026-11-25:
  "Make the downloaded list persistent. The results will never change. So
  it's ok to store them locally. And prepend the newest txs on the next
  displaying of the screen"). Each account's verified rows are kept in an
  app-private file, out of backups and device transfers like every file of
  the app, and shown at once when its Activity opens again. Only the
  entries newer than the newest block read before are then read, checked
  and prepended; older pages go on where the kept list ended.
- How it is applied, not part of the request:
  - Only a verdict against a block the beacon chain had finalized is kept as
    an answer (a row, or a contradiction counted against the key). The kept
    list's top stays below every block whose verdict was not final yet, so
    the index is read there again, with any reorganisation's corrections.
  - Each entry is kept by itself (a row, left out for good, or waiting for
    its check), so a later read neither checks nor counts it again.
  - Every entry newer than the kept list is read when the Activity opens,
    page by page, however many pages that is, each page kept as it loads,
    on any connection (owner's decision, 2026-11-25, on #374);
    older ones only as the list nears its end. A refusal of the newer
    entries is asked again first.
  - Up to 20 entries that no peer answered before are checked again at each
    opening. The ranges a read could not get from Swarm (a page's gaps) are
    named as gaps and read again, one page each per opening, while this
    opening's reads do not miss them again.
  - Only the pinned key's list is kept; under a newly pinned key the list
    starts again from the index. The checkpoint table's alarm deletes what
    is kept, met by a list's read or a row's detail, as it withdraws the
    trust the rows rest on. Removing an account deletes every file named
    after it.
- How it is applied, not part of the ruling: the list says how many entries
  of the index it left out because they could not be verified, and,
  separately, how many the chain contradicts. That follows from this ruling
  and from Myotis's carve-out, which names every range nobody covered as a
  gap, never a shorter list.

## Heavy downloads stay off a metered connection unless the user allows them

(Owner ruling, 2026-10-20.)

- A heavy download is network work that can run to hundreds of megabytes and
  that nobody is waiting on: the log index's walk back over the account
  history first. A read the user waits on (a balance, a send's review) never
  is one.
- On a connection the platform reports as metered, or one whose cost cannot
  be read, it waits for an unmetered one.
- Settings has the opt-in switch that lets it run there
  (`MeteredDownloadsSetting`, off by default). A choice that cannot be read
  is off.
- A platform that reports no metering (the desktop) runs it on any
  connection, as Myotis's desktop host does. The switch is greyed out there,
  and says why.
- Paused, the log index still follows the new blocks.
- How it is applied, not part of the ruling: on iOS an expensive path
  (cellular, a hotspot) or Low Data Mode counts as metered; each network's
  index is paused and resumed alike.

## Swaps: Uniswap first, then CoW Protocol; the user chooses the venue

(Owner's directions, 2026-10-18 and 2026-10-22; docs/swaps.md, issues #69
and #144.)

- The user decides which venue each swap uses. Uniswap stays available when
  CoW is not.
- Uniswap's contracts are chain data like any other: pinned, read with
  verified calls, and sent to like any transaction.

## CoW Protocol's order API is an off-chain service, used only while CoW is chosen for a swap

(Owner ruling, 2026-10-22; amended 2026-11-23 for the compose step's estimate.)

- CoW learns the swaps the user sends to it, and no others but those composed
  with CoW chosen (below): the account's address, the pair, the amounts, the
  device's IP address and, for a signed order, the order. With Uniswap
  chosen, CoW is never contacted.
- With CoW chosen, the compose step asks CoW for its estimate too, so that
  the first screen shows the rate and the amount (owner ruling, 2026-11-23,
  amending the line above): CoW then learns the account's address, the pair
  and the amount composed, whether the swap is sent or not.
- The app data carries nothing beyond the required fields. The IP address is
  not hidden for now.
- Of CoW's answers about an order, only its off-chain life is shown. A fill,
  and every amount it moved, comes from the account's verified logs alone.
- The swap's review also shows CoW's estimate, marked as not verified. This
  follows from #144's design point 3, not from the ruling's words.
- The order is built, hashed and checked here.

## A CoW order does not depend on Uniswap

(Owner ruling, 2026-11-23: "cowswap should not depend on uniswap in any way".
It replaces the verified Uniswap floor under a CoW order, and the refusal of a
pair with no Uniswap route, of 2026-10-22, decision 1; docs/swaps.md, *The
signed minimum*.)

- A CoW order's signed minimum is CoW's own estimate less the tolerance, as
  CoW's own app sets it. No Uniswap quote, route or fee enters a CoW order,
  its compose step, its review or its signature.
- Every pair CoW quotes is offered on CoW, whether Uniswap has a route for it
  or not. A network with CoW pinned and no Uniswap offers Swap.
- The minimum rests on CoW's word: the light client cannot prove CoW's
  estimate. The review says so, in both views.
- CoW is pinned on Ethereum, Gnosis Chain and Sepolia.

## dApps connect over WalletConnect v2; its relay is an off-chain service

(Owner rulings, 2026-11-03, #22, accepting its recommendations;
docs/walletconnect.md is the contract.)

- **WalletConnect v2 is the dApp connection protocol**, in both directions: a
  dApp connects to Punicus, and Punicus connects to an external wallet that
  signs for a Safe owner.
- **The relay never supplies chain state.** It sees the device's IP address,
  the topics, the timing and size of messages and the project ID. The
  payloads are end-to-end encrypted: it cannot read or forge one, only
  withhold, delay or replay it. A request seen before on its topic is
  dropped, so a replay never prompts the user again.
- **What arrives through it is untrusted input**, decoded and shown as such.
  The dApp's name and URL are shown as "stated by the dApp, not verified".
- **The Reown project ID is never committed.** A Gradle property supplies it
  at build time, from a CI secret or variable. A build without one has
  WalletConnect switched off and says so.
- **One project ID serves debug and release builds, for now** (owner ruling,
  2026-11-05; it amends the separate IDs of 2026-11-03, #22). A free-tier
  Reown account has one project. CI reads it from one repository secret or
  variable, `WALLETCONNECT_PROJECT_ID`, for both builds. The build keeps a
  debug and a release property, so a second project later needs only the
  workflows to pass two names again.
- **The optional services stay off:** the Verify API, fetching dApp icons
  (a request to a host the dApp chooses, which reveals the IP address), the
  push (echo) server and Link Mode.
- **A dApp session holds real funds or testnet, never both** (owner ruling,
  2026-11-05, on #22; it amends "a dApp session names Sepolia alone, for
  now" of 2026-11-03, #22, ruling 9).
  - One session may hold Ethereum and Gnosis Chain accounts, and the dApp
    may switch between them with `wallet_switchEthereumChain`.
  - A Sepolia session is Sepolia alone. A dApp can never switch a session
    from testnet to real funds, nor back.
  - A proposal that requires a chain Punicus does not serve, or chains of
    both kinds, is rejected. Accounts of the other kind than those already
    chosen are greyed out on the proposal screen, a tap saying why.
  - Each request names one of its session's networks and an account of the
    session on that network, or it is refused.
- **dApps connecting to Punicus come first**; an external wallet as a Safe
  owner's signer follows (docs/walletconnect.md, *The plan*).

## Send from another wallet hands a payment link to another wallet app

(Owner rulings, 2026-11-05, #255, accepting its recommendations.)

- For an EOA whose key is not on this device, a transfer of the network's
  currency or of an ERC-20 token is handed to another wallet app as an
  EIP-681 payment link. That wallet signs and broadcasts it, through its own
  provider, which the review says learns the transfer and the IP address.
  Punicus contacts no service.
- Every network that is on. It is not a dApp session, so the ruling on dApp
  sessions' networks (real funds or testnet, never both; owner ruling,
  2026-11-05, on #22) does not bind it.
- Only `ethereum:` and `metamask://` links, never MetaMask's `https://` form,
  which falls back to its website.
- Nothing comes back through the link. What the transfer became is read from
  verified blocks alone, for 10 minutes after the handoff; after that, the
  account's history.
- On mobile; not on the desktop. Built for Android so far. iOS is not built
  yet, which is no ruling: the engine's iOS reads have no verified block body,
  and iOS opens an undefined one of several apps that claim `ethereum:`.

## Swarm nodes: pinned contracts, found in the log index or added by hand

(Owner's decisions, 2026-11-23; docs/swarm.md.)

- **Gnosis Chain only.** Bee's contracts as Bee v2.8.3 names them, each
  pinned by address and code hash: the PostageStamp, the chequebook factory
  and its master copy, proven before they are read; and the BZZ token's
  proxy and the implementation its EIP-1967 slot names, proven before an
  approval is quoted or an action signed. xBZZ's balances are read like any
  built-in token's.
  Retired PostageStamps are not read. Swarm's testnet on Sepolia is open,
  the owner's to decide.
- **xBZZ joins Gnosis Chain's built-in token list.** No feed and no pool
  prices it: "no price", out of totals.
- **Found in the verified log index**, only as far back as it reaches
  ("since added"): a chequebook as the recipient of an xBZZ `Transfer` from
  the node; a batch from the pinned PostageStamp's `BatchCreated` logs,
  followed as an emitter while a Swarm node is in the wallet or an account
  of it paid the PostageStamp, whose owner, decoded from the data, is the
  node.
- **Only a batch the wallet paid for is found** (owner's decision,
  2026-11-23, on #347). Its transaction must also hold an xBZZ `Transfer`
  to the PostageStamp from an account of the wallet. Anyone can create a
  batch naming any owner; a batch someone else bought is added by hand.
- How it is applied, not part of the decision: that `Transfer` must move
  exactly the batch's own total, the first word of its `BatchCreated`. A
  `Transfer` of nothing counts as no payment and names no chequebook, since
  anyone can emit one from any account (`transferFrom` of nothing needs no
  allowance).
- **Or bought here, or added by hand:** a chequebook by address, a batch by
  id, kept once proven. The candidates are public data, kept in an
  app-private file.
- **No Swarm service is contacted.** Not a Bee node's API, not a gateway:
  the user copies what Bee shows.

## Privacy Pools' circuit artifacts are bundled

(Owner ruling, 2026-11-11, docs/privacy-pools.md D14.)

- The circuits' proving and verification keys, and what computes their
  witness (today the witness graphs), ship in every build, each pinned by
  its sha256 in code. The proving keys are most of it: about 19 MB.
- The app never fetches them, at first use or later. Kohaku's prover
  downloads them from GitHub at every proof, unchecked; Punicus does not.
- Where the build takes them from, committed here or downloaded by the build
  from a pinned source and checked against the hash, is open
  (docs/privacy-pools.md, D14a).
- A file whose hash does not match is no artifact: proving is refused, and
  says why.

## Open decisions

Each is recorded here before its code lands. Today: each optional
WalletConnect service above; Swarm on Sepolia (docs/swarm.md); what follows a
contradicted addr1 entry beyond the warning (no longer trusting the importer key,
reporting it), the owner's to decide (2026-11-21, on #335); and whether the
Safe refusals reach what a verified trace proves, and the ordinary calls that
still warn (#240; docs/clear-signing.md, *Open decisions*).
