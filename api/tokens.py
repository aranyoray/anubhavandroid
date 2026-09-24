"""Signed bearer tokens for patients and staff.

The API key ships inside the APK, so on its own it proves nothing about who is
calling. Two token kinds close that gap:

* patient token - issued by /api/customer/verify once the 2-of-3 check passes and
  scoped to one phone number. Report/bill endpoints only answer for that phone.
  Long-lived, because the app keeps patients signed in and caches their reports.
* staff token - issued by /api/auth/login against the AKTIV user id + password.
  Short-lived; the server reloads the user's AKTIV roles on every call, so a role
  removed in AKTIV takes effect immediately.

Format: base64url(json payload) + "." + base64url(HMAC-SHA256(payload)). Stateless,
so the cloud fallback can verify the same tokens with the same secret.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any

from config import token_secret

PATIENT_TTL = 180 * 24 * 3600
STAFF_TTL = 12 * 3600


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _sign(body: str, secret: str) -> str:
    return _b64(hmac.new(secret.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest())


def issue(kind: str, ttl: int, now: float | None = None, **claims: Any) -> str:
    secret = token_secret()
    if not secret:
        raise RuntimeError("AKTIV_TOKEN_SECRET / AKTIV_API_KEY not configured")
    payload = {"k": kind, "exp": int((now or time.time()) + ttl), **claims}
    body = _b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    return f"{body}.{_sign(body, secret)}"


def read(token: str | None, kind: str, now: float | None = None) -> dict[str, Any] | None:
    """The token's claims if it is authentic, unexpired and of `kind`; else None."""
    secret = token_secret()
    if not token or not secret or token.count(".") != 1:
        return None
    body, sig = token.split(".")
    try:
        if not hmac.compare_digest(sig.encode("ascii"), _sign(body, secret).encode("ascii")):
            return None
        payload = json.loads(_unb64(body))
    except (ValueError, UnicodeError):
        return None
    if not isinstance(payload, dict) or payload.get("k") != kind:
        return None
    if int(payload.get("exp", 0)) < (now or time.time()):
        return None
    return payload


def patient_token(phone10: str) -> str:
    return issue("p", PATIENT_TTL, ph=phone10)


def patient_phones(token: str | None) -> set[str]:
    claims = read(token, "p")
    return {claims["ph"]} if claims and claims.get("ph") else set()


def staff_token(user_key: int) -> str:
    return issue("s", STAFF_TTL, uk=int(user_key))


def staff_user_key(token: str | None) -> int | None:
    claims = read(token, "s")
    return int(claims["uk"]) if claims and claims.get("uk") else None
