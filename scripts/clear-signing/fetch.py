#!/usr/bin/env python3
"""Vendors the registry files the curated list needs, at generate.py's REGISTRY_COMMIT (a refresh's first step).

    python3 -I scripts/clear-signing/fetch.py

Reads scripts/clear-signing/contracts.json and downloads, from raw.githubusercontent.com at REGISTRY_COMMIT,
every descriptor it names, the files those include, their test vectors (`testsv2/<name>.tests.json`) and their
auditor attestations (`sigs/<name>.eip155-*.json`, listed from the commit's tree), into
scripts/clear-signing/registry/. It replaces that folder, and writes REGISTRY_COMMIT into contracts.json. It
never runs in the app or in CI: what it writes is committed and pinned, and a PR reviews every changed byte
(`generate.py --print-pins` gives the new pins).
"""
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from generate import CONTRACTS, REGISTRY_COMMIT, REGISTRY_REPOSITORY, VENDORED  # noqa: E402

RAW = f"https://raw.githubusercontent.com/{REGISTRY_REPOSITORY}/{REGISTRY_COMMIT}/"


def fetch(path: str) -> bytes:
    with urllib.request.urlopen(RAW + path, timeout=30) as response:
        return response.read()


def tree() -> list:
    out = subprocess.run(
        ["gh", "api", f"repos/{REGISTRY_REPOSITORY}/git/trees/{REGISTRY_COMMIT}?recursive=1", "--jq", ".tree[].path"],
        check=True, capture_output=True, text=True)
    return out.stdout.split()


def main() -> int:
    contracts = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    contracts["registry"] = {"repository": REGISTRY_REPOSITORY, "commit": REGISTRY_COMMIT}
    CONTRACTS.write_text(json.dumps(contracts, indent=2) + "\n", encoding="utf-8")
    paths = sorted({p for d in contracts["deployments"] for p in d["descriptors"]})
    every = set(tree())
    wanted, files = list(paths), {}
    while wanted:
        path = wanted.pop()
        if path in files:
            continue
        files[path] = fetch(path)
        include = json.loads(files[path]).get("includes")
        if include:
            wanted.append((pathlib.PurePosixPath(path).parent / include).as_posix())
    for path in paths:
        stem = pathlib.PurePosixPath(path)
        for extra in sorted(every):
            if extra == f"{stem.parent}/testsv2/{stem.stem}.tests.json" or \
                    extra.startswith(f"{stem.parent}/sigs/{stem.stem}.eip155-"):
                files[extra] = fetch(extra)
    files["specs/erc7730-v2.schema.json"] = fetch("specs/erc7730-v2.schema.json")
    if VENDORED.exists():
        shutil.rmtree(VENDORED)
    for path, data in sorted(files.items()):
        target = VENDORED / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
