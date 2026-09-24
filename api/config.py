"""Load shared env from repo root (.env or env)."""
from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def load_env() -> None:
    for name in (".env", "env"):
        path = ROOT / name
        if not path.exists():
            continue
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"'))


def mssql_config() -> dict:
    load_env()
    return {
        "server": os.environ["MSSQL_HOST"],
        "port": int(os.environ.get("MSSQL_PORT", "1433")),
        "user": os.environ["MSSQL_USER"],
        "password": os.environ["MSSQL_PASSWORD"],
        "database": os.environ["MSSQL_DB"],
        "tds_version": os.environ.get("MSSQL_TDS_VERSION", "7.0"),
    }


def neon_url() -> str:
    load_env()
    return os.environ["NEON_DATABASE_URL"]


def admin_password() -> str:
    """Password gating the in-app Admin booking-details reports.

    Defaults to the value the clinic was given so the feature works out of the box;
    set AKTIV_ADMIN_PASSWORD in the server env to override it in production. The app
    never ships this — the admin types it at login and the server is the authority.
    """
    load_env()
    return os.environ.get("AKTIV_ADMIN_PASSWORD", "nabllab")


def aktiv_settings() -> dict:
    """AKTIV write behaviour — receptionist user, live bookings by default."""
    load_env()
    from datetime import date

    allow_live = os.environ.get("AKTIV_ALLOW_LIVE_BOOKINGS", "true").lower() in (
        "1",
        "true",
        "yes",
    )
    test_date_raw = os.environ.get("AKTIV_TEST_BILL_DATE", "2025-07-02")
    try:
        test_bill_date = date.fromisoformat(test_date_raw)
    except ValueError:
        test_bill_date = date(2025, 7, 2)

    return {
        # 103 = the ONLINE AKTIV account — app/online bills must be stamped to it,
        # not a real staff login (10 was SUBHASHIS).
        "sys_user_key": int(os.environ.get("AKTIV_SYS_USER_KEY", "103")),
        "sys_machine_key": int(os.environ.get("AKTIV_SYS_MACHINE_KEY", "27")),
        "allow_live_bookings": allow_live,
        "test_bill_date": test_bill_date,
    }
