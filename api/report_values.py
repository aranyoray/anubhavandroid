"""Structured pathology result values for a bill — powers the report visualizer.

Reads the LIVE AKTIV MSSQL per-department result tables (CHEMICAL_DTLS,
HAEMATOLOGY_DTLS, HORMONE_DTLS, …). These are NOT mirrored to Neon, so this needs
the clinic PC to be up. Each *_DTLS row is one analyte with its result, unit and
reference range (snapshotted at report time). There is no stored high/low flag —
we compute it by parsing RESULT against LOWER_RANGE/UPPER_RANGE.
"""
from __future__ import annotations

import re
from typing import Any

from db import fetch_all, mssql_conn

# CATEGORY_KEY routes to a department table, but rather than depend on the exact
# category→table map we simply probe every department's *_DTLS by BILL_KEY (all of
# them carry BILL_KEY). A missing table on some installs is skipped, not fatal.
DEPT_TABLES = [
    "CHEMICAL", "HAEMATOLOGY", "HORMONE", "IMMUNOLOGY",
    "FLUID", "MICRO", "MISCL", "MIXED", "MANTOUX",
]

_NUM = re.compile(r"-?\d+(?:\.\d+)?")


def _num(value: Any) -> float | None:
    if value is None:
        return None
    match = _NUM.search(str(value))
    return float(match.group()) if match else None


def _norm_phone(phone: str | None) -> str:
    return re.sub(r"\D", "", phone or "")[-10:]


def _flag(result: Any, low: float | None, high: float | None) -> str:
    """NORMAL / LOW / HIGH. Text results (no numeric value) or params with no
    numeric range are treated as NORMAL (not flagged)."""
    value = _num(result)
    if value is None or (low is None and high is None):
        return "NORMAL"
    if low is not None and value < low:
        return "LOW"
    if high is not None and value > high:
        return "HIGH"
    return "NORMAL"


def report_values(*, bill_key: int, phone: str | None = None) -> dict[str, Any]:
    with mssql_conn() as conn, conn.cursor() as cur:
        head = fetch_all(
            cur,
            "SELECT TOP 1 BILL_KEY, RTRIM(ISNULL(BILL_NO,'')) AS bill_no, "
            "RTRIM(ISNULL(PATIENTNAME,'')) AS patientname, RTRIM(ISNULL(PHONE,'')) AS phone, "
            "CONVERT(varchar, BILLDATE, 103) AS billdate, sex "
            "FROM BILL_HEAD WHERE BILL_KEY = %s",
            (bill_key,),
        )
        if not head:
            raise ValueError("bill not found")
        h = head[0]
        if phone and _norm_phone(h["phone"]) != _norm_phone(phone):
            raise PermissionError("bill does not belong to this phone")

        confirmed_rows = fetch_all(
            cur,
            "SELECT DISTINCT report_key FROM BILL_TEST_DTLS "
            "WHERE bill_key = %s AND ISNULL(report_key,0) > 0 "
            "AND CONVERT(varchar(4), confirm_report) = '1'",
            (bill_key,),
        )
        confirmed = {r["report_key"] for r in confirmed_rows}
        if not confirmed:
            return _envelope(h, [])

        by_report: dict[tuple, list[dict]] = {}
        for dept in DEPT_TABLES:
            try:
                rows = fetch_all(
                    cur,
                    f"""
                    SELECT d.REPORT_KEY AS report_key, d.PARAMETER AS parameter,
                           d.RESULT AS result, d.UNITNAME AS unit,
                           d.NORMALRANGE AS range_text, d.LOWER_RANGE AS low,
                           d.UPPER_RANGE AS high, d.TEST_KEY AS test_key,
                           RTRIM(ISNULL(t.testname,'')) AS test_name
                    FROM {dept}_DTLS d
                    LEFT JOIN MAST_TEST t ON t.test_key = d.TEST_KEY
                    WHERE d.BILL_KEY = %s
                    ORDER BY d.REPORT_DTLS_KEY
                    """,
                    (bill_key,),
                )
            except Exception:
                continue  # table absent on this install / transient — skip department
            for r in rows:
                rk = r["report_key"]
                if rk not in confirmed:
                    continue
                low, high = _num(r["low"]), _num(r["high"])
                name = (r["parameter"] or "").strip()
                if not name:
                    continue
                by_report.setdefault((dept, rk, (r["test_name"] or "").strip()), []).append(
                    {
                        "name": name,
                        "result": (r["result"] or "").strip(),
                        "unit": (r["unit"] or "").strip(),
                        "range": (r["range_text"] or "").strip(),
                        "low": low,
                        "high": high,
                        "value": _num(r["result"]),
                        "flag": _flag(r["result"], low, high),
                    }
                )

    reports = [
        {"category": dept, "report_key": rk, "test_name": test_name, "parameters": params}
        for (dept, rk, test_name), params in by_report.items()
        if params
    ]
    return _envelope(h, reports)


def _envelope(h: dict, reports: list[dict]) -> dict[str, Any]:
    abnormal = sum(
        1 for r in reports for p in r["parameters"] if p["flag"] in ("LOW", "HIGH")
    )
    return {
        "bill_key": h["BILL_KEY"],
        "bill_no": h["bill_no"],
        "patient_name": h["patientname"],
        "bill_date": h["billdate"],
        "abnormal_count": abnormal,
        "reports": reports,
    }
