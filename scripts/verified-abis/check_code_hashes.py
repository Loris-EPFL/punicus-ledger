#!/usr/bin/env python3
"""Checks that each recompiled contract's code is the chain's, through local Myotis daemons (a refresh's step).

    python3 -I scripts/verified-abis/check_code_hashes.py ETHEREUM=http://127.0.0.1:8545 GNOSIS=http://127.0.0.1:8556 \\
        SEPOLIA=http://127.0.0.1:8547

Each NETWORK=URL is a myotis-rpcd (the Myotis repository's JSON-RPC daemon over its verified light client) on
loopback, never an RPC provider, as for phase A's pins (scripts/clear-signing/pin_code_hashes.py). For every
entry of verified.json on those networks it reads the code at the address, hashes it with Keccak-256, and
requires the hash verify.mjs gave (the vendored code's, which the recompile matched). A network not named is
not checked, and says so; the refresh is not done until all are.
"""
import json
import pathlib
import sys
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "clear-signing"))
from keccak import keccak256  # noqa: E402

CHAINS = {"ETHEREUM": 1, "GNOSIS": 100, "SEPOLIA": 11155111}


def code(url: str, address: str) -> bytes:
    if not url.startswith(("http://127.0.0.1:", "http://localhost:", "http://[::1]:")):
        raise SystemExit(f"refused: {url} is not on loopback; only a local Myotis daemon is read")
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "eth_getCode", "params": [address, "latest"]})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(url, body.encode(), {"Content-Type": "application/json"})
    with opener.open(request, timeout=120) as response:
        answer = json.loads(response.read())
    if "error" in answer:
        raise SystemExit(f"{address}: {answer['error']}")
    return bytes.fromhex(answer["result"].removeprefix("0x"))


def main(argv: list) -> int:
    daemons = {CHAINS[name]: url for name, url in (arg.split("=", 1) for arg in argv)}
    verified = json.loads((HERE / "verified.json").read_text(encoding="utf-8"))
    unchecked = 0
    for entry in verified:
        url = daemons.get(entry["chainId"])
        if url is None:
            unchecked += 1
            continue
        onchain = "0x" + keccak256(code(url, entry["address"])).hex()
        if onchain != entry["codeHash"]:
            raise SystemExit(f"{entry['chainId']} {entry['address']}: the chain's code hashes to {onchain}, "
                             f"not to the recompiled {entry['codeHash']}")
        print(f"{entry['chainId']} {entry['address']} {entry['name']}: the chain's code is the recompiled one")
    if unchecked:
        print(f"{unchecked} entries on networks not named were not checked")
    return 1 if unchecked else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
