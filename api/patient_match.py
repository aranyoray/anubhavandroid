"""
Fuzzy 2-of-3 patient verification + report view links for the customer portal.

Login (no OTP): patient gives three things and any TWO must match a bill —
  1. Patient name  (fuzzy: tolerates typos / missing middle name)
  2. Bill No  OR  Bill Date   (this pair counts as ONE factor)
  3. Phone number
Phone is often mistyped, so name is a real fallback: name + (bill no|date) still logs in.
With the phone, the LAST DIGITS of the bill number are enough (phone + "4171" for
2026/09/ALC/4171) - that is what patients read off the receipt.

Reads LIVE MSSQL first (includes bills made minutes ago); if the clinic database is
unreachable it answers from the Neon mirror instead, so login and the report list do
not depend on which of the two stores happens to be up.
"""
from __future__ import annotations

import json
import os
import re
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
    return [t for t in re.sub(r"[^a-zA-Z]+", " ", s or "").lower().split() if t]


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
    return re.sub(r"\D", "", p or "")[-10:]


_JUNK_PHONES = {
    "9999999999", "0000000000", "1111111111", "1234567890", "9876543210", "8888888888",
}


def _is_junk_phone(p: str) -> bool:
    d = _norm_phone(p)
    return len(d) < 10 or len(set(d)) == 1 or d in _JUNK_PHONES


def _bill_serial(bill_no: Optional[str]) -> str:
    return (bill_no or "").split("/")[-1].strip().lstrip("0")


def _bill_month(bill_no: Optional[str]) -> str:
    """The YYYY/MM the bill belongs to, from a 'YYYY/MM/ALC/NNN' bill number.

    ALC serials reset to 001 on the 1st of every month, so a serial only
    identifies a bill *within* its month — the month prefix must be kept.
    """
    parts = [p.strip() for p in (bill_no or "").split("/")]
    if len(parts) >= 3 and parts[0].isdigit() and parts[1].isdigit():
        return f"{parts[0]}/{parts[1]}"
    return ""


def _serial_matches(row_serial: str, serial: str, phone_matched: bool) -> bool:
    """Exact serial, or - only alongside a matching phone - its last digits.

    Receipts print the full ALC number but patients type the tail of it. A suffix is
    accepted only when the phone already matched, so it narrows down that phone's own
    bills and never widens a name-only search to strangers' bills.
    """
    if not serial or not row_serial:
        return False
    if row_serial == serial:
        return True
    return phone_matched and len(serial) >= 3 and serial.isdigit() and row_serial.endswith(serial)


def _score_row(
    r: dict,
    name: str,
    phone_n: str,
    phone_valid: bool,
    serial: str,
    bdate: Optional[date],
    month_prefix: Optional[str],
) -> int:
    """2-of-3 factor count for one candidate bill row.

    A bill-serial match only counts when it is in the right month: ALC numbering
    restarts every month, so serial 3 exists in Jan, Feb, … — without pinning the
    month a serial+name pair could resolve to the wrong patient.
    """
    f_phone = 1 if (phone_valid and _norm_phone(r["phone"]) == phone_n) else 0
    serial_ok = _serial_matches(_bill_serial(r["bill_no"]), serial, bool(f_phone))
    if serial_ok and month_prefix and _bill_month(r["bill_no"]) != month_prefix:
        serial_ok = False
    date_ok = bool(bdate) and r["billdate"] == bdate.strftime("%d/%m/%Y")
    f_bill = 1 if (serial_ok or date_ok) else 0
    f_name = 1 if (name and name_score(name, r["patientname"]) >= NAME_MATCH_THRESHOLD) else 0
    return f_phone + f_bill + f_name


def _parse_date(s: Optional[str]) -> Optional[date]:
    if not s:
        return None
    s = str(s).strip()
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y"):
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            continue
    return None


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
NAME_MATCH_THRESHOLD = 0.6


def _serial_variants(serial: str) -> list[str]:
    variants = [serial]
    if serial.isdigit():
        padded = f"{int(serial):03d}"
        if padded != serial:
            variants.append(padded)
    return variants


