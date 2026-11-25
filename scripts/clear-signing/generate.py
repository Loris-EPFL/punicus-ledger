#!/usr/bin/env python3
"""Writes the built-in clear-signing catalog as Kotlin for :data (docs/clear-signing.md, *A1*).

    python3 -I scripts/clear-signing/generate.py              # check the pins, write the catalog
    python3 -I scripts/clear-signing/generate.py --print-pins # print the sha256 of every vendored file

Reads the ERC-7730 descriptors vendored under scripts/clear-signing/registry/ (a snapshot of
ethereum/clear-signing-erc7730-registry at REGISTRY_COMMIT) and the curated list in
scripts/clear-signing/contracts.json, and writes
data/src/commonMain/kotlin/eth/serotine/data/clearsigning/ClearSigningCatalog.kt.

It refuses, and writes nothing, when:
  - a vendored file's sha256 is not its pin in PINNED, or a file is vendored without one;
  - a deployment of the list is not among its descriptor's own `deployments`, names a network other than
    Ethereum, Gnosis Chain or Sepolia, or has no code hash pinned (scripts/clear-signing/pin_code_hashes.py);
  - a descriptor's `$schema` is not of major version 2, or it holds any key, format, parameter or value the
    runtime does not implement (SUPPORTED below): nothing is dropped silently;
  - a function signature or EIP-712 type is not canonical enough to hash, or two formats share a selector.

The owner's decisions of 2026-11-25 (#240): the descriptors are built in and never fetched by the app; a
refresh is a step of every release PR, through a PR that moves PINNED, REGISTRY_COMMIT and the code hashes.
"""
import hashlib
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from keccak import keccak256  # noqa: E402

REGISTRY_REPOSITORY = "ethereum/clear-signing-erc7730-registry"
REGISTRY_COMMIT = "225ecc69f050cfb255d86203ed54c9cbbba4c71e"
HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VENDORED = HERE / "registry"
CONTRACTS = HERE / "contracts.json"
TARGET = ROOT / "data/src/commonMain/kotlin/eth/serotine/data/clearsigning/ClearSigningCatalog.kt"
CHUNK_BYTES = 60_000  # a JVM class file holds a string constant of at most 65535 bytes

# The sha256 of every file vendored under registry/, by its path in the registry. A refresh moves these, and
# the reviewer of that PR sees each moved line.
PINNED = {
    "registry/aave/calldata-lpv3.json": "89eba0020e349f733d52d50437d3ab7ee5c2b1584be8f44428423fed699d3698",
    "registry/aave/testsv2/calldata-lpv3.tests.json": "15a7cc441c0ba34bd35a35d8356d915e11156689dbd1f6e9c3ac3c19f4345e8e",
    "registry/ens/calldata-ETHRegistrarController.json": "0c6f8928034198423815b5bfdb56d9031eb03caf02e01e813b9e21a41ce9a3d5",
    "registry/ens/testsv2/calldata-ETHRegistrarController.tests.json": "a8e90c48d5aec7da0c253bbdf028825a509efe54b02ebae13ffdb15ed61f9556",
    "registry/lido/calldata-wstETH.json": "a5fe1954ebc04c6f236e237566d374c474368a5346446a23a1cca6171026378b",
    "registry/lido/sigs/calldata-wstETH.eip155-1-0x3846c3A30E62075Fa916216b35EF04B8F53931f6.json": "50ad69b97e54695d7cddcfa7e9e21f8101977388a1b2897894963ddb992bc326",
    "registry/lido/testsv2/calldata-wstETH.tests.json": "85712d33446db0f6ac1b16c1215ec2731dd575934aba83d9fbfe6577dfae29f9",
    "registry/opensea/calldata-Seaport-1.6.json": "a0e981ed74b762c7c5ce3687eb416259da2983bc91101929a66cb5ed9306d801",
    "registry/opensea/testsv2/calldata-Seaport-1.6.tests.json": "7868e5f49aee9135c8c32522fc475fd3eb287a583a1c3a806599b25e50792d09",
    "registry/uniswap/calldata-Permit2.json": "0e490499c465d9623eea11288fde3eb2a661d5955b8bdc28191724a79b70700d",
    "registry/uniswap/calldata-UniswapV3Router02.json": "76cddf255ac5cee8504f447581595c3006ab932e4e55518faba47576f136b02a",
    "registry/uniswap/common-eip712-uniswap.json": "d19927939c74530ffd5583ea3dd3e4d9b8154daaae9ac4916df11454b29bb2fd",
    "registry/uniswap/eip712-uniswap-permit2.json": "8fbd64c5c5d85154d70a26a5f088ce1c96f4eedd873bdb9182aac6eddfb73ea8",
    "registry/uniswap/testsv2/calldata-Permit2.tests.json": "6f6d49253dc2269de3a5082c3850b1613c5519fa5576332cf36bf419f85930ee",
    "registry/uniswap/testsv2/calldata-UniswapV3Router02.tests.json": "c542bbe8b0a0d3b2b956dab56e797e1efd1fc74625423702e4625346fabaf3e6",
    "registry/uniswap/testsv2/eip712-uniswap-permit2.tests.json": "b329cb5fd8d4282465d6b9bea618ea65cf81178afb61da88239ec97d47f39caf",
    "registry/weth/calldata-weth.json": "8d2aaf36389dd5af0b78076b37d567255086d4b7ee3a655af7d7f5643a9b4e4b",
    "registry/weth/testsv2/calldata-weth.tests.json": "be9d605def7540ed571b5225d450aa6ba5f9c3ce7b50f6946f069282acad7886",
    "specs/erc7730-v2.schema.json": "6c1a95762881f54e25f6cb83846091dde79cbbac4a1d26fd24b74f36df30ba98",
}

