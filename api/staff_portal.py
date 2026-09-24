"""Staff bill lookup and edits for the in-app Admin screen.

Who may do what mirrors AKTIV itself (roles.py): creating a bill needs can_book,
changing one needs can_edit_booking (within the user's MODIFICATION_DAY window when
AKTIV sets one), cancelling needs can_cancel_booking. Every write is stamped with the
signed-in user's user_key, exactly as the desktop does.

Edits deliberately stop at the patient particulars (name, phone, sex, age, referring
doctor, remarks). Tests and amounts drive receipts, ledger rows and report headers
across several tables; changing those stays on the AKTIV desktop.
"""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from typing import Any

from config import aktiv_settings
from db import fetch_all, mssql_conn

SEX = {"MALE": 1, "FEMALE": 2, "OTHER": -1}
SEX_LABEL = {1: "MALE", 2: "FEMALE", -1: "OTHER"}
SEARCH_DAYS = 120


def _digits(s: str | None) -> str:
    return re.sub(r"\D", "", s or "")


def search_bills(q: str, limit: int = 30) -> list[dict[str, Any]]:
    """Recent bills matching a bill-number tail, a phone number or a patient name."""
    q = (q or "").strip()
    if len(q) < 2:
        return []
    since = date.today() - timedelta(days=SEARCH_DAYS)
    d = _digits(q)
    tail = re.search(r"(\d+)\s*$", q)
    if ("/" in q or "ALC" in q.upper()) and tail:
        where, params = ("RTRIM(BILL_NO) LIKE %s", (f"%/{tail.group(1)}",))
    elif len(d) >= 10:
        where, params = (
            "RIGHT(REPLACE(REPLACE(REPLACE(ISNULL(PHONE,''),' ',''),'-',''),'+',''),10) = %s",
            (d[-10:],),
        )
    elif q.isdigit():
        where, params = ("RTRIM(BILL_NO) LIKE %s", (f"%{q}",))
    else:
        where, params = ("PATIENTNAME LIKE %s", (f"%{q.upper()}%",))
    with mssql_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            f"SELECT TOP {max(1, min(limit, 100))} BILL_KEY AS bill_key, RTRIM(BILL_NO) AS bill_no, "
            "CONVERT(varchar, BILLDATE, 103) AS bill_date, RTRIM(ISNULL(PATIENTNAME,'')) AS patient_name, "
            "RTRIM(ISNULL(PHONE,'')) AS phone, ISNULL(NETAMOUNT,0) AS net_amount, "
            "ISNULL(RECEIVEDAMOUNT,0) AS received_amount "
            f"FROM BILL_HEAD WHERE BILLDATE >= %s AND {where} ORDER BY BILL_KEY DESC",
            (since, *params),
        )


def bill_detail(bill_key: int) -> dict[str, Any]:
    with mssql_conn() as conn, conn.cursor() as cur:
        head = fetch_all(
            cur,
            "SELECT BILL_KEY AS bill_key, RTRIM(BILL_NO) AS bill_no, BILLDATE AS bill_date, "
            "RTRIM(ISNULL(PATIENTNAME,'')) AS patient_name, RTRIM(ISNULL(PHONE,'')) AS phone, "
            "SEX AS sex, AGEYEAR AS age_year, AGEMONTH AS age_month, AGEDAY AS age_day, "
            "REFRDOCTOR_KEY AS refrdoctor_key, RTRIM(ISNULL(REMARKS,'')) AS remarks, "
            "ISNULL(BILLAMOUNT,0) AS bill_amount, ISNULL(NETAMOUNT,0) AS net_amount, "
            "ISNULL(RECEIVEDAMOUNT,0) AS received_amount "
            "FROM BILL_HEAD WHERE BILL_KEY = %s",
            (bill_key,),
        )
        if not head:
            raise LookupError("bill not found")
        tests = fetch_all(
            cur,
            "SELECT RTRIM(ISNULL(t.TESTNAME,'')) AS test_name, "
            "CASE WHEN CONVERT(varchar(4), d.CONFIRM_REPORT) = '1' THEN 1 ELSE 0 END AS ready "
            "FROM BILL_TEST_DTLS d LEFT JOIN MAST_TEST t ON t.TEST_KEY = d.TEST_KEY "
            "WHERE d.BILL_KEY = %s",
            (bill_key,),
        )
    h = head[0]
    bd = h["bill_date"]
    return {
        **h,
        "bill_date": bd.strftime("%d/%m/%Y") if isinstance(bd, (date, datetime)) else str(bd or ""),
        "sex": SEX_LABEL.get(int(h["sex"] or 0), ""),
        "net_amount": float(h["net_amount"] or 0),
        "bill_amount": float(h["bill_amount"] or 0),
        "received_amount": float(h["received_amount"] or 0),
        "tests": [{"test_name": t["test_name"], "ready": bool(t["ready"])} for t in tests],
    }