def _candidates_mssql(phone_n, phone_valid, serial, bdate, month_prefix) -> list[dict]:
    clauses, params = [], []
    if phone_valid:
        clauses.append("RIGHT(REPLACE(REPLACE(REPLACE(ISNULL(PHONE,''),' ',''),'-',''),'+',''),10) = %s")
        params.append(phone_n)
    if bdate:
        clauses.append("CAST(BILLDATE AS date) = %s")
        params.append(bdate)
    if serial:
        like_prefix = f"{month_prefix}/%" if month_prefix else "%"
        ors = []
        for v in _serial_variants(serial):
            ors.append("BILL_NO LIKE %s")
            params.append(f"{like_prefix}/{v}")
        clauses.append("(" + " OR ".join(ors) + ")")
    sql = (
        "SELECT TOP 500 BILL_KEY AS bill_key, RTRIM(BILL_NO) AS bill_no, RTRIM(ISNULL(PHONE,'')) AS phone, "
        "RTRIM(ISNULL(PATIENTNAME,'')) AS patientname, CONVERT(varchar, BILLDATE, 103) AS billdate "
        f"FROM BILL_HEAD WHERE {' OR '.join(clauses)} ORDER BY BILL_KEY DESC"
    )
    with mssql_conn() as conn, conn.cursor() as cur:
        return fetch_all(cur, sql, tuple(params))


def _candidates_neon(phone_n, phone_valid, serial, bdate, month_prefix) -> list[dict]:
    clauses, params = [], []
    if phone_valid:
        clauses.append(f"{_PHONE10 % 'phone'} = %s")
        params.append(phone_n)
    if bdate:
        clauses.append("billdate::date = %s")
        params.append(bdate)
    if serial:
        like_prefix = f"{month_prefix}/%" if month_prefix else "%"
        ors = []
        for v in _serial_variants(serial):
            ors.append("rtrim(bill_no) LIKE %s")
            params.append(f"{like_prefix}/{v}")
        clauses.append("(" + " OR ".join(ors) + ")")
    sql = (
        "SELECT bill_key, rtrim(coalesce(bill_no,'')) AS bill_no, rtrim(coalesce(phone,'')) AS phone, "
        "rtrim(coalesce(patientname,'')) AS patientname, to_char(billdate,'DD/MM/YYYY') AS billdate "
        f"FROM bill_head WHERE {' OR '.join(clauses)} ORDER BY bill_key DESC LIMIT 500"
    )
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(cur, sql, tuple(params))


def verify_customer(
    name: str,
    phone: str,
    bill_no: str = "",
    bill_date: Optional[str] = None,
) -> dict[str, Any]:
    """2-of-3 match. Returns the patient's matching bills and the canonical phone
    (recovered from a matched bill so the rest of the portal can key off phone)."""
    name = (name or "").strip()
    phone_n = _norm_phone(phone)
    phone_valid = bool(phone_n) and not _is_junk_phone(phone_n)
    serial = _bill_serial(bill_no)
    bdate = _parse_date(bill_date)
    # The month the ALC serial belongs to: from the bill date, or from a full
    # "YYYY/MM/ALC/NNNN" number when the patient typed the whole thing.
    month_prefix = (f"{bdate.year:04d}/{bdate.month:02d}" if bdate else None) or _bill_month(bill_no) or None

    if not (phone_valid or bdate or serial):
        return {"matched": False, "reason": "insufficient_input", "patient_name": "", "phone": phone_n, "bills": []}

    try:
        rows = _candidates_mssql(phone_n, phone_valid, serial, bdate, month_prefix)
        source = "live"
    except Exception as exc:  # clinic DB down/unreachable - the mirror still knows every bill
        logger.warning("verify: MSSQL unavailable (%s); answering from Neon", exc)
        rows = _candidates_neon(phone_n, phone_valid, serial, bdate, month_prefix)
        source = "mirror"

    matched, seen = [], set()
    canonical_phone = phone_n
    for r in rows:
        if _score_row(r, name, phone_n, phone_valid, serial, bdate, month_prefix) >= 2 and r["bill_key"] not in seen:
            seen.add(r["bill_key"])
            if not _is_junk_phone(r["phone"]):
                canonical_phone = _norm_phone(r["phone"]) or canonical_phone
            matched.append({
                "bill_key": r["bill_key"],
                "bill_no": r["bill_no"],
                "bill_date": r["billdate"],
                "patient_name": r["patientname"].strip(),
            })

    return {
        "matched": bool(matched),
        "patient_name": matched[0]["patient_name"] if matched else "",
        "phone": canonical_phone,
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
    phone_sql = "RIGHT(REPLACE(REPLACE(REPLACE(ISNULL(h.PHONE,''),' ',''),'-',''),'+',''),10) = %s"
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
        ready_items = [
            _view_item(b["patient_name"], b["bill_key"], it["category_key"], it["report_key"], b["bill_no"])
            for it in items.get(b["bill_key"], [])
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
