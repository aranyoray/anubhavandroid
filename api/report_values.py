"""Structured pathology result values for a bill — powers the report visualizer.

Reads the LIVE AKTIV MSSQL per-department result tables (CHEMICAL_DTLS,
HAEMATOLOGY_DTLS, HORMONE_DTLS, SEROLOGY_DTLS, …). These are NOT mirrored to
Neon, so this needs the clinic PC to be up. Each *_DTLS row is one analyte with
its result, unit and reference range (snapshotted at report time). There is no
stored high/low flag — we compute it by parsing RESULT against the low/high range.

Two keying strategies per the AKTIV schema:
  - bill_key   : the *_DTLS table has a BILL_KEY column (chemistry, haematology,
                 hormone, urine, fluids).
  - report_key : the *_DTLS table has only REPORT_KEY; join its *_HEAD (which
                 carries BILL_KEY) on REPORT_KEY (serology, micro, mantoux, …).
Column names vary across AKTIV installs, so the exact result/parameter/unit/
range columns are discovered from INFORMATION_SCHEMA rather than hard-coded —
a table keyed or named differently is skipped, not fatal.
"""
from __future__ import annotations

import re
from typing import Any

from db import fetch_all, mssql_conn

# (dtls_table, strategy, head_table). strategy "bill_key" filters d.BILL_KEY;
# "report_key" joins head_table h ON h.REPORT_KEY = d.REPORT_KEY, filters h.BILL_KEY.
DEPT_TABLES: list[tuple[str, str, str | None]] = [
    ("CHEMICAL_DTLS", "bill_key", None),
    ("HAEMATOLOGY_DTLS", "bill_key", None),
    ("HORMONE_DTLS", "bill_key", None),
    ("IMMUNOLOGY_DTLS", "bill_key", None),
    ("URINE_DTLS", "bill_key", None),
    ("URINALYSIS_DTLS", "bill_key", None),
    ("BODYFLUID_DTLS", "bill_key", None),
    ("FLUID_DTLS", "bill_key", None),
    ("SEROLOGY_DTLS", "report_key", "SEROLOGY_HEAD"),
    ("MICRO_DTLS", "report_key", "MICRO_HEAD"),
    ("MANTOUX_DTLS", "report_key", "MANTOUX_HEAD"),
    ("CYTOLOGY_DTLS", "report_key", "CYTOLOGY_HEAD"),
]

# Candidate column names (first match wins), matched case-insensitively.
PARAM_COLS = ["PARAMETER", "TESTNAME", "TEST_NAME", "PARTICULARS", "INVESTIGATION", "ANALYTE", "NAME"]
RESULT_COLS = ["RESULT", "RESULTVALUE", "RESULT_VALUE", "VALUE", "OBSERVEDVALUE", "REPORTVALUE", "REPORT_VALUE"]
UNIT_COLS = ["UNITNAME", "UNIT", "UNITS"]
RANGE_COLS = ["NORMALRANGE", "NORMAL_RANGE", "BIOREFRANGE", "REFRANGE", "REF_RANGE", "REFERENCERANGE", "RANGE"]
LOW_COLS = ["LOWER_RANGE", "LOWRANGE", "LOW_RANGE", "MIN_RANGE", "MINVALUE", "MIN_VALUE", "L_RANGE"]
HIGH_COLS = ["UPPER_RANGE", "HIGHRANGE", "HIGH_RANGE", "MAX_RANGE", "MAXVALUE", "MAX_VALUE", "H_RANGE"]

_NUM = re.compile(r"-?\d+(?:\.\d+)?")


def _num(value: Any) -> float | None:
    if value is None:
        return None
    match = _NUM.search(str(value))
    return float(match.group()) if match else None


def _norm_phone(phone: str | None) -> str:
    return re.sub(r"\D", "", phone or "")[-10:]


def _flag(result: Any, low: float | None, high: float | None) -> str:
    """NORMAL / LOW / HIGH. Text results or params with no numeric range are NORMAL."""
    value = _num(result)
    if value is None or (low is None and high is None):
        return "NORMAL"
    if low is not None and value < low:
        return "LOW"
    if high is not None and value > high:
        return "HIGH"
    return "NORMAL"


def _columns(cur, table: str) -> dict[str, str]:
    """UPPER-name -> real column name for a table, or {} if the table is absent."""
    try:
        rows = fetch_all(
            cur,
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = %s",
            (table,),
        )
    except Exception:
        return {}
    return {(r["COLUMN_NAME"] or "").upper(): r["COLUMN_NAME"] for r in rows}


def _pick(cols: dict[str, str], candidates: list[str]) -> str | None:
    for c in candidates:
        if c in cols:
            return cols[c]
    return None


def _as_str(value: Any) -> str:
    return str(value).strip() if value is not None else ""


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

        by_report: dict[tuple, list[dict]] = {}
        for table, strategy, head_table in DEPT_TABLES:
            cols = _columns(cur, table)
            if not cols:
                continue
            param_c = _pick(cols, PARAM_COLS)
            if not param_c:
                continue  # nothing to label a row with
            result_c = _pick(cols, RESULT_COLS)
            unit_c = _pick(cols, UNIT_COLS)
            range_c = _pick(cols, RANGE_COLS)
            low_c = _pick(cols, LOW_COLS)
            high_c = _pick(cols, HIGH_COLS)
            rk_c = cols.get("REPORT_KEY")
            tk_c = cols.get("TEST_KEY")

            def dcol(name: str | None) -> str:
                return f"d.[{name}]" if name else "NULL"

            select = [
                f"{dcol(rk_c)} AS report_key",
                f"d.[{param_c}] AS parameter",
                f"{dcol(result_c)} AS result",
                f"{dcol(unit_c)} AS unit",
                f"{dcol(range_c)} AS range_text",
                f"{dcol(low_c)} AS low",
                f"{dcol(high_c)} AS high",
                ("RTRIM(ISNULL(t.testname,'')) AS test_name" if tk_c else "'' AS test_name"),
            ]
            join = f"LEFT JOIN MAST_TEST t ON t.test_key = d.[{tk_c}]" if tk_c else ""

            if strategy == "bill_key" and "BILL_KEY" in cols:
                sql = f"SELECT {', '.join(select)} FROM {table} d {join} WHERE d.[{cols['BILL_KEY']}] = %s"
            elif strategy == "report_key" and rk_c and head_table:
                sql = (
                    f"SELECT {', '.join(select)} FROM {table} d "
                    f"INNER JOIN {head_table} h ON h.REPORT_KEY = d.[{rk_c}] {join} "
                    f"WHERE h.BILL_KEY = %s"
                )
            elif "BILL_KEY" in cols:  # some installs add BILL_KEY to report_key tables
                sql = f"SELECT {', '.join(select)} FROM {table} d {join} WHERE d.[{cols['BILL_KEY']}] = %s"
            else:
                continue

            try:
                rows = fetch_all(cur, sql, (bill_key,))
            except Exception:
                continue

            for r in rows:
                rk = r["report_key"]
                # When the department links reports, only show authorised ones.
                if confirmed and rk is not None and rk not in confirmed:
                    continue
                name = (r["parameter"] or "").strip()
                if not name:
                    continue
                low, high = _num(r["low"]), _num(r["high"])
                by_report.setdefault((table, rk, (r["test_name"] or "").strip()), []).append(
                    {
                        "name": name,
                        "result": _as_str(r["result"]),
                        "unit": _as_str(r["unit"]),
                        "range": _as_str(r["range_text"]),
                        "low": low,
                        "high": high,
                        "value": _num(r["result"]),
                        "flag": _flag(r["result"], low, high),
                    }
                )

    reports = [
        {"category": table.replace("_DTLS", ""), "report_key": rk, "test_name": test_name, "parameters": params}
        for (table, rk, test_name), params in by_report.items()
        if params
    ]
    return _envelope(h, reports)


def report_values_debug(*, bill_key: int) -> dict[str, Any]:
    """Coverage diagnostic: per department table, whether it exists, the columns the
    extractor chose, and how many rows this bill has. Returns NO patient values."""
    tables: list[dict[str, Any]] = []
    with mssql_conn() as conn, conn.cursor() as cur:
        for table, strategy, head_table in DEPT_TABLES:
            cols = _columns(cur, table)
            entry: dict[str, Any] = {"table": table, "exists": bool(cols), "strategy": strategy}
            if not cols:
                tables.append(entry)
                continue
            chosen = {
                "parameter": _pick(cols, PARAM_COLS),
                "result": _pick(cols, RESULT_COLS),
                "unit": _pick(cols, UNIT_COLS),
                "range": _pick(cols, RANGE_COLS),
                "low": _pick(cols, LOW_COLS),
                "high": _pick(cols, HIGH_COLS),
                "report_key": cols.get("REPORT_KEY"),
                "test_key": cols.get("TEST_KEY"),
                "bill_key": cols.get("BILL_KEY"),
            }
            entry["columns"] = chosen
            try:
                if strategy == "bill_key" and "BILL_KEY" in cols:
                    r = fetch_all(cur, f"SELECT COUNT_BIG(*) AS n FROM {table} d WHERE d.[{cols['BILL_KEY']}] = %s", (bill_key,))
                elif strategy == "report_key" and chosen["report_key"] and head_table:
                    r = fetch_all(
                        cur,
                        f"SELECT COUNT_BIG(*) AS n FROM {table} d "
                        f"INNER JOIN {head_table} h ON h.REPORT_KEY = d.[{chosen['report_key']}] WHERE h.BILL_KEY = %s",
                        (bill_key,),
                    )
                elif "BILL_KEY" in cols:
                    r = fetch_all(cur, f"SELECT COUNT_BIG(*) AS n FROM {table} d WHERE d.[{cols['BILL_KEY']}] = %s", (bill_key,))
                else:
                    r = [{"n": None}]
                entry["row_count"] = r[0]["n"] if r else 0
            except Exception as exc:  # noqa: BLE001 — surface the DB message for debugging
                entry["error"] = str(exc)[:200]
            tables.append(entry)
    return {"bill_key": bill_key, "tables": tables}


def _envelope(h: dict, reports: list[dict]) -> dict[str, Any]:
    abnormal = sum(1 for r in reports for p in r["parameters"] if p["flag"] in ("LOW", "HIGH"))
    return {
        "bill_key": h["BILL_KEY"],
        "bill_no": h["bill_no"],
        "patient_name": h["patientname"],
        "bill_date": h["billdate"],
        "abnormal_count": abnormal,
        "reports": reports,
    }
