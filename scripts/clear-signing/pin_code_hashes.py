#!/usr/bin/env python3
"""Pins each listed deployment's code hash, and how its code can change, from a local Myotis daemon.

    python3 -I scripts/clear-signing/pin_code_hashes.py ETHEREUM=http://127.0.0.1:8545 SEPOLIA=http://127.0.0.1:8547

Each NETWORK=URL is a myotis-rpcd (the Myotis repository's JSON-RPC daemon over its verified light client)
on loopback, never an RPC provider (docs/clear-signing.md, A1, step 4). For every deployment of
scripts/clear-signing/contracts.json on those networks it reads, at one verified block:

  - the runtime code, hashed with Keccak-256 here, and checked against the account proof's `codeHash`;
  - how that code can change, as `upgrade` records it:
      none  the code runs nothing else: no DELEGATECALL or CALLCODE in it, and every known proxy slot empty;
      slot  a proxy whose implementation sits in a named slot (EIP-1967, its beacon, ZeppelinOS, EIP-1822);
      call  a proxy that names its implementation through a call (an Aragon AppProxy's `implementation()`),
            declared by hand in contracts.json, with the word the call must return.
    A deployment whose code holds a DELEGATECALL and names no implementation in any of these ways is refused,
    unless contracts.json declares by hand what it delegates to (`upgrade.delegatecall`: a multicall into
    the contract's own code), which the PR's reviewer checks against the verified source:
    an empty EIP-1967 slot never means "not a proxy". So is a slot or a call that names a contract without
    code, or one whose implementation can itself DELEGATECALL or CALLCODE, unless contracts.json declares by
    hand what that implementation delegates to (`upgrade.implementationDelegatecall`: linked libraries at
    addresses fixed in its code, for one). A contract that forwards by CALL (USDT's `deprecated`) is not
    seen this way: it stays off the list until the script reads its pointer.

It rewrites contracts.json in place; the PR's diff shows every value. Run generate.py after it.
"""
import json
import pathlib
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from keccak import keccak256  # noqa: E402

CONTRACTS = pathlib.Path(__file__).resolve().parent / "contracts.json"
SLOTS = {
    "EIP-1967 implementation": "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc",
    "EIP-1967 beacon": "0xa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6cb3582b35133d50",
    "ZeppelinOS implementation": "0x7050c9e0f4ca769c69bd3a8ef740bc37934f8e2c036e5a723fd8ee048ed3f8c3",
    "EIP-1822 PROXIABLE": "0xc5f16f0fcc639fa48a6947836d9850f504798523bf8c9a3a87d5876cf622bcf7",
}
ZERO_WORD = "0x" + "00" * 32


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SystemExit(f"refused: {req.full_url} redirects to {newurl}; only the local daemon is read")


class Rpc:
    def __init__(self, url: str):
        if not url.startswith(("http://127.0.0.1:", "http://localhost:", "http://[::1]:")):
            raise SystemExit(f"refused: {url} is not on loopback; only a local Myotis daemon is read")
        self.url, self.next_id = url, 0
        # No proxy from the environment (http_proxy would carry a loopback URL elsewhere), and no redirect.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())

    def call(self, method: str, *params):
        self.next_id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": list(params)})
        request = urllib.request.Request(self.url, body.encode(), {"content-type": "application/json"})
        with self.opener.open(request, timeout=300) as response:
            answer = json.loads(response.read())
        if "error" in answer:
            raise RuntimeError(f"{method}: {answer['error']}")
        return answer["result"]


SOLC_METADATA_KEYS = (b"bzzr0", b"bzzr1", b"ipfs", b"solc", b"experimental")


def without_metadata(code: bytes) -> bytes:
    """[code] without solc's CBOR metadata trailer, which is data, not code.

    The trailer is a CBOR map of one to five entries whose length is the code's last two bytes. It is
    stripped only when that map's first key is one solc writes (a text string `bzzr0`, `bzzr1`, `ipfs`,
    `solc` or `experimental`), so that stray bytes of other code are never taken for one.
    """
    if len(code) >= 2:
        length = int.from_bytes(code[-2:], "big")
        start = len(code) - 2 - length
        if length >= 3 and start >= 0 and 0xA1 <= code[start] <= 0xA5:
            header = code[start + 1]
            if 0x60 <= header <= 0x77 and code[start + 2:start + 2 + header - 0x60] in SOLC_METADATA_KEYS:
                return code[:start]
    return code


