"""Admin booking-details reports, read straight from AKTIV.

Powers the in-app Admin screen (From/To date range, Bill/Test detail toggles and the
four report buttons: Tests, Income, CC, Due). It reads the live AKTIV MSSQL database the
same way the marketing analytics do (github.com/aranyoray/alc-marketing): revenue is
``BILL_HEAD.NETAMOUNT`` on bills that are not cancelled, a test's modality is
``MAST_TEST -> MAST_SUBDEPARTMENT.DEPARTMENT_KEY``, and a "collection centre" is
``MAST_COLLCENTRE``. Nothing here writes to AKTIV.

Every report returns the same envelope — a list of headline figures plus a list of
tables — so the app renders any of them with one generic view. Money and counts are
formatted here (Indian grouping) so the phone is a thin viewer and the numbers read the
same everywhere.
"""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from typing import Any

from db import fetch_all, mssql_conn

# A date range wider than this is almost always a mistake (a fat-fingered year) and would
# make AKTIV scan years of bills for a phone screen, so it is clamped and the app is told.
MAX_SPAN_DAYS = 366
# Detail lists are the head of a long tail; cap them and say so rather than stream thousands
# of rows to a handset.
BILL_LIMIT = 500
TEST_LIMIT = 100

REPORTS = ("tests", "income", "cc", "due")

# MAST_TEST -> sub-department -> department is the only path from a billed line to a
# modality; these department keys are stable AKTIV master data (see alc-marketing/etl.py).
_MODALITY_CASE = """
case sd.DEPARTMENT_KEY
     when 2 then 'USG'
     when 3 then 'XRAY'
     when 7 then 'CT'
     when 1 then 'PATH'
     when 11 then 'PATH'
     when 8 then 'PATH'
     else 'OTHER'
end
"""

_MODALITY_LABEL = {
    "USG": "USG",
    "XRAY": "X-Ray",
    "CT": "CT",
    "PATH": "Pathology",
    "OTHER": "Other",
}


# ---------------------------------------------------------------- formatting
def _group(n: int) -> str:
    """Indian digit grouping: 1234567 -> '12,34,567'."""
    s = str(abs(int(n)))
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        head = re.sub(r"(\d)(?=(\d\d)+$)", r"\1,", head)
        s = f"{head},{tail}"
    return f"-{s}" if n < 0 else s


def _inr(amount: Any) -> str:
    return "₹" + _group(round(float(amount or 0)))


def _num(n: Any) -> str:
    return _group(int(n or 0))


# ---------------------------------------------------------------- inputs
def _parse_date(value: str | None, fallback: date) -> date:
    if not value:
        return fallback
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y"):
        try:
            return datetime.strptime(str(value).strip(), fmt).date()
        except ValueError:
            continue
    raise ValueError(f"Bad date '{value}', expected YYYY-MM-DD")


def _range(start: str | None, end: str | None) -> tuple[date, date, str | None]:
    """Validated, clamped [start, end] plus a note when the span was trimmed."""
    today = date.today()
    a = _parse_date(start, today)
    b = _parse_date(end, today)
    if a > b:
        a, b = b, a
    note = None
    if (b - a).days > MAX_SPAN_DAYS:
        a = b - timedelta(days=MAX_SPAN_DAYS)
        note = f"Range limited to the last {MAX_SPAN_DAYS} days ({a.isoformat()} → {b.isoformat()})."
    return a, b, note


def _bounds(a: date, b: date) -> tuple[date, date]:
    """The half-open bounds to compare BILLDATE against.

    The upper bound is the day after `b` so the whole end day is included whatever time
    component AKTIV stamped on the bill.
    """
    return a, b + timedelta(days=1)


# ---------------------------------------------------------------- reports
def _section(title: str, columns: list[str], rows: list[list[str]], note: str | None = None) -> dict:
    return {"title": title, "columns": columns, "rows": rows, "note": note}


