"""AKTIV desktop login — validates against SYS_MAST_USERS."""
from __future__ import annotations

import hmac
from dataclasses import dataclass, field

from db import mssql_conn
from roles import load_user_permissions


@dataclass
class AuthUser:
    user_key: int
    userid: str
    username: str | None
    role: str = "staff"                 # primary role label for display
    collector_key: int | None = None
    permissions: dict = field(default_factory=dict)


def _primary_role(perms: dict, userid: str = "", username: str | None = None) -> str:
    if perms.get("is_admin"):
        return "admin"
    roles = perms.get("roles") or []
    # Collectors are recognised by AKTIV role, or - where the clinic never gave them
    # one - by the login name the older builds keyed on.
    name = f"{userid} {username or ''}".lower()
    if any("COLL" in r or "SAMPLE" in r for r in roles) or any(
        t in name for t in ("collector", "collection", "agent")
    ):
        return "collector"
    if perms.get("can_view_sales"):
        return "account"
    if perms.get("can_book"):
        return "reception"
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

    perms = load_user_permissions(int(user_key))
    role = _primary_role(perms, str(db_userid or login_id), str(username) if username else None)
    return AuthUser(
        user_key=int(user_key),
        userid=str(db_userid or login_id),
        username=str(username) if username else str(db_userid or login_id),
        role=role,
        collector_key=int(user_key) if role in ("collector", "admin") else None,
        permissions=perms,
    )
