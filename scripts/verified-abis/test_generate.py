#!/usr/bin/env python3
"""Tests of the verified-ABI generator (docs/clear-signing.md, *Phase C*).

    python3 -I scripts/verified-abis/test_generate.py

ci.yml runs them with the recompile, so a broken generator cannot pass silently.
"""
import hashlib
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import generate  # noqa: E402

LISTED = {"contracts": [
    {"network": "GNOSIS", "chainId": 100, "address": "0x3b6a7f0bdb6663007f95dbd7fddef815f7596715", "eip1167Proxies": True},
    {"network": "ETHEREUM", "chainId": 1, "address": "0x38869bf66a61cf6bdb996a6ae40d5853fd43b526"},
]}
VERIFIED = [
    {"chainId": 1, "address": "0x38869bf66a61cf6bdb996a6ae40d5853fd43b526", "name": "MultiSend", "codeHash": "0x11",
     "functions": [{"selector": "0x8d80ff0a", "name": "multiSend", "inputs": [{"name": "transactions", "type": "bytes"}]}]},
    {"chainId": 100, "address": "0x3b6a7f0bdb6663007f95dbd7fddef815f7596715", "name": "ERC20SimpleSwap",
     "codeHash": "0x22", "functions": []},
]


class GenerateTest(unittest.TestCase):
    def test_a_minimal_proxy_hashes_as_the_app_and_bee_pin_the_chequebook(self):
        # data/.../SwarmDeployments.kt: chequebookCodeHash, Bee's AcceptedChequebookBytecodeHash (docs/swarm.md).
        self.assertEqual(
            "0x81d3de06cadb0970fc653f24cef4689243f9a3d702236370ecf4613673048145",
            generate.proxy_code_hash("0x3b6a7f0bdb6663007f95dbd7fddef815f7596715"),
        )

    def test_a_proxy_is_held_to_its_network_and_its_implementation(self):
        catalog = generate.build(VERIFIED, LISTED)
        self.assertEqual(["ETHEREUM", "GNOSIS"], [c["network"] for c in catalog["contracts"]])
        self.assertEqual([{
            "network": "GNOSIS",
            "codeHash": generate.proxy_code_hash("0x3b6a7f0bdb6663007f95dbd7fddef815f7596715"),
            "implementation": "0x3b6a7f0bdb6663007f95dbd7fddef815f7596715",
            "implementationCodeHash": "0x22",
        }], catalog["proxies"])

    def test_a_proxied_implementation_not_recompiled_is_refused(self):
        with self.assertRaises(SystemExit):
            generate.build(VERIFIED[:1], LISTED)

    def test_the_kotlin_holds_the_json_in_chunks_with_its_sha256(self):
        catalog = generate.build(VERIFIED, LISTED)
        written = generate.kotlin(catalog)
        text = json.dumps(catalog, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
        self.assertIn(f'VERIFIED_ABI_CATALOG_SHA256 = "{hashlib.sha256(text.encode()).hexdigest()}"', written)
        self.assertIn('"""' + text + '""",', written)


if __name__ == "__main__":
    unittest.main()
