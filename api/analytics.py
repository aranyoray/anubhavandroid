"""Sales analytics for the app dashboard — reads the static Neon mirror.

Revenue = SUM(netamount) on non-cancelled bills (mirrors the alcanalyzer dashboard),
filtered by billdate range. Category revenue sums the per-test charge in bill_test_dtls.
All read-only; gate behind the caller's `can_view_sales` permission at the route.
"""
from __future__ import annotations

from datetime import date, timedelta
from typing import Any

from db import fetch_all, neon_conn

# per-test charge column in bill_test_dtls
_CHARGE = "COALESCE(d.charge, d.rate, 0)"


def _range(frm: str | None, to: str | None) -> tuple[str, str]:
    today = date.today()
    to_d = to or today.isoformat()
    frm_d = frm or (today - timedelta(days=30)).isoformat()
    return frm_d, to_d


def summary(frm: str | None = None, to: str | None = None) -> dict[str, Any]:
    frm, to = _range(frm, to)
    with neon_conn() as conn, conn.cursor() as cur:
        rows = fetch_all(
            cur,
            """
            SELECT COALESCE(SUM(netamount),0)::float AS revenue,
                   COUNT(*)::int AS bills,
                   COALESCE(SUM(netamount - COALESCE(receivedamount,0)),0)::float AS pending,
                   COUNT(DISTINCT right(regexp_replace(coalesce(phone,''),'\\D','','g'),10))::int AS patients
            FROM bill_head
            WHERE COALESCE(billcancel,0)=0 AND billdate::date BETWEEN %s::date AND %s::date
            """,
            (frm, to),
        )
    r = rows[0] if rows else {}
    rev = float(r.get("revenue") or 0)
    bills = int(r.get("bills") or 0)
    return {
        "from": frm, "to": to,
        "revenue": round(rev, 2),
        "bills": bills,
        "pending": round(float(r.get("pending") or 0), 2),
        "patients": int(r.get("patients") or 0),
        "avg_bill": round(rev / bills, 2) if bills else 0.0,
    }


def by_day(frm: str | None = None, to: str | None = None) -> list[dict]:
    frm, to = _range(frm, to)
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            """
            SELECT to_char(billdate::date,'YYYY-MM-DD') AS day,
                   COALESCE(SUM(netamount),0)::float AS revenue, COUNT(*)::int AS bills
            FROM bill_head
            WHERE COALESCE(billcancel,0)=0 AND billdate::date BETWEEN %s::date AND %s::date
            GROUP BY billdate::date ORDER BY billdate::date
            """,
            (frm, to),
        )


def by_doctor(frm: str | None = None, to: str | None = None, limit: int = 20) -> list[dict]:
    frm, to = _range(frm, to)
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            """
            SELECT COALESCE(NULLIF(TRIM(refrdoctor_name),''),'(walk-in / no referrer)') AS doctor,
                   COALESCE(SUM(netamount),0)::float AS revenue, COUNT(*)::int AS bills
            FROM bill_head
            WHERE COALESCE(billcancel,0)=0 AND billdate::date BETWEEN %s::date AND %s::date
            GROUP BY 1 ORDER BY revenue DESC LIMIT %s
            """,
            (frm, to, limit),
        )


def by_centre(frm: str | None = None, to: str | None = None) -> list[dict]:
    frm, to = _range(frm, to)
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            """
            SELECT COALESCE(NULLIF(TRIM(c.collcentrename),''),'(direct / walk-in)') AS centre,
                   COALESCE(SUM(b.netamount),0)::float AS revenue
            FROM bill_head b LEFT JOIN mast_collcentre c ON c.collcentre_key = b.collcentre_key
            WHERE COALESCE(b.billcancel,0)=0 AND b.billdate::date BETWEEN %s::date AND %s::date
            GROUP BY 1 ORDER BY revenue DESC LIMIT 25
            """,
            (frm, to),
        )


def by_category(frm: str | None = None, to: str | None = None) -> list[dict]:
    frm, to = _range(frm, to)
    with neon_conn() as conn, conn.cursor() as cur:
        return fetch_all(
            cur,
            f"""
            SELECT COALESCE(c.category,'(uncategorised)') AS category,
                   COALESCE(SUM({_CHARGE}),0)::float AS revenue, COUNT(*)::int AS orders
            FROM bill_test_dtls d
            JOIN bill_head b ON b.bill_key = d.bill_key AND COALESCE(b.billcancel,0)=0
            LEFT JOIN mast_test_category c ON c.category_key = d.category_key
            WHERE b.billdate::date BETWEEN %s::date AND %s::date
            GROUP BY 1 ORDER BY revenue DESC LIMIT 20
            """,
            (frm, to),
        )


def yoy() -> dict[str, Any]:
    """Year-over-year monthly revenue matrix for comparison charts."""
    with neon_conn() as conn, conn.cursor() as cur:
        rows = fetch_all(
            cur,
            """
            SELECT EXTRACT(YEAR FROM billdate)::int AS year,
                   EXTRACT(MONTH FROM billdate)::int AS month,
                   COALESCE(SUM(netamount),0)::float AS revenue
            FROM bill_head WHERE COALESCE(billcancel,0)=0
            GROUP BY 1,2 ORDER BY 1,2
            """,
        )
    years = sorted({r["year"] for r in rows})
    return {"years": years, "points": rows}