def _income(cur, a: date, b: date, bill_details: bool) -> tuple[list[dict], list[dict]]:
    lo, hi = _bounds(a, b)
    by_day = fetch_all(
        cur,
        """
        SELECT convert(varchar(10), b.BILLDATE, 23) AS day,
               count(*) AS cases,
               sum(isnull(b.NETAMOUNT, 0)) AS net,
               sum(isnull(b.RECEIVEDAMOUNT, 0)) AS received
        FROM BILL_HEAD b
        WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
          AND isnull(b.BILLCANCEL, 0) = 0
        GROUP BY convert(varchar(10), b.BILLDATE, 23)
        ORDER BY day
        """,
        (lo, hi),
    )
    cases = sum(int(r["cases"] or 0) for r in by_day)
    net = sum(float(r["net"] or 0) for r in by_day)
    received = sum(float(r["received"] or 0) for r in by_day)
    summary = [
        {"label": "Cases", "value": _num(cases)},
        {"label": "Bill value", "value": _inr(net)},
        {"label": "Received", "value": _inr(received)},
        {"label": "Due", "value": _inr(net - received)},
    ]
    sections = [
        _section(
            "By day",
            ["Day", "Cases", "Bill value", "Received"],
            [
                [r["day"], _num(r["cases"]), _inr(r["net"]), _inr(r["received"])]
                for r in by_day
            ],
        )
    ]
    if bill_details:
        sections.append(_bill_section(cur, lo, hi, due_only=False))
    return summary, sections


def _due(cur, a: date, b: date, bill_details: bool) -> tuple[list[dict], list[dict]]:
    lo, hi = _bounds(a, b)
    total = fetch_all(
        cur,
        """
        SELECT count(*) AS bills,
               sum(isnull(b.NETAMOUNT, 0) - isnull(b.RECEIVEDAMOUNT, 0)) AS due
        FROM BILL_HEAD b
        WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
          AND isnull(b.BILLCANCEL, 0) = 0
          AND isnull(b.NETAMOUNT, 0) - isnull(b.RECEIVEDAMOUNT, 0) > 0.01
        """,
        (lo, hi),
    )[0]
    summary = [
        {"label": "Bills with dues", "value": _num(total["bills"])},
        {"label": "Total due", "value": _inr(total["due"])},
    ]
    sections = [_bill_section(cur, lo, hi, due_only=True)] if bill_details else []
    return summary, sections


def _bill_section(cur, lo: date, hi: date, due_only: bool) -> dict:
    where_due = "AND isnull(b.NETAMOUNT, 0) - isnull(b.RECEIVEDAMOUNT, 0) > 0.01" if due_only else ""
    count = fetch_all(
        cur,
        f"""
        SELECT count(*) AS n FROM BILL_HEAD b
        WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
          AND isnull(b.BILLCANCEL, 0) = 0 {where_due}
        """,
        (lo, hi),
    )[0]["n"]
    order = "b.NETAMOUNT - isnull(b.RECEIVEDAMOUNT, 0) DESC" if due_only else "b.BILLDATE DESC, b.BILL_KEY DESC"
    rows = fetch_all(
        cur,
        f"""
        SELECT TOP {BILL_LIMIT}
               rtrim(isnull(b.BILL_NO, '')) AS bill_no,
               convert(varchar(10), b.BILLDATE, 23) AS day,
               rtrim(isnull(b.PATIENTNAME, '')) AS patient,
               isnull(b.NETAMOUNT, 0) AS net,
               isnull(b.RECEIVEDAMOUNT, 0) AS received
        FROM BILL_HEAD b
        WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
          AND isnull(b.BILLCANCEL, 0) = 0 {where_due}
        ORDER BY {order}
        """,
        (lo, hi),
    )
    table = [
        [
            r["bill_no"] or "-",
            r["day"],
            r["patient"] or "-",
            _inr(r["net"]),
            _inr(r["received"]),
            _inr(float(r["net"] or 0) - float(r["received"] or 0)),
        ]
        for r in rows
    ]
    note = None
    if int(count or 0) > len(rows):
        note = f"Showing the first {len(rows)} of {_num(count)} bills."
    return _section(
        "Due bills" if due_only else "Bills",
        ["Bill", "Day", "Patient", "Net", "Received", "Due"],
        table,
        note,
    )


