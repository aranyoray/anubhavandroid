"""Push patient bookings into AKTIV MSSQL (BILL_HEAD + details + APNT_HEAD)."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

from db import fetch_all, mssql_conn, neon_conn
from config import aktiv_settings

DEFAULT_COLL_CENTRE_KEY = 1  # ANUBHAV LIFE CARE
DEFAULT_COLL_CENTRE_NAME = "ANUBHAV LIFE CARE"
BILL_PREFIX2 = "ALC"
COMPANY_KEY = 1
BRANCH_KEY = 1
ACCOUNT_KEY = 10
RECEIPT_MODE = {"CASH": 1, "UPI": 3, "CARD": 6}
SEX = {"MALE": 1, "FEMALE": 2, "OTHER": -1}


@dataclass
class TestLine:
    test_key: int
    testcode: str
    testname: str
    rate: float
    category_key: int | None


@dataclass
class BookingResult:
    bill_key: int
    bill_no: str
    bill_number: str
    regt_key: int
    registration_no: str
    apnt_key: int
    net_amount: float


def list_reception_users() -> list[dict[str, Any]]:
    """
    Receptionist accounts from SYS_MAST_USERS.
    Desktop AKTIV login sets sys_insert_user_key on each bill to the logged-in user_key.
    """
    with mssql_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT user_key, userid, username
            FROM SYS_MAST_USERS
            WHERE (flg_system IS NULL OR flg_system = 0)
              AND (userid IS NOT NULL OR username IS NOT NULL)
            ORDER BY COALESCE(userid, username)
            """
        )
        cols = [d[0].lower() for d in cur.description]
        return [dict(zip(cols, row)) for row in cur.fetchall()]


def _resolve_booking_context(
    *,
    bill_date: date | None,
    apnt_date: date | None = None,
    test_mode: bool | None,
    sys_user_key: int | None,
) -> tuple[date, date, int, int, bool, float]:
    """Return bill_date, apnt_date, sys_user_key, sys_machine_key, is_test, deduction."""
    settings = aktiv_settings()
    is_test = test_mode if test_mode is not None else not settings["allow_live_bookings"]

    if is_test:
        bill_date = settings["test_bill_date"]
    else:
        bill_date = bill_date or date.today()

    apnt_date = apnt_date or bill_date
    user_key = sys_user_key or settings["sys_user_key"]
    machine_key = settings["sys_machine_key"]
    return bill_date, apnt_date, user_key, machine_key, is_test, 0.0


def cancel_booking(bill_key: int, *, sys_user_key: int | None = None) -> dict[str, Any]:
    """
    Void a booking without deleting AKTIV rows (preserves ALC serial numbering).
    Only receipt amounts are zeroed — bill/apnt rows stay intact.
    """
    settings = aktiv_settings()
    user_key = sys_user_key or settings["sys_user_key"]
    machine_key = settings["sys_machine_key"]
    now = datetime.now()

    with mssql_conn() as conn:
        cur = conn.cursor()
        cur.execute("SELECT bill_key, bill_no FROM BILL_HEAD WHERE bill_key = %s", (bill_key,))
        row = cur.fetchone()
        if not row:
            raise ValueError(f"bill_key {bill_key} not found")

        cur.execute(
            """
            UPDATE BILL_HEAD
            SET rcptamount_bill = 0,
                receivedamount = 0,
                sys_last_mod_date = %s,
                sys_mod_user_key = %s,
                sys_mod_machine_key = %s
            WHERE bill_key = %s
            """,
            (now, user_key, machine_key, bill_key),
        )
        cur.execute(
            """
            UPDATE APNT_HEAD
            SET rcptamount_apnt = 0,
                receivedamount = 0,
                sys_last_mod_date = %s,
                sys_mod_user_key = %s,
                sys_mod_machine_key = %s
            WHERE bill_key = %s
            """,
            (now, user_key, machine_key, bill_key),
        )
        cur.execute(
            """
            UPDATE RECEIPT_HEAD
            SET receiptamount = 0,
                amountpaid = 0,
                sys_last_mod_date = %s,
                sys_mod_user_key = %s,
                sys_mod_machine_key = %s
            WHERE bill_key = %s
            """,
            (now, user_key, machine_key, bill_key),
        )
        conn.commit()

    return {"success": True, "bill_key": bill_key, "bill_no": str(row[1])}


