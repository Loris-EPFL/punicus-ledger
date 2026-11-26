#!/usr/bin/env python3
"""Vendors, from Sourcify's repository, what phase C's list needs to be recompiled (a refresh's first step).

    python3 -I scripts/verified-abis/fetch.py

For each contract of scripts/verified-abis/contracts.json it asks Sourcify's API
(`/v2/contract/{chainId}/{address}?fields=all`) and refuses anything but an exact match, of the runtime code
and in Sourcify's own verdict (docs/clear-signing.md, *Phase C*, decision 4). It keeps the metadata, the
sources and the on-chain runtime code, each stored once under vendor/blobs/ by its sha256, and writes for each
contract a manifest (vendor/contracts/<chainId>-<address>.json) naming them by that hash, and its compiler's
version. It also pins each compiler's linux-amd64 build by the sha256 soliditylang.org lists for it
(vendor/solc.json). It never runs in the app or in CI: what it writes is committed, and a PR reviews every
changed byte; verify.mjs then recompiles it all, offline but for the compilers.
"""
import hashlib
import json
import pathlib
import shutil
import sys
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
CONTRACTS = HERE / "contracts.json"
VENDOR = HERE / "vendor"
SOURCIFY = "https://sourcify.dev/server/v2/contract/{chain}/{address}?fields=all"
SOLC_LIST = "https://binaries.soliditylang.org/linux-amd64/list.json"


def get(url: str) -> bytes:
    # A plain client name: soliditylang.org refuses Python's default one.
    request = urllib.request.Request(url, headers={"User-Agent": "verified-abis-fetch"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def blob(data: bytes) -> str:
    """Stores [data] once, by its sha256, and returns that hash."""
    digest = hashlib.sha256(data).hexdigest()
    path = VENDOR / "blobs" / digest
    if not path.exists():
        path.write_bytes(data)
    return digest


def main() -> int:
    listed = json.loads(CONTRACTS.read_text(encoding="utf-8"))["contracts"]
    if VENDOR.exists():
        shutil.rmtree(VENDOR)
    (VENDOR / "blobs").mkdir(parents=True)
    (VENDOR / "contracts").mkdir()
    versions = set()
    for entry in listed:
        chain, address = entry["chainId"], entry["address"]
        answer = json.loads(get(SOURCIFY.format(chain=chain, address=address)))
        if answer.get("match") != "exact_match" or answer.get("runtimeMatch") != "exact_match":
            raise SystemExit(f"refused: {entry['label']} on {chain} is {answer.get('match')}, not an exact match")
        if answer["compilation"].get("language") != "Solidity":
            raise SystemExit(f"refused: {entry['label']} on {chain} is not Solidity")
        metadata = answer["metadata"]
        version = answer["compilation"]["compilerVersion"]
        versions.add(version)
        manifest = {
            "chainId": chain,
            "address": address,
            "compilerVersion": version,
            "metadata": blob(json.dumps(metadata, sort_keys=True, separators=(",", ":")).encode("utf-8")),
            "sources": {path: blob(source["content"].encode("utf-8")) for path, source in sorted(answer["sources"].items())},
            "runtime": blob(answer["runtimeBytecode"]["onchainBytecode"].lower().encode("ascii")),
        }
        target = VENDOR / "contracts" / f"{chain}-{address}.json"
        target.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"{entry['label']} on {chain}: exact match, {version}")
    builds = json.loads(get(SOLC_LIST))["builds"]
    pins = {}
    for version in sorted(versions):
        build = next((b for b in builds if b["longVersion"] == version), None)
        if build is None:
            raise SystemExit(f"refused: no linux-amd64 build of solc {version} is listed")
        pins[version] = {"path": build["path"], "sha256": build["sha256"].removeprefix("0x")}
    (VENDOR / "solc.json").write_text(json.dumps(pins, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
