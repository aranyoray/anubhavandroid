"""
Patient verification + report view links for the customer portal.

Login (no OTP): the patient reads three things off their receipt and ALL THREE must
match one bill:
  1. Patient name  (fuzzy: tolerates typos / a missing middle name)
  2. Bill date     (only the month is used - within a month a serial is already unique,
                    so misremembering the day still gets them in)
  3. Bill No       (the full ALC number, or just its last 3-4 digits: "4171" or "171"
                    for 2026/09/ALC/4171 - that is what patients read off the receipt)
The phone is never typed: it is read back off the matched bill, and the rest of the
portal keys off it. App builds predating the date field send a phone instead, which is
still accepted.

Reads LIVE MSSQL first (includes bills made minutes ago); if the clinic database is
unreachable it answers from the Neon mirror instead, so login and the report list do
not depend on which of the two stores happens to be up.
"""
from __future__ import annotations

import json
import os
import re
import unicodedata
import urllib.parse
from datetime import date, datetime
from typing import Any, Optional

import logging
from datetime import timedelta

from config import load_env
from db import fetch_all, mssql_conn, neon_conn

logger = logging.getLogger("anubhav-api")


def report_view_base() -> str:
    load_env()
    return os.environ.get(
        "REPORT_VIEW_BASE", "https://report.anubhavlifecare.in/AKTIV"
    ).rstrip("/")


