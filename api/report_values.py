"""Structured pathology result values for a bill — powers the report visualizer.

Reads the LIVE AKTIV MSSQL per-department result tables (CHEMICAL_DTLS,
HAEMATOLOGY_DTLS, HORMONE_DTLS, SEROLOGY_DTLS, …). These are NOT mirrored to
Neon, so this needs the clinic PC to be up. There is no stored high/low flag —
we compute it by parsing RESULT against the low/high range.

How the rows really hang together (verified against the clinic DB, Nov 2026):
  - A bill's reports are found through ``<DEPT>_HEAD.BILL_KEY``; the detail rows
    join on ``REPORT_KEY``. ``<DEPT>_DTLS.BILL_KEY`` exists as a column but is
    NULL on every row, so filtering the detail table by it returns nothing —
    that was the "PDF opens but no values on screen" bug.
  - ``REPORT_KEY`` is numbered per department, so it must never be matched
    across tables; authorisation is read from the department's own
    ``<DEPT>_HEAD.CONFIRM_REPORT``.
  - ``<DEPT>_DTLS.PARAMETER`` is the *method* line ("Method : GOD-POD"). The
    analyte name is ``MAST_DESCRIPTION.DESCRIPTION`` via ``DESCRIPTION_KEY``.
  - Rows with no result are layout rows (panel headings, spacers) and are skipped.

Table and column names are discovered from INFORMATION_SCHEMA rather than
hard-coded, so a department table shaped differently is skipped, not fatal.
"""
from __future__ import annotations

import re
from typing import Any

from db import fetch_all, mssql_conn

# Candidate column names (first match wins), matched case-insensitively.
PARAM_COLS = ["PARAMETER", "TESTNAME", "TEST_NAME", "PARTICULARS", "INVESTIGATION", "ANALYTE", "NAME"]
RESULT_COLS = ["RESULT", "RESULTVALUE", "RESULT_VALUE", "VALUE", "OBSERVEDVALUE", "REPORTVALUE", "REPORT_VALUE"]
UNIT_COLS = ["UNITNAME", "UNIT", "UNITS"]
RANGE_COLS = ["NORMALRANGE", "NORMAL_RANGE", "BIOREFRANGE", "REFRANGE", "REF_RANGE", "REFERENCERANGE", "RANGE"]
LOW_COLS = ["LOWER_RANGE", "LOWRANGE", "LOW_RANGE", "MIN_RANGE", "MINVALUE", "MIN_VALUE", "L_RANGE"]
HIGH_COLS = ["UPPER_RANGE", "HIGHRANGE", "HIGH_RANGE", "MAX_RANGE", "MAXVALUE", "MAX_VALUE", "H_RANGE"]

DESC_TABLE = "MAST_DESCRIPTION"

_NUM = re.compile(r"-?\d+(?:\.\d+)?")
_METHOD_PREFIX = re.compile(r"^\s*method\s*[:\-]*\s*", re.IGNORECASE)

# UPPER table name -> {UPPER column name -> real column name}. The schema does not
# change while the server runs, so it is read once (and re-read if it came back empty).
_schema_cache: dict[str, dict[str, str]] = {}


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