NETWORKS = {"ETHEREUM": 1, "GNOSIS": 100, "SEPOLIA": 11155111}
SCHEMA_MAJOR = 2

# What the runtime implements (docs/clear-signing.md, A3). Anything else fails the build.
FORMAT_PARAMS = {
    "raw": set(),
    "amount": set(),
    "tokenAmount": {"tokenPath", "token", "nativeCurrencyAddress", "threshold", "message"},
    "addressName": {"types", "sources", "senderAddress"},
    "date": {"encoding"},
    "duration": set(),
    "unit": {"base", "decimals", "prefix"},
    "enum": {"$ref"},
    "chainId": set(),
    "calldata": {"calleePath"},
    "nftName": {"collectionPath"},
    "tokenTicker": set(),
}
FIELD_KEYS = {"path", "label", "format", "params", "visible", "fields", "iteration", "$id"}
FORMAT_KEYS = {"$id", "intent", "interpolatedIntent", "fields"}
VISIBLE_WORDS = {"always", "never", "optional"}
VISIBLE_RULES = {"mustMatch", "ifNotIn"}
DATE_ENCODINGS = {"timestamp", "blockheight"}
ITERATIONS = {"sequential", "bundled"}
METADATA_KEYS = {"owner", "info", "contractName", "constants", "enums"}
INFO_KEYS = {"url", "deploymentDate", "legalName"}
CONTEXT_KEYS = {"$id", "contract", "eip712"}
CONTRACT_KEYS = {"deployments"}
EIP712_KEYS = {"deployments", "domain"}
DOMAIN_KEYS = {"name", "version", "chainId", "verifyingContract"}
TOP_KEYS = {"$schema", "context", "metadata", "display", "includes"}

PATH_SEGMENT = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*|\[\]|\[-?\d+\]|\[-?\d*:-?\d*\])$")
ADDRESS = re.compile(r"^0x[0-9a-fA-F]{40}$")
HASH = re.compile(r"^0x[0-9a-f]{64}$")
ELEMENTARY = re.compile(r"^(?:address|bool|string|bytes(?:[1-9]|[12][0-9]|3[0-2])?|u?int(?:8|16|24|32|40|48|56|64|72|80|88|96|"
                        r"104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?)$")


class Refused(Exception):
    """A reason the build refuses its input."""


def refuse(where: str, why: str):
    raise Refused(f"{where}: {why}")


# ---------------------------------------------------------------------------------------------------------
# Pins

def vendored_files() -> list:
    return sorted(p.relative_to(VENDORED).as_posix() for p in VENDORED.rglob("*") if p.is_file())


def check_pins() -> None:
    present = set(vendored_files())
    for path in sorted(present - PINNED.keys()):
        refuse(path, "vendored without a pin in PINNED")
    for path in sorted(PINNED.keys() - present):
        refuse(path, "pinned in PINNED but not vendored")
    for path in sorted(present):
        digest = hashlib.sha256((VENDORED / path).read_bytes()).hexdigest()
        if digest != PINNED[path]:
            refuse(path, f"sha256 {digest}, the pin is {PINNED[path]}")


# ---------------------------------------------------------------------------------------------------------
# Signatures

def split_top(text: str, where: str) -> list:
    """Splits [text] at the commas outside parentheses."""
    parts, depth, start = [], 0, 0
    for i, ch in enumerate(text):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth < 0:
                refuse(where, "unbalanced parentheses")
        elif ch == "," and depth == 0:
            parts.append(text[start:i])
            start = i + 1
    if depth:
        refuse(where, "unbalanced parentheses")
    parts.append(text[start:])
    return [p.strip() for p in parts] if text.strip() else []


