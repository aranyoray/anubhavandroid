"""Collector referral log stored by the mobile portal.

The AKTIV schema is the source of truth for bills and reports. Collector-entered
patient/referral data lives in the app database so we do not write speculative
columns into AKTIV tables.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from customer_portal import list_customer_reports
from db import neon_conn


def _normalize_phone(phone: str) -> str:
    digits = "".join(ch for ch in (phone or "") if ch.isdigit())
    return digits[-10:] if len(digits) > 10 else digits


def _ensure_collector_tables() -> None:
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS collector_patients (
                id SERIAL PRIMARY KEY,
                collector_user_key INTEGER NOT NULL,
                patient_name VARCHAR(200) NOT NULL,
                phone VARCHAR(20) NOT NULL,
                age_year INTEGER,
                sex VARCHAR(20),
                referred_by VARCHAR(200),
                notes TEXT,
                followup_status VARCHAR(40),
                created_at TIMESTAMPTZ DEFAULT NOW(),
                updated_at TIMESTAMPTZ DEFAULT NOW()
            )
            """
        )
        cur.execute(
            """
            ALTER TABLE collector_patients
            ADD COLUMN IF NOT EXISTS followup_status VARCHAR(40)
            """
        )
        cur.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_collector_patients_owner
            ON collector_patients (collector_user_key, created_at DESC)
            """
        )
        conn.commit()


def create_collector_patient(
    *,
    collector_user_key: int,
    patient_name: str,
    phone: str,
    age_year: int | None = None,
    sex: str | None = None,
    referred_by: str | None = None,
    notes: str | None = None,
    followup_status: str | None = None,
) -> dict[str, Any]:
    if collector_user_key <= 0:
        raise ValueError("collector_user_key required")
    if not patient_name.strip():
        raise ValueError("patient_name required")
    phone_norm = _normalize_phone(phone)
    if len(phone_norm) < 10:
        raise ValueError("valid 10-digit phone required")

    _ensure_collector_tables()
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO collector_patients (
                collector_user_key, patient_name, phone, age_year, sex,
                referred_by, notes, followup_status
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            RETURNING id, collector_user_key, patient_name, phone, age_year,
                sex, referred_by, notes, followup_status, created_at, updated_at
            """,
            (
                collector_user_key,
                patient_name.strip(),
                phone_norm,
                age_year,
                sex.strip().upper() if sex else None,
                referred_by.strip() if referred_by else None,
                notes.strip() if notes else None,
                followup_status.strip().upper() if followup_status else None,
            ),
        )
        row = cur.fetchone()
        cols = [d[0] for d in cur.description]
        conn.commit()
    return _serialize_patient(dict(zip(cols, row)))


def list_collector_patients(*, collector_user_key: int, limit: int = 100) -> list[dict[str, Any]]:
    if collector_user_key <= 0:
        raise ValueError("collector_user_key required")
    _ensure_collector_tables()
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT id, collector_user_key, patient_name, phone, age_year, sex,
                referred_by, notes, followup_status, created_at, updated_at
            FROM collector_patients
            WHERE collector_user_key = %s
            ORDER BY created_at DESC, id DESC
            LIMIT %s
            """,
            (collector_user_key, min(max(limit, 1), 200)),
        )
        cols = [d[0] for d in cur.description]
        rows = [dict(zip(cols, row)) for row in cur.fetchall()]
    return [_serialize_patient(row) for row in rows]


def list_collector_reports(*, collector_user_key: int, limit: int = 100) -> list[dict[str, Any]]:
    patients = list_collector_patients(collector_user_key=collector_user_key, limit=limit)
    reports: list[dict[str, Any]] = []
    seen: set[tuple[Any, Any]] = set()
    for patient in patients:
        try:
            patient_reports = list_customer_reports(phone=patient["phone"], limit=limit)
        except ValueError:
            patient_reports = []
        for report in patient_reports:
            key = (report.get("bill_key"), report.get("testcode") or report.get("testname"))
            if key in seen:
                continue
            seen.add(key)
            report["collector_patient_id"] = patient["id"]
            report["patientname"] = report.get("patientname") or patient["patient_name"]
            reports.append(report)
    return reports[: min(max(limit, 1), 200)]


def _serialize_patient(row: dict[str, Any]) -> dict[str, Any]:
    for key in ("created_at", "updated_at"):
        value = row.get(key)
        if isinstance(value, datetime):
            row[key] = value.isoformat()
    return row
