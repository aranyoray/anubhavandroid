"""Decode the QR token printed on AKTIV bills.

The bill QR encodes a URL like::

    http://anubhav.patientreport.net/report_view.php?f=<token>

The vendor (SALVUS) mints `<token>` with `AKTIV.Classes.ClsLibrary::fnEncryptStringApi`:

    cipher    = AES-256-CBC, PKCS7 padding
    key       = SHA256(b"salvustech")          # 32 bytes
    iv        = b"\\x00" * 16                   # all-zero IV
    body      = base64(cipher(plaintext_ascii))
    token     = body.replace("+","_Plus_").replace("/","_Slash_").replace("=","_Equal_")

The decrypted plaintext is ``"<report_pdf_filename>|<BILL_KEY>"`` — e.g.
``ALC1638_10092026_IQRA_KHAN_166564_2_8943H|166564``. We only need the trailing
BILL_KEY: from it we look up the bill's phone and reuse the normal by-phone history.

AES is implemented here in pure Python on purpose: the FastAPI service runs as SYSTEM
on the machine-wide interpreter, where a pip-installed `cryptography` is not visible, so
a hard dependency takes the whole API down at import. Decoding happens entirely locally;
the SALVUS host is never contacted.
"""
from __future__ import annotations

import base64
import hashlib
import re
import urllib.parse
from dataclasses import dataclass

_ESCAPES = (("_Plus_", "+"), ("_Slash_", "/"), ("_Equal_", "="))


@dataclass
class QrBill:
    bill_key: int
    filename: str  # the report PDF stem the token pointed at (informational)


# ----------------------------------------------------------------- pure-Python AES
_SBOX = bytes.fromhex(
    "637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0"
    "b7fd9326363ff7cc34a5e5f171d8311504c723c31896059a071280e2eb27b275"
    "09832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cf"
    "d0efaafb434d338545f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2"
    "cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814de5e0bdb"
    "e0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08"
    "ba78252e1ca6b4c6e8dd741f4bbd8b8a703eb5664803f60e613557b986c11d9e"
    "e1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16"
)
_INV_SBOX = bytes(256)
_inv = bytearray(256)
for _i, _v in enumerate(_SBOX):
    _inv[_v] = _i
_INV_SBOX = bytes(_inv)

_RCON = (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36,
         0x6C, 0xD8, 0xAB, 0x4D)


def _xtime(a: int) -> int:
    a <<= 1
    if a & 0x100:
        a ^= 0x11B
    return a & 0xFF


def _mul(a: int, b: int) -> int:
    res = 0
    for _ in range(8):
        if b & 1:
            res ^= a
        b >>= 1
        a = _xtime(a)
    return res


def _key_expansion(key: bytes) -> list[list[int]]:
    nk = len(key) // 4          # 8 for AES-256
    nr = nk + 6                 # 14 rounds
    words = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    for i in range(nk, 4 * (nr + 1)):
        temp = list(words[i - 1])
        if i % nk == 0:
            temp = temp[1:] + temp[:1]                       # RotWord
            temp = [_SBOX[b] for b in temp]                  # SubWord
            temp[0] ^= _RCON[i // nk - 1]
        elif nk > 6 and i % nk == 4:
            temp = [_SBOX[b] for b in temp]
        words.append([words[i - nk][j] ^ temp[j] for j in range(4)])
    # group words into round-key matrices (column-major 4x4)
    round_keys = []
    for r in range(nr + 1):
        round_keys.append([words[4 * r + c] for c in range(4)])
    return round_keys


def _add_round_key(state: list[list[int]], rk: list[list[int]]) -> None:
    for c in range(4):
        for r in range(4):
            state[r][c] ^= rk[c][r]


def _inv_sub_bytes(state: list[list[int]]) -> None:
    for r in range(4):
        for c in range(4):
            state[r][c] = _INV_SBOX[state[r][c]]


def _inv_shift_rows(state: list[list[int]]) -> None:
    for r in range(1, 4):
        state[r] = state[r][-r:] + state[r][:-r]


def _inv_mix_columns(state: list[list[int]]) -> None:
    for c in range(4):
        a0, a1, a2, a3 = (state[r][c] for r in range(4))
        state[0][c] = _mul(a0, 14) ^ _mul(a1, 11) ^ _mul(a2, 13) ^ _mul(a3, 9)
        state[1][c] = _mul(a0, 9) ^ _mul(a1, 14) ^ _mul(a2, 11) ^ _mul(a3, 13)
        state[2][c] = _mul(a0, 13) ^ _mul(a1, 9) ^ _mul(a2, 14) ^ _mul(a3, 11)
        state[3][c] = _mul(a0, 11) ^ _mul(a1, 13) ^ _mul(a2, 9) ^ _mul(a3, 14)


def _decrypt_block(block: bytes, round_keys: list[list[list[int]]]) -> bytes:
    nr = len(round_keys) - 1
    state = [[block[r + 4 * c] for c in range(4)] for r in range(4)]
    _add_round_key(state, round_keys[nr])
    for rnd in range(nr - 1, 0, -1):
        _inv_shift_rows(state)
        _inv_sub_bytes(state)
        _add_round_key(state, round_keys[rnd])
        _inv_mix_columns(state)
    _inv_shift_rows(state)
    _inv_sub_bytes(state)
    _add_round_key(state, round_keys[0])
    return bytes(state[r][c] for c in range(4) for r in range(4))


def _aes_cbc_decrypt(cipher: bytes, key: bytes, iv: bytes) -> bytes:
    round_keys = _key_expansion(key)
    out = bytearray()
    prev = iv
    for off in range(0, len(cipher), 16):
        block = cipher[off:off + 16]
        dec = _decrypt_block(block, round_keys)
        out.extend(b ^ p for b, p in zip(dec, prev))
        prev = block
    return bytes(out)


_KEY = hashlib.sha256(b"salvustech").digest()
_IV = b"\x00" * 16


def _unpad_pkcs7(data: bytes) -> bytes:
    if not data:
        return data
    pad = data[-1]
    if 1 <= pad <= 16 and data[-pad:] == bytes([pad]) * pad:
        return data[:-pad]
    return data


def _extract_token(scanned: str) -> str:
    """Accept the whole scanned QR URL, a bare `f=...` query, or just the token."""
    s = (scanned or "").strip()
    if not s:
        raise ValueError("empty QR data")
    if "f=" in s:
        query = urllib.parse.urlparse(s).query or s
        params = urllib.parse.parse_qs(query, keep_blank_values=True)
        if params.get("f"):
            return params["f"][0]
        m = re.search(r"f=([^&\s]+)", s)
        if m:
            return m.group(1)
    return s


def decrypt_token(scanned: str) -> QrBill:
    """Decode a scanned bill QR (URL or token) to its BILL_KEY and report filename."""
    token = _extract_token(scanned)
    for esc, raw in _ESCAPES:
        token = token.replace(esc, raw)
    if "%" in token and "_Plus_" not in scanned:
        token = urllib.parse.unquote(token)
    token = token.replace(" ", "+")

    try:
        cipher_bytes = base64.b64decode(token)
    except Exception as exc:  # noqa: BLE001
        raise ValueError("QR token is not valid base64") from exc
    if not cipher_bytes or len(cipher_bytes) % 16 != 0:
        raise ValueError("QR token has an unexpected length")

    plain = _unpad_pkcs7(_aes_cbc_decrypt(cipher_bytes, _KEY, _IV))
    try:
        text = plain.decode("ascii")
    except UnicodeDecodeError as exc:
        raise ValueError("QR token did not decrypt to text (wrong QR?)") from exc

    filename, sep, tail = text.partition("|")
    key_str = (tail if sep else filename).strip()
    m = re.search(r"(\d+)\s*$", key_str)
    if not m:
        raise ValueError(f"no BILL_KEY in decoded token: {text!r}")
    return QrBill(bill_key=int(m.group(1)), filename=filename.strip())