def _as_str(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _pick(cols: dict[str, str], candidates: list[str]) -> str | None:
    for c in candidates:
        if c in cols:
            return cols[c]
    return None


def _schema(cur) -> dict[str, dict[str, str]]:
    """Columns of every ``*_DTLS`` / ``*_HEAD`` table plus MAST_DESCRIPTION, in one query."""
    if _schema_cache:
        return _schema_cache
    try:
        rows = fetch_all(
            cur,
            "SELECT c.TABLE_NAME, c.COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS c "
            "INNER JOIN INFORMATION_SCHEMA.TABLES t ON t.TABLE_NAME = c.TABLE_NAME "
            "AND t.TABLE_SCHEMA = c.TABLE_SCHEMA "
            "WHERE t.TABLE_TYPE = 'BASE TABLE' AND (c.TABLE_NAME LIKE '%[_]DTLS' "
            "OR c.TABLE_NAME LIKE '%[_]HEAD' OR c.TABLE_NAME = %s)",
            (DESC_TABLE,),
        )
    except Exception:
        return {}
    schema: dict[str, dict[str, str]] = {}
    for r in rows:
        table, col = r["TABLE_NAME"] or "", r["COLUMN_NAME"] or ""
        schema.setdefault(table.upper(), {})[col.upper()] = col
    _schema_cache.update(schema)
    return schema


def _sources(cur) -> list[dict[str, Any]]:
    """One entry per department result table, with the SQL to read a bill's rows from it.

    A result table is any ``*_DTLS`` with REPORT_KEY, a parameter column and a result
    column. Its rows are tied to the bill through the matching ``*_HEAD`` (preferred —
    that is where AKTIV actually stores BILL_KEY); only when there is no usable head do
    we fall back to a BILL_KEY column on the detail table itself.
    """
    schema = _schema(cur)
    desc_cols = schema.get(DESC_TABLE, {})
    sources: list[dict[str, Any]] = []
    for table in sorted(schema):
        if not table.endswith("_DTLS"):
            continue
        cols = schema[table]
        rk_c = cols.get("REPORT_KEY")
        param_c = _pick(cols, PARAM_COLS)
        result_c = _pick(cols, RESULT_COLS)
        if not (rk_c and param_c and result_c):
            continue

        head = table[:-5] + "_HEAD"
        hcols = schema.get(head, {})
        if "BILL_KEY" in hcols and "REPORT_KEY" in hcols:
            strategy = "head"
            frm = f"FROM [{table}] d INNER JOIN [{head}] h ON h.[{hcols['REPORT_KEY']}] = d.[{rk_c}]"
            where = f"WHERE h.[{hcols['BILL_KEY']}] = %s"
            confirm = f"h.[{hcols['CONFIRM_REPORT']}]" if "CONFIRM_REPORT" in hcols else "NULL"
        elif "BILL_KEY" in cols:
            strategy, head = "bill_key", None
            frm = f"FROM [{table}] d"
            where = f"WHERE d.[{cols['BILL_KEY']}] = %s"
            confirm = "NULL"
        else:
            continue

        def dcol(name: str | None) -> str:
            return f"d.[{name}]" if name else "NULL"

        dk_c = cols.get("DESCRIPTION_KEY")
        use_desc = bool(dk_c and "DESCRIPTION_KEY" in desc_cols and "DESCRIPTION" in desc_cols)
        join = (
            f"LEFT JOIN [{DESC_TABLE}] m ON m.[{desc_cols['DESCRIPTION_KEY']}] = d.[{dk_c}]"
            if use_desc
            else ""
        )
        order_c = cols.get("REPORT_DTLS_KEY")
        select = ", ".join(
            [
                f"d.[{rk_c}] AS report_key",
                (f"m.[{desc_cols['DESCRIPTION']}] AS analyte" if use_desc else "NULL AS analyte"),
                f"d.[{param_c}] AS parameter",
                f"d.[{result_c}] AS result",
                f"{dcol(_pick(cols, UNIT_COLS))} AS unit",
                f"{dcol(_pick(cols, RANGE_COLS))} AS range_text",
                f"{dcol(_pick(cols, LOW_COLS))} AS low",
                f"{dcol(_pick(cols, HIGH_COLS))} AS high",
                f"{confirm} AS confirmed",
            ]
        )
        order = f"ORDER BY d.[{rk_c}]" + (f", d.[{order_c}]" if order_c else "")
        sources.append(
            {
                "table": table,
                "head": head,
                "strategy": strategy,
                "sql": f"SELECT {select} {frm} {join} {where} {order}",
                "count_sql": f"SELECT COUNT_BIG(*) AS n {frm} {where}",
            }
        )
    return sources


def _test_names(cur, bill_key: int) -> dict[Any, str]:
    """report_key -> billed test name(s), for labelling a report section.

    REPORT_KEY repeats across departments, so a key that two of this bill's departments
    share is left unlabelled rather than guessed.
    """
    try:
        rows = fetch_all(
            cur,
            "SELECT b.report_key, b.category_key, RTRIM(ISNULL(t.testname,'')) AS test_name "
            "FROM BILL_TEST_DTLS b LEFT JOIN MAST_TEST t ON t.test_key = b.test_key "
            "WHERE b.bill_key = %s AND ISNULL(b.report_key,0) > 0 ORDER BY b.BILL_TEST_DTLS_KEY",
            (bill_key,),
        )
    except Exception:
        return {}
    names: dict[Any, list[str]] = {}
    cats: dict[Any, set] = {}
    for r in rows:
        rk = r["report_key"]
        cats.setdefault(rk, set()).add(r["category_key"])
        name = (r["test_name"] or "").strip()
        if name and name not in names.setdefault(rk, []):
            names[rk].append(name)
    return {rk: ", ".join(v) for rk, v in names.items() if len(cats.get(rk, ())) == 1}


def _is_blank_result(result: str) -> bool:
    return not result.strip(" .-_")


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

        test_names = _test_names(cur, bill_key)
        by_report: dict[tuple, list[dict]] = {}
        for src in _sources(cur):
            try:
                rows = fetch_all(cur, src["sql"], (bill_key,))
            except Exception:
                continue  # one oddly-shaped department must not blank the whole report

            for r in rows:
                # Only authorised reports, judged by the department's own head row.
                if src["strategy"] == "head" and _as_str(r["confirmed"]) != "1":
                    continue
                result = _as_str(r["result"])
                if _is_blank_result(result):
                    continue  # panel heading / spacer row
                method = _as_str(r["parameter"])
                name = _as_str(r["analyte"]) or method
                if not name:
                    continue
                low, high = _num(r["low"]), _num(r["high"])
                rk = r["report_key"]
                by_report.setdefault((src["table"], rk), []).append(
                    {
                        "name": name,
                        "method": _METHOD_PREFIX.sub("", method) if _as_str(r["analyte"]) else "",
                        "result": result,
                        "unit": _as_str(r["unit"]),
                        "range": _as_str(r["range_text"]),
                        "low": low,
                        "high": high,
                        "value": _num(result),
                        "flag": _flag(result, low, high),
                    }
                )

    single_dept = len({table for table, _ in by_report}) == 1
    reports = [
        {
            "category": table[:-5],
            "report_key": rk,
            # With several departments a shared REPORT_KEY could mislabel; names are
            # already dropped for keys the bill uses in more than one category.
            "test_name": test_names.get(rk, "") if (single_dept or rk in test_names) else "",
            "parameters": params,
        }
        for (table, rk), params in by_report.items()
        if params
    ]
    return _envelope(h, reports)


def report_values_debug(*, bill_key: int) -> dict[str, Any]:
    """Coverage diagnostic: per department table, how it is tied to the bill and how
    many rows this bill has in it. Returns NO patient values."""
    tables: list[dict[str, Any]] = []
    with mssql_conn() as conn, conn.cursor() as cur:
        for src in _sources(cur):
            entry: dict[str, Any] = {
                "table": src["table"],
                "exists": True,
                "strategy": src["strategy"],
                "head": src["head"],
            }
            try:
                r = fetch_all(cur, src["count_sql"], (bill_key,))
                entry["row_count"] = int(r[0]["n"]) if r else 0
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
