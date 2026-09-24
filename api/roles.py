"""AKTIV role-based permissions, mirrored from SYS_MAST_ROLE / SYS_MAST_USERS_ROLE.

A user has one or more roles (SYS_MAST_USERS_ROLE -> SYS_MAST_ROLE.ROLEID) plus a few
per-user flags on SYS_MAST_USERS. We translate those into a flat capability set the app
uses to gate its UI and the server enforces on write/sales endpoints.
"""
from __future__ import annotations

from typing import Any

from db import fetch_all, mssql_conn


def _to_int(v: Any) -> int:
    try:
        return int(str(v).strip())
    except (ValueError, TypeError):
        return 0


def permissions_for(roleids: list[str], flags: dict) -> dict:
    """Map AKTIV role names + per-user flags to app capabilities."""
    R = {(r or "").strip().upper() for r in roleids}
    is_admin = "ADMIN" in R
    account_day = _to_int(flags.get("accountview_day"))
    mod_day = _to_int(flags.get("modification_day"))

    return {
        "roles": sorted(r for r in R if r),
        "is_admin": is_admin,
        # sales / account figures — ADMIN, ACCOUNT role, or a positive account-view window
        "can_view_sales": is_admin or "ACCOUNT" in R or account_day > 0,
        # who may create bookings/bills
        "can_book": is_admin or bool(R & {"RECEPTION", "MASTER(RECP)", "OPD"}),
        # who may edit a bill after it's made
        "can_edit_booking": is_admin or bool(R & {"BILLCHANGE", "MASTER(RECP)"}),
        # who may cancel/void
        "can_cancel_booking": is_admin or bool(R & {"OPD CANCEL", "BILLCHANGE"}),
        # who may authorise/confirm reports
        "can_confirm_report": is_admin or bool(R & {"REPORT CONFIRM", "REPORT VERIFY", "CHECKEDBY"}),
        "can_export_reports": is_admin or _to_int(flags.get("report_export")) > 0,
        # 0 = no limit / not set; else edits allowed only within this many days of the bill
        "modification_days": mod_day,
        "accountview_days": account_day,
    }


def load_user_permissions(user_key: int) -> dict:
    with mssql_conn() as conn, conn.cursor() as cur:
        roles = fetch_all(
            cur,
            """
            SELECT RTRIM(r.ROLEID) AS roleid
            FROM SYS_MAST_USERS_ROLE ur
            JOIN SYS_MAST_ROLE r ON r.ROLE_KEY = ur.ROLE_KEY
            WHERE ur.USER_KEY = %s
            """,
            (user_key,),
        )
        flags = fetch_all(
            cur,
            """
            SELECT MODIFICATION_DAY, ACCOUNTVIEW_DAY, REPORT_EXPORT, USERWISE_REPORT
            FROM SYS_MAST_USERS WHERE USER_KEY = %s
            """,
            (user_key,),
        )
    roleids = [r["roleid"] for r in roles if r.get("roleid")]
    f = flags[0] if flags else {}
    flagd = {
        "modification_day": f.get("MODIFICATION_DAY"),
        "accountview_day": f.get("ACCOUNTVIEW_DAY"),
        "report_export": f.get("REPORT_EXPORT"),
        "userwise_report": f.get("USERWISE_REPORT"),
    }
    return permissions_for(roleids, flagd)


def require(user_key: int | None, capability: str) -> dict:
    """Return the user's permissions, raising PermissionError if they lack `capability`."""
    if not user_key:
        raise PermissionError("login required")
    perms = load_user_permissions(int(user_key))
    if not perms.get(capability):
        raise PermissionError(f"Your role does not allow this ({capability}).")
    return perms
