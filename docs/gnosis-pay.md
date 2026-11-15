# Gnosis Pay accounts

A Gnosis Pay account backs the Gnosis Pay Visa card. On chain it is a Safe on
Gnosis Chain, and the account that controls it is not one of the Safe's
owners: it is a member of a Zodiac Delay modifier enabled on the Safe. This
page is the contract for recognising such an account and for sending from it
through that module. It records the owner's decisions of 2026-11-15, which
`.claude/rules/trust.md` (*A Gnosis Pay account sends through its Delay
module*) carries as rules.

## How the account is set up

Gnosis Pay's own SDK, account-kit (github.com/gnosispay/account-kit,
`accountSetup.ts`), turns a 1/1 Safe into a Gnosis Pay account in one Safe
transaction:

- It swaps the user's EOA out as the Safe's owner for the sentinel `0x…02`,
  which no key controls. The Safe's threshold stays 1. No owner signature can
  move the Safe's funds any more.
- It enables two modules on the Safe: a Roles modifier and a Delay modifier,
  each a minimal proxy deployed by Zodiac's `ModuleProxyFactory`
  (`0x000000000000aDdB49795b0f9bA5BC298cDda236`).
- **The Delay** has the Safe as its avatar, target and owner. The user's EOA,
  or several "beneficiaries", are enabled as modules on the Delay: its
  members. A member queues a transaction for the Safe with
  `execTransactionFromModule(to, value, data, operation)`. After the
  cooldown, and until the expiration, anyone executes it with
  `executeNextTx(to, value, data, operation)`, which the Delay passes to the
  Safe's `execTransactionFromModule`. The queue is first in, first out:
  `txNonce` is the next entry to execute, `queueNonce` the next to be added,
  `txHash(n)` the hash of entry `n`
  (`keccak256(abi.encodePacked(to, value, data, operation))`) and
  `txCreatedAt(n)` its time.
- **The Roles modifier** lets Gnosis Pay's spender move the card's currency to
  its settlement address within an allowance. That allowance binds the
  spender, not the members.

Read on Gnosis Chain on 2026-11-15, the accounts set up since June 2026 carry
a cooldown of 90000 s (25 hours) and an expiration of 111600 s (31 hours
after the cooldown), not the 3 minutes Gnosis Pay's older documentation
names. The screens show each account's own figures, never a constant.

## The mastercopies

The Delay's code is its mastercopy's. Gnosis Pay replaced it on 2026-06-03
(account-kit `f4b9287`), two days after an exploit of 2026-06-01 that went
through the Delay module:

| Mastercopy | Generation | Punicus |
|---|---|---|
| `0x22d903fd45F441F51bcad198D14eBa8a75EA1ef0` | current (Sourcify: exact match of `Delay.sol`) | recognised; a member held here sends |
| `0x4A97E65188A950Dd4b0f21F9b5434dAeE0BBF9f5` | retired | recognised and shown; Send greyed out, saying why |

Both are pinned in `eth.serotine.data.safe.DelayDeployments`. The public
reports of the exploit do not name its root cause. Punicus never uses the
relayed path described below, and it checks the queue itself before an
execution (*Sending*).

## Detection

A Safe in the wallet on Gnosis Chain is a Gnosis Pay account when one of the
modules its verified state lists is the Safe's own Delay:

1. The module's code, proven by the light client, is byte for byte the
   EIP-1167 proxy of a pinned mastercopy.
2. Its getters, run as verified calls at one block (one number, one hash),
   say that its avatar, target and owner are the Safe. A Delay owned by
   anyone else could have its cooldown changed or its queue skipped by them:
   it is no Gnosis Pay account's, and nothing is sent through it.
3. Its cooldown, expiration, queue and members are read the same way.

The Safe is added as any Safe is: watched (*Watch an address*). No new way to
add an account exists. The account's screen then says it is a Gnosis Pay
account and offers Send when a member's key is held here on Gnosis Chain.
Otherwise Send is greyed out, and a tap says what is missing: a member's key;
the member added on Gnosis Chain; the member's watched entry on Gnosis Chain
removed and added again from the held one; the wallet's account list, which
cannot be read right now; or that the module is on the retired mastercopy.

`ObserveGnosisPay` (domain) and `VerifiedDelayModules` (data) implement it.

## Sending

A transfer from a Gnosis Pay account is two transactions from a member whose
key is held here. The member pays both fees in xDAI.

1. **Queue.** `execTransactionFromModule(to, value, data, CALL)` sent to the
   Delay, quoted, simulated and signed like any transaction from the member.
2. **Wait.** The cooldown runs from the block that queued it. The screen
   shows when the transfer can be executed, and until when.
3. **Execute.** `executeNextTx` with the same four fields, sent by the
   member. Before it is quoted, the Delay's verified state must show the
   transfer as the next entry (`txHash(txNonce)` equal to its hash), its
   cooldown passed and at least five minutes left before its expiration, so
   that the transaction is mined while the window is open. An entry ahead of
   it is shown, and Punicus waits: it never executes a transaction it did not
   queue. An expired entry ahead is skipped by the Delay's `skipExpired`,
   which Punicus does not offer yet.

Every quote first proves, fresh, that the Delay is still the Safe's own of
the current mastercopy. The queue is searched from its next entry for the
transfer's hash, through at most 8 entries; one further back is said to be
further back, never gone. A transfer identical to one queued from here and
not yet executed is refused, so no two of this wallet's entries share a
hash. The queued transfers are found again in the history of what this
wallet sent: each queueing with no successful execution of it after.

**Only plain transfers** are queued: the Safe's xDAI as the value with no
data, or an ERC-20 `transfer(to, amount)` with no value; operation CALL;
never to the Safe itself, to one of its modules or to the Delay. Nothing else
reaches the queue, so no Safe setting can change through it, and the Safe
refusals of `.claude/rules/trust.md` cannot be bypassed.

**The relayed path is not used.** The Delay's `moduleOnly` also accepts a
member's EIP-712 signature appended to the calldata, which Gnosis Pay's
relayer sends and pays for. That needs an off-chain service, which the data
sources rule leaves out. Punicus only sends the member's own transactions.

**The card's allowance is never a cap.** The chain does not hold the member
to the Roles allowance, so neither does Punicus. Showing the allowance was
asked for. It waits until where today's accounts keep it is known: on the
accounts read on 2026-11-15, the Roles allowance under account-kit's
`SPENDING_ALLOWANCE` key reads zero.

## Delivery

On `feature/gnosis-pay`, in parts:

1. The rules, this page, and detection: the domain port and use case, the
   pinned mastercopies and the engine adapter, with tests.
2. Queue and execute: the calldata, the use cases over the shared quote and
   send, the record of a queued transfer, and the check of the queue.
3. The screens and the wiring: the account's Gnosis Pay card, Send through
   the Delay with its wait, previews, and the hosts' composition roots.
