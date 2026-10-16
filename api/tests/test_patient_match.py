import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

API_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(API_DIR))

# Stub the DB/config layers so patient_match imports without a DB driver (psycopg2);
# the functions under test are pure and never touch the database.
if "db" not in sys.modules:
    _db = types.ModuleType("db")
    _db.fetch_all = lambda *a, **k: []
    _db.mssql_conn = None
    _db.neon_conn = None
    sys.modules["db"] = _db
if "config" not in sys.modules:
    _cfg = types.ModuleType("config")
    _cfg.load_env = lambda *a, **k: None
    sys.modules["config"] = _cfg

import patient_match as pm  # noqa: E402


def _row(bill_no, billdate, name="PRITI DAS", phone=""):
    return {"bill_no": bill_no, "billdate": billdate, "patientname": name, "phone": phone}


class BillMonthTests(unittest.TestCase):
    def test_bill_month_extracts_year_month(self):
        self.assertEqual(pm._bill_month("2024/01/ALC/003"), "2024/01")
        self.assertEqual(pm._bill_month("2024/12/ALC/117"), "2024/12")

    def test_bill_month_blank_for_malformed(self):
        self.assertEqual(pm._bill_month(""), "")
        self.assertEqual(pm._bill_month("ALC/3"), "")

    def test_bill_serial_strips_prefix_but_keeps_padding_for_suffix_match(self):
        self.assertEqual(pm._bill_serial("2024/01/ALC/003"), "003")
        self.assertEqual(pm._bill_serial("2024/02/ALC/3"), "3")


PHONE = "9230755875"


class AllThreeMatchTests(unittest.TestCase):
    """Phone exact, bill number (full or last 3-4 digits) and a fuzzy name must all match."""

    def matches(self, row, name="PRITI DAS", phone=PHONE, serial="4171", month=None):
        return pm._row_matches(row, name, pm._norm_phone(phone), serial, month)

    def test_full_and_three_or_four_digit_tail_match(self):
        row = _row("2026/09/ALC/4171", "20/09/2026", phone=PHONE)
        for serial in ("4171", "171"):
            with self.subTest(serial=serial):
                self.assertTrue(self.matches(row, serial=serial))

    def test_zero_prefixed_tail_matches_four_digit_serial(self):
        self.assertTrue(self.matches(_row("2026/09/ALC/1003", "20/09/2026", phone=PHONE), serial="003"))

    def test_two_digit_tail_is_too_loose(self):
        self.assertFalse(self.matches(_row("2026/09/ALC/4171", "20/09/2026", phone=PHONE), serial="71"))

    def test_phone_must_match_in_full(self):
        row = _row("2026/09/ALC/4171", "20/09/2026", phone=PHONE)
        self.assertFalse(self.matches(row, phone="9230755876"))

    def test_misspelt_or_partial_names_still_match(self):
        row = _row("2026/09/ALC/4171", "20/09/2026", name="PRITI SHARMA BANERJEE", phone=PHONE)
        for name in ("Priti Sharma Banerjee", "Preeti Sarma", "priti", "Priti Banerji", "Sharma Priti"):
            with self.subTest(name=name):
                self.assertTrue(self.matches(row, name=name))

    def test_a_different_name_does_not_match(self):
        row = _row("2026/09/ALC/4171", "20/09/2026", name="PRITI DAS", phone=PHONE)
        self.assertFalse(self.matches(row, name="Sumit Roy"))

    def test_full_bill_number_pins_the_month(self):
        # ALC serials restart monthly: 2024/01/ALC/003 must not open February's 003.
        feb = _row("2024/02/ALC/003", "15/02/2024", phone=PHONE)
        self.assertFalse(self.matches(feb, serial="003", month="2024/01"))
        self.assertTrue(self.matches(feb, serial="003", month="2024/02"))


class VerifyInputTests(unittest.TestCase):
    def verify(self, name="Priti Das", phone=PHONE, bill="171", rows=()):
        with patch.object(pm, "_candidates_mssql", return_value=list(rows)):
            return pm.verify_customer(name, phone, bill)

    def test_all_three_fields_are_required(self):
        for kwargs in ({"name": ""}, {"phone": ""}, {"bill": ""}):
            with self.subTest(**kwargs), self.assertRaises(ValueError):
                self.verify(**kwargs)

    def test_phone_needs_all_ten_digits(self):
        with self.assertRaises(ValueError):
            self.verify(phone="923075587")

    def test_bill_needs_at_least_three_digits(self):
        with self.assertRaises(ValueError):
            self.verify(bill="71")

    def test_verify_returns_report_for_name_phone_and_three_digit_tail(self):
        row = {**_row("2026/09/ALC/1003", "20/09/2026", phone=PHONE), "bill_key": 42}
        result = self.verify(name="Preeti Das", bill="003", rows=[row])
        self.assertTrue(result["matched"])
        self.assertEqual(result["phone"], PHONE)
        self.assertEqual(result["bills"][0]["bill_no"], "2026/09/ALC/1003")

    def test_no_match_returns_no_phone(self):
        row = {**_row("2026/09/ALC/1003", "20/09/2026", phone=PHONE), "bill_key": 42}
        result = self.verify(name="Sumit Roy", bill="003", rows=[row])
        self.assertFalse(result["matched"])
        self.assertEqual(result["phone"], "")


if __name__ == "__main__":
    unittest.main()