def canonical_type(text: str, where: str) -> str:
    """The canonical ABI type of one parameter, its name dropped: `(address a, uint b)[] x` → `(address,uint256)[]`."""
    text = text.strip()
    if text.startswith("tuple("):
        text = text[len("tuple"):]
    if text.startswith("("):
        depth = 0
        for i, ch in enumerate(text):
            depth += ch == "("
            depth -= ch == ")"
            if depth == 0:
                break
        inner, rest = text[1:i], text[i + 1:]
        suffix = re.match(r"^((?:\[\d*\])*)\s*(?:[A-Za-z_][A-Za-z0-9_]*)?$", rest.strip())
        if not suffix:
            refuse(where, f"cannot read the tuple's suffix {rest!r}")
        members = [canonical_type(member, where) for member in split_top(inner, where)]
        return "(" + ",".join(members) + ")" + suffix.group(1)
    match = re.match(r"^([a-z0-9]+)((?:\[\d*\])*)(?:\s+(?:indexed\s+)?[A-Za-z_][A-Za-z0-9_]*)?$", text)
    if not match:
        refuse(where, f"cannot read the parameter {text!r}")
    base, arrays = match.groups()
    base = {"uint": "uint256", "int": "int256"}.get(base, base)
    if not ELEMENTARY.match(base):
        refuse(where, f"not an ABI type: {base!r}")
    return base + arrays


def type_tree(text: str, where: str):
    """One parameter's name and its type as a tree: ("elem", base) | ("tuple", {name: tree}) | ("array", tree)."""
    text = text.strip()
    if text.startswith("tuple("):
        text = text[len("tuple"):]
    if text.startswith("("):
        depth = 0
        for i, ch in enumerate(text):
            depth += ch == "("
            depth -= ch == ")"
            if depth == 0:
                break
        members = {}
        for member in split_top(text[1:i], where):
            name, tree = type_tree(member, where)
            if name is None or name in members:
                refuse(where, f"tuple member {member!r} has no name of its own")
            members[name] = tree
        node, rest = ("tuple", members), text[i + 1:].strip()
    else:
        match = re.match(r"^([a-z0-9]+)(.*)$", text)
        node, rest = ("elem", match.group(1)), match.group(2).strip()
    arrays = re.match(r"^((?:\[\d*\])*)\s*(?:indexed\s+)?([A-Za-z_][A-Za-z0-9_]*)?$", rest)
    if not arrays:
        refuse(where, f"cannot read {text!r}")
    for _ in re.findall(r"\[\d*\]", arrays.group(1)):
        node = ("array", node)
    return arrays.group(2), node


def calldata_root(key: str, where: str):
    """The function's parameters as a tuple tree, by name."""
    match = re.match(r"^[A-Za-z_][A-Za-z0-9_]*\((.*)\)$", key.strip(), re.S)
    members = {}
    for param in split_top(match.group(1), where):
        name, tree = type_tree(param, where)
        if name is not None:
            members[name] = tree
    return ("tuple", members)


def message_root(key: str):
    """An EIP-712 message's primary struct as a tuple tree, its referenced structs expanded."""
    structs = dict(re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\(([^()]*)\)", key))

    def struct(name):
        members = {}
        for member in structs[name].split(",") if structs[name] else []:
            base_and_arrays, field = member.rsplit(" ", 1)
            base = re.match(r"^[A-Za-z0-9_]+", base_and_arrays).group(0)
            node = struct(base) if base in structs else ("elem", base)
            for _ in re.findall(r"\[\d*\]", base_and_arrays):
                node = ("array", node)
            members[field] = node
        return ("tuple", members)

    return struct(next(iter(structs)))


def resolve_path(value: str, root, descriptor: dict, where: str) -> None:
    """Refuses a path that names nothing in the call's parameters (or the message), the container or the
    descriptor's metadata."""
    if value.startswith("$."):
        check_ref(value, descriptor, where)
        return
    if value.startswith("@."):
        return  # check_container named one of CONTAINER_NAMES
    node = root
    for segment in (value[2:] if value.startswith("#.") else value).split("."):
        if segment.startswith("["):
            if node[0] == "array":
                node = node[1] if ":" not in segment else node
            elif node[0] == "elem" and ":" in segment and re.match(r"^(bytes|string)$", node[1]):
                pass
            else:
                refuse(where, f"path {value!r}: {segment} of something that is not an array")
        elif node[0] == "tuple" and segment in node[1]:
            node = node[1][segment]
        else:
            refuse(where, f"path {value!r}: {segment!r} names no parameter there")


def canonical_signature(key: str, where: str) -> str:
    """`transfer(address to,uint256 value)` → `transfer(address,uint256)`."""
    match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$", key.strip(), re.S)
    if not match:
        refuse(where, "not a function signature")
    name, params = match.groups()
    return name + "(" + ",".join(canonical_type(p, where) for p in split_top(params, where)) + ")"


def check_encode_type(key: str, where: str) -> str:
    """The primary type of an EIP-712 `encodeType`; refuses one whose hash would not be the type hash.

    EIP-712's encodeType is the primary struct, then every struct it references, directly or not, each once
    and in alphabetical order; each member `<type> <name>`, one space, its type canonical (`uint256`, never
    `uint`) or a struct of the string.
    """
    structs = re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\(([^()]*)\)", key)
    if not structs or "".join(f"{n}({m})" for n, m in structs) != key:
        refuse(where, "not an EIP-712 encodeType")
    names = [n for n, _ in structs]
    if len(set(names)) != len(names):
        refuse(where, "a struct is defined twice")
    references = {}
    for name, members in structs:
        references[name] = set()
        for member in members.split(",") if members else []:
            match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)((?:\[\d*\])*) ([A-Za-z_][A-Za-z0-9_]*)$", member)
            if not match:
                refuse(where, f"member {member!r} is not canonical (one space, no other)")
            base = match.group(1)
            if base in ("uint", "int") or (not ELEMENTARY.match(base) and base not in names):
                refuse(where, f"member type {base!r} is neither canonical nor a struct of the type")
            if base in names:
                references[name].add(base)
    reached, todo = set(), [names[0]]
    while todo:
        for ref in references[todo.pop()]:
            if ref not in reached and ref != names[0]:
                reached.add(ref)
                todo.append(ref)
    if names[1:] != sorted(reached):
        refuse(where, f"the appended structs are {names[1:]}, the referenced ones {sorted(reached)} in order")
    return names[0]