def check_edit_window(bill_date: Any, perms: dict, today: date | None = None) -> None:
    """AKTIV's MODIFICATION_DAY: a non-admin may only change bills that recent (0 = no limit)."""
    days = int(perms.get("modification_days") or 0)
    if perms.get("is_admin") or days <= 0:
        return
    if isinstance(bill_date, datetime):
        bill_date = bill_date.date()
    if not isinstance(bill_date, date):
        return
    if ((today or date.today()) - bill_date).days > days:
        raise PermissionError(f"Your AKTIV login may only change bills from the last {days} day(s).")


EDITABLE = ("patient_name", "phone", "sex", "age_year", "age_month", "age_day", "refrdoctor_key", "remarks")


def edit_bill(bill_key: int, changes: dict[str, Any], *, user_key: int, perms: dict) -> dict[str, Any]:
    changes = {k: v for k, v in changes.items() if k in EDITABLE and v is not None}
    if not changes:
        raise ValueError("nothing to change")
    if "patient_name" in changes:
        name = str(changes["patient_name"]).strip().upper()
        if not name:
            raise ValueError("patient name cannot be blank")
        changes["patient_name"] = name
    if "phone" in changes:
        phone = _digits(str(changes["phone"]))
        if len(phone) < 10:
            raise ValueError("phone must have 10 digits")
        changes["phone"] = phone[-10:]
    if "sex" in changes:
        sex = str(changes["sex"]).upper()
        if sex not in SEX:
            raise ValueError("sex must be MALE, FEMALE or OTHER")
        changes["sex"] = SEX[sex]

    # BILL_HEAD column <- field; the appointment row mirrors the same particulars.
    columns = {
        "patient_name": ["PATIENTNAME", "FIRSTNAME"],
        "phone": ["PHONE"],
        "sex": ["SEX"],
        "age_year": ["AGEYEAR"],
        "age_month": ["AGEMONTH"],
        "age_day": ["AGEDAY"],
        "refrdoctor_key": ["REFRDOCTOR_KEY"],
        "remarks": ["REMARKS"],
    }
    sets, params = [], []
    for field, value in changes.items():
        for col in columns[field]:
            sets.append(f"{col} = %s")
            params.append(value)
    apnt_sets = [s for s in sets if not s.startswith("REMARKS")]
    apnt_params = [p for s, p in zip(sets, params) if not s.startswith("REMARKS")]
    machine_key = aktiv_settings()["sys_machine_key"]
    now = datetime.now()
    audit = ["SYS_LAST_MOD_DATE = %s", "SYS_MOD_USER_KEY = %s", "SYS_MOD_MACHINE_KEY = %s"]

    with mssql_conn() as conn:
        cur = conn.cursor()
        cur.execute("SELECT BILLDATE FROM BILL_HEAD WITH (UPDLOCK) WHERE BILL_KEY = %s", (bill_key,))
        row = cur.fetchone()
        if not row:
            raise LookupError("bill not found")
        check_edit_window(row[0], perms)
        cur.execute(
            f"UPDATE BILL_HEAD SET {', '.join(sets + audit)} WHERE BILL_KEY = %s",
            (*params, now, user_key, machine_key, bill_key),
        )
        if apnt_sets:
            cur.execute(
                f"UPDATE APNT_HEAD SET {', '.join(apnt_sets + audit)} WHERE BILL_KEY = %s",
                (*apnt_params, now, user_key, machine_key, bill_key),
            )
        conn.commit()
    return bill_detail(bill_key)