def has_delegatecall(code: bytes) -> bool:
    """Whether DELEGATECALL (0xf4) or CALLCODE (0xf2), which both run other code as this contract, occurs as an
    opcode, PUSH data and the metadata trailer skipped."""
    code = without_metadata(code)
    i = 0
    while i < len(code):
        op = code[i]
        if op in (0xF4, 0xF2):
            return True
        i += 1 + (op - 0x5F if 0x60 <= op <= 0x7F else 0)
    return False


def code_hash(rpc: Rpc, address: str, block: str, where: str) -> tuple:
    code = bytes.fromhex(rpc.call("eth_getCode", address, block)[2:])
    if not code:
        raise SystemExit(f"refused: {where}: no code at {address}")
    digest = "0x" + keccak256(code).hex()
    try:
        proven = rpc.call("eth_getProof", address, [], block)["codeHash"].lower()
    except RuntimeError as error:
        raise SystemExit(f"refused: {where}: no account proof to check the code of {address} against: {error}")
    if proven != digest:
        raise SystemExit(f"refused: {where}: keccak of the code {digest}, the account proof says {proven}")
    return code, digest


def push20_operands(code: bytes) -> set:
    """The addresses pushed by PUSH20 in [code], PUSH data of other widths skipped."""
    code, found, i = without_metadata(code), set(), 0
    while i < len(code):
        op = code[i]
        if op == 0x73 and i + 21 <= len(code):
            found.add("0x" + code[i + 1:i + 21].hex())
        i += 1 + (op - 0x5F if 0x60 <= op <= 0x7F else 0)
    return found


def check_declaration(rpc: Rpc, code: bytes, digest: str, declared: dict, block: str, where: str) -> dict:
    """A by-hand declaration of what [code] delegates to, checked as far as the code allows.

    `forCodeHash` is the code it was read for: a declaration never vouches for other code, so a moved code
    hash is refused until a person reads the new source and updates it. Each of `libraries` must be pushed by
    the code itself (an address fixed at link time), and its hash is pinned. A library's own code may delegate
    only to the declared libraries its `links` names for it (library-to-library linking), each pushed by it.
    `self` (the code delegating into itself, a multicall) cannot be proven here: the PR's reviewer checks it.
    """
    if not isinstance(declared, dict) or not isinstance(declared.get("why"), str):
        raise SystemExit(f"refused: {where}: a declaration is an object with its `why`")
    for_hash = declared.get("forCodeHash")
    if for_hash and for_hash != digest:
        raise SystemExit(f"refused: {where}: its delegate calls were declared for code {for_hash}, the code is now"
                         f" {digest}; read the new source and update the declaration")
    pushed = push20_operands(code)
    libraries = [library.lower() for library in declared.get("libraries", [])]
    links = {k.lower(): [v.lower() for v in vs] for k, vs in declared.get("links", {}).items()}
    hashes = {}
    for library in libraries:
        if library not in pushed:
            raise SystemExit(f"refused: {where}: library {library} is not an address its code pushes")
        library_code, library_hash = code_hash(rpc, library, block, f"{where} library {library}")
        if has_delegatecall(library_code):
            targets = links.get(library)
            if not targets:
                raise SystemExit(f"refused: {where}: library {library} can DELEGATECALL or CALLCODE, and `links`"
                                 " names no library it is linked to")
            for target in targets:
                if target not in libraries or target not in push20_operands(library_code):
                    raise SystemExit(f"refused: {where}: library {library} links {target}, which is not a declared"
                                     " library its code pushes")
        hashes[library] = library_hash
    for library in links:
        if library not in libraries:
            raise SystemExit(f"refused: {where}: `links` names {library}, which is not a declared library")
    if not hashes and not declared.get("self"):
        raise SystemExit(f"refused: {where}: a declaration names libraries, or `self`, or both")
    out = {"why": declared["why"], "forCodeHash": digest}
    if declared.get("self"):
        out["self"] = True
    if hashes:
        out["libraries"] = sorted(hashes)
        out["libraryCodeHashes"] = hashes
    if links:
        out["links"] = {k: sorted(v) for k, v in sorted(links.items())}
    return out