# ---------------------------------------------------------------------------------------------------------
# Descriptors

def load(path: str) -> dict:
    """A vendored file, refused if a key repeats (json keeps the last one, a reader may stop at the first) or
    if any string holds something outside printable ASCII (a control, bidi or zero-width character, a newline)
    that a reader of the diff cannot see but a signing review would show."""
    file = VENDORED / path
    if not file.is_file():
        refuse(path, "not vendored")

    def pairs(items):
        keys = [key for key, _ in items]
        for key in keys:
            if keys.count(key) > 1:
                refuse(path, f"the key {key!r} repeats in one object")
        return dict(items)

    data = json.loads(file.read_text(encoding="utf-8"), object_pairs_hook=pairs)
    check_strings(data, path)
    return data


def check_strings(value, where: str) -> None:
    if isinstance(value, str):
        if not all(0x20 <= ord(ch) <= 0x7E for ch in value):
            refuse(where, f"{value[:40]!r} holds a character outside printable ASCII")
    elif isinstance(value, dict):
        for key, item in value.items():
            check_strings(key, where)
            check_strings(item, f"{where}.{key}"[:200])
    elif isinstance(value, list):
        for item in value:
            check_strings(item, where)


def merge(base, override, where: str = "includes"):
    """ERC-7730 `includes`: objects merge key by key, the including file's values win.

    A key both files define as a list, or as an object in one and not the other, is refused rather than
    guessed at: replacing a list would drop the included file's fields or deployments without a word.
    """
    if isinstance(base, dict) and isinstance(override, dict):
        merged = dict(base)
        for key, value in override.items():
            merged[key] = merge(base[key], value, f"{where}.{key}") if key in base else value
        return merged
    if isinstance(base, (dict, list)) or isinstance(override, (dict, list)):
        refuse(where, "both files define it, and lists or mixed values are not merged")
    return override


def resolve(path: str) -> dict:
    descriptor = load(path)
    include = descriptor.pop("includes", None)
    if include is None:
        return descriptor
    if not isinstance(include, str):
        refuse(path, "`includes` names one file")
    included_path = (pathlib.PurePosixPath(path).parent / include).as_posix()
    included = resolve(included_path)
    if included.get("$schema") != descriptor.get("$schema"):
        refuse(path, f"includes {included_path} of another $schema")
    return merge(included, descriptor)


def only(obj, allowed: set, where: str) -> None:
    if not isinstance(obj, dict):
        refuse(where, "not an object")
    for key in obj:
        if key not in allowed:
            refuse(where, f"`{key}` is not implemented by the runtime")


def check_path(value, where: str) -> None:
    if not isinstance(value, str) or not value:
        refuse(where, "a path is a non-empty string")
    body = value
    for prefix in ("#.", "@.", "$."):
        if value.startswith(prefix):
            body = value[len(prefix):]
    check_container(value, where)
    for segment in body.split("."):
        if not PATH_SEGMENT.match(segment):
            refuse(where, f"path {value!r}: segment {segment!r} is not implemented")