# ---------------------------------------------------------------- fuzzy name match
def _lev(a: str, b: str) -> int:
    a, b = a.lower(), b.lower()
    n, m = len(a), len(b)
    if n == 0:
        return m
    if m == 0:
        return n
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        for j in range(1, m + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[m]


def _sim(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return 1.0 - _lev(a, b) / max(len(a), len(b))


def _tokens(s: str) -> list[str]:
    # Keep Unicode letters and combining marks (Bengali vowel signs included).
    text = unicodedata.normalize("NFC", s or "").casefold()
    return "".join(c if unicodedata.category(c)[0] in "LM" else " " for c in text).split()


def name_score(inp: str, db: str) -> float:
    """Fraction of input name tokens that fuzzily match a DB token.

    Tolerates typos (PRITI~PREETI) and a missing/extra middle name. Short tokens
    (initials like "P", "K") must match EXACTLY, else single letters match anything.
    """
    ti, td = _tokens(inp), _tokens(db)
    if not ti or not td:
        return 0.0
    hit = 0
    for x in ti:
        best = 0.0
        for y in td:
            s = 1.0 if x == y else (0.0 if (len(x) <= 2 or len(y) <= 2) else _sim(x, y))
            if s > best:
                best = s
        if best >= 0.6:
            hit += 1
    return round(hit / len(ti), 2)


# ---------------------------------------------------------------- helpers
def _norm_phone(p: Optional[str]) -> str:
    return "".join(str(unicodedata.decimal(c)) for c in (p or "") if c.isdecimal())[-10:]


def mssql_phone10(column: str) -> str:
    """Normalize legacy phone formatting consistently for all live reads.

    column is a fixed SQL identifier supplied by this module's callers, never input.
    """
    sql = f"ISNULL({column},'')"
    for separator in (" ", "-", "+", "(", ")", ".", "/"):
        sql = f"REPLACE({sql},'{separator}','')"
    return f"RIGHT({sql},10)"


_JUNK_PHONES = {
    "9999999999", "0000000000", "1111111111", "1234567890", "9876543210", "8888888888",
}


def _is_junk_phone(p: str) -> bool:
    d = _norm_phone(p)
    return len(d) < 10 or len(set(d)) == 1 or d in _JUNK_PHONES


def _bill_serial(bill_no: Optional[str]) -> str:
    # Keep leading zeroes: 003 may be the last three digits of a four-digit
    # serial such as 1003, while exact comparisons can still ignore padding.
    return (bill_no or "").split("/")[-1].strip()


def _bill_month(bill_no: Optional[str]) -> str:
    """The YYYY/MM the bill belongs to, from a 'YYYY/MM/ALC/NNN' bill number.

    ALC serials reset to 001 on the 1st of every month, so a serial only
    identifies a bill *within* its month — the month prefix must be kept.
    """
    parts = [p.strip() for p in (bill_no or "").split("/")]
    if len(parts) >= 3 and parts[0].isdigit() and parts[1].isdigit():
        return f"{parts[0]}/{parts[1]}"
    return ""


def _serial_matches(row_serial: str, serial: str) -> bool:
    """Exact serial (ignoring zero padding), or its last 3+ digits.

    Receipts print the full ALC number but patients type the tail of it. The phone
    has already matched exactly, so a tail only narrows down that phone's own bills.
    """
    if not serial or not row_serial:
        return False
    if row_serial == serial or row_serial.lstrip("0") == serial.lstrip("0"):
        return True
    return len(serial) >= 3 and serial.isdigit() and row_serial.endswith(serial)


def _row_matches(
    r: dict,
    name: str,
    serial: str,
    month_prefix: Optional[str],
    phone_n: Optional[str] = None,
) -> bool:
    """True when this bill matches the patient name, the bill number and the month.

    ALC numbering restarts every month, so serial 3 exists in Jan, Feb, ... — the
    month (taken from the bill date the patient picked, or from a full
    "YYYY/MM/ALC/NNNN" number) is what makes a serial identify one bill.

    [phone_n] is only passed by the legacy phone-based path; new lookups leave it None.
    """
    if phone_n is not None and _norm_phone(r["phone"]) != phone_n:
        return False
    if not _serial_matches(_bill_serial(r["bill_no"]), serial):
        return False
    if month_prefix and _bill_month(r["bill_no"]) != month_prefix:
        return False
    return name_score(name, r["patientname"]) >= NAME_MATCH_THRESHOLD


def _view_item(name, bill_key, category_key, report_key, bill_no) -> dict:
    return {
        "BILL_KEY": str(bill_key),
        "CATEGORY_KEY": str(category_key),
        "REPORT_KEY": str(report_key),
        "ATTACHMENT": "0",
        "BILL_NO": str(bill_no or ""),
        "PATIENTNAME": str(name or ""),
    }


def build_view_link(name: str, bill_key, category_key, report_key, bill_no, base: Optional[str] = None) -> str:
    return build_collated_view_link(
        name, bill_no, [_view_item(name, bill_key, category_key, report_key, bill_no)], base=base
    )


def build_collated_view_link(name: str, bill_no, items: list[dict], base: Optional[str] = None) -> str:
    """One LabReportPrint URL for a whole bill — the server renders ALL the given
    reports into a SINGLE collated PDF (this is how 'multiple PDFs' become one)."""
    q = "u=%s&d=0&p=0&pm=1&mp=1&json=%s" % (
        urllib.parse.quote(name or ""),
        urllib.parse.quote(json.dumps(items)),
    )
    return f"{base or report_view_base()}/LabReportPrint.aspx?{q}"


# ---------------------------------------------------------------- verification
# Share of the typed name's words that must fuzzily match the bill's name. Phone and bill
# number already have to match exactly, so the name is a sanity check: half the words is
# enough ("Priti Sharma" for PRITI SHARMA BANERJEE, "Reena Das" for RINA DAS).
NAME_MATCH_THRESHOLD = 0.5


def _candidates_mssql(month_prefix: str, serial: str) -> list[dict]:
    sql = (
        "SELECT BILL_KEY AS bill_key, RTRIM(BILL_NO) AS bill_no, RTRIM(ISNULL(PHONE,'')) AS phone, "
        "RTRIM(ISNULL(PATIENTNAME,'')) AS patientname, CONVERT(varchar, BILLDATE, 103) AS billdate "
        "FROM BILL_HEAD WHERE BILL_NO LIKE %s AND RIGHT(RTRIM(BILL_NO), %s) = %s "
        "ORDER BY BILL_KEY DESC"
    )
    with mssql_conn() as conn, conn.cursor() as cur:
        return fetch_all(cur, sql, (f"{month_prefix}/%", len(serial), serial))


def _candidates_neon(month_prefix: str, serial: str) -> list[dict]:
    sql = (
        "SELECT bill_key, rtrim(coalesce(bill_no,'')) AS bill_no, rtrim(coalesce(phone,'')) AS phone, "
        "rtrim(coalesce(patientname,'')) AS patientname, to_char(billdate,'DD/MM/YYYY') AS billdate "
        "FROM bill_head WHERE bill_no LIKE %s AND right(rtrim(bill_no), %s) = %s "
        "ORDER BY bill_key DESC"
    )
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(cur, sql, (f"{month_prefix}/%", len(serial), serial))


# Pre-date lookup, kept for app builds already installed on patients' phones.
def _candidates_by_phone_mssql(phone_n: str) -> list[dict]:
    sql = (
        "SELECT BILL_KEY AS bill_key, RTRIM(BILL_NO) AS bill_no, RTRIM(ISNULL(PHONE,'')) AS phone, "
        "RTRIM(ISNULL(PATIENTNAME,'')) AS patientname, CONVERT(varchar, BILLDATE, 103) AS billdate "
        f"FROM BILL_HEAD WHERE {mssql_phone10('PHONE')} = %s ORDER BY BILL_KEY DESC"
    )
    with mssql_conn() as conn, conn.cursor() as cur:
        return fetch_all(cur, sql, (phone_n,))


def _candidates_by_phone_neon(phone_n: str) -> list[dict]:
    sql = (
        "SELECT bill_key, rtrim(coalesce(bill_no,'')) AS bill_no, rtrim(coalesce(phone,'')) AS phone, "
        "rtrim(coalesce(patientname,'')) AS patientname, to_char(billdate,'DD/MM/YYYY') AS billdate "
        f"FROM bill_head WHERE {_PHONE10 % 'phone'} = %s ORDER BY bill_key DESC"
    )
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(cur, sql, (phone_n,))


def bill_month_of(bill_date: str) -> str:
    """"YYYY/MM" for an ISO date the patient picked, or "" when it is unusable.

    Only the month is kept: within a month an ALC serial already identifies one bill,
    so a patient who misremembers the exact day still gets in.
    """
    try:
        d = date.fromisoformat((bill_date or "").strip())
    except ValueError:
        return ""
    return f"{d.year:04d}/{d.month:02d}"


def verify_customer(
    name: str,
    bill_no: str,
    bill_date: str = "",
    phone: str = "",
) -> dict[str, Any]:
    """Find the bills matching a patient name, a bill date and a bill number.

    The month comes from [bill_date] (or from a full "YYYY/MM/ALC/NNNN" number) and
    picks out one ALC serial; the name then has to match that bill fuzzily. The phone
    is read back OFF the matched bill — it is what the rest of the portal keys off,
    and the patient never types it.

    [phone] is the pre-date lookup, still accepted so app builds already installed on
    patients' phones keep working: there the month may be absent and the phone
    narrows the search instead.
    """
    name = (name or "").strip()
    bill_no = (bill_no or "").strip()
    serial = _bill_serial(bill_no)
    phone_n = _norm_phone(phone)
    legacy = not (bill_date or "").strip() and len(phone_n) == 10
    # Typing the whole "YYYY/MM/ALC/NNN" is the only way a serial shorter than 3 digits
    # is unambiguous; the month from a picked date does not vouch for the digits.
    typed_full_number = bool(_bill_month(bill_no))
    month_prefix = _bill_month(bill_no) or bill_month_of(bill_date) or None

    if not name:
        raise ValueError("Enter the patient name as printed on the bill")
    if not month_prefix and not legacy:
        raise ValueError("Choose the date printed on the bill")
    if legacy and _is_junk_phone(phone_n):
        raise ValueError("Enter the full 10-digit mobile number registered at the clinic")
    if (not serial or not serial.isascii() or not serial.isdigit() or len(serial) > 12
            or (len(serial) < 3 and not typed_full_number)):
        raise ValueError("Enter the bill number or its last 3-4 digits as printed on the receipt")

    args = (phone_n,) if legacy else (month_prefix, serial)
    fetch_live, fetch_mirror = (
        (_candidates_by_phone_mssql, _candidates_by_phone_neon) if legacy
        else (_candidates_mssql, _candidates_neon)
    )
    try:
        rows = fetch_live(*args)
        source = "live"
    except Exception as exc:  # clinic DB down/unreachable - the mirror still knows every bill
        logger.warning("verify: MSSQL unavailable (%s); answering from Neon", exc)
        rows = fetch_mirror(*args)
        source = "mirror"

    matched, seen = [], set()
    for r in rows:
        if r["bill_key"] in seen:
            continue
        if not _row_matches(r, name, serial, month_prefix, phone_n if legacy else None):
            continue
        seen.add(r["bill_key"])
        matched.append({
            "bill_key": r["bill_key"],
            "bill_no": r["bill_no"],
            "bill_date": r["billdate"],
            "patient_name": r["patientname"].strip(),
        })

    # The phone comes off the bill, so a match hands back the number the clinic has on
    # file even though the patient only typed a name, a date and a bill number.
    phone_out = _norm_phone(
        next((r["phone"] for r in rows if r["bill_key"] == matched[0]["bill_key"]), "")
    ) if matched else ""
    return {
        "matched": bool(matched) and len(phone_out) == 10,
        "patient_name": matched[0]["patient_name"] if matched else "",
        "phone": phone_out if matched else "",
        "bills": matched,
        "source": source,
    }


# ---------------------------------------------------------------- all-history list (My Reports)
_PHONE10 = r"right(regexp_replace(coalesce(%s,''),'\D','','g'),10)"


# The ETL mirrors AKTIV into Neon every 15 minutes, but only while the clinic PC is on.
# Bills made (or reports authorised) since the last sync are missing or stale in the
# mirror, which is exactly when a patient who just verified against the LIVE database
# comes looking. So the live database overlays the mirror for this recent window.
LIVE_OVERLAY_DAYS = 45


def _history_neon(ph: str) -> tuple[list[dict], list[dict]]:
    ph_col = _PHONE10 % "h.phone"
    with neon_conn() as conn, conn.cursor() as cur:
        bills = fetch_all(
            cur,
            f"""
            SELECT h.bill_key,
                   rtrim(coalesce(h.patientname,'')) AS patient_name,
                   h.billdate                          AS raw_date,
                   rtrim(coalesce(h.bill_no,''))       AS bill_no,
                   string_agg(DISTINCT rtrim(t.testname), ', ') AS tests
            FROM bill_head h
            JOIN bill_test_dtls d ON d.bill_key = h.bill_key
            LEFT JOIN mast_test t ON t.test_key = d.test_key
            WHERE {ph_col} = %s
            GROUP BY h.bill_key, h.patientname, h.billdate, h.bill_no
            """,
            (ph,),
        )
        # confirmed report items (for the collated view link), all bills in one shot
        items = fetch_all(
            cur,
            f"""
            SELECT d.bill_key, d.category_key, d.report_key
            FROM bill_test_dtls d
            JOIN bill_head h ON h.bill_key = d.bill_key
            WHERE {ph_col} = %s
              AND coalesce(d.report_key,0) > 0
              AND lower(d.confirm_report::text) IN ('1','true','t')
            """,
            (ph,),
        )
    return bills, items


def _history_mssql(ph: str, since: Optional[date]) -> tuple[list[dict], list[dict]]:
    """Same shape as _history_neon, from the live database (tests aggregated here,
    since STRING_AGG needs a newer SQL Server than the clinic may run)."""
    phone_sql = f"{mssql_phone10('h.PHONE')} = %s"
    date_sql = " AND h.BILLDATE >= %s" if since else ""
    params: tuple = (ph, since) if since else (ph,)
    with mssql_conn() as conn, conn.cursor() as cur:
        rows = fetch_all(
            cur,
            "SELECT h.BILL_KEY AS bill_key, RTRIM(ISNULL(h.PATIENTNAME,'')) AS patient_name, "
            "h.BILLDATE AS raw_date, RTRIM(ISNULL(h.BILL_NO,'')) AS bill_no, "
            "RTRIM(ISNULL(t.TESTNAME,'')) AS testname "
            "FROM BILL_HEAD h JOIN BILL_TEST_DTLS d ON d.BILL_KEY = h.BILL_KEY "
            "LEFT JOIN MAST_TEST t ON t.TEST_KEY = d.TEST_KEY "
            f"WHERE {phone_sql}{date_sql}",
            params,
        )
        items = fetch_all(
            cur,
            "SELECT d.BILL_KEY AS bill_key, d.CATEGORY_KEY AS category_key, d.REPORT_KEY AS report_key "
            "FROM BILL_TEST_DTLS d JOIN BILL_HEAD h ON h.BILL_KEY = d.BILL_KEY "
            f"WHERE {phone_sql}{date_sql} AND ISNULL(d.REPORT_KEY,0) > 0 "
            "AND CONVERT(varchar(4), d.CONFIRM_REPORT) = '1'",
            params,
        )
    bills: dict[Any, dict] = {}
    for r in rows:
        b = bills.setdefault(r["bill_key"], {**r, "tests": set()})
        if r["testname"]:
            b["tests"].add(r["testname"])
    out = []
    for b in bills.values():
        b["tests"] = ", ".join(sorted(b.pop("tests")))
        b.pop("testname", None)
        out.append(b)
    return out, items


def _as_date(v: Any) -> Optional[date]:
    if isinstance(v, datetime):
        return v.date()
    return v if isinstance(v, date) else None


def customer_history(phone: str) -> dict[str, Any]:
    """All AKTIV visits under this phone since the clinic's founding (2022). Names may
    differ (relatives share a phone). Each visit carries a COLLATED view link (one PDF
    for the whole bill); the PDF itself is only fetched when the patient taps View.

    The Neon mirror supplies the long history; the live database overlays the last
    LIVE_OVERLAY_DAYS so new bills and freshly authorised reports show at once. Either
    store alone is enough to answer - whichever is down, the list still loads.
    """
    ph = _norm_phone(phone)
    if len(ph) < 10:
        return {"phone": phone, "visits": []}

    bills: dict[Any, dict] = {}
    items: dict[Any, list[dict]] = {}
    neon_ok = live_ok = False
    try:
        nb, ni = _history_neon(ph)
        neon_ok = True
        for b in nb:
            bills[b["bill_key"]] = b
        for it in ni:
            items.setdefault(it["bill_key"], []).append(it)
    except Exception as exc:
        logger.warning("history: Neon unavailable (%s); using live database only", exc)
    try:
        since = date.today() - timedelta(days=LIVE_OVERLAY_DAYS) if neon_ok else None
        lb, li = _history_mssql(ph, since)
        live_ok = True
        live_items: dict[Any, list[dict]] = {}
        for it in li:
            live_items.setdefault(it["bill_key"], []).append(it)
        for b in lb:
            # live wins for the overlay window: it knows the latest authorisations
            bills[b["bill_key"]] = b
            items[b["bill_key"]] = live_items.get(b["bill_key"], [])
    except Exception as exc:
        if not neon_ok:
            raise
        logger.info("history: live overlay skipped (%s)", exc)

    ordered = sorted(
        bills.values(),
        key=lambda b: (_as_date(b["raw_date"]) or date.min, b["bill_key"]),
        reverse=True,
    )
    visits = []
    for b in ordered:
        report_set = sorted(
            {(it["category_key"], it["report_key"]) for it in items.get(b["bill_key"], [])},
            key=lambda item: (item[0] or 0, item[1]),
        )
        ready_items = [
            _view_item(b["patient_name"], b["bill_key"], category_key, report_key, b["bill_no"])
            for category_key, report_key in report_set
        ]
        d = _as_date(b["raw_date"])
        visits.append({
            "bill_key": b["bill_key"],
            "patient_name": b["patient_name"],
            "bill_date": d.strftime("%d %b %Y") if d else "",
            "bill_no": b["bill_no"],            # the ALC number
            "tests": b["tests"] or "",
            "ready": bool(ready_items),
            # collated single-PDF link (fetched only on tap); None until any report is ready
            "view_link": build_collated_view_link(b["patient_name"], b["bill_no"], ready_items) if ready_items else None,
        })
    return {
        "phone": ph,
        "count": len(visits),
        "visits": visits,
        "source": "live+mirror" if (neon_ok and live_ok) else ("mirror" if neon_ok else "live"),
    }
