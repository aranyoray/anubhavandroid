"""AKTIV desktop login — validates against SYS_MAST_USERS."""
from __future__ import annotations

import hmac
from dataclasses import dataclass

from db import mssql_conn


@dataclass
class AuthUser:
    user_key: int
    userid: str
    username: str | None
    role: str = "staff"
    collector_key: int | None = None


def _user_role(userid: str, username: str | None) -> str:
    source = f"{userid} {username or ''}".lower()
    if any(token in source for token in ("collector", "collection", "agent")):
        return "collector"
    if any(token in source for token in ("admin", "manager", "owner")):
        return "admin"
    return "staff"


def authenticate(userid: str, password: str) -> AuthUser:
    """Match AKTIV login: USERID + USERPASSWORD from SYS_MAST_USERS."""
    login_id = userid.strip()
    if not login_id or not password:
        raise ValueError("Username and password are required")

    with mssql_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT user_key, userid, username, userpassword
            FROM SYS_MAST_USERS
            WHERE UPPER(userid) = UPPER(%s)
            """,
            (login_id,),
        )
        row = cur.fetchone()

    if not row:
        raise ValueError("Invalid username or password")

    user_key, db_userid, username, db_password = row
    stored = "" if db_password is None else str(db_password)
    # compare_digest wants ASCII str or bytes; AKTIV passwords are free text, so
    # compare the UTF-8 bytes and never raise on a non-ASCII character.
    if not hmac.compare_digest(stored.encode("utf-8"), password.encode("utf-8")):
        raise ValueError("Invalid username or password")

    role = _user_role(str(db_userid or login_id), str(username) if username else None)
    return AuthUser(
        user_key=int(user_key),
        userid=str(db_userid or login_id),
        username=str(username) if username else str(db_userid or login_id),
        role=role,
        collector_key=int(user_key) if role in ("collector", "admin") else None,
    )