def _bill_prefix1(d: date) -> str:
    return f"{d.year:04d}/{d.month:02d}"


def _decimal_time(now: datetime) -> float:
    return round(now.hour + now.minute / 60.0, 2)


def _year_prefix(d: date) -> str:
    return f"{d.year % 100:02d}"


def _next_bill_number_on(cur, bill_date: date) -> dict[str, str]:
    """Next sequential ALC bill number for the month, read from the live AKTIV database.

    Serials reset to 001 on the 1st. The front desk writes bills all day, so the
    Neon mirror (refreshed by the ETL in batches) is stale for numbering: reading
    it here handed out serials the desktop had already used since the last sync.
    The UPDLOCK/HOLDLOCK hints make two concurrent bookings queue on the same
    month instead of both computing the same "next" number.
    """
    prefix1 = _bill_prefix1(bill_date)
    cur.execute(
        """
        SELECT ISNULL(MAX(CAST(bill_number AS INT)), 0)
        FROM BILL_HEAD WITH (UPDLOCK, HOLDLOCK)
        WHERE bill_prefix1 = %s AND bill_prefix2 = %s
          AND LTRIM(RTRIM(ISNULL(bill_number, ''))) <> ''
          AND bill_number NOT LIKE '%[^0-9]%'
        """,
        (prefix1, BILL_PREFIX2),
    )
    row = cur.fetchone()
    next_no = int((row[0] if row else 0) or 0) + 1
    bill_number = f"{next_no:03d}"
    return {
        "bill_prefix1": prefix1,
        "bill_prefix2": BILL_PREFIX2,
        "bill_number": bill_number,
        "bill_no": f"{prefix1}/{BILL_PREFIX2}/{bill_number}",
    }


def next_bill_number(bill_date: date | None = None) -> dict[str, str]:
    """Preview of the next bill number for the month (see `_next_bill_number_on`)."""
    bill_date = bill_date or date.today()
    with mssql_conn() as conn, conn.cursor() as cur:
        return _next_bill_number_on(cur, bill_date)


def search_tests(query: str = "", limit: int = 50) -> list[dict[str, Any]]:
    pattern = f"%{query.strip()}%" if query.strip() else "%"
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            """
            SELECT DISTINCT ON (mt.test_key)
                mt.test_key,
                mt.testcode,
                mt.testname,
                COALESCE(bd.rate, 0) AS rate,
                mt.category_key,
                mtc.category AS category_name
            FROM mast_test mt
            LEFT JOIN LATERAL (
                SELECT rate
                FROM bill_dtls
                WHERE test_key = mt.test_key
                ORDER BY bill_key DESC
                LIMIT 1
            ) bd ON TRUE
            LEFT JOIN mast_test_category mtc ON mtc.category_key = mt.category_key
            WHERE (mt.inactive IS NULL OR mt.inactive = 0)
              AND (mt.testname ILIKE %s OR mt.testcode ILIKE %s)
            ORDER BY mt.test_key, mt.testname
            LIMIT %s
            """,
            (pattern, pattern, limit),
        )


def search_doctors(query: str = "", limit: int = 50) -> list[dict[str, Any]]:
    pattern = f"%{query.strip()}%" if query.strip() else "%"
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            """
            SELECT
                refrdoctor_key,
                doctorcode,
                doctorname,
                qualification,
                qualification2,
                qualification3,
                phone
            FROM mast_refrdoctor
            WHERE (inactive IS NULL OR inactive = 0)
              AND doctorname ILIKE %s
            ORDER BY doctorname
            LIMIT %s
            """,
            (pattern, limit),
        )


def list_collection_centres(query: str = "") -> list[dict[str, Any]]:
    pattern = f"%{query.strip()}%" if query.strip() else "%"
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            """
            SELECT collcentre_key, collcentrecode, collcentrename, coll_initial
            FROM mast_collcentre
            WHERE (inactive IS NULL OR inactive = 0)
              AND collcentrename ILIKE %s
            ORDER BY
                CASE WHEN collcentrename = %s THEN 0 ELSE 1 END,
                collcentrename
            """,
            (pattern, DEFAULT_COLL_CENTRE_NAME),
        )


