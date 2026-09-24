"""Customer-facing portal — read bills/reports from AKTIV, prebook with slot rules."""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from typing import Any

from aktiv_booking import _next_key, _resolve_tests, push_booking
from config import aktiv_settings
from db import fetch_all, mssql_conn, neon_conn
from patient_match import build_view_link

PREBOOK_DAYS = (10, 20, 30)
TIME_SLOTS = {
    "MORNING": {"label": "8 AM – 12 PM", "start": 8, "end": 12},
    "AFTERNOON": {"label": "12 PM – 4 PM", "start": 12, "end": 16},
    "EVENING": {"label": "4 PM – 7 PM", "start": 16, "end": 19},
}
SLOT_CAPACITY = 30
PREBOOK_ADVANCE_FRACTION = 0.5
RESCHEDULE_PHONE = "9230755876"


def _normalize_phone(phone: str) -> str:
    digits = re.sub(r"\D", "", phone or "")
    if len(digits) > 10:
        digits = digits[-10:]
    return digits


def _phone_clause() -> str:
    return """
    REPLACE(REPLACE(REPLACE(REPLACE(phone, ' ', ''), '-', ''), '+91', ''), '+', '') LIKE %s
    """


def _ensure_slot_table() -> None:
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS customer_prebook_slots (
                id SERIAL PRIMARY KEY,
                slot_date DATE NOT NULL,
                time_slot VARCHAR(20) NOT NULL,
                bill_key INTEGER,
                phone VARCHAR(20) NOT NULL,
                patient_name VARCHAR(200),
                created_at TIMESTAMPTZ DEFAULT NOW(),
                UNIQUE (slot_date, time_slot, bill_key)
            )
            """
        )
        cur.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_prebook_slot_date
            ON customer_prebook_slots (slot_date, time_slot)
            """
        )
        conn.commit()


def _count_slot_bookings(slot_date: date, time_slot: str) -> int:
    _ensure_slot_table()
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT COUNT(*) FROM customer_prebook_slots
            WHERE slot_date = %s AND time_slot = %s
            """,
            (slot_date, time_slot),
        )
        row = cur.fetchone()
        return int(row[0]) if row else 0


def _slot_booking_counts(dates: list[date]) -> dict[tuple[date, str], int]:
    """Bookings per (date, slot) for the whole calendar in one query.

    The calendar used to open a Neon connection (and re-run CREATE TABLE) for every
    date x slot cell — 27 round trips for a three-month view — on every visit to
    the booking screen.
    """
    if not dates:
        return {}
    _ensure_slot_table()
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT slot_date, time_slot, COUNT(*)
            FROM customer_prebook_slots
            WHERE slot_date BETWEEN %s AND %s
            GROUP BY slot_date, time_slot
            """,
            (min(dates), max(dates)),
        )
        return {(row[0], row[1]): int(row[2]) for row in cur.fetchall()}