def _tests(cur, a: date, b: date, test_details: bool) -> tuple[list[dict], list[dict]]:
    lo, hi = _bounds(a, b)
    by_mod = fetch_all(
        cur,
        f"""
        SELECT {_MODALITY_CASE} AS modality,
               count(*) AS tests,
               sum(isnull(bd.CHARGE, 0)) AS charge
        FROM BILL_DTLS bd
        JOIN BILL_HEAD b ON b.BILL_KEY = bd.BILL_KEY
        JOIN MAST_TEST t ON t.TEST_KEY = bd.TEST_KEY
        LEFT JOIN MAST_SUBDEPARTMENT sd ON sd.SUBDEPARTMENT_KEY = t.SUBDEPARTMENT_KEY
        WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
          AND isnull(b.BILLCANCEL, 0) = 0
          AND isnull(bd.TESTCANCEL, 0) = 0
        GROUP BY {_MODALITY_CASE}
        ORDER BY tests DESC
        """,
        (lo, hi),
    )
    total_tests = sum(int(r["tests"] or 0) for r in by_mod)
    total_charge = sum(float(r["charge"] or 0) for r in by_mod)
    summary = [
        {"label": "Tests", "value": _num(total_tests)},
        {"label": "Value", "value": _inr(total_charge)},
    ]
    sections = [
        _section(
            "By modality",
            ["Modality", "Tests", "Value"],
            [
                [_MODALITY_LABEL.get(r["modality"], r["modality"]), _num(r["tests"]), _inr(r["charge"])]
                for r in by_mod
            ],
        )
    ]
    if test_details:
        top = fetch_all(
            cur,
            f"""
            SELECT TOP {TEST_LIMIT}
                   rtrim(isnull(t.TESTNAME, '')) AS test,
                   count(*) AS tests,
                   sum(isnull(bd.CHARGE, 0)) AS charge
            FROM BILL_DTLS bd
            JOIN BILL_HEAD b ON b.BILL_KEY = bd.BILL_KEY
            JOIN MAST_TEST t ON t.TEST_KEY = bd.TEST_KEY
            WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
              AND isnull(b.BILLCANCEL, 0) = 0
              AND isnull(bd.TESTCANCEL, 0) = 0
            GROUP BY t.TESTNAME
            ORDER BY tests DESC
            """,
            (lo, hi),
        )
        note = f"Top {len(top)} tests by count." if len(top) >= TEST_LIMIT else None
        sections.append(
            _section(
                "Tests",
                ["Test", "Count", "Value"],
                [[r["test"] or "-", _num(r["tests"]), _inr(r["charge"])] for r in top],
                note,
            )
        )
    return summary, sections


def _cc(cur, a: date, b: date) -> tuple[list[dict], list[dict]]:
    lo, hi = _bounds(a, b)
    rows = fetch_all(
        cur,
        """
        SELECT rtrim(isnull(c.COLLCENTRENAME, '')) AS centre,
               count(*) AS cases,
               sum(isnull(b.NETAMOUNT, 0)) AS net
        FROM BILL_HEAD b
        LEFT JOIN MAST_COLLCENTRE c ON c.COLLCENTRE_KEY = b.COLLCENTRE_KEY
        WHERE b.BILLDATE >= %s AND b.BILLDATE < %s
          AND isnull(b.BILLCANCEL, 0) = 0
        GROUP BY b.COLLCENTRE_KEY, c.COLLCENTRENAME
        ORDER BY net DESC
        """,
        (lo, hi),
    )
    summary = [
        {"label": "Centres", "value": _num(len(rows))},
        {"label": "Cases", "value": _num(sum(int(r["cases"] or 0) for r in rows))},
        {"label": "Bill value", "value": _inr(sum(float(r["net"] or 0) for r in rows))},
    ]
    sections = [
        _section(
            "Collection centres",
            ["Centre", "Cases", "Bill value"],
            [[r["centre"] or "(walk-in)", _num(r["cases"]), _inr(r["net"])] for r in rows],
        )
    ]
    return summary, sections


def build_report(
    report: str,
    start: str | None,
    end: str | None,
    bill_details: bool = True,
    test_details: bool = True,
) -> dict[str, Any]:
    """One booking-details report over [start, end]. See module docstring for the envelope."""
    if report not in REPORTS:
        raise ValueError(f"report must be one of {', '.join(REPORTS)}")
    a, b, range_note = _range(start, end)

    with mssql_conn() as conn, conn.cursor() as cur:
        if report == "income":
            summary, sections = _income(cur, a, b, bill_details)
        elif report == "due":
            summary, sections = _due(cur, a, b, bill_details)
        elif report == "tests":
            summary, sections = _tests(cur, a, b, test_details)
        else:  # cc
            summary, sections = _cc(cur, a, b)

    titles = {"tests": "Tests", "income": "Income", "cc": "Collection Centres", "due": "Dues"}
    return {
        "report": report,
        "title": titles[report],
        "start": a.isoformat(),
        "end": b.isoformat(),
        "bill_details": bool(bill_details),
        "test_details": bool(test_details),
        "range_note": range_note,
        "summary": summary,
        "sections": sections,
    }
