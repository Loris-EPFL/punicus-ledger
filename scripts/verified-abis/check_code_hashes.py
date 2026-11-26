#!/usr/bin/env python3
"""Checks that each recompiled contract's code is the chain's, through local Myotis daemons (a refresh's step).

    python3 -I scripts/verified-abis/check_code_hashes.py [--write] ETHEREUM=http://127.0.0.1:8545 \\
        GNOSIS=http://127.0.0.1:8556 SEPOLIA=http://127.0.0.1:8547

Each NETWORK=URL is a myotis-rpcd (the Myotis repository's JSON-RPC daemon over its verified light client) on
loopback, never an RPC provider, read as phase A's pins are (scripts/clear-signing/pin_code_hashes.py: no
redirect, every network at one block, no code refused, each code held to its account proof). For every
entry of contracts.json on those networks it reads the code at the address, hashes it with Keccak-256, and
with --write, pins it in contracts.json (`codeHash`), which verify.mjs then holds every vendored code to, so
that CI tells a swapped code apart. Without it, it requires the pinned hash. A network not named is not
checked, and says so; the refresh is not done until all are.
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "clear-signing"))
from pin_code_hashes import Rpc, code_hash  # noqa: E402

CHAINS = {"ETHEREUM": 1, "GNOSIS": 100, "SEPOLIA": 11155111}


def main(argv: list) -> int:
    write = "--write" in argv
    # Phase A's reader: loopback only, no redirect, each code held to its account proof, all at one block.
    daemons = {CHAINS[name]: Rpc(url) for name, url in (arg.split("=", 1) for arg in argv if arg != "--write")}
    blocks = {chain: rpc.call("eth_blockNumber") for chain, rpc in daemons.items()}
    listed = json.loads((HERE / "contracts.json").read_text(encoding="utf-8"))
    unchecked = 0
    for entry in listed["contracts"]:
        rpc = daemons.get(entry["chainId"])
        if rpc is None:
            unchecked += 1
            continue
        where = f"{entry['label']} on {entry['chainId']}"
        _, onchain = code_hash(rpc, entry["address"], blocks[entry["chainId"]], where)
        if write:
            entry["codeHash"] = onchain
        elif onchain != entry.get("codeHash"):
            raise SystemExit(f"{entry['chainId']} {entry['address']}: the chain's code hashes to {onchain}, "
                             f"not to the pinned {entry.get('codeHash')}")
        print(f"{entry['chainId']} {entry['address']} {entry['label']}: {onchain}")
    if write:
        (HERE / "contracts.json").write_text(json.dumps(listed, indent=2) + "\n", encoding="utf-8")
    if unchecked:
        print(f"{unchecked} entries on networks not named were not checked")
    return 1 if unchecked else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