def _record_slot_booking(
    *,
    slot_date: date,
    time_slot: str,
    bill_key: int,
    phone: str,
    patient_name: str,
) -> None:
    _ensure_slot_table()
    with neon_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO customer_prebook_slots (slot_date, time_slot, bill_key, phone, patient_name)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT DO NOTHING
            """,
            (slot_date, time_slot, bill_key, phone, patient_name),
        )
        conn.commit()


def _valid_prebook_date(d: date) -> bool:
    return d.day in PREBOOK_DAYS and d >= date.today()


def _upcoming_prebook_dates(months_ahead: int = 3) -> list[date]:
    today = date.today()
    end = today + timedelta(days=months_ahead * 31)
    out: list[date] = []
    cursor = date(today.year, today.month, 1)
    while cursor <= end:
        for day in PREBOOK_DAYS:
            try:
                candidate = date(cursor.year, cursor.month, day)
            except ValueError:
                continue
            if candidate >= today:
                out.append(candidate)
        if cursor.month == 12:
            cursor = date(cursor.year + 1, 1, 1)
        else:
            cursor = date(cursor.year, cursor.month + 1, 1)
    return sorted(out)


def get_customer_profile(*, phone: str | None = None, email: str | None = None) -> dict[str, Any]:
    phone_norm = _normalize_phone(phone or "")
    if not phone_norm and not email:
        raise ValueError("phone or email required")

    with mssql_conn() as conn, conn.cursor() as cur:
        if phone_norm:
            cur.execute(
                f"""
                SELECT TOP 1 regt_key, registration_no, patientname, phone, sex,
                       ageyear, agemonth, ageday
                FROM MAST_PATIENT
                WHERE {_phone_clause()}
                ORDER BY regt_key DESC
                """,
                (f"%{phone_norm}",),
            )
        else:
            cur.execute(
                """
                SELECT TOP 1 regt_key, registration_no, patientname, phone, sex,
                       ageyear, agemonth, ageday
                FROM MAST_PATIENT
                WHERE email = %s
                ORDER BY regt_key DESC
                """,
                (email.strip(),),
            )
        row = cur.fetchone()
        if not row:
            return {
                "found": False,
                "phone": phone_norm,
                "email": email,
                "patient_name": None,
                "registration_no": None,
            }
        cols = [d[0].lower() for d in cur.description]
        patient = dict(zip(cols, row))
        return {
            "found": True,
            "regt_key": patient.get("regt_key"),
            "registration_no": patient.get("registration_no"),
            "patient_name": patient.get("patientname"),
            "phone": patient.get("phone"),
            "sex": patient.get("sex"),
            "age_year": patient.get("ageyear"),
            "age_month": patient.get("agemonth"),
            "age_day": patient.get("ageday"),
            "email": email,
        }


def list_customer_bills(*, phone: str, limit: int = 50) -> list[dict[str, Any]]:
    phone_norm = _normalize_phone(phone)
    if len(phone_norm) < 10:
        raise ValueError("valid 10-digit phone required")

    with mssql_conn() as conn, conn.cursor() as cur:
        rows = fetch_all(
            cur,
            f"""
            SELECT TOP {max(1, min(limit, 100))}
                b.bill_key, b.bill_no, b.billdate, b.patientname, b.phone,
                b.billamount, b.netamount, b.receivedamount,
                (b.netamount - ISNULL(b.receivedamount, 0)) AS pending_amount,
                b.remarks, a.apnt_key, a.apntdate, a.apnt_no,
                (SELECT COUNT(DISTINCT d.report_key) FROM BILL_TEST_DTLS d
                    WHERE d.bill_key = b.bill_key AND ISNULL(d.report_key, 0) > 0) AS report_count,
                (SELECT COUNT(DISTINCT d.report_key) FROM BILL_TEST_DTLS d
                    WHERE d.bill_key = b.bill_key AND CONVERT(varchar(4), d.confirm_report) = '1') AS ready_count
            FROM BILL_HEAD b
            LEFT JOIN APNT_HEAD a ON a.bill_key = b.bill_key
            WHERE REPLACE(REPLACE(REPLACE(REPLACE(b.phone, ' ', ''), '-', ''), '+91', ''), '+', '') LIKE %s
            ORDER BY b.billdate DESC, b.bill_key DESC
            """,
            (f"%{phone_norm}",),
        )
    for row in rows:
        row["billdate"] = row["billdate"].isoformat() if row.get("billdate") else None
        row["apntdate"] = row["apntdate"].isoformat() if row.get("apntdate") else None
        row["pending_amount"] = float(row.get("pending_amount") or 0)
        row["netamount"] = float(row.get("netamount") or 0)
        row["receivedamount"] = float(row.get("receivedamount") or 0)
        row["reschedule_phone"] = RESCHEDULE_PHONE
        row["reschedule_note"] = (
            f"To reschedule, call {RESCHEDULE_PHONE}. Staff will update AKTIV."
        )
    return rows


def list_customer_reports(*, phone: str, limit: int = 50) -> list[dict[str, Any]]:
    phone_norm = _normalize_phone(phone)
    if len(phone_norm) < 10:
        raise ValueError("valid 10-digit phone required")

    with mssql_conn() as conn, conn.cursor() as cur:
        rows = fetch_all(
            cur,
            f"""
            SELECT TOP {max(1, min(limit, 100))}
                b.bill_key, b.bill_no, b.billdate, b.patientname,
                t.testname, t.testcode, d.reportingdate,
                d.category_key, d.report_key, d.confirm_report,
                CASE WHEN CONVERT(varchar(4), d.confirm_report) = '1' THEN 'READY' ELSE 'PENDING' END AS status
            FROM BILL_HEAD b
            INNER JOIN BILL_TEST_DTLS d ON d.bill_key = b.bill_key
            LEFT JOIN MAST_TEST t ON t.test_key = d.test_key
            WHERE {_phone_clause()} AND ISNULL(d.report_key, 0) > 0
            ORDER BY b.billdate DESC, d.reportingdate DESC
            """,
            (f"%{phone_norm}",),
        )
    for row in rows:
        row["billdate"] = row["billdate"].isoformat() if row.get("billdate") else None
        row["reportingdate"] = (
            row["reportingdate"].isoformat() if row.get("reportingdate") else None
        )
        # A report is viewable only once authorised (CONFIRM_REPORT=1). The link renders
        # the same PDF the clinic prints, for ALL categories (pathology, USG, radiology…).
        row["ready"] = str(row.get("confirm_report")).strip() in ("1", "True", "true")
        row["view_link"] = (
            build_view_link(
                row.get("patientname"), row["bill_key"],
                row.get("category_key"), row.get("report_key"), row.get("bill_no"),
            )
            if row["ready"] else None
        )
    return rows


def list_pending_payments(*, phone: str) -> list[dict[str, Any]]:
    bills = list_customer_bills(phone=phone, limit=100)
    return [b for b in bills if b.get("pending_amount", 0) > 0.01]


def get_prebook_calendar(months_ahead: int = 3) -> dict[str, Any]:
    dates = _upcoming_prebook_dates(months_ahead)
    counts = _slot_booking_counts(dates)
    slots = []
    for d in dates:
        day_slots = []
        for key, meta in TIME_SLOTS.items():
            booked = counts.get((d, key), 0)
            remaining = max(0, SLOT_CAPACITY - booked)
            day_slots.append(
                {
                    "time_slot": key,
                    "label": meta["label"],
                    "capacity": SLOT_CAPACITY,
                    "booked": booked,
                    "remaining": remaining,
                    "available": remaining > 0,
                }
            )
        slots.append({"date": d.isoformat(), "slots": day_slots})
    return {
        "prebook_days": list(PREBOOK_DAYS),
        "advance_fraction": PREBOOK_ADVANCE_FRACTION,
        "advance_non_refundable": True,
        "reschedule_phone": RESCHEDULE_PHONE,
        "dates": slots,
    }


def create_customer_prebooking(
    *,
    patient_name: str,
    phone: str,
    sex: str = "MALE",
    age_year: int | None = None,
    test_keys: list[int],
    slot_date: date,
    time_slot: str,
    payment_id: str,
    amount_paid: float,
    email: str | None = None,
    address: str | None = None,
    latitude: float | None = None,
    longitude: float | None = None,
) -> dict[str, Any]:
    phone_norm = _normalize_phone(phone)
    if len(phone_norm) < 10:
        raise ValueError("valid 10-digit phone required")
    if time_slot not in TIME_SLOTS:
        raise ValueError(f"time_slot must be one of {list(TIME_SLOTS)}")
    if not _valid_prebook_date(slot_date):
        raise ValueError("Prebooking only on 10th, 20th, or 30th of the month")

    remaining = SLOT_CAPACITY - _count_slot_bookings(slot_date, time_slot)
    if remaining <= 0:
        raise ValueError("Selected time slot is full")

    # Resolve the exact keys the app sent. This used to take the first 500 rows of
    # the catalog (of ~1,700) and silently drop any selected test outside them, so
    # the advance was computed on a partial total and the booking then failed —
    # after Razorpay had already taken the money.
    tests = _resolve_tests(test_keys)

    total = sum(t.rate for t in tests)
    required_advance = round(total * PREBOOK_ADVANCE_FRACTION, 2)
    if amount_paid + 0.01 < required_advance:
        raise ValueError(
            f"50% advance required (₹{required_advance:.2f}), received ₹{amount_paid:.2f}"
        )

    slot_label = TIME_SLOTS[time_slot]["label"]
    remarks = (
        f"CUSTOMER PREBOOK {slot_date.isoformat()} {time_slot} ({slot_label}) "
        f"| 50% NON-REFUNDABLE | Razorpay:{payment_id}"
    )
    if email:
        remarks += f" | email:{email}"
    # Home-collection address (optionally geotagged by the app's GPS autofill).
    if address:
        remarks += f" | addr:{address}"
        if latitude is not None and longitude is not None:
            remarks += f" ({latitude},{longitude})"
    elif latitude is not None and longitude is not None:
        remarks += f" | addr: ({latitude},{longitude})"

    settings = aktiv_settings()
    is_live = settings["allow_live_bookings"]
    if not is_live:
        raise ValueError("Live AKTIV booking is disabled on server")

    result = push_booking(
        patient_name=patient_name,
        phone=phone_norm,
        sex=sex,
        age_year=age_year,
        test_keys=test_keys,
        bill_date=slot_date,
        apnt_date=slot_date,
        amount_paid=amount_paid,
        receipt_mode="UPI",
        cheque_no=payment_id,
        remarks=remarks,
        test_mode=False,
    )

    _record_slot_booking(
        slot_date=slot_date,
        time_slot=time_slot,
        bill_key=result.bill_key,
        phone=phone_norm,
        patient_name=patient_name,
    )

    return {
        "success": True,
        "bill_key": result.bill_key,
        "bill_no": result.bill_no,
        "bill_number": result.bill_number,
        "alc_code": result.bill_no,
        "registration_no": result.registration_no,
        "apnt_key": result.apnt_key,
        "slot_date": slot_date.isoformat(),
        "time_slot": time_slot,
        "time_slot_label": slot_label,
        "total_amount": total,
        "advance_paid": amount_paid,
        "balance_due": round(total - amount_paid, 2),
        "advance_non_refundable": True,
        "reschedule_phone": RESCHEDULE_PHONE,
        "reschedule_note": (
            f"Appointment booked. To reschedule call {RESCHEDULE_PHONE} only."
        ),
    }


def record_pending_payment(
    *,
    bill_key: int,
    phone: str,
    amount_paid: float,
    payment_id: str,
) -> dict[str, Any]:
    phone_norm = _normalize_phone(phone)
    settings = aktiv_settings()
    user_key = settings["sys_user_key"]
    machine_key = settings["sys_machine_key"]
    now = datetime.now()

    with mssql_conn() as conn:
        cur = conn.cursor()
        cur.execute(
            f"""
            SELECT bill_key, netamount, receivedamount, phone
            FROM BILL_HEAD
            WHERE bill_key = %s AND {_phone_clause()}
            """,
            (bill_key, f"%{phone_norm}"),
        )
        row = cur.fetchone()
        if not row:
            raise ValueError("Bill not found for this phone")

        cols = [d[0].lower() for d in cur.description]
        bill = dict(zip(cols, row))
        pending = float(bill["netamount"] or 0) - float(bill["receivedamount"] or 0)
        if amount_paid > pending + 0.01:
            raise ValueError(f"Amount exceeds pending balance (₹{pending:.2f})")

        new_received = float(bill["receivedamount"] or 0) + amount_paid
        cur.execute(
            """
            UPDATE BILL_HEAD
            SET receivedamount = %s, rcptamount_bill = %s,
                sys_last_mod_date = %s, sys_mod_user_key = %s, sys_mod_machine_key = %s
            WHERE bill_key = %s
            """,
            (new_received, new_received, now, user_key, machine_key, bill_key),
        )
        cur.execute(
            """
            UPDATE APNT_HEAD
            SET receivedamount = %s, rcptamount_apnt = %s,
                sys_last_mod_date = %s, sys_mod_user_key = %s, sys_mod_machine_key = %s
            WHERE bill_key = %s
            """,
            (new_received, new_received, now, user_key, machine_key, bill_key),
        )

        receipt_key = _next_key(cur, "RECEIPT_HEAD", "receipt_key")
        cur.execute(
            """
            INSERT INTO RECEIPT_HEAD (
                receipt_key, receiptdate, bill_key, receiptamount,
                receiptmode, chequeno, formid,
                company_key, account_key, branch_key,
                sys_insert_date, sys_insert_user_key, sys_insert_machine_key,
                sys_last_mod_date, sys_mod_user_key, sys_mod_machine_key
            ) VALUES (
                %s, %s, %s, %s,
                3, %s, 300001,
                1, 10, 1,
                %s, %s, %s,
                %s, %s, %s
            )
            """,
            (
                receipt_key,
                date.today(),
                bill_key,
                amount_paid,
                payment_id,
                now,
                user_key,
                machine_key,
                now,
                user_key,
                machine_key,
            ),
        )
        conn.commit()

    return {
        "success": True,
        "bill_key": bill_key,
        "amount_paid": amount_paid,
        "payment_id": payment_id,
    }
