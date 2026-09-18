import sys
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch

API_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(API_DIR))

import aktiv_booking  # noqa: E402
import customer_portal  # noqa: E402
from aktiv_booking import TestLine as _Line  # noqa: E402


class _RecordingCursor:
    """Answers every SELECT with a canned row and remembers the SQL it was given."""

    def __init__(self, rows):
        self.rows = list(rows)
        self.executed = []

    def execute(self, sql, params=None):
        self.executed.append((" ".join(sql.split()), params))

    def fetchone(self):
        return self.rows.pop(0) if self.rows else None

    def fetchall(self):
        rows, self.rows = self.rows, []
        return rows

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class _FakeConn:
    def __init__(self, cursor):
        self._cursor = cursor

    def cursor(self):
        return self._cursor

    def commit(self):
        return None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class BillNumberTests(unittest.TestCase):
    """ALC serials must come from the live AKTIV table, not the batch-synced mirror,
    or an app booking reuses a number the front desk issued since the last sync."""

    def test_next_number_continues_from_the_live_max(self):
        cur = _RecordingCursor([(17,)])

        info = aktiv_booking._next_bill_number_on(cur, date(2026, 9, 18))

        self.assertEqual(info["bill_number"], "018")
        self.assertEqual(info["bill_no"], "2026/09/ALC/018")
        sql, params = cur.executed[0]
        self.assertIn("FROM BILL_HEAD", sql)
        self.assertEqual(params, ("2026/09", "ALC"))

    def test_first_bill_of_the_month_is_001(self):
        cur = _RecordingCursor([(None,)])
        self.assertEqual(aktiv_booking._next_bill_number_on(cur, date(2026, 10, 1))["bill_number"], "001")

    def test_preview_endpoint_reads_aktiv_not_neon(self):
        cur = _RecordingCursor([(4,)])
        with patch.object(aktiv_booking, "mssql_conn", return_value=_FakeConn(cur)), \
                patch.object(aktiv_booking, "neon_conn", side_effect=AssertionError("must not touch Neon")):
            info = aktiv_booking.next_bill_number(date(2026, 9, 18))
        self.assertEqual(info["bill_no"], "2026/09/ALC/005")


class PrebookTestResolutionTests(unittest.TestCase):
    """The advance must be computed on exactly the tests the app selected."""

    def setUp(self):
        self.lines = [
            _Line(test_key=1500, testcode="X", testname="LATE CATALOG TEST", rate=1000.0, category_key=1),
            _Line(test_key=2, testcode="CBC", testname="CBC", rate=350.0, category_key=1),
        ]
        self.patches = [
            patch.object(customer_portal, "_count_slot_bookings", return_value=0),
            patch.object(customer_portal, "_record_slot_booking"),
            patch.object(customer_portal, "aktiv_settings", return_value={"allow_live_bookings": True}),
        ]
        for p in self.patches:
            if p is not None:
                p.start()
                self.addCleanup(p.stop)

    def _slot_date(self):
        # A valid prebook day comfortably in the future.
        today = date.today()
        year, month = (today.year + (1 if today.month == 12 else 0), 1 if today.month == 12 else today.month + 1)
        return date(year, month, 10)

    def test_uses_exact_keys_and_full_total(self):
        with patch.object(customer_portal, "_resolve_tests", return_value=self.lines) as resolve, \
                patch.object(customer_portal, "push_booking") as push:
            push.return_value = type("R", (), {
                "bill_key": 9, "bill_no": "2026/10/ALC/001", "bill_number": "001",
                "registration_no": "26/1", "apnt_key": 3,
            })()
            result = customer_portal.create_customer_prebooking(
                patient_name="Priti Das", phone="9230755875", test_keys=[1500, 2],
                slot_date=self._slot_date(), time_slot="MORNING",
                payment_id="pay_1", amount_paid=675.0,
            )
        resolve.assert_called_once_with([1500, 2])
        self.assertEqual(result["total_amount"], 1350.0)
        self.assertEqual(result["balance_due"], 675.0)

    def test_short_advance_is_rejected_before_writing_to_aktiv(self):
        with patch.object(customer_portal, "_resolve_tests", return_value=self.lines), \
                patch.object(customer_portal, "push_booking") as push:
            with self.assertRaises(ValueError):
                customer_portal.create_customer_prebooking(
                    patient_name="Priti Das", phone="9230755875", test_keys=[1500, 2],
                    slot_date=self._slot_date(), time_slot="MORNING",
                    payment_id="pay_1", amount_paid=175.0,   # 50% of CBC alone
                )
        push.assert_not_called()

    def test_unknown_key_surfaces_as_a_client_error(self):
        with patch.object(customer_portal, "_resolve_tests", side_effect=ValueError("Unknown test_key(s): [99999]")), \
                patch.object(customer_portal, "push_booking") as push:
            with self.assertRaises(ValueError):
                customer_portal.create_customer_prebooking(
                    patient_name="Priti Das", phone="9230755875", test_keys=[99999],
                    slot_date=self._slot_date(), time_slot="MORNING",
                    payment_id="pay_1", amount_paid=500.0,
                )
        push.assert_not_called()


class CalendarTests(unittest.TestCase):
    def test_calendar_counts_every_cell_from_one_query(self):
        d1 = date(2030, 1, 10)
        cur = _RecordingCursor([(d1, "MORNING", 30), (d1, "EVENING", 2)])
        with patch.object(customer_portal, "_upcoming_prebook_dates", return_value=[d1, date(2030, 1, 20)]), \
                patch.object(customer_portal, "_ensure_slot_table"), \
                patch.object(customer_portal, "neon_conn", return_value=_FakeConn(cur)):
            cal = customer_portal.get_prebook_calendar(months_ahead=3)

        self.assertEqual(len(cur.executed), 1, "one grouped query, not one per date x slot")
        self.assertEqual(cur.executed[0][1], (d1, date(2030, 1, 20)))
        by_slot = {s["time_slot"]: s for s in cal["dates"][0]["slots"]}
        self.assertFalse(by_slot["MORNING"]["available"])
        self.assertEqual(by_slot["EVENING"]["remaining"], 28)
        self.assertTrue(by_slot["AFTERNOON"]["available"])
        # untouched dates still list every slot as free
        self.assertTrue(all(s["booked"] == 0 for s in cal["dates"][1]["slots"]))


if __name__ == "__main__":
    unittest.main()