def _resolve_tests(test_keys: list[int]) -> list[TestLine]:
    if not test_keys:
        raise ValueError("At least one test is required")
    with neon_conn() as conn, conn.cursor() as cur:
        rows = fetch_all(
            cur,
            """
            SELECT DISTINCT ON (mt.test_key)
                mt.test_key,
                mt.testcode,
                mt.testname,
                COALESCE(bd.rate, 0) AS rate,
                mt.category_key
            FROM mast_test mt
            LEFT JOIN LATERAL (
                SELECT rate FROM bill_dtls
                WHERE test_key = mt.test_key
                ORDER BY bill_key DESC LIMIT 1
            ) bd ON TRUE
            WHERE mt.test_key = ANY(%s)
            ORDER BY mt.test_key
            """,
            (test_keys,),
        )
    if len(rows) != len(set(test_keys)):
        found = {r["test_key"] for r in rows}
        missing = [k for k in test_keys if k not in found]
        raise ValueError(f"Unknown test_key(s): {missing}")
    return [
        TestLine(
            test_key=r["test_key"],
            testcode=r["testcode"] or "",
            testname=r["testname"] or "",
            rate=float(r["rate"] or 0),
            category_key=r["category_key"],
        )
        for r in rows
    ]


def _next_key(cur, table: str, column: str) -> int:
    """MAX+1 key allocation, locked so two bookings in flight cannot pick the same key."""
    cur.execute(f"SELECT ISNULL(MAX({column}), 0) + 1 FROM {table} WITH (UPDLOCK, HOLDLOCK)")
    row = cur.fetchone()
    return int(row[0])


def _find_or_create_patient(
    cur,
    *,
    patient_name: str,
    phone: str,
    sex: int,
    age_year: int | None,
    age_month: int | None,
    age_day: int | None,
    refrdoctor_key: int | None,
    collcentre_key: int,
    bill_date: date,
    sys_user_key: int,
    sys_machine_key: int,
) -> tuple[int, str]:
    cur.execute(
        """
        SELECT TOP 1 regt_key, regt_no
        FROM MAST_PATIENT
        WHERE phone = %s AND patientname = %s
        ORDER BY regt_key DESC
        """,
        (phone, patient_name),
    )
    existing = cur.fetchone()
    if existing:
        return int(existing[0]), str(existing[1] or "")

    regt_key = _next_key(cur, "MAST_PATIENT", "regt_key")
    year_prefix = _year_prefix(bill_date)
    cur.execute(
        """
        SELECT ISNULL(MAX(CAST(regt_number AS INT)), 0) + 1
        FROM MAST_PATIENT
        WHERE regt_prefix = %s AND regt_number NOT LIKE '%[^0-9]%'
        """,
        (year_prefix,),
    )
    regt_number = str(int(cur.fetchone()[0]))
    regt_no = f"{year_prefix}/{regt_number}"
    now = datetime.now()

    cur.execute(
        """
        INSERT INTO MAST_PATIENT (
            regt_key, regt_prefix, regt_number, regt_no, regtdate,
            patientname, phone, sex, ageyear, agemonth, ageday, agedate,
            refrdoctor_key, collcentre_key,
            company_key, account_key, branch_key,
            sys_insert_date, sys_insert_user_key, sys_insert_machine_key,
            sys_last_mod_date, sys_mod_user_key, sys_mod_machine_key
        ) VALUES (
            %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s, %s, %s,
            %s, %s,
            %s, %s, %s,
            %s, %s, %s,
            %s, %s, %s
        )
        """,
        (
            regt_key, year_prefix, regt_number, regt_no, bill_date,
            patient_name, phone, sex, age_year, age_month, age_day, bill_date,
            refrdoctor_key, collcentre_key,
            COMPANY_KEY, ACCOUNT_KEY, BRANCH_KEY,
            now, sys_user_key, sys_machine_key,
            now, sys_user_key, sys_machine_key,
        ),
    )
    return regt_key, regt_no


def push_booking(
    *,
    patient_name: str,
    phone: str,
    sex: str = "MALE",
    age_year: int | None = None,
    age_month: int | None = None,
    age_day: int | None = None,
    refrdoctor_key: int | None = None,
    collcentre_key: int = DEFAULT_COLL_CENTRE_KEY,
    test_keys: list[int],
    bill_date: date | None = None,
    apnt_date: date | None = None,
    bill_number: str | None = None,
    amount_paid: float | None = None,
    receipt_mode: str = "CASH",
    cheque_no: str | None = None,
    remarks: str | None = None,
    test_mode: bool | None = None,
    sys_user_key: int | None = None,
) -> BookingResult:
    """
    Create a bill in AKTIV matching the desktop Bill screen.
    Test mode uses AKTIV_TEST_BILL_DATE with full deduction (net/receipt = 0).
    IPD/bed fields are intentionally left blank — not used at this clinic.
    Cheque/ref no is stored only for UPI receipts.
    """
    bill_date, apnt_date, user_key, machine_key, is_test, _ = _resolve_booking_context(
        bill_date=bill_date,
        apnt_date=apnt_date,
        test_mode=test_mode,
        sys_user_key=sys_user_key,
    )
    now = datetime.now()
    bill_time = _decimal_time(now)
    sex_code = SEX.get(sex.upper(), SEX["MALE"])
    receipt_mode_code = RECEIPT_MODE.get(receipt_mode.upper(), RECEIPT_MODE["CASH"])

    tests = _resolve_tests(test_keys)
    bill_amount = sum(t.rate for t in tests)
    if is_test:
        deduction = -bill_amount
        net_amount = 0.0
        paid = 0.0
        bill_remarks = (remarks or "").strip()
        if "TEST BOOKING" not in bill_remarks.upper():
            bill_remarks = f"TEST BOOKING {bill_remarks}".strip()
        remarks = bill_remarks or "TEST BOOKING"
    else:
        deduction = 0.0
        net_amount = bill_amount
        paid = float(amount_paid if amount_paid is not None else net_amount)

    # UPI only for cheque/ref number
    if receipt_mode.upper() != "UPI":
        cheque_no = None

    with mssql_conn() as conn:
        cur = conn.cursor()

        # Allocated inside the transaction, from the live table, so the serial reflects
        # every bill the desktop has written up to this moment.
        bill_info = _next_bill_number_on(cur, bill_date)
        if bill_number:
            bill_info["bill_number"] = bill_number.zfill(3)
            bill_info["bill_no"] = (
                f"{bill_info['bill_prefix1']}/{bill_info['bill_prefix2']}/{bill_info['bill_number']}"
            )

        regt_key, regt_no = _find_or_create_patient(
            cur,
            patient_name=patient_name.strip().upper(),
            phone=phone.strip(),
            sex=sex_code,
            age_year=age_year,
            age_month=age_month,
            age_day=age_day,
            refrdoctor_key=refrdoctor_key,
            collcentre_key=collcentre_key,
            bill_date=bill_date,
            sys_user_key=user_key,
            sys_machine_key=machine_key,
        )

        bill_key = _next_key(cur, "BILL_HEAD", "bill_key")
        name_upper = patient_name.strip().upper()
        cur.execute(
            """
            INSERT INTO BILL_HEAD (
                bill_key, bill_prefix1, bill_prefix2, bill_number, bill_no,
                billdate, billtime,
                regt_key, registration_no, firstname, patientname, phone,
                sex, ageyear, agemonth, ageday,
                refrdoctor_key, collcentre_key, remarks,
                billamount, deduction, netamount, rcptamount_bill, receivedamount,
                company_key, branch_key, account_key,
                sys_insert_date, sys_insert_user_key, sys_insert_machine_key,
                sys_last_mod_date, sys_mod_user_key, sys_mod_machine_key
            ) VALUES (
                %s, %s, %s, %s, %s,
                %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s
            )
            """,
            (
                bill_key,
                bill_info["bill_prefix1"],
                bill_info["bill_prefix2"],
                bill_info["bill_number"],
                bill_info["bill_no"],
                bill_date,
                bill_time,
                regt_key,
                regt_no,
                name_upper,
                name_upper,
                phone.strip(),
                sex_code,
                age_year,
                age_month,
                age_day,
                refrdoctor_key,
                collcentre_key,
                remarks,
                bill_amount,
                deduction,
                net_amount,
                paid,
                paid,
                COMPANY_KEY,
                BRANCH_KEY,
                ACCOUNT_KEY,
                now,
                user_key,
                machine_key,
                now,
                user_key,
                machine_key,
            ),
        )

        for test in tests:
            bill_dtls_key = _next_key(cur, "BILL_DTLS", "bill_dtls_key")
            bill_test_dtls_key = _next_key(cur, "BILL_TEST_DTLS", "bill_test_dtls_key")

            cur.execute(
                """
                INSERT INTO BILL_DTLS (
                    bill_key, bill_dtls_key, test_key, rate, testno, charge,
                    reportingdate
                ) VALUES (%s, %s, %s, %s, 1, %s, %s)
                """,
                (
                    bill_key,
                    bill_dtls_key,
                    test.test_key,
                    test.rate,
                    test.rate,
                    bill_date,
                ),
            )

            cur.execute(
                """
                INSERT INTO BILL_TEST_DTLS (
                    bill_key, bill_test_dtls_key, billdate, test_key,
                    rate, testno, charge, reportingdate, category_key
                ) VALUES (%s, %s, %s, %s, %s, 1, %s, %s, %s)
                """,
                (
                    bill_key,
                    bill_test_dtls_key,
                    bill_date,
                    test.test_key,
                    test.rate,
                    test.rate,
                    bill_date,
                    test.category_key,
                ),
            )

        apnt_key = _next_key(cur, "APNT_HEAD", "apnt_key")
        apnt_prefix = f"{bill_info['bill_prefix1'].replace('/', '')}/{BILL_PREFIX2}"
        apnt_number = bill_info["bill_number"]
        apnt_no = f"{apnt_prefix}/{apnt_number}"

        cur.execute(
            """
            INSERT INTO APNT_HEAD (
                apnt_key, apnt_prefix, apnt_number, apnt_no,
                bookingdate, bookingtime, apntdate,
                bill_key, regt_key, firstname, patientname, phone,
                sex, ageyear, agemonth, ageday,
                refrdoctor_key, collcentre_key,
                billamount, netamount, rcptamount_apnt, receivedamount,
                company_key, branch_key, account_key,
                sys_insert_date, sys_insert_user_key, sys_insert_machine_key,
                sys_last_mod_date, sys_mod_user_key, sys_mod_machine_key
            ) VALUES (
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s
            )
            """,
            (
                apnt_key,
                apnt_prefix,
                apnt_number,
                apnt_no,
                bill_date,
                bill_time,
                apnt_date,
                bill_key,
                regt_key,
                name_upper,
                name_upper,
                phone.strip(),
                sex_code,
                age_year,
                age_month,
                age_day,
                refrdoctor_key,
                collcentre_key,
                bill_amount,
                net_amount,
                paid,
                paid,
                COMPANY_KEY,
                BRANCH_KEY,
                ACCOUNT_KEY,
                now,
                user_key,
                machine_key,
                now,
                user_key,
                machine_key,
            ),
        )

        if paid > 0:
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
                    %s, %s, 300001,
                    %s, %s, %s,
                    %s, %s, %s,
                    %s, %s, %s
                )
                """,
                (
                    receipt_key,
                    bill_date,
                    bill_key,
                    paid,
                    receipt_mode_code,
                    cheque_no,
                    COMPANY_KEY,
                    ACCOUNT_KEY,
                    BRANCH_KEY,
                    now,
                    user_key,
                    machine_key,
                    now,
                    user_key,
                    machine_key,
                ),
            )

        conn.commit()

    return BookingResult(
        bill_key=bill_key,
        bill_no=bill_info["bill_no"],
        bill_number=bill_info["bill_number"],
        regt_key=regt_key,
        registration_no=regt_no,
        apnt_key=apnt_key,
        net_amount=net_amount,
    )