def check_ref(value, descriptor: dict, where: str) -> None:
    """A `$.metadata.constants.x` or `$.metadata.enums.x` reference names something the descriptor has."""
    match = re.match(r"^\$\.metadata\.(constants|enums)\.([A-Za-z_][A-Za-z0-9_]*)$", value)
    if not match or match.group(2) not in descriptor.get("metadata", {}).get(match.group(1), {}):
        refuse(where, f"reference {value!r} names nothing in the descriptor's metadata")


ADDRESS_TYPES = {"eoa", "wallet", "contract", "token", "collection"}
ADDRESS_SOURCES = {"local", "ens"}
CONTAINER_NAMES = {"from", "to", "value", "chainId"}


def check_value_or_ref(value, kinds: tuple, descriptor: dict, where: str):
    """A `$.metadata…` reference (resolved and checked in turn), or a value of one of [kinds]."""
    if isinstance(value, str) and value.startswith("$."):
        check_ref(value, descriptor, where)
        name = value.split(".")[-1]
        value = descriptor["metadata"]["constants" if ".constants." in value else "enums"][name]
    for kind in kinds:
        if kind == "address" and isinstance(value, str) and ADDRESS.match(value):
            return
        if kind == "uint" and (isinstance(value, int) and not isinstance(value, bool) and value >= 0
                               or isinstance(value, str) and re.match(r"^(?:0x[0-9a-fA-F]{1,64}|\d+)$", value)):
            return
        if kind == "string" and isinstance(value, str):
            return
        if kind == "bool" and isinstance(value, bool):
            return
        if kind == "int" and isinstance(value, int) and not isinstance(value, bool):
            return
    refuse(where, f"{value!r} is not {' or '.join(kinds)}")


def check_param(fmt: str, key: str, value, descriptor: dict, where: str) -> None:
    """Every parameter's value, not its name alone, is one the runtime implements."""
    if key.endswith("Path"):
        if isinstance(value, str) and value.startswith("$."):
            refuse(where, "a path parameter names a path, not a constant")
        check_path(value, where)
    elif key == "$ref":
        check_ref(value, descriptor, where)
        if ".enums." not in value:
            refuse(where, "an enum's $ref names an enum")
    elif key in ("types", "sources"):
        allowed = ADDRESS_TYPES if key == "types" else ADDRESS_SOURCES
        if not isinstance(value, list) or not value or not set(value) <= allowed:
            refuse(where, f"{value!r} is not a list of {sorted(allowed)}")
    elif key == "token":
        check_value_or_ref(value, ("address",), descriptor, where)
    elif key in ("nativeCurrencyAddress", "senderAddress"):
        for i, item in enumerate(value if isinstance(value, list) else [value]):
            check_value_or_ref(item, ("address",), descriptor, f"{where}[{i}]")
    elif key == "threshold":
        check_value_or_ref(value, ("uint",), descriptor, where)
    elif key in ("message", "base"):
        check_value_or_ref(value, ("string",), descriptor, where)
    elif key == "decimals":
        check_value_or_ref(value, ("int",), descriptor, where)
    elif key == "prefix":
        check_value_or_ref(value, ("bool",), descriptor, where)
    elif key == "encoding":
        if value not in DATE_ENCODINGS:
            refuse(where, f"date encoding {value!r} is not implemented")
    else:
        refuse(where, "no check for this parameter's value")


def check_container(value: str, where: str) -> None:
    if value.startswith("@.") and value[2:] not in CONTAINER_NAMES:
        refuse(where, f"{value!r} names no field of the container ({sorted(CONTAINER_NAMES)})")


FORMAT_REQUIRES = {"date": {"encoding"}, "unit": {"base"}, "nftName": {"collectionPath"},
                   "calldata": {"calleePath"}, "enum": {"$ref"}}


