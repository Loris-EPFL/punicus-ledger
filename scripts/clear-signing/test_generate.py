#!/usr/bin/env python3
"""Tests of the clear-signing generator and its Keccak-256 (docs/clear-signing.md, A1).

    python3 -I scripts/clear-signing/test_generate.py

ci.yml runs them before it regenerates the catalog, so a broken check cannot pass silently.
"""
import copy
import hashlib
import json
import re
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
        for bad in ("Mail(address  to)", "Mail(address to, string body)", "A(B b,C c)C(uint256 x)B(uint256 y)",
                    "Mail(uint to)", "Mail(Person from)", "Mail(address to)Extra(uint256 x)", "A(B b)B(C c)"):
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
        self.refused(lambda d: self.fields(d)[1].update(visible={"mustMatch": ["$.metadata.constants.max"]}))
        self.refused(lambda d: self.fields(d)[1].update(visible={"ifNotIn": ["0x01"]}))
        self.refused(lambda d: self.fields(d)[1].update(visible={"mustMatch": [-1]}))
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

    def test_refuses_a_path_that_names_nothing(self):
        fmt = lambda d: d["display"]["formats"]["pay(address to,uint256 amount)"]  # noqa: E731
        self.refused(lambda d: self.fields(d)[1].update(path="amountt"))
        self.refused(lambda d: self.fields(d)[1].update(path="$.metadata.constants.missing"))
        self.refused(lambda d: self.fields(d)[1].update(path="amount.[]"))
        self.refused(lambda d: self.fields(d).append(
            {"path": "amount", "label": "A", "format": "tokenAmount", "params": {"tokenPath": "nope"}}))
        self.refused(lambda d: fmt(d).update(interpolatedIntent="Pay {nope}"))
        descriptor = minimal_descriptor()
        fmt(descriptor).update(interpolatedIntent="Pay {amount} to {to}")
        self.check(descriptor)

    def test_resolves_paths_into_tuples_arrays_and_messages(self):
        root = generate.calldata_root("f((address a,(uint256 x)[] ys) p,bytes data)", "t")
        for good in ("p.a", "p.ys.[]", "p.ys.[0].x", "p.ys.[-1].x", "#.data", "data.[0:4]", "@.value"):
            generate.resolve_path(good, root, minimal_descriptor(), "t")
        for bad in ("p.b", "p.a.[]", "p.ys.x", "q"):
            with self.assertRaises(generate.Refused, msg=bad):
                generate.resolve_path(bad, root, minimal_descriptor(), "t")
        message = generate.message_root("Order(Item[] items,address owner)Item(address token,uint256 amount)")
        generate.resolve_path("items.[].amount", message, minimal_descriptor(), "t")
        with self.assertRaises(generate.Refused):
            generate.resolve_path("items.[].price", message, minimal_descriptor(), "t")

    def test_refuses_what_a_group_or_a_field_path_cannot_apply(self):
        group = {"label": "G", "visible": {"mustMatch": [0]}, "fields": [{"path": "to", "label": "To"}]}
        self.refused(lambda d: self.fields(d).append(group))
        self.refused(lambda d: self.fields(d).append({"path": "$.metadata.enums.mode", "label": "M"}))

    def test_refuses_a_format_without_what_it_needs(self):
        add = lambda field: (lambda d: self.fields(d).append(dict(path="amount", label="A", **field)))  # noqa: E731
        self.refused(add({"format": "date", "params": {}}))
        self.refused(add({"format": "unit", "params": {"decimals": 2}}))
        self.refused(add({"format": "calldata", "params": {}}))
        self.refused(add({"format": "tokenAmount", "params": {"token": "0x" + "11" * 20, "tokenPath": "to"}}))
        self.refused(lambda d: self.fields(d)[1].pop("label"))
        self.refused(lambda d: d["metadata"].pop("owner"))
        self.refused(lambda d: d["metadata"].update(owner={"name": "x"}))

    def test_includes_never_replace_a_list(self):
        with self.assertRaisesRegex(generate.Refused, "lists or mixed values"):
            generate.merge({"fields": [1, 2]}, {"fields": [3]})

    def test_refuses_a_parameter_value_the_runtime_does_not_implement(self):
        self.refused(lambda d: self.fields(d)[0]["params"].update(types=["spaceship"]))
        self.refused(lambda d: self.fields(d)[0]["params"].update(sources=["coingecko"]))
        self.refused(lambda d: self.fields(d)[0]["params"].update(senderAddress=["$.metadata.constants.nope"]))
        self.refused(lambda d: self.fields(d).append(
            {"path": "amount", "format": "unit", "params": {"decimals": "lots"}}))
        self.refused(lambda d: self.fields(d).append(
            {"path": "amount", "format": "tokenAmount", "params": {"token": "not-an-address"}}))
        self.refused(lambda d: self.fields(d).append(
            {"path": "amount", "format": "tokenAmount", "params": {"tokenPath": "$.metadata.constants.nope"}}))
        self.refused(lambda d: self.fields(d).append({"path": "@.gasPrice", "label": "Gas"}))
        self.refused(lambda d: d["display"]["formats"]["pay(address to,uint256 amount)"].update(
            interpolatedIntent="Pay {amount..x}"))
        self.refused(lambda d: d["display"]["formats"]["pay(address to,uint256 amount)"].update(
            interpolatedIntent="Pay {amount"))


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

    def test_the_kotlin_holds_the_json_and_its_sha256(self):
        catalog = {"x": "$.metadata ${'$'} \"\"\" end", "pad": "y" * (2 * generate.CHUNK_BYTES),
                   "descriptors": {}, "deployments": []}
        source = generate.kotlin(catalog)
        chunks = re.findall(r'^    """(.*)""",$', source, re.M)
        self.assertGreater(len(chunks), 2)
        joined = "".join(chunk.replace("${'$'}", "$") for chunk in chunks)
        self.assertEqual(json.loads(joined), catalog)
        digest = re.search(r'CLEAR_SIGNING_CATALOG_SHA256 = "([0-9a-f]{64})"', source).group(1)
        self.assertEqual(hashlib.sha256(joined.encode("ascii")).hexdigest(), digest)
        for chunk in chunks:
            self.assertNotIn('"""', chunk)
            self.assertNotRegex(chunk.replace("${'$'}", ""), r"\$")

    def test_a_deployment_outside_its_descriptor_is_refused(self):
        contracts = json.loads(generate.CONTRACTS.read_text(encoding="utf-8"))
        contracts["deployments"][0]["address"] = "0x" + "22" * 20
        with mock.patch.object(generate, "CONTRACTS", mock.Mock(read_text=lambda **_: json.dumps(contracts))):
            with self.assertRaisesRegex(generate.Refused, "not among the deployments"):
                generate.build()

    def test_a_file_vendored_without_a_pin_is_refused(self):
        path = next(iter(generate.PINNED))
        pins = {k: v for k, v in generate.PINNED.items() if k != path}
        with mock.patch.object(generate, "PINNED", pins):
            with self.assertRaisesRegex(generate.Refused, "vendored without a pin"):
                generate.check_pins()


