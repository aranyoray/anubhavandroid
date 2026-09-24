"""Patient and staff access: tokens, bill-number tails, AKTIV-role gating, store fallback."""
import os
import sys
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

API_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(API_DIR))

import main  # noqa: E402
import patient_match  # noqa: E402
import staff_portal  # noqa: E402
import tokens  # noqa: E402

PHONE = "9830012345"


def _row(bill_no, phone=PHONE, name="RINA DAS", billdate="24/09/2026", key=1):
    return {"bill_key": key, "bill_no": bill_no, "phone": phone, "patientname": name, "billdate": billdate}


class _Env(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, {"AKTIV_TOKEN_SECRET": "test-secret", "AKTIV_API_KEY": ""})
        env.start()
        self.addCleanup(env.stop)
        self.client = TestClient(main.app)


class TokenTests(_Env):
    def test_round_trip_and_scoping(self):
        t = tokens.patient_token(PHONE)
        self.assertEqual(tokens.patient_phones(t), {PHONE})
        self.assertIsNone(tokens.staff_user_key(t))  # a patient token is not a staff token

    def test_tampered_and_expired_tokens_fail(self):
        t = tokens.patient_token(PHONE)
        body, sig = t.split(".")
        other = tokens.patient_token("9000000001").split(".")[0]
        self.assertEqual(tokens.patient_phones(f"{other}.{sig}"), set())
        old = tokens.issue("p", 10, now=1000, ph=PHONE)
        self.assertEqual(tokens.patient_phones(old), set())
        self.assertEqual(tokens.patient_phones("garbage"), set())

    def test_other_secret_rejects(self):
        t = tokens.patient_token(PHONE)
        with patch.dict(os.environ, {"AKTIV_TOKEN_SECRET": "different"}):
            self.assertEqual(tokens.patient_phones(t), set())


class BillTailTests(unittest.TestCase):
    def score(self, row, name="", phone=PHONE, serial="", bdate=None, month=None):
        pn = patient_match._norm_phone(phone)
        return patient_match._score_row(row, name, pn, bool(pn), serial, bdate, month)

    def test_phone_plus_last_digits_logs_in(self):
        self.assertEqual(self.score(_row("2026/09/ALC/4171"), serial="171"), 2)
        self.assertEqual(self.score(_row("2026/09/ALC/4171"), serial="4171"), 2)

    def test_tail_alone_never_matches_a_strangers_bill(self):
        # name + tail must not count: the tail only narrows the phone's own bills
        row = _row("2026/09/ALC/4171", phone="9000000000")
        self.assertEqual(self.score(row, name="RINA DAS", serial="171"), 1)

    def test_two_digit_tail_is_too_loose(self):
        self.assertEqual(self.score(_row("2026/09/ALC/4171"), serial="71"), 1)

    def test_full_bill_number_pins_the_month(self):
        pm = patient_match._bill_month("2026/08/ALC/4171")
        self.assertEqual(self.score(_row("2026/09/ALC/4171"), serial="4171", month=pm), 1)


class VerifyFallbackTests(_Env):
    def test_mirror_answers_when_clinic_db_is_down(self):
        with patch.object(patient_match, "_candidates_mssql", side_effect=OSError("down")), \
                patch.object(patient_match, "_candidates_neon", return_value=[_row("2026/09/ALC/4171")]):
            out = patient_match.verify_customer("", PHONE, "4171")
        self.assertTrue(out["matched"])
        self.assertEqual(out["source"], "mirror")

    def test_verify_endpoint_issues_scoped_token(self):
        with patch.object(main, "verify_customer",
                          return_value={"matched": True, "phone": PHONE, "patient_name": "X", "bills": []}):
            r = self.client.post("/api/customer/verify", json={"phone": PHONE, "bill_no": "4171"})
        self.assertEqual(tokens.patient_phones(r.json()["token"]), {PHONE})

    def test_no_token_when_no_match(self):
        with patch.object(main, "verify_customer",
                          return_value={"matched": False, "phone": PHONE, "patient_name": "", "bills": []}):
            r = self.client.post("/api/customer/verify", json={"phone": PHONE, "bill_no": "1"})
        self.assertNotIn("token", r.json())


class HistoryMergeTests(unittest.TestCase):
    def _neon(self):
        return ([{"bill_key": 1, "patient_name": "A", "raw_date": date(2024, 1, 5), "bill_no": "2024/01/ALC/5",
                  "tests": "CBC"},
                 {"bill_key": 2, "patient_name": "A", "raw_date": date.today(), "bill_no": "x/2", "tests": "TSH"}],
                [{"bill_key": 1, "category_key": 1, "report_key": 10}])

    def test_live_overlay_adds_new_bill_and_fresh_authorisation(self):
        live = ([{"bill_key": 2, "patient_name": "A", "raw_date": date.today(), "bill_no": "x/2", "tests": "TSH"},
                 {"bill_key": 3, "patient_name": "A", "raw_date": date.today(), "bill_no": "x/3", "tests": "LFT"}],
                [{"bill_key": 2, "category_key": 2, "report_key": 20}])
        with patch.object(patient_match, "_history_neon", return_value=self._neon()), \
                patch.object(patient_match, "_history_mssql", return_value=live) as ms:
            out = patient_match.customer_history(PHONE)
        self.assertEqual([v["bill_key"] for v in out["visits"]], [3, 2, 1])
        ready = {v["bill_key"]: v["ready"] for v in out["visits"]}
        self.assertEqual(ready, {3: False, 2: True, 1: True})
        self.assertEqual(ms.call_args.args[1], date.today() - timedelta(days=patient_match.LIVE_OVERLAY_DAYS))
        self.assertEqual(out["source"], "live+mirror")

    def test_mirror_alone_when_clinic_db_down(self):
        with patch.object(patient_match, "_history_neon", return_value=self._neon()), \
                patch.object(patient_match, "_history_mssql", side_effect=OSError("down")):
            out = patient_match.customer_history(PHONE)
        self.assertEqual(out["source"], "mirror")
        self.assertEqual(len(out["visits"]), 2)

    def test_live_alone_reads_everything_when_mirror_down(self):
        with patch.object(patient_match, "_history_neon", side_effect=OSError("down")), \
                patch.object(patient_match, "_history_mssql", return_value=([], [])) as ms:
            out = patient_match.customer_history(PHONE)
        self.assertIsNone(ms.call_args.args[1])  # no date floor: live covers the full history
        self.assertEqual(out["source"], "live")