def implementation_hash(rpc: Rpc, implementation: str, block: str, where: str, upgrade: dict) -> str:
    """The implementation's code hash; refused when that code itself delegates (a proxy behind the proxy) and
    contracts.json does not say by hand what it delegates to (`implementationDelegatecall`), which is checked
    and rewritten in [upgrade]."""
    code, digest = code_hash(rpc, implementation, block, where + " implementation")
    declared = upgrade.get("implementationDelegatecall")
    if has_delegatecall(code) and not declared:
        raise SystemExit(f"refused: {where}: its implementation {implementation} can DELEGATECALL or CALLCODE too;"
                         " pin that by hand, or leave the deployment off the list")
    if declared:
        upgrade["implementationDelegatecall"] = check_declaration(rpc, code, digest, declared, block,
                                                                  where + " implementation")
    return digest


def as_address(word: str) -> str:
    return "0x" + word[-40:]


def pin(rpc: Rpc, deployment: dict) -> None:
    where = f"{deployment['network']} {deployment['name']}"
    block = rpc.call("eth_blockNumber")
    address = deployment["address"]
    code, digest = code_hash(rpc, address, block, where)
    deployment["codeHash"] = digest
    upgrade = deployment.get("upgrade", {})
    found = {name: rpc.call("eth_getStorageAt", address, slot, block) for name, slot in SLOTS.items()}
    found = {name: word for name, word in found.items() if int(word, 16)}
    if upgrade.get("kind") == "call":
        word = rpc.call("eth_call", {"to": address, "data": upgrade["selector"]}, block)
        if len(word) != 66:
            raise SystemExit(f"refused: {where}: {upgrade['call']} returned {word!r}, not one word")
        upgrade.update(returns=word.lower(),
                       implementationCodeHash=implementation_hash(rpc, as_address(word), block, where, upgrade))
    elif len(found) > 1:
        raise SystemExit(f"refused: {where}: several proxy slots are set ({sorted(found)}); declare it by hand")
    elif found:
        (name, word), = found.items()
        if name == "EIP-1967 beacon":
            raise SystemExit(f"refused: {where}: a beacon proxy, which this script does not pin yet")
        implementation = as_address(word)
        declared = {k: v for k, v in upgrade.items() if k == "implementationDelegatecall"}
        upgrade = {"kind": "slot", "why": f"the {name} slot", "slot": SLOTS[name], "implementation": implementation,
                   **declared}
        upgrade["implementationCodeHash"] = implementation_hash(rpc, implementation, block, where, upgrade)
    elif has_delegatecall(code) and not upgrade.get("delegatecall"):
        raise SystemExit(f"refused: {where}: its code can DELEGATECALL or CALLCODE and no proxy slot names an"
                         " implementation; declare how it changes (upgrade.kind call) by hand")
    elif has_delegatecall(code):
        upgrade = {"kind": "none", "why": "every proxy slot empty; its DELEGATECALL is declared by hand",
                   "delegatecall": check_declaration(rpc, code, digest, upgrade["delegatecall"], block, where)}
    else:
        upgrade = {"kind": "none", "why": upgrade.get("why") or "no DELEGATECALL in its code, every proxy slot empty"}
    deployment["upgrade"] = upgrade
    print(f"{where}: block {int(block, 16)}, code {digest}, upgrade {upgrade['kind']}")


def main(argv: list) -> int:
    rpcs = {}
    for arg in argv:
        network, _, url = arg.partition("=")
        rpcs[network] = Rpc(url)
    if not rpcs:
        print(__doc__, file=sys.stderr)
        return 2
    contracts = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    for deployment in contracts["deployments"]:
        if deployment["network"] in rpcs:
            pin(rpcs[deployment["network"]], deployment)
    CONTRACTS.write_text(json.dumps(contracts, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