def check_field(field: dict, descriptor: dict, where: str, root) -> None:
    only(field, FIELD_KEYS, where)
    if "path" in field and "fields" in field:
        refuse(where, "a group with a path of its own is not implemented")
    if "path" in field:
        check_path(field["path"], where + ".path")
        resolve_path(field["path"], root, descriptor, where + ".path")
        if not isinstance(field.get("label"), str) or not field["label"].strip():
            refuse(where, "every field has a label")
    elif "fields" not in field:
        refuse(where, "a field has a path, or is a group of fields")
    elif "label" in field and not isinstance(field["label"], str):
        refuse(where, "a label is a string")
    visible = field.get("visible", "always")
    if isinstance(visible, str):
        if visible not in VISIBLE_WORDS:
            refuse(where, f"visible {visible!r} is not implemented")
    else:
        only(visible, VISIBLE_RULES, where + ".visible")
        if len(visible) != 1 or not isinstance(next(iter(visible.values())), list):
            refuse(where, "a visibility rule is one of mustMatch or ifNotIn, with a list")
    if "iteration" in field and field["iteration"] not in ITERATIONS:
        refuse(where, f"iteration {field['iteration']!r} is not implemented")
    fmt = field.get("format")
    if fmt is not None:
        if fmt not in FORMAT_PARAMS:
            refuse(where, f"format {fmt!r} is not implemented")
        params = field.get("params", {})
        only(params, FORMAT_PARAMS[fmt], where + ".params")
        for key, value in params.items():
            check_param(fmt, key, value, descriptor, f"{where}.params.{key}")
            if key.endswith("Path"):
                resolve_path(value, root, descriptor, f"{where}.params.{key}")
        missing = FORMAT_REQUIRES.get(fmt, set()) - params.keys()
        if missing:
            refuse(where, f"{fmt} needs {sorted(missing)}")
        if fmt == "tokenAmount" and {"token", "tokenPath"} <= params.keys():
            refuse(where, "a tokenAmount names its token by token or by tokenPath, not both")
    elif "params" in field:
        refuse(where, "params without a format")
    for i, sub in enumerate(field.get("fields", [])):
        check_field(sub, descriptor, f"{where}.fields[{i}]", root)


def check_descriptor(path: str, descriptor: dict) -> str:
    """Refuses what the runtime does not implement; returns the descriptor's kind, calldata or eip712."""
    only(descriptor, TOP_KEYS - {"includes"}, path)
    schema = descriptor.get("$schema", "")
    match = re.search(r"erc7730-v(\d+)(?:\.\d+\.\d+)?\.schema\.json$", schema)
    if not match or int(match.group(1)) != SCHEMA_MAJOR:
        refuse(path, f"$schema {schema!r} is not of major version {SCHEMA_MAJOR}")
    context = descriptor.get("context", {})
    only(context, CONTEXT_KEYS, path + ".context")
    kinds = [k for k in ("contract", "eip712") if k in context]
    if len(kinds) != 1:
        refuse(path, "the context is a contract or an EIP-712 domain")
    kind = "calldata" if kinds[0] == "contract" else "eip712"
    only(context[kinds[0]], CONTRACT_KEYS if kind == "calldata" else EIP712_KEYS, f"{path}.context.{kinds[0]}")
    if kind == "eip712":
        only(context["eip712"].get("domain", {}), DOMAIN_KEYS, path + ".context.eip712.domain")
    for i, deployment in enumerate(context[kinds[0]].get("deployments", [])):
        only(deployment, {"chainId", "address"}, f"{path}.deployments[{i}]")
    metadata = descriptor.get("metadata", {})
    only(metadata, METADATA_KEYS, path + ".metadata")
    if not isinstance(metadata.get("owner"), str) or not metadata["owner"].strip():
        refuse(path, "metadata.owner, the name a review shows after \"Described by\", is a non-empty string")
    only(metadata.get("info", {}), INFO_KEYS, path + ".metadata.info")
    for name, values in metadata.get("enums", {}).items():
        if not isinstance(values, dict) or not all(isinstance(v, str) for v in values.values()):
            refuse(f"{path}.metadata.enums.{name}", "an enum maps values to names")
    display = descriptor.get("display", {})
    only(display, {"formats"}, path + ".display")
    if not display.get("formats"):
        refuse(path, "no formats")
    for key, fmt in display["formats"].items():
        where = f"{path} format {key[:60]!r}"
        only(fmt, FORMAT_KEYS, where)
        if kind == "calldata":
            canonical_signature(key, where)
            root = calldata_root(key, where)
        else:
            check_encode_type(key, where)
            root = message_root(key)
        if not isinstance(fmt.get("intent"), str):
            refuse(where, "the intent is a string (an object intent is not implemented)")
        if "interpolatedIntent" in fmt:
            text = fmt["interpolatedIntent"]
            if not isinstance(text, str) or re.sub(r"\{[^{}]*\}", "", text).count("{") \
                    or re.sub(r"\{[^{}]*\}", "", text).count("}"):
                refuse(where, "the interpolated intent is a string of {path} placeholders")
            for placeholder in re.findall(r"\{([^{}]*)\}", text):
                check_path(placeholder, where + ".interpolatedIntent")
                resolve_path(placeholder, root, descriptor, where + ".interpolatedIntent")
        for i, field in enumerate(fmt.get("fields", [])):
            check_field(field, descriptor, f"{where}.fields[{i}]", root)
    return kind