class PatientGateTests(_Env):
    def test_history_requires_matching_token(self):
        with patch.object(main, "customer_history", return_value={"visits": []}) as hist:
            self.assertEqual(self.client.get("/api/customer/history", params={"phone": PHONE}).status_code, 401)
            other = tokens.patient_token("9000000001")
            r = self.client.get("/api/customer/history", params={"phone": PHONE}, headers={"X-Patient-Token": other})
            self.assertEqual(r.status_code, 403)
            mine = tokens.patient_token(PHONE)
            r = self.client.get("/api/customer/history", params={"phone": "+91 " + PHONE},
                                headers={"X-Patient-Token": mine})
            self.assertEqual(r.status_code, 200)
        hist.assert_called_once_with(PHONE)

    def test_report_pdf_requires_token(self):
        r = self.client.get("/api/customer/report-pdf", params={"bill_key": 1, "phone": PHONE})
        self.assertEqual(r.status_code, 401)

    def test_api_key_accepts_either_header_name(self):
        with patch.dict(os.environ, {"AKTIV_API_KEY": "k"}), \
                patch.object(main, "search_tests", return_value=[]):
            self.assertEqual(self.client.get("/api/tests").status_code, 401)
            self.assertEqual(self.client.get("/api/tests", headers={"X-API-Key": "k"}).status_code, 200)
            self.assertEqual(self.client.get("/api/tests", headers={"X-ALC-Key": "k"}).status_code, 200)


class StaffGateTests(_Env):
    def setUp(self):
        super().setUp()
        self.token = tokens.staff_token(42)

    def _perms(self, perms):
        return patch.object(main.roles, "load_user_permissions", return_value=perms)

    def test_booking_with_token_needs_can_book_and_is_stamped_to_user(self):
        body = {"patient_name": "A", "phone": PHONE, "test_keys": [1]}
        with self._perms({"can_book": False}), patch.object(main, "push_booking") as push:
            r = self.client.post("/api/bookings", json=body, headers={"X-Staff-Token": self.token})
        self.assertEqual(r.status_code, 403)
        push.assert_not_called()

        result = type("R", (), dict(bill_key=1, bill_no="b", bill_number="1", registration_no="r",
                                    apnt_key=1, net_amount=0.0))
        with self._perms({"can_book": True}), patch.object(main, "push_booking", return_value=result) as push:
            r = self.client.post("/api/bookings", json={**body, "sys_user_key": 1},
                                 headers={"X-Staff-Token": self.token})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(push.call_args.kwargs["sys_user_key"], 42)

    def test_edit_needs_billchange_rights(self):
        with self._perms({"can_edit_booking": False}), patch.object(main, "edit_bill") as edit:
            r = self.client.patch("/api/staff/bills/5", json={"phone": PHONE}, headers={"X-Staff-Token": self.token})
        self.assertEqual(r.status_code, 403)
        edit.assert_not_called()

    def test_edit_passes_user_and_only_given_fields(self):
        perms = {"can_edit_booking": True}
        with self._perms(perms), patch.object(main, "edit_bill", return_value={"bill_key": 5}) as edit:
            r = self.client.patch("/api/staff/bills/5", json={"phone": PHONE}, headers={"X-Staff-Token": self.token})
        self.assertEqual(r.status_code, 200)
        edit.assert_called_once_with(5, {"phone": PHONE}, user_key=42, perms=perms)

    def test_cancel_with_token(self):
        with self._perms({"can_cancel_booking": False}), patch.object(main, "cancel_booking") as cancel:
            r = self.client.post("/api/bookings/5/cancel", headers={"X-Staff-Token": self.token})
        self.assertEqual(r.status_code, 403)
        cancel.assert_not_called()
        with self._perms({"can_cancel_booking": True}), \
                patch.object(main, "cancel_booking", return_value={"success": True}) as cancel:
            r = self.client.post("/api/bookings/5/cancel", headers={"X-Staff-Token": self.token})
        self.assertEqual(r.status_code, 200)
        cancel.assert_called_once_with(5, sys_user_key=42)

    def test_modification_window(self):
        today = date(2026, 9, 24)
        staff_portal.check_edit_window(date(2026, 9, 22), {"modification_days": 3}, today)
        with self.assertRaises(PermissionError):
            staff_portal.check_edit_window(date(2026, 9, 1), {"modification_days": 3}, today)
        staff_portal.check_edit_window(date(2020, 1, 1), {"modification_days": 3, "is_admin": True}, today)
        staff_portal.check_edit_window(date(2020, 1, 1), {"modification_days": 0}, today)


if __name__ == "__main__":
    unittest.main()
