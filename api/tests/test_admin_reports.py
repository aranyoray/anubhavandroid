import os
import sys
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

API_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(API_DIR))

import admin_reports  # noqa: E402
import config  # noqa: E402
import main  # noqa: E402


class _Cursor:
    """Returns a canned result set per SQL fragment it recognises, and records params."""

    def __init__(self, answers: dict):
        self.answers = answers
        self.executed = []
        self._rows = []

    def execute(self, sql, params=None):
        flat = " ".join(sql.split())
        self.executed.append((flat, params))
        self._rows = []
        for needle, rows in self.answers.items():
            if needle in flat:
                self._rows = rows
                break

    @property
    def description(self):
        if not self._rows:
            return [("col",)]
        return [(k,) for k in self._rows[0].keys()]

    def fetchall(self):
        return [tuple(r.values()) for r in self._rows]

    def __enter__(self):
        return self

    def __exit__(self, *_a):
        return False


class _Conn:
    def __init__(self, cursor):
        self._cursor = cursor

    def cursor(self):
        return self._cursor

    def __enter__(self):
        return self

    def __exit__(self, *_a):
        return False


def _fake_conn(answers):
    from contextlib import contextmanager

    @contextmanager
    def cm():
        yield _Conn(_Cursor(answers))

    return cm()


class FormattingTests(unittest.TestCase):
    def test_indian_grouping(self):
        self.assertEqual(admin_reports._num(0), "0")
        self.assertEqual(admin_reports._num(1234567), "12,34,567")
        self.assertEqual(admin_reports._inr(1234567), "₹12,34,567")
        self.assertEqual(admin_reports._inr(0), "₹0")
        self.assertEqual(admin_reports._inr(999), "₹999")


class RangeTests(unittest.TestCase):
    def test_swaps_reversed_range(self):
        a, b, note = admin_reports._range("2026-09-18", "2026-09-01")
        self.assertEqual((a.isoformat(), b.isoformat()), ("2026-09-01", "2026-09-18"))
        self.assertIsNone(note)

    def test_clamps_absurd_span_and_notes_it(self):
        a, b, note = admin_reports._range("2000-01-01", "2026-09-18")
        self.assertEqual((b - a).days, admin_reports.MAX_SPAN_DAYS)
        self.assertIsNotNone(note)

    def test_bad_date_rejected(self):
        with self.assertRaises(ValueError):
            admin_reports._range("not-a-date", None)

    def test_end_bound_is_day_after(self):
        lo, hi = admin_reports._bounds(date(2026, 9, 18), date(2026, 9, 18))
        self.assertEqual((hi - lo).days, 1)