def compile_formats(path: str, kind: str, descriptor: dict) -> list:
    formats, seen = [], {}
    for key, fmt in sorted(descriptor["display"]["formats"].items()):
        where = f"{path} format {key[:60]!r}"
        entry = {"key": key, "intent": fmt["intent"], "fields": fmt.get("fields", [])}
        if "interpolatedIntent" in fmt:
            entry["interpolatedIntent"] = fmt["interpolatedIntent"]
        if kind == "calldata":
            signature = canonical_signature(key, where)
            entry["signature"] = signature
            entry["selector"] = "0x" + keccak256(signature.encode("ascii"))[:4].hex()
            ident = entry["selector"]
        else:
            entry["primaryType"] = check_encode_type(key, where)
            entry["typeHash"] = "0x" + keccak256(key.encode("ascii")).hex()
            ident = entry["typeHash"]
        if ident in seen:
            refuse(where, f"shares {ident} with format {seen[ident][:60]!r}")
        seen[ident] = key
        formats.append(entry)
    return formats


# ---------------------------------------------------------------------------------------------------------
# The list

UPGRADE_KINDS = {"none", "slot", "call"}


def check_declaration(declared, code_hash, where: str) -> None:
    """A by-hand declaration of what some code delegates to, as pin_code_hashes.py checked and wrote it."""
    only(declared, {"why", "forCodeHash", "self", "libraries", "libraryCodeHashes", "links"}, where)
    if not isinstance(declared.get("why"), str) or not HASH.match(declared.get("forCodeHash", "")):
        refuse(where, "a declaration has its why and the code hash it was read for (pin_code_hashes.py)")
    if code_hash is not None and declared["forCodeHash"] != code_hash:
        refuse(where, "declared for other code than the code pinned")
    libraries = declared.get("libraries", [])
    if set(libraries) != set(declared.get("libraryCodeHashes", {})) or not all(
            HASH.match(h) for h in declared.get("libraryCodeHashes", {}).values()):
        refuse(where, "every library has its code hash pinned")
    if not libraries and declared.get("self") is not True:
        refuse(where, "a declaration names libraries, or self, or both")


def check_upgrade(upgrade: dict, where: str) -> None:
    kind = upgrade.get("kind")
    if kind not in UPGRADE_KINDS:
        refuse(where, f"upgrade kind {kind!r}: one of {sorted(UPGRADE_KINDS)}")
    keys = {"none": {"kind", "why", "delegatecall"},
            "slot": {"kind", "slot", "implementation", "implementationCodeHash", "implementationDelegatecall", "why"},
            "call": {"kind", "call", "selector", "returns", "implementationCodeHash", "implementationDelegatecall",
                     "why"}}[kind]
    only(upgrade, keys, where)
    for key, code_key in (("delegatecall", "codeHash"), ("implementationDelegatecall", "implementationCodeHash")):
        if key in upgrade:
            check_declaration(upgrade[key], upgrade.get(code_key) if code_key != "codeHash" else None,
                              f"{where}.{key}")
    if kind == "slot":
        if not HASH.match(upgrade.get("slot", "")):
            refuse(where, "a slot is 32 bytes of lowercase hex")
    if kind == "call":
        signature = canonical_signature(upgrade.get("call", ""), where)
        if upgrade.get("selector") != "0x" + keccak256(signature.encode("ascii"))[:4].hex():
            refuse(where, f"selector is not that of {signature}")
        if not re.match(r"^0x[0-9a-f]{64}$", upgrade.get("returns", "")):
            refuse(where, "returns is the 32-byte word the call must answer")
    if kind in ("slot", "call") and not HASH.match(upgrade.get("implementationCodeHash", "")):
        refuse(where, "the implementation's code hash is not pinned (pin_code_hashes.py)")
    if kind == "slot" and not ADDRESS.match(upgrade.get("implementation", "")):
        refuse(where, "the implementation is an address")


def build() -> dict:
    check_pins()
    contracts = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    only(contracts, {"registry", "deployments"}, "contracts.json")
    if contracts["registry"] != {"repository": REGISTRY_REPOSITORY, "commit": REGISTRY_COMMIT}:
        refuse("contracts.json", "its registry is not REGISTRY_REPOSITORY at REGISTRY_COMMIT")
    descriptors, deployments, seen = {}, [], set()
    for i, deployment in enumerate(contracts["deployments"]):
        where = f"contracts.json deployment {i} ({deployment.get('name')})"
        only(deployment, {"network", "name", "address", "codeHash", "upgrade", "descriptors"}, where)
        network = deployment.get("network")
        if network not in NETWORKS:
            refuse(where, f"network {network!r} is not served")
        if not ADDRESS.match(deployment.get("address", "")):
            refuse(where, "not an address")
        address = deployment["address"].lower()
        if (network, address) in seen:
            refuse(where, "listed twice")
        seen.add((network, address))
        if not HASH.match(deployment.get("codeHash", "")):
            refuse(where, "no code hash pinned (pin_code_hashes.py)")
        check_upgrade(deployment.get("upgrade", {}), where + ".upgrade")
        if deployment["upgrade"].get("delegatecall", {}).get("forCodeHash", deployment["codeHash"]) \
                != deployment["codeHash"]:
            refuse(where, "its delegatecall was declared for other code than the code pinned")
        if not deployment.get("descriptors"):
            refuse(where, "names no descriptor")
        for path in deployment["descriptors"]:
            if path not in descriptors:
                descriptor = resolve(path)
                kind = check_descriptor(path, descriptor)
                descriptors[path] = {"descriptor": descriptor, "kind": kind}
            context = descriptors[path]["descriptor"]["context"]
            listed = (context.get("contract") or context.get("eip712")).get("deployments", [])
            if not any(d["chainId"] == NETWORKS[network] and d["address"].lower() == address for d in listed):
                refuse(where, f"not among the deployments of {path}")
        deployments.append({
            "network": network, "chainId": NETWORKS[network], "name": deployment["name"], "address": address,
            "codeHash": deployment["codeHash"], "upgrade": deployment["upgrade"],
            "descriptors": list(deployment["descriptors"]),
        })
    compiled = {}
    for path, entry in sorted(descriptors.items()):
        descriptor, kind = entry["descriptor"], entry["kind"]
        metadata = descriptor.get("metadata", {})
        out = {
            "kind": kind,
            "sha256": PINNED[path],
            "owner": metadata.get("owner"),
            "constants": metadata.get("constants", {}),
            "enums": metadata.get("enums", {}),
            "formats": compile_formats(path, kind, descriptor),
        }
        if kind == "eip712":
            out["domain"] = descriptor["context"]["eip712"].get("domain", {})
        stem = pathlib.PurePosixPath(path)
        attestations = sorted(p for p in PINNED if p.startswith(f"{stem.parent}/sigs/{stem.stem}.eip155-"))
        if attestations:
            out["attestations"] = attestations
        compiled[path] = out
    seen_formats = {}
    for deployment in deployments:
        for path in deployment["descriptors"]:
            for fmt in compiled[path]["formats"]:
                ident = (deployment["network"], deployment["address"], fmt.get("selector") or fmt["typeHash"])
                if ident in seen_formats:
                    refuse(f"{deployment['name']} on {deployment['network']}",
                           f"{ident[2]} in both {seen_formats[ident]} and {path}")
                seen_formats[ident] = path
    return {
        "registry": {"repository": REGISTRY_REPOSITORY, "commit": REGISTRY_COMMIT},
        "deployments": deployments,
        "descriptors": compiled,
    }


def kotlin(catalog: dict) -> str:
    text = json.dumps(catalog, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    digest = hashlib.sha256(text.encode("ascii")).hexdigest()
    chunks, start = [], 0
    while start < len(text):
        chunks.append(text[start:start + CHUNK_BYTES])
        start += CHUNK_BYTES
    out = [
        "// Generated by scripts/clear-signing/generate.py. Do not edit by hand.",
        f"// The ERC-7730 descriptors of {REGISTRY_REPOSITORY} at {REGISTRY_COMMIT},",
        f"// {len(catalog['descriptors'])} descriptors on {len(catalog['deployments'])} deployments, each pinned by its",
        "// runtime code's hash (docs/clear-signing.md, A1).",
        "package eth.serotine.data.clearsigning",
        "",
        "/** The sha256 of [CLEAR_SIGNING_CATALOG_JSON] joined, which the catalog's reader checks before it parses. */",
        f'internal const val CLEAR_SIGNING_CATALOG_SHA256 = "{digest}"',
        "",
        "/**",
        " * The built-in clear-signing catalog: compact JSON, in chunks a JVM string constant holds. Joined, it is",
        " * the curated deployments with their code pins and the descriptors they use, `includes` resolved and every",
        " * selector and EIP-712 type hash computed at build time.",
        " */",
        "internal val CLEAR_SIGNING_CATALOG_JSON: List<String> = listOf(",
    ]
    for chunk in chunks:
        assert '"""' not in chunk
        out.append('    """' + chunk.replace("$", "${'$'}") + '""",')
    out.append(")")
    return "\n".join(out) + "\n"


def main(argv: list) -> int:
    if argv == ["--print-pins"]:
        for path in vendored_files():
            print(f'    "{path}": "{hashlib.sha256((VENDORED / path).read_bytes()).hexdigest()}",')
        return 0
    if argv:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        catalog = build()
    except Refused as refused:
        print(f"refused: {refused}", file=sys.stderr)
        return 1
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(kotlin(catalog), encoding="utf-8")
    print(f"wrote {TARGET.relative_to(ROOT)}: {len(catalog['descriptors'])} descriptors, "
          f"{len(catalog['deployments'])} deployments")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