class LoadTest(unittest.TestCase):
    def load(self, text):
        with mock.patch.object(generate, "VENDORED", mock.MagicMock()) as vendored:
            vendored.__truediv__.return_value.is_file.return_value = True
            vendored.__truediv__.return_value.read_text.return_value = text
            return generate.load("x.json")

    def test_refuses_a_repeated_key(self):
        self.assertEqual(self.load('{"a": 1, "b": {"c": 2}}'), {"a": 1, "b": {"c": 2}})
        with self.assertRaisesRegex(generate.Refused, "repeats"):
            self.load('{"label": "Amount", "label": "To"}')

    def test_refuses_what_a_reader_cannot_see(self):
        for text in ('{"label": "Amount\\nTo: 0x0"}', '{"label": "\\u202eevil"}', '{"label": "a\\u200bb"}',
                     '{"\\u0007": 1}'):
            with self.assertRaisesRegex(generate.Refused, "printable ASCII", msg=text):
                self.load(text)


class UpgradeTest(unittest.TestCase):
    HASH = "0x" + "ab" * 32

    def test_a_declaration_names_the_code_it_was_read_for(self):
        declared = {"why": "x", "forCodeHash": self.HASH, "self": True}
        generate.check_upgrade({"kind": "slot", "slot": "0x" + "36" * 32, "implementation": "0x" + "12" * 20,
                                "implementationCodeHash": self.HASH, "implementationDelegatecall": declared}, "t")
        with self.assertRaisesRegex(generate.Refused, "other code"):
            generate.check_upgrade({"kind": "slot", "slot": "0x" + "36" * 32, "implementation": "0x" + "12" * 20,
                                    "implementationCodeHash": "0x" + "cd" * 32,
                                    "implementationDelegatecall": declared}, "t")
        for bad in ({"why": "x", "self": True}, {"why": "x", "forCodeHash": self.HASH},
                    {"why": "x", "forCodeHash": self.HASH, "libraries": ["0x" + "12" * 20]}):
            with self.assertRaises(generate.Refused, msg=bad):
                generate.check_declaration(bad, None, "t")

    def test_accepts_each_kind(self):
        generate.check_upgrade({"kind": "none", "why": "x"}, "t")
        generate.check_upgrade({"kind": "slot", "slot": "0x" + "36" * 32, "implementation": "0x" + "12" * 20,
                                "implementationCodeHash": self.HASH}, "t")
        generate.check_upgrade({"kind": "call", "call": "implementation()", "selector": "0x5c60da1b",
                                "returns": "0x" + "00" * 32, "implementationCodeHash": self.HASH}, "t")

    def test_refuses_a_wrong_selector_or_a_missing_pin(self):
        with self.assertRaisesRegex(generate.Refused, "selector"):
            generate.check_upgrade({"kind": "call", "call": "implementation()", "selector": "0x00000000",
                                    "returns": "0x" + "00" * 32, "implementationCodeHash": self.HASH}, "t")
        with self.assertRaisesRegex(generate.Refused, "not pinned"):
            generate.check_upgrade({"kind": "slot", "slot": "0x" + "36" * 32, "implementation": "0x" + "12" * 20,
                                    "implementationCodeHash": "pending"}, "t")
        with self.assertRaises(generate.Refused):
            generate.check_upgrade({"kind": "maybe"}, "t")


