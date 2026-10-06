"""Server-side collation of AKTIV report PDFs, with validation + pypdf merge.

The AKTIV print page (LabReportPrint.aspx) sometimes returns an HTML error page or
chokes on a particular report type, so its own multi-report collation can yield an
invalid/undisplayable PDF. Fetch each confirmed report individually and validate
every one before returning a complete PDF. Never silently cache a partial bill.
"""
from __future__ import annotations

import io
import urllib.request

from config import report_fetch_base
from db import fetch_all, mssql_conn
from patient_match import _norm_phone, build_view_link


class ReportUnavailable(RuntimeError):
    """Temporary renderer failure; the patient can retry without losing any reports."""


def _fetch_pdf(url: str, timeout: float = 60.0) -> bytes | None:
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/pdf"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
    except Exception:
        return None
    # A real PDF starts with %PDF- (tolerate a leading BOM/whitespace). An HTML
    # error page or login redirect will not, so we reject it instead of caching junk.
    if not data or data.lstrip()[:5] != b"%PDF-":
        return None
    return data


def collated_report_pdf(*, bill_key: int, phone: str | None = None) -> bytes:
    with mssql_conn() as conn, conn.cursor() as cur:
        head = fetch_all(
            cur,
            "SELECT TOP 1 RTRIM(ISNULL(PATIENTNAME,'')) AS patientname, "
            "RTRIM(ISNULL(PHONE,'')) AS phone, RTRIM(ISNULL(BILL_NO,'')) AS bill_no "
            "FROM BILL_HEAD WHERE BILL_KEY = %s",
            (bill_key,),
        )
        if not head:
            raise ValueError("bill not found")
        h = head[0]
        if phone and _norm_phone(h["phone"]) != _norm_phone(phone):
            raise PermissionError("bill does not belong to this phone")
        items = fetch_all(
            cur,
            "SELECT DISTINCT category_key, report_key FROM BILL_TEST_DTLS "
            "WHERE bill_key = %s AND ISNULL(report_key,0) > 0 "
            "AND CONVERT(varchar(4), confirm_report) = '1' ORDER BY category_key, report_key",
            (bill_key,),
        )
    if not items:
        raise ValueError("no authorised reports for this bill")

    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter()
    for it in items:
        url = build_view_link(
            h["patientname"], bill_key, it["category_key"], it["report_key"], h["bill_no"],
            base=report_fetch_base(),
        )
        data = _fetch_pdf(url)
        if not data:
            raise ReportUnavailable("A report could not be rendered. Please try again later.")
        try:
            reader = PdfReader(io.BytesIO(data))
            if not reader.pages:
                raise ValueError("empty report")
            for page in reader.pages:
                writer.add_page(page)
        except Exception as exc:
            raise ReportUnavailable("A report could not be rendered. Please try again later.") from exc

    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()
