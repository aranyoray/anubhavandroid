"""REST API for Anubhav Life Care Android app ↔ AKTIV."""
from __future__ import annotations

import logging
from datetime import date
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from auth import authenticate
from aktiv_booking import (
    DEFAULT_COLL_CENTRE_KEY,
    cancel_booking,
    list_collection_centres,
    list_reception_users,
    next_bill_number,
    push_booking,
    search_doctors,
    search_tests,
)
from catalog import get_catalog
from config import aktiv_settings
from customer_portal import (
    create_customer_prebooking,
    get_customer_profile,
    get_prebook_calendar,
    list_customer_bills,
    list_customer_reports,
    list_pending_payments,
    record_pending_payment,
)
from collector_portal import (
    create_collector_patient,
    list_collector_patients,
    list_collector_reports,
)
from patient_match import customer_history, verify_customer

app = FastAPI(title="Anubhav Life Care API", version="2.0.0")
logger = logging.getLogger(__name__)


def internal_error(exc: Exception) -> HTTPException:
    logger.exception("Unhandled API error")
    return HTTPException(status_code=500, detail="Internal server error")


app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class BookingRequest(BaseModel):
    patient_name: str = Field(..., min_length=1)
    phone: str = Field(..., min_length=10)
    sex: str = "MALE"
    age_year: Optional[int] = None
    age_month: Optional[int] = None
    age_day: Optional[int] = None
    refrdoctor_key: Optional[int] = None
    collcentre_key: int = DEFAULT_COLL_CENTRE_KEY
    test_keys: list[int] = Field(..., min_length=1)
    bill_date: Optional[date] = None
    bill_number: Optional[str] = None
    amount_paid: Optional[float] = None
    receipt_mode: str = "CASH"
    cheque_no: Optional[str] = None
    remarks: Optional[str] = None
    test_mode: Optional[bool] = None
    sys_user_key: Optional[int] = None


class CancelRequest(BaseModel):
    sys_user_key: Optional[int] = None


class LoginRequest(BaseModel):
    userid: str = Field(..., min_length=1)
    password: str = Field(..., min_length=1)


@app.post("/api/auth/login")
def api_login(body: LoginRequest):
    try:
        user = authenticate(body.userid, body.password)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc

    return {
        "success": True,
        "user_key": user.user_key,
        "userid": user.userid,
        "username": user.username,
        "role": user.role,
        "collector_key": user.collector_key,
    }


@app.get("/health")
def health():
    settings = aktiv_settings()
    return {
        "status": "ok",
        "allow_live_bookings": settings["allow_live_bookings"],
        "test_bill_date": settings["test_bill_date"].isoformat(),
        "default_sys_user_key": settings["sys_user_key"],
    }


@app.get("/api/users")
def api_users():
    """Receptionist logins — bills are stamped with sys_insert_user_key."""
    try:
        return list_reception_users()
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/tests")
def api_tests(q: str = "", limit: int = 50):
    try:
        return search_tests(q, limit=min(limit, 100))
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/catalog")
def api_catalog(known_version: Optional[str] = None):
    """Whole catalog for the app's on-device cache.

    Pass the version the phone already holds as `known_version`; when it matches
    we skip the payload entirely so a routine check costs a few hundred bytes.
    """
    try:
        data = get_catalog()
    except Exception as exc:
        raise internal_error(exc) from exc

    if known_version and known_version == data["version"]:
        return {"version": data["version"], "count": data["count"], "unchanged": True, "tests": []}
    return {**data, "unchanged": False}


@app.get("/api/doctors")
def api_doctors(q: str = "", limit: int = 50):
    try:
        return search_doctors(q, limit=min(limit, 100))
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/collection-centres")
def api_collection_centres(q: str = ""):
    try:
        return list_collection_centres(q)
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/next-bill-number")
def api_next_bill_number(bill_date: Optional[date] = None, test_mode: Optional[bool] = None):
    try:
        settings = aktiv_settings()
        is_test = test_mode if test_mode is not None else not settings["allow_live_bookings"]
        effective_date = settings["test_bill_date"] if is_test else (bill_date or date.today())
        return next_bill_number(effective_date)
    except Exception as exc:
        raise internal_error(exc) from exc