class PinTest(unittest.TestCase):
    def test_delegatecall_is_found_as_an_opcode_only(self):
        self.assertTrue(pin_code_hashes.has_delegatecall(bytes([0x60, 0x00, 0xF4])))
        self.assertFalse(pin_code_hashes.has_delegatecall(bytes([0x60, 0xF4, 0x00])))  # PUSH1 data
        trailer = bytes([0xA1, 0x65]) + b"bzzr0" + bytes([0x58, 0x20]) + bytes([0xF4] * 32) + bytes([0x00, 0x29])
        self.assertFalse(pin_code_hashes.has_delegatecall(bytes([0x00]) + trailer))  # solc's metadata
        self.assertTrue(pin_code_hashes.has_delegatecall(bytes([0x60, 0x00, 0xF2])))  # CALLCODE
        # A tail that merely looks like a map, its first key not one solc writes, is code and is scanned.
        # (0x63 reads as PUSH4 there, so the DELEGATECALL follows the four bytes it pushes.)
        fake = bytes([0xA1, 0x63]) + b"abc" + bytes([0x00, 0xF4]) + bytes([0x00, 0x07])
        self.assertTrue(pin_code_hashes.has_delegatecall(bytes([0x00]) + fake))

    def test_push20_operands(self):
        address = bytes(range(1, 21))
        self.assertEqual(pin_code_hashes.push20_operands(bytes([0x73]) + address + bytes([0xF4])),
                         {"0x" + address.hex()})
        self.assertEqual(pin_code_hashes.push20_operands(bytes([0x7F]) + bytes([0x73]) + address + bytes(11)), set())

    def test_only_loopback_is_read(self):
        with self.assertRaises(SystemExit):
            pin_code_hashes.Rpc("https://mainnet.example.org")


if __name__ == "__main__":
    unittest.main()
