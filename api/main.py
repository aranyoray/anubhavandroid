"""REST API for Anubhav Life Care Android app ↔ AKTIV."""
from __future__ import annotations

import hmac
import logging
from datetime import date, timedelta
from typing import Any, Optional

from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
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
from admin_reports import REPORTS, build_report
from catalog import get_catalog
from config import aktiv_settings, api_key
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
from patient_match import _norm_phone, customer_history, verify_customer
from report_pdf import collated_report_pdf
from report_values import report_values
from staff_portal import bill_detail, edit_bill, search_bills
import analytics
import roles
import tokens

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

# App builds up to #6 sent the key as X-ALC-Key while this server read X-API-Key, so
# every request from them was a 401. Accept both names.
API_KEY_HEADERS = ("x-api-key", "x-alc-key")
_API_KEY_WARNED = False


@app.middleware("http")
async def enforce_api_key(request: Request, call_next):
    """
    Require the shared secret on /api/* when AKTIV_API_KEY is set.

    This service is published to the internet through the cloudflared tunnel. /health
    stays open so uptime checks keep working. Without a key configured the API stays
    open but logs a warning once, making an unprotected deployment visible in api.log.
    """
    path = request.url.path
    if path.startswith("/api/") and request.method != "OPTIONS":
        expected = api_key()
        if expected:
            provided = next((request.headers.get(h, "") for h in API_KEY_HEADERS if request.headers.get(h)), "")
            # Compare as bytes: hmac.compare_digest rejects non-ASCII str inputs,
            # so a crafted header must not be able to raise instead of 401.
            if not hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8")):
                return JSONResponse(status_code=401, content={"detail": "Invalid or missing API key"})
        else:
            global _API_KEY_WARNED
            if not _API_KEY_WARNED:
                _API_KEY_WARNED = True
                logger.warning(
                    "AKTIV_API_KEY is not set — /api endpoints are unauthenticated. "
                    "Set AKTIV_API_KEY (and rebuild the app with a matching key) to secure them."
                )
    return await call_next(request)


# ---------------------------------------------------------------- tokens
PATIENT_TOKEN = Header("", alias="X-Patient-Token")
STAFF_TOKEN = Header("", alias="X-Staff-Token")


def require_patient(token: str, phone: str) -> str:
    """The phone this request may read, or 401/403.

    /api/customer/verify is where the 2-of-3 check happens; it hands back a token
    scoped to the matched phone. Without this check the verify step was decoration:
    any caller holding the (APK-embedded) API key could list anyone's reports by phone.
    """
    ph = _norm_phone(phone)
    phones = tokens.patient_phones(token)
    if not phones:
        raise HTTPException(status_code=401, detail="Verify your details again to see reports")
    if ph not in phones:
        raise HTTPException(status_code=403, detail="These reports belong to a different number")
    return ph


def require_staff(token: str, capability: Optional[str] = None) -> tuple[int, dict]:
    """(user_key, AKTIV permissions) for a signed-in staff member.

    Permissions are reloaded from AKTIV on every call, so a role taken away on the
    desktop applies at once, not when the 12-hour token runs out.
    """
    user_key = tokens.staff_user_key(token)
    if not user_key:
        raise HTTPException(status_code=401, detail="Please sign in again")
    try:
        perms = roles.load_user_permissions(user_key)
    except Exception as exc:
        raise internal_error(exc) from exc
    if capability and not perms.get(capability):
        raise HTTPException(status_code=403, detail="Your AKTIV role does not allow this")
    return user_key, perms


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
    apnt_date: Optional[date] = None
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
        "permissions": user.permissions,
        # Sent back as X-Staff-Token on staff endpoints (bookings, bill edits, reports).
        "token": tokens.staff_token(user.user_key),
    }


@app.get("/api/staff/me")
def api_staff_me(x_staff_token: str = STAFF_TOKEN):
    """Current AKTIV permissions for the signed-in staff member (re-read from AKTIV)."""
    user_key, perms = require_staff(x_staff_token)
    return {"user_key": user_key, "permissions": perms}


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
def api_create_booking(body: BookingRequest, x_staff_token: str = STAFF_TOKEN):
    """Create a bill. With a staff token (the in-app Admin screen) the AKTIV role must
    allow booking and the bill is stamped to that user, whatever the body says. Calls
    without one keep the old behaviour for the separate AKTIVadminandroid app."""
    sys_user_key = body.sys_user_key
    if x_staff_token:
        sys_user_key, _ = require_staff(x_staff_token, "can_book")
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
            apnt_date=body.apnt_date,
            bill_number=body.bill_number,
            amount_paid=body.amount_paid,
            receipt_mode=body.receipt_mode,
            cheque_no=body.cheque_no,
            remarks=body.remarks,
            test_mode=body.test_mode,
            sys_user_key=sys_user_key,
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
        "apnt_date": (body.apnt_date or body.bill_date or date.today()).isoformat(),
        "test_mode": is_test,
    }