class ReportShapeTests(unittest.TestCase):
    def test_income_totals_and_bill_toggle(self):
        answers = {
            "GROUP BY convert(varchar(10), b.BILLDATE, 23)": [
                {"day": "2026-09-18", "cases": 2, "net": 900.0, "received": 500.0},
            ],
            "SELECT count(*) AS n FROM BILL_HEAD": [{"n": 2}],
            "SELECT TOP 500": [
                {"bill_no": "2026/09/ALC/001", "day": "2026-09-18", "patient": "A", "net": 400.0, "received": 400.0},
                {"bill_no": "2026/09/ALC/002", "day": "2026-09-18", "patient": "B", "net": 500.0, "received": 100.0},
            ],
        }
        with patch.object(admin_reports, "mssql_conn", lambda: _fake_conn(answers)):
            out = admin_reports.build_report("income", "2026-09-18", "2026-09-18", bill_details=True)
        due = {i["label"]: i["value"] for i in out["summary"]}
        self.assertEqual(due["Bill value"], "₹900")
        self.assertEqual(due["Received"], "₹500")
        self.assertEqual(due["Due"], "₹400")
        titles = [s["title"] for s in out["sections"]]
        self.assertIn("By day", titles)
        self.assertIn("Bills", titles)

    def test_income_without_bill_details_omits_bill_list(self):
        answers = {"GROUP BY convert(varchar(10), b.BILLDATE, 23)": []}
        with patch.object(admin_reports, "mssql_conn", lambda: _fake_conn(answers)):
            out = admin_reports.build_report("income", None, None, bill_details=False)
        self.assertEqual([s["title"] for s in out["sections"]], ["By day"])

    def test_tests_maps_modalities_and_test_detail_toggle(self):
        answers = {
            "GROUP BY case": [
                {"modality": "USG", "tests": 10, "charge": 20000.0},
                {"modality": "PATH", "tests": 5, "charge": 2500.0},
            ],
            "GROUP BY t.TESTNAME": [{"test": "USG WHOLE ABDOMEN", "tests": 7, "charge": 14000.0}],
        }
        with patch.object(admin_reports, "mssql_conn", lambda: _fake_conn(answers)):
            out = admin_reports.build_report("tests", None, None, test_details=True)
        by_mod = next(s for s in out["sections"] if s["title"] == "By modality")
        self.assertIn(["USG", "10", "₹20,000"], by_mod["rows"])
        self.assertIn(["Pathology", "5", "₹2,500"], by_mod["rows"])
        self.assertTrue(any(s["title"] == "Tests" for s in out["sections"]))

    def test_due_lists_only_outstanding(self):
        answers = {
            "sum(isnull(b.NETAMOUNT, 0) - isnull(b.RECEIVEDAMOUNT, 0)) AS due": [{"bills": 1, "due": 400.0}],
            "SELECT count(*) AS n FROM BILL_HEAD": [{"n": 1}],
            "SELECT TOP 500": [
                {"bill_no": "2026/09/ALC/002", "day": "2026-09-18", "patient": "B", "net": 500.0, "received": 100.0},
            ],
        }
        with patch.object(admin_reports, "mssql_conn", lambda: _fake_conn(answers)):
            out = admin_reports.build_report("due", None, None, bill_details=True)
        self.assertEqual({i["label"]: i["value"] for i in out["summary"]}["Total due"], "₹400")
        self.assertEqual(out["sections"][0]["title"], "Due bills")

    def test_cc_groups_by_centre(self):
        answers = {
            "FROM BILL_HEAD b LEFT JOIN MAST_COLLCENTRE": [
                {"centre": "ANUBHAV LIFE CARE", "cases": 12, "net": 30000.0},
                {"centre": "", "cases": 3, "net": 4000.0},
            ]
        }
        with patch.object(admin_reports, "mssql_conn", lambda: _fake_conn(answers)):
            out = admin_reports.build_report("cc", None, None)
        rows = out["sections"][0]["rows"]
        self.assertIn(["ANUBHAV LIFE CARE", "12", "₹30,000"], rows)
        self.assertIn(["(walk-in)", "3", "₹4,000"], rows)

    def test_unknown_report_rejected(self):
        with self.assertRaises(ValueError):
            admin_reports.build_report("nonsense", None, None)


class EndpointTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)

    def test_check_rejects_wrong_password(self):
        with patch.object(main, "admin_password", return_value="nabllab"):
            self.assertEqual(self.client.get("/api/admin/check", params={"password": "x"}).status_code, 401)
            self.assertEqual(self.client.get("/api/admin/check", params={"password": "nabllab"}).status_code, 200)

    def test_report_requires_password(self):
        with patch.object(main, "admin_password", return_value="nabllab"):
            r = self.client.get("/api/admin/report", params={"password": "wrong", "report": "income"})
        self.assertEqual(r.status_code, 401)

    def test_report_passes_flags_through(self):
        expected = {"report": "tests", "title": "Tests", "summary": [], "sections": []}
        with patch.object(main, "admin_password", return_value="nabllab"), \
                patch.object(main, "build_report", return_value=expected) as build:
            r = self.client.get(
                "/api/admin/report",
                params={"password": "nabllab", "report": "tests", "start": "2026-09-01",
                        "end": "2026-09-18", "bill_details": "false", "test_details": "true"},
            )
        self.assertEqual(r.status_code, 200)
        build.assert_called_once_with("tests", "2026-09-01", "2026-09-18", False, True)

    def test_report_rejects_unknown_type(self):
        with patch.object(main, "admin_password", return_value="nabllab"):
            r = self.client.get("/api/admin/report", params={"password": "nabllab", "report": "bogus"})
        self.assertEqual(r.status_code, 400)

    def test_admin_password_default_is_the_shared_value(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(config.admin_password(), "nabllab")


if __name__ == "__main__":
    unittest.main()
