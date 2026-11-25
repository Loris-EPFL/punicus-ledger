"""Keccak-256 as Ethereum uses it (the original padding, not FIPS 202's SHA3-256), in plain Python.

The standard library's hashlib.sha3_256 pads differently and gives other digests, so the clear-signing
generator carries its own: a function selector, an EIP-712 type hash and a contract's code hash are all
Keccak-256. Slow, and only ever run at build time over a few kilobytes.
"""

_ROUND_CONSTANTS = [
    0x0000000000000001, 0x0000000000008082, 0x800000000000808A, 0x8000000080008000,
    0x000000000000808B, 0x0000000080000001, 0x8000000080008081, 0x8000000000008009,
    0x000000000000008A, 0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
    0x000000008000808B, 0x800000000000008B, 0x8000000000008089, 0x8000000000008003,
    0x8000000000008002, 0x8000000000000080, 0x000000000000800A, 0x800000008000000A,
    0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
]
_ROTATIONS = [
    [0, 36, 3, 41, 18],
    [1, 44, 10, 45, 2],
    [62, 6, 43, 15, 61],
    [28, 55, 25, 21, 56],
    [27, 20, 39, 8, 14],
]
_MASK = (1 << 64) - 1
_RATE = 136  # bytes: 1600 - 2 * 256 bits


def _rotl(value: int, shift: int) -> int:
    return ((value << shift) | (value >> (64 - shift))) & _MASK if shift else value


def _permute(lanes: list) -> None:
    """Keccak-f[1600] on 25 lanes, indexed lanes[x + 5 * y]."""
    for rc in _ROUND_CONSTANTS:
        c = [lanes[x] ^ lanes[x + 5] ^ lanes[x + 10] ^ lanes[x + 15] ^ lanes[x + 20] for x in range(5)]
        d = [c[(x - 1) % 5] ^ _rotl(c[(x + 1) % 5], 1) for x in range(5)]
        for i in range(25):
            lanes[i] ^= d[i % 5]
        b = [0] * 25
        for x in range(5):
            for y in range(5):
                b[y + 5 * ((2 * x + 3 * y) % 5)] = _rotl(lanes[x + 5 * y], _ROTATIONS[x][y])
        for x in range(5):
            for y in range(5):
                lanes[x + 5 * y] = b[x + 5 * y] ^ ((~b[(x + 1) % 5 + 5 * y]) & b[(x + 2) % 5 + 5 * y])
        lanes[0] ^= rc


def keccak256(data: bytes) -> bytes:
    """The 32-byte Keccak-256 digest of [data]."""
    padded = bytearray(data)
    padded.append(0x01)
    while len(padded) % _RATE:
        padded.append(0)
    padded[-1] |= 0x80
    lanes = [0] * 25
    for offset in range(0, len(padded), _RATE):
        block = padded[offset:offset + _RATE]
        for i in range(_RATE // 8):
            lanes[i] ^= int.from_bytes(block[8 * i:8 * i + 8], "little")
        _permute(lanes)
    return b"".join(lane.to_bytes(8, "little") for lane in lanes[:4])