@app.post("/api/bookings/{bill_key}/cancel")
def api_cancel_booking(
    bill_key: int,
    body: CancelRequest = CancelRequest(),
    x_staff_token: str = STAFF_TOKEN,
):
    """
    Void receipt amounts only — never deletes bill rows (preserves ALC serials).
    Requires a user whose AKTIV role allows cancelling (BILLCHANGE / OPD CANCEL / ADMIN).
    """
    if x_staff_token:
        user_key, _ = require_staff(x_staff_token, "can_cancel_booking")
    else:
        try:
            roles.require(body.sys_user_key, "can_cancel_booking")
        except PermissionError as exc:
            raise HTTPException(status_code=403, detail=str(exc)) from exc
        user_key = body.sys_user_key
    try:
        return cancel_booking(bill_key, sys_user_key=user_key)
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
        result = verify_customer(body.name, body.phone, body.bill_no, body.bill_date)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc
    if result.get("matched") and len(result.get("phone") or "") == 10:
        result["token"] = tokens.patient_token(result["phone"])
    return result


@app.get("/api/customer/history")
def api_customer_history(phone: str, x_patient_token: str = PATIENT_TOKEN):
    """All visits under a phone since 2022 — Neon mirror plus a live overlay for recent bills."""
    ph = require_patient(x_patient_token, phone)
    try:
        return customer_history(ph)
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/report-values")
def api_customer_report_values(bill_key: int, phone: str, x_patient_token: str = PATIENT_TOKEN):
    """Structured per-parameter results for a bill (live MSSQL) — the visualizer."""
    ph = require_patient(x_patient_token, phone)
    try:
        return report_values(bill_key=bill_key, phone=ph)
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/report-pdf")
def api_customer_report_pdf(bill_key: int, phone: str, x_patient_token: str = PATIENT_TOKEN):
    """Validated, pypdf-merged single PDF of a bill's authorised reports."""
    ph = require_patient(x_patient_token, phone)
    try:
        data = collated_report_pdf(bill_key=bill_key, phone=ph)
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc
    return Response(
        content=data,
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="report_{bill_key}.pdf"'},
    )


@app.get("/api/customer/profile")
def api_customer_profile(phone: Optional[str] = None, email: Optional[str] = None):
    try:
        return get_customer_profile(phone=phone, email=email)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/bills")
def api_customer_bills(phone: str, limit: int = 50, x_patient_token: str = PATIENT_TOKEN):
    phone = require_patient(x_patient_token, phone)
    try:
        return list_customer_bills(phone=phone, limit=limit)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/customer/reports")
def api_customer_reports(phone: str, limit: int = 50, x_patient_token: str = PATIENT_TOKEN):
    phone = require_patient(x_patient_token, phone)
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
def api_customer_pending(phone: str, x_patient_token: str = PATIENT_TOKEN):
    phone = require_patient(x_patient_token, phone)
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


# --- Admin: AKTIV staff sign-in (X-Staff-Token) ---

# Which AKTIV capability each booking-details report needs. Test counts are what the
# front desk sees all day; money figures follow AKTIV's account-view rights.
REPORT_CAPABILITY = {"tests": None, "income": "can_view_sales", "cc": "can_view_sales", "due": "can_view_sales"}


def _clamp_to_account_window(start: Optional[str], perms: dict) -> Optional[str]:
    """AKTIV's ACCOUNTVIEW_DAY: a non-admin sees money figures only that far back."""
    days = int(perms.get("accountview_days") or 0)
    if perms.get("is_admin") or days <= 0:
        return start
    floor = date.today() - timedelta(days=days)
    try:
        asked = date.fromisoformat(start) if start else None
    except ValueError:
        asked = None
    return (asked if asked and asked > floor else floor).isoformat()


@app.get("/api/admin/report")
def api_admin_report(
    report: str = "income",
    start: Optional[str] = None,
    end: Optional[str] = None,
    bill_details: bool = True,
    test_details: bool = True,
    x_staff_token: str = STAFF_TOKEN,
):
    """Booking-details report (tests | income | cc | due) over a date range."""
    if report not in REPORTS:
        raise HTTPException(status_code=400, detail=f"report must be one of {', '.join(REPORTS)}")
    _, perms = require_staff(x_staff_token, REPORT_CAPABILITY[report])
    if REPORT_CAPABILITY[report]:
        start = _clamp_to_account_window(start, perms)
    try:
        return build_report(report, start, end, bill_details, test_details)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/staff/bills")
def api_staff_bills(q: str, x_staff_token: str = STAFF_TOKEN):
    """Recent bills by bill-number tail, phone or name — any signed-in staff member."""
    require_staff(x_staff_token)
    try:
        return search_bills(q)
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/staff/bills/{bill_key}")
def api_staff_bill(bill_key: int, x_staff_token: str = STAFF_TOKEN):
    require_staff(x_staff_token)
    try:
        return bill_detail(bill_key)
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/staff/bills/{bill_key}/pdf")
def api_staff_bill_pdf(bill_key: int, x_staff_token: str = STAFF_TOKEN):
    """The bill's authorised reports as one PDF, for any signed-in staff member."""
    require_staff(x_staff_token)
    try:
        data = collated_report_pdf(bill_key=bill_key)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc
    return Response(
        content=data,
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="report_{bill_key}.pdf"'},
    )


class BillEditRequest(BaseModel):
    patient_name: Optional[str] = None
    phone: Optional[str] = None
    sex: Optional[str] = None
    age_year: Optional[int] = Field(None, ge=0, le=130)
    age_month: Optional[int] = Field(None, ge=0, le=11)
    age_day: Optional[int] = Field(None, ge=0, le=31)
    refrdoctor_key: Optional[int] = None
    remarks: Optional[str] = None


@app.patch("/api/staff/bills/{bill_key}")
def api_staff_edit_bill(bill_key: int, body: BillEditRequest, x_staff_token: str = STAFF_TOKEN):
    """Change a bill's patient particulars — AKTIV BILLCHANGE rights, within MODIFICATION_DAY."""
    user_key, perms = require_staff(x_staff_token, "can_edit_booking")
    try:
        return edit_bill(bill_key, body.model_dump(exclude_none=True), user_key=user_key, perms=perms)
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise internal_error(exc) from exc


# --- Sales analytics (AKTIVadminandroid; gated by can_view_sales) ---


def _require_sales(user_key: Optional[int], token: str = "") -> None:
    if token:
        require_staff(token, "can_view_sales")
        return
    try:
        roles.require(user_key, "can_view_sales")
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc


def _analytics(fn, user_key, token, *args) -> Any:
    _require_sales(user_key, token)
    try:
        return fn(*args)
    except Exception as exc:
        raise internal_error(exc) from exc


@app.get("/api/analytics/summary")
def api_an_summary(user_key: Optional[int] = None, date_from: Optional[str] = None,
                   date_to: Optional[str] = None, x_staff_token: str = STAFF_TOKEN):
    return _analytics(analytics.summary, user_key, x_staff_token, date_from, date_to)


@app.get("/api/analytics/by-day")
def api_an_by_day(user_key: Optional[int] = None, date_from: Optional[str] = None,
                  date_to: Optional[str] = None, x_staff_token: str = STAFF_TOKEN):
    return _analytics(analytics.by_day, user_key, x_staff_token, date_from, date_to)


@app.get("/api/analytics/by-category")
def api_an_by_category(user_key: Optional[int] = None, date_from: Optional[str] = None,
                       date_to: Optional[str] = None, x_staff_token: str = STAFF_TOKEN):
    return _analytics(analytics.by_category, user_key, x_staff_token, date_from, date_to)


@app.get("/api/analytics/by-doctor")
def api_an_by_doctor(user_key: Optional[int] = None, date_from: Optional[str] = None,
                     date_to: Optional[str] = None, x_staff_token: str = STAFF_TOKEN):
    return _analytics(analytics.by_doctor, user_key, x_staff_token, date_from, date_to)


@app.get("/api/analytics/by-centre")
def api_an_by_centre(user_key: Optional[int] = None, date_from: Optional[str] = None,
                     date_to: Optional[str] = None, x_staff_token: str = STAFF_TOKEN):
    return _analytics(analytics.by_centre, user_key, x_staff_token, date_from, date_to)


@app.get("/api/analytics/yoy")
def api_an_yoy(user_key: Optional[int] = None, x_staff_token: str = STAFF_TOKEN):
    return _analytics(analytics.yoy, user_key, x_staff_token)


@app.post("/api/customer/payments")
def api_customer_payment(body: CustomerPaymentRequest, x_patient_token: str = PATIENT_TOKEN):
    require_patient(x_patient_token, body.phone)
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