@app.post("/api/bookings")
def api_create_booking(body: BookingRequest):
    try:
        result = push_booking(
            patient_name=body.patient_name,
            phone=body.phone,
            sex=body.sex,
            age_year=body.age_year,
            age_month=body.age_month,
            age_day=body.age_day,
            refrdoctor_key=body.refrdoctor_key,
            collcentre_key=body.collcentre_key,
            test_keys=body.test_keys,
            bill_date=body.bill_date,
            bill_number=body.bill_number,
            amount_paid=body.amount_paid,
            receipt_mode=body.receipt_mode,
            cheque_no=body.cheque_no,
            remarks=body.remarks,
            test_mode=body.test_mode,
            sys_user_key=body.sys_user_key,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc

    settings = aktiv_settings()
    is_test = body.test_mode if body.test_mode is not None else not settings["allow_live_bookings"]
    return {
        "success": True,
        "bill_key": result.bill_key,
        "bill_no": result.bill_no,
        "bill_number": result.bill_number,
        "registration_no": result.registration_no,
        "apnt_key": result.apnt_key,
        "net_amount": result.net_amount,
        "apnt_date": date.today().isoformat(),
        "test_mode": is_test,
    }


@app.post("/api/bookings/{bill_key}/cancel")
def api_cancel_booking(bill_key: int, body: CancelRequest = CancelRequest()):
    """
    Void receipt amounts only — never deletes bill rows (preserves ALC serials).
    """
    try:
        return cancel_booking(bill_key, sys_user_key=body.sys_user_key)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


# --- Customer portal ---


class CustomerPrebookRequest(BaseModel):
    patient_name: str = Field(..., min_length=1)
    phone: str = Field(..., min_length=10)
    sex: str = "MALE"
    age_year: Optional[int] = None
    test_keys: list[int] = Field(..., min_length=1)
    slot_date: date
    time_slot: str = Field(..., pattern="^(MORNING|AFTERNOON|EVENING)$")
    payment_id: str = Field(..., min_length=1)
    amount_paid: float = Field(..., gt=0)
    email: Optional[str] = None
    # Home-collection address (optionally geotagged by the app's GPS autofill).
    address: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None


class CustomerPaymentRequest(BaseModel):
    bill_key: int
    phone: str = Field(..., min_length=10)
    amount_paid: float = Field(..., gt=0)
    payment_id: str = Field(..., min_length=1)


class CollectorPatientRequest(BaseModel):
    collector_user_key: int = Field(..., gt=0)
    patient_name: str = Field(..., min_length=1)
    phone: str = Field(..., min_length=10)
    age_year: Optional[int] = None
    sex: Optional[str] = None
    referred_by: Optional[str] = None
    notes: Optional[str] = None
    followup_status: Optional[str] = None


class CustomerVerifyRequest(BaseModel):
    """Guest/patient login: 2 of 3 must match — name, (bill_no OR bill_date), phone."""
    name: str = ""
    phone: str = ""
    bill_no: str = ""
    bill_date: Optional[str] = None


@app.post("/api/customer/verify")
def api_customer_verify(body: CustomerVerifyRequest):
    provided = sum(bool(x) for x in (body.name.strip(), (body.bill_no.strip() or body.bill_date), body.phone.strip()))
    if provided < 2:
        raise HTTPException(status_code=400, detail="Provide at least two of: name, bill no/date, phone")
    try:
        return verify_customer(body.name, body.phone, body.bill_no, body.bill_date)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/history")
def api_customer_history(phone: str):
    """All visits under a phone since 2022 (static Neon mirror) — the My Reports list."""
    try:
        return customer_history(phone)
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/profile")
def api_customer_profile(phone: Optional[str] = None, email: Optional[str] = None):
    try:
        return get_customer_profile(phone=phone, email=email)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/bills")
def api_customer_bills(phone: str, limit: int = 50):
    try:
        return list_customer_bills(phone=phone, limit=limit)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/reports")
def api_customer_reports(phone: str, limit: int = 50):
    try:
        return list_customer_reports(phone=phone, limit=limit)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/collector/patients")
def api_collector_patients(collector_user_key: int, limit: int = 100):
    try:
        return list_collector_patients(
            collector_user_key=collector_user_key,
            limit=limit,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.post("/api/collector/patients")
def api_collector_create_patient(body: CollectorPatientRequest):
    try:
        return create_collector_patient(
            collector_user_key=body.collector_user_key,
            patient_name=body.patient_name,
            phone=body.phone,
            age_year=body.age_year,
            sex=body.sex,
            referred_by=body.referred_by,
            notes=body.notes,
            followup_status=body.followup_status,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/collector/reports")
def api_collector_reports(collector_user_key: int, limit: int = 100):
    try:
        return list_collector_reports(
            collector_user_key=collector_user_key,
            limit=limit,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/pending-payments")
def api_customer_pending(phone: str):
    try:
        return list_pending_payments(phone=phone)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/prebook/calendar")
def api_prebook_calendar(months_ahead: int = 3):
    try:
        return get_prebook_calendar(months_ahead=max(1, min(months_ahead, 6)))
    except Exception as exc:
        raise internal_error(exc) from exc


@app.post("/api/customer/prebook")
def api_customer_prebook(body: CustomerPrebookRequest):
    try:
        return create_customer_prebooking(
            patient_name=body.patient_name,
            phone=body.phone,
            sex=body.sex,
            age_year=body.age_year,
            test_keys=body.test_keys,
            slot_date=body.slot_date,
            time_slot=body.time_slot,
            payment_id=body.payment_id,
            amount_paid=body.amount_paid,
            email=body.email,
            address=body.address,
            latitude=body.latitude,
            longitude=body.longitude,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.post("/api/customer/payments")
def api_customer_payment(body: CustomerPaymentRequest):
    try:
        return record_pending_payment(
            bill_key=body.bill_key,
            phone=body.phone,
            amount_paid=body.amount_paid,
            payment_id=body.payment_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc
