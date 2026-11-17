"""Decode the QR token printed on AKTIV bills.

The bill QR encodes a URL like::

    http://anubhav.patientreport.net/report_view.php?f=<token>

The vendor (SALVUS) desktop/web code mints `<token>` with a routine recovered from
`AKTIV.Classes.ClsLibrary::fnEncryptStringApi`:

    cipher    = AES-256-CBC, PKCS7 padding
    key       = SHA256(b"salvustech")          # 32 bytes
    iv        = b"\\x00" * 16                   # all-zero IV
    body      = base64(cipher(plaintext_ascii))
    token     = body.replace("+","_Plus_").replace("/","_Slash_").replace("=","_Equal_")

The decrypted plaintext is ``"<report_pdf_filename>|<BILL_KEY>"`` — e.g.
``ALC1638_10092026_IQRA_KHAN_166564_2_8943H|166564``. We only need the trailing
BILL_KEY: from it we look up the bill's phone and reuse the normal by-phone history,
so scanning your own bill behaves exactly like signing in with that phone.

Decoding happens entirely on our side; the SALVUS host is not contacted.
"""
from __future__ import annotations

import base64
import hashlib
import re
import urllib.parse
from dataclasses import dataclass

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

_KEY = hashlib.sha256(b"salvustech").digest()
_IV = b"\x00" * 16
_ESCAPES = (("_Plus_", "+"), ("_Slash_", "/"), ("_Equal_", "="))


@dataclass
class QrBill:
    bill_key: int
    filename: str  # the report PDF stem the token pointed at (informational)


def _unpad_pkcs7(data: bytes) -> bytes:
    if not data:
        return data
    pad = data[-1]
    if 1 <= pad <= 16 and data[-pad:] == bytes([pad]) * pad:
        return data[:-pad]
    return data  # tolerate a token that wasn't padded as expected


def _extract_token(scanned: str) -> str:
    """Accept the whole scanned QR URL, a bare `f=...` query, or just the token."""
    s = (scanned or "").strip()
    if not s:
        raise ValueError("empty QR data")
    # URL or query string -> pull the f parameter
    if "f=" in s:
        query = urllib.parse.urlparse(s).query or s
        params = urllib.parse.parse_qs(query, keep_blank_values=True)
        if "f" in params and params["f"]:
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
    # the token may also arrive percent-encoded if it came through a URL
    if "%" in token and "_Plus_" not in scanned:
        token = urllib.parse.unquote(token)
    token = token.replace(" ", "+")  # a space here is a '+' mangled by a URL decode

    try:
        cipher_bytes = base64.b64decode(token)
    except Exception as exc:  # noqa: BLE001
        raise ValueError("QR token is not valid base64") from exc
    if not cipher_bytes or len(cipher_bytes) % 16 != 0:
        raise ValueError("QR token has an unexpected length")

    decryptor = Cipher(algorithms.AES(_KEY), modes.CBC(_IV)).decryptor()
    plain = _unpad_pkcs7(decryptor.update(cipher_bytes) + decryptor.finalize())
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
