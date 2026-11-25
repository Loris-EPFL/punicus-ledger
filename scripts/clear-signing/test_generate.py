#!/usr/bin/env python3
"""Tests of the clear-signing generator and its Keccak-256 (docs/clear-signing.md, A1).

    python3 -I scripts/clear-signing/test_generate.py

ci.yml runs them before it regenerates the catalog, so a broken check cannot pass silently.
"""
import copy
import json
import pathlib
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import generate  # noqa: E402
import pin_code_hashes  # noqa: E402
from keccak import keccak256  # noqa: E402


class KeccakTest(unittest.TestCase):
    def test_known_digests(self):
        self.assertEqual(keccak256(b"").hex(), "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
        self.assertEqual(keccak256(b"transfer(address,uint256)")[:4].hex(), "a9059cbb")
        # Permit2's PERMIT_SINGLE_TYPEHASH, as its source declares it.
        self.assertEqual(
            keccak256(b"PermitSingle(PermitDetails details,address spender,uint256 sigDeadline)"
                      b"PermitDetails(address token,uint160 amount,uint48 expiration,uint48 nonce)").hex(),
            "f3841cd1ff0085026a6327b620b67997ce40f282c88a8e905a7a5626e310f3d0")

    def test_several_blocks(self):
        # Past one 136-byte block, and exactly at the boundary. Recorded from this implementation after its digests
        # of every pinned deployment's code (up to tens of kilobytes) matched their verified account proofs.
        self.assertEqual(keccak256(b"a" * 200).hex(), "96ea54061def936c4be90b518992fdc6f12f535068a256229aca54267b4d084d")
        self.assertEqual(len({keccak256(b"x" * n) for n in (135, 136, 137)}), 3)


class SignatureTest(unittest.TestCase):
    def test_names_dropped_and_tuples_flattened(self):
        self.assertEqual(generate.canonical_signature("transfer(address to,uint256 value)", "t"),
                         "transfer(address,uint256)")
        self.assertEqual(
            generate.canonical_signature(
                "exactInputSingle((address tokenIn, address tokenOut, uint24 fee, address recipient, "
                "uint256 amountIn, uint256 amountOutMinimum, uint160 sqrtPriceLimitX96) params)", "t"),
            "exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))")
        self.assertEqual(generate.canonical_signature("f((uint a,bytes32[] b)[2][] x, uint[] y)", "t"),
                         "f((uint256,bytes32[])[2][],uint256[])")
        self.assertEqual(generate.canonical_signature("deposit()", "t"), "deposit()")

    def test_refuses_what_it_cannot_read(self):
        for bad in ("transfer(address to", "transfer(adress to)", "not a signature", "f(uint7 x)"):
            with self.assertRaises(generate.Refused, msg=bad):
                generate.canonical_signature(bad, "t")

    def test_encode_type(self):
        self.assertEqual(generate.check_encode_type(
            "PermitBatch(PermitDetails[] details,address spender,uint256 sigDeadline)"
            "PermitDetails(address token,uint160 amount,uint48 expiration,uint48 nonce)", "t"), "PermitBatch")
        for bad in ("Mail(address  to)", "Mail(address to, string body)", "A(B b,C c)C(uint256 x)B(uint256 y)"):
            with self.assertRaises(generate.Refused, msg=bad):
                generate.check_encode_type(bad, "t")


def minimal_descriptor() -> dict:
    return {
        "$schema": "https://eips.ethereum.org/assets/eip-7730/erc7730-v2.schema.json",
        "context": {"contract": {"deployments": [{"chainId": 1, "address": "0x" + "11" * 20}]}},
        "metadata": {"owner": "Example", "enums": {"mode": {"1": "one"}}},
        "display": {"formats": {"pay(address to,uint256 amount)": {
            "intent": "Pay",
            "fields": [{"path": "to", "label": "To", "format": "addressName", "params": {"types": ["eoa"]}},
                       {"path": "amount", "label": "Amount", "format": "amount", "visible": {"mustMatch": [0]}}],
        }}},
    }


class DescriptorTest(unittest.TestCase):
    def check(self, descriptor):
        return generate.check_descriptor("test.json", descriptor)

    def test_accepts_the_minimal_descriptor(self):
        self.assertEqual(self.check(minimal_descriptor()), "calldata")

    def refused(self, change):
        descriptor = minimal_descriptor()
        change(descriptor)
        with self.assertRaises(generate.Refused):
            self.check(descriptor)

    def fields(self, descriptor):
        return descriptor["display"]["formats"]["pay(address to,uint256 amount)"]["fields"]

    def test_refuses_another_schema_major(self):
        self.refused(lambda d: d.update({"$schema": "https://eips.ethereum.org/assets/eip-7730/erc7730-v1.schema.json"}))

    def test_refuses_an_unimplemented_format_param_or_key(self):
        self.refused(lambda d: self.fields(d)[0].update(format="interoperableAddressName"))
        self.refused(lambda d: self.fields(d)[0]["params"].update(unknownParam=1))
        self.refused(lambda d: self.fields(d)[0].update(encryption={"scheme": "x"}))
        self.refused(lambda d: d["context"]["contract"].update(factory={"deployEvent": "x"}))
        self.refused(lambda d: d["display"].update(definitions={}))
        self.refused(lambda d: d["metadata"].update(maps={}))

    def test_refuses_a_bad_visibility_path_or_reference(self):
        self.refused(lambda d: self.fields(d)[1].update(visible={"mustMatch": [0], "ifNotIn": [1]}))
        self.refused(lambda d: self.fields(d)[1].update(visible="sometimes"))
        self.refused(lambda d: self.fields(d)[1].update(path="amount..x"))
        self.refused(lambda d: self.fields(d).append(
            {"path": "to", "format": "enum", "params": {"$ref": "$.metadata.enums.missing"}}))

    def test_refuses_two_formats_with_one_selector(self):
        descriptor = minimal_descriptor()
        formats = descriptor["display"]["formats"]
        formats["pay(address payee,uint256 value)"] = copy.deepcopy(formats["pay(address to,uint256 amount)"])
        with self.assertRaises(generate.Refused):
            generate.compile_formats("test.json", "calldata", descriptor)

    def test_includes_merge_with_the_including_file_winning(self):
        self.assertEqual(generate.merge({"a": {"x": 1, "y": 2}, "b": 1}, {"a": {"y": 3}, "c": 4}),
                         {"a": {"x": 1, "y": 3}, "b": 1, "c": 4})


class BuildTest(unittest.TestCase):
    def test_the_committed_catalog_builds(self):
        catalog = generate.build()
        self.assertEqual(catalog["registry"]["commit"], generate.REGISTRY_COMMIT)
        for deployment in catalog["deployments"]:
            self.assertIn(deployment["network"], generate.NETWORKS)
            self.assertRegex(deployment["codeHash"], r"^0x[0-9a-f]{64}$")

    def test_a_vendored_file_that_differs_from_its_pin_is_refused(self):
        path = next(iter(generate.PINNED))
        with mock.patch.dict(generate.PINNED, {path: "00" * 32}):
            with self.assertRaises(generate.Refused):
                generate.build()

    def test_the_output_is_deterministic(self):
        self.assertEqual(generate.kotlin(generate.build()), generate.kotlin(generate.build()))

    def test_a_deployment_outside_its_descriptor_is_refused(self):
        contracts = json.loads(generate.CONTRACTS.read_text(encoding="utf-8"))
        contracts["deployments"][0]["address"] = "0x" + "22" * 20
        with mock.patch.object(generate, "CONTRACTS", mock.Mock(read_text=lambda **_: json.dumps(contracts))):
            with self.assertRaises(generate.Refused):
                generate.build()


class PinTest(unittest.TestCase):
    def test_delegatecall_is_found_as_an_opcode_only(self):
        self.assertTrue(pin_code_hashes.has_delegatecall(bytes([0x60, 0x00, 0xF4])))
        self.assertFalse(pin_code_hashes.has_delegatecall(bytes([0x60, 0xF4, 0x00])))  # PUSH1 data
        trailer = bytes([0xA1, 0x65]) + b"bzzr0" + bytes([0x58, 0x20]) + bytes([0xF4] * 32) + bytes([0x00, 0x29])
        self.assertFalse(pin_code_hashes.has_delegatecall(bytes([0x00]) + trailer))  # solc's metadata

    def test_only_loopback_is_read(self):
        with self.assertRaises(SystemExit):
            pin_code_hashes.Rpc("https://mainnet.example.org")


if __name__ == "__main__":
    unittest.main()
